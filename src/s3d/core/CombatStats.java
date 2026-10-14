package s3d.core;

/**
 * A ship's combat modifiers as vanilla's MutableShipStats hold them (officer skills, hullmods, D-mods,
 * CR), kept live by the host from the puppet every frame, plus the officer skills vanilla runs as
 * combat listeners, rebuilt here (their numbers are in {@link SkillRules}). Defaults change nothing
 * (sandbox targets, bench ships). "to..." = this ship's hits on others, "...Taken" = hits on this ship.
 */
public final class CombatStats {
	// ---- damage dealt ----
	/** Damage vs fighters, vs each hull size (a module counts as its station), vs missiles (Target Analysis, Point Defense). */
	public float toFighters = 1f, toFrigates = 1f, toDestroyers = 1f, toCruisers = 1f, toCapitals = 1f, toMissiles = 1f;
	/** Damage to shields, to hull (what gets past armor), to weapons and engines (Field Modulation, Damage Control, Target Analysis). */
	public float toShields = 1f, toHull = 1f, toWeapons = 1f, toEngines = 1f;
	/** Hit strength against armor, a vanilla StatBonus: (h + h x percent / 100 + flat) x mult, at least 1 (Impact Mitigation). */
	public float hitStrengthPercent, hitStrengthFlat, hitStrengthMult = 1f;

	// ---- damage taken ----
	/** Armor counted for damage reduction only, a StatBonus like hit strength (Polarized Armor, Ablative Armor). */
	public float effArmorPercent, effArmorFlat, effArmorMult = 1f;
	/** Least armor a hit meets (share of the rating) and the most armor takes off a hit; NaN = the game's settings. */
	public float minArmorFraction = Float.NaN, maxArmorReduction = Float.NaN;
	/** Kinetic damage to armor (Impact Mitigation). */
	public float kineticArmorTaken = 1f;
	/** Hull and armor damage by delivery and by damage type (hullmods). */
	public float beamTaken = 1f, missileTaken = 1f, projectileTaken = 1f;
	public float energyTaken = 1f, kineticTaken = 1f, heTaken = 1f, fragTaken = 1f;
	/** Shield damage likewise (the general shield multiplier is in ShieldFlux.fluxPerDamage). */
	public float beamShieldTaken = 1f, missileShieldTaken = 1f, projectileShieldTaken = 1f;
	public float energyShieldTaken = 1f, kineticShieldTaken = 1f, heShieldTaken = 1f, fragShieldTaken = 1f;
	/** EMP reaching this ship's weapons and engines (Impact Mitigation). */
	public float weaponTaken = 1f, engineTaken = 1f;

	// ---- gunnery ----
	/** Weapon spread (vanilla recoil): min/max spread, buildup per shot (small weapons: also the second), recovery (Gunnery Implants). */
	public float recoilMaxMult = 1f, recoilPerShotMult = 1f, recoilPerShotSmallMult = 1f, recoilDecayMult = 1f;
	/** Autofire target leading, 0..1 (Gunnery Implants: 1); PD weapons lead perfectly when pdBestLeading (Integrated Point Defense AI). */
	public float autofireAccuracy;
	public boolean pdBestLeading;

	// ---- skills vanilla runs as combat listeners ----
	/** Energy Weapon Mastery: energy weapons deal up to +magnitude (0.3 = 30%) at close range as flux rises past minLevel. */
	public boolean energyMastery;
	public float energyFluxMagnitude, energyFluxMinLevel;
	/** Ranged Specialization: projectiles and beams deal more the further they've come. */
	public boolean rangedSpec;
	/** Damage Control (elite): at most once every few seconds, one hit's hull damage past a threshold is cut. */
	public boolean damageControl;
	/** Seconds (ship time) since Damage Control last took the edge off a hit. */
	public float sinceDamageControl = Float.MAX_VALUE;
	/** Combat Endurance (elite): the hull slowly regrows, up to a total per battle. */
	public boolean regen;
	public float regenDone;

	/** Damage multiplier of this ship's hits on target, by what it is (vanilla's damage-to-size stats). */
	public float vsTarget(Ship3D target) {
		if (target.fighter) return toFighters;
		switch (target.root().sizeClass) {
			case "capital": return toCapitals;
			case "cruiser": return toCruisers;
			case "destroyer": return toDestroyers;
			default: return toFrigates;
		}
	}

