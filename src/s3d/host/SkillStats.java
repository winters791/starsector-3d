package s3d.host;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.skills.CombatEndurance;
import com.fs.starfarer.api.impl.campaign.skills.DamageControl;
import com.fs.starfarer.api.impl.campaign.skills.EnergyWeaponMastery;
import com.fs.starfarer.api.impl.campaign.skills.PolarizedArmor;
import com.fs.starfarer.api.impl.campaign.skills.RangedSpecialization;

import s3d.core.CombatStats;
import s3d.core.ShieldFlux;
import s3d.core.Ship3D;
import s3d.core.SkillRules;

/**
 * Officer skills and hullmods in 3D. Vanilla keeps their combat effects in each ship's
 * MutableShipStats; this copies them from the puppet into the twin's CombatStats every frame, so
 * stats that change in battle follow along (Polarized Armor's flux-based armor is vanilla's own
 * listener updating the puppet's stats from the flux we write back). Skills that vanilla runs as
 * damage listeners can't reach 3D hits, so they are spotted by their listener class and rebuilt in
 * core (CombatStats, Combat), with vanilla's public constants copied into SkillRules at battle start.
 */
final class SkillStats {
	private static final Logger log = Global.getLogger(SkillStats.class);
	/** Ships whose live effective-armor bonus (Polarized Armor) has been logged once. */
	private static final Set<Ship3D> LOGGED_ARMOR = Collections.newSetFromMap(new WeakHashMap<>());

	private SkillStats() {}

	/** Vanilla's numbers for the listener skills (public static, so other mods may have changed them). */
	static void readRules(SkillRules r) {
		try {
			r.ewmMinRange = EnergyWeaponMastery.MIN_RANGE;
			r.ewmMaxRange = EnergyWeaponMastery.MAX_RANGE;
			r.rsMinRange = RangedSpecialization.MIN_RANGE;
			r.rsMaxRange = RangedSpecialization.MAX_RANGE;
			r.rsMaxPercent = RangedSpecialization.MAX_CHANCE_PERCENT;
			r.rsCritPercent = RangedSpecialization.CRIT_DAMAGE_BONUS_PERCENT;
			r.rsCrits = RangedSpecialization.CRITS;
			r.dcProcSeconds = DamageControl.SECONDS_PER_PROC;
			r.dcThreshold = DamageControl.ELITE_DAMAGE_THRESHOLD;
			r.dcReductionPercent = DamageControl.ELITE_DAMAGE_REDUCTION_PERCENT;
			r.ceRate = CombatEndurance.REGEN_RATE;
			r.ceMaxLevel = CombatEndurance.MAX_REGEN_LEVEL;
			r.ceMaxPoints = CombatEndurance.TOTAL_REGEN_MAX_POINTS;
			r.ceMaxFraction = CombatEndurance.TOTAL_REGEN_MAX_HULL_FRACTION;
		} catch (Throwable t) {
			log.warn("S3D: can't read vanilla's skill constants, using defaults", t);
		}
	}

	/** First read when the twin is made: everything, plus a log line of what isn't vanilla-default. */
	static void attach(ShipAPI ship, Ship3D s) {
		refresh(ship, s, true);
		MutableShipStatsAPI st = ship.getMutableStats();
		boolean armorRules = st != null && (st.getMinArmorFraction().getModifiedValue() != st.getMinArmorFraction().getBaseValue()
				|| st.getMaxArmorDamageReduction().getModifiedValue() != st.getMaxArmorDamageReduction().getBaseValue());
		String d = describe(s.stats, armorRules);
		if (!d.isEmpty() && !ship.isFighter()) log.info("S3D: skills/hullmods in 3D for " + s.name + ":" + d);
	}

