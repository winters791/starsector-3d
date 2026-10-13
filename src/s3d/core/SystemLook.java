package s3d.core;

import java.util.HashSet;
import java.util.Set;

/**
 * How a ship system looks, in vanilla's own terms, so the 3D version reads like the 2D one:
 * "jitter" (tinted copies of the ship drawn at random small offsets every frame, over the hull or
 * under it so only a fuzzy halo shows round the silhouette), weapon glow on one weapon type,
 * engine flames recoloured / stretched / brightened, shield colours, and the colours of its
 * effects (EMP arcs, the phase highlight). Ranges are in sprite pixels like vanilla's.
 *
 * Defaults per kind match the vanilla system they rebuild; for a real ship the host overrides them
 * with whatever the system's own data file sets (so another mod's systems keep their colours).
 * Colours are {r, g, b, a} in 0..1.
 */
public final class SystemLook {
	/** Jitter over the hull (null = none). */
	public float[] jitter;
	public int jitterCopies;
	public float jitterMin, jitterRange, jitterRadiusFraction;
	/** Jitter under the hull: a halo round the silhouette (null = none). */
	public float[] jitterUnder;
	public int jitterUnderCopies;
	public float jitterUnderMin, jitterUnderRange, jitterUnderRadiusFraction;
	/** Extra jitter range (both kinds) at full effect, as some vanilla scripts grow it. */
	public float jitterGrow;
	/** Square the effect for the jitter's strength (vanilla does for some), or take its root (temporal shell). */
	public boolean jitterSquared, jitterRoot;
	/** The ship's own jitter only while charging up, fading over half a second after (Entropy Amplifier). */
	public boolean jitterWhileCharging;
	/** Strength squared while powering down only (Quantum Disruptor). */
	public boolean jitterSquaredOut;
	/** Jitter after each detonation instead, fading over this many seconds (Orion Device 2, Nova Burst 1). */
	public float jitterAfterUse;
	/** Extra jitter range while charging that shrinks to nothing as it comes up (Energy Lash: 150). */
	public float jitterShrinkIn;
	/** Vanilla's "standard" Threat jitter: at least half strength while on (0.5 + 0.5 x effect), squared powering down. */
	public boolean jitterHalfBase;
	/** Weapon glow on the ship's fragment weapons (Fragment Volley). */
	public boolean glowFragmentWeapons;

	/** Weapon glow on these weapon types (null colour = none). */
	public float[] weaponGlow;
	public final Set<String> weaponTypes = new HashSet<>();
	/** Lidar Array: glow on non-beam ballistic/energy hardpoints instead of by type. */
	public boolean glowLidarHardpoints;

	/** Engine flames: colour faded to while it runs (alpha = how far; null = unchanged), and multipliers. */
	public float[] engineColor;
	public float engineLength = 1f, engineWidth = 1f, engineGlow = 1f;
	/** The hull's system-only engines (extra thrusters) light while it runs (vanilla ENGINE_MOD systems). */
	public boolean systemEngines;

	/** Shield colours while it runs (null = unchanged). */
	public float[] shieldRing, shieldInner;
	/** Effect colours: EMP emitter arcs (fringe, core), phase highlight (1) and diffuse (2). */
	public float[] effect1, effect2;

	/** Jitter on the system's target (Entropy Amplifier). */
	public float[] targetJitter;
	public int targetJitterCopies;
	public float targetJitterRange;
	/** Jitter on the carrier's own fighters (Targeting Feed, Recall Device, Termination Sequence drones). */
	public float[] fighterJitter, fighterJitterUnder, fighterWeaponGlow;
	public int fighterJitterCopies, fighterJitterUnderCopies;
	public float fighterJitterRange;
	/** Recall: the fighters' jitter spreads out to their own radius as it charges, and they fade. */
	public boolean fighterJitterGrows;

	private static float[] rgba(int r, int g, int b, int a) {
		return new float[] {r / 255f, g / 255f, b / 255f, a / 255f};
	}

