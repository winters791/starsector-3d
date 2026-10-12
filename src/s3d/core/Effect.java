package s3d.core;

import s3d.math.Vec3;

/** Short-lived visual events produced by the sim (drawn by the renderer / HUD, never simulated). */
public final class Effect {
	/** RIFT: a dark tear with a coloured rim (Omega / Dweller rifts). */
	public enum Kind { FLASH, SPARK, DAMAGE_TEXT, EXPLOSION, ARC, RIFT }

	public final Kind kind;
	public final Vec3 pos = new Vec3(), vel = new Vec3();
	/** Second end point (ARC: an EMP arc from pos to end). */
	public final Vec3 end = new Vec3();
	public float size, life, maxLife;
	public float r = 1f, g = 1f, b = 1f;
	public String text;

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

	public float age01() {
		return maxLife <= 0f ? 1f : 1f - life / maxLife;
	}
}
