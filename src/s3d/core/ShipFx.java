package s3d.core;

import java.util.Random;

import s3d.math.Vec3;

/**
 * Looks only: vent clouds and overload arcs, added to the effect list each frame. Has its own random
 * numbers, so the sim's rolls (and every benchmark result) are the same with or without it. Ship
 * system looks are drawn by the renderer straight from each system's {@link SystemLook}.
 */
public final class ShipFx {
	private final Random rnd = new Random(7);
	private final Vec3 tA = new Vec3(), tB = new Vec3(), tC = new Vec3();

	/** Every frame (sim time). */
	public void step(Sim sim, float dt) {
		if (dt <= 0f) return;
		for (Ship3D s : sim.ships) {
			if (s.destroyed) continue;
			ShieldFlux f = s.flux;
			if (f != null && f.venting) vent(sim, s, f, dt);
			if (f != null && f.overloaded()) overload(sim, s, dt);
		}
	}

	/**
	 * Flux venting as vanilla shows it: clouds billowing off the whole hull in the hull style's vent
	 * colours (white core, violet fringe on most ships), thicker with more flux left to dump.
	 */
	private void vent(Sim sim, Ship3D s, ShieldFlux f, float dt) {
		float rate = Math.min(60f, (14f + s.radius * 0.12f) * (0.35f + 0.65f * f.level()));
		for (int i = count(rate, dt); i > 0; i--) {
			Vec3 dir = randomDir(tA, 1f);
			Vec3 at = tB.set(s.pos).addScaled(dir, s.reachToward(dir) * (0.6f + rnd.nextFloat() * 0.35f));
			float size = Math.max(8f, s.radius * (0.18f + rnd.nextFloat() * 0.16f)), life = 0.6f + rnd.nextFloat() * 0.5f;
			Vec3 drift = tC.set(dir).scale((s.radius * 0.35f + 20f) * (0.4f + rnd.nextFloat() * 0.6f)).add(s.vel);
			Effect fringe = new Effect(Effect.Kind.FLASH, at, size, life).color(s.ventFringe[0], s.ventFringe[1], s.ventFringe[2]);
			fringe.vel.set(drift);
			sim.combat.effects.add(fringe);
			Effect core = new Effect(Effect.Kind.FLASH, at, size * 0.45f, life * 0.6f).color(s.ventCore[0], s.ventCore[1], s.ventCore[2]);
			core.vel.set(drift);
			sim.combat.effects.add(core);
		}
	}

	/** Overload: arcs crackling between nearby points of the hull in the hull style's overload colour. */
	private void overload(Sim sim, Ship3D s, float dt) {
		float[] col = s.overloadColor;
		for (int i = count(Math.min(30f, 14f + s.radius * 0.04f), dt); i > 0; i--) {
			Vec3 d1 = randomDir(tA, 0.8f);
			Vec3 a = tB.set(s.pos).addScaled(d1, s.reachToward(d1) * 0.95f);
			Vec3 d2 = tC.set(d1).add(new Vec3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).scale(0.9f)).normalize();
			Effect arc = new Effect(Effect.Kind.ARC, a, Math.max(3f, 2.2f * s.scale), 0.12f + rnd.nextFloat() * 0.1f).color(col[0], col[1], col[2]);
			arc.end.set(s.pos).addScaled(d2, s.reachToward(d2) * 0.95f);
			arc.vel.set(s.vel);
			sim.combat.effects.add(arc);
		}
	}

	/** How many particles this frame for a rate per second (whole number with the right average). */
	private int count(float ratePerSecond, float dt) {
		float n = ratePerSecond * dt;
		int whole = (int) n;
		return whole + (rnd.nextFloat() < n - whole ? 1 : 0);
	}

	/** A random direction, flattened toward the horizontal by `vertical` (0 = flat, 1 = round). */
	private Vec3 randomDir(Vec3 out, float vertical) {
		out.set(rnd.nextFloat() - 0.5f, (rnd.nextFloat() - 0.5f) * vertical, rnd.nextFloat() - 0.5f);
		if (out.lengthSq() < 1e-6f) out.set(1f, 0f, 0f);
		return out.normalize();
	}
}
