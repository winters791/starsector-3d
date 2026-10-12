package s3d.core;

import s3d.math.Vec3;

/**
 * A missile in flight: steers its nose toward an intercept point at the missile's turn rate and
 * pushes its velocity toward nose * max speed with its acceleration, so it arcs like vanilla's.
 */
public final class Missile3D {
	public static final int TRAIL = 24;

	public final Vec3 pos = new Vec3(), prev = new Vec3(), vel = new Vec3(), dir = new Vec3();
	public Ship3D source, target;
	public WeaponSpec3D spec;
	public int owner;
	public float age, hp;
	/** Distance to its target at which a splitting missile splits (rolled at launch). */
	public float splitAt;
	/** A decoy flare (harmless, lures enemy guided missiles); a missile lured onto a flare chases it. */
	public boolean flare;
	public Missile3D lure;
	float lureCheck;
	public boolean alive = true;
	/** Engine burned out (flight time over): drifts and fades. */
	public float fade = -1f;
	/** Where it was launched (a warhead: where it split off). */
	public final Vec3 spawnPos = new Vec3();
	/** Damage multiplier fixed at launch (Targeting Feed), and flight-time multiplier (Canister Flak's random fuses). */
	public float damageMult = 1f, lifeMult = 1f;
	/** Mine: seconds since it sensed an enemy (< 0 = not yet). */
	public float windup = -1f;
	/** Termination Sequence: the drone flying as this missile; it dies when the missile ends. */
	public Ship3D rider;

	// ---- DEM attack (spec.dem != null) ----
	/** 0 cruising, 1 turning onto the target, 2 painting it, 3 firing the payload, 4 spent. */
	public int demState;
	float demTimer, demTrigger, demHoldMin, demHoldMax, demAngle, strafeLeft;
	int strafeDir;
	Ship3D demTarget;
	Combat.PayloadBeam payload;
	/** Targeting laser (drawn while painting): 0..1 and where it ends. */
	public float laserLevel;
	public final Vec3 laserEnd = new Vec3();

	// Render-only trail ring buffer
	public final float[] trail = new float[TRAIL * 3];
	public int trailHead, trailCount;
	public float trailTimer;

	public boolean flamedOut() {
		return fade >= 0f;
	}

	void pushTrail() {
		trail[trailHead * 3] = pos.x;
		trail[trailHead * 3 + 1] = pos.y;
		trail[trailHead * 3 + 2] = pos.z;
		trailHead = (trailHead + 1) % TRAIL;
		trailCount = Math.min(TRAIL, trailCount + 1);
	}
}
