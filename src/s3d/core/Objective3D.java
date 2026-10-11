package s3d.core;

import s3d.math.Vec3;

/**
 * A battle objective (nav buoy, sensor array, comm relay) as the 3D side sees it: where it is, who
 * holds it and who is near it. Vanilla owns the objective itself; the host keeps this in step.
 */
public final class Objective3D {
	public final String type, name;
	public final Vec3 pos = new Vec3();
	/** 0 or 1, or -1 while nobody holds it. */
	public int owner = -1;
	/** Ships closer than this (horizontally, 3D units) count as on it. */
	public float radius;
	/** Ships of each side on it right now (host-updated, for the HUD). */
	public final int[] present = new int[2];
	/** Back-reference for the host (the vanilla objective). Never read by core code. */
	public Object hostRef;

	public Objective3D(String type, String name) {
		this.type = type;
		this.name = name;
	}

	/** Horizontal distance from p (objectives are taken by standing over them, at any height). */
	public float flatDistance(Vec3 p) {
		return (float) Math.hypot(p.x - pos.x, p.z - pos.z);
	}

	/** Who is contesting it: 0 or 1 when only that side is on it, -1 otherwise. */
	public int holder() {
		if (present[0] > 0 && present[1] == 0) return 0;
		if (present[1] > 0 && present[0] == 0) return 1;
		return -1;
	}
}
