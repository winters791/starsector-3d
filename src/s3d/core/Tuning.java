package s3d.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Every feel/visual knob in one place. Defaults live here; data/config/s3d_tuning.json
 * overrides any subset and can be reloaded in-game. Units: game units (1 sprite pixel),
 * seconds, degrees in the JSON (converted where used).
 */
public final class Tuning implements Cloneable {

	// ---- flight ----
	/** 3D turn rate = turnCurveRef * (hullTurnRate / turnCurveRef)^turnCurveExp * turnRateMult. Exp < 1 compresses the frigate/capital gap. */
	public float turnCurveRef = 60f;
	public float turnCurveExp = 0.75f;
	/** User (2026-10-11): ships were far too fast; speed, acceleration and turning all halved. */
	public float turnRateMult = 0.5f;
	public float pitchRateMult = 1f;
	public float yawRateMult = 1f;
	public float rollRateMult = 1.6f;
	public float rollRateMinDeg = 12.5f;
	/** Multiplies hull turn acceleration (after the curve keeps the hull's spin-up time). Higher = snappier. */
	public float turnAccelMult = 2f;
	/**
	 * User (2026-10-12): "a tad faster", +15% top speed (0.5 -> 0.575); later the same day +50% on top
	 * (0.575 -> 0.8625), with weapon ranges halved. Acceleration and turning unchanged.
	 */
	public float speedMult = 0.8625f;
	public float accelMult = 0.5f;
	public float decelMult = 0.5f;
	public float strafeSpeedMult = 0.6f;
	public float strafeAccelMult = 0.7f;
	/** Up/down strafe speed, acceleration and braking relative to sideways strafe (user 2026-10-12: 30% faster). */
	public float verticalMult = 1.3f;
	public float reverseSpeedMult = 0.5f;
	/** How hard flight assist kills sideways drift, as a multiple of hull deceleration. */
	public float lateralDampMult = 1f;
	/** Throttle change per second while W/S held. */
	public float throttleRate = 0.8f;
	public boolean autoLevelDefault = true;
	public float autoLevelGain = 2.5f;
	/** Cosmetic bank into yaw turns (render only). */
	public float visualBankDeg = 10f;

	// ---- mouse ----
	public float mouseDeadzone = 0.03f;
	/** Reticle distance from screen centre for full turn rate, as a fraction of screen height. */
	public float mouseFullDeflection = 0.42f;
	/** 0 = linear response, 1 = fully cubic (fine control near centre). */
	public float mouseExpo = 0.35f;
	public boolean invertPitch = false;
	/** Hide the game's hardware cursor while the 3D view is up (our HUD draws the reticle). */
	public boolean hideGameCursor = true;

	// ---- camera ----
	public float camFovDeg = 70f;
	public float camBackRadii = 3.0f;
	public float camBackMin = 40f;
	public float camUpRadii = 0.9f;
	/**
	 * How camera distance grows with hull size, relative to a ~Wolf-sized ship (camSizeRefRadius).
	 * 1 = distance proportional to size, so every hull fills the screen the same;
	 * 0 = fixed distance, so on-screen size is true to scale (capitals fill the view).
	 */
	public float camSizeExp = 0.35f;
	public float camSizeRefRadius = 80f;
	/** Seconds for the camera to catch up with ship rotation (visible lag that shows turning). */
	public float camRotLag = 0.12f;
	/** Extra pull-back distance at full speed, in ship radii. */
	public float camSpeedPullbackRadii = 0.5f;
	public float camFreeLookReturn = 0.25f;
	public float camTacticalDist = 2600f;
	public float camTacticalPitchDeg = 55f;
	public float camTransition = 0.35f;
	public float camZoomMin = 0.5f;
	public float camZoomMax = 3f;

	// ---- render ----
	public int msaaSamples = 4;
	public float anisotropy = 8f;
	public int dustCount = 900;
	public float dustRange = 800f;
	public float dustStreak = 0.1f;
	public float dustBrightness = 0.8f;
	public int starCount = 3500;
	public float nebulaStrength = 1f;
	public float[] sunDir = {0.55f, 0.45f, -0.70f};
	public float[] sunColor = {1.25f, 1.15f, 1.0f};
	public float[] ambient = {0.20f, 0.21f, 0.27f};
	/** Cool secondary light from below/behind so shadowed sides and undersides stay readable. */
	public float[] fillDir = {-0.35f, -0.75f, 0.55f};
	public float[] fillColor = {0.20f, 0.24f, 0.36f};
	public float[] nebulaColorA = {0.30f, 0.12f, 0.38f};
	public float[] nebulaColorB = {0.08f, 0.22f, 0.40f};
	public float engineFlameScale = 1f;
	/** Shield glow strength (additive; lower = easier to see through). */
	public float shieldBrightness = 0.45f;

