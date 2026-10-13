package s3d.core;

import s3d.math.Vec3;

/**
 * What the units systems and weapons put into a battle do, after vanilla's own AI for them:
 * - Construction swarm (Fabricator): drifts out at a third of its speed for a few seconds, then holds
 *   still and builds; when the build time is up the host spawns the ship there and the swarm is gone.
 * - Ejecta (Maelstrom's Tenebrous Expulsion): slows to a crawl, loses 6.67% of its hull a second and
 *   blows up when it's gone (2000 energy on enemies).
 * - Vortex (Churning Locus): spins, heads for the enemy ship it can reach before it decays (5% a
 *   second), leading it; every ram knocks its speed down and makes it decay faster; blows up when gone.
 * Attack swarms fly as fighters (ShipAI3D), with no carrier to go home to.
 */
public final class SpawnBehaviours {
	public static final String CONSTRUCTION_SWARM_WING = "construction_swarm_wing";
	public static final float CONSTRUCTION_SPEED_MULT = 0.33f;
	public static final float EJECTA_DECAY = 0.0667f, VORTEX_DECAY = 0.05f;
	public static final float VORTEX_RANGE = 5000f;

	private SpawnBehaviours() {}

	/** One frame for a spawned unit; false if it's an ordinary fighter (attack swarm) the caller flies. */
	static boolean step(ShipAI3D ai, Sim sim, Ship3D s, float dt) {
		if (s.behaviour == null || s.behaviour == Ship3D.Behaviour.SWARM) return false;
		Tuning t = sim.tuning;
		if (s.decayPerSecond > 0f) {
			s.hull -= s.hullMax * s.decayPerSecond * (1f + s.rams) * dt;
			if (s.hull <= 0f && !s.destroyed) {
				s.hull = 0f;
				sim.combat.kill(s);
				return true;
			}
		}
		float top = s.params != null ? s.params.fwdSpeed : 100f;
		switch (s.behaviour) {
			case CONSTRUCTION: {
				if (s.travelLeft > 0f) {
					s.travelLeft -= dt;
					Vec3 dir = s.vel.lengthSq() > 1e-4f ? new Vec3(s.vel).normalize() : s.rot.forward(new Vec3());
					s.vel.set(dir).scale(top * CONSTRUCTION_SPEED_MULT);
				} else {
					s.vel.scale((float) Math.exp(-2f * dt)); // settles where it is
					if (s.buildVariant != null) {
						s.buildLeft -= dt;
						if (s.buildLeft <= 0f) s.buildDone = true;
					}
				}
				s.pos.addScaled(s.vel, dt);
				s.rot.integrateBody(0f, 0.25f, 0f, dt); // the swarm churns slowly
				return true;
			}
			case EJECTA: {
				// Vanilla decelerates it whenever it's faster than a tenth of its top speed.
				float speed = s.vel.length(), floor = top * 0.1f;
				if (speed > floor) s.vel.scale(Math.max(floor, speed - (s.params != null ? s.params.fwdDecel : 50f) * dt) / speed);
				s.pos.addScaled(s.vel, dt);
				s.rot.integrateBody(0.1f, 0.3f, 0.05f, dt);
				return true;
			}
			case VORTEX: {
				Ship3D target = ai.target;
				if (target == null || target.destroyed || !sim.ships.contains(target) || ai.retargetDue(dt)) {
					target = vortexTarget(sim, s, top);
					ai.target = target;
				}
				Vec3 want = new Vec3();
				if (target != null) {
					float lead = (s.vel.length() + top) * 0.5f;
					if (!Combat.interceptCurved(s.pos, new Vec3(), target.pos, target.vel, target.accel, Math.max(1f, lead), want)) want.set(target.pos);
					want.sub(s.pos);
				} else {
					want.set(s.vel);
				}
				if (want.lengthSq() > 1e-4f) {
					// Heads that way without turning to it (vanilla), at its acceleration.
					Vec3 d = want.normalize().scale(top).sub(s.vel);
					float need = d.length(), can = (s.params != null ? s.params.fwdAccel : 100f) * dt;
					if (need > can) d.scale(can / need);
					s.vel.add(d);
				}
				for (Ship3D o : sim.ships) {
					if (o.owner == s.owner || o.destroyed || o.fighter || o.phased) continue;
					float gap = o.pos.distance(s.pos) - s.radius - o.reachToward(new Vec3(s.pos).sub(o.pos).normalize());
					if (gap < 0f) { // rammed it: knocked back to a tenth of its speed, decaying faster from now on
						s.vel.scale(0.1f);
						s.rams++;
						s.pos.addScaled(new Vec3(s.pos).sub(o.pos).normalize(), -gap + 1f);
					}
				}
				s.pos.addScaled(s.vel, dt);
				s.rot.integrateBody(0f, 2.5f * (s.id.hashCode() % 2 == 0 ? 1f : -1f), 0f, dt); // spins
				ShipSystem3D lunge = s.system;
				if (lunge != null && lunge.kind == ShipSystem3D.Kind.CONVULSIVE_LUNGE && target != null && lunge.ready(s)
						&& target.pos.distance(s.pos) < lunge.ex.lungeDistance * 1.2f) lunge.activate(s);
				return true;
			}
			default:
				return false;
		}
	}

	/** Vanilla's pick: the nearest enemy ship it can reach before decaying away, else the nearest at all. */
	private static Ship3D vortexTarget(Sim sim, Ship3D s, float top) {
		float reachable = s.hullMax > 0f ? s.hull / s.hullMax / Math.max(0.001f, s.decayPerSecond) * top * 0.75f : VORTEX_RANGE;
		Ship3D best = null, anyBest = null;
		float bestD = Float.MAX_VALUE, anyD = Float.MAX_VALUE;
		for (Ship3D o : sim.ships) {
			if (o.owner == s.owner || o.destroyed || o.fighter || o.phased) continue;
			float d = o.pos.distance(s.pos);
			if (d > VORTEX_RANGE) continue;
			if (d < anyD) {
				anyD = d;
				anyBest = o;
			}
			if (d <= reachable && d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best != null ? best : anyBest;
	}
}
