package s3d.core;

/**
 * The numbers of the officer skills vanilla runs as combat listeners (see CombatStats). The host
 * overwrites these defaults with vanilla's own public constants when a 3D battle starts, so other
 * mods' changes to them carry over. Ranges are vanilla units: the sim scales them by the weapon range mult.
 */
public final class SkillRules {
	/** Energy Weapon Mastery: full bonus at minRange and closer, none from maxRange on. */
	public float ewmMinRange = 600f, ewmMaxRange = 1000f;
	/** Ranged Specialization: nothing at minRange, rising to maxPercent at maxRange; with crits on, that's a chance of +critPercent instead. */
	public float rsMinRange = 800f, rsMaxRange = 1600f, rsMaxPercent = 30f, rsCritPercent = 100f;
	public boolean rsCrits;
	/** Damage Control (elite): once every procSeconds, a hit's hull damage past threshold is cut by reductionPercent. */
	public float dcProcSeconds = 2f, dcThreshold = 500f, dcReductionPercent = 60f;
	/** Combat Endurance (elite): rate x max hull per second up to maxLevel of the hull, at most max(points, fraction x max hull) per battle. */
	public float ceRate = 0.005f, ceMaxLevel = 1f, ceMaxPoints = 2000f, ceMaxFraction = 0.5f;
}