	/** Hit strength after this (attacking) ship's bonus. */
	public float hitStrength(float h) {
		return Math.max(1f, (h + h * hitStrengthPercent * 0.01f + hitStrengthFlat) * hitStrengthMult);
	}

	/** Armor as this (target) ship's damage reduction counts it. */
	public float effectiveArmor(float armor) {
		return Math.max(0f, (armor + armor * effArmorPercent * 0.01f + effArmorFlat) * effArmorMult);
	}

	/** Hull and armor damage multiplier for a hit by w (an arc, or a shot without a weapon kind, counts as a projectile). */
	public float hullTaken(WeaponSpec3D w, boolean arc) {
		WeaponSpec3D.Kind k = arc ? WeaponSpec3D.Kind.PROJECTILE : w.kind;
		return (k == WeaponSpec3D.Kind.BEAM ? beamTaken : k == WeaponSpec3D.Kind.MISSILE ? missileTaken : projectileTaken) * byType(w.damageType, false);
	}

	/** Shield damage multiplier for a hit by w. */
	public float shieldTaken(WeaponSpec3D w, boolean soft) {
		WeaponSpec3D.Kind k = soft ? WeaponSpec3D.Kind.BEAM : w.kind;
		return (k == WeaponSpec3D.Kind.BEAM ? beamShieldTaken : k == WeaponSpec3D.Kind.MISSILE ? missileShieldTaken : projectileShieldTaken) * byType(w.damageType, true);
	}

	private float byType(String type, boolean shield) {
		if (type == null) return 1f;
		switch (type) {
			case "ENERGY": return shield ? energyShieldTaken : energyTaken;
			case "KINETIC": return shield ? kineticShieldTaken : kineticTaken;
			case "HIGH_EXPLOSIVE": return shield ? heShieldTaken : heTaken;
			case "FRAGMENTATION": return shield ? fragShieldTaken : fragTaken;
			default: return 1f;
		}
	}

	/**
	 * How much short of the true lead this ship's autofire aims: 1 = on it, 2 = half of it (vanilla
	 * with no leading skill: unskilled = 2). The weapon's own accuracy bonus and PD leading take it toward 1.
	 */
	public float leadFactor(WeaponSpec3D w, float unskilled) {
		float acc = Math.max(0f, Math.min(1f, autofireAccuracy));
		float f = 1f + (unskilled - 1f) * (1f - acc);
		if (pdBestLeading && w.pd && !w.strike) return 1f;
		return Math.max(1f, f - w.autofireAccBonus);
	}

	/** Energy Weapon Mastery's multiplier now, from this ship's flux level (vanilla: linear above the minimum level). */
	public float energyFluxMult(ShieldFlux flux) {
		if (!energyMastery || energyFluxMagnitude <= 0f || flux == null || flux.maxFlux <= 0f) return 1f;
		float level = flux.flux / flux.maxFlux;
		if (level <= energyFluxMinLevel) return 1f;
		return 1f + energyFluxMagnitude * (level - energyFluxMinLevel) / Math.max(1e-3f, 1f - energyFluxMinLevel);
	}

	/**
	 * Damage Control: cuts this hit's hull damage past the threshold, if the skill is ready, and starts
	 * its wait. Vanilla spends it on the first hull or armor hit once ready, however small.
	 */
	public float damageControl(float hullDamage, SkillRules r) {
		if (!damageControl || sinceDamageControl <= r.dcProcSeconds) return hullDamage;
		sinceDamageControl = 0f;
		if (hullDamage <= r.dcThreshold) return hullDamage;
		return r.dcThreshold + (hullDamage - r.dcThreshold) * (1f - r.dcReductionPercent * 0.01f);
	}

	/** Per frame on the ship's own clock: Damage Control's wait, Combat Endurance's regrowth. */
	public void step(Ship3D s, float dt, SkillRules r) {
		if (dt <= 0f) return;
		if (damageControl && sinceDamageControl < Float.MAX_VALUE / 2f) sinceDamageControl += dt;
		if (!regen || s.destroyed || s.hullMax <= 0f) return;
		float limit = Math.max(r.ceMaxPoints, r.ceMaxFraction * s.hullMax);
		float upTo = s.hullMax * r.ceMaxLevel;
		if (regenDone >= limit || s.hull >= upTo) return;
		float add = Math.min(limit - regenDone, s.hullMax * r.ceRate * dt);
		add = Math.min(add, upTo - s.hull);
		if (add <= 0f) return;
		s.hull += add;
		regenDone += add;
	}
}
