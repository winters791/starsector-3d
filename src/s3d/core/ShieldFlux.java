package s3d.core;

import s3d.math.Vec3;

/**
 * One ship's flux pool, shield, venting and overload, following vanilla's rules:
 * weapons and shield upkeep make soft flux, shield hits make hard flux (beam hits on shields make
 * soft flux), dissipation clears soft flux first and hard flux only while the shield is down,
 * venting dumps everything at double rate but leaves the ship defenceless, and a shield hit that
 * would go past capacity overloads the ship (no shield, no weapons) for a few seconds.
 *
 * 3D shield: a cap of a sphere around the shield centre. The vanilla arc keeps its share of
 * directions covered (coverage-preserving): a 2D arc covering arc/360 of the circle becomes a cone
 * covering arc/360 of the sphere, so 90 -> 60 deg half-angle, 180 -> a half sphere, 360 -> a bubble.
 */
public final class ShieldFlux {
	public enum Type { NONE, FRONT, OMNI }

	// ---- spec (from the live ship) ----
	public Type type = Type.NONE;
	public float maxFlux, dissipation;
	/** Set by the ship's systems each frame: dissipation multiplier (Incursion Mode x3). */
	public float dissipationMult = 1f;
	/** Set by the ship's systems each frame: the shield's whole arc in degrees (-1 = its own) and a forced facing, ship-local (null = its own). Darkened Gaze. */
	public float arcOverrideDeg = -1f;
	public Vec3 facingOverride;
	/** Vanilla arc in degrees, and the 3D cone half-angle it maps to (radians). */
	public float arcDeg, halfAngleMax;
	/** Shield radius and centre in the ship's unscaled local frame. */
	public float radius;
	public final Vec3 centre = new Vec3();
	/** Flux per point of shield damage (after the damage-type multiplier). */
	public float fluxPerDamage = 1f;
	/** Soft flux per second while the shield is up. */
	public float upkeep;
	/** Seconds to raise the shield fully. */
	public float unfoldTime = 0.5f;
	/** Omni shield turn rate, rad/s. */
	public float turnRate = (float) Math.toRadians(120);
	/** Fraction of dissipation that also drains hard flux while the shield is up (usually 0). */
	public float hardDissipationFrac;
	public float ventRateMult = 1f, overloadTimeMult = 1f;
	public float zeroFluxBoost = 50f, zeroFluxMinLevel;
	public float[] innerColor = {0.3f, 0.6f, 1f}, ringColor = {0.6f, 0.85f, 1f};

	// ---- state ----
	public float flux, hardFlux;
	/** Pilot wants the shield up. */
	public boolean shieldOn;
	/** 0..1 how far the shield has unfolded. */
	public float unfold;
	/** Shield axis, ship-local unit vector (-Z = nose). */
	public final Vec3 facing = new Vec3(0f, 0f, -1f);
	public float overloadLeft;
	public boolean venting;

	// Recent shield hits, for the ripple effect (ship-local directions, age in seconds).
	public static final int HITS = 4;
	public final Vec3[] hitDir = {new Vec3(), new Vec3(), new Vec3(), new Vec3()};
	public final float[] hitAge = {9f, 9f, 9f, 9f};
	public final float[] hitSize = new float[HITS];
	private int hitNext;

	/** Cone half-angle (radians) that covers the same share of the sphere as arcDeg covers of the circle. */
	public static float coverageHalfAngle(float arcDeg) {
		float f = Math.max(0f, Math.min(1f, arcDeg / 360f));
		return (float) Math.acos(1f - 2f * f);
	}

	public boolean hasShield() {
		return type != Type.NONE && radius > 0f && halfAngleMax > 0f;
	}

	public float level() {
		return maxFlux > 0f ? flux / maxFlux : 0f;
	}

	public boolean overloaded() {
		return overloadLeft > 0f;
	}

	/** Overloaded or venting: no shield and no weapons. */
	public boolean disabled() {
		return overloadLeft > 0f || venting;
	}

	/** The shield is (at least partly) up and blocking. */
	public boolean shieldUp() {
		return hasShield() && unfold > 0.02f && !disabled() && !blocked;
	}

	public float halfAngle() {
		float max = arcOverrideDeg >= 0f ? (float) Math.toRadians(arcOverrideDeg * 0.5f) : halfAngleMax;
		return max * unfold;
	}

	public boolean zeroFluxBoostActive() {
		return maxFlux > 0f && !disabled() && flux <= maxFlux * zeroFluxMinLevel + 0.5f;
	}

	public void toggleShield() {
		if (hasShield() && !disabled()) shieldOn = !shieldOn;
	}

	/** False for ships whose vent rate is zero (Safety Overrides multiplies it by 0): vanilla won't let them vent. */
	public boolean canVent() {
		return maxFlux > 0f && ventRateMult > 0f && dissipation > 0f;
	}

	public void vent() {
		if (disabled() || flux <= 0.5f || !canVent()) return;
		venting = true;
		shieldOn = false;
	}

	/**
	 * Flux a ship system costs (soft, or hard for systems vanilla marks so). False, nothing added, if
	 * it would go past capacity: systems never overload a ship.
	 */
	public boolean addSystemFlux(float amount, boolean hard) {
		if (maxFlux <= 0f || amount <= 0f) return true;
		if (flux + amount > maxFlux) return false;
		flux += amount;
		if (hard) hardFlux += amount;
		return true;
	}

	/** Set by the ship's systems each frame: no dissipation (some systems), no hard flux dissipation (phase cloak), shield forced up / blocked. */
	public boolean noDissipation, noHardDissipation, forceUp, blocked;

