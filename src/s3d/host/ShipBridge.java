package s3d.host;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EngineSlotAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BreachOnHitEffect;
import com.fs.starfarer.api.impl.combat.DisintegratorEffect;
import com.fs.starfarer.api.impl.combat.threat.EnergyLashSystemScript;
import com.fs.starfarer.api.impl.combat.threat.FragmentVolleySystemScript;
import com.fs.starfarer.api.impl.combat.threat.IncursionModeSystemScript;
import com.fs.starfarer.api.impl.combat.threat.ExtractionProtocolSystemScript;
import com.fs.starfarer.api.impl.combat.threat.ConstructionSwarmSystemScript;
import com.fs.starfarer.api.impl.combat.dweller.ConvulsiveLungeSystemScript;
import com.fs.starfarer.api.impl.combat.dweller.DarkenedGazeSystemScript;
import com.fs.starfarer.api.impl.combat.dweller.TenebrousExpulsionSystemScript;
import com.fs.starfarer.api.impl.combat.dweller.DwellerCombatStrategyAI;
import com.fs.starfarer.api.impl.combat.MoteControlScript;
import s3d.core.ExoticSystems;
import com.fs.starfarer.api.impl.combat.AcausalDisruptorStats;
import com.fs.starfarer.api.impl.combat.EntropyAmplifierStats;
import com.fs.starfarer.api.impl.combat.GravitonBeamEffect;
import com.fs.starfarer.api.impl.combat.LidarArrayStats;
import com.fs.starfarer.api.impl.combat.MineStrikeStationStats;
import com.fs.starfarer.api.impl.combat.MineStrikeStats;
import com.fs.starfarer.api.impl.combat.MiningBlasterOnHitEffect;
import com.fs.starfarer.api.impl.combat.PhaseCloakStats;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.combat.TargetingFeedStats;
import com.fs.starfarer.api.impl.combat.ShockRepeaterOnFireEffect;
import com.fs.starfarer.api.impl.combat.SquallOnFireEffect;

import s3d.core.ArmorShell;
import s3d.core.DemSpec;
import s3d.core.ShieldFlux;
import s3d.core.ShipSystem3D;
import s3d.core.SystemLook;
import s3d.core.EngineMount;
import s3d.core.HullVolume;
import s3d.core.ModData;
import s3d.core.Tuning;
import s3d.core.WeaponGroup3D;
import s3d.core.WeaponMount;
import s3d.core.WeaponSpec3D;
import s3d.math.Vec3;
import s3d.core.FlightParams;
import s3d.core.Ship3D;
import s3d.render.ShipModel;
import com.fs.starfarer.api.loading.ShotBehaviorSpecAPI;
import org.json.JSONArray;
import org.json.JSONObject;
import s3d.render.SpriteImage;

/**
 * Reads everything the 3D side needs from a live vanilla ship: movement stats after hullmods,
 * skills and CR (MutableShipStatsAPI), sprite + rotation centre, and engine slots. Nothing is
 * copied out of the game's files; sprites are read through the game's own resource loader.
 */
final class ShipBridge {
	private static final Logger log = Global.getLogger(ShipBridge.class);
	private static final Map<String, SpriteImage> SPRITES = new HashMap<>();
	private static final java.util.Set<String> LOGGED_DRIVE_KEYS = new java.util.HashSet<>();
	private static final java.util.Set<String> LOGGED_SPEED_KEYS = new java.util.HashSet<>();
	private static final java.util.Set<String> LOGGED_SHIELD_RAW = new java.util.HashSet<>();
	private static final Map<String, String[]> FIRE_SOUNDS = new HashMap<>();

	/**
	 * A weapon's fire sounds {fireSoundOne, fireSoundTwo} from its data file, read through the game's
	 * loader (the API has no getter for them); cached per weapon, nulls if unavailable.
	 */
	static String[] fireSounds(String weaponId) {
		return FIRE_SOUNDS.computeIfAbsent(weaponId, id -> {
			try {
				JSONObject j = Global.getSettings().loadJSON("data/weapons/" + id + ".wpn");
				String one = j.optString("fireSoundOne", null), two = j.optString("fireSoundTwo", null);
				return new String[] {one == null || one.isEmpty() ? null : one, two == null || two.isEmpty() ? null : two};
			} catch (Throwable t) {
				log.info("S3D: no fire sound for weapon " + id + " (" + t.getClass().getSimpleName() + ")");
				return new String[2];
			}
		});
	}

	/** Fighters are hit like vanilla's outline: a slab this fraction of their radius above and below. */
	static final float FIGHTER_HIT_HALF_HEIGHT = 0.35f;
	/** Fighters relaunch constantly; their full description is logged once per hull. */
	private static final java.util.Set<String> LOGGED_FIGHTERS = new java.util.HashSet<>();

	private ShipBridge() {}

	static Ship3D toShip3D(ShipAPI ship, String id, Tuning t) throws Exception {
		Ship3D s = new Ship3D(id);
		s.hullName = ship.getHullSpec().getHullNameWithDashClass();
		s.name = ship.getName() != null && !ship.getName().isEmpty() ? ship.getName() : s.hullName;
		s.variantName = variantName(ship);
		s.owner = ship.getOwner();
		s.baseRadius = ship.getCollisionRadius();
		s.mass = Math.max(1f, ship.getMass());
		s.radius = s.baseRadius;
		s.sizeClass = sizeClass(ship);
		s.hostRef = ship;

		s.params = readFlightParams(ship);
		s.baseParams = new FlightParams(s.params.maxSpeed, s.params.accel, s.params.decel, s.params.maxTurnRateDeg, s.params.turnAccelDeg);

		String spritePath = ship.getHullSpec().getSpriteName();
		SpriteImage img = loadSprite(spritePath);
		SpriteAPI spr = ship.getSpriteAPI();
		float sx = spr.getWidth() > 0 ? img.w / spr.getWidth() : 1f;
		float sy = spr.getHeight() > 0 ? img.h / spr.getHeight() : 1f;
		float cx = spr.getCenterX() > 0 ? spr.getCenterX() * sx : img.w * 0.5f;
		// SpriteAPI's centre is measured from the bottom-left; the mesh builder wants it from the top.
		float cyTop = spr.getCenterY() > 0 ? img.h - spr.getCenterY() * sy : img.h * 0.5f;
		ShipModel.Source hullSource = new ShipModel.Source(spritePath, img, cx, cyTop, HullMeshes.find(ship.getHullSpec(), img));
		s.modelSource = hullSource;

		// Durability (live values: hullmods and skills included)
		s.hullMax = ship.getMaxHitpoints();
		s.hull = s.hullMax;
		ArmorGridAPI grid = ship.getArmorGrid();
		s.armor = new ArmorShell(grid != null ? grid.getArmorRating() : 0f);
		MutableShipStatsAPI st = ship.getMutableStats();
		s.armorTakenMult = st.getArmorDamageTakenMult().getModifiedValue();
		s.hullTakenMult = st.getHullDamageTakenMult().getModifiedValue();
		s.volume = ShipModel.geometry(hullSource, t).volume;
		if (ship.isFighter() && s.volume != null) s.volume = s.volume.thickened(s.baseRadius * FIGHTER_HIT_HALF_HEIGHT);
		s.flux = readFlux(ship, t);
		s.empTakenMult = st.getEmpDamageTakenMult().getModifiedValue();
		s.system = readSystem(ship, ship.getSystem(), false, s.volume, t);
		if (s.system != null && s.system.kind == ShipSystem3D.Kind.DARKENED_GAZE) gazeBeams(s, ship, t);
		s.dp = ship.getHullSpec() != null ? ship.getHullSpec().getSuppliesToRecover() : 0f;
		ShieldAPI defence = ship.getShield();
		if (ship.getPhaseCloak() != null && (defence == null || defence.getType() == ShieldAPI.ShieldType.PHASE || defence.getType() == ShieldAPI.ShieldType.NONE)) {
			s.cloak = readSystem(ship, ship.getPhaseCloak(), true, s.volume, t);
		}
		s.weaponRepairTime = t.weaponRepairTime * st.getCombatWeaponRepairTimeMult().getModifiedValue();
		s.engineRepairTime = t.engineRepairTime * st.getCombatEngineRepairTimeMult().getModifiedValue();

		StringBuilder wlog = new StringBuilder();
		Map<WeaponAPI, WeaponMount> byWeapon = new HashMap<>();
		for (WeaponAPI w : ship.getAllWeapons()) {
			WeaponMount m = mount(w, s.volume, ship, t);
			if (m == null) continue;
			m.maxHp = m.hp = Math.max(1f, w.getMaxHealth());
			s.weapons.add(m);
			byWeapon.put(w, m);
			WeaponSpec3D ws = m.spec;
			wlog.append(String.format("%n    %s @%s %s arc %.0f: %s", ws.name, m.slotId, m.hardpoint ? "hardpoint" : "turret",
					Math.toDegrees(m.halfArcRad * 2f), describe(ws)));
		}

		for (ShipEngineAPI e : ship.getEngineController().getShipEngines()) {
			EngineSlotAPI slot = e.getEngineSlot();
			Vector2f loc = slot.computePosition(new Vector2f(0f, 0f), 0f); // ship space: x forward, y left
			Color c = slot.getColor() != null ? slot.getColor() : e.getEngineColor();
			if (c == null) c = new Color(255, 150, 80);
			EngineMount em = EngineMount.fromShipSpace(loc.x, loc.y, slot.getAngle(), slot.getLength(), slot.getWidth(),
					c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
			// System-activated thrusters only light while a movement system runs (looks only: no thrust, no EMP).
			if (e.isSystemActivated()) s.systemEngines.add(em);
			else s.engines.add(em);
		}
		try {
			Color vc = ship.getVentCoreColor(), vf = ship.getVentFringeColor(), oc = ship.getOverloadColor();
			if (vc != null) s.ventCore = new float[] {vc.getRed() / 255f, vc.getGreen() / 255f, vc.getBlue() / 255f};
			if (vf != null) s.ventFringe = new float[] {vf.getRed() / 255f, vf.getGreen() / 255f, vf.getBlue() / 255f};
			if (oc != null) s.overloadColor = new float[] {oc.getRed() / 255f, oc.getGreen() / 255f, oc.getBlue() / 255f};
		} catch (Throwable ignored) {
			// older API: keep the defaults
		}
		for (WeaponAPI w : ship.getAllWeapons()) {
			if (!w.isDecorative() || w.getSlot() == null || w.getSpec() == null || !w.getSpec().hasTag("lidar")) continue;
			try {
				WeaponSlotAPI slot = w.getSlot();
				Vector2f loc = slot.getLocation();
				float x = -loc.y, z = -loc.x;
				float y = s.volume != null ? s.volume.topAt(x, z) + 1f : 0f;
				double a = Math.toRadians(slot.getAngle());
				s.lidarDishes.add(new WeaponMount(slot.getId(), weaponSpec(w, ship, t), new Vec3(x, y, z),
						new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)), slot.getArc(), slot.isHardpoint()));
			} catch (Throwable e) {
				log.warn("S3D: lidar dish " + w.getId() + " on " + ship.getName() + " left out (" + e + ")");
			}
		}

