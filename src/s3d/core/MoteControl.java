package s3d.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import s3d.math.Vec3;

/**
 * Mote Attractor (Ziggurat), after vanilla's MoteControlScript and MoteAIScript. The ship keeps
 * launching motes (a few a second from its launch slots, up to 30) that flock round it and go for
 * enemy fighters and missiles nearby (point defence; extra damage on fighters). Using the system
 * puts an attractor where you aim (or locks onto the ship you're aiming at): an arc shoots to it and
 * every mote swarms it for 10 seconds (20 when locked onto a ship).
 */
public final class MoteControl {
	// ---- vanilla's numbers (the host reads the live ones) ----
	public int maxMotes = 30;
	public float attractorSeconds = 10f, attractorLockSeconds = 20f;
	public float pdFromSource = 2000f, pdFromAttractor = 1000f;
	public float antiFighterDamage = 200f;
	/** Reach of the attractor; the mote (vanilla's motelauncher shot) and the arc's colour. */
	public float range = 1500f;
	public WeaponSpec3D mote;
	public float[] arcColor = {0.39f, 0.39f, 1f};

	// ---- state ----
	public final List<Missile3D> list = new ArrayList<>();
	/** The attractor: a point, or a ship it's locked on; seconds left. */
	public final Vec3 attractor = new Vec3();
	public Ship3D attractorLock;
	public float attractorLeft;
	private float launchTimer;
	private float time;
	private final Vec3 tTotal = new Vec3(), tD = new Vec3(), tUp = new Vec3();
	private static final Random RND = new Random(17);

	public boolean attracting() {
		return attractorLeft > 0f;
	}

	void step(Ship3D s, ShipSystem3D sys, Sim sim, float dt) {
		time += dt;
		Combat c = sim.combat;
		list.removeIf(m -> !m.alive || !c.missiles.contains(m));
		if (attractorLeft > 0f) {
			attractorLeft -= dt;
			if (attractorLeft <= 0f || attractorLock != null && (attractorLock.destroyed || !sim.ships.contains(attractorLock)) || list.isEmpty()) {
				attractorLeft = 0f;
				attractorLock = null;
			}
		}
		if (s.destroyed) {
			for (Missile3D m : list) m.fade = 0f; // the motes go out with their ship
			list.clear();
			return;
		}
		// Vanilla: a new mote every 0.15-0.25 s while under the cap, not while locked on a ship or overloaded / venting.
		launchTimer -= dt;
		if (launchTimer <= 0f) {
			launchTimer = 0.15f + 0.1f * RND.nextFloat();
			if (mote != null && list.size() < maxMotes && attractorLock == null && (s.flux == null || !s.flux.disabled())) launch(s, sys, c);
		}
		int i = 0;
		for (Missile3D m : list) steer(s, m, i++, c);
	}

	private void launch(Ship3D s, ShipSystem3D sys, Combat c) {
		WeaponMount slot = sys.slots.isEmpty() ? null : sys.slots.get(RND.nextInt(sys.slots.size()));
		Vec3 at = slot != null ? slot.worldPos(s, new Vec3()) : new Vec3(s.pos);
		Vec3 dir = slot != null ? s.rot.rotate(slot.restDir, new Vec3()) : s.rot.up(new Vec3());
		Combat.jitterDir(dir, Math.max(0.3f, slot != null ? slot.halfArcRad : 0.5f), RND);
		Missile3D m = c.spawnMissile(s, mote, at, dir, Math.max(50f, mote.launchSpeed), null);
		m.vel.add(s.vel);
		list.add(m);
		c.sounds.play("mote_attractor_launch_mote", at, 0.25f);
	}

