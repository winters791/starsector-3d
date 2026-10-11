package s3d.host;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
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

import s3d.core.ArmorShell;
import s3d.core.ShieldFlux;
import s3d.core.ShipSystem3D;
import s3d.core.EngineMount;
import s3d.core.HullVolume;
import s3d.core.Tuning;
import s3d.core.WeaponGroup3D;
import s3d.core.WeaponMount;
import s3d.core.WeaponSpec3D;
import s3d.math.Vec3;
import s3d.core.FlightParams;
import s3d.core.Ship3D;
import s3d.render.ShipModel;
import com.fs.starfarer.api.loading.ShotBehaviorSpecAPI;
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
		ShipModel.Source hullSource = new ShipModel.Source(spritePath, img, cx, cyTop);
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
		s.system = readSystem(ship, ship.getSystem(), false);
		ShieldAPI defence = ship.getShield();
		if (ship.getPhaseCloak() != null && (defence == null || defence.getType() == ShieldAPI.ShieldType.PHASE || defence.getType() == ShieldAPI.ShieldType.NONE)) {
			s.cloak = readSystem(ship, ship.getPhaseCloak(), true);
		}
		s.weaponRepairTime = t.weaponRepairTime * st.getCombatWeaponRepairTimeMult().getModifiedValue();
		s.engineRepairTime = t.engineRepairTime * st.getCombatEngineRepairTimeMult().getModifiedValue();

		StringBuilder wlog = new StringBuilder();
		Map<WeaponAPI, WeaponMount> byWeapon = new HashMap<>();
		for (WeaponAPI w : ship.getAllWeapons()) {
			WeaponMount m = mount(w, s.volume, ship);
			if (m == null) continue;
			m.maxHp = m.hp = Math.max(1f, w.getMaxHealth());
			s.weapons.add(m);
			byWeapon.put(w, m);
			WeaponSpec3D ws = m.spec;
			wlog.append(String.format("%n    %s @%s %s arc %.0f: %s", ws.name, m.slotId, m.hardpoint ? "hardpoint" : "turret",
					Math.toDegrees(m.halfArcRad * 2f), describe(ws)));
		}

		for (ShipEngineAPI e : ship.getEngineController().getShipEngines()) {
			if (e.isSystemActivated()) continue;
			EngineSlotAPI slot = e.getEngineSlot();
			Vector2f loc = slot.computePosition(new Vector2f(0f, 0f), 0f); // ship space: x forward, y left
			Color c = slot.getColor() != null ? slot.getColor() : e.getEngineColor();
			if (c == null) c = new Color(255, 150, 80);
			s.engines.add(EngineMount.fromShipSpace(loc.x, loc.y, slot.getAngle(), slot.getLength(), slot.getWidth(),
					c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f));
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

		// Carriers: fighters land on the first launch bay (else the middle of the hull's top).
		s.landingLocal.set(0f, s.volume != null ? s.volume.topAt(0f, 0f) + 2f : 0f, 0f);
		java.util.List<com.fs.starfarer.api.combat.FighterLaunchBayAPI> bays = ship.getLaunchBaysCopy();
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
	static ShipSystem3D readSystem(ShipAPI ship, com.fs.starfarer.api.combat.ShipSystemAPI sys, boolean phase) {
		if (sys == null) return null;
		ShipSystem3D.Kind kind = ShipSystem3D.kindFor(sys.getId(), phase);
		ShipSystem3D s = new ShipSystem3D(sys.getId(), sys.getDisplayName(), kind);
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
			s.alwaysAccelerate = spec.isAlwaysAccelerate();
			s.range = spec.getRange(ship.getMutableStats());
			s.useSound = spec.getUseSound();
			s.loopSound = spec.getLoopSound();
			s.offSound = spec.getDeactivateSound();
			s.damage = spec.getDamage();
			s.empDamage = spec.getEmpDamage();
		}
		if (phase) s.toggle = true;
		return s;
	}

	private static String describe(String what, ShipSystem3D s) {
		if (s == null) return "";
		String timing = String.format("in %.1f / active %.1f / out %.1f s, cooldown %.1f s", s.chargeUp, s.active, s.chargeDown, s.cooldown)
				+ (s.maxAmmo > 0 ? String.format(", %d charges (+%.2f/s)", s.maxAmmo, s.ammoPerSecond) : "")
				+ (s.fluxPerUse > 0f ? String.format(", %.0f flux/use", s.fluxPerUse) : "")
				+ (s.fluxPerSecond > 0f ? String.format(", %.0f flux/s", s.fluxPerSecond) : "")
				+ (s.toggle ? ", toggle" : "") + (!s.firingAllowed ? ", no firing" : "");
		return String.format(", %s %s (%s)%s: %s", what, s.name, s.id, s.supported() ? " -> " + s.kind : " NOT IN 3D YET", timing);
	}

	private static String describe(ShieldFlux f) {
		String flux = String.format("flux %.0f/%.0f (+%.0f/s)", f.flux, f.maxFlux, f.dissipation);
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

	/** Rebuilds the hit volume and re-seats turrets after the mesh tuning changed. */
	static void refreshGeometry(Ship3D s, Tuning t) {
		if (!(s.modelSource instanceof ShipModel.Source)) return;
		s.volume = ShipModel.geometry((ShipModel.Source) s.modelSource, t).volume;
		if (s.fighter && s.volume != null) s.volume = s.volume.thickened(s.baseRadius * FIGHTER_HIT_HALF_HEIGHT);
		for (WeaponMount m : s.weapons) m.localPos.y = s.volume.topAt(m.localPos.x, m.localPos.z) + 1f;
	}

	private static WeaponMount mount(WeaponAPI w, HullVolume volume, ShipAPI ship) {
		WeaponSlotAPI slot = w.getSlot();
		if (slot == null || slot.isDecorative() || slot.isSystemSlot() || w.isDecorative()) return null;
		WeaponSpec3D ws = weaponSpec(w, ship);
		Vector2f loc = slot.getLocation(); // ship space: x forward, y left
		float x = -loc.y, z = -loc.x;
		float y = volume != null ? volume.topAt(x, z) + 1f : 0f;
		double a = Math.toRadians(slot.getAngle());
		Vec3 rest = new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a));
		WeaponMount m = new WeaponMount(slot.getId(), ws, new Vec3(x, y, z), rest, slot.getArc(), slot.isHardpoint());
		m.hostRef = w;
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

	private static WeaponSpec3D weaponSpec(WeaponAPI w, ShipAPI ship) {
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
			ws.ammoPerSecond = w.getAmmoPerSecond();
		}
		WeaponSpecAPI spec = w.getSpec();
		if (spec != null && spec.getAIHints() != null) {
			ws.pd = spec.getAIHints().contains(WeaponAPI.AIHints.PD) || spec.getAIHints().contains(WeaponAPI.AIHints.PD_ONLY)
					|| spec.getAIHints().contains(WeaponAPI.AIHints.PD_ALSO);
			ws.pdOnly = spec.getAIHints().contains(WeaponAPI.AIHints.PD_ONLY);
		}
		ws.supported = true;

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
			effect(ws, b.getEffectClassName());
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
			fillMissile(ws, (MissileSpecAPI) ps, ship);
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
			effect(ws, proj.getOnHitClassName());
			behaviour(ws, proj.getBehaviorSpec(), ship);
		} else {
			unsupported(ws, "unknown projectile type");
		}
		return ws;
	}

	/** Missile flight comes from the missile's own hull/engine spec, plus the firing ship's missile bonuses. */
	private static void fillMissile(WeaponSpec3D ws, MissileSpecAPI ms, ShipAPI ship) {
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
		ws.guided = ws.missileTurnRate > 1f;
		ws.projectileSpeed = ws.missileMaxSpeed;
		Color glow = ms.getGlowColor() != null ? ms.getGlowColor() : ms.getExplosionColor();
		if (glow != null) ws.missileGlow = rgb(glow);
		ws.fringeColor = ws.missileGlow;
		ws.missileModelSource = weaponSprite(hs.getSpriteName(), false);
		effect(ws, ms.getOnHitClassName());
		behaviour(ws, ms.getBehaviorSpec(), ship);
	}

	/**
	 * Vanilla weapon scripts: the ion/tachyon family (EMP arcs that pierce shields at high hard flux)
	 * has a 3D version; the rest are named in the log as not modelled.
	 */
	private static void effect(WeaponSpec3D ws, String className) {
		if (className == null || className.isEmpty()) return;
		String simple = className.substring(className.lastIndexOf('.') + 1);
		switch (simple) {
			case "IonCannonOnHitEffect":
			case "IonBeamEffect":
			case "TachyonLanceEffect":
				ws.empArcs = true;
				break;
			default:
				ws.unmodelledEffect = simple;
		}
	}

	/** Shot behaviours: proximity fuse (flak) and splitting missiles (MIRV) get 3D versions. */
	private static void behaviour(WeaponSpec3D ws, ShotBehaviorSpecAPI b, ShipAPI ship) {
		if (b == null || b.getBehavorString() == null) return;
		JSONObject p = b.getParams();
		if (p == null) p = new JSONObject();
		switch (b.getBehavorString()) {
			case "PROXIMITY_FUSE": {
				ws.proxRange = (float) p.optDouble("range", 30);
				JSONObject ex = p.optJSONObject("explosionSpec");
				ws.proxRadius = ex != null ? (float) ex.optDouble("radius", ws.proxRange * 1.5f) : ws.proxRange * 1.5f;
				ws.proxCore = ex != null ? (float) ex.optDouble("coreRadius", ws.proxRange) : ws.proxRange;
				break;
			}
			case "MIRV": {
				ws.mirvCount = Math.max(1, p.optInt("numShots", 3));
				ws.mirvSplitRange = (float) p.optDouble("splitRange", 400);
				ws.mirvSplitRangeRandom = (float) p.optDouble("splitRangeRange", 0);
				ws.mirvMinTime = (float) p.optDouble("minTimeToSplit", 0);
				ws.mirvArcDeg = (float) p.optDouble("arc", 20);
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
				h.projectileSpeed = (float) p.optDouble("spreadSpeed", 800);
				h.impact = (float) p.optDouble("impact", ws.impact);
				h.range = ws.mirvSplitRange * 2f + ws.mirvSplitRangeRandom;
				h.projLength = 30f;
				h.projWidth = 6f;
				h.coreColor = new float[] {1f, 1f, 1f};
				h.fringeColor = ws.missileGlow;
				ws.mirvWarhead = h;
				break;
			}
			default:
				if (ws.unmodelledEffect == null) ws.unmodelledEffect = b.getBehavorString();
		}
	}

	private static String describe(WeaponSpec3D ws) {
		if (!ws.supported) return "not fired (" + ws.unsupportedReason + ")";
		String pd = ws.pdOnly ? " [PD only]" : ws.pd ? " [PD]" : "";
		String ammo = ws.maxAmmo > 0 ? String.format(", ammo %d (+%.2f/s)", ws.maxAmmo, ws.ammoPerSecond) : "";
		ammo += ws.fluxPerSecond > 0f ? String.format(", flux %.0f/s", ws.fluxPerSecond) : String.format(", flux %.0f/shot", ws.fluxPerShot);
		if (ws.emp > 0f) ammo += String.format(", EMP %.0f%s%s", ws.emp, ws.kind == WeaponSpec3D.Kind.BEAM ? "/s" : "", ws.empArcs ? " (arcs through shields)" : "");
		if (ws.proxRange > 0f) ammo += String.format(", proximity fuse %.0f (blast %.0f)", ws.proxRange, ws.proxRadius);
		if (ws.mirvWarhead != null) ammo += String.format(", splits at %.0f into %d x %.0f %s", ws.mirvSplitRange, ws.mirvCount, ws.mirvWarhead.damage, ws.mirvWarhead.damageType);
		if (ws.unmodelledEffect != null) ammo += ", not modelled: " + ws.unmodelledEffect;
		switch (ws.kind) {
			case BEAM:
				return String.format("BEAM %s %.0f dps, range %.0f, charge %.2f/%.2fs, %s, cooldown %.2fs, width %.0f, turn %.0f deg/s%s%s",
						ws.damageType, ws.dps, ws.range, ws.chargeUp, ws.chargeDown,
						ws.burstBeam ? String.format("burst %.2fs", ws.burstDuration) : "continuous", ws.cooldown, ws.beamWidth, ws.turnRate, pd, ammo);
			case MISSILE:
				return String.format("MISSILE %s %.0f dmg, range %.0f, launch %.0f, speed %.0f, accel %.0f, turn %.0f deg/s, %s, flight %.1fs, hp %.0f, cooldown %.2fs%s%s",
						ws.damageType, ws.damage, ws.range, ws.launchSpeed, ws.missileMaxSpeed, ws.missileAccel, ws.missileTurnRate,
						ws.guided ? "guided" : "unguided", ws.flightTime, ws.missileHp, ws.cooldown, pd, ammo);
			default:
				return String.format("%s %.0f dmg, range %.0f, speed %.0f, cooldown %.2fs, burst %dx%.2fs, charge %.2fs, spread %.0f-%.0f, turn %.0f deg/s%s%s",
						ws.damageType, ws.damage, ws.range, ws.projectileSpeed, ws.cooldown, ws.burstSize, ws.burstDelay,
						ws.chargeTime, ws.minSpread, ws.maxSpread, ws.turnRate, pd, ammo);
		}
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