		float engineMax = 0f;
		for (ShipEngineAPI e : ship.getEngineController().getShipEngines()) {
			if (!e.isSystemActivated()) engineMax = Math.max(engineMax, e.getMaxHitpoints());
		}
		s.initComponents(engineMax > 0f ? engineMax : 100f);

		// Vanilla weapon groups: trigger fires the selected one, autofire groups engage on their own.
		WeaponGroupAPI selected = ship.getSelectedGroupAPI();
		for (WeaponGroupAPI g : ship.getWeaponGroupsCopy()) {
			WeaponGroup3D g3 = new WeaponGroup3D();
			g3.autofire = g.isAutofiring();
			g3.alternating = g.getType() == WeaponGroupType.ALTERNATING;
			for (WeaponAPI w : g.getWeaponsCopy()) {
				WeaponMount m = byWeapon.get(w);
				if (m != null) g3.mounts.add(m);
			}
			if (g3.mounts.isEmpty()) continue;
			if (g == selected) s.selectedGroup = s.groups.size();
			s.groups.add(g3);
			wlog.append(String.format("%n    group %d: %s%s%s", s.groups.size(), g3.label(), g3.autofire ? " [autofire]" : "",
					g3.alternating ? " [alternating]" : " [linked]"));
		}
		if (selected == null || s.groups.isEmpty() || !s.groups.get(s.selectedGroup).hasWorkingWeapon()) {
			// Default to the first manual group that can actually fire.
			for (int i = 0; i < s.groups.size(); i++) {
				if (!s.groups.get(i).autofire && s.groups.get(i).hasWorkingWeapon()) {
					s.selectedGroup = i;
					break;
				}
			}
		}

		wlog.append(ExoticBridge.ship(s, ship)); // Threat fragment swarm, Dweller shroud

		// Carriers: fighters land on the first launch bay (else the middle of the hull's top).
		s.landingLocal.set(0f, s.volume != null ? s.volume.topAt(0f, 0f) + 2f : 0f, 0f);
		java.util.List<com.fs.starfarer.api.combat.FighterLaunchBayAPI> bays = ship.getLaunchBaysCopy();
		s.hasBays = bays != null && !bays.isEmpty();
		if (bays != null && !bays.isEmpty() && bays.get(0).getWeaponSlot() != null) launchPoint(s, bays.get(0).getWeaponSlot(), s.landingLocal);

