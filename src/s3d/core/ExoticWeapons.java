package s3d.core;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import s3d.math.Vec3;

/**
 * Vanilla weapon scripts, batch 2: Omega, Threat and Dweller weapons, flare launchers, missiles that
 * blow up over an area. Rebuilt in 3D like batch 1 (vanilla's scripts never run; their numbers reach
 * the specs through the host). Combat calls in where a shot is fired, a beam plays, something is hit or
 * a missile lands, and steps this once a frame for whatever outlives the shot: rifts waiting to open,
 * Reality Disruptor orbs, Defabrication swarms eating into a hull, fragment swarms regrowing, slowed
 * repairs.
 */
public final class ExoticWeapons {
	/** Rift colours (vanilla's standard rift, and the Dwellers' lightning). */
	public static final float[] RIFT_COLOR = {0.39f, 0.24f, 1f}, LIGHTNING_COLOR = {0.6f, 0.35f, 1f};

	private final Combat c;

	ExoticWeapons(Combat c) {
		this.c = c;
	}

	// ---- rifts ------------------------------------------------------------------------------------

	/** A rift about to open: a blast (its spec's damage, radius and core) after a short delay, where it was put. */
	private static final class Rift {
		final Vec3 pos = new Vec3();
		WeaponSpec3D blast;
		Ship3D source;
		int owner;
		float timer, dmgMult, size;
	}

	private final List<Rift> rifts = new ArrayList<>();

	/**
	 * Opens a rift of `blast` at `at` after `delay` seconds (0 = now). It hits enemies only, or everyone
	 * but the ship that made it if the blast is friendly fire (blastFF). A short purple ping marks it
	 * while it waits.
	 */
	void openRift(WeaponSpec3D blast, Vec3 at, Ship3D source, int owner, float delay, float dmgMult, float sizeMult) {
		Rift r = new Rift();
		r.pos.set(at);
		r.blast = blast;
		r.source = source;
		r.owner = owner;
		r.timer = delay;
		r.dmgMult = dmgMult;
		r.size = sizeMult;
		if (delay <= 0f) {
			burst(r);
			return;
		}
		rifts.add(r);
		Effect ping = new Effect(Effect.Kind.FLASH, at, Math.max(20f, blast.proxCore * sizeMult), delay).color(RIFT_COLOR[0], RIFT_COLOR[1], RIFT_COLOR[2]);
		c.effects.add(ping);
	}

	private void burst(Rift r) {
		WeaponSpec3D b = r.blast;
		c.areaBlast(b, r.pos, r.source, b.blastFF ? -1 : r.owner, null, r.dmgMult);
		riftVisual(r.pos, Math.max(b.proxRadius, b.proxRange) * 0.7f * r.size, 1.2f);
	}

	/** The dark tear a rift leaves (two of them, a little apart, like vanilla's). */
	void riftVisual(Vec3 at, float size, float life) {
		for (int i = 0; i < 2; i++) {
			Vec3 p = new Vec3(at);
			if (i > 0) p.addScaled(randomUnit(), size * 0.4f);
			float s = size * (0.75f + 0.5f * c.rand()) * (i == 0 ? 1f : 0.7f);
			c.effects.add(new Effect(Effect.Kind.RIFT, p, s, life * (0.8f + 0.4f * c.rand())).color(RIFT_COLOR[0], RIFT_COLOR[1], RIFT_COLOR[2]));
		}
	}

	// ---- beams: Rift Beam, Rift Cascade, Rift Lance, Abyssal Glare ------------------------------------

	/** Per-mount state of a rift-making beam; dropped when the beam goes off (Rift Cascade starts over each burst). */
	private static final class BeamRifts {
		float timer, sinceRift;
		boolean hadTarget, done;
		int toSpawn = -1, spawned;
		final Vec3 last = new Vec3(), axis = new Vec3();
		boolean hasLast;
	}

	private final Map<WeaponMount, BeamRifts> beamRifts = new IdentityHashMap<>();

