package s3d.core;

import s3d.math.Vec3;

/** One engine nozzle in ship-local 3D space (forward -Z, right +X, up +Y). */
public final class EngineMount {
	public final Vec3 pos;
	/** Exhaust direction (unit), usually +Z (backwards). */
	public final Vec3 dir;
	public final float length, width;
	public final float r, g, b;

	public EngineMount(Vec3 pos, Vec3 dir, float length, float width, float r, float g, float b) {
		this.pos = pos;
		this.dir = dir;
		this.length = length;
		this.width = width;
		this.r = r;
		this.g = g;
		this.b = b;
	}

	/**
	 * Converts a Starsector engine slot (ship space: x forward, y left; angle in degrees,
	 * counter-clockwise from forward) to a 3D mount on the ship's mid-plane.
	 */
	public static EngineMount fromShipSpace(float fwd, float left, float angleDeg, float length, float width,
											float r, float g, float b) {
		double a = Math.toRadians(angleDeg);
		Vec3 pos = new Vec3(-left, 0f, -fwd);
		Vec3 dir = new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)).normalize();
		return new EngineMount(pos, dir, length, width, r, g, b);
	}
}
