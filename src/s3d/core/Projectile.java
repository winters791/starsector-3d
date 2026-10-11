package s3d.core;

import s3d.math.Vec3;

public final class Projectile {
	public final Vec3 pos = new Vec3(), prev = new Vec3(), vel = new Vec3(), origin = new Vec3();
	/** Firing direction relative to the shooter (for drawing the streak; vel includes ship velocity). */
	public final Vec3 dir = new Vec3();
	public Ship3D source;
	public WeaponSpec3D spec;
	public float maxRange;
	/** Damage multiplier fixed at firing (e.g. High Energy Focus). */
	public float damageMult = 1f;
	public boolean alive = true;

	/** Distance from the mount it was fired from — range is a sphere around the mount. */
	public float distanceFromOrigin() {
		return pos.distance(origin);
	}
}
