package s3d.host;

import java.awt.Color;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.combat.RealityDisruptorChargeGlow;
import com.fs.starfarer.api.impl.combat.RiftBeamEffect;
import com.fs.starfarer.api.impl.combat.RiftCascadeEffect;
import com.fs.starfarer.api.impl.combat.RiftLanceEffect;
import com.fs.starfarer.api.impl.combat.dweller.AbyssalGlareEffect;
import com.fs.starfarer.api.impl.combat.dweller.AssayingRiftEffect;
import com.fs.starfarer.api.impl.combat.dweller.DwellerHullmod;
import com.fs.starfarer.api.impl.combat.dweller.DwellerShroud;
import com.fs.starfarer.api.impl.combat.dweller.VortexLauncherEffect;
import com.fs.starfarer.api.impl.combat.dweller.InimicalEmanationOnFireEffect;
import com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect;
import com.fs.starfarer.api.impl.combat.threat.BaseFragmentMissileEffect;
import com.fs.starfarer.api.impl.combat.threat.DevouringSwarmMissileEffect;
import com.fs.starfarer.api.impl.combat.threat.FragmentSwarmHullmod;
import com.fs.starfarer.api.impl.combat.threat.FragmentWeapon;
import com.fs.starfarer.api.impl.combat.threat.KineticFragmentMissileEffect;
import com.fs.starfarer.api.impl.combat.threat.NeutronTorpedoOnFireEffect;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.SwarmLauncherEffect;
import com.fs.starfarer.api.impl.combat.threat.VoltaicCannonEffect;
import com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import s3d.core.ExoticWeapons;
import s3d.core.FragmentSwarm;
import s3d.core.Ship3D;
import s3d.core.SwarmLook;
import s3d.core.Tuning;
import s3d.core.WeaponSpec3D;

/**
 * Weapon scripts batch 2, host side: what the 3D versions of the Omega, Threat and Dweller weapon
 * scripts need, read from vanilla (the scripts' public constants, the minelayer weapons their rifts
 * come from, the missiles' own data) onto WeaponSpec3D; fragment swarms and Dweller shrouds onto
 * Ship3D. Numbers that are inline in vanilla's code come from the tuning file (weaponFx2).
 */
final class ExoticBridge {
	private static final Logger log = Global.getLogger(ExoticBridge.class);

	private ExoticBridge() {}

