package s3d.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import s3d.math.Vec3;

/**
 * Weapons, projectiles and damage. Rules follow vanilla where they translate directly:
 * damage-type multipliers, armor damage reduction (hit strength vs armor, capped), minimum armor,
 * burst/charge/cooldown/spread timing, projectiles inheriting the firing ship's velocity.
 * 3D-specific: weapons fire from their slot, range is a sphere around the mount, turrets turn
 * within a cone, and a ship's own shots pass through its own hull.
 */
public final class Combat {
	/** Vanilla settings (host overrides from settings.json). */
	public float maxArmorDamageReduction = 0.85f, minArmorFraction = 0.05f;
	/** Beams: armor hit strength = damage per second times this (vanilla dpsToHitStrengthMult). */
	public float dpsToHitStrengthMult = 0.5f;

	/** Ships beams can hit this frame (set by the sim). */
	public List<Ship3D> world = new ArrayList<>();
	public final List<Projectile> projectiles = new ArrayList<>();
	public final List<Missile3D> missiles = new ArrayList<>();
	/** Missile target and aim point (world; may be null) for the shot being fired (set per stepMount call). */
	private Ship3D pendingTarget;
	private Vec3 pendingAim;
	public final List<Effect> effects = new ArrayList<>();
	/** Camera shake earned this frame by numbersFor (you): hits, nearby blasts, your own heavy guns. */
	public float shake;
	/** Sounds for the host to play (filled during the step). */
	public final SoundEvents sounds = new SoundEvents();
	/** Floating damage numbers only for hits this ship deals or takes (null = all hits). */
	public Ship3D numbersFor;
	/**
	 * In game: numbers only for hits numbersFor deals, merged into one number per target every
	 * MERGE_SECONDS (hull red, armor amber, shield-only blue). Off: one number per hit (tests).
	 */
	public boolean mergeNumbers;
	private static final float MERGE_SECONDS = 0.5f;
	/** Sim clock of numbersFor's (your) latest hit on an enemy's hull/armor, on a shield, and latest kill: HUD hit markers. */
	public float lastHullHitAt = -1e9f, lastShieldHitAt = -1e9f, lastKillAt = -1e9f;
	/** Whether that kill was a ship (big marker) rather than a fighter. */
	public boolean lastKillShip;
	/** Latest hull/armor damage numbersFor (you) took: when, from where (the attacker, else the hit), how much (HUD damage direction). */
	public float hurtAt = -1e9f, hurtAmount;
	public final Vec3 hurtFrom = new Vec3();
	/** Wrecks still burning: {seconds left, flame budget}. */
	private final java.util.Map<Ship3D, float[]> burning = new java.util.IdentityHashMap<>();

	private static final class Tally {
		float sum, age;
		boolean hull, armor;
		final Vec3 at = new Vec3(), vel = new Vec3();
	}

	private final java.util.Map<Ship3D, Tally> tallies = new java.util.IdentityHashMap<>();
	/** Set by the sim each frame: tuning (overload times) and the sim clock (threat markers). */
	public Tuning tuning = new Tuning();
	public float clock;
	private final Random rnd = new Random();
	private final Vec3 tA = new Vec3(), tB = new Vec3(), tC = new Vec3();

	/** Fixes the random rolls (arcs, MIRV split ranges, ion pierce...) so a benchmark battle repeats. */
	public void seed(long seed) {
		rnd.setSeed(seed);
	}

	/** Omega / Threat / Dweller weapon scripts, flare launchers and missile blasts (weapon scripts batch 2). */
	public final ExoticWeapons exotic = new ExoticWeapons(this);
	/** Wings and ships that systems and weapons put into the battle; the host spawns them (see SpawnRequest). */
	public final List<SpawnRequest> spawnRequests = new ArrayList<>();

	float rand() {
		return rnd.nextFloat();
	}

	// ---- weapons ---------------------------------------------------------------------------

	/**
	 * Turns every mount toward aimPoint (world; null = back to rest) and fires the ready ones while
	 * the trigger is held.
	 */
	public void stepWeapons(Ship3D s, Vec3 aimPoint, boolean trigger, float dt, Tuning t) {
		for (WeaponMount m : s.weapons) stepMount(s, m, aimPoint, trigger, dt, t);
	}

	/** One mount: turn toward aimPoint (world; null = rest) and fire while trigger is held and it's ready. */
	public void stepMount(Ship3D s, WeaponMount m, Vec3 aimPoint, boolean trigger, float dt, Tuning t) {
		stepMount(s, m, aimPoint, trigger, null, dt, t);
	}

	/** As above; missiles launched this frame home on missileTarget (null = fly straight). */
	public void stepMount(Ship3D s, WeaponMount m, Vec3 aimPoint, boolean trigger, Ship3D missileTarget, float dt, Tuning t) {
		m.firedThisFrame = false;
		if (dt <= 0f) return;
		if (s.destroyed) {
			m.beamLevel = 0f;
			m.beamFiring = false;
			m.beamHitting = false;
			return;
		}
		if (m.disabledFor > 0f) {
			// Knocked out by EMP: no firing, no turning, beams wind down.
			m.beamFiring = false;
			m.beamHitting = false;
			m.beamLevel = Math.max(0f, m.beamLevel - dt * 4f);
			m.burstShotsLeft = 0;
			m.chargeLeft = -1f;
			return;
		}
		{
			WeaponSpec3D w = m.spec;
			if (m.flash > 0f) m.flash -= dt;
			if (!w.supported) return;
			if (w.maxAmmo > 0) m.ammo = Math.min(w.maxAmmo, m.ammo + w.ammoPerSecond * dt * (w.kind != WeaponSpec3D.Kind.MISSILE ? s.sysAmmoRegenMult : 1f)); // Incursion Mode: x5 for guns
			m.spread = Math.max(w.minSpread, m.spread - w.spreadDecay * dt);
			boolean ballistic = "BALLISTIC".equals(w.type);
			boolean lidarGun = lidarGun(s, m);
			float rof = (ballistic ? s.sysBallisticRofMult : 1f) * (lidarGun ? s.sysLidarRofMult : 1f);
			if (m.cooldownLeft > 0f) m.cooldownLeft -= dt * rof;
			if (s.sysNoFire || s.phased) trigger = false; // a system forbids firing, or phased
			if (s.sysFragmentVolley && !fragmentWeapon(w) && !w.pdOnly) trigger = false; // Fragment Volley: only fragment weapons fire
			if (s.sysLidar > 0 && !lidarGun && w.kind != WeaponSpec3D.Kind.MISSILE) trigger = false; // Lidar Array: its guns only (missiles exempt)
			if (s.flux != null && s.flux.disabled()) trigger = false; // overloaded or venting
			if (trigger && !exotic.mayFire(s, m)) trigger = false; // too few fragments, too many Hungering Rifts out

			aim(s, m, aimPoint, dt);
			if (w.kind == WeaponSpec3D.Kind.BEAM) {
				stepBeam(s, m, trigger, dt, t);
				return;
			}
			pendingTarget = missileTarget;
			pendingAim = aimPoint;

			if (m.burstShotsLeft == 0 && m.chargeLeft < 0f && trigger && m.ready()) {
				if (w.chargeTime > 0f) {
					m.chargeLeft = w.chargeTime;
				} else {
					m.burstShotsLeft = Math.max(1, w.burstSize);
					m.burstLeftTime = 0f;
				}
			}
			if (m.chargeLeft >= 0f) {
				m.chargeLeft -= dt;
				if (m.chargeLeft < 0f) {
					m.chargeLeft = -1f;
					m.burstShotsLeft = Math.max(1, w.burstSize);
					m.burstLeftTime = 0f;
				}
			}
			if (m.burstShotsLeft > 0) {
				m.burstLeftTime -= dt * rof;
				while (m.burstShotsLeft > 0 && m.burstLeftTime <= 0f) {
					if (w.maxAmmo > 0 && m.ammo < 1f || !exotic.mayFire(s, m)
							|| s.flux != null && !s.flux.spend(w.fluxPerShot * (ballistic ? s.sysBallisticFluxMult : 1f))) {
						m.burstShotsLeft = 0; // out of ammo or fragments, or the shot would overflow flux
						break;
					}
					fire(s, m, t);
					m.burstShotsLeft--;
					m.burstLeftTime += Math.max(0.01f, w.burstDelay);
				}
				if (m.burstShotsLeft == 0) m.cooldownLeft = w.cooldown;
			}
		}
	}

	/** True if a world point lies inside the mount's firing cone and within its range (sphere around the mount). */
	public static boolean canReach(Ship3D s, WeaponMount m, Vec3 point, Tuning t) {
		return canReach(s, m, point, 0f, t);
	}

	/**
	 * As above, with the point allowed `nearer` beyond the range: aiming at a big structure's
	 * centre, the shot meets its near side that much sooner.
	 */
	public static boolean canReach(Ship3D s, WeaponMount m, Vec3 point, float nearer, Tuning t) {
		return canReach(s, m, m.worldPos(s, new Vec3()), point, nearer, 0f, t, new Vec3());
	}

	/**
	 * As above, for a mount whose world position the caller already has; tmp is scratch. Autofire
	 * asks this for every mount against every candidate target each frame, so it mustn't allocate
	 * (its two temporary vectors were most of the sim's garbage: a young GC every half second).
	 * With padRadius > 0 the point only has to be within that distance of the arc (a ship is hit
	 * anywhere on its hull, not just at its centre; see {@link Sim#arcPad}).
	 */
	public static boolean canReach(Ship3D s, WeaponMount m, Vec3 mountWorld, Vec3 point, float nearer, float padRadius, Tuning t, Vec3 tmp) {
		Vec3 d = tmp.set(point).sub(mountWorld);
		float dist = d.length();
		if (dist - nearer > m.spec.range * t.weaponRangeMult * rangeMult(s, m)) return false;
		if (dist < 1e-3f) return true;
		if (m.spec.kind == WeaponSpec3D.Kind.MISSILE && m.spec.guided) return true; // launched along the barrel, then steer
		s.rot.inverseRotate(d, d).scale(1f / dist);
		float c = d.dot(m.restDir);
		if (c >= m.cosHalfArc - 1e-4f) return true;
		if (padRadius <= 0f) return false;
		// Widen the arc by the pad's angle p (sin p = pad / dist), without trig: cos(arc + p).
		float sp = Math.min(1f, padRadius / dist);
		if (m.halfArcRad >= (float) Math.PI * 0.5f && sp >= m.sinHalfArc) return true; // arc + p reaches all the way round
		return c >= m.cosHalfArc * (float) Math.sqrt(1f - sp * sp) - m.sinHalfArc * sp - 1e-4f;
	}

	/** A gun the Lidar Array boosts (and the only kind besides missiles it lets fire): hardpoint, ballistic or energy, not a beam. */
	/** A system's range bonus for this mount: Lidar Array on its hardpoint guns, Fragment Volley x2 on fragment weapons. */
	static float rangeMult(Ship3D s, WeaponMount m) {
		return (lidarGun(s, m) ? s.sysLidarRangeMult : 1f) * (fragmentWeapon(m.spec) ? s.sysFragmentRangeMult : 1f);
	}

	/** A Threat fragment weapon fired by hand (not point defence): what Fragment Volley drives. */
	public static boolean fragmentWeapon(WeaponSpec3D w) {
		return w.fragmentsNeeded > 0 && !w.pdOnly && w.launchWing == null;
	}

	static boolean lidarGun(Ship3D s, WeaponMount m) {
		WeaponSpec3D w = m.spec;
		return s.sysLidar == 2 && m.hardpoint && w.kind == WeaponSpec3D.Kind.PROJECTILE && ("BALLISTIC".equals(w.type) || "ENERGY".equals(w.type));
	}

	/**
	 * Distance along the ray to the first real hull surface (not bounding sphere) of any ship except
	 * `skip`, or -1. Used so weapons converge on the hull under the reticle.
	 */
	/**
	 * A ship's own shots pass through its own hull, and through every part of its structure: a
	 * station's sections fire through the core and each other harmlessly, as in vanilla.
	 */
	static boolean sameStructure(Ship3D a, Ship3D b) {
		return a == b || a != null && b != null && (a.parent != null || b.parent != null) && a.root() == b.root();
	}

	public float raycast(List<Ship3D> ships, Vec3 origin, Vec3 dir, float maxDist, Ship3D skip) {
		Vec3 hit = new Vec3();
		Ship3D s = rayHit(ships, origin, dir, maxDist, skip, hit, new Vec3());
		return s == null ? -1f : hit.distance(origin);
	}

	/** First ship hull hit along a ray; writes the world hit point and the local just-outside point. */
	public Ship3D rayHit(List<Ship3D> ships, Vec3 origin, Vec3 dir, float maxDist, Ship3D skip, Vec3 hitOut, Vec3 localOut) {
		Ship3D best = null;
		float bestD = Float.MAX_VALUE;
		Vec3 end = new Vec3(), hit = new Vec3(), local = new Vec3();
		for (Ship3D s : ships) {
			if (sameStructure(s, skip) || s.destroyed || s.phased || s.volume == null) continue;
			// Chord of the bounding sphere along the ray.
			Vec3 oc = new Vec3(origin).sub(s.pos);
			float r = s.radius * 1.15f + 2f;
			float b = oc.dot(dir), c = oc.lengthSq() - r * r, disc = b * b - c;
			if (disc < 0f) continue;
			float sq = (float) Math.sqrt(disc);
			float t0 = Math.max(0f, -b - sq), t1 = Math.min(maxDist, -b + sq);
			if (t1 <= t0 || t0 >= bestD) continue;
			Vec3 a = new Vec3(origin).addScaled(dir, t0);
			end.set(origin).addScaled(dir, t1);
			float u = segmentHit(s, a, end, hit, local);
			if (u < 0f) continue;
			float d = t0 + (t1 - t0) * u;
			if (d < bestD) {
				bestD = d;
				best = s;
				hitOut.set(hit);
				localOut.set(local);
			}
		}
		return best;
	}

	/** Parameter (0..1) where segment a->b first enters the sphere, or -1. */
	static float segmentSphere(Vec3 a, Vec3 b, Vec3 c, float r) {
		float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
		float fx = a.x - c.x, fy = a.y - c.y, fz = a.z - c.z;
		float A = dx * dx + dy * dy + dz * dz;
		float B = 2f * (fx * dx + fy * dy + fz * dz);
		float C = fx * fx + fy * fy + fz * fz - r * r;
		if (C <= 0f) return 0f;
		if (A < 1e-9f) return -1f;
		float disc = B * B - 4f * A * C;
		if (disc < 0f) return -1f;
		float t = (-B - (float) Math.sqrt(disc)) / (2f * A);
		return t >= 0f && t <= 1f ? t : -1f;
	}

	/** Rotates the barrel toward the target direction at the weapon's turn rate, inside its cone. */
	private void aim(Ship3D s, WeaponMount m, Vec3 aimPoint, float dt) {
		Vec3 desired = tA;
		if (aimPoint != null) {
			Vec3 mw = m.worldPos(s, tB);
			desired.set(aimPoint).sub(mw);
			s.rot.inverseRotate(desired, desired);
			if (desired.lengthSq() < 1e-6f) desired.set(m.restDir);
			desired.normalize();
		} else {
			desired.set(m.restDir);
		}
		clampToCone(desired, m.restDir, m.halfArcRad);
		rotateToward(m.aimDir, desired, (float) Math.toRadians(Math.max(1f, m.spec.turnRate)) * dt);
	}