	/**
	 * A beam at mount m played from origin along dir this frame and ends at m.beamEnd; struck is what it
	 * hit (a ship, its raised shield when onShield, a missile) or null.
	 */
	void beam(Ship3D s, int owner, WeaponMount m, Vec3 origin, Vec3 dir, Object struck, boolean onShield, float dt) {
		WeaponSpec3D w = m.spec;
		if (w.rift == null || w.riftMode == WeaponSpec3D.RiftMode.NONE) return;
		BeamRifts st = beamRifts.get(m);
		if (st == null) {
			st = new BeamRifts();
			st.timer = w.riftMode == WeaponSpec3D.RiftMode.SCATTER ? c.rand() * w.riftInterval : 0f;
			beamRifts.put(m, st);
		}
		float k = c.tuning.weaponRangeMult;
		float range = w.range * k, len = origin.distance(m.beamEnd);
		float mult = energyMult(s) * w.riftDamageMult;
		switch (w.riftMode) {
			case SCATTER: scatter(s, owner, m, origin, dir, len, mult, st, dt); break;
			case CASCADE: cascade(s, owner, m, origin, dir, struck, onShield, range, len, mult, st, dt); break;
			case LANCE:
				if (st.done || m.beamLevel < 1f) break;
				if (len > range - 10f || struck != null) {
					st.done = true;
					if (c.rand() < w.riftChance) openRift(w.rift, m.beamEnd, s, owner, w.riftDelay, mult, 1f);
				}
				break;
			case GLARE: glare(s, owner, m, origin, struck, range, len, st, dt); break;
			default: break;
		}
	}

	private static float energyMult(Ship3D s) {
		return s.sysEnergyDamageMult * s.sysDamageMult;
	}

	/**
	 * Rift Beam: every half second or so at full power a small rift opens by the beam: on an enemy
	 * missile or fighter close to it if there is one, else somewhere along it.
	 */
	private void scatter(Ship3D s, int owner, WeaponMount m, Vec3 origin, Vec3 dir, float len, float mult, BeamRifts st, float dt) {
		WeaponSpec3D w = m.spec;
		if (m.beamLevel < 1f) return;
		st.timer -= dt;
		if (st.timer > 0f) return;
		st.timer = w.riftInterval * (0.8f + 0.4f * c.rand());
		if (len < 10f) return;
		Vec3 end = m.beamEnd, p = new Vec3();
		List<Vec3> near = new ArrayList<>();
		for (Missile3D ms : c.missiles) {
			if (ms.owner == owner || !ms.alive || ms.hp <= 0f || ms.flamedOut()) continue;
			if (distToSegment(ms.pos, origin, end, p) - ms.spec.missileRadius <= w.riftReach) near.add(ms.pos);
		}
		for (Ship3D o : c.world) {
			if (!o.fighter || o.destroyed || o.phased || o.owner == owner) continue;
			if (distToSegment(o.pos, origin, end, p) - o.radius <= w.riftReach) near.add(o.pos);
		}
		Vec3 loc;
		if (!near.isEmpty()) {
			loc = new Vec3(near.get(Math.min(near.size() - 1, (int) (c.rand() * near.size()))));
		} else {
			loc = new Vec3(origin).addScaled(dir, len * (0.25f + 0.75f * c.rand())).addScaled(randomUnit(), w.riftSpread * c.rand());
		}
		Vec3 from = new Vec3();
		float d = distToSegment(loc, origin, end, from);
		Vec3 at = new Vec3(from);
		if (d > 1e-3f) at.addScaled(new Vec3(loc).sub(from).scale(1f / d), Math.min(d, w.riftSpread));
		openRift(w.rift, at, s, owner, w.riftDelay, mult, 1f);
	}