	/** A batch-2 script by its simple class name: its 3D version set on ws (or noted as visual only). False if it isn't one. */
	static boolean effect(WeaponSpec3D ws, String simple, ShipAPI ship, Tuning t) {
		float energy = ship.getMutableStats().getEnergyWeaponDamageMult().getModifiedValue();
		switch (simple) {
			// ---- Omega ----
			case "RiftBeamEffect":
				if (!mineRift(ws, "riftbeam_minelayer", energy)) return false;
				ws.riftMode = WeaponSpec3D.RiftMode.SCATTER;
				ws.riftInterval = t.fx2RiftBeamInterval;
				ws.riftReach = RiftBeamEffect.TARGET_RANGE;
				ws.riftSpread = RiftBeamEffect.RIFT_RANGE;
				note(ws, String.format("a %.0f rift by the beam every ~%.1fs (on missiles/fighters near it first)", ws.rift.damage, ws.riftInterval));
				return true;
			case "RiftCascadeEffect":
				if (!mineRift(ws, RiftCascadeEffect.RIFTCASCADE_MINELAYER, energy)) return false;
				ws.riftMode = WeaponSpec3D.RiftMode.CASCADE;
				ws.riftInterval = RiftCascadeEffect.SPAWN_INTERVAL;
				ws.riftSpread = RiftCascadeEffect.SPAWN_SPACING;
				ws.riftReach = RiftCascadeEffect.UNUSED_RANGE_PER_SPAWN;
				ws.riftMax = RiftCascadeEffect.MAX_RIFTS;
				note(ws, String.format("up to %d rifts of ~%.0f walk round what it hits (%.1fs fuse%s)", ws.riftMax, ws.rift.damage, ws.riftDelay,
						ws.rift.blastFF ? ", friendly fire" : ""));
				return true;
			case "RiftLanceEffect":
				if (RiftLanceEffect.MINE_SPAWN_CHANCE > 0f && mineRift(ws, "riftlance_minelayer", energy)) {
					ws.riftMode = WeaponSpec3D.RiftMode.LANCE;
					ws.riftChance = RiftLanceEffect.MINE_SPAWN_CHANCE;
					note(ws, String.format("%.0f%% chance of a %.0f rift where it lands", ws.riftChance * 100f, ws.rift.damage));
				} else {
					visual(ws, simple); // vanilla's mine chance is 0: the darkening is all it does
				}
				return true;
			case "RiftTorpedoEffect":
				ws.impactRift = 40f;
				ws.ignoresFlares = true;
				note(ws, "ignores flares");
				return true;
			case "RealityDisruptorEffect":
				ws.orb = true;
				ws.guided = false;
				ws.orbArcRange = RealityDisruptorChargeGlow.MAX_ARC_RANGE;
				ws.orbArcInterval = t.fx2DisruptorArcInterval;
				ws.orbArcDelay = t.fx2DisruptorArcDelay;
				ws.slowRepairMult = RealityDisruptorChargeGlow.REPAIR_RATE_MULT;
				ws.slowRepairTime = RealityDisruptorChargeGlow.REPAIR_RATE_DEBUFF_DUR;
				note(ws, String.format("orb that arcs to anything within %.0f every ~%.2fs; repairs x%.1f for %.0fs", ws.orbArcRange, ws.orbArcInterval,
						ws.slowRepairMult, ws.slowRepairTime));
				return true;
			// ---- Threat ----
			case "VoltaicDischargeOnFireEffect":
				ws.arcGunConeDeg = VoltaicDischargeOnFireEffect.EXTRA_ARC;
				ws.arcFromShip = true;
				ws.arcColor = rgb(VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR);
				ws.arcImpactSound = "voltaic_discharge_emp_impact";
				note(ws, String.format("arcs from the fragment swarm to the nearest enemy (%.0f deg)", ws.arcGunConeDeg));
				return true;
			case "VoltaicCannonEffect": {
				VoltaicCannonEffect v = new VoltaicCannonEffect();
				ws.empArcsMin = Math.max(0, v.MIN_NUM_ARCS);
				ws.empArcsMax = Math.max(ws.empArcsMin, v.MAX_NUM_ARCS);
				note(ws, String.format("%d-%d EMP arcs on a hit (through shields by hard flux)", ws.empArcsMin, ws.empArcsMax));
				return true;
			}
			case "VoidblasterEffect":
				ws.softOnShields = true;
				note(ws, "soft flux on shields");
				return true;
			case "NeutronTorpedoOnFireEffect":
				ws.launchVelMult = NeutronTorpedoOnFireEffect.VEL_MULT;
				note(ws, String.format("launched at x%.2f speed", ws.launchVelMult));
				return true;
			case "KineticFragmentMissileEffect": {
				WeaponSpec3D f = new WeaponSpec3D();
				f.id = f.name = ws.name + " fragment";
				f.type = ws.type;
				f.supported = true;
				f.damage = KineticFragmentMissileEffect.OTHER_FRAGMENT_DAMAGE;
				damageType(f, DamageType.KINETIC);
				ws.fragmentStrike = f;
				return true; // how many strike: weapon()
			}
			case "SeekerFragmentMissileEffect":
			case "UnstableFragmentMissileEffect":
				return true; // fragments: weapon(); otherwise a plain missile (Seeker's blast: missile())
			case "DevouringSwarmMissileEffect":
				ws.dotTicks = Math.max(1, DevouringSwarmMissileEffect.NUM_TICKS);
				ws.dotRefundChance = t.fx2DevouringRefundChance;
				note(ws, String.format("hull hits eat in over %d ticks (%.0f%% chance each of a fragment back)", ws.dotTicks, ws.dotRefundChance * 100f));
				return true;
			case "SwarmLauncherEffect": {
				// Each shot is an attack swarm (a fighter made of 50 of the ship's fragments); up to its wing size out,
				// then it waits until one is left (vanilla). Ammo never runs out on Threat ships.
				String wing = SwarmLauncherEffect.WING_IDS.getOrDefault(ws.id, SwarmLauncherEffect.ATTACK_SWARM_WING);
				ws.launchWing = wing;
				ws.launchBehaviour = s3d.core.Ship3D.Behaviour.SWARM;
				Integer frags = SwarmLauncherEffect.FRAGMENT_NUM.get(wing), size = SwarmLauncherEffect.WING_SIZE.get(wing);
				ws.fragmentsNeeded = ws.fragmentsSpent = frags != null ? frags : 50;
				ws.launchMaxOut = Math.round(ship.getMutableStats().getDynamic().getValue(Stats.SWARM_LAUNCHER_WING_SIZE_MOD, size != null ? size : 4));
				if (ship.getVariant() != null && ship.getVariant().hasHullMod(HullMods.THREAT_HULLMOD)) ws.maxAmmo = 0;
				note(ws, String.format("launches attack swarms (%d fragments each, %d out at once)", ws.fragmentsNeeded, ws.launchMaxOut));
				return true;
			}
			case "VortexLauncherEffect":
				// Each shot is a shrouded vortex: it spins after an enemy ship, decays, and blows up.
				ws.launchWing = VortexLauncherEffect.VORTEX_WING;
				ws.launchBehaviour = s3d.core.Ship3D.Behaviour.VORTEX;
				ws.launchArcDeg = VortexLauncherEffect.LAUNCH_ARC;
				note(ws, "launches shrouded vortices");
				return true;
			// ---- Dweller ----
			case "AbyssalGlareEffect":
				ws.rift = blast(ws.name + " rift", AbyssalGlareEffect.RIFT_DAMAGE, 0f, DamageType.ENERGY, t.fx2GlareRadius, t.fx2GlareCore, true,
						"abyssal_glare_explosion", ExoticWeapons.LIGHTNING_COLOR);
				ws.riftMode = WeaponSpec3D.RiftMode.GLARE;
				ws.riftInterval = t.fx2GlareInterval;
				ws.riftChance = t.fx2GlareChance;
				ws.riftMaxGap = t.fx2GlareMaxGap;
				ws.riftDamageMult = AbyssalGlareEffect.DAMAGE_MULT_NORMAL_WEAPON; // Darkened Gaze (full damage) isn't in 3D
				note(ws, String.format("a %.0f rift where it lands at least every %.1fs (friendly fire)", ws.rift.damage * ws.riftDamageMult, ws.riftMaxGap));
				return true;
			case "AssayingRiftEffect":
				ws.riftHeal = AssayingRiftEffect.HEAL_AMOUNT;
				ws.riftCap = ship.getHullSpec().hasTag(Tags.DWELLER) ? 0 : AssayingRiftEffect.MAX_RIFTS;
				ws.impactRift = 15f;
				ws.ignoresFlares = true;
				note(ws, String.format("a hull hit heals a damaged friendly Dweller %.0f%s", ws.riftHeal, ws.riftCap > 0 ? ", at most " + ws.riftCap + " out" : ""));
				return true;
			case "InimicalEmanationOnFireEffect":
				ws.arcGunConeDeg = InimicalEmanationOnFireEffect.EXTRA_ARC;
				ws.arcFromShip = true;
				ws.arcHitsPhased = true;
				ws.slowRepairMult = RealityDisruptorChargeGlow.REPAIR_RATE_MULT;
				ws.slowRepairTime = InimicalEmanationOnFireEffect.REPAIR_RATE_DEBUFF_DUR;
				if (DwellerShroud.SHROUD_GLOW_COLOR != null) ws.arcColor = rgb(DwellerShroud.SHROUD_GLOW_COLOR);
				ws.arcImpactSound = "inimical_emanation_impact";
				note(ws, String.format("arcs from the shroud (%.0f deg, reaches phased ships); repairs x%.1f for %.0fs", ws.arcGunConeDeg, ws.slowRepairMult,
						ws.slowRepairTime));
				return true;
			case "RiftLightningEffect":
				if (!mineRift(ws, "rift_lightning_minelayer", energy)) return false;
				ws.riftMode = WeaponSpec3D.RiftMode.LIGHTNING;
				ws.riftSpeed = RiftLightningEffect.RIFT_LIGHTNING_SPEED;
				note(ws, String.format("lightning: a %.0f rift (%.0f EMP) where it strikes", ws.rift.damage, ws.rift.emp));
				return true;
			case "PseudoparticleJetEffect":
				visual(ws, simple);
				return true;
			default:
				return false;
		}
	}