	/**
	 * Copies the live stats. listeners: also look again for the listener skills (cheap enough at a
	 * few times a second, and some are added after the ship is made).
	 */
	static void refresh(ShipAPI ship, Ship3D s, boolean listeners) {
		MutableShipStatsAPI st = ship.getMutableStats();
		if (st == null) return;
		CombatStats c = s.stats;
		c.toFighters = st.getDamageToFighters().getModifiedValue();
		c.toFrigates = st.getDamageToFrigates().getModifiedValue();
		c.toDestroyers = st.getDamageToDestroyers().getModifiedValue();
		c.toCruisers = st.getDamageToCruisers().getModifiedValue();
		c.toCapitals = st.getDamageToCapital().getModifiedValue();
		c.toMissiles = st.getDamageToMissiles().getModifiedValue();
		c.toShields = st.getDamageToTargetShieldsMult().getModifiedValue();
		c.toHull = st.getDamageToTargetHullMult().getModifiedValue();
		c.toWeapons = st.getDamageToTargetWeaponsMult().getModifiedValue();
		c.toEngines = st.getDamageToTargetEnginesMult().getModifiedValue();
		StatBonus hs = st.getHitStrengthBonus();
		c.hitStrengthPercent = hs.getPercentMod();
		c.hitStrengthFlat = hs.getFlatBonus();
		c.hitStrengthMult = hs.getMult();

		StatBonus ea = st.getEffectiveArmorBonus();
		c.effArmorPercent = ea.getPercentMod();
		c.effArmorFlat = ea.getFlatBonus();
		c.effArmorMult = ea.getMult();
		c.minArmorFraction = st.getMinArmorFraction().getModifiedValue(); // starts at the game's setting
		c.maxArmorReduction = st.getMaxArmorDamageReduction().getModifiedValue();
		c.kineticArmorTaken = st.getKineticArmorDamageTakenMult().getModifiedValue();
		c.beamTaken = st.getBeamDamageTakenMult().getModifiedValue();
		c.missileTaken = st.getMissileDamageTakenMult().getModifiedValue();
		c.projectileTaken = st.getProjectileDamageTakenMult().getModifiedValue();
		c.energyTaken = st.getEnergyDamageTakenMult().getModifiedValue();
		c.kineticTaken = st.getKineticDamageTakenMult().getModifiedValue();
		c.heTaken = st.getHighExplosiveDamageTakenMult().getModifiedValue();
		c.fragTaken = st.getFragmentationDamageTakenMult().getModifiedValue();
		c.beamShieldTaken = st.getBeamShieldDamageTakenMult().getModifiedValue();
		c.missileShieldTaken = st.getMissileShieldDamageTakenMult().getModifiedValue();
		c.projectileShieldTaken = st.getProjectileShieldDamageTakenMult().getModifiedValue();
		c.energyShieldTaken = st.getEnergyShieldDamageTakenMult().getModifiedValue();
		c.kineticShieldTaken = st.getKineticShieldDamageTakenMult().getModifiedValue();
		c.heShieldTaken = st.getHighExplosiveShieldDamageTakenMult().getModifiedValue();
		c.fragShieldTaken = st.getFragmentationShieldDamageTakenMult().getModifiedValue();
		c.weaponTaken = st.getWeaponDamageTakenMult().getModifiedValue();
		c.engineTaken = st.getEngineDamageTakenMult().getModifiedValue();
		s.armorTakenMult = st.getArmorDamageTakenMult().getModifiedValue();
		s.hullTakenMult = st.getHullDamageTakenMult().getModifiedValue();
		s.empTakenMult = st.getEmpDamageTakenMult().getModifiedValue();
		ShieldFlux f = s.flux;
		if (f != null && f.type != ShieldFlux.Type.NONE && ship.getShield() != null) f.fluxPerDamage = ShipBridge.shieldFluxPerDamage(ship);

		c.recoilMaxMult = st.getMaxRecoilMult().getModifiedValue();
		c.recoilPerShotMult = st.getRecoilPerShotMult().getModifiedValue();
		c.recoilPerShotSmallMult = st.getRecoilPerShotMultSmallWeaponsOnly().getModifiedValue();
		c.recoilDecayMult = st.getRecoilDecayMult().getModifiedValue();
		c.autofireAccuracy = st.getAutofireAimAccuracy().getModifiedValue();
		c.pdBestLeading = st.getDynamic().getValue(Stats.PD_BEST_TARGET_LEADING, 0f) >= 1f;
		c.energyFluxMagnitude = st.getEnergyWeaponFluxBasedBonusDamageMagnitude().getModifiedValue();
		c.energyFluxMinLevel = st.getEnergyWeaponFluxBasedBonusDamageMinLevel().getModifiedValue();

		if (listeners) {
			c.energyMastery = ship.hasListenerOfClass(EnergyWeaponMastery.EWMDamageDealtMod.class);
			c.rangedSpec = ship.hasListenerOfClass(RangedSpecialization.RangedSpecDamageDealtMod.class);
			c.damageControl = ship.hasListenerOfClass(DamageControl.DamageControlDamageTakenMod.class);
			c.regen = ship.hasListenerOfClass(CombatEndurance.CombatEnduranceRegen.class);
		}
		if (c.effArmorPercent > 0.5f && !ship.isFighter() && LOGGED_ARMOR.add(s)) {
			log.info(String.format("S3D: %s effective armor now +%.0f%% for damage reduction (%s, %.0f%% hard flux)", s.name, c.effArmorPercent,
					ship.hasListenerOfClass(PolarizedArmor.PolarizedArmorEffectMod.class) ? "Polarized Armor" : "a vanilla stat",
					f != null && f.maxFlux > 0f ? 100f * f.hardFlux / f.maxFlux : 0f));
		}
	}