	/** Vanilla's look for each system kind (its data file and stats script). */
	public static SystemLook defaults(ShipSystem3D.Kind kind) {
		SystemLook l = new SystemLook();
		switch (kind) {
			case BURN_DRIVE:
				l.engineLength = 2.5f;
				l.engineWidth = 3f;
				l.engineGlow = 2f;
				l.systemEngines = true;
				break;
			case MANEUVERING_JETS:
				l.engineGlow = 2f;
				l.systemEngines = true;
				break;
			case PLASMA_JETS:
				l.engineColor = rgba(100, 255, 100, 255);
				l.engineLength = 3f; // its script stretches the flame by twice its length
				l.engineGlow = 2f;
				l.systemEngines = true;
				break;
			case MICROBURN:
				l.engineColor = rgba(100, 255, 100, 255);
				l.engineLength = 3f;
				l.engineWidth = 1.5f;
				l.engineGlow = 3f;
				l.systemEngines = true;
				break;
			case DISPLACER:
				l.jitter = rgba(100, 165, 255, 155);
				l.jitterCopies = 10;
				l.jitterRadiusFraction = 0.5f;
				l.engineColor = rgba(100, 165, 255, 125);
				break;
			case PHASE_TELEPORTER:
				l.jitter = rgba(100, 165, 255, 150);
				l.jitterCopies = 5;
				l.jitterRange = 25f;
				l.engineLength = l.engineWidth = l.engineGlow = 0f;
				break;
			case AMMO_FEED:
				l.weaponGlow = rgba(255, 200, 0, 155);
				l.weaponTypes.add("BALLISTIC");
				break;
			case HIGH_ENERGY_FOCUS:
				l.weaponGlow = rgba(255, 0, 255, 255);
				l.weaponTypes.add("ENERGY");
				break;
			case LIDAR_ARRAY:
				l.weaponGlow = rgba(255, 50, 50, 155);
				l.glowLidarHardpoints = true;
				break;
			case FORTRESS_SHIELD:
				l.shieldRing = rgba(255, 255, 255, 255);
				l.shieldInner = rgba(255, 100, 255, 75);
				break;
			case DAMPER:
				l.jitterUnder = rgba(255, 165, 90, 155);
				l.jitterUnderCopies = 25;
				l.jitterUnderRange = 7f;
				l.jitter = rgba(255, 165, 90, 55);
				l.jitterCopies = 2;
				l.jitterRange = 5f;
				break;
			case TEMPORAL_SHELL:
				l.jitter = rgba(90, 165, 255, 55);
				l.jitterCopies = 3;
				l.jitterUnder = rgba(90, 165, 255, 155);
				l.jitterUnderCopies = 25;
				l.jitterUnderRange = 7f;
				l.jitterGrow = 10f;
				l.jitterRoot = true;
				l.engineColor = rgba(90, 165, 255, 255);
				l.engineLength = l.engineWidth = l.engineGlow = 0.75f;
				break;
			case PHASE_CLOAK:
				l.effect1 = rgba(255, 175, 255, 255);
				l.effect2 = rgba(255, 0, 255, 150);
				l.engineLength = l.engineWidth = l.engineGlow = 0f;
				break;
			case EMP_EMITTER:
				l.effect1 = rgba(255, 150, 255, 200);
				l.effect2 = rgba(255, 175, 255, 255);
				break;
			case ENTROPY_AMPLIFIER:
				l.jitter = rgba(255, 50, 50, 75);
				l.jitterCopies = 4;
				l.jitterGrow = 50f;
				l.jitterWhileCharging = true;
				l.targetJitter = rgba(255, 50, 50, 75);
				l.targetJitterCopies = 3;
				l.targetJitterRange = 5f;
				break;
			case MINE_STRIKE:
				l.jitterUnder = rgba(255, 155, 255, 155);
				l.jitterUnderCopies = 11;
				l.jitterUnderRange = 3f;
				l.jitter = rgba(255, 155, 255, 75);
				l.jitterCopies = 4;
				l.jitterGrow = 25f;
				l.jitterSquared = true;
				break;
			case TARGETING_FEED:
				l.fighterJitterUnder = rgba(255, 50, 0, 75);
				l.fighterJitterUnderCopies = 5;
				l.fighterJitter = rgba(255, 50, 0, 125);
				l.fighterJitterCopies = 2;
				l.fighterJitterRange = 5f;
				l.fighterWeaponGlow = rgba(255, 50, 0, 255);
				break;
			case RECALL:
				l.fighterJitter = rgba(100, 165, 255, 155);
				l.fighterJitterCopies = 10;
				l.fighterJitterRange = 5f;
				l.fighterJitterGrows = true;
				break;
			case COMBAT_BURN:
				l.engineLength = 1.5f;
				l.engineWidth = 2f;
				l.engineGlow = 2f;
				l.systemEngines = true;
				break;
			case ORION_DEVICE:
				l.jitter = rgba(255, 125, 25, 55);
				l.jitterCopies = 3;
				l.jitterAfterUse = 2f;
				break;
			case NOVA_BURST:
				l.jitter = rgba(100, 165, 255, 35);
				l.jitterCopies = 3;
				l.jitterAfterUse = 1f;
				break;
			case ACAUSAL_DISRUPTOR:
				l.jitterUnder = rgba(255, 155, 255, 155);
				l.jitterUnderCopies = 21;
				l.jitterUnderRange = 3f;
				l.jitter = rgba(255, 155, 255, 75);
				l.jitterCopies = 4;
				l.jitterGrow = 50f;
				l.jitterSquaredOut = true;
				break;
			case ENERGY_LASH:
				l.jitter = rgba(130, 155, 145, 255);
				l.jitterCopies = 5;
				l.jitterRange = 3f;
				l.jitterShrinkIn = 150f;
				break;
			case FRAGMENT_VOLLEY:
				l.jitter = rgba(130, 155, 145, 255);
				l.jitterCopies = 1;
				l.jitterRange = 4f;
				l.jitterHalfBase = true;
				l.weaponGlow = rgba(130, 155, 145, 50);
				l.glowFragmentWeapons = true;
				break;
			case INCURSION_MODE:
				l.jitter = rgba(130, 155, 145, 255);
				l.jitterCopies = 1;
				l.jitterRange = 4f;
				l.jitterHalfBase = true;
				l.weaponGlow = rgba(130, 155, 145, 50);
				l.weaponTypes.add("BALLISTIC");
				l.weaponTypes.add("ENERGY");
				l.engineLength = 2f;
				l.engineGlow = 1.5f;
				break;
			case EXTRACTION_PROTOCOL:
				l.jitter = rgba(130, 155, 145, 255);
				l.jitterCopies = 1;
				l.jitterRange = 4f;
				l.jitterHalfBase = true;
				l.engineGlow = 2f;
				break;
			case DRONE_STRIKE:
				l.fighterJitter = rgba(255, 100, 50, 75);
				l.fighterJitterCopies = 10;
				l.fighterJitterRange = 10f;
				break;
			default:
		}
		return l;
	}

	/** Strength of the system's own jitter now. */
	public float jitterLevel(ShipSystem3D sys) {
		float effect = sys.effect;
		if (jitterAfterUse > 0f) return sys.orion != null ? Math.max(0f, 1f - sys.orion.sinceDetonation / jitterAfterUse) : 0f;
		if (jitterSquaredOut && sys.state == ShipSystem3D.State.OUT) return effect * effect;
		if (jitterHalfBase) {
			float j = 0.5f + 0.5f * effect;
			return sys.state == ShipSystem3D.State.OUT ? j * j : j;
		}
		if (jitterWhileCharging) {
			if (sys.state == ShipSystem3D.State.IN) return effect;
			return sys.state == ShipSystem3D.State.ACTIVE ? Math.max(0f, 1f - sys.stateTime / 0.5f) : 0f;
		}
		if (jitterRoot) return (float) Math.sqrt(effect);
		return jitterSquared ? effect * effect : effect;
	}
}