	// ---- weapons ----
	/** Global multiplier on vanilla weapon ranges (1 = vanilla). User: halved 2 -> 1 (2026-10-12), then +20% flat, 1.2 (2026-10-13). */
	public float weaponRangeMult = 1.2f;
	/**
	 * Scales how much damage armor stops: vanilla's reduction (hit strength vs armor, capped at
	 * maxArmorDamageReduction) times this. User (2026-10-12): 30% less, so 0.7 (the 85% cap becomes 59.5%).
	 */
	public float armorReductionMult = 0.7f;
	/** Radius of the mouse reticle circle, pixels. */
	public float reticleRadiusPx = 11f;
	/** Lead circle radius relative to the reticle (1.1 = 10% bigger). Reticle inside it = shots go to the lead. */
	public float leadCircleScale = 1.1f;
	/** Enemies within this many degrees of the reticle get an automatic lead pip (no need to press R). */
	public float autoTargetDeg = 25f;
	/** Aim distance along the reticle ray when no target is selected or under the reticle. */
	public float weaponConvergence = 600f;
	public float radarRange = 5000f;
	public boolean damageNumbers = true;
	/**
	 * Weapon scripts whose numbers aren't public in vanilla's code (the rest are read from the scripts):
	 * Paladin PD's once-per-burst blast (damage, radius, full-damage core) and Sabot's EMP arc chance.
	 */
	public float fxPaladinBlastDamage = 100f, fxPaladinBlastRadius = 75f, fxPaladinBlastCore = 50f;
	public float fxSabotArcChance = 0.25f;
	/**
	 * Weapon scripts batch 2, numbers inline in vanilla's code: Rift Beam's seconds between rifts,
	 * Reality Disruptor's seconds between arcs and before the first, Abyssal Glare's rift (seconds between
	 * chances, chance, longest gap, radius, full-damage core), Defabrication Swarm's chance a damaging tick
	 * gives its ship a fragment back.
	 */
	public float fx2RiftBeamInterval = 0.5f, fx2DisruptorArcInterval = 0.1f, fx2DisruptorArcDelay = 0.5f;
	public float fx2GlareInterval = 0.25f, fx2GlareChance = 0.2f, fx2GlareMaxGap = 0.5f, fx2GlareRadius = 75f, fx2GlareCore = 50f;
	public float fx2DevouringRefundChance = 0.75f;

	// ---- per-class 3D size (relative to the sprite's 1 px = 1 unit) ----
	public final Map<String, Float> hullScale = new LinkedHashMap<>();

	public float scaleFor(String sizeClass) {
		Float f = hullScale.get(sizeClass);
		return f == null || f <= 0f ? 1f : f;
	}

	// ---- relief mesh ----
	public int meshCellsAcross = 110;
	public float meshAlphaThreshold = 0.45f;
	/** Edge rounding radius as a fraction of the sprite's smaller dimension. */
	public float meshRoundRadiusFrac = 0.25f;
	public float meshTopHeight = 0.75f;
	public float meshBottomHeight = 0.5f;
	/** Underside is this much deeper along the centreline than at the flanks (0 = flat, 1 = strong V). */
	public float meshKeel = 0.5f;
	public float meshDetailAmp = 0f;
	/** Thickness of the hull-coloured edge wall, as a fraction of the sprite's larger dimension. */
	public float meshEdgeThickness = 0.012f;
	public float meshSdfBlur = 0.8f;
	/** "hard" (bevelled stepped plates, default) or "round" (the original inflated look). */
	public String meshProfile = "hard";
	public float meshBevelFrac = 0.14f;
	public float meshBaseHeight = 0.32f;
	public int meshTiers = 3;
	public float meshTierInsetFrac = 0.32f;
	public float meshTierHeight = 0.15f;
	public float meshTierLumInfluence = 0f;
	public int meshTierLumSteps = 3;
	public float meshUnderHeight = 0.28f;
	public float meshKeelHeight = 0.18f;
	public float meshWallFrac = 0.03f;
	/** Strength of the surface detail (normal map) generated from the sprite; 0 = off. */
	public float bumpStrength = 0.4f;

	// ---- sandbox ----
	public List<String> sandboxHulls = new ArrayList<>(List.of(
			"wolf_Assault", "lasher_Standard", "hammerhead_Balanced", "eagle_Assault", "dominator_Assault", "onslaught_Standard"));

	/** Spawned side by side in front of the player for scale comparison, left to right. */
	public List<String> sandboxReferences = new ArrayList<>(List.of(
			"lasher_Standard", "hammerhead_Balanced", "sunder_Assault", "eagle_Assault", "dominator_Assault", "onslaught_Standard"));
	public float sandboxLineupDepth = 1200f;
	/** A target that flies a circle, for practising leads ("" = none). */
	public String sandboxMovingTarget = "hound_Standard";

	// ---- shields and flux ----
	/** Omni shield turn rate (vanilla hull data has none), degrees per second, before hullmod multipliers. */
	public float omniShieldTurnDeg = 120f;
	/** Active vent drains flux at dissipation times this (vanilla: 2). */
	public float ventRateMult = 2f;
	/** Overload length: base seconds for a bare overflow, up to max for an overflow of 10% of capacity or more. */
	public float overloadBaseTime = 4f, overloadMaxTime = 10f;
	/** AI holds ship-killing fire above this flux level, keeping room for its shield. */
	public float aiFluxFireLimit = 0.85f;

	// ---- EMP ----
	/** EMP from a hit reaches the nearest weapon/engine within this fraction of the hull radius (at least empArcMin). */
	public float empArcFrac = 0.35f, empArcMin = 40f;
	/** Armor reduces EMP like it reduces damage (off: EMP ignores armor). */
	public boolean empArmorReduces = false;
	/** Base seconds a disabled weapon / engine needs to come back (times the hull's repair-time stats). */
	public float weaponRepairTime = 10f, engineRepairTime = 8f;

