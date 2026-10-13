package s3d.core;

import java.util.Random;

import s3d.math.Vec3;

/**
 * Orion Device (Retribution) and Nova Burst (Nova), rebuilt from how vanilla's script behaves: a
 * shaped antimatter charge drops out of the rear system slot, goes off a moment later behind the
 * ship and throws a cone of glowing cloud away from it, and the blast on the push plate shoves the
 * ship forward hard for a moment with its top speed raised; then it brakes back to its normal top
 * speed over a few seconds. One press fires the whole train: every charge left, one per cooldown
 * (vanilla: 4 charges a second apart), and while more detonations are coming the ship doesn't brake
 * between them, so the jolts stack into one long sprint.
 */
public final class OrionDrive {
	// ---- vanilla's numbers (OrionDeviceParams; Nova Burst's own set) ----
	/** Seconds from dropping the charge to its detonation, its drift speed off the slot, the share of the ship's velocity it keeps. */
	float bombLive = 0.25f, bombSpeed = 50f, bombInherit = 0.5f;
	/** The shaped blast: colour {r, g, b, a}, particles, size range and growth, speed range, life range, cone, scatter, offset. */
	float[] cloud = {1f, 0.49f, 0.1f, 0.61f};
	int particles = 200;
	float sizeMin = 80f, sizeMax = 100f, growMin = 1f, growMax = 2f, speedMin = 100f, speedMax = 350f, lifeMin = 1f, lifeMax = 2f;
	float arcDeg = 90f, scatter = 100f, offset = 50f;
	/** The push: acceleration at its start (it tapers to nothing), how long it lasts, top-speed bonus while it does. */
	float impactAccel = 5000f, impactSeconds = 0.25f, speedBoost = 500f;
	/** Nova Burst: a big blue-white flash at the detonation. */
	boolean novaFlash;
	String explosionSound = "system_orion_device_explosion";

	// ---- state ----
	/** Detonations still to come in this train (after the current one). */
	public int trainLeft;
	/** Seconds until the dropped charge goes off (-1 = none), into the current push (-1 = none), into braking (-1 = none). */
	float fuse = -1f, pushT = -1f, brakeT = -1f;
	/** Seconds since the last detonation (the ship's jitter fades over this). */
	public float sinceDetonation = 99f;

	private static final Random RND = new Random();
	/**
	 * Vanilla's nebula particles are smoky sprites; ours are additive glows, which pile up into a solid
	 * blob at vanilla's alpha. This keeps the cone readable.
	 */
	private static final float CLOUD_GAIN = 0.4f;
	/** Fewer, slightly bigger cloud particles than vanilla's 200: same look, a fraction of the draw cost. */
	private static final int MAX_PARTICLES = 90;

	static OrionDrive orion() {
		return new OrionDrive();
	}

	static OrionDrive nova() {
		OrionDrive o = new OrionDrive();
		o.bombLive = 0f;
		o.bombSpeed = 0f;
		o.cloud = new float[] {0.39f, 0.65f, 1f, 0.33f};
		o.scatter = 0f;
		o.offset = 20f;
		o.growMin = 1.5f;
		o.growMax = 2f;
		o.sizeMin = 50f;
		o.sizeMax = 80f;
		o.speedMax = 500f;
		o.arcDeg = 60f;
		o.impactAccel = 10000f;
		o.impactSeconds = 1f;
		o.novaFlash = true;
		o.explosionSound = "system_nova_burst_explosion";
		return o;
	}

	public boolean busy() {
		return trainLeft > 0 || fuse >= 0f || pushT >= 0f;
	}

	/** Drops a charge from each system slot (or the stern if the hull has none); the blast follows by itself. */
	void fire(Ship3D s, ShipSystem3D sys, Sim sim) {
		Vec3 back = s.rot.forward(new Vec3()).scale(-1f);
		java.util.List<Vec3> from = new java.util.ArrayList<>();
		for (WeaponMount m : sys.slots) from.add(m.worldPos(s, new Vec3()));
		if (from.isEmpty()) from.add(new Vec3(s.pos).addScaled(back, s.reachToward(back)));
		float k = s.scale, vk = sim.tuning.speedMult;
		for (Vec3 at : from) {
			Vec3 vel = new Vec3(back).scale(bombSpeed * vk).addScaled(s.vel, bombInherit);
			Vec3 det = new Vec3(at).addScaled(vel, bombLive);
			if (bombLive > 0f) { // the charge itself, drifting out of the slot
				Effect bomb = new Effect(Effect.Kind.FLASH, at, Math.max(6f, 9f * k), bombLive).color(1f, 0.6f, 0.3f);
				bomb.vel.set(vel);
				sim.combat.effects.add(bomb);
			}
			blast(s, sim, det, back, k, vk);
		}
		fuse = bombLive;
	}