	/** Fragment weapons (any script that says it is one, mods' too): fragments needed to fire, and spent (fragment missiles are made of them). */
	static void weapon(WeaponSpec3D ws, WeaponAPI w) {
		Object p = w.getEffectPlugin();
		if (!(p instanceof FragmentWeapon)) return;
		int n = Math.max(0, ((FragmentWeapon) p).getNumFragmentsToFire());
		ws.fragmentsNeeded = n;
		ws.fragmentsSpent = p instanceof BaseFragmentMissileEffect ? n : 0;
		if (ws.fragmentStrike != null) ws.fragmentStrikes = Math.max(0, n - 1);
		note(ws, ws.fragmentsSpent > 0 ? String.format("uses %d fragments", n) : String.format("needs %d fragments", n));
		if (ws.fragmentStrikes > 0) note(ws, String.format("%d more fragments strike with it for %.0f %s each", ws.fragmentStrikes,
				ws.fragmentStrike.damage, ws.fragmentStrike.damageType));
	}

	/**
	 * Missile data (after its scripts and behaviour): decoy flares, fizzling at range, flying through
	 * fighters/missiles, and the area blast of missiles that have one.
	 */
	static void missile(WeaponSpec3D ws, MissileSpecAPI ms) {
		String type = ms.getTypeString();
		if ("FLARE".equals(type)) {
			ws.flareLauncher = true;
			ws.unmodelledEffect = remove(ws.unmodelledEffect, "CUSTOM behaviour");
			note(ws, "decoy flares");
		}
		ws.passFighters = ms.isPassThroughFighters();
		ws.passMissiles = ms.isPassThroughMissiles();
		ws.fizzleAtRange = ms.isFizzleOnReachingWeaponRange();
		if (ws.flareLauncher || ws.orb || ws.dem != null || ws.mine) return;
		DamagingExplosionSpec ex = ms.getExplosionSpec();
		JSONObject bj = ms.getBehaviorJSON(), bx = bj != null ? bj.optJSONObject("explosionSpec") : null;
		if (ex != null && ex.getRadius() > 0f && !(ws.proxRange > 0f)) {
			ws.impactBlast = blast(ws.name + " blast", ws.damage, 0f, null, ex.getRadius(), ex.getCoreRadius(), friendlyFire(ex.getCollisionClass()),
					ws.impactRift > 0f ? "rifttorpedo_explosion" : "", ws.missileGlow);
		} else if (ws.proxRange <= 0f && ws.proxRadius > 0f) {
			// A proximity fuse that never fuses (Hungering Rift: range -10000): it blows up on contact.
			ws.impactBlast = blast(ws.name + " blast", ws.damage, 0f, null, ws.proxRadius, ws.proxCore,
					bx != null && friendlyFire(bx.optString("collisionClass", "")), ws.impactRift > 0f ? "assaying_rift_explosion" : "", ws.missileGlow);
		}
		if (ws.impactBlast != null) {
			WeaponSpec3D b = ws.impactBlast;
			b.damageType = ws.damageType;
			b.shieldMult = ws.shieldMult;
			b.armorMult = ws.armorMult;
			b.hullMult = ws.hullMult;
			note(ws, String.format("blast %.0f (full within %.0f)%s", b.proxRadius, b.proxCore, b.blastFF ? ", friendly fire" : ""));
		}
	}