	/**
	 * Rift Cascade: once the burst is at full power and on a target, rifts open one after another
	 * (0.1 s apart) round the target's hull from where the beam hit, walking on round it in the
	 * direction the beam travels; more of them (up to 5) the closer the target. A beam that reaches its
	 * full range opens one where it ends. Later rifts hit harder (x0.75 up to x1.25).
	 */
	private void cascade(Ship3D s, int owner, WeaponMount m, Vec3 origin, Vec3 dir, Object struck, boolean onShield, float range, float len,
						 float mult, BeamRifts st, float dt) {
		WeaponSpec3D w = m.spec;
		if (m.beamLevel < 1f || st.done) return;
		Ship3D target = struck instanceof Ship3D ? (Ship3D) struck : null;
		if (st.toSpawn <= 0 && target != null) {
			st.toSpawn = Math.min(w.riftMax, (int) ((range - len) / Math.max(1f, w.riftReach * c.tuning.weaponRangeMult)) + 1);
			st.timer = 0f;
		}
		st.timer -= dt;
		if (st.timer > 0f) return;
		float size = 0.75f + 0.5f * st.spawned / (float) Math.max(1, st.toSpawn - 1);
		boolean spawned = false;
		if (len > range - 10f) {
			openRift(w.rift, m.beamEnd, s, owner, w.riftDelay, mult * size, size);
			spawned = true;
		} else if (target != null) {
			Vec3 at = nextCascadePoint(st, target, m.beamEnd, dir, onShield, w, size);
			if (st.hasLast && st.last.distance(at) < w.riftSpread * 2f && st.spawned > 0) {
				Effect arc = new Effect(Effect.Kind.ARC, st.last, Math.max(4f, w.beamWidth * 0.3f), 0.3f).color(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2]);
				arc.end.set(at);
				c.effects.add(arc);
			}
			openRift(w.rift, at, s, owner, w.riftDelay, mult * size, size);
			st.last.set(at);
			st.hasLast = true;
			spawned = true;
		}
		st.timer = w.riftInterval;
		if (spawned && ++st.spawned >= st.toSpawn) st.done = true;
	}

	/**
	 * Where the next cascade rift goes: the first at the beam's hit; each next one riftSpread further
	 * round the target (an angle of spread / radius, at most 90 deg) in the plane of the beam and the
	 * hit, just off the hull (most of a rift's core out, so it bites) or off the shield.
	 */
	private Vec3 nextCascadePoint(BeamRifts st, Ship3D target, Vec3 hit, Vec3 beamDir, boolean onShield, WeaponSpec3D w, float size) {
		Ship3D t = target.parent != null ? target.root() : target;
		if (!st.hasLast) {
			Vec3 r0 = new Vec3(hit).sub(t.pos);
			st.axis.set(r0).cross(beamDir);
			if (st.axis.lengthSq() < 1e-4f * Math.max(1f, r0.lengthSq())) st.axis.set(r0).cross(Math.abs(r0.y) < 0.9f * r0.length() ? new Vec3(0f, 1f, 0f) : new Vec3(1f, 0f, 0f));
			if (st.axis.lengthSq() < 1e-8f) st.axis.set(0f, 1f, 0f);
			st.axis.normalize();
			return new Vec3(hit);
		}
		float radius = t != target ? st.last.distance(t.pos) : Math.max(1f, t.radius);
		float per = w.riftSpread * (onShield ? 0.67f : 1f);
		float angle = Math.min((float) (Math.PI * 0.5), per / Math.max(1f, radius));
		Vec3 r = rotateAbout(new Vec3(st.last).sub(t.pos), st.axis, angle);
		float rl = r.length();
		if (rl < 1e-3f) return new Vec3(st.last);
		r.scale(1f / rl);
		float reach;
		if (onShield && target.flux != null) {
			reach = target.flux.radius * target.scale + 30f + 50f * c.rand();
		} else {
			reach = (t != target ? radius : t.reachToward(r)) + w.rift.proxCore * 0.9f * size;
		}
		return new Vec3(t.pos).addScaled(r, reach);
	}

	/**
	 * Abyssal Glare: a few times a second, when the beam is on something (or nearly at full reach),
	 * a rift may open where it lands (always when it first lands, and at least every half second), and
	 * lightning plays along it.
	 */
	private void glare(Ship3D s, int owner, WeaponMount m, Vec3 origin, Object struck, float range, float len, BeamRifts st, float dt) {
		WeaponSpec3D w = m.spec;
		st.sinceRift += dt;
		boolean has = struck != null;
		boolean force = has && !st.hadTarget;
		st.hadTarget = has;
		st.timer -= dt;
		if (st.timer > 0f && !force) return;
		if (st.timer <= 0f) st.timer = w.riftInterval * (0.8f + 0.4f * c.rand());
		if (!has && len < range * 0.9f || m.beamLevel < 1f) return;
		boolean opened = false;
		if (c.rand() < w.riftChance || force || st.sinceRift > w.riftMaxGap) {
			openRift(w.rift, m.beamEnd, s, owner, 0f, w.riftDamageMult, 1f);
			st.sinceRift = 0f;
			opened = true;
		}
		if (len > 100f && (c.rand() > 0.5f || opened)) {
			Effect arc = new Effect(Effect.Kind.ARC, origin, Math.max(8f, w.beamWidth * 0.35f), 0.2f).color(LIGHTNING_COLOR[0], LIGHTNING_COLOR[1], LIGHTNING_COLOR[2]);
			arc.end.set(m.beamEnd);
			c.effects.add(arc);
			c.sounds.play("abyssal_glare_lightning", m.beamEnd, 0.8f);
		}
	}

	// ---- Rift Lightning --------------------------------------------------------------------------

	/**
	 * Rift Lightning: the shot itself does nothing; lightning leaps from the ship's shroud to the first
	 * enemy missile, shield or hull along the barrel within range (or, missing, somewhere 50-100% of the
	 * range out) and a rift opens there a moment later.
	 */
	void lightning(Ship3D s, WeaponMount m, Vec3 muzzle, Vec3 dir, float dmgMult) {
		WeaponSpec3D w = m.spec;
		float range = w.range * c.tuning.weaponRangeMult;
		Vec3 hit = new Vec3(), local = new Vec3(), sHit = new Vec3(), sDir = new Vec3();
		Ship3D ship = c.rayHit(c.world, muzzle, dir, range, s, hit, local);
		float dist = ship != null ? hit.distance(muzzle) : range;
		Vec3 end = new Vec3(muzzle).addScaled(dir, dist);
		Ship3D shielded = c.shieldOnSegment(c.world, muzzle, end, s, 2f, sHit, sDir, new float[1]);
		Vec3 point;
		if (shielded != null) {
			dist = sHit.distance(muzzle);
			point = sHit;
		} else {
			point = ship != null ? hit : null;
		}
		Missile3D ms = c.missileOnRay(muzzle, dir, dist, s.owner);
		if (ms != null) point = new Vec3(ms.pos);
		if (point == null) point = new Vec3(muzzle).addScaled(dir, range * (0.5f + 0.5f * c.rand()));
		Vec3 from = shipPoint(s, new Vec3(point).sub(s.pos));
		Effect arc = new Effect(Effect.Kind.ARC, from, Math.max(8f, 6f * s.scale), 0.25f).color(LIGHTNING_COLOR[0], LIGHTNING_COLOR[1], LIGHTNING_COLOR[2]);
		arc.end.set(point);
		arc.vel.set(s.vel);
		c.effects.add(arc);
		float delay = from.distance(point) / Math.max(1f, w.riftSpeed) * 0.8f;
		openRift(w.rift, point, s, s.owner, delay <= 0.016f ? 0f : delay, dmgMult * w.riftDamageMult, 1f);
	}

	/** A point in the ship's swarm or shroud: round its hull on the side facing `toward`. */
	Vec3 shipPoint(Ship3D s, Vec3 toward) {
		Vec3 d = randomUnit();
		if (toward.lengthSq() > 1e-6f && d.dot(toward) < 0f) d.scale(-1f);
		return new Vec3(s.pos).addScaled(d, s.radius * (0.5f + 0.25f * c.rand()));
	}

	// ---- Reality Disruptor orbs ----------------------------------------------------------------------

	/**
	 * Reality Disruptor shots in flight: slow orbs that pass through everything (nothing can shoot them
	 * down) and, after a moment, arc several times a second to the nearest enemy ship or missile within
	 * reach. Drawn by the renderer.
	 */
	public final List<Missile3D> orbs = new ArrayList<>();

	void launchOrb(Ship3D s, WeaponMount m, Vec3 from, Vec3 dir, float dmgMult) {
		WeaponSpec3D w = m.spec;
		Missile3D o = new Missile3D();
		o.spec = w;
		o.source = s;
		o.owner = s.owner;
		o.pos.set(from);
		o.prev.set(from);
		o.spawnPos.set(from);
		o.dir.set(dir);
		o.vel.set(dir).scale(w.launchSpeed).add(s.vel);
		o.hp = Math.max(1f, w.missileHp);
		o.damageMult = dmgMult;
		o.lureCheck = w.orbArcDelay; // time to the first arc
		orbs.add(o);
	}

	private void stepOrbs(float dt) {
		float k = c.tuning.weaponRangeMult;
		for (Iterator<Missile3D> it = orbs.iterator(); it.hasNext(); ) {
			Missile3D o = it.next();
			WeaponSpec3D w = o.spec;
			o.prev.set(o.pos);
			o.age += dt;
			Vec3 want = new Vec3(o.dir).scale(Math.max(1f, w.missileMaxSpeed)).sub(o.vel);
			float need = want.length(), can = Math.max(1f, w.missileAccel) * dt;
			if (need > can) want.scale(can / need);
			o.vel.add(want);
			o.pos.addScaled(o.vel, dt);
			o.trailTimer += dt;
			if (o.trailTimer >= 0.03f) {
				o.trailTimer = 0f;
				o.pushTrail();
			}
			if (o.flamedOut()) {
				o.fade += dt;
				if (o.fade > 0.5f) it.remove();
				continue;
			}
			if (o.spawnPos.distance(o.pos) > w.range * k || o.age > Math.max(1f, w.flightTime) * k) {
				o.fade = 0f; // fizzles out at its range
				continue;
			}
			c.sounds.loop("realitydisruptor_loop", o, o.pos, 1f, 1f);
			o.lureCheck -= dt;
			if (o.lureCheck <= 0f) {
				o.lureCheck = w.orbArcInterval * (0.85f + 0.3f * c.rand());
				orbArc(o);
			}
		}
	}

	/** One arc from an orb: the nearest enemy ship (shields stop it) or missile in reach, else a crackle into nothing. */
	private void orbArc(Missile3D o) {
		WeaponSpec3D w = o.spec;
		float reach = w.orbArcRange * c.tuning.weaponRangeMult;
		Object best = null;
		float bestD = Float.MAX_VALUE;
		for (Ship3D t : c.world) {
			if (t.destroyed || t.phased || t.invulnerable || t.owner == o.owner) continue;
			float d = t.surfaceDistance(o.pos) - 50f;
			if (d <= reach && d < bestD) {
				bestD = d;
				best = t;
			}
		}
		for (Missile3D ms : c.missiles) {
			if (ms.owner == o.owner || !ms.alive || ms.hp <= 0f) continue;
			float d = ms.pos.distance(o.pos) - ms.spec.missileRadius - 50f;
			if (d <= reach && d < bestD) {
				bestD = d;
				best = ms;
			}
		}
		Effect arc = new Effect(Effect.Kind.ARC, o.pos, 7f, 0.25f).color(RIFT_COLOR[0], RIFT_COLOR[1], RIFT_COLOR[2]);
		c.effects.add(arc);
		float dmg = w.damage * o.damageMult;
		if (best instanceof Missile3D) {
			Missile3D ms = (Missile3D) best;
			ms.hp -= dmg;
			arc.end.set(ms.pos);
		} else if (best instanceof Ship3D) {
			hitWithArc(o.source, (Ship3D) best, o.pos, dmg, w, arc.end);
		} else {
			arc.end.set(o.pos).addScaled(randomUnit(), 200f * (0.75f + 0.5f * c.rand()));
			arc.size *= 0.7f;
		}
		c.sounds.play("realitydisruptor_emp_impact", arc.end, 0.8f);
	}

	/**
	 * An arc from `from` to ship t carrying damage and the weapon's EMP: a raised shield in the way
	 * takes it, otherwise the hull (and its repairs slow if the weapon does that). Writes where it struck.
	 */
	void hitWithArc(Ship3D attacker, Ship3D t, Vec3 from, float dmg, WeaponSpec3D w, Vec3 endOut) {
		Vec3 hit = new Vec3(), local = new Vec3(), sHit = new Vec3(), sDir = new Vec3();
		if (c.shieldOnSegment(List.of(t), from, t.pos, attacker, 2f, sHit, sDir, new float[1]) != null) {
			c.shieldDamage(attacker, t, dmg, w, false, sHit, sDir, true);
			endOut.set(sHit);
		} else if (c.segmentHit(t, from, t.pos, hit, local) >= 0f) {
			float[] dealt = c.dealDamage(attacker, t, dmg, dmg * w.armorMult, w, local);
			c.damageText(hit, attacker, t, dealt);
			if (w.slowRepairTime > 0f) slowRepairs(t, w);
			endOut.set(hit);
		} else {
			endOut.set(t.pos);
		}
	}

	// ---- slowed repairs (Reality Disruptor, Inimical Emanation) -------------------------------------

	private final Map<Ship3D, Float> slowed = new IdentityHashMap<>();

	/** The ship's disabled weapons and engines come back at slowRepairMult speed for slowRepairTime seconds (refreshed, not stacked). */
	void slowRepairs(Ship3D t, WeaponSpec3D w) {
		Float left = slowed.get(t);
		slowed.put(t, Math.max(left != null ? left : 0f, w.slowRepairTime));
		t.repairRateMult = Math.min(t.repairRateMult, w.slowRepairMult);
	}

	/** Seconds of slowed repairs the ship has left (0 = none): for the HUD. */
	public float slowedRepairsLeft(Ship3D t) {
		Float left = slowed.get(t);
		return left != null ? left : 0f;
	}

	// ---- firing: fragments, Hungering Rift's cap ----------------------------------------------------

	/** False while the weapon can't fire: its ship's swarm is short of fragments, or too many Hungering Rifts are out. */
	boolean mayFire(Ship3D s, WeaponSpec3D w) {
		if (w.fragmentsNeeded > 0 && (s.fragments == null || s.fragments.active() < w.fragmentsNeeded)) return false;
		if (w.riftCap > 0) {
			int out = 0;
			for (Missile3D ms : c.missiles) if (ms.spec.riftHeal > 0f && ms.alive) out++;
			if (out >= w.riftCap) return false;
		}
		return true;
	}

	/** A shot left: fragment weapons spend their fragments (a fighter's swarm is its body: it pays hull for them). */
	void fired(Ship3D s, WeaponSpec3D w) {
		if (w.fragmentsSpent <= 0 || s.fragments == null) return;
		s.fragments.spend(w.fragmentsSpent);
		if (s.fighter && s.fragments.size > 0) {
			s.hull -= s.hullMax / (s.fragments.size * 0.8f) * w.fragmentsSpent;
			if (s.hull <= 0f && !s.destroyed) {
				s.hull = 0f;
				c.kill(s);
			}
		}
	}

	// ---- hits -----------------------------------------------------------------------------------------

	/** Projectile and missile hits (after the hit's own damage). Voltaic Cannon: a fistful of EMP arcs. */
	void onHit(Ship3D attacker, Ship3D target, WeaponSpec3D w, Vec3 at, Vec3 local, boolean shieldHit) {
		if (w.empArcsMax <= 0 || w.emp <= 0f || target.destroyed) return;
		int n = w.empArcsMin + (int) (c.rand() * (w.empArcsMax - w.empArcsMin + 1));
		float pierce = shieldHit ? Combat.pierceChance(target) : 1f;
		Vec3 hullLocal = new Vec3();
		if (!shieldHit) hullLocal.set(local);
		else if (c.segmentHit(target, at, target.pos, new Vec3(), hullLocal) < 0f) return;
		for (int i = 0; i < n; i++) {
			if (shieldHit && c.rand() >= pierce) continue;
			c.empHit(target, hullLocal, w.emp, at, Float.MAX_VALUE / 4f);
		}
	}

	/**
	 * A missile struck `target` at `at` (its raised shield if shieldHit, with shieldDir; else the hull at
	 * local), after its own damage: an area blast for missiles that have one, Kinetic Fragments' escort
	 * fragments striking too, a Defabrication Swarm settling in to eat the hull, Hungering Rift healing a
	 * Dweller, rift tears.
	 */
	void missileImpact(Missile3D ms, Ship3D target, Vec3 at, Vec3 local, Vec3 shieldDir, boolean shieldHit) {
		WeaponSpec3D w = ms.spec;
		if (w.impactBlast != null) {
			WeaponSpec3D b = w.impactBlast;
			c.areaBlast(b, at, ms.source, b.blastFF ? -1 : ms.owner, target, ms.damageMult);
		}
		if (w.impactRift > 0f) riftVisual(at, w.impactRift, 1.6f);
		if (w.fragmentStrike != null && w.fragmentStrikes > 0) {
			float left = Math.max(0f, Math.min(1f, ms.hp / Math.max(1f, w.missileHp)));
			int n = Math.round(w.fragmentStrikes * left);
			WeaponSpec3D f = w.fragmentStrike;
			float dmg = f.damage * ms.damageMult;
			for (int i = 0; i < n && !target.destroyed; i++) {
				if (shieldHit) {
					c.shieldDamage(ms.source, target, dmg, f, false, at, shieldDir, true);
				} else {
					float[] dealt = c.dealDamage(ms.source, target, dmg, dmg * f.armorMult, f, local);
					c.damageText(at, ms.source, target, dealt);
				}
			}
		}
		if (!shieldHit && w.dotTicks > 0 && !target.destroyed && !target.invulnerable) {
			Dot d = new Dot();
			d.target = target;
			d.source = ms.source;
			d.w = w;
			d.local.set(local);
			d.ticksLeft = w.dotTicks;
			d.perTick = w.damage * ms.damageMult / w.dotTicks;
			d.timer = 0.3f;
			dots.add(d);
			c.sounds.play("devouring_swarm_hit_ship", at, 1f);
		}
		if (!shieldHit && w.riftHeal > 0f && !target.destroyed) heal(ms);
	}

	/** Hungering Rift: a damaged friendly Dweller (one missing at least 70% of a heal first, else any) gets hull back. */
	private void heal(Missile3D ms) {
		WeightedPick big = new WeightedPick(), small = new WeightedPick();
		float amount = ms.spec.riftHeal;
		for (Ship3D o : c.world) {
			if (o.destroyed || o.fighter || o.owner != ms.owner || o.riftHealMult <= 0f) continue;
			float missing = o.hullMax - o.hull;
			if (missing <= 0f) continue;
			if (missing < amount * 0.7f) small.add(o, missing);
			else big.add(o, missing);
		}
		Ship3D pick = big.pick(c.rand());
		if (pick == null) pick = small.pick(c.rand());
		if (pick == null) return;
		pick.hull = Math.min(pick.hullMax, pick.hull + amount * pick.riftHealMult);
		Effect glow = new Effect(Effect.Kind.FLASH, pick.pos, pick.radius * 0.8f, 0.8f).color(RIFT_COLOR[0], RIFT_COLOR[1], RIFT_COLOR[2]);
		glow.vel.set(pick.vel);
		c.effects.add(glow);
	}

	private static final class WeightedPick {
		final List<Ship3D> items = new ArrayList<>();
		final List<Float> weights = new ArrayList<>();
		float total;

		void add(Ship3D s, float w) {
			items.add(s);
			weights.add(w);
			total += w;
		}

		Ship3D pick(float r) {
			if (items.isEmpty() || total <= 0f) return null;
			float x = r * total;
			for (int i = 0; i < items.size(); i++) {
				x -= weights.get(i);
				if (x <= 0f) return items.get(i);
			}
			return items.get(items.size() - 1);
		}
	}

	// ---- Defabrication Swarm: damage eaten in over ~10 s --------------------------------------------

	private static final class Dot {
		Ship3D target, source;
		WeaponSpec3D w;
		final Vec3 local = new Vec3();
		int ticksLeft;
		float perTick, timer;
	}

	private final List<Dot> dots = new ArrayList<>();

	private void stepDots(float dt) {
		for (Iterator<Dot> it = dots.iterator(); it.hasNext(); ) {
			Dot d = it.next();
			Ship3D tg = d.target;
			if (tg.destroyed || d.ticksLeft <= 0) {
				it.remove();
				continue;
			}
			Vec3 at = tg.rot.rotate(d.local.x * tg.scale, d.local.y * tg.scale, d.local.z * tg.scale, new Vec3()).add(tg.pos);
			c.sounds.loop("devouring_swarm_loop", d, at, 0.8f, 1f);
			d.timer -= dt;
			if (d.timer > 0f) continue;
			d.timer = 0.8f + c.rand() * 0.2f;
			d.ticksLeft--;
			float[] dealt = c.dealDamage(d.source, tg, d.perTick, d.perTick * d.w.armorMult, d.w, d.local);
			c.damageText(at, d.source, tg, dealt);
			if (dealt[0] + dealt[1] > 0.5f && d.source != null && !d.source.destroyed && !d.source.fighter && d.source.fragments != null
					&& c.rand() < d.w.dotRefundChance) {
				d.source.fragments.add(1f);
			}
			for (int i = 0; i < 3; i++) {
				Effect e = new Effect(Effect.Kind.SPARK, at, (10f + c.rand() * 10f) * Math.max(1f, tg.scale), 1f + c.rand() * 0.6f).color(0.5f, 0.65f, 0.2f);
				e.vel.set(randomUnit()).scale(25f).add(tg.vel);
				c.effects.add(e);
			}
		}
	}

	// ---- flare launchers ---------------------------------------------------------------------------

	/**
	 * Autofire for flare launchers: fire while an enemy guided missile is coming for this ship (or is
	 * near it), launching along the mount's rest direction. Writes the aim point.
	 */
	public boolean flareWanted(Ship3D self, WeaponMount m, Vec3 aimOut) {
		float reach = Math.max(600f, c.tuning.sysFlareLure * 2f);
		boolean threat = false;
		for (Missile3D ms : c.missiles) {
			if (ms.owner == self.owner || ms.flare || !ms.alive || ms.flamedOut() || !ms.spec.guided || ms.spec.ignoresFlares) continue;
			float d = ms.pos.distance(self.pos) - self.radius;
			if (d < reach && (ms.target == self || d < reach * 0.5f)) {
				threat = true;
				break;
			}
		}
		if (!threat) return false;
		Vec3 mw = m.worldPos(self, new Vec3());
		aimOut.set(mw).addScaled(self.rot.rotate(m.restDir, new Vec3()), 300f);
		return true;
	}

	// ---- per frame -------------------------------------------------------------------------------

	void step(float dt) {
		for (Iterator<Rift> it = rifts.iterator(); it.hasNext(); ) {
			Rift r = it.next();
			r.timer -= dt;
			if (r.timer > 0f) continue;
			it.remove();
			burst(r);
		}
		beamRifts.keySet().removeIf(m -> m.beamLevel <= 0f);
		stepOrbs(dt);
		stepDots(dt);
		for (Ship3D s : c.world) if (s.fragments != null && !s.destroyed) s.fragments.step(dt);
		for (Iterator<Map.Entry<Ship3D, Float>> it = slowed.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Ship3D, Float> e = it.next();
			float left = e.getValue() - dt;
			if (left <= 0f || e.getKey().destroyed) {
				e.getKey().repairRateMult = 1f;
				it.remove();
			} else {
				e.setValue(left);
			}
		}
	}

	void clear() {
		rifts.clear();
		beamRifts.clear();
		orbs.clear();
		dots.clear();
		for (Ship3D s : slowed.keySet()) s.repairRateMult = 1f;
		slowed.clear();
	}

	// ---- geometry -------------------------------------------------------------------------------

	private Vec3 randomUnit() {
		for (int i = 0; i < 8; i++) {
			Vec3 v = new Vec3(c.rand() * 2f - 1f, c.rand() * 2f - 1f, c.rand() * 2f - 1f);
			float l2 = v.lengthSq();
			if (l2 > 1e-4f && l2 <= 1f) return v.scale(1f / (float) Math.sqrt(l2));
		}
		return new Vec3(0f, 1f, 0f);
	}

	/** Distance from p to segment a-b; writes the closest point on it. */
	static float distToSegment(Vec3 p, Vec3 a, Vec3 b, Vec3 closestOut) {
		float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
		float len2 = dx * dx + dy * dy + dz * dz;
		float t = len2 > 1e-9f ? ((p.x - a.x) * dx + (p.y - a.y) * dy + (p.z - a.z) * dz) / len2 : 0f;
		t = Math.max(0f, Math.min(1f, t));
		closestOut.set(a.x + dx * t, a.y + dy * t, a.z + dz * t);
		return closestOut.distance(p);
	}

	/** v rotated by angle (radians) about unit axis (Rodrigues). */
	static Vec3 rotateAbout(Vec3 v, Vec3 axis, float angle) {
		float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
		Vec3 cross = new Vec3(axis).cross(v);
		float dot = axis.dot(v);
		return new Vec3(v).scale(cos).addScaled(cross, sin).addScaled(axis, dot * (1f - cos));
	}
}