	// ---- ship systems (effect sizes; timing, charges and flux come from the game data) ----
	/** Burn Drive: extra top speed (before speedMult), acceleration multiplier, what's left of turning (steering stays: vanilla's no-turning flag isn't applied). */
	public float sysBurnSpeed = 300f, sysBurnAccelMult = 4f, sysBurnTurnMult = 0.5f;
	/** Maneuvering / Plasma Jets: extra speed (fraction), acceleration multiplier, extra turn rate (fraction). */
	public float sysJetsSpeed = 0.5f, sysJetsAccelMult = 2f, sysJetsTurn = 0.5f, sysPlasmaSpeed = 1f;
	/** Microburn: extra top speed during the burst (before speedMult). */
	public float sysMicroburnSpeed = 600f;
	/** Phase Skimmer jump distance (3D units, x the ship's class scale). */
	public float sysDisplacerDistance = 500f;
	/** Ammo Feeder: ballistic rate-of-fire multiplier and flux-per-shot multiplier. */
	public float sysAmmoFeedRof = 2f, sysAmmoFeedFlux = 0.67f;
	/** High Energy Focus: energy weapon damage multiplier. */
	public float sysEnergyFocusDamage = 1.5f;
	/** Fortress Shield: shield damage taken multiplier. Damper Field: hull/armor damage taken multiplier. */
	public float sysFortressShieldTaken = 0.1f, sysDamperTaken = 0.33f;
	/** Temporal Shell and phase cloak: time runs this much faster for the ship. */
	public float sysTemporalTimeMult = 3f, sysPhaseTimeMult = 3f;
	/** EMP Emitter: seconds between arcs and targets per arc burst. */
	public float sysEmpInterval = 0.5f;
	public int sysEmpTargets = 3;
	/** Flare launchers: flares per use and how long they burn; distract enemy guided missiles nearby. */
	public int sysFlareCount = 3;
	public float sysFlareLife = 4f, sysFlareLure = 800f, sysFlareLureChance = 0.6f;
	/**
	 * Systems whose numbers aren't public in vanilla's code (the rest are read from the scripts): Entropy
	 * Amplifier's reach (vanilla map units, before system range bonuses); the mine Mine Strike lays (a weapon
	 * id), how long it takes to arm and its windup once an enemy is near; the missile Termination
	 * Sequence turns a drone into (a weapon id).
	 */
	public float sysEntropyRange = 1500f;
	public String sysMineWeapon = "minelayer2";
	public float sysMineArmTime = 0.5f, sysMineWindup = 1f;
	public String sysDroneStrikeWeapon = "terminator_missile";

	// ---- collisions ----
	public boolean collisions = true;
	/** Bounciness (0 = ships stop dead against each other, 1 = perfectly elastic). */
	public float collisionRestitution = 0.3f;
	/** Collision damage per unit of reduced mass x closing speed (3D units/s) / 1000; no damage below minSpeed. */
	public float collisionDamage = 8f, collisionMinSpeed = 15f;

	// ---- weight: hits push and spin ships ----
	/** Impulse per hit = impactScale x (vanilla impact + damage x impactPerDamage); velocity change = impulse / mass. */
	public float impactScale = 30f, impactPerDamage = 0.1f;
	/** How much off-centre hits spin a ship (0 = never). */
	public float spinScale = 0.3f;
	/** Firing kicks the shooter back by this fraction of the shot's impulse. */
	public float recoilScale = 0.15f;
	/** Explosions push nearby ships: impulse per unit of blast radius. */
	public float blastScale = 30f;
	/** Camera shake strength (0 = off). */
	public float cameraShake = 1f;

	// ---- sound ----
	public boolean sound = true;
	public float soundVolume = 1f;
	/** 3D distance -> vanilla's 2D sound distance (vanilla attenuates and pans by that). */
	public float soundDistanceScale = 0.3f;
	/** Sounds further than this from the camera aren't played at all. */
	public float soundRange = 6000f;

	// ---- HUD ----
	/** Battlefield minimap edge length in pixels (0 = off). */
	public float minimapSize = 230f;
	/** Show the FPS / sim / render readout at start (I toggles it). */
	public boolean perfDefault = false;

