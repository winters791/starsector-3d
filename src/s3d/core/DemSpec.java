package s3d.core;

/**
 * A DEM missile's attack (vanilla Gorgon, Gazer, Dragonfire, Hydra warheads): it cruises in like any
 * guided missile, then near the target stops at a stand-off, turns onto it, paints it with a
 * targeting laser and fires its payload beam along the nose. Distances are vanilla map units (the
 * sim scales them like weapon ranges). Pairs (min, max) are rolled per missile.
 */
public final class DemSpec {
	/** Seconds after launch before it may start its attack (unless it's already too close). */
	public float minDelay;
	/** Starts the attack this close to the target's hull. */
	public float triggerMin = 500f, triggerMax = 500f;
	/** Holds between these distances while aiming and firing; holdMax < 0 = its trigger distance. */
	public float holdMinLo, holdMinHi, holdMax = -1f;
	/** Skips the delay once it has swung this far round the target from where it was launched (deg); < 0 = never. */
	public float triggerAngleLo = -1f, triggerAngleHi = -1f;
	/** Drift allowed while holding, as a fraction of its top speed. */
	public float driftFrac = 0.33f;
	/** Degrees per second added to its turn rate once attacking. */
	public float turnBoost = 100f;
	/** Starts painting once its nose is within this cone (full angle, deg) of the target. */
	public float aimArcDeg = 10f;
	public float targetingTime = 1f, firingTime = 1.25f;
	/** Targeting laser reach (0 = no laser) and colour. */
	public float laserRange;
	public float[] laserColor = {1f, 0.25f, 0.2f};
	/** Bomb-pumped: the missile blows itself apart and the beam fires from where it was. */
	public boolean bombPumped;
	/** Explodes when the payload is spent (else its engine dies and it drifts off). */
	public boolean destroyWhenDone;
	/** Sidesteps at random while holding (Gazer). */
	public boolean randomStrafe;
	/** The payload weapon: a beam fired from the missile along its nose. */
	public WeaponSpec3D payload;
}
