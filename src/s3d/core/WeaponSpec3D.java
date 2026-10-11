package s3d.core;

/**
 * Weapon numbers as the 3D sim needs them, read by the host from the live vanilla weapon
 * (so range/damage/rate-of-fire bonuses from hullmods and skills are already in).
 */
public final class WeaponSpec3D {
	public enum Kind { PROJECTILE, BEAM, MISSILE }

	public String id, name;
	public Kind kind = Kind.PROJECTILE;
	/** Point defence: autofire goes after missiles first; pdOnly never shoots ships. */
	public boolean pd, pdOnly;
	/** "BALLISTIC", "ENERGY", "MISSILE", ... (for the HUD and future rules). */
	public String type;
	public String size;
	/** False for beams and missiles until they're supported; those mounts never fire. */
	public boolean supported;
	public String unsupportedReason;

	public float range, projectileSpeed, damage;
	public String damageType;
	/** Damage-type multipliers vs shields, armor, hull (vanilla DamageType values). */
	public float shieldMult = 1f, armorMult = 1f, hullMult = 1f;

	/** Soft flux per shot (projectiles, missiles) and per second while a beam is on. */
	public float fluxPerShot, fluxPerSecond;
	/** Vanilla "impact" (knockback mass of the shot): kinetic guns high, energy 0. */
	public float impact;
	/** EMP per shot (per second for beams). */
	public float emp;
	/** Vanilla ion/tachyon effects: EMP arcs that can pierce a shield (more likely the more hard flux it holds). */
	public boolean empArcs;
	/** Proximity fuse: detonates within proxRange of an enemy missile; blast radius / full-damage core radius. */
	public float proxRange, proxRadius, proxCore;
	/** Splitting missile (MIRV): warheads, split distance (+ random extra), earliest split, fan arc (deg). */
	public int mirvCount;
	public float mirvSplitRange, mirvSplitRangeRandom, mirvMinTime, mirvArcDeg;
	public WeaponSpec3D mirvWarhead;
	/** Vanilla sound ids: played per shot / launch, or looped while a beam is on; null = none. */
	public String fireSound, beamLoopSound;
	/** Vanilla script on this weapon that 3D doesn't model (logged), or null. */
	public String unmodelledEffect;

	/** Seconds between bursts (or shots, when burstSize is 1). */
	public float cooldown;
	public float chargeTime;
	public int burstSize = 1;
	public float burstDelay;
	/** Degrees. */
	public float minSpread, maxSpread, spreadBuildup, spreadDecay;
	/** Degrees per second. */
	public float turnRate;
	/** Ammo; maxAmmo <= 0 means unlimited. */
	public int maxAmmo;
	public float ammoPerSecond;

	// Beam (damage is per second; armor hit strength = dps * dpsToHitStrengthMult)
	public float dps, chargeUp, chargeDown, burstDuration;
	public boolean burstBeam;
	public float beamWidth = 10f, beamSpeed = 10000f;

	// Missile (the missile's own engine and durability)
	public float launchSpeed, missileMaxSpeed, missileAccel, missileTurnRate, missileTurnAccel;
	public float flightTime, missileHp, missileRadius = 10f, explosionRadius = 30f;
	public boolean guided;
	public float[] missileGlow = {1f, 0.6f, 0.3f};
	/** Render-side model source for the missile sprite (ShipModel.Source). */
	public Object missileModelSource, missileModel;

	// Projectile look
	public float projLength = 20f, projWidth = 4f;
	public float[] coreColor = {1f, 1f, 1f}, fringeColor = {1f, 0.6f, 0.3f};
}