	// ---- campaign battles (Milestone 4) ----
	/** Play real campaign battles in 3D. false = every campaign battle stays vanilla 2D. */
	public boolean battleEnabled = true;
	/** 3D units per vanilla map unit when mapping positions and map edges between 2D and 3D. */
	public float battleMapScale = 0.5f;
	/**
	 * How far from the centre line each side enters (3D units; at most 85% of the way to its map edge).
	 * User (2026-10-12): about 30 s of runway before the fighting. Measured with bench fleets: 2700
	 * (5400 apart) gives first shots after 22-37 s, frigates to capitals; it was 0.4 of the half-map
	 * (1800 on an 18000 map), first shots after 10-20 s.
	 */
	public float battleDeployDistance = 2700f;
	/** Squeezes vanilla's sideways deployment spread (1 = vanilla's spread at mapScale). */
	public float battleDeploySpread = 0.5f;
	/** Spread of entry altitudes (+/- units) so fleets don't arrive on one flat plane. */
	public float battleDeployAltitude = 250f;
	/** Seconds after your ship is destroyed before command passes to your next ship. */
	public float battleTransferDelay = 3f;
	/** Station battles (and ships with modules) in 3D; false = those battles stay vanilla 2D. */
	public boolean battleStations = true;
	/**
	 * How fast stations with Axial Rotation turn in 3D, degrees per second. The outer sections' speed
	 * grows with the station scale: 1 at scale 5 moves them as fast as 2 did at scale 2.5. User
	 * (2026-10-13): 4x faster, 1 -> 4 (a full turn in 90 s).
	 */
	public float stationSpinDeg = 4f;
	/**
	 * Battle objectives, in vanilla map units: how close a ship must be to take one (vanilla's own
	 * figure isn't exposed, so the 3D pilots sit well inside this), and how long one side may hold
	 * it alone before 3D flips it itself if vanilla hasn't (seconds).
	 */
	public float battleCaptureRadius = 600f, battleCaptureFallback = 30f;
	/** Command-map orders, vanilla map units: the ground a defend/rally order holds, and the area an avoid order keeps out of. */
	public float battleHoldRadius = 1000f, battleAvoidRadius = 2000f;

	// ---- AI pilots ----
	/** AI ships try to fight at this fraction of their main weapons' range. */
	public float aiRangeFrac = 0.75f;
	/** How hard AI ships circle-strafe while in range (0..1 of strafe speed). */
	public float aiStrafe = 0.35f;
	/** Seconds between target re-evaluations. */
	public float aiRetargetTime = 2f;
	/** AI fires a ship-killing weapon only when its lead point is within this many degrees of the barrel. */
	public float aiFireConeDeg = 6f;
	/**
	 * AI ships keep their shield up while an enemy has them within its weapons' reach, as long as flux is
	 * below this level (above it the shield only goes up for fire that's about to land, so hard flux can
	 * drain). User (2026-10-12): the AI "doesn't use its shields". Vanilla shields take 2-4 s to unfold,
	 * so raising one for a shot already on its way was always too late.
	 */
	public float aiShieldFlux = 0.6f;
	/** An AI ship with nothing in range drifts back once it's this far from its own fleet's centre. */
	public float aiCohesion = 2000f;
	/** AI ships stay within this height of the fight's mean altitude. */
	public float aiAltitudeBand = 700f;
	/** Strongest weave (sideways/vertical strafe, 0..1 of strafe speed) for a frigate; bigger hulls weave less. */
	public float aiWeave = 0.6f;
	/** Chance per plan that an agile ship (frigate/destroyer, faster than its target) makes gun runs. */
	public float aiPassChance = 0.5f;
	/** Chance per plan that a nose-gun ship slides round its target to a new angle. */
	public float aiFlankChance = 0.6f;
	/**
	 * How hard AI ships keep out of enemy guns' reach (0 = off, 1 = default): standing off at the edge of
	 * a target they outrange, sliding out of other enemies' reach, backing out to cool off at high flux,
	 * picking fighting spots and targets away from enemy crowds, no gun runs into a crowd.
	 */
	public float aiCaution = 1f;
	/**
	 * How readily AI ships take their shots (0 = off, 1 = default): guns turn to another enemy when a
	 * friendly blocks the shot or a missile isn't worth it, prefer enemies that can't fight back or whose
	 * shield won't stop the shot, fire harder at high flux when the shot lands on armor, ships pick
	 * targets that are hurt, exposed or already in front of their guns, and fighters dogfight enemy
	 * fighters instead of flashing past them.
	 */
	public float aiOpportunism = 1f;

	// ---- keys (LWJGL Keyboard names) ----
	public final Map<String, String> keys = new LinkedHashMap<>();

	public Tuning() {
		// Chosen in the sandbox (2026-10-11): capitals read as much bigger than vanilla's sprite ratio.
		hullScale.put("fighter", 0.8f);
		hullScale.put("frigate", 0.8f);
		hullScale.put("destroyer", 1.0f);
		hullScale.put("cruiser", 1.4f);
		hullScale.put("capital", 2.5f);
		hullScale.put("station", 5.0f); // user (2026-10-12): twice the first station scale (2.5)
		keys.put("throttleUp", "W");
		keys.put("throttleDown", "S");
		keys.put("allStop", "X");
		keys.put("strafeLeft", "A");
		keys.put("strafeRight", "D");
		keys.put("strafeUp", "SPACE");
		keys.put("strafeDown", "LSHIFT");
		keys.put("rollLeft", "Q");
		keys.put("rollRight", "E");
		keys.put("autoLevel", "L");
		keys.put("freeLook", "LMENU");
		keys.put("tactical", "T");
		keys.put("pause", "P");
		keys.put("prevHull", "LBRACKET");
		keys.put("nextHull", "RBRACKET");
		keys.put("reloadTuning", "K");
		keys.put("toggleOverlay", "O");
		keys.put("perfHud", "I");
		keys.put("target", "R");
		keys.put("resetTargets", "N");
		keys.put("incomingFire", "B");
		keys.put("toggleHud", "F10,BACKSLASH,G");
		keys.put("handBack", "F9");
		keys.put("vent", "V");
		keys.put("wings", "Z");
		keys.put("spectate", "J");
		keys.put("system", "F");
	}

