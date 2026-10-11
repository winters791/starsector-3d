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
	/** Missile target for the shot being fired (set per stepMount call). */
	private Ship3D pendingTarget;
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
			if (w.maxAmmo > 0) m.ammo = Math.min(w.maxAmmo, m.ammo + w.ammoPerSecond * dt);
			m.spread = Math.max(w.minSpread, m.spread - w.spreadDecay * dt);
			boolean ballistic = "BALLISTIC".equals(w.type);
			float rof = ballistic ? s.sysBallisticRofMult : 1f;
			if (m.cooldownLeft > 0f) m.cooldownLeft -= dt * rof;
			if (s.sysNoFire || s.phased) trigger = false; // a system forbids firing, or phased
			if (s.flux != null && s.flux.disabled()) trigger = false; // overloaded or venting

			aim(s, m, aimPoint, dt);
			if (w.kind == WeaponSpec3D.Kind.BEAM) {
				stepBeam(s, m, trigger, dt, t);
				return;
			}
			pendingTarget = missileTarget;

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
					if (w.maxAmmo > 0 && m.ammo < 1f || s.flux != null && !s.flux.spend(w.fluxPerShot * (ballistic ? s.sysBallisticFluxMult : 1f))) {
						m.burstShotsLeft = 0; // out of ammo, or the shot would overflow flux
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
		Vec3 mw = m.worldPos(s, new Vec3());
		Vec3 d = new Vec3(point).sub(mw);
		float dist = d.length();
		if (dist > m.spec.range * t.weaponRangeMult) return false;
		if (dist < 1e-3f) return true;
		if (m.spec.kind == WeaponSpec3D.Kind.MISSILE && m.spec.guided) return true; // launched along the barrel, then steer
		s.rot.inverseRotate(d, d).scale(1f / dist);
		return d.dot(m.restDir) >= (float) Math.cos(m.halfArcRad) - 1e-4f;
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
		if (w.kind == WeaponSpec3D.Kind.MISSILE) {
			launchMissile(s, m, dir);
			return;
		}
		Projectile p = new Projectile();
		m.worldPos(s, p.origin);
		p.pos.set(p.origin);
		p.prev.set(p.origin);
		p.dir.set(dir);
		p.vel.set(dir).scale(w.projectileSpeed).add(s.vel);
		p.source = s;
		p.spec = w;
		p.maxRange = w.range * t.weaponRangeMult;
		if ("ENERGY".equals(w.type)) p.damageMult = s.sysEnergyDamageMult;
		projectiles.add(p);
		float kick = shotImpulse(w, w.damage) * tuning.recoilScale;
		if (kick > 0f) push(s, p.origin, new Vec3(dir).scale(-1f), kick);
		m.spread = Math.min(w.maxSpread, m.spread + w.spreadBuildup);
		if (w.maxAmmo > 0) m.ammo -= 1f;
		m.flash = 0.06f;
		m.firedThisFrame = true;
		Effect muzzle = new Effect(Effect.Kind.FLASH, p.origin, Math.max(6f, w.projWidth * 3f) * s.scale, 0.08f)
				.color(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2]);
		muzzle.vel.set(s.vel);
		effects.add(muzzle);
		sounds.play(w.fireSound, p.origin, 1f);
	}

	private void launchMissile(Ship3D s, WeaponMount m, Vec3 dir) {
		WeaponSpec3D w = m.spec;
		Missile3D ms = new Missile3D();
		m.worldPos(s, ms.pos);
		ms.prev.set(ms.pos);
		ms.dir.set(dir);
		ms.vel.set(dir).scale(w.launchSpeed).add(s.vel);
		ms.spec = w;
		ms.source = s;
		ms.owner = s.owner;
		ms.target = w.guided ? pendingTarget : null;
		ms.hp = Math.max(1f, w.missileHp);
		if (w.mirvCount > 0) ms.splitAt = w.mirvSplitRange + rnd.nextFloat() * w.mirvSplitRangeRandom;
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
		sounds.loop(w.beamLoopSound, m, origin, m.beamLevel, 1f);
		Vec3 dir = s.rot.rotate(m.aimDir, new Vec3());
		float range = w.range * t.weaponRangeMult;
		Vec3 hit = new Vec3(), local = new Vec3();
		Ship3D ship = rayHit(world, origin, dir, range, s, hit, local);
		float shipDist = ship != null ? hit.distance(origin) : Float.MAX_VALUE;
		Vec3 end = new Vec3(origin).addScaled(dir, Math.min(range, shipDist));
		Vec3 shieldPt = new Vec3(), shieldDir = new Vec3();
		float[] st = new float[1];
		Ship3D shielded = shieldOnSegment(world, origin, end, s, 2f, shieldPt, shieldDir, st);
		if (shielded != null) shipDist = shieldPt.distance(origin);
		Missile3D missile = missileOnRay(origin, dir, Math.min(range, shipDist), s.owner);
		float dmg = w.dps * m.beamLevel * dt * ("ENERGY".equals(w.type) ? s.sysEnergyDamageMult : 1f);
		if (missile == null && shielded != null) {
			m.beamEnd.set(shieldPt);
			m.beamHitting = true;
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
			float tm = new Vec3(missile.pos).sub(origin).dot(dir);
			m.beamEnd.set(origin).addScaled(dir, tm);
			m.beamHitting = true;
			missile.hp -= dmg;
		} else if (ship != null) {
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
	private Missile3D missileOnRay(Vec3 origin, Vec3 dir, float maxDist, int owner) {
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
			if (ms.hp <= 0f) {
				effects.add(new Effect(Effect.Kind.FLASH, ms.pos, w.missileRadius * 4f + 10f, 0.35f).color(1f, 0.7f, 0.4f));
				if (ms.flare) flareLife.remove(ms);
				it.remove();
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
				if (ms.age > w.flightTime) {
					ms.fade = 0f;
				} else {
					updateLure(ms, dt);
					if (ms.lure != null) {
						desired.set(ms.lure.pos).sub(ms.pos);
						if (desired.lengthSq() > 1e-4f) rotateToward(ms.dir, desired.normalize(), (float) Math.toRadians(w.missileTurnRate) * dt);
					} else if (ms.target != null && !ms.target.destroyed && !ms.target.phased) {
						float speed = Math.max(w.missileMaxSpeed, ms.vel.length());
						if (!interceptCurved(ms.pos, zero, ms.target.pos, ms.target.vel, ms.target.accel, speed, aim)) aim.set(ms.target.pos);
						desired.set(aim).sub(ms.pos);
						if (desired.lengthSq() > 1e-4f) rotateToward(ms.dir, desired.normalize(), (float) Math.toRadians(w.missileTurnRate) * dt);
					}
					// Push velocity toward nose * max speed, limited by the missile's acceleration.
					desired.set(ms.dir).scale(w.missileMaxSpeed).sub(ms.vel);
					float need = desired.length(), can = Math.max(1f, w.missileAccel) * dt;
					if (need > can) desired.scale(can / need);
					ms.vel.add(desired);
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
			if (w.mirvCount > 0 && w.mirvWarhead != null && ms.target != null && !ms.target.destroyed
					&& ms.age >= w.mirvMinTime && ms.pos.distance(ms.target.pos) - ms.target.radius <= ms.splitAt) {
				split(ms);
				it.remove();
				continue;
			}
			if (w.proxRange > 0f && proximityTrigger(ms.pos, w.proxRange, ms.owner, ms)) {
				detonate(w, ms.pos, ms.source, ms.owner, ms);
				it.remove();
				continue;
			}

			Ship3D best = null;
			float bestT = 2f;
			for (Ship3D s : ships) {
				if (sameStructure(s, ms.source) || s.destroyed || s.phased || s.volume == null) continue;
				float tt = segmentHit(s, ms.prev, ms.pos, hit, local);
				if (tt >= 0f && tt < bestT) {
					bestT = tt;
					best = s;
				}
			}
			Ship3D shielded = shieldOnSegment(ships, ms.prev, ms.pos, ms.source, bestT, sHit, sDir, sT);
			if (shielded != null) {
				shieldDamage(ms.source, shielded, w.damage, w, false, sHit, sDir, true);
				push(shielded, sHit, ms.vel, shotImpulse(w, w.damage));
				blast(sHit, Math.max(25f, w.explosionRadius), shielded);
				Effect boom = new Effect(Effect.Kind.EXPLOSION, sHit, Math.max(25f, w.explosionRadius), 0.6f)
						.color(w.missileGlow[0], w.missileGlow[1], w.missileGlow[2]);
				boom.vel.set(shielded.vel);
				effects.add(boom);
				it.remove();
			} else if (best != null) {
				segmentHit(best, ms.prev, ms.pos, hit, local);
				float[] dealt = dealDamage(ms.source, best, w.damage, w.damage * w.armorMult, w, local);
				push(best, hit, ms.vel, shotImpulse(w, w.damage));
				blast(hit, Math.max(25f, w.explosionRadius), best);
				Effect boom = new Effect(Effect.Kind.EXPLOSION, hit, Math.max(25f, w.explosionRadius), 0.6f)
						.color(w.missileGlow[0], w.missileGlow[1], w.missileGlow[2]);
				boom.vel.set(best.vel);
				effects.add(boom);
				damageText(hit, ms.source, best, dealt);
				sounds.play("explosion_missile", hit, 1f);
				if (best.hull <= 0f && !best.destroyed) destroy(best);
				it.remove();
			}
		}
	}

	// ---- damage ----------------------------------------------------------------------------

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
		float effArmor = Math.max(armorHere, armorRating * minArmorFraction);
		float reduction = effArmor > 0f ? Math.max(1f - maxArmorDamageReduction, hitStrength / (hitStrength + effArmor)) : 1f;
		float toArmor = dmg * w.armorMult * reduction * target.armorTakenMult;
		if (armor != null && armorHere > 0f) {
			armorDealt = Math.min(toArmor, armorHere);
			armor.strip(sector, armorDealt);
		}
		float overflow = toArmor > 0f ? Math.max(0f, toArmor - armorDealt) / toArmor : 1f;
		float hullDealt = dmg * overflow * w.hullMult * reduction * target.hullTakenMult;
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
		if (ms.flare || ms.lure != null || !ms.spec.guided) return;
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
			Effect arc = new Effect(Effect.Kind.ARC, s.pos, Math.max(3f, 2.5f * s.scale), 0.3f).color(0.55f, 0.75f, 1f);
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

	/** An enemy missile (not self) or enemy fighter within range of a fused shot. */
	private boolean proximityTrigger(Vec3 at, float range, int owner, Missile3D self) {
		for (Missile3D ms : missiles) {
			if (ms == self || ms.owner == owner || ms.flamedOut() || ms.hp <= 0f) continue;
			float r = range + ms.spec.missileRadius;
			if (dist2(ms.pos, at) <= r * r) return true;
		}
		for (Ship3D s : world) {
			if (!s.fighter || s.destroyed || s.phased || s.owner == owner) continue;
			float r = range + s.radius * 0.7f;
			if (dist2(s.pos, at) <= r * r) return true;
		}
		return false;
	}

	/**
	 * Blast: full damage within the core radius, falling to half at the edge. Hits enemy missiles,
	 * and enemy ships whose hull (or raised shield) is within the blast.
	 */
	private void detonate(WeaponSpec3D w, Vec3 at, Ship3D source, int owner, Missile3D self) {
		float radius = Math.max(w.proxRadius, w.proxRange), core = Math.min(radius, Math.max(w.proxCore, w.proxRange));
		Effect boom = new Effect(Effect.Kind.EXPLOSION, at, radius, 0.35f).color(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2]);
		effects.add(boom);
		sounds.play("explosion_flak", at, 0.9f);
		blast(at, radius, null);
		for (Missile3D ms : missiles) {
			if (ms == self || ms.owner == owner) continue;
			float d = (float) Math.sqrt(dist2(ms.pos, at)) - ms.spec.missileRadius;
			if (d <= radius) ms.hp -= w.damage * falloff(d, core, radius);
		}
		Vec3 hit = new Vec3(), local = new Vec3(), sHit = new Vec3(), sDir = new Vec3();
		float[] sT = new float[1];
		for (Ship3D s : world) {
			if (s.destroyed || s.phased || sameStructure(s, source) || s.owner == owner || s.volume == null) continue;
			if (at.distance(s.pos) > s.radius * 1.2f + radius) continue;
			Vec3 toward = new Vec3(s.pos).sub(at);
			float len = toward.length();
			Vec3 end = len > radius ? new Vec3(at).addScaled(toward, radius / len) : new Vec3(s.pos);
			Ship3D shielded = shieldOnSegment(List.of(s), at, end, source, 2f, sHit, sDir, sT);
			if (shielded != null) {
				shieldDamage(source, s, w.damage * falloff(sHit.distance(at), core, radius), w, false, sHit, sDir, true);
				continue;
			}
			if (segmentHit(s, at, end, hit, local) < 0f) continue;
			float[] dealt = dealDamage(source, s, w.damage * falloff(hit.distance(at), core, radius), w.damage * w.armorMult, w, local);
			damageText(hit, source, s, dealt);
			if (s.hull <= 0f && !s.destroyed) destroy(s);
		}
	}

	private static float falloff(float d, float core, float radius) {
		if (d <= core) return 1f;
		return 1f - 0.5f * Math.min(1f, (d - core) / Math.max(1f, radius - core));
	}

	/** Splitting missile: warheads fan out toward where the target will be. */
	private void split(Missile3D ms) {
		WeaponSpec3D w = ms.spec, h = w.mirvWarhead;
		Vec3 aim = new Vec3();
		if (!interceptCurved(ms.pos, ms.vel, ms.target.pos, ms.target.vel, ms.target.accel, h.projectileSpeed, aim)) aim.set(ms.target.pos);
		Vec3 dir = new Vec3(aim).sub(ms.pos).normalize();
		Vec3 side = new Vec3(Math.abs(dir.y) < 0.9f ? 0f : 1f, Math.abs(dir.y) < 0.9f ? 1f : 0f, 0f).cross(dir).normalize();
		float arc = (float) Math.toRadians(w.mirvArcDeg);
		for (int i = 0; i < w.mirvCount; i++) {
			float a = w.mirvCount == 1 ? 0f : -arc * 0.5f + arc * i / (w.mirvCount - 1);
			Vec3 d = new Vec3(dir).scale((float) Math.cos(a)).addScaled(side, (float) Math.sin(a)).normalize();
			jitter(d, (float) Math.toRadians(2f));
			Projectile p = new Projectile();
			p.origin.set(ms.pos);
			p.pos.set(ms.pos);
			p.prev.set(ms.pos);
			p.dir.set(d);
			p.vel.set(d).scale(h.projectileSpeed).add(ms.vel);
			p.source = ms.source;
			p.spec = h;
			p.maxRange = h.range;
			projectiles.add(p);
		}
		effects.add(new Effect(Effect.Kind.FLASH, ms.pos, w.missileRadius * 3f + 15f, 0.25f).color(w.missileGlow[0], w.missileGlow[1], w.missileGlow[2]));
	}

	// ---- shields ---------------------------------------------------------------------------

	/**
	 * First raised shield (any ship but skip) that segment a->b enters before parameter maxT.
	 * Writes the hit point, the ship-local shield direction and the parameter.
	 */
	Ship3D shieldOnSegment(List<Ship3D> ships, Vec3 a, Vec3 b, Ship3D skip, float maxT, Vec3 hitOut, Vec3 dirOut, float[] tOut) {
		Ship3D best = null;
		float bestT = maxT;
		Vec3 h = new Vec3(), d = new Vec3();
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
	private float shieldDamage(Ship3D attacker, Ship3D target, float dmg, WeaponSpec3D w, boolean soft, Vec3 at, Vec3 localDir, boolean text) {
		float sd = dmg * w.shieldMult * target.sysShieldDamageTakenMult; // Fortress Shield
		boolean wasOverloaded = target.flux.overloaded();
		target.flux.absorb(sd, soft, localDir, tuning);
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

	private void damageText(Vec3 at, Ship3D attacker, Ship3D target, float[] dealt) {
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
				if (sameStructure(s, p.source) || s.destroyed || s.phased || s.volume == null) continue;
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
				if (ms.owner == owner) continue;
				float t = segmentSphere(p.prev, p.pos, ms.pos, ms.spec.missileRadius + 3f);
				if (t >= 0f && t < bestT) {
					bestT = t;
					bestMissile = ms;
				}
			}
			if (bestMissile == null && shielded != null) {
				shieldDamage(p.source, shielded, p.spec.damage * p.damageMult, p.spec, false, sHit, sDir, true);
				push(shielded, sHit, p.vel, shotImpulse(p.spec, p.spec.damage * p.damageMult));
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
				it.remove();
			}
		}
	}

	/**
	 * First entry of segment a->b into the ship's hull volume. Returns the segment parameter (0..1)
	 * or -1; writes the world hit point and the local (unscaled) point just outside the hull.
	 */
	private float segmentHit(Ship3D s, Vec3 a, Vec3 b, Vec3 hitOut, Vec3 localOutsideOut) {
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
		Effect boom = new Effect(Effect.Kind.EXPLOSION, s.pos, s.radius * 2.2f, 1.4f).color(1f, 0.65f, 0.3f);
		boom.vel.set(s.vel);
		effects.add(boom);
		for (int i = 0; i < 12; i++) {
			Effect f = new Effect(Effect.Kind.FLASH, s.pos, s.radius * (0.4f + rnd.nextFloat() * 0.5f), 0.6f + rnd.nextFloat() * 0.8f)
					.color(1f, 0.55f + rnd.nextFloat() * 0.3f, 0.25f);
			f.vel.set(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize().scale(s.radius * 0.6f).add(s.vel);
			effects.add(f);
		}
	}

	public void stepEffects(float dt) {
		flushTallies(dt);
		if (dt <= 0f) return;
		for (Iterator<Effect> it = effects.iterator(); it.hasNext(); ) {
			Effect e = it.next();
			e.life -= dt;
			e.pos.addScaled(e.vel, dt);
			if (e.kind == Effect.Kind.ARC) e.end.addScaled(e.vel, dt);
			if (e.life <= 0f) it.remove();
		}
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
		if (Float.isNaN(px) || t > 30f) return true; // keep the linear answer
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
		if (halfAngle <= 0f) return;
		Vec3 a = Math.abs(dir.y) < 0.9f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 u = new Vec3(a).cross(dir).normalize();
		Vec3 v = new Vec3(dir).cross(u).normalize();
		double r = Math.sqrt(rnd.nextDouble()) * Math.tan(halfAngle);
		double th = rnd.nextDouble() * Math.PI * 2;
		dir.addScaled(u, (float) (r * Math.cos(th))).addScaled(v, (float) (r * Math.sin(th))).normalize();
	}
}