		if (ship.isFighter() && !LOGGED_FIGHTERS.add(ship.getHullSpec().getHullId())) return s;
		log.info(String.format("S3D: mirrored %s [%s %s] owner=%d radius=%.0f sprite=%s %dx%d centre=(%.1f, %.1f from top) engines=%d travelDrive=%s | %s",
				s.name, ship.getHullSpec().getHullId(), s.sizeClass, s.owner, s.baseRadius, spritePath, img.w, img.h, cx, cyTop,
				s.engines.size(), travelDriveActive(ship), s.params)
				+ String.format("%n  hull %.0f, armor %.0f, %s%s%s, weapons:", s.hullMax, s.armor.rating, describe(s.flux),
						describe("system", s.system), describe("phase cloak", s.cloak)) + wlog);
		return s;
	}

	/**
	 * Flux and shield from the live ship (hullmods, skills and CR included where vanilla applies
	 * them to stats). Shield flux per damage = hull shield efficiency x shield absorption and
	 * shield damage-taken multipliers; upkeep = the hull's upkeep fraction x base dissipation x
	 * upkeep multiplier (vanilla data stores upkeep as a fraction).
	 */
	static ShieldFlux readFlux(ShipAPI ship, Tuning t) {
		ShieldFlux f = new ShieldFlux();
		MutableShipStatsAPI st = ship.getMutableStats();
		FluxTrackerAPI ft = ship.getFluxTracker();
		f.maxFlux = ft != null ? ft.getMaxFlux() : st.getFluxCapacity().getModifiedValue();
		f.dissipation = st.getFluxDissipation().getModifiedValue();
		if (ft != null) {
			f.flux = Math.min(f.maxFlux, ft.getCurrFlux());
			f.hardFlux = Math.min(f.flux, ft.getHardFlux());
		}
		f.hardDissipationFrac = st.getHardFluxDissipationFraction().getModifiedValue();
		f.ventRateMult = st.getVentRateMult().getModifiedValue();
		f.overloadTimeMult = st.getOverloadTimeMod().computeEffective(1f);
		f.zeroFluxBoost = st.getZeroFluxSpeedBoost().getModifiedValue();
		f.zeroFluxMinLevel = st.getZeroFluxMinimumFluxLevel().getModifiedValue();

		ShieldAPI sh = ship.getShield();
		if (sh == null || sh.getType() == null) return f;
		switch (sh.getType()) {
			case FRONT: f.type = ShieldFlux.Type.FRONT; break;
			case OMNI: f.type = ShieldFlux.Type.OMNI; break;
			default: return f; // NONE, or PHASE (phase cloaks aren't in 3D yet)
		}
		f.arcDeg = sh.getArc();
		f.halfAngleMax = ShieldFlux.coverageHalfAngle(f.arcDeg);
		f.radius = sh.getRadius();
		// Shield centre relative to the ship, into the 3D local frame (x = starboard, z = aft).
		Vector2f c = sh.getLocation(), p = ship.getLocation();
		if (c != null) {
			double a = Math.toRadians(ship.getFacing());
			float rx = c.x - p.x, ry = c.y - p.y;
			float fwd = (float) (rx * Math.cos(a) + ry * Math.sin(a)), left = (float) (-rx * Math.sin(a) + ry * Math.cos(a));
			f.centre.set(-left, 0f, -fwd);
		}
		ShipHullSpecAPI.ShieldSpecAPI spec = ship.getHullSpec().getShieldSpec();
		float base = spec != null && spec.getFluxPerDamageAbsorbed() > 0f ? spec.getFluxPerDamageAbsorbed() : sh.getFluxPerPointOfDamage();
		f.fluxPerDamage = base * st.getShieldAbsorptionMult().getModifiedValue() * st.getShieldDamageTakenMult().getModifiedValue();
		float upkeep = sh.getUpkeep();
		f.upkeep = upkeep <= 2f ? upkeep * ship.getHullSpec().getFluxDissipation() * st.getShieldUpkeepMult().getModifiedValue() : upkeep;
		f.unfoldTime = Math.max(0.1f, Math.min(3f, sh.getUnfoldTime()));
		f.turnRate = (float) Math.toRadians(t.omniShieldTurnDeg * st.getShieldTurnRateMult().getModifiedValue());
		if (sh.getInnerColor() != null) f.innerColor = rgb(sh.getInnerColor());
		if (sh.getRingColor() != null) f.ringColor = rgb(sh.getRingColor());
		if (LOGGED_SHIELD_RAW.add(ship.getHullSpec().getHullId())) {
			log.info(String.format("S3D: shield raw values for %s: getUpkeep %.3f, getFluxPerPointOfDamage %.3f, spec efficiency %.3f, unfold %.2f s",
					ship.getHullSpec().getHullId(), upkeep, sh.getFluxPerPointOfDamage(), base, sh.getUnfoldTime()));
		}
		return f;
	}

	/**
	 * A ship system (or phase cloak) for 3D: timing, charges, flux and what it blocks, from the live
	 * vanilla system on the puppet (hullmod/skill changes included); the effect is ShipSystem3D's.
	 */
	static ShipSystem3D readSystem(ShipAPI ship, com.fs.starfarer.api.combat.ShipSystemAPI sys, boolean phase, HullVolume volume, Tuning t) {
		if (sys == null) return null;
		com.fs.starfarer.api.combat.ShipSystemSpecAPI spec0 = sys.getSpecAPI();
		JSONObject json = spec0 != null ? spec0.getSpecJson() : null;
		// The right-click slot isn't always a cloak: the Invictus has Canister Flak there, the Vanguard a Damper.
		boolean cloak = phase && (spec0 == null || spec0.isPhaseCloak() || json != null && "PHASE_CLOAK".equals(json.optString("type", null)));
		// A mod's data/config/s3d/systems.csv row wins: the kind it plays as, and its own numbers.
		ModData.SystemRow row = ModDataLoader.data().system(sys.getId());
		ShipSystem3D.Kind kind = row != null ? (row.ignored() ? ShipSystem3D.Kind.UNSUPPORTED : row.kind)
				: ShipSystem3D.kindFor(sys.getId(), cloak, json != null ? json.optString("type", null) : null,
						json != null ? json.optString("aiType", null) : null);
		ShipSystem3D s = new ShipSystem3D(sys.getId(), sys.getDisplayName(), kind);
		if (row != null) {
			s.tuning = row.tuningFor(t);
			if (s.tuning != null) t = s.tuning;
		}
		s.chargeUp = Math.max(0f, sys.getChargeUpDur());
		s.active = Math.max(0f, sys.getChargeActiveDur());
		s.chargeDown = Math.max(0f, sys.getChargeDownDur());
		s.cooldown = Math.max(0f, sys.getCooldown());
		int maxAmmo = sys.getMaxAmmo();
		s.maxAmmo = maxAmmo > 0 && maxAmmo < 1000 ? maxAmmo : 0; // vanilla reports "unlimited" as a huge number
		s.ammoPerSecond = Math.max(0f, sys.getAmmoPerSecond());
		s.ammo = s.maxAmmo;
		s.fluxPerUse = Math.max(0f, sys.getFluxPerUse());
		s.fluxPerSecond = Math.max(0f, sys.getFluxPerSecond());
		com.fs.starfarer.api.combat.ShipSystemSpecAPI spec = sys.getSpecAPI();
		if (spec != null) {
			s.toggle = spec.isToggle();
			s.hardFlux = spec.generatesHardFlux();
			s.firingAllowed = spec.isFiringAllowed();
			s.turningAllowed = spec.isTurningAllowed();
			s.shieldAllowed = spec.isShieldAllowed();
			s.dissipationAllowed = spec.isDissipationAllowed();
			s.hardDissipationAllowed = spec.isHardDissipationAllowed();
			s.alwaysAccelerate = spec.isAlwaysAccelerate();
			s.range = spec.getRange(ship.getMutableStats());
			s.useSound = spec.getUseSound();
			s.loopSound = spec.getLoopSound();
			s.offSound = spec.getDeactivateSound();
			s.damage = spec.getDamage();
			s.empDamage = spec.getEmpDamage();
		}
		if (cloak) s.toggle = true;
		systemSpecifics(s, ship, spec, json, volume, t);
		lookFromData(s.look, json);
		return s;
	}

	/**
	 * The system's own data file wins over the kind's default look wherever it says something
	 * (jitter, weapon glow, engine glow, shield and effect colours), so another mod's systems keep
	 * their colours. Script-made looks (temporal shell, entropy, ...) keep the defaults.
	 */
	static void lookFromData(SystemLook l, JSONObject j) {
		if (j == null) return;
		l.jitter = color(j, "jitterColor", l.jitter);
		l.jitterCopies = j.optInt("jitterCopies", l.jitterCopies);
		l.jitterMin = (float) j.optDouble("jitterMinRange", l.jitterMin);
		l.jitterRange = (float) j.optDouble("jitterRange", l.jitterRange);
		l.jitterRadiusFraction = (float) j.optDouble("jitterRangeRadiusFraction", l.jitterRadiusFraction);
		l.jitterUnder = color(j, "jitterUnderColor", l.jitterUnder);
		l.jitterUnderCopies = j.optInt("jitterUnderCopies", l.jitterUnderCopies);
		l.jitterUnderMin = (float) j.optDouble("jitterUnderMinRange", l.jitterUnderMin);
		l.jitterUnderRange = (float) j.optDouble("jitterUnderRange", l.jitterUnderRange);
		l.jitterUnderRadiusFraction = (float) j.optDouble("jitterUnderRangeRadiusFraction", l.jitterUnderRadiusFraction);
		l.weaponGlow = color(j, "weaponGlowColor", l.weaponGlow);
		JSONArray types = j.optJSONArray("weaponTypes");
		if (types != null) {
			l.weaponTypes.clear();
			for (int i = 0; i < types.length(); i++) l.weaponTypes.add(types.optString(i).toUpperCase(java.util.Locale.ROOT));
		}
		l.engineColor = color(j, "engineGlowColor", l.engineColor);
		l.engineLength = (float) j.optDouble("engineGlowLengthMult", l.engineLength);
		l.engineWidth = (float) j.optDouble("engineGlowWidthMult", l.engineWidth);
		l.engineGlow = (float) j.optDouble("engineGlowGlowMult", l.engineGlow);
		if ("ENGINE_MOD".equals(j.optString("type", null))) l.systemEngines = true;
		l.shieldRing = color(j, "shieldRingColor", l.shieldRing);
		l.shieldInner = color(j, "shieldInnerColor", l.shieldInner);
		l.effect1 = color(j, "effectColor1", l.effect1);
		l.effect2 = color(j, "effectColor2", l.effect2);
	}

	/** An [r, g, b, a] colour (0-255) from the data file as 0..1, or the fallback if it isn't there. */
	private static float[] color(JSONObject j, String key, float[] fallback) {
		JSONArray a = j.optJSONArray(key);
		if (a == null || a.length() < 3) return fallback;
		return new float[] {(float) a.optDouble(0) / 255f, (float) a.optDouble(1) / 255f, (float) a.optDouble(2) / 255f,
				a.length() > 3 ? (float) a.optDouble(3) / 255f : 1f};
	}

	/**
	 * What each rebuilt system needs beyond timing: numbers from the vanilla script's public constants
	 * where it has them (else the tuning file), the weapon it fires or spawns, its launch slots.
	 */
	private static void systemSpecifics(ShipSystem3D s, ShipAPI ship, com.fs.starfarer.api.combat.ShipSystemSpecAPI spec, JSONObject json,
										HullVolume volume, Tuning t) {
		MutableShipStatsAPI st = ship.getMutableStats();
		switch (s.kind) {
			case PHASE_CLOAK: {
				// Vanilla's PhaseCloakStats: phase coil stress (top speed falls with hard flux) and the skills /
				// hullmods that change it (Adaptive Phase Coils) or speed up the phased ship (Phase Mastery,
				// Phase Corps). Cloaks run by another script don't get the coil stress.
				String script = json != null ? json.optString("statsScript", "") : "";
				if (PhaseCloakStats.FLUX_LEVEL_AFFECTS_SPEED && (script.isEmpty() || PhaseCloakStats.class.getName().equals(script))) {
					s.coilMinSpeed = PhaseCloakStats.MIN_SPEED_MULT;
					StatBonus level = st.getDynamic().getMod(Stats.PHASE_CLOAK_FLUX_LEVEL_FOR_MIN_SPEED_MOD);
					float base = PhaseCloakStats.BASE_FLUX_LEVEL_FOR_MIN_SPEED;
					s.coilStressLevel = Math.max(0f, level != null ? level.computeEffective(base) : base);
				}
				StatBonus speed = st.getDynamic().getMod(Stats.PHASE_CLOAK_SPEED_MOD), accel = st.getDynamic().getMod(Stats.PHASE_CLOAK_ACCEL_MOD);
				if (speed != null) {
					s.phaseSpeedPercent = speed.computeEffective(0f);
					s.phaseSpeedMult = speed.getMult();
				}
				if (accel != null) {
					s.phaseAccelPercent = accel.computeEffective(0f);
					s.phaseAccelMult = accel.getMult();
				}
				s.note = String.format("%s upkeep, hard flux %s while phased", s.hardFlux ? "hard" : "soft", s.hardDissipationAllowed ? "dissipates" : "stays")
						+ (s.coilStressLevel >= 0f ? String.format(", top speed falls to %.0f%% at %.0f%% hard flux", s.coilMinSpeed * 100f, s.coilStressLevel * 100f) : "")
						+ (s.phaseSpeedPercent != 0f || s.phaseSpeedMult != 1f ? String.format(", phased top speed %+.0f%% x%.2f", s.phaseSpeedPercent, s.phaseSpeedMult) : "");
				break;
			}
			case MISSILE_AUTOFORGE:
				s.reloadBelow = json != null ? (float) json.optDouble("minFractionToReload", 1) : 1f;
				s.reloadBaseOnly = json != null && json.optBoolean("reloadBaseAmmoAmountOnly", false);
				s.note = String.format("reloads missile racks %s once one is below %.0f%%", s.reloadBaseOnly ? "by their base ammo" : "to full", s.reloadBelow * 100f);
				break;
			case ENTROPY_AMPLIFIER:
				s.effectMult = EntropyAmplifierStats.DAM_MULT;
				s.range = st.getSystemRangeBonus().computeEffective(t.sysEntropyRange);
				s.note = String.format("target within %.0f takes x%.2f damage", s.range, s.effectMult);
				break;
			case PHASE_TELEPORTER:
				if (s.range <= 0f) s.range = json != null ? (float) json.optDouble("range", 1500) : 1500f;
				s.note = String.format("jumps up to %.0f", s.range);
				break;
			case MINE_STRIKE: {
				s.range = "mine_strike_station".equals(s.id) ? st.getSystemRangeBonus().computeEffective(MineStrikeStationStats.MINE_RANGE_STATION)
						: MineStrikeStats.getRange(ship);
				s.payload = systemMissile(t.sysMineWeapon, ship, t);
				if (s.payload != null) {
					WeaponSpec3D m = s.payload;
					m.mine = true;
					m.guided = false;
					m.flightTime = MineStrikeStats.LIVE_TIME;
					m.mineArmTime = t.sysMineArmTime;
					m.mineWindup = t.sysMineWindup;
					m.blastSound = "mine_explosion";
					s.note = String.format("lays a %.0f %s mine within %.0f (fuse %.0f, blast %.0f), lives %.0fs", m.damage, m.damageType, s.range,
							m.proxRange, m.proxRadius, m.flightTime);
				}
				break;
			}
			case ORION_DEVICE:
			case NOVA_BURST: {
				// Charges drop from the hull's system slots (the Retribution's is at the stern, behind its push plate).
				for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
					if (!slot.isSystemSlot()) continue;
					Vector2f loc = slot.getLocation(); // ship space: x forward, y left
					float x = -loc.y, z = -loc.x;
					float y = volume != null ? volume.topAt(x, z) * 0.5f : 0f;
					double a = Math.toRadians(slot.getAngle());
					s.slots.add(new WeaponMount(slot.getId(), new WeaponSpec3D(), new Vec3(x, y, z),
							new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)), slot.getArc(), false));
				}
				s.note = String.format("%d charges, one press fires them all a cooldown apart; %d charge slot(s)", s.maxAmmo, s.slots.size());
				break;
			}
			case ENERGY_LASH: {
				ExoticSystems x = s.ex;
				s.range = EnergyLashSystemScript.getRange(ship);
				x.lashEmp = EnergyLashSystemScript.EMP_DAMAGE;
				x.lashDamage = EnergyLashSystemScript.DAMAGE;
				x.lashMinCooldown = EnergyLashSystemScript.MIN_COOLDOWN;
				x.lashMaxCooldown = EnergyLashSystemScript.MAX_COOLDOWN;
				x.lashCooldownPerDp = EnergyLashSystemScript.COOLDOWN_DP_MULT;
				x.lashMinEnemyCooldown = EnergyLashSystemScript.MIN_HIT_ENEMY_COOLDOWN;
				x.lashMaxEnemyCooldown = EnergyLashSystemScript.MAX_HIT_ENEMY_COOLDOWN;
				x.lashPhaseCooldownMult = EnergyLashSystemScript.HIT_PHASE_ENEMY_COOLDOWN_MULT;
				x.lashPhaseOverload = EnergyLashSystemScript.PHASE_OVERLOAD_DUR;
				s.slots.addAll(systemSlots(ship, volume, new WeaponSpec3D()));
				s.note = String.format("lashes a friendly unit's system (or an enemy, %.0f EMP x %d) within %.0f", x.lashEmp, Math.max(1, s.slots.size()), s.range);
				break;
			}
			case FRAGMENT_VOLLEY:
				s.ex.volleyRangeMult = FragmentVolleySystemScript.FRAGMENT_RANGE_MULT;
				s.ex.volleyRegenMult = FragmentVolleySystemScript.FRAGMENT_REGEN_RATE_MULT;
				s.ammo = 0f; // only an Overseer's lash charges it
				s.note = "fires when an Overseer lashes it: fragment weapons x" + s.ex.volleyRangeMult + " range, at will";
				break;
			case INCURSION_MODE:
				s.ex.incursionSpeed = IncursionModeSystemScript.SPEED_BONUS;
				s.ex.incursionAmmoRegen = IncursionModeSystemScript.AMMO_REGEN_MULT;
				s.ex.incursionDissipation = IncursionModeSystemScript.FLUX_DISSIPATION_MULT;
				s.ammo = 0f;
				s.note = "fires when an Overseer lashes it: +" + (int) s.ex.incursionSpeed + " speed, reckless";
				break;
			case EXTRACTION_PROTOCOL:
				s.ex.extractionSpeed = ExtractionProtocolSystemScript.SPEED_BONUS;
				s.ammo = 0f;
				s.note = "fires when an Overseer lashes it: backs off, then vents and reloads";
				break;
			case CONSTRUCTION_SWARM:
				ConstructionSwarmSystemScript.init();
				s.ex.minFragments = ConstructionSwarmSystemScript.MIN_FRAGMENTS;
				s.slots.addAll(systemSlots(ship, volume, new WeaponSpec3D()));
				s.note = "sends out a swarm that builds a Threat ship (needs " + s.ex.minFragments + "+ fragments)";
				break;
			case CONVULSIVE_LUNGE:
				s.ex.lungeDistance = ConvulsiveLungeSystemScript.PULL_DIST;
				s.ex.lungeSpring = ConvulsiveLungeSystemScript.SPRING_CONSTANT;
				s.ex.lungeFriction = ConvulsiveLungeSystemScript.FRICTION;
				s.ex.maw = DwellerCombatStrategyAI.isMaw(ship);
				s.note = String.format("lunges %.0f toward the aim", s.ex.lungeDistance);
				break;
			case DARKENED_GAZE:
				s.ex.gazeOpeningDeg = DarkenedGazeSystemScript.SHIELD_OPENING;
				s.ex.gazeTurnReduction = DarkenedGazeSystemScript.TURN_RATE_REDUCTION;
				s.ex.gazeHullTakenMult = DarkenedGazeSystemScript.DAMAGE_TAKEN_MULT;
				s.note = "shield to the back, opens at the front, gaze beams fire through";
				break;
			case TENEBROUS_EXPULSION:
				s.ex.expelInterval = TenebrousExpulsionSystemScript.REFIRE_DELAY;
				s.ex.expelArcDeg = TenebrousExpulsionSystemScript.LAUNCH_ARC;
				s.ex.expelBackOff = TenebrousExpulsionSystemScript.BACK_OFF_ACCEL;
				s.ex.ejectaWing = TenebrousExpulsionSystemScript.EJECTA_WING;
				s.note = "spits out shroud ejecta every " + s.ex.expelInterval + " s, then backs off";
				break;
			case MOTE_CONTROL: {
				s3d.core.MoteControl mc = s.motes;
				mc.maxMotes = MoteControlScript.getMaxMotes(ship);
				mc.range = MoteControlScript.getRange(ship);
				mc.antiFighterDamage = MoteControlScript.getAntiFighterDamage(ship);
				mc.attractorSeconds = MoteControlScript.ATTRACTOR_DURATION;
				mc.attractorLockSeconds = MoteControlScript.ATTRACTOR_DURATION_LOCK;
				mc.pdFromSource = MoteControlScript.MAX_DIST_FROM_SOURCE_TO_ENGAGE_AS_PD;
				mc.pdFromAttractor = MoteControlScript.MAX_DIST_FROM_ATTRACTOR_TO_ENGAGE_AS_PD;
				Color ec = MoteControlScript.getEMPColor(ship);
				if (ec != null) mc.arcColor = new float[] {ec.getRed() / 255f, ec.getGreen() / 255f, ec.getBlue() / 255f};
				mc.mote = systemMissile(MoteControlScript.getWeaponId(ship), ship, t);
				if (mc.mote != null) {
					mc.mote.antiFighterDamage = mc.antiFighterDamage;
					mc.mote.ignoresFlares = true;
					mc.mote.guided = true;
				}
				s.slots.addAll(systemSlots(ship, volume, new WeaponSpec3D()));
				s.note = String.format("up to %d motes (%s), attractor within %.0f", mc.maxMotes, mc.mote != null ? mc.mote.name : "no mote weapon", mc.range);
				break;
			}
			case ACAUSAL_DISRUPTOR:
				s.range = AcausalDisruptorStats.getMaxRange(ship);
				s.disruptSeconds = AcausalDisruptorStats.DISRUPTION_DUR;
				s.note = String.format("overloads a ship within %.0f for %.1f s", s.range, s.disruptSeconds);
				break;
			case RESERVE_DEPLOYMENT:
				s.note = "extra fighters from the carrier's bays (host)";
				break;
			case DRONE_LAUNCHER:
				s.note = "vanilla launches the drones; they orbit in 3D";
				break;
			case COMBAT_BURN:
				s.note = "+100 top speed and acceleration while on";
				break;
			case CANISTER_FLAK: {
				String wid = spec != null && spec.getWeaponId() != null ? spec.getWeaponId() : "canister_flak";
				s.payload = systemMissile(wid, ship, t);
				WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(wid);
				s.burst = ws != null ? Math.max(1, ws.getBurstSize()) : 5;
				for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
					if (!slot.isSystemSlot()) continue;
					Vector2f loc = slot.getLocation(); // ship space: x forward, y left
					float x = -loc.y, z = -loc.x;
					float y = volume != null ? volume.topAt(x, z) + 1f : 0f;
					double a = Math.toRadians(slot.getAngle());
					s.slots.add(new WeaponMount(slot.getId(), s.payload != null ? s.payload : new WeaponSpec3D(), new Vec3(x, y, z),
							new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)), slot.getArc(), false));
				}
				if (s.payload != null) {
					s.payload.blastSound = "system_canister_flak_explosion";
					s.note = String.format("%d slots x %d canisters (%.0f %s, fuse %.0f, blast %.0f)", s.slots.size(), s.burst, s.payload.damage,
							s.payload.damageType, s.payload.proxRange, s.payload.proxRadius);
				}
				break;
			}
			case TARGETING_FEED:
				s.effectMult = 1f + TargetingFeedStats.DAMAGE_INCREASE_PERCENT / 100f;
				s.note = String.format("its fighters deal x%.2f damage", s.effectMult);
				break;
			case LIDAR_ARRAY:
				s.lidarRange = LidarArrayStats.RANGE_BONUS / 100f;
				s.lidarRof = LidarArrayStats.ROF_BONUS;
				s.lidarSpeed = LidarArrayStats.PROJECTILE_SPEED_BONUS / 100f;
				s.note = String.format("hardpoint guns +%.0f%% range, x%.1f rate of fire, +%.0f%% shot speed; nothing else fires", s.lidarRange * 100f,
						1f + s.lidarRof, s.lidarSpeed * 100f);
				break;
			case DRONE_STRIKE:
				s.payload = systemMissile(t.sysDroneStrikeWeapon, ship, t);
				if (s.payload != null) {
					s.payload.missileModelSource = null; // the drone itself is what flies
					s.note = String.format("a drone becomes a %.0f %s missile (+%.0f EMP)", s.payload.damage, s.payload.damageType, s.payload.emp);
				}
				break;
			default:
		}
		if (s.supported() && s.note == null && (s.kind == ShipSystem3D.Kind.MINE_STRIKE || s.kind == ShipSystem3D.Kind.CANISTER_FLAK
				|| s.kind == ShipSystem3D.Kind.DRONE_STRIKE)) {
			log.warn("S3D: " + ship.getName() + ": no weapon data for system " + s.id + "; it won't do anything");
		}
	}

	/**
	 * A missile weapon a system fires or spawns (not on any slot), from its spec: damage with the
	 * ship's missile damage bonus, flight with its missile bonuses. Null if it isn't a missile weapon.
	 */
	private static WeaponSpec3D systemMissile(String weaponId, ShipAPI ship, Tuning t) {
		WeaponSpecAPI spec = weaponId != null ? Global.getSettings().getWeaponSpec(weaponId) : null;
		if (!(spec instanceof ProjectileWeaponSpecAPI) || !(((ProjectileWeaponSpecAPI) spec).getProjectileSpec() instanceof MissileSpecAPI)) return null;
		WeaponSpec3D ws = new WeaponSpec3D();
		ws.id = weaponId;
		ws.name = spec.getWeaponName();
		ws.type = spec.getType() != null ? spec.getType().name() : "MISSILE";
		ws.supported = true;
		ws.range = spec.getMaxRange();
		if (spec.getDerivedStats() != null) {
			ws.damage = spec.getDerivedStats().getDamagePerShot() * ship.getMutableStats().getMissileWeaponDamageMult().getModifiedValue();
			ws.emp = spec.getDerivedStats().getEmpPerShot();
		}
		DamageType dt = spec.getDamageType();
		if (dt != null) {
			ws.damageType = dt.name();
			ws.shieldMult = dt.getShieldMult();
			ws.armorMult = dt.getArmorMult();
			ws.hullMult = dt.getHullMult();
		}
		String[] snd = fireSounds(weaponId);
		ws.fireSound = snd[1] != null ? snd[1] : snd[0];
		fillMissile(ws, (MissileSpecAPI) ((ProjectileWeaponSpecAPI) spec).getProjectileSpec(), ship, t);
		return ws;
	}

	private static String describe(String what, ShipSystem3D s) {
		if (s == null) return "";
		String timing = String.format("in %.1f / active %.1f / out %.1f s, cooldown %.1f s", s.chargeUp, s.active, s.chargeDown, s.cooldown)
				+ (s.maxAmmo > 0 ? String.format(", %d charges (+%.2f/s)", s.maxAmmo, s.ammoPerSecond) : "")
				+ (s.fluxPerUse > 0f ? String.format(", %.0f flux/use", s.fluxPerUse) : "")
				+ (s.fluxPerSecond > 0f ? String.format(", %.0f flux/s", s.fluxPerSecond) : "")
				+ (s.toggle ? ", toggle" : "") + (!s.firingAllowed ? ", no firing" : "");
		ModData.SystemRow row = ModDataLoader.data().system(s.id);
		String by = row == null ? "" : row.ignored() ? " (left out by " + row.mod + "'s systems.csv)" : " (" + row.mod + "'s systems.csv" + (row.params != null ? " " + row.params : "") + ")";
		return String.format(", %s %s (%s)%s%s: %s%s", what, s.name, s.id, s.supported() ? " -> " + s.kind : row != null ? "" : " NOT IN 3D YET", by, timing,
				s.note != null ? "; " + s.note : "");
	}

	private static String describe(ShieldFlux f) {
		String flux = String.format("flux %.0f/%.0f (+%.0f/s)%s", f.flux, f.maxFlux, f.dissipation, f.canVent() ? "" : ", can't vent");
		if (!f.hasShield()) return flux + ", no shield";
		return String.format("%s, %s shield arc %.0f -> 3D cone %.0f deg half-angle, radius %.0f, %.2f flux/dmg, upkeep %.0f/s",
				flux, f.type, f.arcDeg, Math.toDegrees(f.halfAngleMax), f.radius, f.fluxPerDamage, f.upkeep);
	}

	/** A launch bay slot as a point on the carrier's hull (unscaled local frame). */
	static Vec3 launchPoint(Ship3D carrier, WeaponSlotAPI slot, Vec3 out) {
		Vector2f loc = slot.getLocation(); // ship space: x forward, y left
		float x = -loc.y, z = -loc.x;
		return out.set(x, carrier.volume != null ? carrier.volume.topAt(x, z) + 2f : 0f, z);
	}

	/** Rebuilds the hit volume and re-seats turrets after the mesh tuning or a hull model file changed. */
	static void refreshGeometry(Ship3D s, Tuning t) {
		if (!(s.modelSource instanceof ShipModel.Source)) return;
		ShipModel.Source src = (ShipModel.Source) s.modelSource;
		if (s.hostRef instanceof ShipAPI) {
			com.fs.starfarer.api.combat.ShipHullSpecAPI spec = ((ShipAPI) s.hostRef).getHullSpec();
			s.modelSource = new ShipModel.Source(src.spritePath, src.image, src.cx, src.cyTop, HullMeshes.find(spec, src.image));
		}
		s.volume = ShipModel.geometry((ShipModel.Source) s.modelSource, t).volume;
		if (s.fighter && s.volume != null) s.volume = s.volume.thickened(s.baseRadius * FIGHTER_HIT_HALF_HEIGHT);
		for (WeaponMount m : s.weapons) m.localPos.y = s.volume.topAt(m.localPos.x, m.localPos.z) + 1f;
	}

	private static WeaponMount mount(WeaponAPI w, HullVolume volume, ShipAPI ship, Tuning t) {
		WeaponSlotAPI slot = w.getSlot();
		if (slot == null || slot.isDecorative() || slot.isSystemSlot() || w.isDecorative()) return null;
		WeaponSpec3D ws = weaponSpec(w, ship, t);
		Vector2f loc = slot.getLocation(); // ship space: x forward, y left
		float x = -loc.y, z = -loc.x;
		float y = volume != null ? volume.topAt(x, z) + 1f : 0f;
		double a = Math.toRadians(slot.getAngle());
		Vec3 rest = new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a));
		WeaponMount m = new WeaponMount(slot.getId(), ws, new Vec3(x, y, z), rest, slot.getArc(), slot.isHardpoint());
		m.hostRef = w;
		ws.iconSprites = iconSprites(w.getSpec());
		if (!slot.isHidden()) {
			WeaponSpecAPI spec = w.getSpec();
			m.modelSource = weaponSprite(slot.isHardpoint() ? spec.getHardpointSpriteName() : spec.getTurretSpriteName(), slot.isHardpoint());
			if (spec instanceof ProjectileWeaponSpecAPI) {
				ProjectileWeaponSpecAPI pws = (ProjectileWeaponSpecAPI) spec;
				m.gunModelSource = weaponSprite(slot.isHardpoint() ? pws.getHardpointGunSpriteName() : pws.getTurretGunSpriteName(), slot.isHardpoint());
			}
		}
		return m;
	}

	/** The weapon's icon as vanilla shows weapons: its turret sprite layers (under, base, barrel), else the hardpoint one; null if it has none. */
	private static String[] iconSprites(WeaponSpecAPI spec) {
		if (spec == null) return null;
		String base = spec.getTurretSpriteName(), under = spec.getTurretUnderSpriteName();
		String gun = spec instanceof ProjectileWeaponSpecAPI ? ((ProjectileWeaponSpecAPI) spec).getTurretGunSpriteName() : null;
		if (base == null || base.isEmpty()) {
			base = spec.getHardpointSpriteName();
			under = spec.getHardpointUnderSpriteName();
			gun = spec instanceof ProjectileWeaponSpecAPI ? ((ProjectileWeaponSpecAPI) spec).getHardpointGunSpriteName() : null;
		}
		if (base == null || base.isEmpty()) return null;
		java.util.List<String> layers = new java.util.ArrayList<>(3);
		for (String p : new String[] {under, base, gun}) if (p != null && !p.isEmpty()) layers.add(p);
		return layers.toArray(new String[0]);
	}

	/** Turret sprites pivot on their centre; hardpoint sprites on a point a quarter up from the back. */
	private static ShipModel.Source weaponSprite(String path, boolean hardpoint) {
		if (path == null || path.isEmpty()) return null;
		try {
			SpriteImage img = loadSprite(path);
			return new ShipModel.Source(path, img, img.w * 0.5f, hardpoint ? img.h * 0.75f : img.h * 0.5f, true);
		} catch (Throwable t) {
			log.warn("S3D: no weapon sprite " + path + " (" + t + ")");
			return null;
		}
	}

	private static WeaponSpec3D weaponSpec(WeaponAPI w, ShipAPI ship, Tuning t) {
		WeaponSpec3D ws = new WeaponSpec3D();
		ws.id = w.getId();
		ws.name = w.getDisplayName();
		ws.type = w.getType() != null ? w.getType().name() : "?";
		ws.size = w.getSize() != null ? w.getSize().name() : "?";
		ws.range = w.getRange();
		ws.projectileSpeed = w.getProjectileSpeed();
		ws.damage = w.getDamage() != null ? w.getDamage().getDamage() : 0f;
		DamageType dt = w.getDamageType();
		if (dt != null) {
			ws.damageType = dt.name();
			ws.shieldMult = dt.getShieldMult();
			ws.armorMult = dt.getArmorMult();
			ws.hullMult = dt.getHullMult();
		}
		ws.cooldown = w.getCooldown();
		ws.turnRate = w.getTurnRate();
		ws.fluxPerShot = Math.max(0f, w.getFluxCostToFire());
		String[] snd = fireSounds(w.getId());
		if (w.isBeam()) ws.beamLoopSound = snd[1] != null ? snd[1] : snd[0];
		else ws.fireSound = snd[1] != null ? snd[1] : snd[0];
		if (w.getDerivedStats() != null) ws.emp = Math.max(0f, w.isBeam() ? w.getDerivedStats().getEmpPerSecond() : w.getDerivedStats().getEmpPerShot());
		if (w.usesAmmo()) {
			ws.maxAmmo = w.getMaxAmmo();
			ws.baseMaxAmmo = w.getSpec() != null ? w.getSpec().getMaxAmmo() : ws.maxAmmo;
			ws.ammoPerSecond = w.getAmmoPerSecond();
		}
		WeaponSpecAPI spec = w.getSpec();
		if (spec != null && spec.getAIHints() != null) {
			ws.pd = spec.getAIHints().contains(WeaponAPI.AIHints.PD) || spec.getAIHints().contains(WeaponAPI.AIHints.PD_ONLY)
					|| spec.getAIHints().contains(WeaponAPI.AIHints.PD_ALSO);
			ws.pdOnly = spec.getAIHints().contains(WeaponAPI.AIHints.PD_ONLY);
		}
		ws.supported = true;
		if (w.getEffectPlugin() != null) effect(ws, w.getEffectPlugin().getClass().getName(), ship, t); // every-frame script
		ExoticBridge.weapon(ws, w); // Threat fragment weapons

		if (w.isBeam() && spec instanceof BeamWeaponSpecAPI) {
			BeamWeaponSpecAPI b = (BeamWeaponSpecAPI) spec;
			ws.kind = WeaponSpec3D.Kind.BEAM;
			ws.dps = w.getDamage() != null && w.getDamage().isDps() ? w.getDamage().getDamage() : b.getDamagePerSecond();
			ws.damage = ws.dps;
			ws.chargeUp = b.getChargeupTime();
			ws.chargeDown = b.getChargedownTime();
			ws.burstBeam = b.isBurstBeam();
			ws.burstDuration = b.getBurstDuration();
			if (b.getWidth() > 0f) ws.beamWidth = b.getWidth();
			if (b.getBeamSpeed() > 0f) ws.beamSpeed = b.getBeamSpeed();
			ws.projectileSpeed = ws.beamSpeed;
			// Beams: getFluxCostToFire is flux per second while firing; the spec value is the fallback.
			ws.fluxPerSecond = ws.fluxPerShot > 0f ? ws.fluxPerShot : Math.max(0f, b.getFluxPerSecond());
			ws.fluxPerShot = 0f;
			if (b.getCoreColor() != null) ws.coreColor = rgb(b.getCoreColor());
			if (b.getFringeColor() != null) ws.fringeColor = rgb(b.getFringeColor());
			effect(ws, b.getEffectClassName(), ship, t);
			return ws;
		}
		if (w.isBeam()) {
			unsupported(ws, "beam without beam data");
			return ws;
		}
		if (!(spec instanceof ProjectileWeaponSpecAPI)) {
			unsupported(ws, "not a projectile or beam weapon");
			return ws;
		}
		ProjectileWeaponSpecAPI pws = (ProjectileWeaponSpecAPI) spec;
		ws.chargeTime = pws.getChargeTime();
		ws.burstSize = Math.max(1, pws.getBurstSize());
		// getFluxCostToFire is per firing (the whole burst, e.g. Devastator 12 x 75 = 900); we charge per shell.
		ws.fluxPerShot /= ws.burstSize;
		ws.burstDelay = pws.getBurstDelay();
		ws.minSpread = pws.getMinSpread();
		ws.maxSpread = Math.max(pws.getMinSpread(), pws.getMaxSpread());
		ws.spreadBuildup = pws.getSpreadBuildup();
		ws.spreadDecay = pws.getSpreadDecayRate();
		Object ps = pws.getProjectileSpec();
		if (ps instanceof MissileSpecAPI) {
			fillMissile(ws, (MissileSpecAPI) ps, ship, t);
		} else if (ps instanceof ProjectileSpecAPI) {
			ProjectileSpecAPI proj = (ProjectileSpecAPI) ps;
			ws.impact = Math.max(0f, proj.getImpactMass());
			if (proj.getLength() > 0f) ws.projLength = proj.getLength();
			if (proj.getWidth() > 0f) ws.projWidth = proj.getWidth();
			Color core = proj.getCoreColor(), fringe = proj.getFringeColor(), glow = proj.getGlowColor();
			if (fringe == null) fringe = glow;
			if (core != null) ws.coreColor = rgb(core);
			if (fringe != null) ws.fringeColor = rgb(fringe);
			if (ws.projectileSpeed <= 0f) unsupported(ws, "no projectile speed");
			effect(ws, proj.getOnHitClassName(), ship, t);
			effect(ws, proj.getOnFireClassName(), ship, t);
			behaviour(ws, proj.getBehaviorSpec(), ship, t);
			ExoticBridge.projectile(ws, proj);
		} else {
			unsupported(ws, "unknown projectile type");
		}
		return ws;
	}

	/** The hull's system slots as mounts (where system launches, lashes and charges come from), facing their slot angle. */
	private static List<WeaponMount> systemSlots(ShipAPI ship, HullVolume volume, WeaponSpec3D spec) {
		List<WeaponMount> out = new ArrayList<>();
		for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
			if (!slot.isSystemSlot()) continue;
			Vector2f loc = slot.getLocation(); // ship space: x forward, y left
			float x = -loc.y, z = -loc.x;
			float y = volume != null ? volume.topAt(x, z) + 1f : 0f;
			double a = Math.toRadians(slot.getAngle());
			WeaponSpec3D ws = spec;
			if (slot.getSlotSize() != null) {
				ws = new WeaponSpec3D();
				ws.size = slot.getSlotSize().name();
			}
			out.add(new WeaponMount(slot.getId(), ws, new Vec3(x, y, z), new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)), slot.getArc(), false));
		}
		return out;
	}

	/** Darkened Gaze's beams: the Eye's decorative beam weapons, fired by the system only. */
	private static void gazeBeams(Ship3D s, ShipAPI ship, Tuning t) {
		for (WeaponAPI w : ship.getAllWeapons()) {
			if (w.getSlot() == null || !w.getSlot().isDecorative() || !w.isBeam()) continue;
			try {
				WeaponSlotAPI slot = w.getSlot();
				Vector2f loc = slot.getLocation();
				float x = -loc.y, z = -loc.x;
				float y = s.volume != null ? s.volume.topAt(x, z) + 1f : 0f;
				WeaponMount m = new WeaponMount(slot.getId(), weaponSpec(w, ship, t), new Vec3(x, y, z), new Vec3(0f, 0f, -1f), 10f, true);
				m.hostRef = w;
				s.gazeBeams.add(m);
			} catch (Throwable e) {
				log.warn("S3D: gaze beam " + w.getId() + " on " + ship.getName() + " left out (" + e + ")");
			}
		}
	}

	/** Vanilla flies rockets and bombs straight whatever turn rate their engine has (Hammer, Reaper, Sabot warheads, canisters...). */
	static boolean fliesStraight(String missileType) {
		return "ROCKET".equals(missileType) || "BOMB".equals(missileType) || "BOMB_WITH_SLOW".equals(missileType);
	}

	/** How vanilla's missile AI for this missile type goes for its target (see WeaponSpec3D.Guidance). */
	static WeaponSpec3D.Guidance guidance(String missileType) {
		if ("MISSILE_TWO_STAGE_SECOND_UNGUIDED".equals(missileType)) return WeaponSpec3D.Guidance.TWO_STAGE;
		if ("HEATSEEKER".equals(missileType)) return WeaponSpec3D.Guidance.HEATSEEKER;
		if ("PHASE_CHARGE".equals(missileType)) return WeaponSpec3D.Guidance.AIM_POINT;
		return WeaponSpec3D.Guidance.HOMING;
	}

	/** Missile flight comes from the missile's own hull/engine spec, plus the firing ship's missile bonuses. */
	private static void fillMissile(WeaponSpec3D ws, MissileSpecAPI ms, ShipAPI ship, Tuning t) {
		ShipHullSpecAPI hs = ms.getHullSpec();
		if (hs == null || hs.getEngineSpec() == null) {
			unsupported(ws, "missile without engine data");
			return;
		}
		MutableShipStatsAPI st = ship.getMutableStats();
		ShipHullSpecAPI.EngineSpecAPI e = hs.getEngineSpec();
		ws.kind = WeaponSpec3D.Kind.MISSILE;
		ws.impact = Math.max(0f, ms.getImpactStrength());
		ws.launchSpeed = ms.getLaunchSpeed();
		ws.missileMaxSpeed = st.getMissileMaxSpeedBonus().computeEffective(e.getMaxSpeed());
		ws.missileAccel = st.getMissileAccelerationBonus().computeEffective(e.getAcceleration());
		ws.missileTurnRate = st.getMissileMaxTurnRateBonus().computeEffective(e.getMaxTurnRate());
		ws.missileTurnAccel = st.getMissileTurnAccelerationBonus().computeEffective(e.getTurnAcceleration());
		ws.missileHp = st.getMissileHealthBonus().computeEffective(hs.getHitpoints());
		ws.missileRadius = Math.max(4f, hs.getCollisionRadius());
		ws.flightTime = ms.getMaxFlightTime();
		ws.explosionRadius = ms.getExplosionRadius() > 0f ? ms.getExplosionRadius() : ws.missileRadius * 3f;
		ws.guidance = guidance(ms.getTypeString());
		// A proximity charge flies to its aim point, not after a ship.
		ws.guided = ws.missileTurnRate > 1f && !fliesStraight(ms.getTypeString()) && ws.guidance != WeaponSpec3D.Guidance.AIM_POINT;
		ws.projectileSpeed = ws.missileMaxSpeed;
		Color glow = ms.getGlowColor() != null ? ms.getGlowColor() : ms.getExplosionColor();
		if (glow != null) ws.missileGlow = rgb(glow);
		ws.fringeColor = ws.missileGlow;
		ws.missileModelSource = weaponSprite(hs.getSpriteName(), false);
		effect(ws, ms.getOnHitClassName(), ship, t);
		effect(ws, ms.getOnFireClassName(), ship, t);
		if (isDem(ms.getOnFireClassName())) ws.dem = dem(ms.getBehaviorJSON(), ship, ws.name);
		behaviour(ws, ms.getBehaviorSpec(), ship, t);
		ExoticBridge.missile(ws, ms); // flares, fizzling, pass-through, area blasts
	}

	/**
	 * The scripts builtInEffect gives a 3D version (keep in step with its switch; ExoticBridge has its
	 * own list), and the ones that only change the look. For the compatibility report.
	 */
	static final java.util.Set<String> MODELLED_EFFECTS = java.util.Set.of("IonCannonOnHitEffect", "IonBeamEffect", "TachyonLanceEffect",
			"BreachOnHitEffect", "MiningBlasterOnHitEffect", "DisintegratorEffect", "GravitonBeamEffect", "PaladinEffect", "PilumOnHitEffect",
			"SabotOnHitEffect", "SquallOnFireEffect", "ShockRepeaterOnFireEffect", "DEMEffect", "CanisterFlakPlugin");
	static final java.util.Set<String> VISUAL_EFFECTS = java.util.Set.of("CryoblasterEffect", "CryofluxTransducerEffect",
			"HeavyAdjudicatorEffect", "LightsEffect", "BlinkerEffect", "SensorDishRotationEffect", "VPDriverEffect");

	/**
	 * Weapon scripts (on-hit, on-fire, every-frame, beam) by class name. Another mod's row in its
	 * data/config/s3d/weapon_effects.csv comes first (an effect with its numbers, the 3D version of a
	 * vanilla script, looks only, or left out); else the vanilla script's 3D version, by simple name.
	 */
	private static void effect(WeaponSpec3D ws, String className, ShipAPI ship, Tuning t) {
		if (className == null || className.isEmpty()) return;
		String simple = ModData.simpleName(className);
		ModData.EffectRow row = ModDataLoader.data().effect(className);
		if (row == null) {
			builtInEffect(ws, simple, ship, t);
		} else if (row.vanillaScript != null) {
			builtInEffect(ws, row.vanillaScript, ship, t);
		} else if (row.effect == ModData.Effect.VISUAL) {
			ws.visualEffect = append(ws.visualEffect, simple);
		} else if (row.effect != ModData.Effect.IGNORE) {
			note(ws, ModData.apply(row, ws));
		}
	}

	/**
	 * Vanilla weapon scripts by simple class name. Those with a 3D version set their numbers on the
	 * spec, read from the script's own public constants where it has them (so a mod that rebalances
	 * them carries over); purely visual ones are noted; anything else is named in the log as not modelled.
	 */
	private static void builtInEffect(WeaponSpec3D ws, String simple, ShipAPI ship, Tuning t) {
		switch (simple) {
			case "IonCannonOnHitEffect":
			case "IonBeamEffect":
			case "TachyonLanceEffect":
				ws.empArcs = true;
				break;
			case "BreachOnHitEffect":
				ws.armorBreak = BreachOnHitEffect.DAMAGE;
				note(ws, String.format("strips %.0f armor on a hull hit", ws.armorBreak));
				break;
			case "MiningBlasterOnHitEffect":
				ws.armorBreak = MiningBlasterOnHitEffect.DAMAGE;
				note(ws, String.format("strips %.0f armor on a hull hit", ws.armorBreak));
				break;
			case "DisintegratorEffect":
				ws.armorBurn = DisintegratorEffect.TOTAL_DAMAGE;
				ws.armorBurnTicks = Math.max(1, DisintegratorEffect.NUM_TICKS);
				note(ws, String.format("burns %.0f armor over %d ticks", ws.armorBurn, ws.armorBurnTicks));
				break;
			case "GravitonBeamEffect":
				ws.shieldMark = new float[] {GravitonBeamEffect.DAMAGE_PERCENT_ONE / 100f, GravitonBeamEffect.DAMAGE_PERCENT_TWO / 100f,
						GravitonBeamEffect.DAMAGE_PERCENT_THREE / 100f};
				ws.shieldMarkTime = GravitonBeamEffect.EFFECT_DUR;
				note(ws, String.format("shields under it take +%.0f/%.0f/%.0f%% damage", GravitonBeamEffect.DAMAGE_PERCENT_ONE,
						GravitonBeamEffect.DAMAGE_PERCENT_TWO, GravitonBeamEffect.DAMAGE_PERCENT_THREE));
				break;
			case "PaladinEffect": {
				WeaponSpec3D b = new WeaponSpec3D();
				b.id = b.name = ws.name + " blast";
				b.type = ws.type;
				b.supported = true;
				b.damage = t.fxPaladinBlastDamage;
				b.proxRadius = t.fxPaladinBlastRadius;
				b.proxCore = t.fxPaladinBlastCore;
				b.damageType = DamageType.FRAGMENTATION.name();
				b.shieldMult = DamageType.FRAGMENTATION.getShieldMult();
				b.armorMult = DamageType.FRAGMENTATION.getArmorMult();
				b.hullMult = DamageType.FRAGMENTATION.getHullMult();
				b.fringeColor = new float[] {1f, 0.4f, 0.4f};
				ws.beamBlast = b;
				note(ws, String.format("%.0f frag blast per burst", b.damage));
				break;
			}
			case "PilumOnHitEffect":
				ws.bonusArcChance = 1f;
				ws.bonusArcPierces = true;
				note(ws, "EMP arc on a hit (through shields at high hard flux)");
				break;
			case "SabotOnHitEffect":
				ws.bonusArcChance = t.fxSabotArcChance;
				note(ws, String.format("%.0f%% chance of an EMP arc on a hull hit", ws.bonusArcChance * 100f));
				break;
			case "SquallOnFireEffect":
				ws.shieldBonus = SquallOnFireEffect.DAMAGE * ship.getMutableStats().getMissileWeaponDamageMult().getModifiedValue();
				note(ws, String.format("+%.0f damage on shields", ws.shieldBonus));
				break;
			case "ShockRepeaterOnFireEffect":
				ws.arcGunConeDeg = ShockRepeaterOnFireEffect.ARC;
				note(ws, String.format("arcs to the nearest enemy in a %.0f deg cone", ws.arcGunConeDeg));
				break;
			case "DEMEffect":
				break; // the missile's behaviour JSON drives it (dem())
			case "CanisterFlakPlugin":
				break; // Canister Flak's random speeds and fuses: the system throws them that way
			case "CryoblasterEffect":
			case "CryofluxTransducerEffect":
			case "HeavyAdjudicatorEffect":
			case "LightsEffect":
			case "BlinkerEffect":
			case "SensorDishRotationEffect":
			case "VPDriverEffect":
				ws.visualEffect = append(ws.visualEffect, simple);
				break;
			default:
				if (!ExoticBridge.effect(ws, simple, ship, t)) ws.unmodelledEffect = append(ws.unmodelledEffect, simple); // batch 2, else not modelled
		}
	}

	private static boolean isDem(String className) {
		return className != null && className.endsWith(".DEMEffect");
	}

	private static void note(WeaponSpec3D ws, String what) {
		ws.effectNote = append(ws.effectNote, what);
	}

	private static String append(String list, String item) {
		if (list == null) return item;
		return (", " + list + ", ").contains(", " + item + ", ") ? list : list + ", " + item;
	}

	/** A behaviour value that's either a number or a [min, max] pair; returns {min, max}. */
	private static float[] pair(JSONObject j, String key, float def) {
		JSONArray a = j.optJSONArray(key);
		if (a != null && a.length() >= 2) return new float[] {(float) a.optDouble(0, def), (float) a.optDouble(1, def)};
		float v = (float) j.optDouble(key, def);
		return new float[] {v, v};
	}

	/**
	 * DEM missile attack from its behaviour JSON (vanilla's keys), with the payload beam read from its
	 * weapon spec (plus the launching ship's missile damage bonus, as vanilla's drone gets). Null if
	 * the payload isn't a beam (then it flies as a plain guided missile).
	 */
	private static DemSpec dem(JSONObject j, ShipAPI ship, String weaponName) {
		if (j == null) return null;
		String payloadId = j.optString("payloadWeaponId", null);
		WeaponSpecAPI ps = payloadId != null ? Global.getSettings().getWeaponSpec(payloadId) : null;
		if (!(ps instanceof BeamWeaponSpecAPI)) {
			log.warn("S3D: " + weaponName + ": DEM payload " + payloadId + " isn't a beam; flies as a plain missile");
			return null;
		}
		BeamWeaponSpecAPI b = (BeamWeaponSpecAPI) ps;
		DemSpec d = new DemSpec();
		float[] v = pair(j, "minDelayBeforeTriggering", 1f);
		d.minDelay = (v[0] + v[1]) * 0.5f;
		v = pair(j, "triggerDistance", 500f);
		d.triggerMin = v[0];
		d.triggerMax = v[1];
		v = pair(j, "preferredMinFireDistance", 0f);
		d.holdMinLo = v[0];
		d.holdMinHi = v[1];
		if (j.has("preferredMaxFireDistance")) {
			v = pair(j, "preferredMaxFireDistance", d.triggerMax);
			d.holdMax = (v[0] + v[1]) * 0.5f;
		}
		if (j.has("triggerAngle")) {
			v = pair(j, "triggerAngle", 0f);
			d.triggerAngleLo = v[0];
			d.triggerAngleHi = v[1];
		}
		d.driftFrac = (float) j.optDouble("allowedDriftFraction", 0.33);
		d.turnBoost = (float) j.optDouble("turnRateBoost", 100);
		d.aimArcDeg = (float) j.optDouble("targetingLaserArc", 10);
		v = pair(j, "targetingTime", 1f);
		d.targetingTime = (v[0] + v[1]) * 0.5f;
		d.firingTime = (float) j.optDouble("firingTime", 1.25);
		d.laserRange = j.has("targetingLaserRange") ? (float) j.optDouble("targetingLaserRange", 600)
				: Math.max(d.triggerMax, d.holdMinHi) + 200f;
		String laserId = j.optString("targetingLaserId", null);
		WeaponSpecAPI ls = laserId != null ? Global.getSettings().getWeaponSpec(laserId) : null;
		if (ls instanceof BeamWeaponSpecAPI && ((BeamWeaponSpecAPI) ls).getFringeColor() != null) d.laserColor = rgb(((BeamWeaponSpecAPI) ls).getFringeColor());
		d.bombPumped = j.optBoolean("bombPumped", false);
		d.destroyWhenDone = j.optBoolean("destroyMissleWhenDoneFiring", false); // sic, vanilla's key
		d.randomStrafe = j.optBoolean("randomStrafe", false);

		WeaponSpec3D p = new WeaponSpec3D();
		p.id = payloadId;
		p.name = weaponName + " payload";
		p.kind = WeaponSpec3D.Kind.BEAM;
		p.type = ps.getType() != null ? ps.getType().name() : "ENERGY";
		p.supported = true;
		p.dps = b.getDamagePerSecond() * ship.getMutableStats().getMissileWeaponDamageMult().getModifiedValue();
		p.damage = p.dps;
		p.emp = Math.max(0f, b.getEMPPerSecond());
		DamageType dt = ps.getDamageType();
		if (dt != null) {
			p.damageType = dt.name();
			p.shieldMult = dt.getShieldMult();
			p.armorMult = dt.getArmorMult();
			p.hullMult = dt.getHullMult();
		}
		p.range = ps.getMaxRange();
		p.chargeUp = b.getChargeupTime();
		p.chargeDown = b.getChargedownTime();
		p.burstBeam = b.isBurstBeam();
		p.burstDuration = b.getBurstDuration();
		if (b.getWidth() > 0f) p.beamWidth = b.getWidth();
		if (b.getCoreColor() != null) p.coreColor = rgb(b.getCoreColor());
		if (b.getFringeColor() != null) p.fringeColor = rgb(b.getFringeColor());
		p.beamLoopSound = fireSounds(payloadId)[1] != null ? fireSounds(payloadId)[1] : fireSounds(payloadId)[0];
		d.payload = p;
		return d;
	}

	private static final Map<String, JSONObject> WARHEADS = new HashMap<>();

	/** A MIRV warhead's projectile data file, read through the game's loader (cached; null if it can't be read). */
	private static JSONObject warheadJson(String projId) {
		if (projId == null || projId.isEmpty()) return null;
		if (WARHEADS.containsKey(projId)) return WARHEADS.get(projId);
		JSONObject j = null;
		try {
			j = Global.getSettings().loadJSON("data/weapons/proj/" + projId + ".proj");
		} catch (Throwable e) {
			log.info("S3D: can't read warhead " + projId + " (" + e.getClass().getSimpleName() + "); it flies as a plain shell");
		}
		WARHEADS.put(projId, j);
		return j;
	}

	/**
	 * A warhead that's a missile of its own (Pilum's second stage, Hydra's DEM warheads, Hurricane's
	 * unguided ones): flight from its data file plus the ship's missile bonuses.
	 */
	private static void warheadMissile(WeaponSpec3D parent, WeaponSpec3D h, JSONObject wj, JSONObject mirv, ShipAPI ship, Tuning t) {
		JSONObject e = wj.optJSONObject("engineSpec");
		if (e == null) return;
		MutableShipStatsAPI st = ship.getMutableStats();
		h.kind = WeaponSpec3D.Kind.MISSILE;
		h.missileMaxSpeed = st.getMissileMaxSpeedBonus().computeEffective((float) e.optDouble("maxSpeed", 300));
		h.missileAccel = st.getMissileAccelerationBonus().computeEffective((float) e.optDouble("acc", 300));
		h.missileTurnRate = st.getMissileMaxTurnRateBonus().computeEffective((float) e.optDouble("turnRate", 0));
		h.missileTurnAccel = st.getMissileTurnAccelerationBonus().computeEffective((float) e.optDouble("turnAcc", 0));
		h.flightTime = (float) wj.optDouble("maxFlightTime", 5);
		h.missileHp = st.getMissileHealthBonus().computeEffective((float) mirv.optDouble("hitpoints", parent.missileHp));
		h.missileRadius = Math.max(4f, (float) wj.optDouble("collisionRadius", 8));
		h.explosionRadius = (float) wj.optDouble("explosionRadius", h.missileRadius * 3f);
		String type = wj.optString("missileType", "");
		// Hurricane's warheads (two-stage: "second unguided") still aim at the target before they lock on and burn.
		h.guidance = guidance(type);
		h.guided = h.missileTurnRate > 1f && !fliesStraight(type) && h.guidance != WeaponSpec3D.Guidance.AIM_POINT;
		h.projectileSpeed = h.missileMaxSpeed;
		h.range = parent.range;
		h.missileGlow = parent.missileGlow;
		String sprite = wj.optString("sprite", null);
		if (sprite != null && !sprite.isEmpty()) h.missileModelSource = weaponSprite(sprite, false);
		String onFire = wj.optString("onFireEffect", null);
		effect(h, onFire, ship, t);
		if (isDem(onFire)) h.dem = dem(wj.optJSONObject("behaviorSpec"), ship, h.name);
	}

	/** Shot behaviours: proximity fuse (flak), splitting missiles (MIRV) and DEM attacks get 3D versions. */
	private static void behaviour(WeaponSpec3D ws, ShotBehaviorSpecAPI b, ShipAPI ship, Tuning t) {
		if (b == null || b.getBehavorString() == null) return;
		JSONObject p = b.getParams();
		if (p == null) p = new JSONObject();
		switch (b.getBehavorString()) {
			case "PROXIMITY_FUSE": {
				ws.proxRange = (float) p.optDouble("range", 30);
				JSONObject ex = p.optJSONObject("explosionSpec");
				ws.proxRadius = ex != null ? (float) ex.optDouble("radius", ws.proxRange * 1.5f) : ws.proxRange * 1.5f;
				ws.proxCore = ex != null ? (float) ex.optDouble("coreRadius", ws.proxRange) : ws.proxRange;
				ws.proxRangeVariance = (float) p.optDouble("shotRangeVariance", 0);
				ws.proxVarianceChance = (float) p.optDouble("probOfVariance", 1);
				break;
			}
			case "MIRV": {
				ws.mirvCount = Math.max(1, p.optInt("numShots", 3));
				ws.mirvSplitRange = (float) p.optDouble("splitRange", 400);
				ws.mirvSplitRangeRandom = (float) p.optDouble("splitRangeRange", 0);
				ws.mirvMinTime = (float) p.optDouble("minTimeToSplit", 0);
				ws.mirvArcDeg = (float) p.optDouble("arc", 20);
				ws.mirvArcOffsetDeg = (float) p.optDouble("arcOffset", 0);
				ws.mirvSpreadSpeed = (float) p.optDouble("spreadSpeed", 800);
				ws.mirvSpreadSpeedRandom = (float) p.optDouble("spreadSpeedRange", 0);
				ws.mirvEvenSpread = p.optBoolean("evenSpread", false);
				ws.mirvInaccuracyDeg = (float) p.optDouble("spreadInaccuracy", 0);
				ws.mirvSplitEarly = p.optBoolean("canSplitEarly", false);
				WeaponSpec3D h = new WeaponSpec3D();
				h.id = ws.id + "_warhead";
				h.name = ws.name + " warhead";
				h.supported = true;
				h.type = ws.type;
				h.damage = (float) p.optDouble("damage", ws.damage);
				h.emp = (float) p.optDouble("emp", 0);
				h.damageType = p.optString("damageType", ws.damageType);
				try {
					DamageType dt = DamageType.valueOf(h.damageType);
					h.shieldMult = dt.getShieldMult();
					h.armorMult = dt.getArmorMult();
					h.hullMult = dt.getHullMult();
				} catch (Throwable ignored) {
					h.shieldMult = ws.shieldMult;
					h.armorMult = ws.armorMult;
					h.hullMult = ws.hullMult;
				}
				// Warhead damage gets the firing ship's missile damage bonus too.
				h.damage = ship.getMutableStats().getMissileWeaponDamageMult().getModifiedValue() * h.damage;
				h.projectileSpeed = ws.mirvSpreadSpeed;
				h.impact = (float) p.optDouble("impact", ws.impact);
				h.range = (float) p.optDouble("projectileRange", ws.mirvSplitRange * 2f + ws.mirvSplitRangeRandom);
				h.projLength = 30f;
				h.projWidth = 6f;
				h.coreColor = new float[] {1f, 1f, 1f};
				h.fringeColor = ws.missileGlow;
				JSONObject wj = warheadJson(p.optString("projectileSpec", null));
				if (wj != null) {
					effect(h, wj.optString("onHitEffect", null), ship, t);
					if ("missile".equals(wj.optString("specClass", ""))) warheadMissile(ws, h, wj, p, ship, t);
				}
				ws.mirvWarhead = h;
				break;
			}
			case "CUSTOM":
				if (ws.dem == null) ws.unmodelledEffect = append(ws.unmodelledEffect, "CUSTOM behaviour");
				break;
			default:
				ws.unmodelledEffect = append(ws.unmodelledEffect, b.getBehavorString());
		}
	}

	private static String guidanceName(WeaponSpec3D ws) {
		switch (ws.guidance) {
			case TWO_STAGE: return ws.guided ? "two-stage (aims, locks, burns straight)" : "unguided";
			case HEATSEEKER: return ws.guided ? "heatseeker (goes for the tail)" : "unguided";
			case AIM_POINT: return "to the aim point, then waits";
			default: return ws.guided ? "guided" : "unguided";
		}
	}

	private static String describe(WeaponSpec3D ws) {
		if (!ws.supported) return "not fired (" + ws.unsupportedReason + ")";
		String pd = ws.pdOnly ? " [PD only]" : ws.pd ? " [PD]" : "";
		String ammo = ws.maxAmmo > 0 ? String.format(", ammo %d (+%.2f/s)", ws.maxAmmo, ws.ammoPerSecond) : "";
		ammo += ws.fluxPerSecond > 0f ? String.format(", flux %.0f/s", ws.fluxPerSecond) : String.format(", flux %.0f/shot", ws.fluxPerShot);
		if (ws.emp > 0f) ammo += String.format(", EMP %.0f%s%s", ws.emp, ws.kind == WeaponSpec3D.Kind.BEAM ? "/s" : "", ws.empArcs ? " (arcs through shields)" : "");
		if (ws.proxRange > 0f) ammo += String.format(", proximity fuse %.0f (blast %.0f%s)", ws.proxRange, ws.proxRadius,
				ws.proxRangeVariance > 0f ? String.format(", bursts up to %.0f%% short", ws.proxRangeVariance * 100f) : "");
		if (ws.mirvWarhead != null) {
			WeaponSpec3D h = ws.mirvWarhead;
			ammo += String.format(", splits at %.0f%s into %d x %.0f %s %s over %.0f deg%s", ws.mirvSplitRange, ws.mirvSplitEarly ? " (early if close)" : "",
					ws.mirvCount, h.damage, h.damageType, h.kind == WeaponSpec3D.Kind.MISSILE ? guidanceName(h) + " missiles" : "shells",
					ws.mirvArcDeg, ws.mirvArcOffsetDeg != 0f ? String.format(" turned %.0f", ws.mirvArcOffsetDeg) : "");
			if (h.effectNote != null) ammo += " (" + h.effectNote + ")";
			if (h.dem != null) ammo += " (" + describeDem(h.dem) + ")";
		}
		if (ws.dem != null) ammo += ", " + describeDem(ws.dem);
		if (ws.effectNote != null) ammo += ", " + ws.effectNote;
		if (ws.visualEffect != null) ammo += ", visual only: " + ws.visualEffect;
		if (ws.unmodelledEffect != null) ammo += ", not modelled: " + ws.unmodelledEffect;
		switch (ws.kind) {
			case BEAM:
				return String.format("BEAM %s %.0f dps, range %.0f, charge %.2f/%.2fs, %s, cooldown %.2fs, width %.0f, turn %.0f deg/s%s%s",
						ws.damageType, ws.dps, ws.range, ws.chargeUp, ws.chargeDown,
						ws.burstBeam ? String.format("burst %.2fs", ws.burstDuration) : "continuous", ws.cooldown, ws.beamWidth, ws.turnRate, pd, ammo);
			case MISSILE:
				return String.format("MISSILE %s %.0f dmg, range %.0f, launch %.0f, speed %.0f, accel %.0f, turn %.0f deg/s, %s, flight %.1fs, hp %.0f, cooldown %.2fs%s%s",
						ws.damageType, ws.damage, ws.range, ws.launchSpeed, ws.missileMaxSpeed, ws.missileAccel, ws.missileTurnRate,
						guidanceName(ws), ws.flightTime, ws.missileHp, ws.cooldown, pd, ammo);
			default:
				return String.format("%s %.0f dmg, range %.0f, speed %.0f, cooldown %.2fs, burst %dx%.2fs, charge %.2fs, spread %.0f-%.0f, turn %.0f deg/s%s%s",
						ws.damageType, ws.damage, ws.range, ws.projectileSpeed, ws.cooldown, ws.burstSize, ws.burstDelay,
						ws.chargeTime, ws.minSpread, ws.maxSpread, ws.turnRate, pd, ammo);
		}
	}

	private static String describeDem(DemSpec d) {
		WeaponSpec3D p = d.payload;
		return String.format("DEM: attacks from %.0f-%.0f, holds %.0f-%.0f, paints %.1fs, then %s beam %.0f dps %s %.2fs%s",
				d.triggerMin, d.triggerMax, d.holdMinLo, d.holdMax >= 0f ? d.holdMax : d.triggerMax, d.targetingTime, p.damageType, p.dps,
				p.burstBeam ? "burst" : "held", p.burstBeam ? p.burstDuration : d.firingTime, d.bombPumped ? ", bomb-pumped" : "");
	}

	private static void unsupported(WeaponSpec3D ws, String why) {
		if (!ws.supported) return;
		ws.supported = false;
		ws.unsupportedReason = why;
	}

	private static float[] rgb(Color c) {
		return new float[] {c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f};
	}

	static String sizeClass(ShipAPI ship) {
		if (ship.isStation() && !ship.isStationModule()) return "station";
		switch (ship.getHullSize()) {
			case FIGHTER: return "fighter";
			case DESTROYER: return "destroyer";
			case CRUISER: return "cruiser";
			case CAPITAL_SHIP: return "capital";
			default: return "frigate";
		}
	}

	/** Variant name and role, as vanilla's ship panel prints them ("Custom" + "Combat Freighter"); null if neither. */
	private static String variantName(ShipAPI ship) {
		if (ship.getVariant() == null) return null;
		String name = ship.getVariant().getDisplayName(), role = ship.getVariant().getDesignation();
		if (role == null || role.isEmpty()) role = ship.getHullSpec().getDesignation();
		String s = ((name != null ? name : "") + " " + (role != null ? role : "")).trim();
		return s.isEmpty() ? null : s;
	}

	/**
	 * Movement stats with every modifier applied (hullmods, skills, D-mods, CR) except the deployment
	 * travel drive, which adds a temporary +600 flat to speed and acceleration while a ship burns in.
	 */
	static FlightParams readFlightParams(ShipAPI ship) {
		MutableShipStatsAPI st = ship.getMutableStats();
		return new FlightParams(
				withoutTransient(st.getMaxSpeed(), ship),
				withoutTransient(st.getAcceleration(), ship),
				withoutTransient(st.getDeceleration(), ship),
				withoutTransient(st.getMaxTurnRate(), ship),
				withoutTransient(st.getTurnAcceleration(), ship));
	}

	/** True while vanilla's deployment burn is running; its stat bonus is meaningless in 3D. */
	static boolean travelDriveActive(ShipAPI ship) {
		ShipSystemAPI drive = ship.getTravelDrive();
		return drive != null && (drive.isActive() || drive.isOn());
	}

	/** Same formula as MutableStat.recompute(), skipping travel-drive modifiers. */
	private static float withoutTransient(MutableStat stat, ShipAPI ship) {
		float base = stat.getBaseValue(), flat = 0f, percent = 0f, mult = 1f;
		for (Map.Entry<String, MutableStat.StatMod> e : stat.getFlatMods().entrySet()) {
			if (isTravelDrive(e.getKey(), ship)) continue;
			flat += e.getValue().getValue();
		}
		for (Map.Entry<String, MutableStat.StatMod> e : stat.getPercentMods().entrySet()) {
			if (isTravelDrive(e.getKey(), ship)) continue;
			percent += e.getValue().getValue();
		}
		for (Map.Entry<String, MutableStat.StatMod> e : stat.getMultMods().entrySet()) {
			if (isTravelDrive(e.getKey(), ship)) continue;
			mult *= e.getValue().getValue();
		}
		return (base + base * percent / 100f + flat) * mult;
	}

	/**
	 * How much battle objectives raise this ship's top speed right now (vanilla nav buoys / nav
	 * rating): its top speed with modifiers whose source mentions "nav" over its top speed without
	 * them; 1 if none. Every top-speed modifier source is logged once, so the real key shows in the
	 * log if it's something else.
	 */
	static float navSpeedMult(ShipAPI ship) {
		MutableStat stat = ship.getMutableStats().getMaxSpeed();
		float base = stat.getBaseValue(), flat = 0f, navFlat = 0f, percent = 0f, navPercent = 0f, mult = 1f, navMult = 1f;
		for (Map.Entry<String, MutableStat.StatMod> e : stat.getFlatMods().entrySet()) {
			if (isNav(e.getKey())) navFlat += e.getValue().getValue();
			else flat += e.getValue().getValue();
		}
		for (Map.Entry<String, MutableStat.StatMod> e : stat.getPercentMods().entrySet()) {
			if (isNav(e.getKey())) navPercent += e.getValue().getValue();
			else percent += e.getValue().getValue();
		}
		for (Map.Entry<String, MutableStat.StatMod> e : stat.getMultMods().entrySet()) {
			if (isNav(e.getKey())) navMult *= e.getValue().getValue();
			else mult *= e.getValue().getValue();
		}
		float without = (base + base * percent / 100f + flat) * mult;
		float with = (base + base * (percent + navPercent) / 100f + flat + navFlat) * mult * navMult;
		return without > 1f && with > without ? with / without : 1f;
	}

	private static boolean isNav(String source) {
		if (source == null) return false;
		if (LOGGED_SPEED_KEYS.add(source)) log.info("S3D: top speed modifier source seen: '" + source + "'");
		return source.toLowerCase().contains("nav");
	}

	private static boolean isTravelDrive(String source, ShipAPI ship) {
		if (source == null) return false;
		String k = source.toLowerCase();
		boolean match = k.contains("traveldrive") || k.contains("travel_drive");
		if (match && LOGGED_DRIVE_KEYS.add(source)) log.info("S3D: ignoring travel-drive stat modifier '" + source + "'");
		return match;
	}

	static SpriteImage loadSprite(String path) throws Exception {
		SpriteImage img = SPRITES.get(path);
		if (img != null) return img;
		BufferedImage bi;
		try (InputStream in = Global.getSettings().openStream(path)) {
			bi = ImageIO.read(in);
		}
		if (bi == null) throw new IllegalStateException("Could not decode sprite " + path);
		img = new SpriteImage(bi);
		SPRITES.put(path, img);
		return img;
	}
}