	/** Shots that fly through fighters or missiles (Voltaic Cannon). */
	static void projectile(WeaponSpec3D ws, ProjectileSpecAPI proj) {
		ws.passFighters = proj.isPassThroughFighters();
		ws.passMissiles = proj.isPassThroughMissiles();
	}

	/**
	 * Threat fragment swarm (vanilla size and regrowth by hull size, with the ship's swarm stat
	 * modifiers; a fighter's swarm is its wing's and doesn't regrow) and whether Hungering Rifts can heal
	 * it (shrouded Dweller ships). Returns a line for the log, or "".
	 */
	static String ship(Ship3D s, ShipAPI ship) {
		StringBuilder sb = new StringBuilder();
		try {
			if (hasFragmentSwarm(ship)) {
				int size;
				float regen;
				if (ship.isFighter()) {
					String wing = ship.getWing() != null ? ship.getWing().getWingId() : null;
					Integer n = wing != null ? SwarmLauncherEffect.FRAGMENT_NUM.get(wing) : null;
					size = n != null ? n : 50;
					regen = 0f;
				} else {
					size = (int) ship.getMutableStats().getDynamic().getValue(Stats.FRAGMENT_SWARM_SIZE_MOD,
							FragmentSwarmHullmod.getBaseSwarmSize(ship.getHullSize()));
					regen = FragmentSwarmHullmod.getBaseSwarmRespawnRateMult(ship.getHullSize())
							* ship.getMutableStats().getDynamic().getValue(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT);
				}
				s.fragments = new FragmentSwarm(size, regen);
				sb.append(String.format("%n  fragment swarm %d (+%.1f/s)", size, regen));
			}
			if (shrouded(ship)) {
				s.shrouded = true;
				s.riftHealMult = Math.max(0f, AssayingRiftEffect.getHealMult(ship));
				sb.append(String.format("%n  shrouded: Hungering Rift heals x%.2f", s.riftHealMult));
			}
		} catch (Throwable e) {
			log.warn("S3D: couldn't read " + s.name + "'s fragment swarm / shroud (" + e + ")");
		}
		return sb.toString();
	}