	private void fire(Ship3D s, WeaponMount m, Tuning t) {
		WeaponSpec3D w = m.spec;
		Vec3 dir = s.rot.rotate(m.aimDir, new Vec3());
		jitter(dir, (float) Math.toRadians(m.spread * 0.5f));
		exotic.fired(s, w);
		if (w.launchWing != null) { // Swarm Launcher, Churning Locus: a wing comes out instead of a shot
			exotic.launchWing(s, m, dir);
			return;
		}
		if (w.kind == WeaponSpec3D.Kind.MISSILE && !w.orb) {
			launchMissile(s, m, dir);
			return;
		}
		Vec3 origin = m.worldPos(s, new Vec3());
		float dmgMult = ("ENERGY".equals(w.type) ? s.sysEnergyDamageMult : 1f) * s.sysDamageMult;
		if (w.orb) {
			exotic.launchOrb(s, m, origin, dir, dmgMult); // Reality Disruptor
		} else if (w.riftMode == WeaponSpec3D.RiftMode.LIGHTNING) {
			exotic.lightning(s, m, origin, dir, dmgMult);
		} else if (w.arcGunConeDeg > 0f) {
			arcShot(s, w, w.arcFromShip ? exotic.shipPoint(s, dir) : origin, dir, dmgMult, t); // Voltaic Discharge, Inimical Emanation: from the swarm / shroud
		} else {
			boolean lidar = lidarGun(s, m);
			Projectile p = new Projectile();
			p.origin.set(origin);
			p.pos.set(origin);
			p.prev.set(origin);
			p.dir.set(dir);
			p.vel.set(dir).scale(w.projectileSpeed * (lidar ? s.sysLidarSpeedMult : 1f)).add(s.vel);
			p.source = s;
			p.spec = w;
			p.maxRange = w.range * t.weaponRangeMult * rangeMult(s, m);
			// Fused shells burst where their range runs out; some (Devastator) fall short by a random amount.
			if (w.proxRangeVariance > 0f && rnd.nextFloat() < w.proxVarianceChance) p.maxRange *= 1f - w.proxRangeVariance * rnd.nextFloat();
			p.damageMult = dmgMult;
			projectiles.add(p);
		}
		float kick = shotImpulse(w, w.damage) * tuning.recoilScale;
		if (kick > 0f) push(s, origin, new Vec3(dir).scale(-1f), kick);
		m.spread = Math.min(w.maxSpread, m.spread + w.spreadBuildup);
		if (w.maxAmmo > 0) m.ammo -= 1f;
		m.flash = 0.06f;
		m.firedThisFrame = true;
		Effect muzzle = new Effect(Effect.Kind.FLASH, origin, Math.max(6f, w.projWidth * 3f) * s.scale, 0.08f)
				.color(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2]);
		muzzle.vel.set(s.vel);
		effects.add(muzzle);
		sounds.play(w.fireSound, origin, 1f);
	}

	/**
	 * Shock Repeater: no shell. The shot is an arc from the muzzle to the nearest enemy ship or missile
	 * within range and the weapon's cone, carrying the shot's damage and EMP; a raised shield in the way
	 * takes it. With nothing there it crackles out ahead and fizzles.
	 */
	private void arcShot(Ship3D s, WeaponSpec3D w, Vec3 from, Vec3 dir, float dmgMult, Tuning t) {
		float range = w.range * t.weaponRangeMult * (fragmentWeapon(w) ? s.sysFragmentRangeMult : 1f);
		float cos = (float) Math.cos(Math.toRadians(w.arcGunConeDeg * 0.5f));
		Object best = null;
		float bestD = Float.MAX_VALUE;
		Vec3 to = new Vec3();
		for (Ship3D o : world) {
			if (o.destroyed || o.phased && !w.arcHitsPhased || o.invulnerable || o.owner == s.owner || sameStructure(o, s)) continue;
			to.set(o.pos).sub(from);
			float len = to.length(), d = o.surfaceDistance(from);
			if (d > range || d >= bestD || len < 1e-3f || to.dot(dir) / len < cos) continue;
			bestD = d;
			best = o;
		}
		for (Missile3D ms : missiles) {
			if (ms.owner == s.owner || ms.hp <= 0f) continue;
			to.set(ms.pos).sub(from);
			float len = to.length(), d = len - ms.spec.missileRadius;
			if (d > range || d >= bestD || len < 1e-3f || to.dot(dir) / len < cos) continue;
			bestD = d;
			best = ms;
		}
		float[] col = w.arcColor != null ? w.arcColor : new float[] {0.55f, 0.6f, 1f};
		String impact = w.arcImpactSound != null ? w.arcImpactSound : "shock_repeater_emp_impact";
		Effect arc = new Effect(Effect.Kind.ARC, from, Math.max(2.5f, 2f * s.scale), 0.2f).color(col[0], col[1], col[2]);
		arc.vel.set(s.vel);
		effects.add(arc);
		float dmg = w.damage * dmgMult;
		if (best instanceof Missile3D) {
			Missile3D ms = (Missile3D) best;
			ms.hp -= dmg;
			arc.end.set(ms.pos);
			sounds.play(impact, ms.pos, 0.7f);
		} else if (best instanceof Ship3D) {
			Ship3D o = (Ship3D) best;
			Vec3 hit = new Vec3(), local = new Vec3(), sHit = new Vec3(), sDir = new Vec3();
			if (shieldOnSegment(List.of(o), from, o.pos, s, 2f, sHit, sDir, new float[1]) != null) {
				shieldDamage(s, o, dmg, w, false, sHit, sDir, true);
				arc.end.set(sHit);
			} else if (segmentHit(o, from, o.pos, hit, local) >= 0f) {
				float[] dealt = dealDamage(s, o, dmg, dmg * w.armorMult, w, local);
				push(o, hit, dir, shotImpulse(w, dmg));
				damageText(hit, s, o, dealt);
				if (w.slowRepairTime > 0f) exotic.slowRepairs(o, w); // Inimical Emanation
				arc.end.set(hit);
			} else {
				arc.end.set(o.pos);
			}
			sounds.play(impact, arc.end, 0.8f);
		} else {
			arc.end.set(from).addScaled(dir, Math.max(0f, range - 50f));
			arc.end.add(new Vec3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).scale(100f));
			arc.size *= 0.7f;
		}
	}

	private void launchMissile(Ship3D s, WeaponMount m, Vec3 dir) {
		WeaponSpec3D w = m.spec;
		Missile3D ms = new Missile3D();
		m.worldPos(s, ms.pos);
		ms.prev.set(ms.pos);
		ms.dir.set(dir);
		ms.vel.set(dir).scale(w.launchSpeed).add(s.vel);
		if (w.launchVelMult != 1f) ms.vel.scale(w.launchVelMult); // Neutron Torpedo leaves slowly
		ms.spec = w;
		ms.source = s;
		ms.owner = s.owner;
		ms.target = w.guided && !w.flareLauncher ? pendingTarget : null;
		if (w.guidance == WeaponSpec3D.Guidance.AIM_POINT && pendingAim != null) ms.aimPoint = new Vec3(pendingAim);
		ms.hp = Math.max(1f, w.missileHp);
		ms.spawnPos.set(ms.pos);
		ms.damageMult = s.sysDamageMult;
		if (fragmentWeapon(w)) ms.lifeMult *= s.sysFragmentRangeMult; // Fragment Volley: twice the reach
		if (w.flareLauncher) { // a decoy: drifts, burns its flight time, lures enemy guided missiles
			ms.flare = true;
			flareLife.put(ms, Math.max(1f, w.flightTime));
		}
		if (w.mirvCount > 0) ms.splitAt = w.mirvSplitRange + rnd.nextFloat() * w.mirvSplitRangeRandom;
		initDem(ms);
		missiles.add(ms);
		if (w.maxAmmo > 0) m.ammo -= 1f;
		m.flash = 0.08f;
		m.firedThisFrame = true;
		Effect puff = new Effect(Effect.Kind.FLASH, ms.pos, Math.max(10f, w.missileRadius * 2f) * s.scale, 0.15f)
				.color(w.missileGlow[0], w.missileGlow[1], w.missileGlow[2]);
		puff.vel.set(s.vel);
		effects.add(puff);
		sounds.play(w.fireSound, ms.pos, 1f);
	}

	// ---- beams -----------------------------------------------------------------------------

	/**
	 * Vanilla beam cycle: charge up (level 0 -> 1), sustain while the trigger is held (continuous) or
	 * for the burst duration (burst beams), charge down, then cooldown. While level > 0 the beam
	 * traces from the mount and damages the first hull or missile it meets, scaled by level.
	 */
	private void stepBeam(Ship3D s, WeaponMount m, boolean trigger, float dt, Tuning t) {
		WeaponSpec3D w = m.spec;
		if (!m.beamFiring && m.beamLevel <= 0f && trigger && m.ready()) {
			m.beamFiring = true;
			m.beamSustainLeft = w.burstBeam ? Math.max(0.05f, w.burstDuration) : 0f;
			m.burstFxDone = false;
		}
		if (m.beamFiring && s.flux != null && !s.flux.spend(w.fluxPerSecond * dt)) m.beamFiring = false; // flux full, overloaded or venting
		if (m.beamFiring) {
			m.beamLevel = w.chargeUp > 0f ? Math.min(1f, m.beamLevel + dt / w.chargeUp) : 1f;
			if (w.burstBeam) {
				if (m.beamLevel >= 1f) {
					m.beamSustainLeft -= dt;
					if (m.beamSustainLeft <= 0f) m.beamFiring = false;
				}
			} else if (!trigger) {
				m.beamFiring = false;
			}
		} else if (m.beamLevel > 0f) {
			m.beamLevel = w.chargeDown > 0f ? Math.max(0f, m.beamLevel - dt / w.chargeDown) : 0f;
			if (m.beamLevel <= 0f) m.cooldownLeft = w.cooldown;
		}

		m.beamHitting = false;
		if (m.beamLevel <= 0f) {
			flushBeamText(s, m, null);
			return;
		}
		m.firedThisFrame = true;
		Vec3 origin = m.worldPos(s, new Vec3());
		Vec3 dir = s.rot.rotate(m.aimDir, new Vec3());
		traceBeam(s, s.owner, m, origin, dir, ("ENERGY".equals(w.type) ? s.sysEnergyDamageMult : 1f) * s.sysDamageMult, dt, t);
	}

	/**
	 * A beam at its mount's current level, from origin along dir: damages the first enemy missile,
	 * raised shield or hull it meets (the shooter's own structure excluded), with the weapon's on-beam
	 * effects. s is the ship credited (may be a DEM missile's launcher); owner picks the missiles it ignores.
	 */
	private void traceBeam(Ship3D s, int owner, WeaponMount m, Vec3 origin, Vec3 dir, float dmgMult, float dt, Tuning t) {
		WeaponSpec3D w = m.spec;
		sounds.loop(w.beamLoopSound, m, origin, m.beamLevel, 1f);
		float range = w.range * t.weaponRangeMult;
		Vec3 hit = new Vec3(), local = new Vec3();
		Ship3D ship = rayHit(world, origin, dir, range, s, hit, local);
		float shipDist = ship != null ? hit.distance(origin) : Float.MAX_VALUE;
		Vec3 end = new Vec3(origin).addScaled(dir, Math.min(range, shipDist));
		Vec3 shieldPt = new Vec3(), shieldDir = new Vec3();
		float[] st = new float[1];
		Ship3D shielded = shieldOnSegment(world, origin, end, s, 2f, shieldPt, shieldDir, st);
		if (shielded != null) shipDist = shieldPt.distance(origin);
		Missile3D missile = missileOnRay(origin, dir, Math.min(range, shipDist), owner);
		float dmg = w.dps * m.beamLevel * dt * dmgMult;
		Object struck = null;
		if (missile == null && shielded != null) {
			struck = shielded;
			m.beamEnd.set(shieldPt);
			m.beamHitting = true;
			if (w.shieldMark != null && m.beamLevel >= 1f) markShield(shielded, m);
			float f = shieldDamage(s, shielded, dmg, w, true, shieldPt, shieldDir, false);
			push(shielded, shieldPt, dir, beamImpulse(dmg));
			if (w.empArcs && w.emp > 0f) {
				m.arcTimer += dt;
				if (m.arcTimer >= BEAM_ARC_INTERVAL) {
					m.arcTimer = 0f;
					if (rnd.nextFloat() < pierceChance(shielded)) {
						piercingArc(s, shielded, shieldPt, w.dps * BEAM_ARC_INTERVAL * 0.5f, w.emp * BEAM_ARC_INTERVAL, w);
					}
				}
			}
			m.beamDamageAccum += f;
			m.beamTextTimer += dt;
			if (m.beamTextTimer >= 0.3f) flushBeamText(s, m, shielded);
		} else if (missile != null) {
			struck = missile;
			float tm = new Vec3(missile.pos).sub(origin).dot(dir);
			m.beamEnd.set(origin).addScaled(dir, tm);
			m.beamHitting = true;
			missile.hp -= dmg;
		} else if (ship != null) {
			struck = ship;
			m.beamEnd.set(hit);
			m.beamHitting = true;
			float hitStrength = w.dps * dpsToHitStrengthMult * w.armorMult;
			float[] dealt = dealDamage(s, ship, dmg, hitStrength, w, local);
			push(ship, hit, dir, beamImpulse(dmg)); // beams push by damage only (their impact is per shot, they hit every frame)
			tally(s, ship, hit, dealt[0] + dealt[1], dealt[1] > 0.01f, dealt[0] > 0.01f);
			m.beamDamageAccum += dealt[0] + dealt[1];
			m.beamTextTimer += dt;
			if (m.beamTextTimer >= 0.3f) flushBeamText(s, m, ship);
		} else {
			m.beamEnd.set(origin).addScaled(dir, range);
		}
		if (w.beamBlast != null && struck != null && !m.burstFxDone && m.beamLevel >= 1f) {
			m.burstFxDone = true;
			areaBlast(w.beamBlast, m.beamEnd, s, owner, struck);
		}
		if (w.rift != null) exotic.beam(s, owner, m, origin, dir, struck, struck != null && struck == shielded, dt); // Rift Beam, Rift Cascade, Abyssal Glare
	}

	// ---- lingering weapon effects: graviton marks on shields, disintegrator burns -------------

	/** Graviton beams playing on each ship's shield: per beam, seconds the mark has left. */
	private final java.util.Map<Ship3D, java.util.Map<WeaponMount, Float>> shieldMarks = new java.util.IdentityHashMap<>();

	private void markShield(Ship3D target, WeaponMount m) {
		shieldMarks.computeIfAbsent(target, k -> new java.util.IdentityHashMap<>()).put(m, m.spec.shieldMarkTime);
	}

	/** Shield damage multiplier from graviton marks: the step for how many such beams are on it (1, 2, 3+). */
	float shieldMarkMult(Ship3D target) {
		java.util.Map<WeaponMount, Float> marks = shieldMarks.get(target);
		if (marks == null || marks.isEmpty()) return 1f;
		float bonus = 0f;
		for (WeaponMount m : marks.keySet()) {
			float[] steps = m.spec.shieldMark;
			bonus = Math.max(bonus, steps[Math.min(marks.size(), steps.length) - 1]);
		}
		return 1f + bonus;
	}

	/** Disintegrator: armor eaten away at one spot on a hull, a tick about every second. */
	private static final class Burn {
		Ship3D target, source;
		final Vec3 local = new Vec3();
		int sector, ticksLeft;
		float perTick, timer;
	}

	private final List<Burn> burns = new ArrayList<>();

	/** Marks fade, burns tick (armor only: a burn that finds no armor left does nothing). */
	private void stepLingering(float dt) {
		for (java.util.Iterator<java.util.Map<WeaponMount, Float>> it = shieldMarks.values().iterator(); it.hasNext(); ) {
			java.util.Map<WeaponMount, Float> marks = it.next();
			marks.replaceAll((m, left) -> left - dt);
			marks.values().removeIf(left -> left <= 0f);
			if (marks.isEmpty()) it.remove();
		}
		for (Iterator<Burn> it = burns.iterator(); it.hasNext(); ) {
			Burn b = it.next();
			Ship3D tg = b.target;
			if (tg.destroyed || b.ticksLeft <= 0) {
				it.remove();
				continue;
			}
			Vec3 at = tg.rot.rotate(b.local.x * tg.scale, b.local.y * tg.scale, b.local.z * tg.scale, new Vec3()).add(tg.pos);
			sounds.loop("disintegrator_loop", b, at, 0.8f, 1f);
			b.timer -= dt;
			if (b.timer > 0f) continue;
			b.timer = 0.8f + rnd.nextFloat() * 0.2f;
			b.ticksLeft--;
			float dealt = tg.armor != null ? Math.min(b.perTick * tg.armorTakenMult, tg.armor.armorAt(b.sector)) : 0f;
			if (dealt > 0.5f) {
				tg.armor.strip(b.sector, dealt);
				tg.hitFlash = Math.max(tg.hitFlash, 0.06f);
				damageText(at, b.source, tg, new float[] {dealt, 0f});
			}
			for (int i = 0; i < 3; i++) {
				Effect e = new Effect(Effect.Kind.SPARK, at, (14f + rnd.nextFloat() * 10f) * Math.max(1f, tg.scale), 1.2f + rnd.nextFloat() * 0.6f)
						.color(0.25f, 0.35f, 0.8f);
				e.vel.set(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).scale(30f).add(tg.vel);
				effects.add(e);
			}
		}
	}

	// ---- on-hit weapon scripts ------------------------------------------------------------------

	/**
	 * The weapon's scripted extras on a hit (vanilla on-hit effects rebuilt in 3D). shieldHit: it stopped
	 * on a raised shield at `at`; otherwise it hit the hull at `at`, local point `local`.
	 */
	private void onHitEffects(Ship3D attacker, Ship3D target, WeaponSpec3D w, Vec3 at, Vec3 local, boolean shieldHit) {
		if (target.destroyed || target.invulnerable) return;
		if (!shieldHit && target.armor != null && target.armor.rating > 0f) {
			int sector = ArmorShell.sector(local.x, local.z, target.volume != null ? target.volume.surfaceClass(local.x, local.y, local.z) : 1);
			if (w.armorBreak > 0f) {
				// Breach / mining blaster: armor torn off round the hit, armor doesn't reduce it, hull untouched.
				float dealt = Math.min(w.armorBreak * target.armorTakenMult, target.armor.armorAt(sector));
				if (dealt > 0.5f) {
					target.armor.strip(sector, dealt);
					damageText(at, attacker, target, new float[] {dealt, 0f});
				}
			}
			if (w.armorBurn > 0f && w.armorBurnTicks > 0) {
				Burn b = new Burn();
				b.target = target;
				b.source = attacker;
				b.local.set(local);
				b.sector = sector;
				b.ticksLeft = w.armorBurnTicks;
				b.perTick = w.armorBurn / w.armorBurnTicks;
				burns.add(b);
			}
		}
		if (w.bonusArcChance > 0f && w.emp > 0f) {
			// Pilum / Sabot: an EMP-only arc to a component anywhere on the ship (Pilum's can go through a shield).
			boolean through = shieldHit && w.bonusArcPierces && rnd.nextFloat() < pierceChance(target);
			if ((!shieldHit || through) && rnd.nextFloat() < w.bonusArcChance) {
				Vec3 hit = new Vec3(), hullLocal = new Vec3();
				if (!shieldHit) hullLocal.set(local);
				else if (segmentHit(target, at, target.pos, hit, hullLocal) < 0f) return;
				empHit(target, hullLocal, w.emp, at, Float.MAX_VALUE / 4f);
			}
		}
		if (w.empArcsMax > 0) exotic.onHit(attacker, target, w, at, local, shieldHit); // Voltaic Cannon
	}

	/** Beams show their damage as one number every 0.3 s instead of one per frame. */
	private void flushBeamText(Ship3D shooter, WeaponMount m, Ship3D target) {
		if (m.beamDamageAccum >= 1f && showNumbers(shooter, target)) {
			Effect text = new Effect(Effect.Kind.DAMAGE_TEXT, m.beamEnd, 0f, 1f).color(1f, 0.85f, 0.4f);
			text.text = String.valueOf(Math.round(m.beamDamageAccum));
			if (target != null) text.vel.set(target.vel).add(new Vec3(0f, 25f, 0f));
			effects.add(text);
		}
		m.beamDamageAccum = 0f;
		m.beamTextTimer = 0f;
	}

	/** Nearest enemy missile within its radius of the ray (origin, unit dir) up to maxDist, or null. */
	Missile3D missileOnRay(Vec3 origin, Vec3 dir, float maxDist, int owner) {
		Missile3D best = null;
		float bestT = maxDist;
		Vec3 oc = new Vec3();
		for (Missile3D ms : missiles) {
			if (ms.owner == owner || !ms.alive) continue;
			oc.set(ms.pos).sub(origin);
			float tt = oc.dot(dir);
			if (tt < 0f || tt > bestT) continue;
			float d2 = oc.lengthSq() - tt * tt;
			float r = ms.spec.missileRadius + 4f;
			if (d2 <= r * r) {
				bestT = tt;
				best = ms;
			}
		}
		return best;
	}

	// ---- missiles --------------------------------------------------------------------------

	/** Guidance, thrust, flight time, point-defence kills and impacts. */
	public void stepMissiles(List<Ship3D> ships, float dt) {
		if (dt <= 0f) return;
		Vec3 aim = new Vec3(), desired = new Vec3(), zero = new Vec3(), hit = new Vec3(), local = new Vec3();
		Vec3 sHit = new Vec3(), sDir = new Vec3();
		float[] sT = new float[1];
		for (Iterator<Missile3D> it = missiles.iterator(); it.hasNext(); ) {
			Missile3D ms = it.next();
			WeaponSpec3D w = ms.spec;
			ms.prev.set(ms.pos);
			ms.age += dt;
			if (!(w.guidance == WeaponSpec3D.Guidance.TWO_STAGE && ms.seek >= 0f)) ms.flight += dt; // a two-stage missile's clock starts when it burns
			if (ms.hp <= 0f || !ms.alive) {
				if (ms.alive) effects.add(new Effect(Effect.Kind.FLASH, ms.pos, w.missileRadius * 4f + 10f, 0.35f).color(1f, 0.7f, 0.4f));
				ms.alive = false;
				if (ms.flare) flareLife.remove(ms);
				it.remove();
				continue;
			}
			if (w.mine) {
				if (mineStep(ms, dt)) it.remove();
				continue;
			}
			if (ms.flare) {
				Float life = flareLife.get(ms);
				if (life == null || ms.age > life) {
					flareLife.remove(ms);
					it.remove();
					continue;
				}
				ms.vel.scale((float) Math.pow(0.6, dt)); // drift to a stop
			} else if (!ms.flamedOut()) {
				if (w.dem != null && demStep(ms, dt)) {
					if (!ms.alive) { // bomb-pumped: blew itself apart to fire
						it.remove();
						continue;
					}
				} else if (ms.flight > w.flightTime * tuning.weaponRangeMult * ms.lifeMult // ranges are scaled in 3D; flight time with them, so missiles reach their range
						|| w.fizzleAtRange && ms.spawnPos.distance(ms.pos) > w.range * tuning.weaponRangeMult) {
					if (w.proxRange > 0f) { // a fused missile bursts where its flight ends (vanilla: canisters, proximity charges, unstable fragments)
						detonate(w, ms.pos, ms.source, ms.owner, ms);
						ms.alive = false;
						it.remove();
						continue;
					}
					ms.fade = 0f;
				} else {
					updateLure(ms, dt);
					if (steer(ms, dt)) {
						// Push velocity toward nose * max speed, limited by the missile's acceleration.
						desired.set(ms.dir).scale(w.missileMaxSpeed).sub(ms.vel);
						float need = desired.length(), can = Math.max(1f, w.missileAccel) * dt;
						if (need > can) desired.scale(can / need);
						ms.vel.add(desired);
					}
				}
			} else {
				ms.fade += dt;
				if (ms.fade > 1f) {
					it.remove();
					continue;
				}
			}
			ms.pos.addScaled(ms.vel, dt);
			ms.trailTimer += dt;
			if (ms.trailTimer >= 0.03f) {
				ms.trailTimer = 0f;
				ms.pushTrail();
			}
			if (ms.flamedOut() || ms.flare) continue; // spent missiles fade out without detonating; flares never hit anything
			if (ms.lure != null && ms.pos.distance(ms.lure.pos) < 25f + ms.spec.missileRadius) {
				// Took the bait: both go up harmlessly.
				effects.add(new Effect(Effect.Kind.FLASH, ms.pos, w.missileRadius * 4f + 10f, 0.35f).color(1f, 0.7f, 0.4f));
				ms.lure.hp = 0f;
				it.remove();
				continue;
			}
			if (w.mirvCount > 0 && w.mirvWarhead != null && readyToSplit(ms)) {
				split(ms);
				ms.alive = false;
				it.remove();
				continue;
			}
			if (w.proxRange > 0f && ms.demState == 0 && proximityTrigger(ms.pos, w.proxRange, ms.owner, ms)) {
				detonate(w, ms.pos, ms.source, ms.owner, ms);
				it.remove();
				continue;
			}

			Ship3D best = null;
			float bestT = 2f;
			for (Ship3D s : ships) {
				if (sameStructure(s, ms.source) || s == ms.rider || s.destroyed || s.phased || s.volume == null || s.fighter && w.passFighters) continue;
				float tt = segmentHit(s, ms.prev, ms.pos, hit, local);
				if (tt >= 0f && tt < bestT) {
					bestT = tt;
					best = s;
				}
			}
			Ship3D shielded = shieldOnSegment(ships, ms.prev, ms.pos, ms.source, bestT, sHit, sDir, sT);
			if (shielded != null) {
				shieldDamage(ms.source, shielded, (w.damage + w.shieldBonus) * ms.damageMult, w, false, sHit, sDir, true); // Squall: extra on shields
				push(shielded, sHit, ms.vel, shotImpulse(w, w.damage));
				blast(sHit, Math.max(25f, w.explosionRadius), shielded);
				Effect boom = new Effect(Effect.Kind.EXPLOSION, sHit, Math.max(25f, w.explosionRadius), 0.6f)
						.color(w.missileGlow[0], w.missileGlow[1], w.missileGlow[2]);
				boom.vel.set(shielded.vel);
				effects.add(boom);
				onHitEffects(ms.source, shielded, w, sHit, null, true);
				exotic.missileImpact(ms, shielded, sHit, null, sDir, true);
				ms.alive = false;
				it.remove();
			} else if (best != null) {
				segmentHit(best, ms.prev, ms.pos, hit, local);
				// Defabrication Swarm deals nothing up front on a hull: it eats in afterwards (missileImpact).
				float[] dealt = w.dotTicks > 0 ? new float[2]
						: dealDamage(ms.source, best, (w.damage + (best.fighter ? w.antiFighterDamage : 0f)) * ms.damageMult, w.damage * ms.damageMult * w.armorMult, w, local);
				onHitEffects(ms.source, best, w, hit, local, false);
				exotic.missileImpact(ms, best, hit, local, null, false);
				push(best, hit, ms.vel, shotImpulse(w, w.damage));
				blast(hit, Math.max(25f, w.explosionRadius), best);
				Effect boom = new Effect(Effect.Kind.EXPLOSION, hit, Math.max(25f, w.explosionRadius), 0.6f)
						.color(w.missileGlow[0], w.missileGlow[1], w.missileGlow[2]);
				boom.vel.set(best.vel);
				effects.add(boom);
				damageText(hit, ms.source, best, dealt);
				sounds.play("explosion_missile", hit, 1f);
				if (best.hull <= 0f && !best.destroyed) destroy(best);
				ms.alive = false;
				it.remove();
			}
		}
		if (!spawned.isEmpty()) {
			missiles.addAll(spawned); // warheads that are missiles themselves (split this frame)
			spawned.clear();
		}
		stepPayloads(dt);
		stepRiders();
	}

	// ---- missiles that ship systems let loose ----------------------------------------------------

	/**
	 * A missile from a ship system (a mine, a flak canister, a drone turned missile): from pos along
	 * dir at speed, plus the ship's own velocity (mines start still). Homes on target if guided.
	 */
	public Missile3D spawnMissile(Ship3D source, WeaponSpec3D w, Vec3 pos, Vec3 dir, float speed, Ship3D target) {
		Missile3D ms = new Missile3D();
		ms.spec = w;
		ms.source = source;
		ms.owner = source.owner;
		ms.target = w.guided ? target : null;
		ms.hp = Math.max(1f, w.missileHp);
		ms.pos.set(pos);
		ms.prev.set(pos);
		ms.spawnPos.set(pos);
		ms.dir.set(dir);
		if (ms.dir.lengthSq() < 1e-6f) source.rot.forward(ms.dir);
		ms.dir.normalize();
		ms.vel.set(ms.dir).scale(speed);
		if (!w.mine) ms.vel.add(source.vel);
		ms.damageMult = source.sysDamageMult;
		initDem(ms);
		missiles.add(ms);
		return ms;
	}

	// ---- missile guidance (vanilla's missile AI by missile type) ----------------------------------

	/** Two-stage missiles: lock on once the nose is this close to the lead (deg) after MIN seconds, or anyway after MAX. */
	static final float TWO_STAGE_LOCK_DEG = 1f, TWO_STAGE_MIN_SEEK = 0.5f, TWO_STAGE_MAX_SEEK = 3f;
	/** Heatseeker: goes in once within this angle of the target's tail (deg); circles this far beyond its hull (+ up to the random extra) until then. */
	static final float HEATSEEKER_TAIL_DEG = 45f, HEATSEEKER_ORBIT = 225f, HEATSEEKER_ORBIT_RANDOM = 100f;
	/** Aim-point missile: stops within this of its point. */
	static final float AIM_POINT_STOP = 20f;

	private final Vec3 gA = new Vec3(), gB = new Vec3(), gC = new Vec3(), gZero = new Vec3();

	/**
	 * Turns a missile's nose for this frame as its guidance says and returns whether its engine burns
	 * (pushes it toward nose x max speed); false = it coasts, or brakes itself.
	 */
	private boolean steer(Missile3D ms, float dt) {
		WeaponSpec3D w = ms.spec;
		float turn = (float) Math.toRadians(w.missileTurnRate) * dt;
		if (ms.headDir != null) {
			ms.dir.set(ms.headDir);
			return true;
		}
		if (ms.lure != null) {
			turnToward(ms, ms.lure.pos, turn);
			return true;
		}
		Ship3D tg = ms.target != null && !ms.target.destroyed && !ms.target.phased ? ms.target : null;
		switch (w.guidance) {
			case TWO_STAGE:
				return twoStage(ms, tg, dt, turn);
			case AIM_POINT:
				return toAimPoint(ms, dt, turn);
			case HEATSEEKER:
				if (tg != null) heatseek(ms, tg, dt, turn);
				return true;
			default:
				if (tg != null) {
					float speed = Math.max(w.missileMaxSpeed, ms.vel.length());
					// Closing on its split range, a MIRV leads for its warheads' speed (vanilla), so they come out on the lead.
					if (w.mirvWarhead != null && warheadSpeed(w) > 1f && ms.pos.distance(tg.pos) - tg.radius < ms.splitAt + 100f) speed = warheadSpeed(w);
					turnToward(ms, lead(ms, tg, speed, gA), turn);
				}
				return true;
		}
	}

	private void turnToward(Missile3D ms, Vec3 point, float turn) {
		Vec3 d = gB.set(point).sub(ms.pos);
		if (d.lengthSq() > 1e-4f) rotateToward(ms.dir, d.normalize(), turn);
	}

	/** Where a missile flying at `speed` meets target tg (its position if it can't). */
	private Vec3 lead(Missile3D ms, Ship3D tg, float speed, Vec3 out) {
		if (!interceptCurved(ms.pos, gZero, tg.pos, tg.vel, tg.accel, speed, out)) out.set(tg.pos);
		return out;
	}

	private static float warheadSpeed(WeaponSpec3D w) {
		WeaponSpec3D h = w.mirvWarhead;
		return Math.max(w.mirvSpreadSpeed, h.kind == WeaponSpec3D.Kind.MISSILE ? h.missileMaxSpeed : 0f);
	}

	/**
	 * Vanilla's two-stage missile (Squall, Hurricane's warheads): no thrust and the flight clock held
	 * while it turns its nose onto the lead; once on it (after half a second), or after 3 s, or if the
	 * target is gone or phases, it locks that heading and burns straight, unguided, for its flight time.
	 */
	private boolean twoStage(Missile3D ms, Ship3D tg, float dt, float turn) {
		if (ms.seek < 0f) return true; // locked: the nose stays where it was
		if (tg == null) {
			ms.seek = -1f;
			return true;
		}
		ms.seek += dt;
		Vec3 to = lead(ms, tg, ms.spec.missileMaxSpeed, gA).sub(ms.pos);
		if (to.lengthSq() < 1e-4f) to.set(tg.pos).sub(ms.pos);
		to.normalize();
		float off = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, ms.dir.dot(to)))));
		if (off < TWO_STAGE_LOCK_DEG && ms.seek > TWO_STAGE_MIN_SEEK || ms.seek > TWO_STAGE_MAX_SEEK) {
			ms.seek = -1f;
			return true;
		}
		rotateToward(ms.dir, to, turn);
		return false;
	}

	/**
	 * Vanilla's heatseeker (Salamander): unless it's already within 45 deg of the target's tail it
	 * circles round the target (225-325 beyond its hull, swinging wide when inside that and in when
	 * outside, a random 15-30 deg weave rerolled every 1-3 s) toward the tail, then goes straight in.
	 * Fighters and station sections it goes straight for.
	 */
	private void heatseek(Missile3D ms, Ship3D tg, float dt, float turn) {
		Vec3 r = gA.set(ms.pos).sub(tg.pos);
		float dist = r.length();
		if (!ms.finalApproach && dist > 1e-3f && !tg.fighter && tg.parent == null) {
			r.scale(1f / dist);
			Vec3 tail = tg.rot.forward(gB).scale(-1f);
			float fromNose = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, -r.dot(tail)))));
			if (fromNose <= 180f - HEATSEEKER_TAIL_DEG) {
				ms.courseTimer -= dt;
				if (ms.courseTimer <= 0f) {
					ms.courseTimer = 1f + 2f * rnd.nextFloat();
					ms.courseOffset = (15f + 15f * rnd.nextFloat()) * (rnd.nextBoolean() ? 1f : -1f);
					if (ms.orbitExtra < 0f) ms.orbitExtra = HEATSEEKER_ORBIT_RANDOM * rnd.nextFloat();
				}
				float inside = tg.radius + HEATSEEKER_ORBIT + Math.max(0f, ms.orbitExtra) - dist;
				float off = Math.min(90f, (180f + 35f - fromNose) * 0.5f) + Math.min(90f, Math.abs(inside) * 0.25f) * Math.signum(inside) + ms.courseOffset;
				off = (float) Math.toRadians(Math.max(0f, Math.min(170f, off)));
				// Round the hull toward the tail: the part of the tail direction square to the line to the target.
				Vec3 round = gC.set(tail).addScaled(r, -r.dot(tail));
				if (round.lengthSq() < 1e-4f) round.set(Math.abs(r.y) < 0.9f ? 0f : 1f, Math.abs(r.y) < 0.9f ? 1f : 0f, 0f).cross(r);
				round.normalize();
				Vec3 heading = gB.set(r).scale(-(float) Math.cos(off)).addScaled(round, (float) Math.sin(off)).normalize();
				rotateToward(ms.dir, heading, turn);
				return;
			}
		}
		ms.finalApproach = true; // once in from the tail it stays on its run
		turnToward(ms, lead(ms, tg, Math.max(ms.spec.missileMaxSpeed, ms.vel.length()), gA), turn);
	}

	/**
	 * Vanilla's proximity charge: flies to the point it was aimed at and stops there (braking at its
	 * acceleration), waiting for its fuse or the end of its flight. Without an aim point it drifts.
	 */
	private boolean toAimPoint(Missile3D ms, float dt, float turn) {
		if (ms.aimPoint == null) return false;
		Vec3 to = gA.set(ms.aimPoint).sub(ms.pos);
		if (to.length() > AIM_POINT_STOP) {
			rotateToward(ms.dir, to.normalize(), turn);
			return true;
		}
		float speed = ms.vel.length(), brake = Math.max(1f, ms.spec.missileAccel) * dt;
		ms.vel.scale(speed > brake ? (speed - brake) / speed : 0f);
		return false;
	}

	/**
	 * Vanilla's split rule: within split range of the target's hull after its minimum time (or sooner,
	 * if it may split early and would get there before then), with its nose on the target: within the
	 * fan's arc less 1.5 deg, or anywhere on the hull (Pilum, arc 0).
	 */
	private boolean readyToSplit(Missile3D ms) {
		WeaponSpec3D w = ms.spec;
		Ship3D tg = ms.target;
		if (tg == null || tg.destroyed) return false;
		Vec3 to = gA.set(tg.pos).sub(ms.pos);
		float dist = to.length();
		if (dist < 1e-3f) return true;
		to.scale(1f / dist);
		if (ms.age < w.mirvMinTime) {
			if (!w.mirvSplitEarly || ms.age <= 1f) return false;
			float closing = gB.set(ms.vel).sub(tg.vel).dot(to);
			if (closing <= 10f) return false;
			float eta = Math.max(0f, dist - tg.radius - 0.75f * w.mirvSplitRange) / closing;
			if (eta >= w.mirvMinTime - ms.age) return false;
		}
		if (dist - tg.radius > ms.splitAt) return false;
		float off = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, ms.dir.dot(to)))));
		float hull = (float) Math.toDegrees(Math.atan2(tg.radius, dist));
		return off <= Math.max(0f, w.mirvArcDeg - 1.5f) + hull;
	}

	/**
	 * A mine: settles in place; once armed, an enemy ship whose hull comes within its fuse range starts
	 * the windup (vanilla's ping), and it blows when the windup ends or its life does. True once it has blown.
	 */
	private boolean mineStep(Missile3D ms, float dt) {
		WeaponSpec3D w = ms.spec;
		ms.vel.scale((float) Math.pow(0.02, dt));
		ms.pos.addScaled(ms.vel, dt);
		if (ms.windup < 0f && ms.age >= w.mineArmTime) {
			for (Ship3D s : world) {
				if (s.owner == ms.owner || s.destroyed || s.phased || s.invulnerable) continue;
				if (s.surfaceDistance(ms.pos) <= w.proxRange) {
					ms.windup = 0f;
					sounds.play("mine_ping", ms.pos, 1f);
					break;
				}
			}
		}
		if (ms.windup >= 0f) ms.windup += dt;
		if (ms.windup >= w.mineWindup || ms.age >= w.flightTime) {
			detonate(w, ms.pos, ms.source, ms.owner, ms);
			ms.alive = false;
			return true;
		}
		return false;
	}

	/** Missiles carrying a drone (Termination Sequence). */
	private final List<Missile3D> ridden = new ArrayList<>();

	/** Termination Sequence: the drone rides the missile from where it was; it dies with it. */
	public void carry(Missile3D ms, Ship3D drone) {
		ms.rider = drone;
		drone.carriedBy = ms;
		ridden.add(ms);
	}

	/** Riders follow their missiles; a missile that's gone takes its drone with it, a drone shot off its missile ends it. */
	private void stepRiders() {
		for (Iterator<Missile3D> it = ridden.iterator(); it.hasNext(); ) {
			Missile3D ms = it.next();
			Ship3D d = ms.rider;
			if (d.destroyed) ms.alive = false; // shot down while riding: the missile goes with it
			if (!ms.alive || ms.flamedOut() || !missiles.contains(ms)) {
				d.carriedBy = null;
				if (!d.destroyed) destroy(d);
				it.remove();
				continue;
			}
			d.pos.set(ms.pos);
			d.vel.set(ms.vel);
			d.rot.setLookRotation(ms.dir, Math.abs(ms.dir.y) > 0.95f ? new Vec3(1f, 0f, 0f) : new Vec3(0f, 1f, 0f));
		}
	}

	/** Missiles created while stepping the missile list (warheads), added after the pass. */
	private final List<Missile3D> spawned = new ArrayList<>();

	// ---- DEM missiles --------------------------------------------------------------------------

	/** A DEM payload beam in flight: fired from its missile (or from where a bomb-pumped one blew). */
	public static final class PayloadBeam {
		public final WeaponMount mount;
		public final Vec3 origin = new Vec3(), dir = new Vec3();
		public Ship3D source;
		public int owner;
		/** The missile it fires from; null once a bomb-pumped missile has blown itself apart. */
		public Missile3D carrier;
		float age, hold;
		boolean done;

		PayloadBeam(WeaponSpec3D payload) {
			mount = new WeaponMount("dem", payload, new Vec3(), new Vec3(0f, 0f, -1f), 0f, true);
		}
	}

	public final List<PayloadBeam> payloads = new ArrayList<>();

	/** Rolls a DEM missile's own trigger and stand-off distances (vanilla gives ranges). */
	private void initDem(Missile3D ms) {
		DemSpec d = ms.spec.dem;
		if (d == null) return;
		ms.demTrigger = roll(d.triggerMin, d.triggerMax);
		ms.demHoldMin = roll(d.holdMinLo, d.holdMinHi);
		ms.demHoldMax = d.holdMax >= 0f ? d.holdMax : ms.demTrigger;
		ms.demAngle = d.triggerAngleLo >= 0f ? roll(d.triggerAngleLo, d.triggerAngleHi) : -1f;
	}

	private float roll(float lo, float hi) {
		return hi > lo ? lo + rnd.nextFloat() * (hi - lo) : lo;
	}

	/**
	 * DEM attack: cruises like any guided missile; within its trigger distance of the target's hull
	 * (after its minimum delay, or sooner if it has swung far round the target or is already too
	 * close) it holds a stand-off, turns onto the target, paints it with its laser, then fires the
	 * payload beam along its nose and is spent. Returns true while attacking (its own steering, and its
	 * flight time doesn't run out), false while still cruising.
	 */
	private boolean demStep(Missile3D ms, float dt) {
		DemSpec d = ms.spec.dem;
		float k = tuning.weaponRangeMult;
		ms.laserLevel = 0f;
		if (ms.demState == 0) {
			Ship3D tg = ms.target;
			if (tg == null || tg.destroyed || tg.phased) return false;
			float dist = tg.surfaceDistance(ms.pos);
			boolean waited = ms.age >= d.minDelay || dist < ms.demHoldMin * k || ms.demAngle >= 0f && angleRound(tg, ms) >= ms.demAngle;
			if (!waited || dist >= ms.demTrigger * k) return false;
			ms.demState = 1;
			ms.demTarget = tg;
			ms.demTimer = 0f;
		}
		Ship3D tg = ms.demTarget;
		if (ms.demState >= 4) return false;
		if (tg.destroyed && ms.demState < 3) {
			ms.demState = 4; // nothing left to fire at: fizzles
			ms.fade = 0f;
			return true;
		}
		ms.demTimer += dt;
		demControl(ms, tg, dt);
		Vec3 to = new Vec3(tg.pos).sub(ms.pos);
		float len = to.length();
		switch (ms.demState) {
			case 1:
				if (len > 1e-3f && ms.dir.dot(to) / len >= (float) Math.cos(Math.toRadians(d.aimArcDeg * 0.5f))) {
					ms.demState = 2;
					ms.demTimer = 0f;
				}
				break;
			case 2: {
				if (d.laserRange > 0f) {
					ms.laserLevel = Math.min(1f, ms.demTimer / 0.1f);
					float reach = d.laserRange * k;
					float hitD = raycast(world, ms.pos, ms.dir, reach, ms.source);
					ms.laserEnd.set(ms.pos).addScaled(ms.dir, hitD >= 0f ? hitD : reach);
				}
				if (ms.demTimer >= d.targetingTime) {
					PayloadBeam pb = new PayloadBeam(d.payload);
					pb.source = ms.source;
					pb.owner = ms.owner;
					pb.carrier = d.bombPumped ? null : ms;
					pb.origin.set(ms.pos);
					pb.dir.set(ms.dir);
					pb.hold = d.payload.burstBeam ? Math.max(0.05f, d.payload.burstDuration) : d.firingTime;
					payloads.add(pb);
					ms.payload = pb;
					ms.demState = 3;
					ms.demTimer = 0f;
					if (d.bombPumped) {
						Effect boom = new Effect(Effect.Kind.EXPLOSION, ms.pos, Math.max(40f, ms.spec.explosionRadius) * 1.5f, 0.8f)
								.color(ms.spec.missileGlow[0], ms.spec.missileGlow[1], ms.spec.missileGlow[2]);
						effects.add(boom);
						sounds.play("explosion_missile", ms.pos, 1f);
						ms.alive = false;
					}
				}
				break;
			}
			case 3:
				if (ms.demTimer >= d.firingTime && (ms.payload == null || ms.payload.done)) {
					ms.demState = 4;
					if (d.destroyWhenDone) {
						effects.add(new Effect(Effect.Kind.FLASH, ms.pos, ms.spec.missileRadius * 4f + 15f, 0.4f)
								.color(ms.spec.missileGlow[0], ms.spec.missileGlow[1], ms.spec.missileGlow[2]));
						ms.alive = false;
					} else {
						ms.fade = 0f; // engine dies; it drifts off
					}
				}
				break;
			default:
				break;
		}
		return true;
	}

	/** Degrees the missile has swung round the target since it was launched (as seen from the target). */
	private static float angleRound(Ship3D tg, Missile3D ms) {
		Vec3 a = new Vec3(ms.spawnPos).sub(tg.pos), b = new Vec3(ms.pos).sub(tg.pos);
		float la = a.length(), lb = b.length();
		if (la < 1e-3f || lb < 1e-3f) return 0f;
		return (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, a.dot(b) / (la * lb)))));
	}

	/**
	 * Holding a stand-off: nose onto the target (turn rate boosted), back off when too close, close in
	 * when too far, otherwise bleed speed down to a slow drift (sidestepping at random if it strafes).
	 */
	private void demControl(Missile3D ms, Ship3D tg, float dt) {
		DemSpec d = ms.spec.dem;
		WeaponSpec3D w = ms.spec;
		float k = tuning.weaponRangeMult;
		Vec3 to = new Vec3(tg.pos).sub(ms.pos);
		float len = to.length();
		if (len < 1e-3f) return;
		to.scale(1f / len);
		if (!tg.phased) rotateToward(ms.dir, to, (float) Math.toRadians(w.missileTurnRate + d.turnBoost) * dt);
		if (ms.demState >= 3 && d.bombPumped) return;
		float dist = tg.surfaceDistance(ms.pos), top = Math.max(50f, w.missileMaxSpeed), drift = top * d.driftFrac;
		Vec3 want = new Vec3();
		if (dist < ms.demHoldMin * k) want.set(to).scale(-top);
		else if (dist > ms.demHoldMax * k) want.set(to).scale(top);
		else {
			want.set(ms.vel);
			float sp = want.length();
			if (sp > drift) want.scale(drift / sp);
			if (d.randomStrafe) {
				ms.strafeLeft -= dt;
				if (ms.strafeLeft <= 0f) {
					ms.strafeLeft = 0.5f + rnd.nextFloat() * 0.5f;
					float r = rnd.nextFloat();
					ms.strafeDir = ms.strafeDir == 0 ? (r < 0.4f ? 1 : r < 0.8f ? -1 : 0) : (r < 0.8f ? -ms.strafeDir : 0);
				}
				if (ms.strafeDir != 0) {
					Vec3 side = new Vec3(to).cross(new Vec3(0f, 1f, 0f));
					if (side.lengthSq() < 1e-4f) side.set(1f, 0f, 0f);
					want.addScaled(side.normalize(), ms.strafeDir * drift);
				}
			}
		}
		Vec3 change = want.sub(ms.vel);
		float need = change.length(), can = Math.max(1f, w.missileAccel) * dt;
		if (need > can) change.scale(can / need);
		ms.vel.add(change);
	}

	/**
	 * Payload beams: one burst each (charge up, hold for the burst or the firing time, charge down),
	 * traced like a ship's beam from the missile's nose. A payload whose missile is shot down stops.
	 */
	private void stepPayloads(float dt) {
		for (Iterator<PayloadBeam> it = payloads.iterator(); it.hasNext(); ) {
			PayloadBeam pb = it.next();
			WeaponMount m = pb.mount;
			WeaponSpec3D w = m.spec;
			if (pb.carrier != null) {
				if (!pb.carrier.alive || !missiles.contains(pb.carrier)) {
					m.beamLevel = 0f;
				} else {
					pb.origin.set(pb.carrier.pos);
					pb.dir.set(pb.carrier.dir);
				}
			}
			pb.age += dt;
			float up = Math.max(0f, w.chargeUp), down = Math.max(0f, w.chargeDown);
			if (m.beamLevel > 0f || pb.age <= dt) {
				if (pb.age < up) m.beamLevel = pb.age / up;
				else if (pb.age < up + pb.hold) m.beamLevel = 1f;
				else m.beamLevel = down > 0f ? Math.max(0f, 1f - (pb.age - up - pb.hold) / down) : 0f;
			}
			m.beamHitting = false;
			if (m.beamLevel <= 0f) {
				flushBeamText(pb.source, m, null);
				pb.done = true;
				it.remove();
				continue;
			}
			m.firedThisFrame = true;
			traceBeam(pb.source, pb.owner, m, pb.origin, pb.dir, 1f, dt, tuning);
		}
	}

	// ---- damage ----------------------------------------------------------------------------

	/**
	 * Share of a hit's damage that gets through armor: vanilla's formula (hit strength against the armor
	 * there, at least minArmorFraction of the rating, reduction capped at maxArmorDamageReduction), with
	 * the reduction scaled by Tuning.armorReductionMult (0.7: armor stops 30% less than in vanilla).
	 */
	public float armorDamageMult(float hitStrength, float armorHere, float armorRating) {
		float effArmor = Math.max(armorHere, armorRating * minArmorFraction);
		float through = effArmor > 0f ? Math.max(1f - maxArmorDamageReduction, hitStrength / (hitStrength + effArmor)) : 1f;
		return 1f - (1f - through) * tuning.armorReductionMult;
	}

	/**
	 * Vanilla-style armor/hull damage for one hit at a local (unscaled, just-outside-the-hull) point.
	 * hitStrength drives armor reduction (shots: damage x armor mult; beams: dps x 0.5 x armor mult).
	 * Returns {armorDealt, hullDealt}.
	 */
	float[] dealDamage(Ship3D attacker, Ship3D target, float dmg, float hitStrength, WeaponSpec3D w, Vec3 localOutside) {
		if (target.invulnerable) { // Vast Bulk: a station's core soaks up anything
			target.hitFlash = Math.max(target.hitFlash, 0.06f);
			return new float[] {0f, 0f};
		}
		dmg *= target.sysDamageTakenMult; // Damper Field
		if (attacker != null && attacker != target) {
			target.lastHitBy = attacker;
			if (target == numbersFor) attacker.lastHitPlayerAt = clock;
		}
		int surface = target.volume != null ? target.volume.surfaceClass(localOutside.x, localOutside.y, localOutside.z) : 1;
		int sector = ArmorShell.sector(localOutside.x, localOutside.z, surface);
		float armorDealt = 0f;
		ArmorShell armor = target.armor;
		float armorHere = armor != null ? armor.armorAt(sector) : 0f;
		float armorRating = armor != null ? armor.rating : 0f;
		float reduction = armorDamageMult(hitStrength, armorHere, armorRating);
		float toArmor = dmg * w.armorMult * reduction * target.armorTakenMult;
		if (armor != null && armorHere > 0f) {
			armorDealt = Math.min(toArmor, armorHere);
			armor.strip(sector, armorDealt);
		}
		float overflow = toArmor > 0f ? Math.max(0f, toArmor - armorDealt) / toArmor : 1f;
		float hullDealt = dmg * overflow * w.hullMult * reduction * target.hullTakenMult * target.sysHullTakenMult; // Darkened Gaze: x2
		target.hull = Math.max(0f, target.hull - hullDealt);
		// EMP rides along in proportion to the damage (beams: EMP/s over dps; partial blasts scale too).
		if (w.emp > 0f && w.damage > 0f && !arcInProgress) {
			float emp = dmg * (w.emp / w.damage) * (tuning.empArmorReduces ? reduction : 1f);
			empHit(target, localOutside, emp, null);
		}
		target.hitFlash = Math.max(target.hitFlash, 0.12f);
		if (target.hull <= 0f && !target.destroyed) destroy(target);
		return new float[] {armorDealt, hullDealt};
	}

	// ---- weight: knockback, spin, blasts ------------------------------------------------------

	float beamImpulse(float damageThisFrame) {
		return tuning.impactScale * damageThisFrame * tuning.impactPerDamage;
	}

	/** Impulse of one hit: vanilla impact (kinetic guns hit hardest) plus a share of the damage. */
	float shotImpulse(WeaponSpec3D w, float damage) {
		return tuning.impactScale * (w.impact + damage * tuning.impactPerDamage);
	}

	/**
	 * A hit at a world point pushing along `along`: velocity changes by impulse / mass, and an
	 * off-centre hit spins the ship (inertia ~ mass x radius^2). Flight assist then pulls the ship
	 * back on course, quickly for light hulls and slowly for heavy ones. Shakes your camera if it's you.
	 */
	public void push(Ship3D s, Vec3 point, Vec3 along, float impulse) {
		if (impulse <= 0f || s.phased) return;
		s = s.root(); // a hit on a module moves the whole ship; a station doesn't move
		if (s.anchored) return;
		float len = along.length();
		if (len < 1e-4f) return;
		float m = Math.max(1f, s.mass);
		Vec3 dir = new Vec3(along).scale(1f / len);
		s.vel.addScaled(dir, impulse / m);
		if (tuning.spinScale > 0f) {
			Vec3 r = new Vec3(point).sub(s.pos);
			Vec3 torque = new Vec3(r).cross(new Vec3(dir).scale(impulse));
			s.rot.inverseRotate(torque, torque);
			float inertia = 0.3f * m * Math.max(1f, s.radius) * Math.max(1f, s.radius);
			s.angVel.addScaled(torque, tuning.spinScale / inertia);
		}
		if (s == numbersFor) shake += impulse / m * 0.02f;
	}

	/** An explosion shoves every ship within `radius` (not `except`) outward, falling off with distance. */
	void blast(Vec3 at, float radius, Ship3D except) {
		float reach = radius * 2f;
		for (Ship3D s : world) {
			if (s == except || s.phased || s.parent != null || s.anchored) continue; // structures move as one (via their root) or not at all
			Vec3 away = new Vec3(s.pos).sub(at);
			float d = away.length() - s.radius;
			if (d > reach) continue;
			float k = 1f - Math.max(0f, d) / reach;
			if (away.lengthSq() < 1e-4f) away.set(0f, 1f, 0f);
			push(s, new Vec3(s.pos).addScaled(away.normalize(), -s.radius * 0.5f), away, tuning.blastScale * radius * k);
		}
		if (numbersFor != null) {
			float d = numbersFor.pos.distance(at);
			if (d < reach * 3f) shake += radius / 400f * (1f - d / (reach * 3f));
		}
	}

	// ---- collisions --------------------------------------------------------------------------

	private static final WeaponSpec3D RAM = ramSpec();

	private static WeaponSpec3D ramSpec() {
		WeaponSpec3D w = new WeaponSpec3D();
		w.id = w.name = "collision";
		w.type = "COLLISION";
		w.damageType = "KINETIC";
		w.supported = true;
		return w;
	}

	/** Collision damage to one ship at a world contact point (armor/hull rules apply; the other ship gets the credit). */
	public void collisionDamage(Ship3D other, Ship3D target, Vec3 contact, float dmg) {
		if (dmg < 1f || target.destroyed) return;
		Vec3 local = target.rot.inverseRotate(new Vec3(contact).sub(target.pos), new Vec3()).scale(1f / Math.max(1e-3f, target.scale));
		RAM.damage = dmg;
		float[] dealt = dealDamage(other, target, dmg, dmg, RAM, local);
		damageText(contact, other, target, dealt);
		sounds.play("collision_ships", contact, Math.min(1.2f, 0.4f + dmg / 400f));
		Effect spark = new Effect(Effect.Kind.SPARK, contact, Math.min(80f, 15f + dmg * 0.05f), 0.4f).color(1f, 0.75f, 0.4f);
		spark.vel.set(target.vel);
		effects.add(spark);
		if (target.hull <= 0f && !target.destroyed) destroy(target);
	}

	// ---- flares and the EMP emitter (ship systems) -----------------------------------------

	private static final WeaponSpec3D FLARE = flareSpec();

	private static WeaponSpec3D flareSpec() {
		WeaponSpec3D f = new WeaponSpec3D();
		f.id = f.name = "flare";
		f.kind = WeaponSpec3D.Kind.MISSILE;
		f.supported = true;
		f.missileRadius = 6f;
		f.missileHp = 40f;
		f.missileMaxSpeed = 120f;
		f.missileAccel = 20f;
		f.missileGlow = new float[] {1f, 0.55f, 0.2f};
		return f;
	}

	/** Flare launcher: decoys fan out above and behind the ship and burn for `life` seconds. */
	public void launchFlares(Ship3D s, int count, float life) {
		Vec3 up = s.rot.up(new Vec3()), back = s.rot.forward(new Vec3()).scale(-1f), right = s.rot.right(new Vec3());
		for (int i = 0; i < count; i++) {
			Missile3D f = new Missile3D();
			f.flare = true;
			f.spec = FLARE;
			f.source = s;
			f.owner = s.owner;
			f.hp = FLARE.missileHp;
			f.pos.set(s.pos).addScaled(up, s.radius * 0.3f);
			f.prev.set(f.pos);
			float a = count > 1 ? -1f + 2f * i / (count - 1) : 0f;
			f.dir.set(back).addScaled(up, 0.8f).addScaled(right, a).normalize();
			f.vel.set(f.dir).scale(150f + rnd.nextFloat() * 60f).add(s.vel);
			flareLife.put(f, life);
			missiles.add(f);
		}
		effects.add(new Effect(Effect.Kind.FLASH, s.pos, s.radius * 0.8f, 0.2f).color(1f, 0.6f, 0.3f));
		sounds.play("launch_flare_1", s.pos, 0.8f);
	}

	/** Flares burn for their own time (they have no missile flight time). */
	private final java.util.Map<Missile3D, Float> flareLife = new java.util.IdentityHashMap<>();

	/** Every half second an unlured guided missile may take the bait of an enemy flare in range. */
	private void updateLure(Missile3D ms, float dt) {
		if (ms.lure != null && (ms.lure.hp <= 0f || !missiles.contains(ms.lure))) ms.lure = null;
		if (ms.flare || ms.lure != null || !ms.spec.guided || ms.spec.ignoresFlares) return;
		if (ms.spec.guidance == WeaponSpec3D.Guidance.TWO_STAGE && ms.seek < 0f) return; // locked on and burning: unguided now
		ms.lureCheck -= dt;
		if (ms.lureCheck > 0f) return;
		ms.lureCheck = 0.5f;
		Missile3D best = null;
		float bestD = tuning.sysFlareLure;
		for (Missile3D f : missiles) {
			if (!f.flare || f.owner == ms.owner || f.hp <= 0f) continue;
			float d = f.pos.distance(ms.pos);
			if (d < bestD) {
				bestD = d;
				best = f;
			}
		}
		if (best != null && rnd.nextFloat() < tuning.sysFlareLureChance) ms.lure = best;
	}

	/** EMP Emitter: arcs from the ship to the nearest few enemies (ships, fighters, missiles) in range. */
	public void empEmitterPulse(Ship3D s, float range, float damage, float emp, int targets) {
		WeaponSpec3D spec = EMITTER;
		spec.damage = Math.max(1f, damage);
		spec.emp = emp;
		java.util.List<Object> picks = new java.util.ArrayList<>();
		java.util.List<Float> dists = new java.util.ArrayList<>();
		float r = range * tuning.weaponRangeMult;
		for (Ship3D o : world) {
			if (o.destroyed || o.phased || o.owner == s.owner) continue;
			float d = o.pos.distance(s.pos) - o.radius;
			if (d <= r) insertSorted(picks, dists, o, d);
		}
		for (Missile3D m : missiles) {
			if (m.owner == s.owner || m.hp <= 0f) continue;
			float d = m.pos.distance(s.pos);
			if (d <= r) insertSorted(picks, dists, m, d);
		}
		Vec3 hit = new Vec3(), local = new Vec3(), sHit = new Vec3(), sDir = new Vec3();
		float[] sT = new float[1];
		for (int i = 0; i < Math.min(targets, picks.size()); i++) {
			Object o = picks.get(i);
			float[] ac = s.system != null && s.system.look.effect1 != null ? s.system.look.effect1 : new float[] {0.55f, 0.75f, 1f}; // the system's arc colour
			Effect arc = new Effect(Effect.Kind.ARC, s.pos, Math.max(3f, 2.5f * s.scale), 0.3f).color(ac[0], ac[1], ac[2]);
			if (o instanceof Missile3D) {
				Missile3D m = (Missile3D) o;
				m.hp -= spec.damage;
				arc.end.set(m.pos);
			} else {
				Ship3D t = (Ship3D) o;
				Ship3D shielded = shieldOnSegment(List.of(t), s.pos, t.pos, s, 2f, sHit, sDir, sT);
				if (shielded != null) {
					shieldDamage(s, t, spec.damage, spec, false, sHit, sDir, true);
					arc.end.set(sHit);
				} else if (segmentHit(t, s.pos, t.pos, hit, local) >= 0f) {
					float[] dealt = dealDamage(s, t, spec.damage, spec.damage, spec, local);
					tally(s, t, hit, dealt[0] + dealt[1], dealt[1] > 0.5f, dealt[0] > 0.5f);
					arc.end.set(hit);
					if (t.hull <= 0f && !t.destroyed) destroy(t);
				} else {
					continue;
				}
			}
			arc.vel.set(s.vel);
			effects.add(arc);
		}
	}

	private static final WeaponSpec3D EMITTER = emitterSpec();

	/**
	 * One EMP arc from a point to a ship (Energy Lash): a raised shield in the way takes it (the
	 * damage as flux), else it strikes the hull where it reaches: damage and EMP. Drawn as an arc
	 * `width` wide in the given colours, after `delay` seconds.
	 */
	public void strikeArc(Ship3D source, Vec3 from, Ship3D t, float damage, float emp, float[] fringe, float width, float delay, String sound) {
		Vec3 hit = new Vec3(), local = new Vec3(), sHit = new Vec3(), sDir = new Vec3();
		float[] sT = new float[1];
		Effect arc = new Effect(Effect.Kind.ARC, from, width, 0.35f).color(fringe[0], fringe[1], fringe[2]).delayed(delay);
		arc.vel.set(t.vel);
		Ship3D shielded = shieldOnSegment(List.of(t), from, t.pos, source, 2f, sHit, sDir, sT);
		if (shielded != null) {
			if (damage > 0f) shieldDamage(source, t, damage, EMITTER, false, sHit, sDir, true);
			arc.end.set(sHit);
		} else if (segmentHit(t, from, t.pos, hit, local) >= 0f) {
			if (damage > 0f) {
				float[] dealt = dealDamage(source, t, damage, damage, EMITTER, local);
				tally(source, t, hit, dealt[0] + dealt[1], dealt[1] > 0.5f, dealt[0] > 0.5f);
			}
			if (emp > 0f) empHit(t, local, emp, from);
			arc.end.set(hit);
			if (t.hull <= 0f && !t.destroyed) destroy(t);
		} else {
			arc.end.set(t.pos);
		}
		effects.add(arc);
		if (sound != null) effects.add(new Effect(Effect.Kind.FLASH, arc.end, width * 2f, 0.2f).color(fringe[0], fringe[1], fringe[2]).delayed(delay).withSound(sound, 1f));
	}

	private static WeaponSpec3D emitterSpec() {
		WeaponSpec3D w = new WeaponSpec3D();
		w.id = w.name = "emp emitter";
		w.type = "ENERGY";
		w.damageType = "ENERGY";
		w.supported = true;
		return w;
	}

	private static void insertSorted(java.util.List<Object> picks, java.util.List<Float> dists, Object o, float d) {
		int i = 0;
		while (i < dists.size() && dists.get(i) < d) i++;
		picks.add(i, o);
		dists.add(i, d);
	}

	// ---- EMP -------------------------------------------------------------------------------

	private static final float BEAM_ARC_INTERVAL = 0.5f;
	/** Set while an arc deals its damage, so dealDamage doesn't add the EMP a second time. */
	private boolean arcInProgress;

	/**
	 * EMP reaching a ship at a local (unscaled) point jumps to the nearest working weapon or engine
	 * within reach; enough of it disables that component until repaired. Drawn as an arc.
	 */
	void empHit(Ship3D target, Vec3 local, float emp, Vec3 arcFromWorld) {
		empHit(target, local, emp, arcFromWorld, Math.max(tuning.empArcMin, target.baseRadius * tuning.empArcFrac));
	}

	/** As above with an explicit reach (unscaled units); piercing arcs seek the nearest component anywhere. */
	void empHit(Ship3D target, Vec3 local, float emp, Vec3 arcFromWorld, float reach) {
		if (emp <= 0f || target.destroyed || target.phased) return;
		emp *= target.empTakenMult;
		float best = reach * reach;
		WeaponMount bestM = null;
		int bestE = -1;
		for (WeaponMount m : target.weapons) {
			if (m.disabledFor > 0f) continue;
			float d2 = dist2(m.localPos, local);
			if (d2 < best) {
				best = d2;
				bestM = m;
			}
		}
		if (target.engineDown != null) {
			for (int i = 0; i < target.engines.size(); i++) {
				if (target.engineDown[i] > 0f) continue;
				float d2 = dist2(target.engines.get(i).pos, local);
				if (d2 < best) {
					best = d2;
					bestM = null;
					bestE = i;
				}
			}
		}
		if (bestM == null && bestE < 0) return;
		Vec3 compLocal = bestM != null ? bestM.localPos : target.engines.get(bestE).pos;
		Vec3 to = target.rot.rotate(compLocal.x * target.scale, compLocal.y * target.scale, compLocal.z * target.scale, new Vec3()).add(target.pos);
		Vec3 from = arcFromWorld != null ? arcFromWorld
				: target.rot.rotate(local.x * target.scale, local.y * target.scale, local.z * target.scale, new Vec3()).add(target.pos);
		boolean knockedOut = false;
		if (bestM != null) {
			bestM.hp -= emp;
			if (bestM.hp <= 0f) {
				bestM.hp = 0f;
				bestM.disabledFor = target.weaponRepairTime;
				knockedOut = true;
			}
		} else {
			target.engineHp[bestE] -= emp;
			if (target.engineHp[bestE] <= 0f) {
				target.engineHp[bestE] = 0f;
				target.engineDown[bestE] = target.engineRepairTime;
				knockedOut = true;
			}
		}
		if (emp < 40f && !knockedOut) return; // beams trickle EMP every frame; only real jolts get an arc
		sounds.play(knockedOut ? (bestM != null ? "disabled_small" : "engine_disabled") : "shock_repeater_emp_impact", to, 0.8f);
		Effect arc = new Effect(Effect.Kind.ARC, from, Math.max(2.5f, 2f * target.scale), 0.25f).color(0.55f, 0.75f, 1f);
		arc.end.set(to);
		arc.vel.set(target.vel);
		effects.add(arc);
	}

	/** Chance an ion/tachyon arc pierces a raised shield: grows with the hard flux it holds. */
	static float pierceChance(Ship3D target) {
		if (target.flux == null || target.flux.maxFlux <= 0f) return 0f;
		return Math.max(0f, Math.min(0.9f, target.flux.hardFlux / target.flux.maxFlux - 0.1f));
	}

	/** An arc jumps from a shield hit through the shield to the hull, carrying damage and EMP. */
	private void piercingArc(Ship3D attacker, Ship3D target, Vec3 fromWorld, float dmg, float emp, WeaponSpec3D w) {
		// Hull point under the shield hit: march from the hit toward the ship's centre.
		Vec3 hit = new Vec3(), local = new Vec3();
		if (segmentHit(target, fromWorld, target.pos, hit, local) < 0f) return;
		arcInProgress = true;
		float[] dealt;
		try {
			dealt = dealDamage(attacker, target, dmg, dmg * w.armorMult, w, local);
		} finally {
			arcInProgress = false;
		}
		tally(attacker, target, hit, dealt[0] + dealt[1], dealt[1] > 0.5f, dealt[0] > 0.5f);
		empHit(target, local, emp, fromWorld, Float.MAX_VALUE / 4f);
		if (target.hull <= 0f && !target.destroyed) destroy(target);
	}

	private static float dist2(Vec3 a, Vec3 b) {
		float dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
		return dx * dx + dy * dy + dz * dz;
	}

	// ---- proximity fuses and splitting missiles ---------------------------------------------

	/**
	 * An enemy missile (not self), enemy fighter, or enemy ship's hull within range of a fused shot
	 * (vanilla's fuse goes off by ships too: flak bursts just short of a hull, a proximity charge as
	 * one passes).
	 */
	private boolean proximityTrigger(Vec3 at, float range, int owner, Missile3D self) {
		for (Missile3D ms : missiles) {
			if (ms == self || ms.owner == owner || ms.flamedOut() || ms.hp <= 0f) continue;
			float r = range + ms.spec.missileRadius;
			if (dist2(ms.pos, at) <= r * r) return true;
		}
		for (Ship3D s : world) {
			if (s.destroyed || s.phased || s.owner == owner) continue;
			if (s.fighter) {
				float r = range + s.radius * 0.7f;
				if (dist2(s.pos, at) <= r * r) return true;
				continue;
			}
			float r = range + s.radius * 1.15f + 2f; // bounding sphere first, the hull's shape only when that's close
			if (dist2(s.pos, at) <= r * r && s.surfaceDistance(at) <= range) return true;
		}
		return false;
	}

	/**
	 * Blast: full damage within the core radius, falling to half at the edge. Hits enemy missiles,
	 * and enemy ships whose hull (or raised shield) is within the blast.
	 */
	private void detonate(WeaponSpec3D w, Vec3 at, Ship3D source, int owner, Missile3D self) {
		areaBlast(w, at, source, owner, self);
	}

	/**
	 * A damaging blast of w (radius proxRadius, full damage within proxCore) at `at`: hits enemy
	 * missiles and enemy hulls or raised shields within reach. spare (a missile or ship) isn't hit:
	 * the fused shell itself, or what a Paladin beam is already burning.
	 */
	private void areaBlast(WeaponSpec3D w, Vec3 at, Ship3D source, int owner, Object spare) {
		areaBlast(w, at, source, owner, spare, 1f);
	}

	/** As above with w's damage x mult. owner -1: friendly fire (everyone but the source's own structure). */
	void areaBlast(WeaponSpec3D w, Vec3 at, Ship3D source, int owner, Object spare, float mult) {
		float radius = Math.max(w.proxRadius, w.proxRange), core = Math.min(radius, Math.max(w.proxCore, w.proxRange));
		float damage = w.damage * mult;
		Effect boom = new Effect(Effect.Kind.EXPLOSION, at, radius, 0.35f).color(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2]);
		effects.add(boom);
		sounds.play(w.blastSound != null ? w.blastSound : "explosion_flak", at, 0.9f);
		blast(at, radius, null);
		for (Missile3D ms : missiles) {
			if (ms == spare || ms.owner == owner) continue;
			float d = (float) Math.sqrt(dist2(ms.pos, at)) - ms.spec.missileRadius;
			if (d <= radius) ms.hp -= damage * falloff(d, core, radius);
		}
		Vec3 hit = new Vec3(), local = new Vec3(), sHit = new Vec3(), sDir = new Vec3();
		float[] sT = new float[1];
		for (Ship3D s : world) {
			if (s == spare || s.destroyed || s.phased || sameStructure(s, source) || s.owner == owner || s.volume == null) continue;
			if (at.distance(s.pos) > s.radius * 1.2f + radius) continue;
			Vec3 toward = new Vec3(s.pos).sub(at);
			float len = toward.length();
			Vec3 end = len > radius ? new Vec3(at).addScaled(toward, radius / len) : new Vec3(s.pos);
			Ship3D shielded = shieldOnSegment(List.of(s), at, end, source, 2f, sHit, sDir, sT);
			if (shielded != null) {
				shieldDamage(source, s, damage * falloff(sHit.distance(at), core, radius), w, false, sHit, sDir, true);
				continue;
			}
			if (segmentHit(s, at, end, hit, local) < 0f) continue;
			float[] dealt = dealDamage(source, s, damage * falloff(hit.distance(at), core, radius), damage * w.armorMult, w, local);
			damageText(hit, source, s, dealt);
			if (s.hull <= 0f && !s.destroyed) destroy(s);
		}
	}

	private static float falloff(float d, float core, float radius) {
		if (d <= core) return 1f;
		return 1f - 0.5f * Math.min(1f, (d - core) / Math.max(1f, radius - core));
	}

	/**
	 * Splitting missile: warheads fan out (round the arc offset from the lead direction: Hydra's and
	 * Hurricane's spill out backwards), evenly over the arc or at random in it, plus the behaviour's
	 * scatter. Shell warheads fly straight. Missile warheads come out facing the target, as vanilla
	 * spawns them, drifting along the fan, and go after the same target under their own guidance:
	 * Pilum's second stage homes, Hydra's DEM warheads swing round and attack, Hurricane's (two-stage)
	 * line up from where the fan took them, lock on and burn in.
	 */
	private void split(Missile3D ms) {
		WeaponSpec3D w = ms.spec, h = w.mirvWarhead;
		boolean missile = h.kind == WeaponSpec3D.Kind.MISSILE;
		float lead = missile ? Math.max(1f, h.missileMaxSpeed) : h.projectileSpeed;
		Vec3 aim = new Vec3();
		if (!interceptCurved(ms.pos, ms.vel, ms.target.pos, ms.target.vel, ms.target.accel, lead, aim)) aim.set(ms.target.pos);
		Vec3 dir = new Vec3(aim).sub(ms.pos).normalize();
		Vec3 side = new Vec3(Math.abs(dir.y) < 0.9f ? 0f : 1f, Math.abs(dir.y) < 0.9f ? 1f : 0f, 0f).cross(dir).normalize();
		float off = (float) Math.toRadians(w.mirvArcOffsetDeg);
		if (off != 0f) dir.scale((float) Math.cos(off)).addScaled(side, (float) Math.sin(off)).normalize();
		side.set(Math.abs(dir.y) < 0.9f ? 0f : 1f, Math.abs(dir.y) < 0.9f ? 1f : 0f, 0f).cross(dir).normalize();
		float arc = (float) Math.toRadians(w.mirvArcDeg);
		Vec3 facing = new Vec3(ms.target.pos).sub(ms.pos);
		if (facing.lengthSq() < 1e-4f) facing.set(ms.dir);
		facing.normalize();
		for (int i = 0; i < w.mirvCount; i++) {
			float a = !w.mirvEvenSpread ? arc * (rnd.nextFloat() - 0.5f) : w.mirvCount == 1 ? 0f : -arc * 0.5f + arc * i / (w.mirvCount - 1);
			Vec3 d = new Vec3(dir).scale((float) Math.cos(a)).addScaled(side, (float) Math.sin(a)).normalize();
			if (w.mirvInaccuracyDeg > 0f) jitter(d, (float) Math.toRadians(w.mirvInaccuracyDeg * 0.5f));
			float speed = (missile ? w.mirvSpreadSpeed : h.projectileSpeed) + rnd.nextFloat() * w.mirvSpreadSpeedRandom;
			if (missile) {
				Missile3D wm = new Missile3D();
				wm.spec = h;
				wm.source = ms.source;
				wm.owner = ms.owner;
				wm.target = h.guided ? ms.target : null;
				wm.hp = Math.max(1f, h.missileHp);
				wm.pos.set(ms.pos);
				wm.prev.set(ms.pos);
				wm.spawnPos.set(ms.pos);
				wm.dir.set(facing);
				wm.vel.set(d).scale(speed).add(ms.vel);
				initDem(wm);
				spawned.add(wm);
				continue;
			}
			Projectile p = new Projectile();
			p.origin.set(ms.pos);
			p.pos.set(ms.pos);
			p.prev.set(ms.pos);
			p.dir.set(d);
			p.vel.set(d).scale(speed).add(ms.vel);
			p.source = ms.source;
			p.spec = h;
			p.maxRange = h.range;
			projectiles.add(p);
		}
		effects.add(new Effect(Effect.Kind.FLASH, ms.pos, w.missileRadius * 3f + 15f, 0.25f).color(w.missileGlow[0], w.missileGlow[1], w.missileGlow[2]));
	}

	// ---- shields ---------------------------------------------------------------------------

	private final Vec3 segHit = new Vec3(), segDir = new Vec3();

	/**
	 * First raised shield (any ship but skip) that segment a->b enters before parameter maxT.
	 * Writes the hit point, the ship-local shield direction and the parameter. Runs for every shot
	 * and missile every frame, so it works in scratch vectors.
	 */
	Ship3D shieldOnSegment(List<Ship3D> ships, Vec3 a, Vec3 b, Ship3D skip, float maxT, Vec3 hitOut, Vec3 dirOut, float[] tOut) {
		Ship3D best = null;
		float bestT = maxT;
		Vec3 h = segHit, d = segDir;
		for (Ship3D s : ships) {
			if (sameStructure(s, skip) || s.destroyed || s.phased || s.flux == null || !s.flux.shieldUp()) continue;
			float t = s.flux.segmentHit(s, a, b, h, d);
			if (t >= 0f && t < bestT) {
				bestT = t;
				best = s;
				hitOut.set(h);
				dirOut.set(d);
			}
		}
		tOut[0] = bestT;
		return best;
	}

	/**
	 * A hit on a raised shield: damage x type multiplier becomes flux (hard; soft for beams), which
	 * can overload the target. Returns the shield damage.
	 */
	float shieldDamage(Ship3D attacker, Ship3D target, float dmg, WeaponSpec3D w, boolean soft, Vec3 at, Vec3 localDir, boolean text) {
		float sd = dmg * w.shieldMult * target.sysShieldDamageTakenMult * shieldMarkMult(target); // Fortress Shield, graviton beams
		boolean wasOverloaded = target.flux.overloaded();
		target.flux.absorb(sd, soft || w.softOnShields, localDir, tuning); // Voidblaster: soft flux
		if (!soft && w.empArcs && w.emp > 0f && rnd.nextFloat() < pierceChance(target)) piercingArc(attacker, target, at, dmg, w.emp, w);
		if (attacker != null && attacker != target) {
			target.lastHitBy = attacker;
			if (target == numbersFor) attacker.lastHitPlayerAt = clock;
		}
		float[] rc = target.flux.ringColor;
		if (!soft) {
			boolean energy = "ENERGY".equals(w.damageType);
			sounds.play(sd >= 150f ? (energy ? "hit_shield_heavy_energy" : "hit_shield_heavy_gun") : (energy ? "hit_shield_solid_energy" : "hit_shield_solid_gun"), at, 0.8f);
			Effect spark = new Effect(Effect.Kind.SPARK, at, Math.max(12f, w.projWidth * 4f) + sd * 0.03f, 0.3f).color(rc[0], rc[1], rc[2]);
			spark.vel.set(target.vel);
			effects.add(spark);
		}
		if (!wasOverloaded && target.flux.overloaded()) {
			Effect flash = new Effect(Effect.Kind.EXPLOSION, target.pos, target.radius * 1.2f, 0.5f).color(rc[0], rc[1], rc[2]);
			flash.vel.set(target.vel);
			effects.add(flash);
		}
		tally(attacker, target, at, sd, false, false);
		if (text && showNumbers(attacker, target)) {
			Effect t = new Effect(Effect.Kind.DAMAGE_TEXT, at, 0f, 1.1f).color(0.55f, 0.8f, 1f);
			t.text = String.valueOf(Math.round(sd));
			t.vel.set(target.vel).add(new Vec3(0f, 25f, 0f));
			effects.add(t);
		}
		return sd;
	}

	private boolean showNumbers(Ship3D attacker, Ship3D target) {
		if (mergeNumbers) return false; // merged numbers go through tally()
		return numbersFor == null || attacker == numbersFor || target == numbersFor;
	}

	/** Adds a hit you dealt to the target's running number (merged mode). */
	private void tally(Ship3D attacker, Ship3D target, Vec3 at, float amount, boolean hull, boolean armor) {
		if (attacker != null && attacker == numbersFor && target != null && target.owner != attacker.owner && amount >= 0.5f) {
			if (hull || armor) lastHullHitAt = clock;
			else lastShieldHitAt = clock;
		}
		if (!mergeNumbers || attacker == null || attacker != numbersFor || target == null || amount < 0.5f) return;
		Tally t = tallies.computeIfAbsent(target, k -> new Tally());
		t.sum += amount;
		t.hull |= hull;
		t.armor |= armor;
		t.at.set(at);
		t.vel.set(target.vel);
	}

	private void flushTallies(float dt) {
		if (tallies.isEmpty()) return;
		for (java.util.Iterator<Tally> it = tallies.values().iterator(); it.hasNext(); ) {
			Tally t = it.next();
			t.age += dt;
			if (t.age < MERGE_SECONDS) continue;
			Effect text = new Effect(Effect.Kind.DAMAGE_TEXT, t.at, 0f, 1.1f);
			text.text = String.valueOf(Math.round(t.sum));
			if (t.hull) text.color(1f, 0.45f, 0.35f);
			else if (t.armor) text.color(1f, 0.85f, 0.4f);
			else text.color(0.55f, 0.8f, 1f);
			text.vel.set(t.vel).add(new Vec3(0f, 25f, 0f));
			effects.add(text);
			it.remove();
		}
	}

	void damageText(Vec3 at, Ship3D attacker, Ship3D target, float[] dealt) {
		if (target != null && target == numbersFor && dealt[0] + dealt[1] > 0.5f) {
			hurtAmount = (clock - hurtAt < 0.2f ? hurtAmount : 0f) + dealt[0] + dealt[1];
			hurtAt = clock;
			hurtFrom.set(attacker != null && attacker != target ? attacker.pos : at);
		}
		tally(attacker, target, at, dealt[0] + dealt[1], dealt[1] > 0.5f, dealt[0] > 0.5f);
		if (!showNumbers(attacker, target)) return;
		Effect text = new Effect(Effect.Kind.DAMAGE_TEXT, at, 0f, 1.1f);
		text.text = String.valueOf(Math.round(dealt[0] + dealt[1]));
		if (dealt[1] > 0.5f) text.color(1f, 0.45f, 0.35f);
		else text.color(1f, 0.85f, 0.4f);
		text.vel.set(target.vel).add(new Vec3(0f, 25f, 0f));
		effects.add(text);
	}

	/** Clears all shots, missiles and effects (sandbox reset). */
	public void clear() {
		projectiles.clear();
		missiles.clear();
		effects.clear();
		tallies.clear();
		flareLife.clear();
		payloads.clear();
		spawned.clear();
		shieldMarks.clear();
		burns.clear();
		ridden.clear();
		exotic.clear();
		burning.clear();
	}

	// ---- projectiles -----------------------------------------------------------------------

	public void stepProjectiles(List<Ship3D> ships, float dt) {
		if (dt <= 0f) return;
		Vec3 hit = new Vec3(), localOut = new Vec3(), sHit = new Vec3(), sDir = new Vec3();
		float[] sT = new float[1];
		for (Iterator<Projectile> it = projectiles.iterator(); it.hasNext(); ) {
			Projectile p = it.next();
			p.prev.set(p.pos);
			p.pos.addScaled(p.vel, dt);
			int pOwner = p.source != null ? p.source.owner : -1;
			if (p.spec.proxRange > 0f && proximityTrigger(p.pos, p.spec.proxRange, pOwner, null)) {
				detonate(p.spec, p.pos, p.source, pOwner, null);
				it.remove();
				continue;
			}
			Ship3D best = null;
			float bestT = 2f;
			for (Ship3D s : ships) {
				if (sameStructure(s, p.source) || s.destroyed || s.phased || s.volume == null || s.fighter && p.spec.passFighters) continue;
				float t = segmentHit(s, p.prev, p.pos, hit, localOut);
				if (t >= 0f && t < bestT) {
					bestT = t;
					best = s;
				}
			}
			Ship3D shielded = shieldOnSegment(ships, p.prev, p.pos, p.source, bestT, sHit, sDir, sT);
			if (shielded != null) bestT = sT[0];
			// Point defence: shots also hit enemy missiles crossing their path.
			Missile3D bestMissile = null;
			int owner = p.source != null ? p.source.owner : -1;
			for (Missile3D ms : missiles) {
				if (ms.owner == owner || p.spec.passMissiles) continue;
				float t = segmentSphere(p.prev, p.pos, ms.pos, ms.spec.missileRadius + 3f);
				if (t >= 0f && t < bestT) {
					bestT = t;
					bestMissile = ms;
				}
			}
			if (bestMissile == null && shielded != null) {
				shieldDamage(p.source, shielded, p.spec.damage * p.damageMult, p.spec, false, sHit, sDir, true);
				push(shielded, sHit, p.vel, shotImpulse(p.spec, p.spec.damage * p.damageMult));
				onHitEffects(p.source, shielded, p.spec, sHit, null, true);
				it.remove();
			} else if (bestMissile != null) {
				bestMissile.hp -= p.spec.damage;
				effects.add(new Effect(Effect.Kind.SPARK, bestMissile.pos, 12f, 0.2f)
						.color(p.spec.fringeColor[0], p.spec.fringeColor[1], p.spec.fringeColor[2]));
				it.remove();
			} else if (best != null) {
				segmentHit(best, p.prev, p.pos, hit, localOut);
				applyHit(best, p, hit, localOut);
				it.remove();
			} else if (p.distanceFromOrigin() > p.maxRange) {
				if (p.spec.proxRange > 0f) detonate(p.spec, p.pos, p.source, pOwner, null); // a fused shell bursts where its range runs out (vanilla flak)
				it.remove();
			}
		}
	}

	/**
	 * First entry of segment a->b into the ship's hull volume. Returns the segment parameter (0..1)
	 * or -1; writes the world hit point and the local (unscaled) point just outside the hull.
	 */
	float segmentHit(Ship3D s, Vec3 a, Vec3 b, Vec3 hitOut, Vec3 localOutsideOut) {
		// Broad phase: segment vs bounding sphere.
		float r = s.radius * 1.15f + 2f;
		Vec3 d = tA.set(b).sub(a);
		float len2 = d.lengthSq();
		Vec3 m = tB.set(a).sub(s.pos);
		float tc = len2 > 0f ? -m.dot(d) / len2 : 0f;
		tc = Math.max(0f, Math.min(1f, tc));
		float cx = m.x + d.x * tc, cy = m.y + d.y * tc, cz = m.z + d.z * tc;
		if (cx * cx + cy * cy + cz * cz > r * r) return -1f;

		// Narrow phase: march the segment through the volume in the ship's local frame.
		float len = (float) Math.sqrt(len2);
		float step = Math.max(1.5f, s.volume.cell * s.scale);
		int n = Math.max(1, (int) Math.ceil(len / step));
		float prevT = 0f;
		for (int i = 0; i <= n; i++) {
			float t = (float) i / n;
			if (inside(s, a, d, t)) {
				if (i == 0) {
					localPoint(s, a, d, 0f, localOutsideOut);
					hitOut.set(a);
					return 0f;
				}
				float lo = prevT, hi = t;
				for (int k = 0; k < 6; k++) {
					float mid = (lo + hi) * 0.5f;
					if (inside(s, a, d, mid)) hi = mid;
					else lo = mid;
				}
				localPoint(s, a, d, lo, localOutsideOut);
				hitOut.set(a).addScaled(d, hi);
				return hi;
			}
			prevT = t;
		}
		return -1f;
	}

	private boolean inside(Ship3D s, Vec3 a, Vec3 d, float t) {
		Vec3 l = localPoint(s, a, d, t, tC);
		return s.volume.contains(l.x, l.y, l.z);
	}

	private static Vec3 localPoint(Ship3D s, Vec3 a, Vec3 d, float t, Vec3 out) {
		out.set(a.x + d.x * t - s.pos.x, a.y + d.y * t - s.pos.y, a.z + d.z * t - s.pos.z);
		s.rot.inverseRotate(out, out);
		return out.scale(1f / Math.max(1e-3f, s.scale));
	}

	/** Vanilla-style armor/hull damage from one projectile. */
	private void applyHit(Ship3D target, Projectile p, Vec3 hitWorld, Vec3 localOutside) {
		WeaponSpec3D w = p.spec;
		push(target, hitWorld, p.vel, shotImpulse(w, w.damage * p.damageMult));
		float[] dealt = dealDamage(p.source, target, w.damage * p.damageMult, w.damage * p.damageMult * w.armorMult, w, localOutside);
		onHitEffects(p.source, target, w, hitWorld, localOutside, false);
		Effect spark = new Effect(Effect.Kind.SPARK, hitWorld, Math.max(10f, w.projWidth * 4f) + w.damage * 0.04f, 0.25f)
				.color(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2]);
		spark.vel.set(target.vel);
		effects.add(spark);
		damageText(hitWorld, p.source, target, dealt);
		boolean energy = "ENERGY".equals(w.damageType);
		sounds.play(w.damage >= 150f ? (energy ? "hit_hull_heavy_energy" : "hit_hull_heavy") : (energy ? "hit_hull_solid_energy" : "hit_hull_solid"), hitWorld, 0.8f);
	}

	/** Destroys a ship the host says is gone (explosion, wreck, its modules with it). */
	public void kill(Ship3D s) {
		destroy(s);
	}

	private void destroy(Ship3D s) {
		if (s.destroyed) return;
		s.destroyed = true;
		if (world != null) for (Ship3D o : world) if (o.parent == s) destroy(o); // a ship's modules die with it
		// Wrecks aren't stepped any more: shut every weapon down now, or a beam firing at the moment of
		// death would stay drawn on the wreck.
		for (WeaponMount m : s.weapons) {
			m.beamFiring = false;
			m.beamHitting = false;
			m.beamLevel = 0f;
			m.firedThisFrame = false;
			m.burstShotsLeft = 0;
			m.flash = 0f;
		}
		s.thrustVisual = 0f;
		s.angVel.set((rnd.nextFloat() - 0.5f) * 0.6f, (rnd.nextFloat() - 0.5f) * 0.6f, (rnd.nextFloat() - 0.5f) * 0.6f);
		sounds.play("explosion_ship", s.pos, s.fighter ? 0.4f : 1.2f);
		blast(s.pos, s.radius * 2.5f, s);
		if (s.deathBlast != null) areaBlast(s.deathBlast, s.pos, s, s.owner, s); // ejecta, vortices: 2000 energy on enemies only
		if (numbersFor != null && s.lastHitBy == numbersFor && s.owner != numbersFor.owner) {
			lastKillAt = clock;
			lastKillShip = !s.fighter;
		}
		deathVisuals(s);
	}

	/**
	 * The big blast, a shockwave, sparks thrown off the hull; then (not fighters) a few secondary
	 * pops across the hull over the next couple of seconds, and the wreck burns for a while.
	 */
	private void deathVisuals(Ship3D s) {
		Effect boom = new Effect(Effect.Kind.EXPLOSION, s.pos, s.radius * 2.2f, 1.4f).color(1f, 0.65f, 0.3f);
		boom.vel.set(s.vel);
		effects.add(boom);
		Effect flash = new Effect(Effect.Kind.FLASH, s.pos, s.radius * 3f, 0.25f).color(1f, 0.9f, 0.75f);
		flash.vel.set(s.vel);
		effects.add(flash);
		Effect ring = new Effect(Effect.Kind.RING, s.pos, s.radius * (s.fighter ? 3f : 4.5f), s.fighter ? 0.5f : 1.1f).color(1f, 0.72f, 0.45f);
		ring.vel.set(s.vel);
		effects.add(ring);
		for (int i = 0; i < 12; i++) {
			Effect f = new Effect(Effect.Kind.FLASH, s.pos, s.radius * (0.4f + rnd.nextFloat() * 0.5f), 0.6f + rnd.nextFloat() * 0.8f)
					.color(1f, 0.55f + rnd.nextFloat() * 0.3f, 0.25f);
			f.vel.set(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize().scale(s.radius * 0.6f).add(s.vel);
			effects.add(f);
		}
		int sparks = s.fighter ? 6 : 18;
		for (int i = 0; i < sparks; i++) { // hot debris flung out fast
			Effect d = new Effect(Effect.Kind.FLASH, s.pos, Math.max(3f, s.radius * (0.04f + rnd.nextFloat() * 0.05f)), 0.5f + rnd.nextFloat() * 1.2f)
					.color(1f, 0.85f + rnd.nextFloat() * 0.15f, 0.55f);
			d.vel.set(rnd.nextFloat() - 0.5f, (rnd.nextFloat() - 0.5f) * 0.6f, rnd.nextFloat() - 0.5f).normalize().scale(s.radius * (1.5f + rnd.nextFloat() * 3f)).add(s.vel);
			effects.add(d);
		}
		if (s.fighter) return;
		int pops = "capital".equals(s.sizeClass) ? 6 : "cruiser".equals(s.sizeClass) ? 4 : "destroyer".equals(s.sizeClass) ? 3 : 2;
		Vec3 at = new Vec3();
		for (int i = 0; i < pops; i++) {
			hullPoint(s, at);
			float when = 0.35f + (i + rnd.nextFloat()) * (1.8f / pops);
			Effect pop = new Effect(Effect.Kind.EXPLOSION, at, s.radius * (0.5f + rnd.nextFloat() * 0.4f), 0.7f).color(1f, 0.6f, 0.28f)
					.delayed(when).withSound("explosion_ship", 0.45f);
			pop.vel.set(s.vel);
			effects.add(pop);
		}
		burning.put(s, new float[] {Math.min(14f, 6f + s.radius * 0.02f), 0f});
	}

	/** Visual effects age and move; lingering weapon effects (graviton marks, disintegrator burns) tick too. */
	public void stepEffects(float dt) {
		flushTallies(dt);
		if (dt <= 0f) return;
		stepLingering(dt);
		exotic.step(dt); // rifts, Reality Disruptor orbs, Defabrication swarms, fragment swarms, slowed repairs
		for (Iterator<Effect> it = effects.iterator(); it.hasNext(); ) {
			Effect e = it.next();
			e.pos.addScaled(e.vel, dt);
			if (e.delay > 0f) { // staged: drifts along until its moment, then plays its sound
				e.delay -= dt;
				if (e.delay <= 0f && e.sound != null) sounds.play(e.sound, e.pos, e.soundVolume);
				continue;
			}
			e.life -= dt;
			if (e.kind == Effect.Kind.ARC) e.end.addScaled(e.vel, dt);
			if (e.life <= 0f) it.remove();
		}
		stepWreckFires(dt);
	}

	/** Fresh wrecks keep burning for a while: flames licking off the hull, now and then a small pop. */
	private void stepWreckFires(float dt) {
		if (burning.isEmpty()) return;
		Vec3 at = new Vec3();
		for (Iterator<java.util.Map.Entry<Ship3D, float[]>> it = burning.entrySet().iterator(); it.hasNext(); ) {
			java.util.Map.Entry<Ship3D, float[]> en = it.next();
			Ship3D s = en.getKey();
			float[] b = en.getValue();
			b[0] -= dt;
			if (b[0] <= 0f || !s.destroyed || world != null && !world.contains(s)) { // burnt out, repaired (sandbox) or gone
				it.remove();
				continue;
			}
			float heat = Math.min(1f, b[0] / 4f); // dies down over the last few seconds
			b[1] += dt * heat * Math.min(25f, 2f + s.radius * 0.05f); // flames per second, more on bigger hulls
			while (b[1] >= 1f) {
				b[1] -= 1f;
				hullPoint(s, at);
				Effect f = new Effect(Effect.Kind.FLASH, at, Math.max(5f, s.radius * (0.06f + rnd.nextFloat() * 0.08f)), 0.45f + rnd.nextFloat() * 0.5f)
						.color(1f, 0.42f + rnd.nextFloat() * 0.3f, 0.12f);
				f.vel.set(s.vel).add(new Vec3(rnd.nextFloat() - 0.5f, 0.6f + rnd.nextFloat(), rnd.nextFloat() - 0.5f).scale(s.radius * 0.12f));
				effects.add(f);
				if (rnd.nextFloat() < 0.08f * heat) {
					Effect pop = new Effect(Effect.Kind.EXPLOSION, at, Math.max(12f, s.radius * 0.35f), 0.5f).color(1f, 0.6f, 0.25f);
					pop.vel.set(s.vel);
					effects.add(pop);
					sounds.play("hit_hull_heavy", at, 0.5f);
				}
			}
		}
	}

	/** A point on the hull: one of its weapon mounts if it has any, else near its middle. */
	private void hullPoint(Ship3D s, Vec3 out) {
		if (!s.weapons.isEmpty() && rnd.nextFloat() < 0.75f) {
			s.weapons.get(rnd.nextInt(s.weapons.size())).worldPos(s, out);
		} else {
			out.set(rnd.nextFloat() - 0.5f, (rnd.nextFloat() - 0.5f) * 0.3f, rnd.nextFloat() - 0.5f).scale(s.radius * 0.7f).add(s.pos);
		}
	}

	/** A retreating ship leaving the battle: a flash stretched ahead along its course and a ring, instead of just vanishing. */
	public void jumpOut(Ship3D s) {
		Vec3 dir = s.vel.lengthSq() > 1f ? new Vec3(s.vel).normalize() : s.rot.forward(new Vec3());
		for (int i = 0; i < 8; i++) {
			float k = i / 7f;
			Effect f = new Effect(Effect.Kind.FLASH, new Vec3(s.pos).addScaled(dir, s.radius * 3f * k), s.radius * (1.5f - k), 0.3f + 0.3f * (1f - k))
					.color(0.6f, 0.8f, 1f);
			f.vel.set(dir).scale(s.radius * 8f);
			effects.add(f);
		}
		effects.add(new Effect(Effect.Kind.RING, s.pos, s.radius * 3f, 0.6f).color(0.55f, 0.75f, 1f));
		sounds.play("system_burn_drive_activate", s.pos, 0.9f);
	}

	// ---- aiming math -----------------------------------------------------------------------

	/**
	 * Where to aim so a projectile of the given speed (fired from shooterPos, inheriting shooterVel)
	 * meets the target. Returns false if no intercept exists.
	 */
	public static boolean intercept(Vec3 shooterPos, Vec3 shooterVel, Vec3 targetPos, Vec3 targetVel, float speed, Vec3 out) {
		float px = targetPos.x - shooterPos.x, py = targetPos.y - shooterPos.y, pz = targetPos.z - shooterPos.z;
		float vx = targetVel.x - shooterVel.x, vy = targetVel.y - shooterVel.y, vz = targetVel.z - shooterVel.z;
		float a = vx * vx + vy * vy + vz * vz - speed * speed;
		float b = 2f * (px * vx + py * vy + pz * vz);
		float c = px * px + py * py + pz * pz;
		float time;
		if (Math.abs(a) < 1e-6f) {
			if (Math.abs(b) < 1e-6f) return false;
			time = -c / b;
		} else {
			float disc = b * b - 4f * a * c;
			if (disc < 0f) return false;
			float sq = (float) Math.sqrt(disc);
			float t1 = (-b - sq) / (2f * a), t2 = (-b + sq) / (2f * a);
			time = Math.min(t1, t2) > 0f ? Math.min(t1, t2) : Math.max(t1, t2);
		}
		if (time <= 0f) return false;
		out.set(targetPos.x + vx * time, targetPos.y + vy * time, targetPos.z + vz * time);
		return true;
	}

	/**
	 * Like {@link #intercept} but predicts along a curved path: the target keeps its current
	 * acceleration (so a turning ship is led along its turn, not off the tangent). Writes the point the
	 * barrel must aim at (in the shooter's moving frame, i.e. future target position minus how far the
	 * inherited shooter velocity carries the shot).
	 */
	public static boolean interceptCurved(Vec3 shooterPos, Vec3 shooterVel, Vec3 targetPos, Vec3 targetVel, Vec3 targetAccel,
										  float speed, Vec3 out) {
		if (!intercept(shooterPos, shooterVel, targetPos, targetVel, speed, out)) return false;
		if (targetAccel == null || targetAccel.lengthSq() < 1e-4f) return true;
		// Time of flight from the linear solution, then refine: t = |P(t) - M - Vs*t| / s.
		float t = out.distance(shooterPos) / speed;
		float px = 0f, py = 0f, pz = 0f;
		for (int i = 0; i < 6; i++) {
			float h = 0.5f * t * t;
			px = targetPos.x + targetVel.x * t + targetAccel.x * h - shooterVel.x * t;
			py = targetPos.y + targetVel.y * t + targetAccel.y * h - shooterVel.y * t;
			pz = targetPos.z + targetVel.z * t + targetAccel.z * h - shooterVel.z * t;
			float dx = px - shooterPos.x, dy = py - shooterPos.y, dz = pz - shooterPos.z;
			float nt = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / speed;
			if (Math.abs(nt - t) < 1e-4f) {
				t = nt;
				break;
			}
			t = nt;
		}
		// Too far ahead, or diverged, keep the linear answer. A target all but outrunning the shot gives a
		// huge linear time and t^2 overflows: px = Inf, py = NaN, t = NaN. NaN fails every comparison, and a
		// NaN aim point left the turret's aim direction NaN for the rest of the battle (bombers' torpedoes
		// flew as NaN), so test "not <= 30" and every component.
		if (!(t <= 30f) || !Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)) return true;
		out.set(px, py, pz);
		return true;
	}

	/** Clamps unit vector v to within halfAngle of axis (both unit vectors). */
	static void clampToCone(Vec3 v, Vec3 axis, float halfAngle) {
		if (halfAngle >= Math.PI - 1e-3) return;
		float cos = Math.max(-1f, Math.min(1f, v.dot(axis)));
		float ang = (float) Math.acos(cos);
		if (ang <= halfAngle) return;
		Vec3 perp = new Vec3(v).addScaled(axis, -cos);
		if (perp.lengthSq() < 1e-8f) {
			// v points straight back along the axis: any perpendicular will do.
			perp.set(Math.abs(axis.y) < 0.9f ? 0f : 1f, Math.abs(axis.y) < 0.9f ? 1f : 0f, 0f);
			perp.addScaled(axis, -perp.dot(axis));
		}
		perp.normalize();
		v.set(axis).scale((float) Math.cos(halfAngle)).addScaled(perp, (float) Math.sin(halfAngle)).normalize();
	}

	/** Rotates unit vector v toward target by at most maxAngle radians (great-circle). */
	static void rotateToward(Vec3 v, Vec3 target, float maxAngle) {
		float cos = Math.max(-1f, Math.min(1f, v.dot(target)));
		float ang = (float) Math.acos(cos);
		if (ang <= maxAngle || ang < 1e-5f) {
			v.set(target);
			return;
		}
		Vec3 perp = new Vec3(target).addScaled(v, -cos);
		if (perp.lengthSq() < 1e-10f) return; // opposite: any axis; wait a frame
		perp.normalize();
		float c = (float) Math.cos(maxAngle), s = (float) Math.sin(maxAngle);
		v.scale(c).addScaled(perp, s).normalize();
	}

	private void jitter(Vec3 dir, float halfAngle) {
		jitterDir(dir, halfAngle, rnd);
	}

	/** Turns a unit direction by a random amount within halfAngle (radians), spread evenly over the cone's face. */
	static void jitterDir(Vec3 dir, float halfAngle, Random rnd) {
		if (halfAngle <= 0f) return;
		Vec3 a = Math.abs(dir.y) < 0.9f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 u = new Vec3(a).cross(dir).normalize();
		Vec3 v = new Vec3(dir).cross(u).normalize();
		double r = Math.sqrt(rnd.nextDouble()) * Math.tan(halfAngle);
		double th = rnd.nextDouble() * Math.PI * 2;
		dir.addScaled(u, (float) (r * Math.cos(th))).addScaled(v, (float) (r * Math.sin(th))).normalize();
	}

	/** The battle's random numbers (seeded by the bench), for helpers outside this class. */
	Random randomSource() {
		return rnd;
	}
}