	/** What this ship's stats change from vanilla's defaults, for the log ("" if nothing). */
	static String describe(CombatStats c, boolean armorRules) {
		StringBuilder b = new StringBuilder();
		if (c.toFrigates != 1f || c.toDestroyers != 1f || c.toCruisers != 1f || c.toCapitals != 1f)
			b.append(String.format(" vs frigate/destroyer/cruiser/capital x%.2f/%.2f/%.2f/%.2f,", c.toFrigates, c.toDestroyers, c.toCruisers, c.toCapitals));
		mult(b, "vs fighters", c.toFighters);
		mult(b, "vs missiles", c.toMissiles);
		mult(b, "to shields", c.toShields);
		mult(b, "to hull", c.toHull);
		mult(b, "to weapons", c.toWeapons);
		mult(b, "to engines", c.toEngines);
		if (c.hitStrengthPercent != 0f || c.hitStrengthFlat != 0f || c.hitStrengthMult != 1f) b.append(String.format(" hit strength %+.0f%%,", c.hitStrengthPercent));
		if (c.effArmorPercent != 0f || c.effArmorFlat != 0f || c.effArmorMult != 1f) b.append(String.format(" effective armor %+.0f%% x%.2f,", c.effArmorPercent, c.effArmorMult));
		if (armorRules) b.append(String.format(" least armor %.0f%%, most reduction %.0f%%,", 100f * c.minArmorFraction, 100f * c.maxArmorReduction));
		mult(b, "kinetic armor taken", c.kineticArmorTaken);
		mult(b, "beam taken", c.beamTaken);
		mult(b, "energy taken", c.energyTaken);
		mult(b, "weapon damage taken", c.weaponTaken);
		mult(b, "engine damage taken", c.engineTaken);
		mult(b, "recoil", c.recoilPerShotMult);
		if (c.autofireAccuracy != 0f) b.append(String.format(" autofire leading %.2f,", c.autofireAccuracy));
		if (c.pdBestLeading) b.append(" PD leads perfectly,");
		if (c.energyMastery) b.append(String.format(" Energy Weapon Mastery (+%.0f%% at full flux),", 100f * c.energyFluxMagnitude));
		if (c.rangedSpec) b.append(" Ranged Specialization,");
		if (c.damageControl) b.append(" Damage Control (elite),");
		if (c.regen) b.append(" Combat Endurance regen,");
		String out = b.toString();
		return out.endsWith(",") ? out.substring(0, out.length() - 1) : out;
	}

	private static void mult(StringBuilder b, String what, float v) {
		if (Math.abs(v - 1f) > 1e-4f) b.append(String.format(" %s x%.2f,", what, v));
	}
}
