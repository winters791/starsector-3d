package s3d.core;

import s3d.math.Vec3;

/** Short-lived visual events produced by the sim (drawn by the renderer / HUD, never simulated). */
public final class Effect {
	/**
	 * RIFT: a dark tear with a coloured rim (Omega / Dweller rifts). RING: an expanding shockwave shell.
	 * NEBULA: vanilla's nebula particle, a soft cloud that fades in, holds, then fades while it grows to `grow` times its size.
	 */
	public enum Kind { FLASH, SPARK, DAMAGE_TEXT, EXPLOSION, ARC, RIFT, RING, NEBULA }

	public final Kind kind;
	public final Vec3 pos = new Vec3(), vel = new Vec3();
	/** Second end point (ARC: an EMP arc from pos to end). */
	public final Vec3 end = new Vec3();
	public float size, life, maxLife;
	public float r = 1f, g = 1f, b = 1f;
	public String text;
	/** Seconds before it appears (it drifts with vel meanwhile); then sound, if set, plays once. */
	public float delay;
	/** NEBULA: size it grows to by the end, as a multiple of its starting size. */
	public float grow = 1f;
	public String sound;
	public float soundVolume = 1f;

	public Effect(Kind kind, Vec3 at, float size, float life) {
		this.kind = kind;
		this.pos.set(at);
		this.size = size;
		this.life = life;
		this.maxLife = life;
	}

	public Effect color(float r, float g, float b) {
		this.r = r;
		this.g = g;
		this.b = b;
		return this;
	}

	public Effect delayed(float seconds) {
		delay = Math.max(0f, seconds);
		return this;
	}

	public Effect withSound(String id, float volume) {
		sound = id;
		soundVolume = volume;
		return this;
	}

	public boolean visible() {
		return delay <= 0f;
	}

	public float age01() {
		return maxLife <= 0f ? 1f : 1f - life / maxLife;
	}
}