	/**
	 * The ship's swarm / shroud look from vanilla's live effect (its RoilingSwarm / DwellerShroud
	 * parameters, which differ by hull). False while the effect doesn't exist yet (vanilla makes it on
	 * the ship's first combat frame): try again later; the renderer uses vanilla's defaults meanwhile.
	 */
	static boolean swarmLook(Ship3D s, ShipAPI ship) {
		try {
			if (s.shrouded) {
				DwellerShroud shroud = DwellerShroud.getShroudFor(ship);
				if (shroud == null) return false;
				SwarmLook l = SwarmLook.shroud();
				look(l, shroud.getParams());
				DwellerShroud.DwellerShroudParams p = shroud.getShroudParams();
				l.darkBatch = p.negativeParticleNumBase;
				l.darkArea = p.negativeParticleAreaMult;
				l.darkSize = p.negativeParticleSizeMult;
				l.darkClearCentre = p.negativeParticleClearCenterAreaRadius;
				l.darkHighContrast = p.negativeParticleHighContrastMode;
				s.swarmLook = l;
				log.info(String.format("S3D: %s shroud look: %d blobs within %.0f, dark x%d", s.name, l.members, l.maxOffset, l.darkBatch));
				return true;
			}
			if (s.fragments != null) {
				RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
				if (swarm == null) return false;
				RoilingSwarmEffect.RoilingSwarmParams p = swarm.getParams();
				SwarmLook l = p.generateOffsetAroundAttachedEntityOval ? SwarmLook.fragments(ship.getCollisionRadius())
						: SwarmLook.swarm(p.baseMembersToMaintain, p.maxOffset);
				look(l, p);
				l.members = Math.max(l.members, p.baseMembersToMaintain);
				s.swarmLook = l;
				log.info(String.format("S3D: %s swarm look: up to %d pieces within %.0f%s", s.name, l.members, l.maxOffset, l.aroundHull ? " of the hull" : ""));
				return true;
			}
		} catch (Throwable e) {
			log.warn("S3D: couldn't read " + s.name + "'s swarm look, using vanilla's defaults (" + e + ")");
		}
		return true;
	}

	/** A construction swarm's look, as vanilla's script sets it: its reach x the build's size, EMP-coloured flashes. */
	static void constructionLook(Ship3D s, float radiusMult) {
		Integer r = SwarmLauncherEffect.SWARM_RADIUS.get(SwarmLauncherEffect.CONSTRUCTION_SWARM_WING);
		SwarmLook l = SwarmLook.swarm(s.fragments != null ? s.fragments.size : 50, (r != null ? r : 50) * radiusMult);
		l.flashFringe = rgba(VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR);
		s.swarmLook = l;
	}

	private static void look(SwarmLook l, RoilingSwarmEffect.RoilingSwarmParams p) {
		l.maxOffset = p.maxOffset;
		l.spriteSize = p.baseSpriteSize;
		if (l.shroud) {
			l.members = p.baseMembersToMaintain;
			l.lifeMin = p.baseDur;
			l.lifeRange = p.durRange;
			l.color = rgba(p.color);
			l.flashPerSecond = p.flashFrequency * p.numToFlash * p.flashProbability;
		}
		l.flashRadius = p.flashRadius;
		l.flashFringe = rgba(p.flashFringeColor);
		l.flashCore = rgba(p.flashCoreColor);
	}

	private static float[] rgba(Color c) {
		return new float[] {c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f};
	}

	private static boolean hasFragmentSwarm(ShipAPI ship) {
		if (ship.getVariant() != null && ship.getVariant().hasHullMod(HullMods.FRAGMENT_SWARM)) return true;
		return com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect.getSwarmFor(ship) != null;
	}

	private static boolean shrouded(ShipAPI ship) {
		if (DwellerShroud.getShroudFor(ship) != null) return true;
		if (ship.getVariant() == null) return false;
		for (String id : ship.getVariant().getHullMods()) {
			com.fs.starfarer.api.loading.HullModSpecAPI hm = Global.getSettings().getHullModSpec(id);
			if (hm != null && hm.getEffect() instanceof DwellerHullmod) return true;
		}
		return false;
	}