	/** The detonation's looks, timed to go off when the charge does. */
	private void blast(Ship3D s, Sim sim, Vec3 det, Vec3 axis, float k, float vk) {
		Combat c = sim.combat;
		int n = Math.min(particles, MAX_PARTICLES);
		float sizeK = (float) Math.sqrt(particles / (float) n);
		Vec3 u = new Vec3(Math.abs(axis.y) < 0.9f ? 0f : 1f, Math.abs(axis.y) < 0.9f ? 1f : 0f, 0f).cross(axis).normalize();
		Vec3 v = new Vec3(axis).cross(u);
		Vec3 spawn = new Vec3(det).addScaled(axis, offset * k);
		for (int i = 0; i < n; i++) {
			// Vanilla's distribution: most particles near the cone's axis, the fast ones there too.
			float off = RND.nextFloat();
			if (off > 0.2f) off *= off;
			float speedK = 0.5f + 0.5f * (1f - off);
			double theta = Math.toRadians(off * arcDeg * 0.5f), phi = RND.nextDouble() * Math.PI * 2;
			Vec3 dir = new Vec3(axis).scale((float) Math.cos(theta))
					.addScaled(u, (float) (Math.sin(theta) * Math.cos(phi))).addScaled(v, (float) (Math.sin(theta) * Math.sin(phi)));
			Vec3 at = new Vec3(spawn).addScaled(dir, RND.nextFloat() * RND.nextFloat() * scatter * k);
			float life = lifeMin + (lifeMax - lifeMin) * RND.nextFloat();
			Effect p = new Effect(Effect.Kind.NEBULA, at, (sizeMin + (sizeMax - sizeMin) * RND.nextFloat()) * k * sizeK * 0.5f, life)
					.color(cloud[0] * cloud[3] * CLOUD_GAIN, cloud[1] * cloud[3] * CLOUD_GAIN, cloud[2] * cloud[3] * CLOUD_GAIN).delayed(bombLive);
			p.grow = growMin + (growMax - growMin) * RND.nextFloat();
			p.vel.set(dir).scale((speedMin + (speedMax - speedMin) * RND.nextFloat()) * speedK * vk * k);
			if (i == 0) p.withSound(explosionSound, 1f);
			c.effects.add(p);
		}
		if (novaFlash) { // vanilla's three hit particles: two blue, a white core
			c.effects.add(new Effect(Effect.Kind.FLASH, det, 800f * k * 0.9f, 1f).color(0.39f, 0.65f, 1f).delayed(bombLive));
			c.effects.add(new Effect(Effect.Kind.FLASH, det, 800f * 0.67f * k * 0.9f, 0.75f).color(0.39f, 0.65f, 1f).delayed(bombLive));
			c.effects.add(new Effect(Effect.Kind.FLASH, det, 800f * 0.33f * k * 0.9f, 0.75f).color(1f, 1f, 1f).delayed(bombLive));
		} else {
			c.effects.add(new Effect(Effect.Kind.FLASH, det, 60f * k, 0.35f).color(1f, 0.75f, 0.45f).delayed(bombLive));
		}
	}

	/** Every frame, whatever state the system is in: the fuse, the push and the braking after it. */
	void step(Ship3D s, Sim sim, float dt) {
		if (dt <= 0f) return;
		sinceDetonation += dt;
		if (fuse >= 0f) {
			fuse -= dt;
			if (fuse < 0f) { // detonation: the push starts, any braking stops
				fuse = -1f;
				pushT = 0f;
				brakeT = -1f;
				sinceDetonation = 0f;
				if (s == sim.player) sim.combat.shake += 0.8f;
			}
		}
		float normal = s.params != null ? s.params.fwdSpeed * s.sysSpeedMult : 0f;
		float vk = sim.tuning.speedMult;
		if (pushT >= 0f) {
			float mag = Math.max(0f, 1f - pushT / impactSeconds);
			s.vel.addScaled(s.rot.forward(new Vec3()), impactAccel * mag * dt * vk);
			s.sysSpeedFlat += speedBoost;
			clamp(s, normal, normal + speedBoost * vk, dt);
			pushT += dt;
			if (pushT >= impactSeconds) {
				pushT = -1f;
				brakeT = 0f;
			}
		} else if (brakeT >= 0f) {
			if (trainLeft > 0 || fuse >= 0f) {
				// More detonations coming: hold the sprint, no braking between jolts (vanilla's multiple impacts).
				s.sysSpeedFlat += speedBoost;
				clamp(s, normal, normal + speedBoost * vk, dt);
				return;
			}
			brakeT += dt;
			boolean done = clamp(s, normal, normal, dt * 2f);
			if (done || brakeT > 3f) brakeT = -1f;
		}
	}

	/**
	 * Vanilla's braking past a cap: deceleration as big as the excess (at least the normal top speed),
	 * scaled by `rate`. True once the ship is down to the cap.
	 */
	private static boolean clamp(Ship3D s, float normal, float cap, float rate) {
		float speed = s.vel.length();
		if (speed <= cap) return true;
		float decel = Math.max(normal, speed - normal);
		float next = Math.max(cap, speed - decel * rate);
		s.vel.scale(next / speed);
		return next <= cap;
	}
}