	public static Tuning fromJson(JSONObject root) {
		Tuning t = new Tuning();
		if (root == null) return t;

		JSONObject f = root.optJSONObject("flight");
		if (f != null) {
			t.turnCurveRef = num(f, "turnCurveRef", t.turnCurveRef);
			t.turnCurveExp = num(f, "turnCurveExp", t.turnCurveExp);
			t.turnRateMult = num(f, "turnRateMult", t.turnRateMult);
			t.pitchRateMult = num(f, "pitchRateMult", t.pitchRateMult);
			t.yawRateMult = num(f, "yawRateMult", t.yawRateMult);
			t.rollRateMult = num(f, "rollRateMult", t.rollRateMult);
			t.rollRateMinDeg = num(f, "rollRateMinDeg", t.rollRateMinDeg);
			t.turnAccelMult = num(f, "turnAccelMult", t.turnAccelMult);
			t.speedMult = num(f, "speedMult", t.speedMult);
			t.accelMult = num(f, "accelMult", t.accelMult);
			t.decelMult = num(f, "decelMult", t.decelMult);
			t.strafeSpeedMult = num(f, "strafeSpeedMult", t.strafeSpeedMult);
			t.strafeAccelMult = num(f, "strafeAccelMult", t.strafeAccelMult);
			t.verticalMult = num(f, "verticalMult", t.verticalMult);
			t.reverseSpeedMult = num(f, "reverseSpeedMult", t.reverseSpeedMult);
			t.lateralDampMult = num(f, "lateralDampMult", t.lateralDampMult);
			t.throttleRate = num(f, "throttleRate", t.throttleRate);
			t.autoLevelDefault = f.optBoolean("autoLevelDefault", t.autoLevelDefault);
			t.autoLevelGain = num(f, "autoLevelGain", t.autoLevelGain);
			t.visualBankDeg = num(f, "visualBankDeg", t.visualBankDeg);
		}
		JSONObject m = root.optJSONObject("mouse");
		if (m != null) {
			t.mouseDeadzone = num(m, "deadzone", t.mouseDeadzone);
			t.mouseFullDeflection = num(m, "fullDeflection", t.mouseFullDeflection);
			t.mouseExpo = num(m, "expo", t.mouseExpo);
			t.invertPitch = m.optBoolean("invertPitch", t.invertPitch);
			t.hideGameCursor = m.optBoolean("hideGameCursor", t.hideGameCursor);
		}
		JSONObject c = root.optJSONObject("camera");
		if (c != null) {
			t.camFovDeg = num(c, "fovDeg", t.camFovDeg);
			t.camBackRadii = num(c, "backRadii", t.camBackRadii);
			t.camBackMin = num(c, "backMin", t.camBackMin);
			t.camUpRadii = num(c, "upRadii", t.camUpRadii);
			t.camSizeExp = num(c, "sizeExp", t.camSizeExp);
			t.camSizeRefRadius = num(c, "sizeRefRadius", t.camSizeRefRadius);
			t.camRotLag = num(c, "rotLag", t.camRotLag);
			t.camSpeedPullbackRadii = num(c, "speedPullbackRadii", t.camSpeedPullbackRadii);
			t.camFreeLookReturn = num(c, "freeLookReturn", t.camFreeLookReturn);
			t.camTacticalDist = num(c, "tacticalDist", t.camTacticalDist);
			t.camTacticalPitchDeg = num(c, "tacticalPitchDeg", t.camTacticalPitchDeg);
			t.camTransition = num(c, "transition", t.camTransition);
			t.camZoomMin = num(c, "zoomMin", t.camZoomMin);
			t.camZoomMax = num(c, "zoomMax", t.camZoomMax);
		}
		JSONObject r = root.optJSONObject("render");
		if (r != null) {
			t.msaaSamples = r.optInt("msaaSamples", t.msaaSamples);
			t.anisotropy = num(r, "anisotropy", t.anisotropy);
			t.dustCount = r.optInt("dustCount", t.dustCount);
			t.dustRange = num(r, "dustRange", t.dustRange);
			t.dustStreak = num(r, "dustStreak", t.dustStreak);
			t.dustBrightness = num(r, "dustBrightness", t.dustBrightness);
			t.starCount = r.optInt("starCount", t.starCount);
			t.nebulaStrength = num(r, "nebulaStrength", t.nebulaStrength);
			t.sunDir = vec(r, "sunDir", t.sunDir);
			t.sunColor = vec(r, "sunColor", t.sunColor);
			t.ambient = vec(r, "ambient", t.ambient);
			t.fillDir = vec(r, "fillDir", t.fillDir);
			t.fillColor = vec(r, "fillColor", t.fillColor);
			t.nebulaColorA = vec(r, "nebulaColorA", t.nebulaColorA);
			t.nebulaColorB = vec(r, "nebulaColorB", t.nebulaColorB);
			t.engineFlameScale = num(r, "engineFlameScale", t.engineFlameScale);
			t.shieldBrightness = num(r, "shieldBrightness", t.shieldBrightness);
		}
		JSONObject wp = root.optJSONObject("weapons");
		if (wp != null) {
			t.weaponRangeMult = num(wp, "rangeMult", t.weaponRangeMult);
			t.armorReductionMult = num(wp, "armorReductionMult", t.armorReductionMult);
			t.weaponConvergence = num(wp, "convergence", t.weaponConvergence);
			t.reticleRadiusPx = num(wp, "reticleRadiusPx", t.reticleRadiusPx);
			t.leadCircleScale = num(wp, "leadCircleScale", t.leadCircleScale);
			t.autoTargetDeg = num(wp, "autoTargetDeg", t.autoTargetDeg);
			t.radarRange = num(wp, "radarRange", t.radarRange);
			t.damageNumbers = wp.optBoolean("damageNumbers", t.damageNumbers);
		}
		JSONObject fx = root.optJSONObject("weaponFx");
		if (fx != null) {
			t.fxPaladinBlastDamage = num(fx, "paladinBlastDamage", t.fxPaladinBlastDamage);
			t.fxPaladinBlastRadius = num(fx, "paladinBlastRadius", t.fxPaladinBlastRadius);
			t.fxPaladinBlastCore = num(fx, "paladinBlastCore", t.fxPaladinBlastCore);
			t.fxSabotArcChance = num(fx, "sabotArcChance", t.fxSabotArcChance);
		}
		JSONObject fx2 = root.optJSONObject("weaponFx2");
		if (fx2 != null) {
			t.fx2RiftBeamInterval = num(fx2, "riftBeamInterval", t.fx2RiftBeamInterval);
			t.fx2DisruptorArcInterval = num(fx2, "disruptorArcInterval", t.fx2DisruptorArcInterval);
			t.fx2DisruptorArcDelay = num(fx2, "disruptorArcDelay", t.fx2DisruptorArcDelay);
			t.fx2GlareInterval = num(fx2, "glareInterval", t.fx2GlareInterval);
			t.fx2GlareChance = num(fx2, "glareChance", t.fx2GlareChance);
			t.fx2GlareMaxGap = num(fx2, "glareMaxGap", t.fx2GlareMaxGap);
			t.fx2GlareRadius = num(fx2, "glareRadius", t.fx2GlareRadius);
			t.fx2GlareCore = num(fx2, "glareCore", t.fx2GlareCore);
			t.fx2DevouringRefundChance = num(fx2, "devouringRefundChance", t.fx2DevouringRefundChance);
		}
		JSONObject sc = root.optJSONObject("scale");
		if (sc != null) {
			for (String cls : new ArrayList<>(t.hullScale.keySet())) t.hullScale.put(cls, num(sc, cls, t.hullScale.get(cls)));
		}
		JSONObject mesh = root.optJSONObject("mesh");
		if (mesh != null) {
			t.meshCellsAcross = mesh.optInt("cellsAcross", t.meshCellsAcross);
			t.meshAlphaThreshold = num(mesh, "alphaThreshold", t.meshAlphaThreshold);
			t.meshRoundRadiusFrac = num(mesh, "roundRadiusFrac", t.meshRoundRadiusFrac);
			t.meshTopHeight = num(mesh, "topHeight", t.meshTopHeight);
			t.meshBottomHeight = num(mesh, "bottomHeight", t.meshBottomHeight);
			t.meshKeel = num(mesh, "keel", t.meshKeel);
			t.meshDetailAmp = num(mesh, "detailAmp", t.meshDetailAmp);
			t.meshEdgeThickness = num(mesh, "edgeThickness", t.meshEdgeThickness);
			t.meshSdfBlur = num(mesh, "sdfBlur", t.meshSdfBlur);
			t.meshProfile = mesh.optString("profile", t.meshProfile);
			t.meshBevelFrac = num(mesh, "bevelFrac", t.meshBevelFrac);
			t.meshBaseHeight = num(mesh, "baseHeight", t.meshBaseHeight);
			t.meshTiers = mesh.optInt("tiers", t.meshTiers);
			t.meshTierInsetFrac = num(mesh, "tierInsetFrac", t.meshTierInsetFrac);
			t.meshTierHeight = num(mesh, "tierHeight", t.meshTierHeight);
			t.meshTierLumInfluence = num(mesh, "tierLumInfluence", t.meshTierLumInfluence);
			t.meshTierLumSteps = mesh.optInt("tierLumSteps", t.meshTierLumSteps);
			t.meshUnderHeight = num(mesh, "underHeight", t.meshUnderHeight);
			t.meshKeelHeight = num(mesh, "keelHeight", t.meshKeelHeight);
			t.meshWallFrac = num(mesh, "wallFrac", t.meshWallFrac);
			t.bumpStrength = num(mesh, "bumpStrength", t.bumpStrength);
		}
		JSONObject sb = root.optJSONObject("sandbox");
		if (sb != null) {
			JSONArray hulls = sb.optJSONArray("hulls");
			if (hulls != null && hulls.length() > 0) {
				t.sandboxHulls.clear();
				for (int i = 0; i < hulls.length(); i++) t.sandboxHulls.add(hulls.optString(i));
			}
			JSONArray refs = sb.optJSONArray("references");
			if (refs != null) {
				t.sandboxReferences.clear();
				for (int i = 0; i < refs.length(); i++) t.sandboxReferences.add(refs.optString(i));
			}
			t.sandboxLineupDepth = num(sb, "lineupDepth", t.sandboxLineupDepth);
			t.sandboxMovingTarget = sb.optString("movingTarget", t.sandboxMovingTarget);
		}
		JSONObject sh = root.optJSONObject("shields");
		if (sh != null) {
			t.omniShieldTurnDeg = num(sh, "omniTurnRateDeg", t.omniShieldTurnDeg);
			t.ventRateMult = num(sh, "ventRateMult", t.ventRateMult);
			t.overloadBaseTime = num(sh, "overloadBaseTime", t.overloadBaseTime);
			t.overloadMaxTime = num(sh, "overloadMaxTime", t.overloadMaxTime);
			t.aiFluxFireLimit = num(sh, "aiFluxFireLimit", t.aiFluxFireLimit);
		}
		JSONObject em = root.optJSONObject("emp");
		if (em != null) {
			t.empArcFrac = num(em, "arcFrac", t.empArcFrac);
			t.empArcMin = num(em, "arcMin", t.empArcMin);
			t.empArmorReduces = em.optBoolean("armorReduces", t.empArmorReduces);
			t.weaponRepairTime = num(em, "weaponRepairTime", t.weaponRepairTime);
			t.engineRepairTime = num(em, "engineRepairTime", t.engineRepairTime);
		}
		JSONObject sy = root.optJSONObject("systems");
		if (sy != null) readSystems(t, sy);
		JSONObject co = root.optJSONObject("collisions");
		if (co != null) {
			t.collisions = co.optBoolean("enabled", t.collisions);
			t.collisionRestitution = num(co, "restitution", t.collisionRestitution);
			t.collisionDamage = num(co, "damage", t.collisionDamage);
			t.collisionMinSpeed = num(co, "minSpeed", t.collisionMinSpeed);
		}
		JSONObject ph = root.optJSONObject("weight");
		if (ph != null) {
			t.impactScale = num(ph, "impactScale", t.impactScale);
			t.impactPerDamage = num(ph, "impactPerDamage", t.impactPerDamage);
			t.spinScale = num(ph, "spinScale", t.spinScale);
			t.recoilScale = num(ph, "recoilScale", t.recoilScale);
			t.blastScale = num(ph, "blastScale", t.blastScale);
			t.cameraShake = num(ph, "cameraShake", t.cameraShake);
		}
		JSONObject so = root.optJSONObject("sound");
		if (so != null) {
			t.sound = so.optBoolean("enabled", t.sound);
			t.soundVolume = num(so, "volume", t.soundVolume);
			t.soundDistanceScale = num(so, "distanceScale", t.soundDistanceScale);
			t.soundRange = num(so, "range", t.soundRange);
		}
		JSONObject hd = root.optJSONObject("hud");
		if (hd != null) {
			t.minimapSize = num(hd, "minimapSize", t.minimapSize);
			t.perfDefault = hd.optBoolean("perfDefault", t.perfDefault);
		}
		JSONObject bt = root.optJSONObject("battle");
		if (bt != null) {
			t.battleEnabled = bt.optBoolean("enabled", t.battleEnabled);
			t.battleMapScale = num(bt, "mapScale", t.battleMapScale);
			t.battleDeployDistance = num(bt, "deployDistance", t.battleDeployDistance);
			t.battleDeploySpread = num(bt, "deploySpread", t.battleDeploySpread);
			t.battleDeployAltitude = num(bt, "deployAltitude", t.battleDeployAltitude);
			t.battleTransferDelay = num(bt, "transferDelay", t.battleTransferDelay);
			t.battleStations = bt.optBoolean("stations", t.battleStations);
			t.stationSpinDeg = num(bt, "stationSpinDeg", t.stationSpinDeg);
			t.battleCaptureRadius = num(bt, "captureRadius", t.battleCaptureRadius);
			t.battleCaptureFallback = num(bt, "captureFallback", t.battleCaptureFallback);
			t.battleHoldRadius = num(bt, "holdRadius", t.battleHoldRadius);
			t.battleAvoidRadius = num(bt, "avoidRadius", t.battleAvoidRadius);
		}
		JSONObject ai = root.optJSONObject("ai");
		if (ai != null) {
			t.aiRangeFrac = num(ai, "rangeFrac", t.aiRangeFrac);
			t.aiStrafe = num(ai, "strafe", t.aiStrafe);
			t.aiRetargetTime = num(ai, "retargetTime", t.aiRetargetTime);
			t.aiFireConeDeg = num(ai, "fireConeDeg", t.aiFireConeDeg);
			t.aiShieldFlux = num(ai, "shieldFlux", t.aiShieldFlux);
			t.aiCohesion = num(ai, "cohesion", t.aiCohesion);
			t.aiAltitudeBand = num(ai, "altitudeBand", t.aiAltitudeBand);
			t.aiWeave = num(ai, "weave", t.aiWeave);
			t.aiPassChance = num(ai, "passChance", t.aiPassChance);
			t.aiFlankChance = num(ai, "flankChance", t.aiFlankChance);
			t.aiCaution = num(ai, "caution", t.aiCaution);
			t.aiOpportunism = num(ai, "opportunism", t.aiOpportunism);
		}
		JSONObject k = root.optJSONObject("keys");
		if (k != null) {
			for (String action : new ArrayList<>(t.keys.keySet())) {
				t.keys.put(action, k.optString(action, t.keys.get(action)));
			}
		}
		return t;
	}