	/** Spends soft flux for a shot. False (nothing spent) if it would exceed capacity, or while overloaded/venting. */
	public boolean spend(float soft) {
		if (disabled()) return false;
		if (maxFlux <= 0f || soft <= 0f) return true;
		if (flux + soft > maxFlux) return false;
		flux += soft;
		return true;
	}

	/**
	 * Shield takes a hit worth shieldDamage (damage x type multiplier). Hard flux, or soft for beams.
	 * Going past capacity overloads the ship; the longer the overflow, the longer the overload.
	 * Returns the flux added.
	 */
	public float absorb(float shieldDamage, boolean soft, Vec3 localDir, Tuning t) {
		float f = shieldDamage * fluxPerDamage;
		if (localDir != null) {
			hitDir[hitNext].set(localDir);
			hitAge[hitNext] = 0f;
			hitSize[hitNext] = Math.min(0.8f, 0.2f + f / Math.max(1f, maxFlux) * 12f);
			hitNext = (hitNext + 1) % HITS;
		}
		if (maxFlux <= 0f) return f;
		flux += f;
		if (!soft) hardFlux += f;
		if (flux > maxFlux) {
			float over = flux - maxFlux;
			flux = maxFlux;
			hardFlux = Math.min(hardFlux, flux);
			float k = Math.min(1f, over / Math.max(1f, maxFlux * 0.1f));
			overloadLeft = (t.overloadBaseTime + (t.overloadMaxTime - t.overloadBaseTime) * k) * overloadTimeMult;
			shieldOn = false;
			venting = false;
		}
		return f;
	}

	/**
	 * Per frame: overload timer, shield unfold and upkeep, omni facing (toward desiredLocal, a
	 * ship-local direction, or unchanged if null), dissipation or venting.
	 */
	public void step(float dt, Vec3 desiredLocal, Tuning t) {
		if (dt <= 0f) return;
		for (int i = 0; i < HITS; i++) hitAge[i] += dt;
		if (overloadLeft > 0f) {
			overloadLeft = Math.max(0f, overloadLeft - dt);
			shieldOn = false;
		}
		boolean wantUp = (shieldOn || forceUp) && !blocked && hasShield() && !disabled();
		float unfoldRate = 1f / Math.max(0.05f, unfoldTime);
		unfold = wantUp ? Math.min(1f, unfold + unfoldRate * dt) : Math.max(0f, unfold - unfoldRate * 2f * dt);
		if (wantUp && maxFlux > 0f) flux = Math.min(maxFlux, flux + upkeep * dt);

		if (facingOverride != null) {
			facing.set(facingOverride).normalize();
		} else if (type == Type.FRONT) {
			facing.set(0f, 0f, -1f);
		} else if (type == Type.OMNI && desiredLocal != null && desiredLocal.lengthSq() > 1e-6f) {
			Combat.rotateToward(facing, new Vec3(desiredLocal).normalize(), turnRate * dt);
		}

		if (maxFlux <= 0f || noDissipation) return;
		if (venting) {
			float d = dissipation * t.ventRateMult * ventRateMult * dt;
			flux = Math.max(0f, flux - d);
			hardFlux = Math.min(hardFlux, flux);
			if (flux <= 0.5f) {
				flux = hardFlux = 0f;
				venting = false;
			}
			return;
		}
		// Soft flux first; hard flux only with the shield down (or the hullmod/skill fraction while up),
		// and not at all while a system blocks it (phased).
		float d = dissipation * dissipationMult * dt;
		float soft = flux - hardFlux;
		float fromSoft = Math.min(soft, d);
		flux -= fromSoft;
		float hardBudget = noHardDissipation ? 0f : shieldUp() ? dissipation * dt * hardDissipationFrac : d - fromSoft;
		float fromHard = Math.min(hardFlux, Math.max(0f, hardBudget));
		hardFlux -= fromHard;
		flux -= fromHard;
		flux = Math.max(flux, hardFlux);
	}

	/** Knocked into overload for this long (Quantum Disruptor), unless already overloaded or venting. */
	public void disrupt(float seconds) {
		if (disabled()) return;
		overloadLeft = Math.max(overloadLeft, seconds * overloadTimeMult);
		shieldOn = false;
		venting = false;
	}

	public void reset() {
		flux = hardFlux = 0f;
		overloadLeft = 0f;
		venting = false;
		unfold = shieldOn ? 1f : 0f;
		for (int i = 0; i < HITS; i++) hitAge[i] = 9f;
	}

	private final Vec3 segCentre = new Vec3(), segLocal = new Vec3();

	/**
	 * Where segment a->b first hits this ship's raised shield from outside, as a 0..1 parameter, or
	 * -1. Segments that start inside the shield sphere, or enter outside the arc, aren't blocked.
	 * Runs for every shot near a raised shield every frame, so it works in scratch vectors.
	 */
	public float segmentHit(Ship3D s, Vec3 a, Vec3 b, Vec3 hitOut, Vec3 localDirOut) {
		if (!shieldUp()) return -1f;
		Vec3 c = s.rot.rotate(centre.x * s.scale, centre.y * s.scale, centre.z * s.scale, segCentre).add(s.pos);
		float r = radius * s.scale;
		if (segLocal.set(a).sub(c).lengthSq() <= r * r) return -1f;
		float t = Combat.segmentSphere(a, b, c, r);
		if (t < 0f) return -1f;
		hitOut.set(b).sub(a).scale(t).add(a);
		Vec3 local = s.rot.inverseRotate(segLocal.set(hitOut).sub(c), segLocal).scale(1f / r);
		if (local.dot(facing) < (float) Math.cos(halfAngle())) return -1f;
		if (localDirOut != null) localDirOut.set(local);
		return t;
	}
}