	/**
	 * Where each mote goes, as vanilla's MoteAIScript: half a second after launch it picks a target, an
	 * enemy missile or fighter near the ship (or near the attractor while one is up; a missile it
	 * touches is destroyed) or the ship the attractor is locked on once it's close, and heads for it;
	 * otherwise it flocks. Motes head somewhere without turning to it (the velocity swings round).
	 */
	private void steer(Ship3D s, Missile3D m, int index, Combat c) {
		m.target = null;
		if (m.age < 0.5f) return; // the launch spray
		Vec3 head = m.headDir != null ? m.headDir : (m.headDir = new Vec3(m.dir));
		float k = c.tuning.weaponRangeMult; // vanilla distances, at 3D range scale
		if (attractorLock != null && attracting()) {
			if (m.pos.distance(attractorLock.pos) <= attractorLock.radius + 300f * k) {
				headAt(m, attractorLock.pos, attractorLock.vel, head);
				m.target = attractorLock;
				return;
			}
		} else if (pdCheck(s, m, c, attracting() ? pdFromAttractor : pdFromSource, attracting() ? attractor : s.pos, head)) {
			return;
		}
		flock(s, m, index, c, k, head);
	}

	/**
	 * Vanilla's flocking: pulled to the attractor (if any); kept off ships; apart from and in step with
	 * the motes nearby; around their ship, pushed out from close in and drawn back from far out (it
	 * settles some way out), carried along when it moves; circling it if nothing else pulls.
	 */
	private void flock(Ship3D s, Missile3D m, int index, Combat c, float k, Vec3 head) {
		Vec3 total = tTotal.set(0f, 0f, 0f), d = tD;
		float avoid = 50f * k * (1f + 0.25f * (float) Math.sin(time)), cohesion = 100f * k;
		if (attracting()) {
			Vec3 a = attractorLock != null ? attractorLock.pos : attractor;
			float dist = d.set(a).sub(m.pos).length();
			if (dist > 1e-3f) total.addScaled(d, Math.min(1f, dist / (200f * k)) * 3f / dist);
			avoid *= 3f;
		}
		boolean hardAvoiding = false;
		for (Ship3D o : c.world) {
			if (o == s || o.destroyed || o.fighter || o.phased) continue;
			float reach = o.radius + avoid + 50f * k;
			float dist = d.set(m.pos).sub(o.pos).length();
			if (dist >= reach || dist < 1e-3f) continue;
			float f = 1f - dist / reach;
			total.addScaled(d, f * 5f / dist);
			hardAvoiding |= f > 0.5f;
		}
		for (Missile3D o : list) {
			if (o == m) continue;
			float dist = d.set(m.pos).sub(o.pos).length();
			if (dist < avoid && !hardAvoiding && dist > 1e-3f) total.addScaled(d, (1f - dist / avoid) / dist);
			if (dist < cohesion) {
				float v = o.vel.length();
				if (v > 1e-3f) total.addScaled(o.vel, (1f - dist / cohesion) / v);
			}
		}
		float rejoin = s.radius + 200f * k, repel = s.radius + 50f * k, along = s.radius + 600f * k;
		float dist = d.set(s.pos).sub(m.pos).length();
		if (dist > 1e-3f) {
			d.scale(1f / dist); // toward the ship
			if (dist > rejoin) total.addScaled(d, (dist / (rejoin + 400f * k) - 1f) * 0.5f);
			if (dist < repel) total.addScaled(d, -(1f - dist / repel) * 5f);
			float sv = s.vel.length();
			if (dist < along && sv > 20f) total.addScaled(s.vel, (1f - dist / along) / sv);
			if (total.length() <= 0.05f) total.set(d).cross(s.rot.up(tUp)).scale(index % 2 == 0 ? 1f : -1f); // circles its ship
		}
		if (total.lengthSq() > 1e-8f) head.set(total).normalize();
	}

	private static void headAt(Missile3D m, Vec3 pos, Vec3 vel, Vec3 head) {
		Vec3 aim = new Vec3();
		float speed = Math.max(m.spec.missileMaxSpeed, m.vel.length());
		if (!Combat.interceptCurved(m.pos, new Vec3(), pos, vel, new Vec3(), Math.max(1f, speed), aim)) aim.set(pos);
		aim.sub(m.pos);
		if (aim.lengthSq() > 1e-6f) head.set(aim).normalize();
	}

