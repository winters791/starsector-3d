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
	/** The weapon group icon in your ship panel: vanilla's turret sprite layers, bottom first (under, base, barrel); null = none. */
	public String[] iconSprites;
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
	/**
	 * Proximity fuse: detonates within proxRange of an enemy missile, fighter or hull, and where its
	 * flight or range runs out; blast radius / full-damage core radius.
	 */
	public float proxRange, proxRadius, proxCore;
	/** Fused shell: its range is cut by up to this fraction (rolled per shot, with chance proxVarianceChance) so it bursts short (Devastator). */
	public float proxRangeVariance, proxVarianceChance = 1f;
	/** Sound of a fused blast (null = flak's). */
	public String blastSound;
	/**
	 * A mine (Mine Strike): holds still; once armed (mineArmTime) an enemy ship whose hull comes within
	 * proxRange starts its windup (mineWindup seconds), then it blows; it blows anyway when its life
	 * (flightTime) ends.
	 */
	public boolean mine;
	public float mineArmTime, mineWindup;
	/**
	 * Splitting missile (MIRV): warheads, split distance (+ random extra), earliest split, fan arc and
	 * its offset from the missile's heading (deg), warhead ejection speed (+ random extra). The warhead
	 * is a shell, or a missile of its own (kind MISSILE: Pilum's second stage, Hydra's DEM warheads).
	 */
	public int mirvCount;
	public float mirvSplitRange, mirvSplitRangeRandom, mirvMinTime, mirvArcDeg, mirvArcOffsetDeg;
	public float mirvSpreadSpeed, mirvSpreadSpeedRandom;
	/** Warheads evenly over the arc (else at random in it), plus up to this many degrees of scatter each. */
	public boolean mirvEvenSpread = true;
	public float mirvInaccuracyDeg;
	/** May split before mirvMinTime if it would reach its split range before then (fired from close in). */
	public boolean mirvSplitEarly;
	public WeaponSpec3D mirvWarhead;
	/** Vanilla sound ids: played per shot / launch, or looped while a beam is on; null = none. */
	public String fireSound, beamLoopSound;
	/** Vanilla script on this weapon that 3D doesn't model (logged), or null. */
	public String unmodelledEffect;

	// ---- vanilla weapon scripts rebuilt in 3D (numbers set by the host) ----
	/** Breach SRM, Mining Blaster: armor stripped round a hull hit on top of the damage (armor doesn't reduce it). */
	public float armorBreak;
	/** Disintegrator: armor burned away where it hit, over ticks about a second apart; never the hull. */
	public float armorBurn;
	public int armorBurnTicks;
	/**
	 * Graviton Beam: a shield it plays on takes this much more damage from everything (fraction, for
	 * 1, 2, 3+ such beams on it), lasting shieldMarkTime seconds after the beam leaves.
	 */
	public float[] shieldMark;
	public float shieldMarkTime;
	/** Squall: extra damage when it hits a shield. */
	public float shieldBonus;
	/** Paladin PD: a blast where the beam lands, once per burst at full power, sparing what it hit. */
	public WeaponSpec3D beamBlast;
	/**
	 * Pilum, Sabot: chance of an extra EMP-only arc on a hull hit; pierces = also through a raised
	 * shield, as likely as an ion arc (by the target's hard flux).
	 */
	public float bonusArcChance;
	public boolean bonusArcPierces;
	/** Shock Repeater: no shell; each shot is an instant arc to the nearest enemy in range within this cone (full angle, deg). */
	public float arcGunConeDeg;
	/** DEM missile attack (stand-off, targeting laser, payload beam), or null. */
	public DemSpec dem;
	/** For the log: the scripts this weapon has a 3D version of, and the purely visual ones (nothing to model). */
	public String effectNote, visualEffect;

	// ---- batch 2: Omega / Threat / Dweller scripts, flare launchers, missile blasts (ExoticWeapons) ----
	public enum RiftMode { NONE, SCATTER, CASCADE, LANCE, GLARE, LIGHTNING }
	/**
	 * Rifts this weapon opens: Rift Beam scatters them along the beam, Rift Cascade walks them round
	 * what it hits, Rift Lance (if its chance is raised) one where it lands, Abyssal Glare where it lands,
	 * Rift Lightning where its lightning strikes. `rift` is the blast (damage, EMP, proxRadius, proxCore,
	 * blastFF), already with the ship's energy damage bonus.
	 */
	public WeaponSpec3D rift;
	public RiftMode riftMode = RiftMode.NONE;
	/** Seconds between rifts (scatter, cascade, glare) and from a rift appearing to it opening. */
	public float riftInterval, riftDelay;
	/** Rift damage multiplier (Abyssal Glare outside Darkened Gaze: half). */
	public float riftDamageMult = 1f;
	/** Chance of a rift (lance: per burst; glare: per tick); glare's longest wait between rifts. */
	public float riftChance, riftMaxGap;
	/** Scatter: reach from the beam for missiles/fighters and how far off it a rift may be; cascade: spacing round the target, range per extra rift, max rifts. */
	public float riftReach, riftSpread;
	public int riftMax;
	/** Rift Lightning: how fast the lightning travels (the rift opens when it arrives). */
	public float riftSpeed;
	/** A blast that hits friend and foe alike (never the ship that made it). */
	public boolean blastFF;
	/** Missiles that blow up over an area when they hit (vanilla explosionSpec): the blast, sparing what was hit. */
	public WeaponSpec3D impactBlast;
	/** Size of the rift tear a missile leaves where it hits (Rift Torpedo, Hungering Rift); 0 = none. */
	public float impactRift;
	/** Missile or shot that flies through fighters / missiles without hitting them (Rift Torpedo, Voltaic Cannon). */
	public boolean passFighters, passMissiles;
	/** Flares don't fool this missile. */
	public boolean ignoresFlares;
	/** Missile flames out once it's its weapon range from where it was launched. */
	public boolean fizzleAtRange;
	/** Launch velocity multiplier (Neutron Torpedo leaves slowly). */
	public float launchVelMult = 1f;
	/** Reality Disruptor: the shot is an orb that passes through everything and arcs (shot damage + EMP) to the nearest enemy in reach. */
	public boolean orb;
	public float orbArcRange, orbArcInterval, orbArcDelay;
	/** What its arcs reach repairs at slowRepairMult speed for slowRepairTime seconds (Reality Disruptor, Inimical Emanation). */
	public float slowRepairMult = 1f, slowRepairTime;
	/** Arc guns: the arc leaves from a point round the ship (its swarm or shroud), not the muzzle; it can reach phased ships. */
	public boolean arcFromShip, arcHitsPhased;
	/** Arc gun look and impact sound (null = Shock Repeater's). */
	public float[] arcColor;
	public String arcImpactSound;
	/** Voltaic Cannon: EMP-only arcs on a hit (min..max), through a raised shield as often as an ion arc. */
	public int empArcsMin, empArcsMax;
	/** Voidblaster: shield hits are soft flux. */
	public boolean softOnShields;
	/** Threat fragment weapons: fragments the ship's swarm needs to fire, and spends per shot. */
	public int fragmentsNeeded, fragmentsSpent;
	/**
	 * Swarm Launcher / Churning Locus: the wing it puts into the battle instead of firing a shot (null
	 * = an ordinary weapon), what it does once out, how many it keeps out at once (0 = no limit), and
	 * the spread it launches them in (degrees).
	 */
	public String launchWing;
	/** Extra damage when it hits a fighter (Mote Attractor's motes). */
	public float antiFighterDamage;
	public Ship3D.Behaviour launchBehaviour;
	public int launchMaxOut;
	public float launchArcDeg;
	/** Kinetic Fragments: the escort fragments that strike with a hit (fewer as the missile is shot up), each this hit. */
	public WeaponSpec3D fragmentStrike;
	public int fragmentStrikes;
	/** Defabrication Swarm: a hull hit does nothing up front; its damage eats in over dotTicks ticks, each damaging one may give the firer a fragment back. */
	public int dotTicks;
	public float dotRefundChance;
	/** Hungering Rift: a hull hit heals a damaged friendly Dweller this much; at most riftCap of these missiles out at once (0 = no cap). */
	public float riftHeal;
	public int riftCap;
	/** Flare launcher: its missiles are decoy flares that lure enemy guided missiles. */
	public boolean flareLauncher;

	/** Seconds between bursts (or shots, when burstSize is 1). */
	public float cooldown;
	public float chargeTime;
	public int burstSize = 1;
	public float burstDelay;
	/** Degrees. */
	public float minSpread, maxSpread, spreadBuildup, spreadDecay;
	/** Degrees per second. */
	public float turnRate;
	/** Ammo; maxAmmo <= 0 means unlimited. baseMaxAmmo: before hullmods and skills (Missile Autoforge reloads that much). */
	public int maxAmmo, baseMaxAmmo;
	public float ammoPerSecond;

	// Beam (damage is per second; armor hit strength = dps * dpsToHitStrengthMult)
	public float dps, chargeUp, chargeDown, burstDuration;
	public boolean burstBeam;
	public float beamWidth = 10f, beamSpeed = 10000f;

	// Missile (the missile's own engine and durability)
	public float launchSpeed, missileMaxSpeed, missileAccel, missileTurnRate, missileTurnAccel;
	public float flightTime, missileHp, missileRadius = 10f, explosionRadius = 30f;
	public boolean guided;
	/**
	 * How a guided missile goes for its target, from vanilla's missile type: HOMING (MISSILE) all the
	 * way; TWO_STAGE (Squall, Hurricane's warheads) coasts while it turns onto the lead, locks that
	 * heading and burns straight; HEATSEEKER (Salamander) circles round to the target's tail before it
	 * goes in; AIM_POINT (Proximity Charge) flies to where it was aimed, stops there and waits.
	 */
	public enum Guidance { HOMING, TWO_STAGE, HEATSEEKER, AIM_POINT }
	public Guidance guidance = Guidance.HOMING;
	public float[] missileGlow = {1f, 0.6f, 0.3f};
	/** Render-side model source for the missile sprite (ShipModel.Source). */
	public Object missileModelSource, missileModel;

	// Projectile look
	public float projLength = 20f, projWidth = 4f;
	public float[] coreColor = {1f, 1f, 1f}, fringeColor = {1f, 0.6f, 0.3f};
}