	// ---- helpers ------------------------------------------------------------------------------------

	/**
	 * The rift a weapon opens, from the minelayer weapon vanilla spawns it with: its shot's damage
	 * (x the ship's energy damage bonus) and EMP, the mine's blast radius, core, friendly fire, sound
	 * and fuse delay. False (and a log line) if there's no such minelayer.
	 */
	private static boolean mineRift(WeaponSpec3D ws, String minelayerId, float energyMult) {
		WeaponSpecAPI spec = null;
		try {
			spec = Global.getSettings().getWeaponSpec(minelayerId);
		} catch (Throwable ignored) {
			// missing (a mod removed it): handled below
		}
		if (spec == null || !(spec.getProjectileSpec() instanceof MissileSpecAPI)) {
			log.warn("S3D: " + ws.name + ": no minelayer " + minelayerId + "; its rifts aren't modelled");
			return false;
		}
		MissileSpecAPI ms = (MissileSpecAPI) spec.getProjectileSpec();
		JSONObject b = ms.getBehaviorJSON(), ex = b != null ? b.optJSONObject("explosionSpec") : null;
		float damage = spec.getDerivedStats() != null ? spec.getDerivedStats().getDamagePerShot() : 0f;
		if (damage <= 0f && ms.getDamage() != null) damage = ms.getDamage().getBaseDamage();
		float emp = spec.getDerivedStats() != null ? Math.max(0f, spec.getDerivedStats().getEmpPerShot()) : 0f;
		float radius = ex != null ? (float) ex.optDouble("radius", 50) : 50f;
		float core = ex != null ? (float) ex.optDouble("coreRadius", radius * 0.5f) : radius * 0.5f;
		Color glow = ms.getGlowColor();
		ws.rift = blast(ws.name + " rift", damage * energyMult, emp, spec.getDamageType(), radius, core,
				ex != null && friendlyFire(ex.optString("collisionClass", "")), ex != null ? ex.optString("sound", "") : "",
				glow != null ? rgb(glow) : ExoticWeapons.RIFT_COLOR);
		ws.riftDelay = b != null ? Math.max(0f, (float) b.optDouble("delay", 0)) : 0f;
		return true;
	}

	/** A blast spec (as Combat.areaBlast reads it): damage at the core, half at the edge. */
	private static WeaponSpec3D blast(String name, float damage, float emp, DamageType dt, float radius, float core, boolean ff, String sound, float[] color) {
		WeaponSpec3D b = new WeaponSpec3D();
		b.id = b.name = name;
		b.type = "ENERGY";
		b.supported = true;
		b.damage = damage;
		b.emp = emp;
		b.proxRadius = radius;
		b.proxCore = Math.min(radius, core);
		b.blastFF = ff;
		b.blastSound = sound;
		b.fringeColor = color;
		if (dt != null) damageType(b, dt);
		return b;
	}

	private static void damageType(WeaponSpec3D w, DamageType dt) {
		w.damageType = dt.name();
		w.shieldMult = dt.getShieldMult();
		w.armorMult = dt.getArmorMult();
		w.hullMult = dt.getHullMult();
	}

	/** Explosions that hit both sides: *_FF collision classes (not NO_FF) and HITS_SHIPS_AND_ASTEROIDS. */
	private static boolean friendlyFire(CollisionClass cc) {
		return cc != null && friendlyFire(cc.name());
	}

	private static boolean friendlyFire(String cc) {
		if (cc == null) return false;
		return cc.endsWith("_FF") && !cc.endsWith("NO_FF") || "HITS_SHIPS_AND_ASTEROIDS".equals(cc);
	}

	private static void visual(WeaponSpec3D ws, String simple) {
		ws.visualEffect = append(ws.visualEffect, simple);
	}

	private static void note(WeaponSpec3D ws, String what) {
		ws.effectNote = append(ws.effectNote, what);
	}

	private static String append(String list, String item) {
		if (list == null) return item;
		return (", " + list + ", ").contains(", " + item + ", ") ? list : list + ", " + item;
	}

	private static String remove(String list, String item) {
		if (list == null) return null;
		String r = (", " + list + ", ").replace(", " + item + ", ", ", ");
		r = r.length() >= 4 ? r.substring(2, r.length() - 2) : "";
		return r.isEmpty() ? null : r;
	}

	private static float[] rgb(Color c) {
		return new float[] {c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f};
	}
}