	/** Point defence: head for the nearest enemy missile / fighter within reach of `centre`; a missile touched is destroyed. */
	private boolean pdCheck(Ship3D s, Missile3D m, Combat c, float reach, Vec3 centre, Vec3 head) {
		Missile3D best = null;
		float bestD = Float.MAX_VALUE;
		for (Missile3D o : c.missiles) {
			if (o.owner == s.owner || !o.alive || o.flare || o.flamedOut() || o.spec.orb) continue;
			if (o.pos.distance(centre) > reach) continue;
			float d = o.pos.distance(m.pos);
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		Ship3D fighter = null;
		float fD = Float.MAX_VALUE;
		for (Ship3D o : c.world) {
			if (!o.fighter || o.owner == s.owner || o.destroyed || o.phased) continue;
			if (o.pos.distance(centre) > reach) continue;
			float d = o.pos.distance(m.pos);
			if (d < fD) {
				fD = d;
				fighter = o;
			}
		}
		if (best != null && bestD <= fD) {
			if (closest(m, best) < m.spec.missileRadius + best.spec.missileRadius + 15f) { // rammed the missile
				best.hp = 0f;
				m.alive = false;
				c.effects.add(new Effect(Effect.Kind.FLASH, m.pos, 30f, 0.25f).color(arcColor[0], arcColor[1], arcColor[2]));
				return true;
			}
			headAt(m, best.pos, best.vel, head);
			return true;
		}
		if (fighter != null) {
			headAt(m, fighter.pos, fighter.vel, head);
			m.target = fighter;
			return true;
		}
		return false;
	}

	/** How close two missiles came over the last frame (along their relative motion, so fast ones can't slip past). */
	private float closest(Missile3D a, Missile3D b) {
		Vec3 r0 = tD.set(a.prev).sub(b.prev), seg = tTotal.set(a.pos).sub(b.pos).sub(r0);
		float len2 = seg.lengthSq();
		float u = len2 > 1e-6f ? Math.max(0f, Math.min(1f, -r0.dot(seg) / len2)) : 1f;
		return r0.addScaled(seg, u).length();
	}

	/**
	 * The system used: an attractor where you aim, locked onto the enemy ship under the reticle if
	 * there is one in reach (an AI: on its target); an arc shoots out to it.
	 */
	void used(Ship3D s, ShipSystem3D sys, Sim sim) {
		float reach = range * sim.tuning.weaponRangeMult;
		Ship3D lock = s == sim.player ? (sim.target != null ? sim.target : sim.aimTarget) : s.ai != null ? s.ai.target : null;
		if (lock != null && (lock.owner == s.owner || lock.destroyed || lock.pos.distance(s.pos) > reach + lock.radius)) lock = null;
		attractorLock = lock;
		if (lock != null) {
			attractor.set(lock.pos);
		} else if (s == sim.player && sim.hasAimPoint) {
			Vec3 d = new Vec3(sim.aimPoint).sub(s.pos);
			if (d.length() > reach) d.normalize().scale(reach);
			attractor.set(s.pos).add(d);
		} else {
			attractor.set(s.pos).addScaled(s.rot.forward(new Vec3()), reach * 0.5f);
		}
		attractorLeft = lock != null ? attractorLockSeconds : attractorSeconds;
		Effect arc = new Effect(Effect.Kind.ARC, sys.slots.isEmpty() ? s.pos : sys.slots.get(0).worldPos(s, new Vec3()), Math.max(10f, 8f * s.scale), 0.5f)
				.color(arcColor[0], arcColor[1], arcColor[2]);
		arc.end.set(attractor);
		sim.combat.effects.add(arc);
		sim.combat.sounds.play(lock != null ? "mote_attractor_targeted_ship" : "mote_attractor_targeted_empty_space", attractor, 1f);
	}
}