	/** Mesh parameters change the generated geometry; used as part of the model cache key. */
	public String meshSignature() {
		return meshProfile + "|" + meshBevelFrac + "|" + meshBaseHeight + "|" + meshTiers + "|" + meshTierInsetFrac + "|"
				+ meshTierHeight + "|" + meshTierLumInfluence + "|" + meshTierLumSteps + "|" + meshUnderHeight + "|" + meshKeelHeight + "|" + meshWallFrac + "|"
				+ meshCellsAcross + "|" + meshAlphaThreshold + "|" + meshRoundRadiusFrac + "|" + meshTopHeight + "|"
				+ meshBottomHeight + "|" + meshKeel + "|" + meshDetailAmp + "|" + meshEdgeThickness + "|" + meshSdfBlur;
	}

	/** The "systems" section, over t's values (also another mod's per-system numbers, see withSystems). */
	private static void readSystems(Tuning t, JSONObject sy) {
		t.sysBurnSpeed = num(sy, "burnDriveSpeed", t.sysBurnSpeed);
		t.sysBurnAccelMult = num(sy, "burnDriveAccelMult", t.sysBurnAccelMult);
		t.sysBurnTurnMult = num(sy, "burnDriveTurnMult", t.sysBurnTurnMult);
		t.sysJetsSpeed = num(sy, "jetsSpeed", t.sysJetsSpeed);
		t.sysJetsAccelMult = num(sy, "jetsAccelMult", t.sysJetsAccelMult);
		t.sysJetsTurn = num(sy, "jetsTurn", t.sysJetsTurn);
		t.sysPlasmaSpeed = num(sy, "plasmaJetsSpeed", t.sysPlasmaSpeed);
		t.sysMicroburnSpeed = num(sy, "microburnSpeed", t.sysMicroburnSpeed);
		t.sysDisplacerDistance = num(sy, "phaseSkimmerDistance", t.sysDisplacerDistance);
		t.sysAmmoFeedRof = num(sy, "ammoFeedRateOfFire", t.sysAmmoFeedRof);
		t.sysAmmoFeedFlux = num(sy, "ammoFeedFluxPerShot", t.sysAmmoFeedFlux);
		t.sysEnergyFocusDamage = num(sy, "highEnergyFocusDamage", t.sysEnergyFocusDamage);
		t.sysFortressShieldTaken = num(sy, "fortressShieldDamageTaken", t.sysFortressShieldTaken);
		t.sysDamperTaken = num(sy, "damperDamageTaken", t.sysDamperTaken);
		t.sysTemporalTimeMult = num(sy, "temporalShellTimeMult", t.sysTemporalTimeMult);
		t.sysPhaseTimeMult = num(sy, "phaseTimeMult", t.sysPhaseTimeMult);
		t.sysEmpInterval = num(sy, "empEmitterInterval", t.sysEmpInterval);
		t.sysEmpTargets = sy.optInt("empEmitterTargets", t.sysEmpTargets);
		t.sysFlareCount = sy.optInt("flareCount", t.sysFlareCount);
		t.sysFlareLife = num(sy, "flareLife", t.sysFlareLife);
		t.sysFlareLure = num(sy, "flareLureRange", t.sysFlareLure);
		t.sysFlareLureChance = num(sy, "flareLureChance", t.sysFlareLureChance);
		t.sysEntropyRange = num(sy, "entropyAmplifierRange", t.sysEntropyRange);
		t.sysMineWeapon = sy.optString("mineStrikeWeapon", t.sysMineWeapon);
		t.sysMineArmTime = num(sy, "mineArmTime", t.sysMineArmTime);
		t.sysMineWindup = num(sy, "mineWindup", t.sysMineWindup);
		t.sysDroneStrikeWeapon = sy.optString("droneStrikeWeapon", t.sysDroneStrikeWeapon);
	}

	/**
	 * A copy of this tuning with one ship system's own numbers (keys of the "systems" section), for a
	 * system another mod mapped in its data/config/s3d/systems.csv. Everything else is shared.
	 */
	public Tuning withSystems(JSONObject overrides) {
		Tuning c;
		try {
			c = (Tuning) clone();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException(e);
		}
		readSystems(c, overrides);
		return c;
	}

	/** The keys the "systems" section reads (to tell a modder about a misspelt one). */
	public static java.util.Set<String> systemKeys() {
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		readSystems(new Tuning(), new JSONObject() {
			@Override
			public double optDouble(String key, double def) {
				seen.add(key);
				return def;
			}

			@Override
			public int optInt(String key, int def) {
				seen.add(key);
				return def;
			}

			@Override
			public String optString(String key, String def) {
				seen.add(key);
				return def;
			}
		});
		return seen;
	}

	private static float num(JSONObject o, String key, float def) {
		return (float) o.optDouble(key, def);
	}

	private static float[] vec(JSONObject o, String key, float[] def) {
		JSONArray a = o.optJSONArray(key);
		if (a == null || a.length() < 3) return def;
		return new float[] {(float) a.optDouble(0, def[0]), (float) a.optDouble(1, def[1]), (float) a.optDouble(2, def[2])};
	}
}
