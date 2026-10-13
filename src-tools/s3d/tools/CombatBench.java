package s3d.tools;

import java.io.File;
import java.util.Map;

import javax.imageio.ImageIO;

import s3d.core.ArmorShell;
import s3d.core.Combat;
import s3d.core.FlightParams;
import s3d.core.ScriptedMover;
import s3d.core.Ship3D;
import s3d.core.Tuning;
import s3d.core.WeaponMount;
import s3d.core.WeaponSpec3D;
import s3d.math.Quat;
import s3d.math.Vec3;
import s3d.render.ReliefMeshBuilder;
import s3d.render.SpriteImage;

/** Headless checks of the 3D weapon/damage rules on real hull shapes. Prints PASS/FAIL per scenario. */
public final class CombatBench {
	private static final float DT = 1f / 60f;
	private static int failures;
	private static boolean sawExplosion;

	public static void main(String[] args) throws Exception {
		File game = new File(args[0]);
		File build = new File(args[1]);
		Tuning t = GameData.modTuning(build);
		Map<String, GameData.Hull> hulls = GameData.hulls(game);
		String ai = System.getenv("S3D_AI"); // "weave,passChance,flankChance" override, for comparing AI settings
		if (ai != null) {
			String[] v = ai.split(",");
			t.aiWeave = Float.parseFloat(v[0]);
			t.aiPassChance = Float.parseFloat(v[1]);
			t.aiFlankChance = Float.parseFloat(v[2]);
		}
		String only = System.getenv("S3D_ONLY"); // one group of checks, for quick iteration
		if ("weaponScripts2".equals(only)) {
			weaponScripts2(game, hulls, t);
			System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
			return;
		}
		if ("systemsBatch2".equals(only)) {
			systemsBatch2(game, hulls, t);
			System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
			return;
		}
		if ("weaponScripts".equals(only) || "aiLoad".equals(only)) {
			if ("weaponScripts".equals(only)) weaponScripts(game, hulls, t);
			else aiLoad(game, t);
			System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
			return;
		}
		if (System.getenv("S3D_AB") != null) {
			// New AI (caution + opportunism) against the old one, mirrored fleets, each seed fought twice with the sides swapped.
			aiAB(game, hulls, t, Integer.parseInt(System.getenv("S3D_AB")));
			return;
		}
		if ("aiCaution".equals(only)) {
			aiCaution(game, hulls, t);
			aiOpportunism(game, hulls, t);
			System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
			return;
		}
		if (only != null && !only.isEmpty()) {
			// Any other single check group by its method name (game, hulls, tuning), e.g. S3D_ONLY=stationAssault.
			CombatBench.class.getDeclaredMethod(only, File.class, Map.class, Tuning.class).invoke(null, game, hulls, t);
			System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
			return;
		}
		if (System.getenv("S3D_SKIRMISH_SWEEP") != null) {
			// Many seeds of the 4v4, one line each: for judging AI changes beyond one lucky/unlucky run.
			int n = Integer.parseInt(System.getenv("S3D_SKIRMISH_SWEEP"));
			for (int seed = 0; seed < n; seed++) aiSkirmish(game, hulls, t, seed * 13, false);
			return;
		}

		armorMath(t);
		directHit(game, hulls, t);
		rangeSphere(game, hulls, t);
		ownHullPassThrough(game, hulls, t);
		leading(game, hulls, t);
		killTarget(game, hulls, t);
		curvedLeading(game, hulls, t);
		weaponGroups(game, hulls, t);
		aimAssist(game, hulls, t);
		beams(game, hulls, t);
		missiles(game, hulls, t);
		pointDefence(game, hulls, t);
		shieldRules(game, hulls, t);
		aiShieldUse(game, hulls, t);
		aiShieldUnderGuns(game, hulls, t);
		empRules(game, hulls, t);
		missileRules(game, hulls, t);
		missileGuidance(game, hulls, t);
		weaponScripts(game, hulls, t);
		weaponScripts2(game, hulls, t);
		fighterRules(game, hulls, t);
		systemRules(game, hulls, t);
		phaseRules(game, hulls, t);
		systemsBatch2(game, hulls, t);
		systemsBatch3(game, hulls, t);
		systemsBatch4(game, hulls, t);
		modData(game, hulls, t);
		collisionRules(game, hulls, t);
		soundEvents(game, hulls, t);
		weightRules(game, hulls, t);
		aiSkirmish(game, hulls, t, 0, true);
		aiManoeuvres(game, hulls, t);
		stationAssault(game, hulls, t);
		moduleShip(game, hulls, t);
		aiBroadside(game, hulls, t);
		aiHardpointLances(game, hulls, t);
		aiRetreat(game, hulls, t);
		aiOrders(game, hulls, t);
		aiCaution(game, hulls, t);
		aiOpportunism(game, hulls, t);
		aiLoad(game, t);
		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
	}

	/**
	 * Fixture scale: many set pieces below were laid out when 3D ranges were 2x vanilla
	 * (weapons.rangeMult 2). Distances that must be within (or beyond) a weapon's or system's reach
	 * are scaled by this, so the set pieces keep testing the same thing at any range setting.
	 */
	static float fk(Tuning t) {
		return t.weaponRangeMult / 2f;
	}

	private static void check(String name, boolean ok, String detail) {
		System.out.printf("%-34s %s  %s%n", name, ok ? "PASS" : "FAIL", detail);
		if (!ok) failures++;
	}

	static WeaponSpec3D gun(String type, float armorMult, float damage, float range, float speed) {
		WeaponSpec3D w = new WeaponSpec3D();
		w.id = w.name = "test " + type;
		w.type = "BALLISTIC";
		w.supported = true;
		w.damage = damage;
		w.damageType = type;
		w.armorMult = armorMult;
		w.hullMult = 1f;
		w.range = range;
		w.projectileSpeed = speed;
		w.cooldown = 0.25f;
		w.turnRate = 720f;
		return w;
	}

	static Ship3D ship(File game, GameData.Hull h, Tuning t, String id, int owner, float armorRating, float hull) throws Exception {
		GameData.readShipFile(game, h);
		SpriteImage img = new SpriteImage(ImageIO.read(new File(game, h.spritePath)));
		Ship3D s = new Ship3D(id);
		s.name = id;
		s.owner = owner;
		s.sizeClass = h.sizeClass;
		s.baseRadius = (float) Math.hypot(img.w, img.h) * 0.5f;
		s.params = new FlightParams(h.speed, h.accel, h.decel, h.turn, h.turnAccel).derive(t);
		s.applyScale(t.scaleFor(s.sizeClass));
		s.volume = ReliefMeshBuilder.build(img, h.centerX, img.h - h.centerYFromBottom, ReliefMeshBuilder.Params.from(t)).volume;
		s.armor = new ArmorShell(armorRating);
		s.hullMax = s.hull = hull;
		s.flux = flux(h, t);
		if (h.mass > 0f) s.mass = h.mass;
		return s;
	}

	/** Flux and shield from the hull data (no hullmods), like ShipBridge reads them in game. */
	static s3d.core.ShieldFlux flux(GameData.Hull h, Tuning t) {
		s3d.core.ShieldFlux f = new s3d.core.ShieldFlux();
		f.maxFlux = h.maxFlux;
		f.dissipation = h.dissipation;
		if ("FRONT".equals(h.shieldType) || "OMNI".equals(h.shieldType)) {
			f.type = "FRONT".equals(h.shieldType) ? s3d.core.ShieldFlux.Type.FRONT : s3d.core.ShieldFlux.Type.OMNI;
			f.arcDeg = h.shieldArc;
			f.halfAngleMax = s3d.core.ShieldFlux.coverageHalfAngle(h.shieldArc);
			f.radius = h.shieldRadius;
			f.fluxPerDamage = h.shieldEff;
			f.upkeep = h.shieldUpkeep * h.dissipation;
			f.turnRate = (float) Math.toRadians(t.omniShieldTurnDeg);
		}
		return f;
	}

	static void armorMath(Tuning t) {
		Combat c = new Combat();
		c.tuning = t;
		// Vanilla: 50 kinetic hit strength vs 1500 armor is capped at 85% reduction -> 100*0.5*0.15 = 7.5 to armor.
		// In 3D the reduction is scaled by armorReductionMult (0.7: capped at 59.5% -> 100*0.5*0.405 = 20.25).
		float kin = 100f * 0.5f * c.armorDamageMult(50f, 1500f, 1500f);
		float he = 100f * 2f * c.armorDamageMult(200f, 1500f, 1500f);
		float expectKin = 100f * 0.5f * (1f - c.maxArmorDamageReduction * t.armorReductionMult);
		float heVanilla = Math.max(1f - c.maxArmorDamageReduction, 200f / (200f + 1500f)), expectHe = 100f * 2f * (1f - (1f - heVanilla) * t.armorReductionMult);
		check("armor formula (kinetic vs heavy)", Math.abs(kin - expectKin) < 0.01f && Math.abs(he - expectHe) < 0.01f,
				String.format("kinetic %.2f, HE %.2f armor dmg per 100 (vanilla 7.50 / %.2f; reduction x%.2f)", kin, he, 100f * 2f * heVanilla, t.armorReductionMult));
	}

	/** Static frigate fires its forward gun at an Onslaught 700 units ahead. */
	static void directHit(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
		Ship3D target = ship(game, hulls.get("onslaught"), t, "onslaught", 1, 1750, 20000);
		target.pos.set(0, 0, -700);
		target.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(90)); // broadside
		shooter.weapons.add(new WeaponMount("WS1", gun("KINETIC", 0.5f, 100f, 900f, 1000f), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 10f, true));
		Combat c = new Combat();
		int shots = run(c, shooter, target, new Vec3(target.pos), 3f, t);
		float armorLost = 1f - target.armor.averageFraction();
		check("direct hit on hull volume", shots > 0 && target.hull <= 20000f && armorLost > 0f,
				String.format("%d hits, armor lost %.1f%%, hull %.0f", shots, armorLost * 100f, target.hull));
	}

	/** Same gun, target edge just outside / inside range measured from the mount. */
	static void rangeSphere(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		int[] hits = new int[2];
		float extra = 600f * t.weaponRangeMult - 600f; // the gun below has vanilla range 600
		float[] dists = {760f + extra, 640f + extra};
		for (int k = 0; k < 2; k++) {
			Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
			Ship3D target = ship(game, hulls.get("lasher"), t, "lasher", 1, 100, 1000);
			// Lasher nose toward the shooter: near surface is about its nose length from its centre.
			target.pos.set(0, 0, -dists[k]);
			target.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
			shooter.weapons.add(new WeaponMount("WS1", gun("ENERGY", 1f, 50f, 600f, 1200f), new Vec3(0, 5, -60), new Vec3(0, 0, -1), 10f, true));
			hits[k] = run(new Combat(), shooter, target, new Vec3(target.pos), 2f, t);
		}
		check("range is a sphere from the mount", hits[0] == 0 && hits[1] > 0, String.format("hits beyond range %d, inside range %d", hits[0], hits[1]));
	}

	/** A turret on top fires backwards through its own hull at a target behind: shots must pass through. */
	static void ownHullPassThrough(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		Ship3D shooter = ship(game, hulls.get("eagle"), t, "eagle", 0, 800, 5000);
		Ship3D target = ship(game, hulls.get("hammerhead"), t, "hammerhead", 1, 400, 3000);
		target.pos.set(0, -40, 650);
		// Mount at the nose, facing backwards, 360 arc: its shots must cross the shooter's own hull.
		shooter.weapons.add(new WeaponMount("WS1", gun("ENERGY", 1f, 50f, 1000f, 1100f), new Vec3(0, 10, -100), new Vec3(0, 0, 1), 360f, false));
		int hits = run(new Combat(), shooter, target, new Vec3(target.pos), 2f, t);
		check("own hull is pass-through", hits > 0 && shooter.hull == shooter.hullMax, String.format("hits on target %d, shooter hull %.0f/%.0f", hits, shooter.hull, shooter.hullMax));
	}

	/**
	 * Frigate circling the shooter at 800 units: leading with Combat.intercept should hit; aiming at
	 * its centre should mostly miss (it moves ~130 units during the 0.8 s flight).
	 */
	static void leading(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		int[] hits = new int[2];
		for (int mode = 0; mode < 2; mode++) {
			Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
			Ship3D target = ship(game, hulls.get("hound"), t, "hound", 1, 100, 1e9f);
			target.mover = new ScriptedMover(new Vec3(0, 0, 0), 800f, 160f, 25f);
			target.mover.step(target, 0.001f);
			shooter.weapons.add(new WeaponMount("WS1", gun("ENERGY", 1f, 10f, 2000f, 1000f), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 360f, false));
			Combat c = new Combat();
			int n = 0;
			Vec3 aim = new Vec3();
			for (float time = 0; time < 6f; time += DT) {
				target.mover.step(target, DT);
				if (mode == 0) {
					if (!Combat.intercept(shooter.pos, shooter.vel, target.pos, target.vel, 1000f, aim)) aim.set(target.pos);
				} else {
					aim.set(target.pos);
				}
				c.stepWeapons(shooter, aim, true, DT, t);
				int before = c.effects.size();
				c.stepProjectiles(java.util.List.of(shooter, target), DT);
				for (int i = before; i < c.effects.size(); i++) if (c.effects.get(i).kind == s3d.core.Effect.Kind.DAMAGE_TEXT) n++;
				c.stepEffects(DT);
			}
			hits[mode] = n;
		}
		check("lead solution hits a moving target", hits[0] > 3 * Math.max(1, hits[1]), String.format("hits with lead %d vs aiming at centre %d", hits[0], hits[1]));
	}

	/**
	 * Tight turn at long range (the case where straight-line lead misses everything): predicting
	 * along the curve with the tracked acceleration must hit far more often.
	 */
	static void curvedLeading(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		int[] hits = new int[2];
		for (int mode = 0; mode < 2; mode++) {
			Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
			Ship3D target = ship(game, hulls.get("hound"), t, "hound", 1, 100, 1e9f);
			target.mover = new ScriptedMover(new Vec3(0, 100, -1200), 500f, 160f, 25f);
			target.mover.step(target, 0.001f);
			shooter.weapons.add(new WeaponMount("WS1", gun("ENERGY", 1f, 10f, 2000f, 700f), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 360f, false));
			Combat c = new Combat();
			Vec3 aim = new Vec3();
			int n = 0;
			for (float time = 0; time < 8f; time += DT) {
				target.mover.step(target, DT);
				target.trackAcceleration(DT);
				boolean ok = mode == 0
						? Combat.intercept(shooter.pos, shooter.vel, target.pos, target.vel, 700f, aim)
						: Combat.interceptCurved(shooter.pos, shooter.vel, target.pos, target.vel, target.accel, 700f, aim);
				if (!ok) aim.set(target.pos);
				c.stepWeapons(shooter, aim, true, DT, t);
				int before = c.effects.size();
				c.stepProjectiles(java.util.List.of(shooter, target), DT);
				for (int i = before; i < c.effects.size(); i++) if (c.effects.get(i).kind == s3d.core.Effect.Kind.DAMAGE_TEXT) n++;
				c.stepEffects(DT);
			}
			hits[mode] = n;
		}
		check("curved lead hits a turning target", hits[1] >= 10 && hits[1] > 3 * Math.max(1, hits[0]),
				String.format("hits straight-line lead %d vs curved lead %d (of ~32 shots)", hits[0], hits[1]));
	}

	/** Enough HE fire destroys the target and marks it destroyed. */
	static void killTarget(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
		Ship3D target = ship(game, hulls.get("lasher"), t, "lasher", 1, 300, 1500);
		target.pos.set(0, 0, -500);
		WeaponSpec3D he = gun("HIGH_EXPLOSIVE", 2f, 400f, 900f, 1000f);
		he.cooldown = 0.1f;
		shooter.weapons.add(new WeaponMount("WS1", he, new Vec3(0, 5, -20), new Vec3(0, 0, -1), 20f, true));
		Combat c = new Combat();
		sawExplosion = false;
		run(c, shooter, target, new Vec3(target.pos), 8f, t);
		boolean boom = sawExplosion;
		check("target destroyed at 0 hull", target.destroyed && target.hull == 0f && boom,
				String.format("destroyed=%s hull=%.0f explosion=%s", target.destroyed, target.hull, boom));
	}

	/** Player Wolf in a Sim: hardpoint in manual group 1, turret in autofire group 2. */
	static s3d.core.Sim wolfSim(File game, Map<String, GameData.Hull> hulls, Tuning t, Ship3D target) throws Exception {
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D wolf = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1500);
		WeaponSpec3D blaster = gun("ENERGY", 1f, 500f, 600f, 1000f);
		blaster.name = "blaster";
		blaster.turnRate = 4f;
		blaster.cooldown = 1f;
		WeaponSpec3D ion = gun("ENERGY", 1f, 25f, 500f, 1000f);
		ion.name = "ion";
		ion.turnRate = 200f;
		ion.chargeTime = 0.5f;
		ion.cooldown = 0f;
		WeaponMount hp = new WeaponMount("WS004", blaster, new Vec3(0, 5, -45), new Vec3(0, 0, -1), 5f, true);
		WeaponMount tu = new WeaponMount("WS001", ion, new Vec3(0, 8, -11), new Vec3(0, 0, -1), 150f, false);
		wolf.weapons.add(hp);
		wolf.weapons.add(tu);
		s3d.core.WeaponGroup3D g1 = new s3d.core.WeaponGroup3D();
		g1.mounts.add(hp);
		s3d.core.WeaponGroup3D g2 = new s3d.core.WeaponGroup3D();
		g2.mounts.add(tu);
		g2.autofire = true;
		wolf.groups.add(g1);
		wolf.groups.add(g2);
		sim.addShip(wolf);
		sim.setPlayer(wolf);
		sim.addShip(target);
		return sim;
	}

	static s3d.core.PilotInput screen() {
		s3d.core.PilotInput in = new s3d.core.PilotInput();
		in.screenW = 1280;
		in.screenH = 800;
		in.mouseX = 640;
		in.mouseY = 400;
		return in;
	}

	/** Trigger fires only the selected (manual) group; the autofire group shoots on its own. */
	static void weaponGroups(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		int[][] shots = new int[2][2]; // [trigger][0=blaster,1=ion]
		for (int trig = 0; trig < 2; trig++) {
			Ship3D target = ship(game, hulls.get("eagle"), t, "eagle", 1, 800, 1e9f);
			target.pos.set(0, 0, -650 * fk(t));
			target.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(90));
			s3d.core.Sim sim = wolfSim(game, hulls, t, target);
			s3d.core.PilotInput in = screen();
			java.util.Set<s3d.core.Projectile> seen = new java.util.HashSet<>();
			for (float time = 0; time < 3f; time += DT) {
				in.fire = trig == 1;
				sim.update(in, DT, DT);
				for (s3d.core.Projectile p : sim.combat.projectiles) {
					if (seen.add(p)) shots[trig][p.spec.name.equals("blaster") ? 0 : 1]++;
				}
			}
		}
		check("trigger fires only selected group", shots[0][0] == 0 && shots[1][0] > 0,
				String.format("blaster shots without/with trigger %d/%d", shots[0][0], shots[1][0]));
		check("autofire group engages by itself", shots[0][1] > 0 && shots[1][1] > 0,
				String.format("ion shots without/with trigger %d/%d", shots[0][1], shots[1][1]));
	}

	/**
	 * Crossing frigate, turret group selected: the "player" keeps the mouse on the lead circle (as you
	 * would on screen). Assist must lock and the turret must land hits.
	 */
	static void aimAssist(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		Ship3D target = ship(game, hulls.get("hound"), t, "hound", 1, 100, 1e9f);
		target.mover = new ScriptedMover(new Vec3(0, 0, -700), 300f, 150f, 0f);
		target.mover.step(target, 0.001f);
		s3d.core.Sim sim = wolfSim(game, hulls, t, target);
		sim.player.groups.get(1).autofire = false;
		sim.player.selectedGroup = 1; // the turret
		s3d.core.PilotInput in = screen();
		float[] clip = new float[4];
		int hits = 0, lockedFrames = 0, frames = 0;
		for (float time = 0; time < 6f; time += DT) {
			if (sim.hasLead) {
				sim.camera.viewProj.transform(sim.leadPoint.x, sim.leadPoint.y, sim.leadPoint.z, clip);
				if (clip[3] > 0f) {
					in.mouseX = (clip[0] / clip[3] * 0.5f + 0.5f) * in.screenW;
					in.mouseY = (clip[1] / clip[3] * 0.5f + 0.5f) * in.screenH;
				}
			}
			in.fire = true;
			int before = sim.combat.effects.size();
			sim.update(in, DT, DT);
			for (int i = before; i < sim.combat.effects.size(); i++) {
				if (sim.combat.effects.get(i).kind == s3d.core.Effect.Kind.DAMAGE_TEXT) hits++;
			}
			frames++;
			if (sim.assistLocked) lockedFrames++;
		}
		check("reticle on lead circle hits", hits >= 3 && lockedFrames > frames * 0.7f,
				String.format("hits %d, assist locked %d%% of frames, aim target %s", hits, 100 * lockedFrames / frames,
						sim.aimTarget != null ? sim.aimTarget.name : "none"));
	}

	static WeaponSpec3D beam(float dps, boolean burst) {
		WeaponSpec3D w = gun("ENERGY", 1f, dps, 800f, 10000f);
		w.kind = WeaponSpec3D.Kind.BEAM;
		w.name = burst ? "burst beam" : "beam";
		w.dps = dps;
		w.chargeUp = 0.1f;
		w.chargeDown = 0.1f;
		w.burstBeam = burst;
		w.burstDuration = burst ? 1f : 0f;
		w.cooldown = burst ? 2f : 0f;
		w.beamSpeed = 10000f;
		w.beamWidth = 12f;
		return w;
	}

	static WeaponSpec3D launcher(boolean guided) {
		WeaponSpec3D w = gun("HIGH_EXPLOSIVE", 2f, 300f, 2500f, 500f);
		w.kind = WeaponSpec3D.Kind.MISSILE;
		w.name = guided ? "harpoon-ish" : "rocket";
		w.launchSpeed = 150f;
		w.missileMaxSpeed = 500f;
		w.missileAccel = 600f;
		w.missileTurnRate = guided ? 120f : 0f;
		w.guided = guided;
		w.flightTime = 8f;
		w.missileHp = 100f;
		w.missileRadius = 10f;
		w.cooldown = 1.5f;
		return w;
	}

	/** Continuous beam: damage = dps x time on an unarmored target; burst beam: cycles and refires. */
	static void beams(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
		Ship3D target = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 10000f);
		target.pos.set(0, 0, -500);
		target.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(90));
		WeaponMount m = new WeaponMount("B1", beam(200f, false), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 60f, false);
		shooter.weapons.add(m);
		Combat c = new Combat();
		c.world = java.util.List.of(shooter, target);
		for (float time = 0; time < 2f; time += DT) c.stepWeapons(shooter, new Vec3(target.pos), true, DT, t);
		float dealt = 10000f - target.hull;
		check("continuous beam deals dps x time", dealt > 370f && dealt < 410f, String.format("%.0f damage in 2 s at 200 dps (ramp-up 0.1 s)", dealt));

		Ship3D target2 = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e9f);
		target2.pos.set(0, 0, -500);
		Ship3D shooter2 = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
		WeaponMount mb = new WeaponMount("B2", beam(200f, true), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 60f, false);
		shooter2.weapons.add(mb);
		c = new Combat();
		c.world = java.util.List.of(shooter2, target2);
		int bursts = 0;
		boolean wasOn = false;
		for (float time = 0; time < 7f; time += DT) {
			c.stepWeapons(shooter2, new Vec3(target2.pos), true, DT, t);
			boolean on = mb.beamLevel > 0f;
			if (on && !wasOn) bursts++;
			wasOn = on;
		}
		check("burst beam cycles: charge, burst, cooldown", bursts == 3, String.format("%d bursts in 7 s (cycle 0.1 + 1.0 + 0.1 + 2.0 s)", bursts));
	}

	/** Guided missile hits a circling target; unguided rocket keeps its heading; flight time ends it. */
	static void missiles(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
		Ship3D target = ship(game, hulls.get("hound"), t, "hound", 1, 100, 1e9f);
		target.mover = new ScriptedMover(new Vec3(0, 200, -1200), 400f, 170f, 30f);
		target.mover.step(target, 0.001f);
		WeaponMount m = new WeaponMount("M1", launcher(true), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 5f, true);
		shooter.weapons.add(m);
		Combat c = new Combat();
		java.util.List<Ship3D> ships = java.util.List.of(shooter, target);
		c.world = ships;
		int hits = 0, launched = 0;
		for (float time = 0; time < 10f; time += DT) {
			target.mover.step(target, DT);
			target.trackAcceleration(DT);
			c.stepMount(shooter, m, null, launched < 3, target, DT, t);
			if (m.firedThisFrame) launched++;
			int before = c.effects.size();
			c.stepMissiles(ships, DT);
			for (int i = before; i < c.effects.size(); i++) if (c.effects.get(i).kind == s3d.core.Effect.Kind.EXPLOSION) hits++;
			c.stepEffects(DT);
		}
		check("guided missiles hit a circling target", hits >= 2, String.format("%d of %d missiles hit", hits, launched));

		Ship3D shooter2 = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1000);
		WeaponMount r = new WeaponMount("R1", launcher(false), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 5f, true);
		r.spec.flightTime = 1f;
		shooter2.weapons.add(r);
		c = new Combat();
		c.world = java.util.List.of(shooter2);
		c.stepMount(shooter2, r, null, true, null, DT, t);
		s3d.core.Missile3D rocket = c.missiles.get(0);
		Vec3 dir0 = new Vec3(rocket.dir);
		for (float time = 0; time < 0.9f; time += DT) c.stepMissiles(java.util.List.of(shooter2), DT);
		boolean straight = rocket.dir.dot(dir0) > 0.9999f && rocket.vel.dot(dir0) > 0.99f * rocket.vel.length();
		for (float time = 0; time < 2.5f; time += DT) c.stepMissiles(java.util.List.of(shooter2), DT);
		check("unguided rocket flies straight, burns out", straight && c.missiles.isEmpty(),
				String.format("straight=%s, gone after flight time=%s", straight, c.missiles.isEmpty()));
	}

	/** Enemy Harpoon-style missiles at the Wolf: PD autofire shoots most down; without PD they hit. */
	static void pointDefence(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		int[] playerHits = new int[2], killed = new int[2];
		for (int withPd = 0; withPd < 2; withPd++) {
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D wolf = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			WeaponSpec3D pd = beam(150f, true);
			pd.name = "pd laser";
			pd.pd = true;
			pd.range = 400f;
			pd.burstDuration = 0.5f;
			pd.cooldown = 0.5f;
			WeaponMount pd1 = new WeaponMount("PD1", pd, new Vec3(-12, 6, 13), new Vec3(-1, 0, 0), 200f, false);
			WeaponMount pd2 = new WeaponMount("PD2", pd, new Vec3(12, 6, 13), new Vec3(1, 0, 0), 200f, false);
			wolf.weapons.add(pd1);
			wolf.weapons.add(pd2);
			s3d.core.WeaponGroup3D g = new s3d.core.WeaponGroup3D();
			g.mounts.add(pd1);
			g.mounts.add(pd2);
			g.autofire = withPd == 1;
			wolf.groups.add(g);
			s3d.core.WeaponGroup3D dummy = new s3d.core.WeaponGroup3D(); // selected (empty) group so PD isn't on the trigger
			wolf.groups.add(dummy);
			wolf.selectedGroup = 1;
			sim.addShip(wolf);
			sim.setPlayer(wolf);
			Ship3D enemy = ship(game, hulls.get("hammerhead"), t, "hammerhead", 1, 400, 1e9f);
			enemy.pos.set(0, 300, -1600);
			WeaponSpec3D h = launcher(true);
			h.missileMaxSpeed = 350f;
			h.cooldown = 1f;
			enemy.weapons.add(new WeaponMount("H1", h, new Vec3(0, 10, -40), new Vec3(0, 0, -1), 5f, true));
			sim.addShip(enemy);
			sim.incomingFire = true;
			s3d.core.PilotInput in = screen();
			java.util.Set<s3d.core.Missile3D> seen = new java.util.HashSet<>();
			int explosions = 0;
			for (float time = 0; time < 12f; time += DT) {
				int before = sim.combat.effects.size();
				java.util.Set<s3d.core.Missile3D> alive = new java.util.HashSet<>(sim.combat.missiles);
				sim.update(in, DT, DT);
				seen.addAll(sim.combat.missiles);
				for (int i = before; i < sim.combat.effects.size(); i++) if (sim.combat.effects.get(i).kind == s3d.core.Effect.Kind.EXPLOSION) explosions++;
				for (s3d.core.Missile3D ms : alive) if (!sim.combat.missiles.contains(ms) && ms.hp <= 0f) killed[withPd]++;
			}
			playerHits[withPd] = explosions;
		}
		check("point defence shoots down missiles", killed[1] >= 3 && playerHits[1] < playerHits[0],
				String.format("without PD: %d hits on you; with PD: %d hits, %d missiles shot down", playerHits[0], playerHits[1], killed[1]));
	}

	/** A ship with a synthetic gun on every weapon slot of its real hull (size sets damage/range). */
	static Ship3D armed(File game, Map<String, GameData.Hull> hulls, Tuning t, String hullId, String id, int owner) throws Exception {
		GameData.Hull h = hulls.get(hullId);
		float armor = "capital".equals(h.sizeClass) ? 1500 : "cruiser".equals(h.sizeClass) ? 1000 : "destroyer".equals(h.sizeClass) ? 600 : 300;
		float hp = "capital".equals(h.sizeClass) ? 14000 : "cruiser".equals(h.sizeClass) ? 8000 : "destroyer".equals(h.sizeClass) ? 4000 : 1800;
		Ship3D s = ship(game, h, t, id, owner, armor, hp);
		for (GameData.Slot sl : GameData.slots(game, hullId)) {
			if (!"TURRET".equals(sl.mount) && !"HARDPOINT".equals(sl.mount)) continue;
			if ("MISSILE".equals(sl.type) || "DECORATIVE".equals(sl.type) || "SYSTEM".equals(sl.type) || "LAUNCH_BAY".equals(sl.type) || "BUILT_IN".equals(sl.type) && sl.arc <= 0f) continue;
			float k = "LARGE".equals(sl.size) ? 4f : "MEDIUM".equals(sl.size) ? 2f : 1f;
			WeaponSpec3D w = gun(k > 1f ? "HIGH_EXPLOSIVE" : "KINETIC", k > 1f ? 2f : 0.5f, 60f * k, 500f + 150f * k, 900f);
			w.name = sl.size.toLowerCase() + " gun";
			w.cooldown = 0.6f;
			w.turnRate = "HARDPOINT".equals(sl.mount) ? 10f : 60f / k;
			w.shieldMult = k > 1f ? 0.5f : 2f; // HE vs kinetic, like vanilla
			w.fluxPerShot = w.damage * 0.8f; // vanilla weapons mostly run 0.6-1.2 flux per damage
			float x = -sl.y, z = -sl.x;
			float y = s.volume.topAt(x, z) + 1f;
			double a = Math.toRadians(sl.angle);
			s.weapons.add(new WeaponMount(sl.id, w, new Vec3(x, y, z), new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)),
					Math.max(5f, sl.arc), "HARDPOINT".equals(sl.mount)));
		}
		return s;
	}

	static float damageTaken(Ship3D s) {
		return (s.hullMax - s.hull) + s.armor.rating * (1f - s.armor.averageFraction());
	}

	/** Shield and flux rules, one at a time. */
	static void shieldRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		float c90 = (float) Math.toDegrees(s3d.core.ShieldFlux.coverageHalfAngle(90f));
		float c180 = (float) Math.toDegrees(s3d.core.ShieldFlux.coverageHalfAngle(180f));
		float c360 = (float) Math.toDegrees(s3d.core.ShieldFlux.coverageHalfAngle(360f));
		check("shield arc keeps its coverage", Math.abs(c90 - 60f) < 0.5f && Math.abs(c180 - 90f) < 0.5f && Math.abs(c360 - 180f) < 0.5f,
				String.format("arc 90 -> %.1f, 180 -> %.1f, 360 -> %.1f deg half-angle", c90, c180, c360));

		// Front shield blocks shots from ahead, not from behind.
		float[] fluxGot = new float[2], hullLost = new float[2];
		for (int side = 0; side < 2; side++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D target = ship(game, hulls.get("wolf"), t, "wolf", 1, 150, 2000);
			target.flux.shieldOn = true;
			target.flux.unfold = 1f;
			Ship3D shooter = ship(game, hulls.get("lasher"), t, "lasher", 0, 300, 2000);
			shooter.pos.set(0f, 0f, side == 0 ? -700f : 700f);
			WeaponSpec3D g = gun("KINETIC", 0.5f, 50f, 1000f, 1200f);
			g.shieldMult = 2f;
			shooter.weapons.add(new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, side == 0 ? 1 : -1), 360f, false));
			run(c, shooter, target, new Vec3(target.pos), 2f, t);
			fluxGot[side] = target.flux.flux;
			hullLost[side] = damageTaken(target);
		}
		check("front shield blocks from ahead only", fluxGot[0] > 0f && hullLost[0] == 0f && fluxGot[1] == 0f && hullLost[1] > 0f,
				String.format("from ahead: flux %.0f, hull/armor lost %.0f; from behind: flux %.0f, lost %.0f", fluxGot[0], hullLost[0], fluxGot[1], hullLost[1]));

		// Damage type: kinetic (x2) vs high explosive (x0.5) on shields; wolf efficiency 0.7.
		s3d.core.ShieldFlux f = flux(hulls.get("wolf"), t);
		float kin = f.absorb(100f * 2f, false, null, t);
		f.reset();
		float he = f.absorb(100f * 0.5f, false, null, t);
		check("shield flux by damage type", Math.abs(kin - 140f) < 0.01f && Math.abs(he - 35f) < 0.01f,
				String.format("100 kinetic -> %.0f hard flux, 100 HE -> %.0f (efficiency %.1f)", kin, he, f.fluxPerDamage));

		// Hard flux stays while the shield is up, drains when it's down; soft drains either way.
		f.reset();
		f.flux = f.hardFlux = 1000f;
		f.shieldOn = true;
		f.unfold = 1f;
		for (int i = 0; i < 120; i++) f.step(DT, null, t);
		float hardUp = f.hardFlux;
		f.shieldOn = false;
		for (int i = 0; i < 180; i++) f.step(DT, null, t);
		check("hard flux drains only with shield down", Math.abs(hardUp - 1000f) < 0.01f && f.hardFlux < 1000f - f.dissipation * 2f,
				String.format("after 2 s shield up: hard %.0f; after 3 s down: hard %.0f (dissipation %.0f/s)", hardUp, f.hardFlux, f.dissipation));

		// Overload: a hit past capacity drops the shield and blocks weapons for a while.
		f.reset();
		f.shieldOn = true;
		f.unfold = 1f;
		f.flux = f.hardFlux = f.maxFlux - 20f;
		f.absorb(200f, false, null, t);
		boolean over = f.overloaded() && !f.shieldOn && !f.spend(1f);
		float dur = f.overloadLeft;
		for (int i = 0; i < 60 * 12; i++) f.step(DT, null, t);
		check("overload past capacity", over && dur >= t.overloadBaseTime && dur <= t.overloadMaxTime && !f.overloaded(),
				String.format("overloaded=%s for %.1f s, over after 12 s=%s", over, dur, !f.overloaded()));

		// Vent: double rate, defenceless until empty.
		f.reset();
		f.flux = 2000f;
		f.hardFlux = 1000f;
		f.vent();
		float time = 0f;
		boolean blocked = !f.spend(1f);
		while (f.venting && time < 30f) {
			f.step(DT, null, t);
			time += DT;
		}
		float expect = 2000f / (f.dissipation * t.ventRateMult);
		check("vent at double rate, no firing", blocked && Math.abs(time - expect) < 0.1f && f.flux == 0f,
				String.format("2000 flux vented in %.2f s (expected %.2f), weapons blocked=%s", time, expect, blocked));

		// Weapons can't fire past capacity.
		Combat c = new Combat();
		c.tuning = t;
		Ship3D shooter = ship(game, hulls.get("lasher"), t, "lasher", 0, 300, 2000);
		shooter.flux.maxFlux = 100f;
		shooter.flux.dissipation = 0f;
		WeaponSpec3D g = gun("ENERGY", 1f, 50f, 1000f, 1200f);
		g.fluxPerShot = 60f;
		shooter.weapons.add(new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false));
		Ship3D dummy = ship(game, hulls.get("wolf"), t, "wolf", 1, 150, 1e9f);
		dummy.pos.set(0f, 0f, -600f);
		int shots = 0;
		for (float tt = 0; tt < 2f; tt += DT) {
			c.stepWeapons(shooter, dummy.pos, true, DT, t);
			if (shooter.weapons.get(0).firedThisFrame) shots++;
		}
		check("no shots past flux capacity", shots == 1 && shooter.flux.flux == 60f, String.format("%d shot(s), flux %.0f/100", shots, shooter.flux.flux));

		// Beams on shields make soft flux.
		c = new Combat();
		c.tuning = t;
		Ship3D beamer = ship(game, hulls.get("lasher"), t, "lasher", 0, 300, 2000);
		beamer.weapons.add(new WeaponMount("B", beam(200f, false), new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false));
		Ship3D tgt = ship(game, hulls.get("wolf"), t, "wolf", 1, 150, 2000);
		tgt.pos.set(0f, 0f, -500f);
		tgt.rot.setAxisAngle(0, 1, 0, (float) Math.PI); // shield toward the beam
		tgt.flux.shieldOn = true;
		tgt.flux.unfold = 1f;
		c.world = java.util.List.of(beamer, tgt);
		for (float tt = 0; tt < 2f; tt += DT) c.stepWeapons(beamer, tgt.pos, true, DT, t);
		check("beam on shield is soft flux", tgt.flux.flux > 100f && tgt.flux.hardFlux == 0f && damageTaken(tgt) == 0f,
				String.format("flux %.0f, hard %.0f, hull/armor lost %.0f", tgt.flux.flux, tgt.flux.hardFlux, damageTaken(tgt)));
	}

	/** An AI Enforcer (omni shield) under fire from behind turns its shield and blocks most of it. */
	static void aiShieldUse(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D enforcer = ship(game, hulls.get("enforcer"), t, "enforcer", 1, 900, 1e9f);
		enforcer.ai = new s3d.core.ShipAI3D(2);
		sim.addShip(enforcer);
		Ship3D shooter = ship(game, hulls.get("lasher"), t, "lasher", 0, 300, 1e9f);
		shooter.pos.set(0f, 100f, 900f); // behind it
		WeaponSpec3D g = gun("KINETIC", 0.5f, 40f, 2000f, 1400f);
		g.shieldMult = 2f;
		g.cooldown = 0.2f;
		WeaponMount gm = new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false);
		shooter.weapons.add(gm);
		sim.addShip(shooter);
		s3d.core.PilotInput in = screen();
		float lostEarly = 0f, lostLate = 0f;
		for (float time = 0; time < 8f; time += DT) {
			float before = damageTaken(enforcer);
			sim.update(in, DT, DT);
			sim.combat.stepMount(shooter, gm, enforcer.pos, true, DT, t);
			float d = damageTaken(enforcer) - before;
			if (time < 2f) lostEarly += d; else lostLate += d;
		}
		// Since 2026-10-12 the shield is usually up before the first shot (an enemy has it within reach), so
		// nothing may be lost early either: allow under one point in the last 6 s, or a fifth of three times the early loss.
		check("AI omni shield faces the fire", enforcer.flux.shieldOn && lostLate < Math.max(1f, 0.2f * lostEarly * 3f) && enforcer.flux.flux > 0f,
				String.format("hull/armor lost first 2 s %.1f, last 6 s %.1f; flux %.0f/%.0f, shield %s", lostEarly, lostLate,
						enforcer.flux.flux, enforcer.flux.maxFlux, enforcer.flux.shieldOn ? "up" : "down"));
	}

	/**
	 * Shields take seconds to unfold (vanilla's unfold time; 2-4 s on many hulls), so an AI ship keeps
	 * its shield up while an enemy has it within reach instead of waiting for shots to be on their way.
	 * An Eagle with a 3 s unfold, a ship within reach, a half-second burst every 3 s after the first 4 s:
	 * the shield must be fully up nearly all that time; hull and armor lost compared with raising it only
	 * for fire already inbound (the old rule).
	 */
	static void aiShieldUnderGuns(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		float[] lost = new float[2], up = new float[2];
		float keepUpBelow = t.aiShieldFlux;
		s3d.core.PilotInput in = screen();
		try {
			for (int k = 0; k < 2; k++) {
				t.aiShieldFlux = k == 0 ? keepUpBelow : 0f;
				s3d.core.Sim sim = new s3d.core.Sim(t);
				Ship3D eagle = ship(game, hulls.get("eagle"), t, "eagle", 1, 1000, 1e9f);
				eagle.flux.unfoldTime = 3f;
				eagle.ai = new s3d.core.ShipAI3D(5);
				eagle.ai.homeDir.set(0f, 0f, -1f);
				sim.addShip(eagle);
				Ship3D shooter = ship(game, hulls.get("lasher"), t, "lasher", 0, 300, 1e9f);
				shooter.pos.set(0f, 0f, 1200f * fk(t));
				WeaponSpec3D g = gun("HIGH_EXPLOSIVE", 2f, 150f, 900f, 1100f);
				g.cooldown = 0.15f;
				WeaponMount gm = new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false);
				shooter.weapons.add(gm);
				sim.addShip(shooter);
				int frames = 0, upFrames = 0;
				for (float time = 0; time < 25f; time += DT) {
					float before = damageTaken(eagle);
					sim.update(in, DT, DT);
					sim.combat.stepMount(shooter, gm, eagle.pos, time > 4f && time % 3f < 0.5f, DT, t);
					if (time > 4f) {
						lost[k] += damageTaken(eagle) - before;
						frames++;
						if (eagle.flux.unfold > 0.9f) upFrames++;
					}
				}
				up[k] = (float) upFrames / frames;
			}
		} finally {
			t.aiShieldFlux = keepUpBelow;
		}
		check("AI keeps its shield up under the guns", up[0] > 0.9f && lost[0] <= lost[1],
				String.format("3 s unfold, enemy in reach: shield fully up %.0f%% of the time, hull/armor lost %.0f (raising it only for inbound fire: up %.0f%%, lost %.0f)",
						up[0] * 100f, lost[0], up[1] * 100f, lost[1]));
	}

	/** EMP disables the nearest component and it comes back; ion arcs pierce shields only at high hard flux; flameout. */
	static void empRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		// Ion-cannon-like shots on an unshielded hull: the turret nearest the impact goes down, then repairs.
		Combat c = new Combat();
		c.tuning = t;
		Ship3D target = ship(game, hulls.get("hammerhead"), t, "hammerhead", 1, 500, 1e9f);
		WeaponMount nose = new WeaponMount("N", gun("ENERGY", 1f, 50f, 800f, 900f), new Vec3(0, 8, -60), new Vec3(0, 0, -1), 90f, false);
		WeaponMount tail = new WeaponMount("T", gun("ENERGY", 1f, 50f, 800f, 900f), new Vec3(0, 8, 60), new Vec3(0, 0, 1), 90f, false);
		nose.maxHp = nose.hp = tail.maxHp = tail.hp = 300f;
		target.weapons.add(nose);
		target.weapons.add(tail);
		target.weaponRepairTime = 3f;
		Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		shooter.pos.set(0f, 0f, -700f);
		WeaponSpec3D ion = gun("ENERGY", 1f, 25f, 1000f, 1000f);
		ion.emp = 200f;
		ion.cooldown = 0.5f;
		shooter.weapons.add(new WeaponMount("I", ion, new Vec3(0, 5, 0), new Vec3(0, 0, 1), 360f, false));
		int arcs = 0;
		java.util.List<Ship3D> pair = java.util.List.of(shooter, target);
		for (float tt = 0; tt < 2f; tt += DT) {
			c.stepWeapons(shooter, new Vec3(0f, 0f, -50f), true, DT, t);
			int before = c.effects.size();
			c.stepProjectiles(pair, DT);
			for (int i = before; i < c.effects.size(); i++) if (c.effects.get(i).kind == s3d.core.Effect.Kind.ARC) arcs++;
			c.stepEffects(DT);
		}
		boolean noseDown = nose.disabledFor > 0f, tailUp = tail.disabledFor <= 0f;
		for (float tt = 0; tt < 3.1f; tt += DT) target.stepRepairs(DT);
		check("EMP disables the nearest weapon", noseDown && tailUp && nose.disabledFor <= 0f && nose.hp == nose.maxHp,
				String.format("nose turret down=%s (tail untouched=%s), %d arcs drawn, back after repair time=%s", noseDown, tailUp, arcs, nose.disabledFor <= 0f));

		// A disabled weapon doesn't fire.
		nose.disabledFor = 5f;
		int fired = 0;
		for (float tt = 0; tt < 1f; tt += DT) {
			c.stepMount(target, nose, shooter.pos, true, DT, t);
			if (nose.firedThisFrame) fired++;
		}
		check("disabled weapon holds fire", fired == 0, fired + " shots while disabled");

		// Ion arcs through shields: none at zero hard flux, frequent at high hard flux.
		int[] pierced = new int[2];
		for (int k = 0; k < 2; k++) {
			c = new Combat();
			c.tuning = t;
			Ship3D tgt = ship(game, hulls.get("enforcer"), t, "enforcer", 1, 900, 1e9f);
			tgt.pos.set(0f, 0f, -700f);
			tgt.rot.setAxisAngle(0, 1, 0, (float) Math.PI); // shield toward the shooter
			tgt.flux.maxFlux = 1e7f;
			tgt.flux.flux = tgt.flux.hardFlux = k == 0 ? 0f : 0.8e7f;
			tgt.flux.shieldOn = true;
			tgt.flux.unfold = 1f;
			tgt.flux.type = s3d.core.ShieldFlux.Type.FRONT;
			WeaponMount victim = new WeaponMount("V", gun("ENERGY", 1f, 50f, 800f, 900f), new Vec3(0, 8, -20), new Vec3(0, 0, -1), 90f, false);
			victim.maxHp = victim.hp = 1e9f;
			tgt.weapons.add(victim);
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			WeaponSpec3D ionArc = gun("ENERGY", 1f, 25f, 1500f, 1000f);
			ionArc.emp = 200f;
			ionArc.empArcs = true;
			ionArc.cooldown = 0.25f;
			sh.weapons.add(new WeaponMount("I", ionArc, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false));
			run(c, sh, tgt, new Vec3(tgt.pos), 6f, t);
			pierced[k] = Math.round((1e9f - victim.hp) / 200f);
		}
		check("ion arcs pierce loaded shields", pierced[0] == 0 && pierced[1] >= 8,
				String.format("arcs through the shield: %d at 0%% hard flux, %d at 80%% (of ~24 hits)", pierced[0], pierced[1]));

		// Engines: losing them all = flameout; the ship drifts and can't speed up.
		Ship3D wolf = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		wolf.engines.addAll(hulls.get("wolf").engines);
		wolf.initComponents(100f);
		s3d.core.ShipControls ctl = new s3d.core.ShipControls();
		wolf.throttle = 1f;
		for (float tt = 0; tt < 4f; tt += DT) s3d.core.FlightModel.step(wolf, ctl, t, DT);
		float full = wolf.speed();
		java.util.Arrays.fill(wolf.engineDown, 30f);
		for (float tt = 0; tt < 3f; tt += DT) s3d.core.FlightModel.step(wolf, ctl, t, DT);
		float drift = wolf.speed();
		check("flameout: no thrust, keeps drifting", wolf.engineThrust() == 0f && Math.abs(drift - full) < 1f && full > 50f,
				String.format("speed %.0f with engines, %.0f after 3 s flamed out", full, drift));
	}

	/** Missile discipline, proximity fuse and splitting missiles. */
	static void missileRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		Ship3D shooter = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
		Ship3D target = ship(game, hulls.get("enforcer"), t, "enforcer", 1, 900, 5000f);
		target.pos.set(0f, 0f, -1500f);
		target.rot.setAxisAngle(0, 1, 0, (float) Math.PI); // facing the shooter
		WeaponSpec3D he = launcher(true);
		he.shieldMult = 0.5f;
		he.maxAmmo = 8;
		he.ammoPerSecond = 0f;
		WeaponSpec3D kin = launcher(true);
		kin.shieldMult = 2f;
		kin.maxAmmo = 8;
		WeaponMount mh = new WeaponMount("H", he, new Vec3(), new Vec3(0, 0, -1), 5f, true), mk = new WeaponMount("K", kin, new Vec3(), new Vec3(0, 0, -1), 5f, true);
		s3d.core.ShieldFlux f = target.flux;
		f.type = s3d.core.ShieldFlux.Type.FRONT;
		f.shieldOn = true;
		f.unfold = 1f;
		boolean heIntoShield = s3d.core.Sim.missileWorthFiring(shooter, mh, target);
		boolean kinLowFlux = s3d.core.Sim.missileWorthFiring(shooter, mk, target);
		f.flux = f.maxFlux * 0.6f;
		boolean kinLoaded = s3d.core.Sim.missileWorthFiring(shooter, mk, target);
		f.overloadLeft = 5f;
		boolean heOverloaded = s3d.core.Sim.missileWorthFiring(shooter, mh, target);
		f.overloadLeft = 0f;
		target.rot.identity(); // shield now faces away
		boolean heBehind = s3d.core.Sim.missileWorthFiring(shooter, mh, target);
		check("missiles wait for an opening", !heIntoShield && !kinLowFlux && kinLoaded && heOverloaded && heBehind,
				String.format("HE into raised shield %s, kinetic at low flux %s / at 60%% flux %s, HE at overloaded %s, HE at shield facing away %s",
						heIntoShield, kinLowFlux, kinLoaded, heOverloaded, heBehind));

		// Proximity fuse: a flak shell passing 25 units from a missile still kills it.
		Combat c = new Combat();
		c.tuning = t;
		Ship3D pd = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		WeaponSpec3D flak = gun("FRAGMENTATION", 0.25f, 200f, 1000f, 700f);
		flak.proxRange = 35f;
		flak.proxRadius = 50f;
		flak.proxCore = 35f;
		s3d.core.Projectile shell = new s3d.core.Projectile();
		shell.spec = flak;
		shell.source = pd;
		shell.maxRange = 2000f;
		shell.origin.set(0f, 0f, 0f);
		shell.pos.set(0f, 0f, 0f);
		shell.vel.set(0f, 0f, -700f);
		c.projectiles.add(shell);
		s3d.core.Missile3D incoming = new s3d.core.Missile3D();
		incoming.spec = launcher(true);
		incoming.owner = 1;
		incoming.hp = 150f;
		incoming.pos.set(25f, 0f, -400f);
		incoming.vel.set(0f, 0f, 1f);
		incoming.dir.set(0f, 0f, 1f);
		incoming.age = 0f;
		incoming.spec.flightTime = 100f;
		c.missiles.add(incoming);
		for (float tt = 0; tt < 1.5f; tt += DT) {
			c.stepProjectiles(java.util.List.of(pd), DT);
			c.stepMissiles(java.util.List.of(pd), DT);
		}
		check("proximity fuse kills a near miss", !c.missiles.contains(incoming), "missile passed 25 units from the shell; destroyed=" + !c.missiles.contains(incoming));

		// Splitting missile: splits near the target into warheads that hit it.
		c = new Combat();
		c.tuning = t;
		Ship3D launcherShip = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
		Ship3D tgt = ship(game, hulls.get("eagle"), t, "eagle", 1, 1000, 1e9f);
		tgt.pos.set(0f, 0f, -2500f);
		tgt.rot.setAxisAngle(0, 1, 0, (float) Math.PI); // shield toward the launcher
		tgt.flux.shieldOn = true;
		tgt.flux.unfold = 1f;
		tgt.flux.maxFlux = 1e7f;
		c.world = java.util.List.of(launcherShip, tgt);
		WeaponSpec3D sabot = launcher(true);
		sabot.missileMaxSpeed = 400f;
		sabot.flightTime = 20f;
		sabot.mirvCount = 5;
		sabot.mirvSplitRange = 400f;
		sabot.mirvArcDeg = 20f;
		WeaponSpec3D head = gun("KINETIC", 0.5f, 200f, 1000f, 1000f);
		head.shieldMult = 2f;
		head.emp = 200f;
		sabot.mirvWarhead = head;
		WeaponMount lm = new WeaponMount("S", sabot, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 5f, true);
		int maxProj = 0;
		for (float tt = 0; tt < 12f; tt += DT) {
			c.stepMount(launcherShip, lm, tgt.pos, tt < DT, tgt, DT, t);
			c.stepMissiles(c.world, DT);
			c.stepProjectiles(c.world, DT);
			maxProj = Math.max(maxProj, c.projectiles.size());
		}
		float expect = 5 * 200f * 2f * tgt.flux.fluxPerDamage;
		check("missile splits into warheads", maxProj == 5 && tgt.flux.flux > expect * 0.6f,
				String.format("%d warheads in flight at once; shield took %.0f flux (all five hitting = %.0f)", maxProj, tgt.flux.flux, expect));
	}

	/** Hurricane with vanilla's numbers: a MIRV that splits 1000 out into seven two-stage warheads. */
	static WeaponSpec3D hurricane() {
		WeaponSpec3D hu = launcher(true);
		hu.name = "hurricane-ish";
		hu.launchSpeed = 50f;
		hu.missileMaxSpeed = 250f;
		hu.missileAccel = 250f;
		hu.missileTurnRate = 150f;
		hu.flightTime = 12f;
		hu.missileHp = 350f;
		hu.missileRadius = 16f;
		hu.maxAmmo = 1;
		hu.mirvCount = 7;
		hu.mirvSplitRange = 1000f;
		hu.mirvMinTime = 2f;
		hu.mirvSplitEarly = true;
		hu.mirvArcDeg = 180f;
		hu.mirvArcOffsetDeg = 180f;
		hu.mirvEvenSpread = true;
		hu.mirvSpreadSpeed = 200f;
		hu.mirvSpreadSpeedRandom = 50f;
		WeaponSpec3D wh = launcher(true);
		wh.name = "hurricane warhead";
		wh.damage = 500f;
		wh.missileMaxSpeed = 400f;
		wh.missileAccel = 2400f;
		wh.missileTurnRate = 1100f;
		wh.flightTime = 5f;
		wh.missileHp = 250f;
		wh.missileRadius = 12f;
		wh.guidance = WeaponSpec3D.Guidance.TWO_STAGE;
		hu.mirvWarhead = wh;
		return hu;
	}

	/**
	 * Vanilla's missile AIs by type, and fuses: Hurricane's warheads line up and burn in, two-stage
	 * missiles lock their heading, Salamanders go for the tail, fused shots burst at the end of their
	 * range or flight and by hulls, proximity charges go to their aim point.
	 */
	static void missileGuidance(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		// Hurricane: splits ~1000 out, seven warheads spawn facing the target and drift out along a
		// backward fan (no thrust), each locks on within 3 s and burns in: all seven hit.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("dominator"), t, "dom", 0, 400, 1e9f);
			Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e9f);
			tg.pos.set(0f, 0f, -2600f);
			tg.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
			tg.flux.shieldOn = false;
			WeaponSpec3D hu = hurricane(), wh = hu.mirvWarhead;
			sh.weapons.add(new WeaponMount("H", hu, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true));
			c.world = java.util.List.of(sh, tg);
			java.util.List<s3d.core.Missile3D> heads = new java.util.ArrayList<>();
			java.util.List<Vec3> spreadDirs = new java.util.ArrayList<>(); // each warhead's push out of the MIRV
			Vec3 mirvVel = new Vec3();
			float splitAt = -1f, splitDist = 0f, minFacing = 1f, coastMax = 0f, lockLatest = 0f, spreadAtLock = 0f;
			int coastingEarly = 0;
			for (float tt = 0; tt < 20f; tt += DT) {
				c.stepMount(sh, sh.weapons.get(0), tg.pos, tt < DT, tg, DT, t);
				s3d.core.Missile3D mirv = c.missiles.stream().filter(m -> m.spec == hu).findFirst().orElse(null);
				if (mirv != null) {
					splitDist = mirv.pos.distance(tg.pos) - tg.radius;
					mirvVel.set(mirv.vel);
				}
				c.stepProjectiles(c.world, DT);
				c.stepMissiles(c.world, DT);
				c.stepEffects(DT);
				for (s3d.core.Missile3D m : c.missiles) {
					if (m.spec != wh || heads.contains(m)) continue;
					heads.add(m);
					spreadDirs.add(new Vec3(m.vel).sub(mirvVel).normalize());
					if (splitAt < 0f) splitAt = tt;
					minFacing = Math.min(minFacing, new Vec3(tg.pos).sub(m.pos).normalize().dot(m.dir));
				}
				if (splitAt >= 0f) {
					float since = tt - splitAt;
					int locked = 0;
					for (s3d.core.Missile3D m : heads) if (m.seek < 0f) locked++;
					if (since < 0.4f) coastingEarly = Math.max(coastingEarly, heads.size() - locked);
					if (locked == heads.size() && lockLatest == 0f && !heads.isEmpty()) {
						lockLatest = since;
						for (s3d.core.Missile3D a : heads) for (s3d.core.Missile3D b : heads) spreadAtLock = Math.max(spreadAtLock, a.pos.distance(b.pos));
					}
				}
			}
			int hits = 0;
			for (s3d.core.Missile3D m : heads) if (!m.alive && m.hp > 0f && !m.flamedOut()) hits++;
			check("hurricane warheads line up, burn in", heads.size() == 7 && hits == 7 && splitDist < 1000f + 60f && minFacing > 0.98f
					&& coastingEarly == 7 && lockLatest > 0.5f && lockLatest <= 3.05f && spreadAtLock > 150f,
					String.format("split %.0f from the hull, %d warheads (facing the target: worst cos %.2f), all coasting for 0.4 s: %s, all locked %.1f s after"
							+ " the split, fanned %.0f wide by then, %d of 7 hit", splitDist, heads.size(), minFacing, coastingEarly == 7, lockLatest, spreadAtLock, hits));

			// The spread is 3D: no plane holds all seven pushes, and each turns from the spread's centre
			// (straight back) by one of vanilla's even-fan angles 0, 30, 30, 60, 60, 90, 90.
			Vec3 centre = new Vec3();
			for (Vec3 d : spreadDirs) centre.add(d); // mirrored pairs cancel sideways
			centre.normalize();
			float[] ang = new float[spreadDirs.size()];
			for (int i = 0; i < ang.length; i++) ang[i] = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, spreadDirs.get(i).dot(centre)))));
			java.util.Arrays.sort(ang);
			StringBuilder angs = new StringBuilder();
			for (float a : ang) angs.append(angs.length() > 0 ? "/" : "").append(Math.round(a));
			float[] vanilla = {0f, 30f, 30f, 60f, 60f, 90f, 90f};
			float angErr = ang.length == vanilla.length ? 0f : 999f;
			for (int i = 0; i < Math.min(ang.length, vanilla.length); i++) angErr = Math.max(angErr, Math.abs(ang[i] - vanilla[i]));
			float flattest = 1f; // the plane (through two pushes) that leaves the others closest to it: how far out the farthest is
			for (Vec3 a : spreadDirs) for (Vec3 b : spreadDirs) {
				Vec3 nrm = new Vec3(a).cross(b);
				if (nrm.length() < 0.3f) continue;
				nrm.normalize();
				float out = 0f;
				for (Vec3 d : spreadDirs) out = Math.max(out, Math.abs(d.dot(nrm)));
				flattest = Math.min(flattest, out);
			}
			check("hurricane spread is 3D", angErr < 3f && flattest > 0.4f && centre.dot(new Vec3(sh.pos).sub(tg.pos).normalize()) > 0.9f,
					String.format("warheads turn %s deg from the spread's centre (vanilla 0/30/30/60/60/90/90, worst off by %.1f); the flattest plane"
							+ " through two of them still leaves one %.0f deg out of it; centre points back at the launcher: cos %.2f",
							angs, angErr, Math.toDegrees(Math.asin(Math.min(1f, flattest))), centre.dot(new Vec3(sh.pos).sub(tg.pos).normalize())));
		}

		// Two-stage (Squall): lines up while coasting (flight clock held), then its heading never changes
		// again: it hits a target that holds still, and misses one that moves off once it has locked.
		{
			int[] hitsOn = new int[2];
			float[] maxTurnAfterLock = new float[2], seekTime = new float[2], clockGap = new float[2];
			for (int k = 0; k < 2; k++) {
				Combat c = new Combat();
				c.tuning = t;
				Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
				Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e9f);
				tg.pos.set(-900f, 0f, -1400f); // off to the side: the rocket has to turn onto it
				tg.flux.shieldOn = false;
				WeaponSpec3D sq = launcher(true);
				sq.launchSpeed = 150f;
				sq.missileMaxSpeed = 700f;
				sq.missileAccel = 1000f;
				sq.missileTurnRate = 180f;
				sq.flightTime = 4f;
				sq.maxAmmo = 1;
				sq.guidance = WeaponSpec3D.Guidance.TWO_STAGE;
				sh.weapons.add(new WeaponMount("Q", sq, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true));
				c.world = java.util.List.of(sh, tg);
				s3d.core.Missile3D rocket = null;
				Vec3 lockedDir = null;
				for (float tt = 0; tt < 8f; tt += DT) {
					c.stepMount(sh, sh.weapons.get(0), tg.pos, tt < DT, tg, DT, t);
					if (rocket == null && !c.missiles.isEmpty()) rocket = c.missiles.get(0);
					int before = c.effects.size();
					c.stepProjectiles(c.world, DT);
					c.stepMissiles(c.world, DT);
					for (int i = before; i < c.effects.size(); i++) if (c.effects.get(i).kind == s3d.core.Effect.Kind.DAMAGE_TEXT) hitsOn[k]++;
					c.stepEffects(DT);
					if (rocket != null && rocket.seek < 0f) {
						if (lockedDir == null) {
							lockedDir = new Vec3(rocket.dir);
							seekTime[k] = rocket.age;
							clockGap[k] = rocket.age - rocket.flight;
							if (k == 1) tg.vel.set(0f, 0f, -250f); // moves off once the rocket is committed
						}
						maxTurnAfterLock[k] = Math.max(maxTurnAfterLock[k], (float) Math.toDegrees(2.0 * Math.asin(Math.min(1.0, lockedDir.distance(rocket.dir) * 0.5))));
					}
					tg.pos.addScaled(tg.vel, DT);
				}
			}
			check("two-stage locks, then flies straight", hitsOn[0] >= 1 && hitsOn[1] == 0 && maxTurnAfterLock[0] < 0.01f && maxTurnAfterLock[1] < 0.01f
					&& seekTime[0] > 0.5f && seekTime[0] <= 3.05f && clockGap[0] > 0.45f,
					String.format("lined up for %.2f s (flight clock held %.2f s), turned %.3f deg after locking; hits: %d on a still target, %d on one moving off after the lock",
							seekTime[0], clockGap[0], Math.max(maxTurnAfterLock[0], maxTurnAfterLock[1]), hitsOn[0], hitsOn[1]));
		}

		// Heatseeker (Salamander): launched at a ship's nose, it circles round and strikes from behind;
		// a homing missile with the same engine strikes the front.
		{
			float[] along = new float[2];
			boolean[] hit = new boolean[2];
			for (int k = 0; k < 2; k++) {
				Combat c = new Combat();
				c.tuning = t;
				Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
				Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e9f);
				tg.pos.set(0f, 0f, -1500f);
				tg.rot.setAxisAngle(0, 1, 0, (float) Math.PI); // nose toward the launcher
				tg.flux.shieldOn = false;
				WeaponSpec3D sal = launcher(true);
				sal.launchSpeed = 50f;
				sal.missileMaxSpeed = 400f;
				sal.missileAccel = 5500f;
				sal.missileTurnRate = 300f;
				sal.flightTime = 15f;
				sal.maxAmmo = 1;
				if (k == 0) sal.guidance = WeaponSpec3D.Guidance.HEATSEEKER;
				sh.weapons.add(new WeaponMount("S", sal, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true));
				c.world = java.util.List.of(sh, tg);
				s3d.core.Missile3D ms = null;
				for (float tt = 0; tt < 20f && (ms == null || c.missiles.contains(ms)); tt += DT) {
					c.stepMount(sh, sh.weapons.get(0), tg.pos, tt < DT, tg, DT, t);
					if (ms == null && !c.missiles.isEmpty()) ms = c.missiles.get(0);
					c.stepProjectiles(c.world, DT);
					c.stepMissiles(c.world, DT);
					c.stepEffects(DT);
				}
				hit[k] = ms != null && !ms.alive && !ms.flamedOut();
				// Where it struck along the target's length: + toward its nose, - toward its tail.
				if (ms != null) along[k] = new Vec3(ms.pos).sub(tg.pos).dot(tg.rot.forward(new Vec3()));
			}
			check("salamander goes for the tail", hit[0] && hit[1] && along[0] < 0f && along[1] > 0f,
					String.format("struck %.0f along the target from its centre as a heatseeker (hit %s), %.0f as a plain homing missile (hit %s); + = nose",
							along[0], hit[0], along[1], hit[1]));
		}

		// Fused shells: flak bursts where its range runs out; Devastator's shells burst up to 75% short.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D pd = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			c.world = java.util.List.of(pd);
			WeaponSpec3D flak = gun("FRAGMENTATION", 0.25f, 200f, 600f, 700f);
			flak.proxRange = 35f;
			flak.proxRadius = 50f;
			flak.proxCore = 35f;
			WeaponMount fm = new WeaponMount("F", flak, new Vec3(0, 5, -20), new Vec3(0, 0, -1), 5f, true);
			int booms = 0;
			float burstAt = 0f;
			for (float tt = 0; tt < 2f; tt += DT) {
				c.stepMount(pd, fm, new Vec3(0f, 0f, -5000f), tt < DT, null, DT, t);
				int before = c.effects.size();
				c.stepProjectiles(c.world, DT);
				for (int i = before; i < c.effects.size(); i++) {
					if (c.effects.get(i).kind != s3d.core.Effect.Kind.EXPLOSION) continue;
					booms++;
					burstAt = -c.effects.get(i).pos.z;
				}
				c.stepEffects(DT);
			}
			WeaponSpec3D dev = gun("HIGH_EXPLOSIVE", 2f, 250f, 1000f, 800f);
			dev.proxRange = 25f;
			dev.proxRadius = 55f;
			dev.proxRangeVariance = 0.75f;
			dev.cooldown = 0.01f;
			WeaponMount dm = new WeaponMount("D", dev, new Vec3(0, 5, -20), new Vec3(0, 0, -1), 5f, true);
			Combat c2 = new Combat();
			c2.tuning = t;
			c2.seed(7);
			c2.world = java.util.List.of(pd);
			float lo = Float.MAX_VALUE, hi = 0f;
			for (float tt = 0; tt < 2f; tt += DT) {
				c2.stepMount(pd, dm, new Vec3(0f, 0f, -5000f), true, null, DT, t);
				for (s3d.core.Projectile p : c2.projectiles) {
					lo = Math.min(lo, p.maxRange);
					hi = Math.max(hi, p.maxRange);
				}
				c2.projectiles.clear();
			}
			float full = 1000f * t.weaponRangeMult;
			check("fused shells burst at range", booms == 1 && Math.abs(burstAt - 600f * t.weaponRangeMult) < 40f && lo < full * 0.35f && hi > full * 0.9f,
					String.format("flak into empty space: %d burst at %.0f (range %.0f); Devastator bursts between %.0f and %.0f of %.0f",
							booms, burstAt, 600f * t.weaponRangeMult, lo, hi, full));
		}

		// A flak shell that would just miss an enemy hull bursts beside it and hurts it.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D pd = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e5f);
			tg.pos.set(0f, 0f, -800f);
			tg.flux.shieldOn = false;
			c.world = java.util.List.of(pd, tg);
			float side = tg.reachToward(new Vec3(1f, 0f, 0f)) + 20f; // passes 20 beyond its side
			WeaponSpec3D flak = gun("FRAGMENTATION", 0.25f, 200f, 2000f, 700f);
			flak.proxRange = 35f;
			flak.proxRadius = 50f;
			flak.proxCore = 35f;
			s3d.core.Projectile shell = new s3d.core.Projectile();
			shell.spec = flak;
			shell.source = pd;
			shell.maxRange = 3000f;
			shell.origin.set(side, 0f, 0f);
			shell.pos.set(side, 0f, 0f);
			shell.vel.set(0f, 0f, -700f);
			c.projectiles.add(shell);
			for (float tt = 0; tt < 2.5f; tt += DT) {
				c.stepProjectiles(c.world, DT);
				c.stepEffects(DT);
			}
			float lost = tg.hullMax - tg.hull;
			check("flak fuses by a hull", c.projectiles.isEmpty() && lost > 0f, String.format("shell passing 20 beyond the hull: hull lost %.0f", lost));
		}

		// A fused missile (canister, proximity charge) bursts where its flight ends instead of fizzling.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			c.world = java.util.List.of(sh);
			WeaponSpec3D can = launcher(false);
			can.flightTime = 1f;
			can.proxRange = 40f;
			can.proxRadius = 75f;
			can.proxCore = 50f;
			WeaponMount cm = new WeaponMount("C", can, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
			int booms = 0;
			for (float tt = 0; tt < 3f; tt += DT) {
				c.stepMount(sh, cm, new Vec3(0f, 0f, -5000f), tt < DT, null, DT, t);
				int before = c.effects.size();
				c.stepMissiles(c.world, DT);
				for (int i = before; i < c.effects.size(); i++) if (c.effects.get(i).kind == s3d.core.Effect.Kind.EXPLOSION) booms++;
				c.stepEffects(DT);
			}
			check("fused missile bursts at flight end", booms == 1 && c.missiles.isEmpty(), String.format("%d burst when its %.1f s flight ran out", booms, can.flightTime * t.weaponRangeMult));
		}

		// Proximity charge: flies to the point it was aimed at (not after the ship, which moves off) and stops there.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			Ship3D tg = ship(game, hulls.get("wolf"), t, "wolf", 1, 0, 1e9f);
			tg.pos.set(0f, 0f, -700f);
			tg.vel.set(300f, 0f, 0f);
			c.world = java.util.List.of(sh, tg);
			WeaponSpec3D pc = launcher(false);
			pc.launchSpeed = 150f;
			pc.missileMaxSpeed = 200f;
			pc.missileAccel = 200f;
			pc.missileTurnRate = 150f;
			pc.flightTime = 8f;
			pc.proxRange = 40f;
			pc.proxRadius = 150f;
			pc.proxCore = 75f;
			pc.guidance = WeaponSpec3D.Guidance.AIM_POINT;
			WeaponMount pm = new WeaponMount("P", pc, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
			Vec3 point = new Vec3(0f, 0f, -600f);
			s3d.core.Missile3D ch = null;
			float closest = Float.MAX_VALUE, slowest = Float.MAX_VALUE;
			for (float tt = 0; tt < 6f; tt += DT) {
				c.stepMount(sh, pm, point, tt < DT, tg, DT, t);
				if (ch == null && !c.missiles.isEmpty()) ch = c.missiles.get(0);
				c.stepMissiles(c.world, DT);
				c.stepEffects(DT);
				tg.pos.addScaled(tg.vel, DT);
				if (ch != null && c.missiles.contains(ch)) {
					closest = Math.min(closest, ch.pos.distance(point));
					if (ch.pos.distance(point) < 40f) slowest = Math.min(slowest, ch.vel.length());
				}
			}
			check("proximity charge waits at its aim", ch != null && ch.target == null && closest < 25f && slowest < 20f,
					String.format("got within %.0f of the aim point and slowed to %.0f there (the ship it was aimed near moved off at 300)", closest, slowest));
		}
	}

	/** Lowest armor reading anywhere on the hull (where a hit stripped it). */
	static float minArmor(Ship3D s) {
		float m = Float.MAX_VALUE;
		for (int i = 0; i < ArmorShell.AZ * ArmorShell.EL; i++) m = Math.min(m, s.armor.armorAt(i));
		return m;
	}

	/** A target with its front shield raised toward -Z (where the shooters sit), no dissipation, room for any flux. */
	static Ship3D shieldedTarget(File game, Map<String, GameData.Hull> hulls, Tuning t, float hardFlux) throws Exception {
		Ship3D tgt = ship(game, hulls.get("enforcer"), t, "enforcer", 1, 900, 1e9f);
		tgt.pos.set(0f, 0f, -900f);
		tgt.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
		tgt.flux.type = s3d.core.ShieldFlux.Type.FRONT;
		tgt.flux.maxFlux = 1e7f;
		tgt.flux.dissipation = 0f;
		tgt.flux.flux = tgt.flux.hardFlux = hardFlux;
		tgt.flux.shieldOn = true;
		tgt.flux.unfold = 1f;
		return tgt;
	}

	/** Steps a two-ship duel: shooter fires every mount at `aim`, everything advances. */
	static void duel(Combat c, Ship3D shooter, Ship3D target, Vec3 aim, Ship3D missileTarget, float seconds, Tuning t) {
		c.world = java.util.List.of(shooter, target);
		for (float time = 0; time < seconds; time += DT) {
			for (WeaponMount m : shooter.weapons) c.stepMount(shooter, m, aim, true, missileTarget, DT, t);
			c.stepProjectiles(c.world, DT);
			c.stepMissiles(c.world, DT);
			c.stepEffects(DT);
		}
	}

	/** Vanilla weapon scripts rebuilt in 3D: armor breakers, burns, graviton, Paladin, arcs, Squall, shock repeater, DEM, missile warheads. */
	static void weaponScripts(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		// Breach: one HE shot strips extra armor round the hit, armor doesn't reduce it, hull untouched.
		float[] drop = new float[2], hullLost = new float[2];
		for (int k = 0; k < 2; k++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			Ship3D tg = ship(game, hulls.get("enforcer"), t, "enforcer", 1, 900, 5000f);
			tg.pos.set(0f, 0f, -900f);
			WeaponSpec3D he = gun("HIGH_EXPLOSIVE", 2f, 150f, 1500f, 1000f);
			he.maxAmmo = 1;
			if (k == 1) he.armorBreak = 250f;
			sh.weapons.add(new WeaponMount("B", he, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true));
			float before = minArmor(tg);
			duel(c, sh, tg, new Vec3(tg.pos), null, 2f, t);
			drop[k] = before - minArmor(tg);
			hullLost[k] = tg.hullMax - tg.hull;
		}
		check("breach strips extra armor", drop[1] > drop[0] + 120f && drop[1] < drop[0] + 260f && hullLost[1] == hullLost[0],
				String.format("armor reading at the hit dropped %.0f plain, %.0f with +250 breach; hull lost %.0f / %.0f", drop[0], drop[1], hullLost[0], hullLost[1]));

		// Disintegrator: armor burns away over ~10 s after one hit; the hull never takes burn damage.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			Ship3D tg = ship(game, hulls.get("enforcer"), t, "enforcer", 1, 300, 5000f);
			tg.pos.set(0f, 0f, -900f);
			WeaponSpec3D dis = gun("HIGH_EXPLOSIVE", 2f, 100f, 1500f, 1000f);
			dis.maxAmmo = 1;
			dis.armorBurn = 1000f;
			dis.armorBurnTicks = 11;
			sh.weapons.add(new WeaponMount("D", dis, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true));
			duel(c, sh, tg, new Vec3(tg.pos), null, 1.6f, t);
			float early = minArmor(tg), hullEarly = tg.hull;
			duel(c, sh, tg, new Vec3(tg.pos), null, 11f, t);
			float late = minArmor(tg);
			check("disintegrator burns armor, not hull", early > late + 100f && late < 30f && tg.hull == hullEarly,
					String.format("armor at the hit %.0f after 1.6 s, %.0f after 12.6 s (rating 300); hull %.0f -> %.0f during the burn", early, late, hullEarly, tg.hull));
		}

		// Graviton: a shield under one/three graviton beams takes +5%/+10% from a gun (hard flux only: beams are soft).
		float[] hard = new float[3];
		for (int k = 0; k < 3; k++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			sh.flux = null;
			Ship3D tg = shieldedTarget(game, hulls, t, 0f);
			WeaponSpec3D g = gun("KINETIC", 0.5f, 100f, 1500f, 1500f);
			g.shieldMult = 2f;
			g.cooldown = 0.5f;
			sh.weapons.add(new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true));
			int beams = k == 0 ? 0 : k == 1 ? 1 : 3;
			for (int i = 0; i < beams; i++) {
				WeaponSpec3D gb = beam(100f, false);
				gb.range = 800f / fk(t);
				gb.damageType = "KINETIC";
				gb.shieldMult = 2f;
				gb.shieldMark = new float[] {0.05f, 0.08f, 0.10f};
				gb.shieldMarkTime = 1f;
				sh.weapons.add(new WeaponMount("GB" + i, gb, new Vec3(i * 6f - 6f, 5, 0), new Vec3(0, 0, -1), 10f, true));
			}
			duel(c, sh, tg, new Vec3(tg.pos), null, 3f, t);
			hard[k] = tg.flux.hardFlux;
		}
		check("graviton beams soften shields", Math.abs(hard[1] / hard[0] - 1.05f) < 0.02f && Math.abs(hard[2] / hard[0] - 1.10f) < 0.02f,
				String.format("gun's hard flux on the shield %.0f alone, x%.3f under one graviton beam, x%.3f under three", hard[0], hard[1] / hard[0], hard[2] / hard[0]));

		// Paladin: the burst's blast takes out the missiles packed round the one the beam burns.
		int[] left = new int[2];
		for (int k = 0; k < 2; k++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			c.world = java.util.List.of(sh);
			WeaponSpec3D pal = beam(1000f, true);
			pal.burstDuration = 0.2f;
			pal.chargeUp = 0f;
			pal.chargeDown = 0f;
			if (k == 1) {
				WeaponSpec3D b = gun("FRAGMENTATION", 0.25f, 100f, 0f, 0f);
				b.shieldMult = 0.25f;
				b.proxRadius = 75f;
				b.proxCore = 50f;
				pal.beamBlast = b;
			}
			WeaponMount pm = new WeaponMount("P", pal, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, false);
			for (int i = 0; i < 4; i++) {
				s3d.core.Missile3D ms = new s3d.core.Missile3D();
				ms.spec = launcher(true);
				ms.owner = 1;
				ms.hp = 80f;
				ms.pos.set(i == 0 ? 0f : (i - 2) * 30f, i == 0 ? 0f : 20f, -600f);
				c.missiles.add(ms);
			}
			for (float tt = 0; tt < 0.5f; tt += DT) c.stepMount(sh, pm, new Vec3(0f, 0f, -600f), true, DT, t);
			for (s3d.core.Missile3D ms : c.missiles) if (ms.hp > 0f) left[k]++;
		}
		check("paladin burst blasts a swarm", left[0] >= 3 && left[1] == 0, String.format("missiles left of 4: %d without the blast, %d with it", left[0], left[1]));

		// Pilum-style EMP arc: a nose hit knocks out a tail turret the hit's own EMP can't reach.
		boolean[] tailOut = new boolean[2];
		for (int k = 0; k < 2; k++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			Ship3D tg = ship(game, hulls.get("hammerhead"), t, "hammerhead", 1, 300, 1e9f);
			tg.pos.set(0f, 0f, -900f);
			tg.rot.setAxisAngle(0, 1, 0, (float) Math.PI); // nose toward the shooter, tail turret far side
			WeaponMount tail = new WeaponMount("T", gun("ENERGY", 1f, 50f, 800f, 900f), new Vec3(0, 8, 60), new Vec3(0, 0, 1), 90f, false);
			tail.maxHp = tail.hp = 300f;
			tg.weapons.add(tail);
			WeaponSpec3D p = gun("FRAGMENTATION", 0.25f, 50f, 1500f, 1000f);
			p.emp = 500f;
			p.maxAmmo = 1;
			if (k == 1) {
				p.bonusArcChance = 1f;
				p.bonusArcPierces = true;
			}
			sh.weapons.add(new WeaponMount("P", p, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true));
			duel(c, sh, tg, new Vec3(tg.pos), null, 2f, t);
			tailOut[k] = tail.disabledFor > 0f;
		}
		check("pilum arc reaches any component", !tailOut[0] && tailOut[1], String.format("tail turret knocked out by a nose hit: %s plain, %s with the arc", tailOut[0], tailOut[1]));

		// Squall: +150 damage on shields (kinetic: x2 flux).
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			Ship3D tg = shieldedTarget(game, hulls, t, 0f);
			WeaponSpec3D sq = launcher(true);
			sq.damage = 100f;
			sq.damageType = "KINETIC";
			sq.shieldMult = 2f;
			sq.maxAmmo = 1;
			sq.shieldBonus = 150f;
			sh.weapons.add(new WeaponMount("S", sq, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true));
			duel(c, sh, tg, new Vec3(tg.pos), tg, 4f, t);
			float expect = (100f + 150f) * 2f * tg.flux.fluxPerDamage;
			check("squall hits shields harder", Math.abs(tg.flux.hardFlux - expect) < 1f, String.format("shield took %.0f hard flux (100 + 150 kinetic = %.0f)", tg.flux.hardFlux, expect));
		}

		// Shock repeater: an instant arc to the nearest enemy in its cone; nothing outside the cone; no shells.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			Ship3D front = ship(game, hulls.get("wolf"), t, "front", 1, 0, 5000f);
			front.pos.set(0f, 0f, -450f);
			Ship3D side = ship(game, hulls.get("wolf"), t, "side", 1, 0, 5000f);
			side.pos.set(350f, 0f, 0f);
			WeaponSpec3D sr = gun("ENERGY", 1f, 50f, 300f / fk(t), 10000f);
			sr.emp = 200f;
			sr.cooldown = 0.5f;
			sr.arcGunConeDeg = 30f;
			sh.weapons.add(new WeaponMount("SR", sr, new Vec3(0, 5, -20), new Vec3(0, 0, -1), 10f, false));
			c.world = java.util.List.of(sh, front, side);
			int shells = 0;
			for (float tt = 0; tt < 2f; tt += DT) {
				c.stepMount(sh, sh.weapons.get(0), new Vec3(0f, 0f, -1000f), true, DT, t);
				shells += c.projectiles.size();
				c.stepProjectiles(c.world, DT);
				c.stepEffects(DT);
			}
			float frontLost = front.hullMax - front.hull, sideLost = side.hullMax - side.hull;
			check("shock repeater arcs in its cone", frontLost >= 150f && sideLost == 0f && shells == 0,
					String.format("ship ahead lost %.0f hull (an arc of 50 every 0.5 s for 2 s), ship off to the side %.0f, shells %d", frontLost, sideLost, shells));
		}

		// DEM (Gorgon-like): stops at its stand-off, paints, then the payload beam does the damage.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e5f);
			tg.pos.set(0f, 0f, -4000f * fk(t));
			WeaponSpec3D gor = launcher(true);
			gor.damage = 800f;
			gor.missileMaxSpeed = 300f;
			gor.missileAccel = 400f;
			gor.flightTime = 6f;
			gor.maxAmmo = 1;
			s3d.core.DemSpec d = new s3d.core.DemSpec();
			d.triggerMin = 500f;
			d.triggerMax = 550f;
			d.holdMinLo = 500f;
			d.holdMinHi = 550f;
			d.targetingTime = 1f;
			d.firingTime = 1.25f;
			d.laserRange = 600f;
			d.destroyWhenDone = true;
			WeaponSpec3D pay = beam(800f, true);
			pay.range = 700f;
			pay.burstDuration = 0.9f;
			pay.chargeUp = 0.05f;
			pay.chargeDown = 0.25f;
			d.payload = pay;
			gor.dem = d;
			sh.weapons.add(new WeaponMount("G", gor, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true));
			c.world = java.util.List.of(sh, tg);
			float closest = Float.MAX_VALUE;
			boolean painted = false, beamed = false;
			float hullAtFirstBeam = -1f;
			for (float tt = 0; tt < 20f; tt += DT) {
				float hullBefore = tg.hull;
				c.stepMount(sh, sh.weapons.get(0), tg.pos, tt < DT, tg, DT, t);
				c.stepMissiles(c.world, DT);
				c.stepEffects(DT);
				for (s3d.core.Missile3D ms : c.missiles) {
					closest = Math.min(closest, tg.surfaceDistance(ms.pos));
					painted |= ms.laserLevel > 0f;
				}
				if (!c.payloads.isEmpty() && !beamed) {
					beamed = true;
					hullAtFirstBeam = hullBefore; // untouched until the payload fires (the missile didn't ram it)
				}
			}
			float lost = tg.hullMax - tg.hull, expect = 800f * (0.9f + 0.05f * 0.5f + 0.25f * 0.5f);
			boolean ok = painted && beamed && hullAtFirstBeam == tg.hullMax && Math.abs(lost - expect) < expect * 0.1f
					&& closest > 500f * t.weaponRangeMult - 150f && c.missiles.isEmpty(); // it may brake ~100 past the hold line
			check("DEM stands off, paints, fires", ok,
					String.format("closest %.0f from the hull (hold %.0f+), laser %s, beam %s, hull lost %.0f (payload burst ~%.0f), missile gone %s",
							closest, 500f * t.weaponRangeMult, painted, beamed, lost, expect, c.missiles.isEmpty()));
		}

		// Missile warheads: Pilum's second stage keeps homing on a target that sidesteps after the split.
		int[] hitsOn = new int[2];
		for (int k = 0; k < 2; k++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e9f);
			tg.pos.set(0f, 0f, -3000f);
			WeaponSpec3D pil = launcher(true);
			pil.missileMaxSpeed = 250f;
			pil.flightTime = 30f;
			pil.maxAmmo = 1;
			pil.mirvCount = 1;
			pil.mirvSplitRange = 800f;
			pil.mirvSpreadSpeed = 0f;
			WeaponSpec3D head = launcher(true);
			head.damage = 500f;
			head.missileMaxSpeed = 300f;
			head.missileAccel = 400f;
			head.missileTurnRate = 70f;
			head.flightTime = 5f;
			if (k == 0) {
				head.kind = WeaponSpec3D.Kind.PROJECTILE; // the old way: a shell carrying on in a straight line
				head.projectileSpeed = 0f;
				head.range = 1700f;
			}
			pil.mirvWarhead = head;
			sh.weapons.add(new WeaponMount("P", pil, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true));
			c.world = java.util.List.of(sh, tg);
			boolean split = false;
			for (float tt = 0; tt < 25f; tt += DT) {
				c.stepMount(sh, sh.weapons.get(0), tg.pos, tt < DT, tg, DT, t);
				int before = c.effects.size();
				c.stepProjectiles(c.world, DT);
				c.stepMissiles(c.world, DT);
				for (int i = before; i < c.effects.size(); i++) if (c.effects.get(i).kind == s3d.core.Effect.Kind.DAMAGE_TEXT) hitsOn[k]++;
				c.stepEffects(DT);
				boolean warheadOut = k == 1 ? c.missiles.stream().anyMatch(m -> m.spec == head) : !c.projectiles.isEmpty();
				if (warheadOut && !split) {
					split = true;
					tg.vel.set(160f, 0f, 0f); // sidesteps once the warhead is on its way
				}
				tg.pos.addScaled(tg.vel, DT);
			}
		}
		check("pilum second stage homes", hitsOn[0] == 0 && hitsOn[1] >= 1, String.format("hits on a target sidestepping after the split: %d as a straight shell, %d as a guided second stage", hitsOn[0], hitsOn[1]));

		// Hydra-like: the missile spills six DEM warheads backwards; they swing round and each fires its payload.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e6f);
			tg.pos.set(0f, 0f, -4000f * fk(t));
			WeaponSpec3D hy = launcher(true);
			hy.missileMaxSpeed = 300f;
			hy.flightTime = 7f;
			hy.maxAmmo = 1;
			hy.mirvCount = 6;
			hy.mirvSplitRange = 1000f;
			hy.mirvArcDeg = 180f;
			hy.mirvArcOffsetDeg = 180f;
			hy.mirvSpreadSpeed = 200f;
			WeaponSpec3D wh = launcher(true);
			wh.damage = 500f;
			wh.missileMaxSpeed = 200f;
			wh.missileAccel = 400f;
			wh.missileTurnRate = 200f;
			wh.flightTime = 10f;
			s3d.core.DemSpec d = new s3d.core.DemSpec();
			d.minDelay = 5f;
			d.triggerMin = 400f;
			d.triggerMax = 450f;
			d.holdMinLo = d.holdMinHi = 150f;
			d.holdMax = 300f;
			d.triggerAngleLo = 30f;
			d.triggerAngleHi = 60f;
			d.driftFrac = 0.25f;
			d.turnBoost = 200f;
			d.targetingTime = 0f;
			d.firingTime = 1.25f;
			WeaponSpec3D pay = beam(500f, true);
			pay.range = 700f;
			pay.burstDuration = 0.9f;
			pay.chargeUp = 0.05f;
			pay.chargeDown = 0.25f;
			d.payload = pay;
			wh.dem = d;
			hy.mirvWarhead = wh;
			sh.weapons.add(new WeaponMount("H", hy, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true));
			c.world = java.util.List.of(sh, tg);
			java.util.Set<Object> fired = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			int maxWarheads = 0;
			for (float tt = 0; tt < 30f; tt += DT) {
				c.stepMount(sh, sh.weapons.get(0), tg.pos, tt < DT, tg, DT, t);
				c.stepProjectiles(c.world, DT);
				c.stepMissiles(c.world, DT);
				c.stepEffects(DT);
				fired.addAll(c.payloads);
				maxWarheads = Math.max(maxWarheads, (int) c.missiles.stream().filter(m -> m.spec == wh).count());
			}
			float lost = tg.hullMax - tg.hull;
			check("hydra warheads attack as DEMs", maxWarheads == 6 && fired.size() >= 5 && lost > 5 * 500f * 0.9f,
					String.format("%d warheads out, %d payloads fired, hull lost %.0f (each burst ~%.0f)", maxWarheads, fired.size(), lost, 500f * 1.05f));
		}
	}

	/** A rift / area blast as ExoticWeapons opens it (radius, full-damage core, friendly fire). */
	static WeaponSpec3D blastSpec(float damage, float radius, float core, boolean ff) {
		WeaponSpec3D b = gun("ENERGY", 1f, damage, 0f, 0f);
		b.proxRadius = radius;
		b.proxCore = core;
		b.blastFF = ff;
		b.fringeColor = new float[] {0.4f, 0.25f, 1f};
		return b;
	}

	/** Mounts spread over a hull (for EMP checks), hp 100 each. */
	static void studWithGuns(Ship3D s, int n) {
		for (int i = 0; i < n; i++) {
			float a = (float) (i * Math.PI * 2 / n);
			s.weapons.add(new WeaponMount("W" + i, gun("ENERGY", 1f, 10f, 500f, 900f), new Vec3((float) Math.cos(a) * 20f, 6f, (float) Math.sin(a) * 40f),
					new Vec3(0, 0, -1), 180f, false));
		}
	}

	static int disabled(Ship3D s) {
		int n = 0;
		for (WeaponMount m : s.weapons) if (m.disabledFor > 0f) n++;
		return n;
	}

	/** Steps everything for `seconds`: mount m fires at aim while fire(time) says so. */
	static void run(Combat c, Ship3D shooter, WeaponMount m, Vec3 aim, Ship3D missileTarget, float seconds, java.util.function.DoublePredicate fire, Tuning t) {
		for (float time = 0; time < seconds; time += DT) {
			if (m != null) c.stepMount(shooter, m, aim, fire.test(time), missileTarget, DT, t);
			c.stepProjectiles(c.world, DT);
			c.stepMissiles(c.world, DT);
			c.stepEffects(DT);
		}
	}

	/** Weapon scripts batch 2: Omega rifts and orbs, Threat fragment weapons, Dweller arcs and heals, flare launchers, missile blasts. */
	static void weaponScripts2(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		float k = t.weaponRangeMult;
		// Rift Cascade: a burst on a close target opens rifts that walk round its hull and add their damage.
		{
			float[] lost = new float[2];
			int rifts = 0;
			float worst = 0f;
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(7);
				Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
				sh.flux = null;
				Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e6f);
				tg.flux = null;
				tg.pos.set(0f, 0f, -500f);
				WeaponSpec3D cas = beam(1000f, true);
				cas.range = 1000f;
				cas.burstDuration = 0.5f;
				cas.cooldown = 10f;
				if (v == 1) {
					cas.rift = blastSpec(1000f, 100f, 50f, true);
					cas.riftMode = WeaponSpec3D.RiftMode.CASCADE;
					cas.riftInterval = 0.1f;
					cas.riftSpread = 175f;
					cas.riftReach = 200f;
					cas.riftMax = 5;
					cas.riftDelay = 0.5f;
				}
				WeaponMount m = new WeaponMount("C", cas, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true);
				sh.weapons.add(m);
				c.world = java.util.List.of(sh, tg);
				java.util.Set<s3d.core.Effect> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
				for (float tt = 0; tt < 3f; tt += DT) {
					c.stepMount(sh, m, tg.pos, tt < 0.2f, DT, t);
					c.stepEffects(DT);
					for (s3d.core.Effect e : c.effects) {
						if (e.kind != s3d.core.Effect.Kind.RIFT || !seen.add(e)) continue;
						rifts++;
						worst = Math.max(worst, tg.surfaceDistance(e.pos));
					}
				}
				lost[v] = damageTaken(tg);
			}
			rifts /= 2; // each rift is drawn as two tears
			check("rift cascade walks round target", rifts >= 3 && rifts <= 5 && lost[1] > lost[0] + 2000f && worst < 200f,
					String.format("%d rifts (up to 5, more the closer), farthest %.0f off the hull; damage %.0f beam alone, %.0f with the rifts", rifts, worst, lost[0], lost[1]));
		}

		// Rift Beam: rifts open on missiles near the beam (the beam itself misses them).
		{
			int[] killed = new int[2];
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(3);
				Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
				sh.flux = null;
				c.world = java.util.List.of(sh);
				WeaponSpec3D rb = beam(150f, false);
				rb.range = 600f;
				if (v == 1) {
					rb.rift = blastSpec(200f, 50f, 35f, false);
					rb.riftMode = WeaponSpec3D.RiftMode.SCATTER;
					rb.riftInterval = 0.5f;
					rb.riftReach = 100f;
					rb.riftSpread = 50f;
				}
				WeaponMount m = new WeaponMount("R", rb, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, false);
				WeaponSpec3D parked = launcher(true);
				parked.missileMaxSpeed = 0f; // they sit still
				for (int i = 0; i < 4; i++) {
					s3d.core.Missile3D ms = new s3d.core.Missile3D();
					ms.spec = parked;
					ms.owner = 1;
					ms.hp = 150f;
					ms.dir.set(0f, 0f, 1f);
					ms.pos.set(i % 2 == 0 ? 60f : -60f, 0f, -250f - i * 200f);
					c.missiles.add(ms);
				}
				run(c, sh, m, new Vec3(0f, 0f, -3000f), null, 4f, tt -> true, t);
				killed[v] = 4 - c.missiles.size();
			}
			check("rift beam rifts take missiles", killed[0] == 0 && killed[1] >= 2,
					String.format("missiles 60 off the beam destroyed: %d by the beam alone, %d with its rifts (of 4, in 4 s)", killed[0], killed[1]));
		}

		// Abyssal Glare: rifts where it lands, at least every half second.
		{
			float[] lost = new float[2];
			int rifts = 0;
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(5);
				Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
				sh.flux = null;
				Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e6f);
				tg.flux = null;
				tg.pos.set(0f, 0f, -600f);
				WeaponSpec3D gl = beam(500f, false);
				gl.range = 800f;
				if (v == 1) {
					gl.rift = blastSpec(200f, 75f, 50f, true);
					gl.riftMode = WeaponSpec3D.RiftMode.GLARE;
					gl.riftInterval = 0.25f;
					gl.riftChance = 0.2f;
					gl.riftMaxGap = 0.5f;
					gl.riftDamageMult = 0.5f;
				}
				WeaponMount m = new WeaponMount("G", gl, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true);
				c.world = java.util.List.of(sh, tg);
				java.util.Set<s3d.core.Effect> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
				for (float tt = 0; tt < 3f; tt += DT) {
					c.stepMount(sh, m, tg.pos, true, DT, t);
					c.stepEffects(DT);
					for (s3d.core.Effect e : c.effects) if (e.kind == s3d.core.Effect.Kind.RIFT && seen.add(e) && v == 1) rifts++;
				}
				lost[v] = damageTaken(tg);
			}
			rifts /= 2;
			check("abyssal glare rifts where it lands", rifts >= 4 && lost[1] > lost[0] + rifts * 100f * 0.8f,
					String.format("%d rifts in 3 s; damage %.0f beam alone, %.0f with the rifts (100 each)", rifts, lost[0], lost[1]));
		}

		// Rift Lightning: no shell; the lightning's rift hits for the rift's damage.
		{
			Combat c = new Combat();
			c.tuning = t;
			c.seed(23);
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			sh.flux = null;
			Ship3D tg = ship(game, hulls.get("wolf"), t, "target", 1, 0, 1e6f);
			tg.flux = null;
			tg.pos.set(0f, 0f, -500f);
			WeaponSpec3D rl = gun("ENERGY", 1f, 500f, 600f, 1e6f);
			rl.cooldown = 5f;
			rl.rift = blastSpec(500f, 80f, 40f, false);
			rl.rift.emp = 1000f;
			rl.riftMode = WeaponSpec3D.RiftMode.LIGHTNING;
			rl.riftSpeed = 10000f;
			WeaponMount m = new WeaponMount("L", rl, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true);
			c.world = java.util.List.of(sh, tg);
			int shells = 0;
			boolean arc = false;
			for (float tt = 0; tt < 1.5f; tt += DT) {
				c.stepMount(sh, m, tg.pos, true, DT, t);
				shells += c.projectiles.size();
				c.stepProjectiles(c.world, DT);
				c.stepEffects(DT);
				for (s3d.core.Effect e : c.effects) arc |= e.kind == s3d.core.Effect.Kind.ARC;
			}
			float lost = tg.hullMax - tg.hull;
			check("rift lightning strikes as a rift", shells == 0 && arc && lost > 400f && lost < 520f,
					String.format("shells %d, lightning drawn %s, target lost %.0f (one 500 rift)", shells, arc, lost));
		}

		// Reality Disruptor: an orb that flies through a ship in its way and arcs EMP; hulls it reaches repair at half speed.
		{
			Combat c = new Combat();
			c.tuning = t;
			c.seed(11);
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			sh.flux = null;
			Ship3D inPath = ship(game, hulls.get("wolf"), t, "inpath", 1, 0, 1e6f);
			inPath.flux = null;
			inPath.pos.set(0f, 0f, -300f);
			studWithGuns(inPath, 4);
			Ship3D side = ship(game, hulls.get("hammerhead"), t, "side", 1, 0, 1e6f);
			side.flux = null;
			side.pos.set(500f * fk(t), 0f, -1100f * fk(t));
			studWithGuns(side, 4);
			WeaponSpec3D rd = launcher(false);
			rd.type = "ENERGY";
			rd.damageType = "ENERGY";
			rd.armorMult = 1f;
			rd.damage = 25f;
			rd.emp = 1500f;
			rd.orb = true;
			rd.launchSpeed = 0f;
			rd.missileMaxSpeed = 200f;
			rd.missileAccel = 300f;
			rd.range = 1000f;
			rd.flightTime = 100f;
			rd.cooldown = 60f;
			rd.orbArcRange = 600f;
			rd.orbArcInterval = 0.1f;
			rd.orbArcDelay = 0.5f;
			rd.slowRepairMult = 0.5f;
			rd.slowRepairTime = 5f;
			WeaponMount m = new WeaponMount("O", rd, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true);
			c.world = java.util.List.of(sh, inPath, side);
			boolean passed = false;
			int sideOut = 0, pathOut = 0;
			float slowMult = 1f;
			for (float tt = 0; tt < 13f; tt += DT) {
				c.stepMount(sh, m, new Vec3(0f, 0f, -3000f), tt < DT, DT, t);
				c.stepProjectiles(c.world, DT);
				c.stepMissiles(c.world, DT);
				c.stepEffects(DT);
				for (s3d.core.Missile3D o : c.exotic.orbs) if (o.pos.z < inPath.pos.z - 150f && !o.flamedOut()) passed = true;
				sideOut = Math.max(sideOut, disabled(side));
				pathOut = Math.max(pathOut, disabled(inPath));
				slowMult = Math.min(slowMult, Math.min(side.repairRateMult, inPath.repairRateMult));
			}
			float arcsDealt = (inPath.hullMax - inPath.hull) + (side.hullMax - side.hull);
			check("reality disruptor orb", passed && pathOut >= 3 && sideOut >= 3 && slowMult == 0.5f && arcsDealt > 25f * 20f && c.exotic.orbs.isEmpty(),
					String.format("flew through the ship in its way %s, guns knocked out %d/4 in the way, %d/4 off to the side; arcs dealt %.0f; repairs x%.1f; orb gone at range %s",
							passed, pathOut, sideOut, arcsDealt, slowMult, c.exotic.orbs.isEmpty()));
		}

		// Voltaic Cannon: a hull hit throws 10-14 EMP arcs into the ship's components; a shield with no hard flux stops them all.
		{
			int[] out = new int[2];
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(13);
				Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
				sh.flux = null;
				Ship3D tg;
				if (v == 0) {
					tg = ship(game, hulls.get("enforcer"), t, "enforcer", 1, 0, 1e6f);
					tg.flux = null;
					tg.pos.set(0f, 0f, -900f);
				} else {
					tg = shieldedTarget(game, hulls, t, 0f);
				}
				studWithGuns(tg, 8);
				WeaponSpec3D vc = gun("ENERGY", 1f, 300f, 1500f, 1500f);
				vc.emp = 500f;
				vc.maxAmmo = 1;
				vc.empArcsMin = 10;
				vc.empArcsMax = 14;
				sh.weapons.add(new WeaponMount("V", vc, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true));
				duel(c, sh, tg, new Vec3(tg.pos), null, 2f, t);
				out[v] = disabled(tg);
			}
			check("voltaic cannon EMP storm", out[0] >= 8 && out[1] == 0,
					String.format("components knocked out of 8: %d by a hull hit, %d through a shield with no hard flux", out[0], out[1]));
		}

		// Voidblaster: shield hits are soft flux.
		{
			Combat c = new Combat();
			c.tuning = t;
			c.seed(23);
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			sh.flux = null;
			Ship3D tg = shieldedTarget(game, hulls, t, 0f);
			WeaponSpec3D vb = gun("HIGH_EXPLOSIVE", 2f, 200f, 1500f, 3000f);
			vb.shieldMult = 0.5f;
			vb.maxAmmo = 1;
			vb.softOnShields = true;
			sh.weapons.add(new WeaponMount("V", vb, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 10f, true));
			duel(c, sh, tg, new Vec3(tg.pos), null, 1f, t);
			check("voidblaster: soft flux on shields", tg.flux.flux > 50f && tg.flux.hardFlux == 0f,
					String.format("shield flux %.0f, of it hard %.0f", tg.flux.flux, tg.flux.hardFlux));
		}

		// Fragment weapons: need the swarm, spend it, wait for it to regrow; no swarm, no shot.
		{
			int[] launched = new int[2];
			float left = 0f, regrown = 0f;
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(23);
				Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
				sh.flux = null;
				if (v == 0) sh.fragments = new s3d.core.FragmentSwarm(20, 1f);
				Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e6f);
				tg.pos.set(0f, 0f, -6000f);
				WeaponSpec3D kf = launcher(true);
				kf.cooldown = 0.2f;
				kf.fragmentsNeeded = 5;
				kf.fragmentsSpent = 5;
				WeaponMount m = new WeaponMount("K", kf, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
				c.world = java.util.List.of(sh, tg);
				for (float tt = 0; tt < 1.2f; tt += DT) {
					c.stepMount(sh, m, tg.pos, true, tg, DT, t);
					if (m.firedThisFrame) launched[v]++;
					c.stepMissiles(c.world, DT);
					c.stepEffects(DT);
				}
				if (v == 0) {
					left = sh.fragments.count;
					for (float tt = 0; tt < 6f; tt += DT) c.stepEffects(DT);
					regrown = sh.fragments.count;
				}
			}
			check("fragment weapons spend the swarm", launched[0] == 4 && left < 5f && regrown >= 6f && launched[1] == 0,
					String.format("launched %d in 1.2 s from a swarm of 20 (5 each), %.1f left, %.1f after 6 s regrowth (1/s); %d without a swarm", launched[0], left, regrown,
							launched[1]));
		}

		// Kinetic Fragments: the escort fragments strike with the missile (100 kinetic each).
		{
			float[] lost = new float[2];
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(23);
				Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
				sh.flux = null;
				Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e6f);
				tg.flux = null;
				tg.pos.set(0f, 0f, -700f);
				WeaponSpec3D kf = launcher(true);
				kf.damage = 400f;
				kf.damageType = "KINETIC";
				kf.armorMult = 0.5f;
				kf.maxAmmo = 1;
				if (v == 1) {
					WeaponSpec3D f = gun("KINETIC", 0.5f, 100f, 0f, 0f);
					kf.fragmentStrike = f;
					kf.fragmentStrikes = 4;
				}
				sh.weapons.add(new WeaponMount("K", kf, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true));
				duel(c, sh, tg, new Vec3(tg.pos), tg, 4f, t);
				lost[v] = tg.hullMax - tg.hull;
			}
			check("kinetic fragments strike together", Math.abs(lost[1] - lost[0] - 400f) < 1f,
					String.format("hull lost %.0f from the missile alone, %.0f with its 4 fragments", lost[0], lost[1]));
		}

		// Defabrication Swarm: nothing up front on a hull, the damage eats in over ~10 s, fragments come back to the firer.
		{
			Combat c = new Combat();
			c.tuning = t;
			c.seed(23);
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			sh.flux = null;
			sh.fragments = new s3d.core.FragmentSwarm(20, 0f);
			sh.fragments.count = 10f;
			Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e6f);
			tg.flux = null;
			tg.pos.set(0f, 0f, -700f);
			WeaponSpec3D ds = launcher(true);
			ds.damage = 220f;
			ds.maxAmmo = 1;
			ds.dotTicks = 11;
			ds.dotRefundChance = 1f;
			WeaponMount m = new WeaponMount("D", ds, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
			sh.weapons.add(m);
			c.world = java.util.List.of(sh, tg);
			float atImpact = -1f;
			boolean out = false;
			for (float tt = 0; tt < 16f; tt += DT) {
				c.stepMount(sh, m, tg.pos, true, tg, DT, t);
				out |= !c.missiles.isEmpty();
				c.stepMissiles(c.world, DT);
				if (out && c.missiles.isEmpty() && atImpact < 0f) atImpact = tg.hullMax - tg.hull;
				c.stepEffects(DT);
			}
			float lost = tg.hullMax - tg.hull;
			check("defabrication swarm eats in", atImpact == 0f && Math.abs(lost - 220f) < 1f && sh.fragments.count >= 21f,
					String.format("hull lost %.0f at impact, %.0f after 15 s (220 over 11 ticks); firer's fragments 10 -> %.0f", atImpact, lost, sh.fragments.count));
		}

		// Hungering Rift: a hull hit heals a damaged friendly Dweller (not a damaged non-Dweller); a cap on how many are out.
		{
			Combat c = new Combat();
			c.tuning = t;
			c.seed(23);
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			sh.flux = null;
			Ship3D dweller = ship(game, hulls.get("eagle"), t, "dweller", 0, 0, 10000f);
			dweller.pos.set(3000f, 0f, 0f);
			dweller.hull = 5000f;
			dweller.riftHealMult = 1f;
			Ship3D plain = ship(game, hulls.get("wolf"), t, "plain", 0, 0, 2000f);
			plain.pos.set(-3000f, 0f, 0f);
			plain.hull = 500f;
			Ship3D tg = ship(game, hulls.get("enforcer"), t, "enforcer", 1, 0, 1e6f);
			tg.flux = null;
			tg.pos.set(0f, 0f, -700f);
			WeaponSpec3D hr = launcher(true);
			hr.damage = 600f;
			hr.maxAmmo = 1;
			hr.riftHeal = 1000f;
			WeaponMount m = new WeaponMount("H", hr, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
			c.world = java.util.List.of(sh, dweller, plain, tg);
			run(c, sh, m, new Vec3(tg.pos), tg, 4f, tt -> true, t);
			Combat c2 = new Combat();
			c2.tuning = t;
			Ship3D far = ship(game, hulls.get("enforcer"), t, "far", 1, 0, 1e6f);
			far.pos.set(0f, 0f, -9000f);
			WeaponSpec3D cap = launcher(true);
			cap.cooldown = 0.1f;
			cap.riftHeal = 1000f;
			cap.riftCap = 2;
			WeaponMount m2 = new WeaponMount("H2", cap, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
			c2.world = java.util.List.of(sh, far);
			run(c2, sh, m2, new Vec3(far.pos), far, 1f, tt -> true, t);
			check("hungering rift heals a dweller", dweller.hull == 6000f && plain.hull == 500f && c2.missiles.size() == 2,
					String.format("damaged Dweller hull 5000 -> %.0f, damaged non-Dweller 500 -> %.0f; %d out at once with a cap of 2", dweller.hull, plain.hull,
							c2.missiles.size()));
		}

		// Flare launcher: decoys fly only when missiles come, and draw some of them off.
		{
			float[] lost = new float[2];
			int flares = 0;
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(17);
				Ship3D me = ship(game, hulls.get("eagle"), t, "eagle", 0, 0, 1e6f);
				me.flux = null;
				Ship3D foe = ship(game, hulls.get("hammerhead"), t, "hh", 1, 400, 1e9f);
				foe.flux = null;
				foe.pos.set(0f, 0f, -3000f);
				WeaponSpec3D h = launcher(true);
				h.maxAmmo = 6;
				h.cooldown = 0.5f;
				WeaponMount hm = new WeaponMount("H", h, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
				WeaponSpec3D fl = launcher(true);
				fl.flareLauncher = true;
				fl.flightTime = 4f;
				fl.cooldown = 0.6f;
				fl.missileHp = 40f;
				WeaponMount fm = new WeaponMount("F", fl, new Vec3(0, 10, 0), new Vec3(0, 1, 0), 90f, false);
				c.world = java.util.List.of(me, foe);
				Vec3 aim = new Vec3();
				int idleShots = 0;
				for (float tt = 0; tt < 15f; tt += DT) {
					c.stepMount(foe, hm, me.pos, tt > 1f, me, DT, t);
					if (v == 1) {
						boolean want = c.exotic.flareWanted(me, fm, aim);
						c.stepMount(me, fm, want ? aim : null, want, null, DT, t);
						if (fm.firedThisFrame) {
							flares++;
							if (tt < 1f) idleShots++;
						}
					}
					c.stepMissiles(c.world, DT);
					c.stepEffects(DT);
				}
				lost[v] = me.hullMax - me.hull;
				if (v == 1 && idleShots > 0) lost[v] = Float.MAX_VALUE;
			}
			check("flare launcher decoys missiles", flares > 0 && lost[1] < lost[0],
					String.format("hull lost to 6 missiles: %.0f without flares, %.0f with %d flares (none before missiles flew)", lost[0], lost[1], flares));
		}

		// Missile area blast (vanilla explosionSpec): hits a ship next to the target; friendly fire only if the blast says so.
		{
			float[] nearLost = new float[3], friendLost = new float[3];
			for (int v = 0; v < 3; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(23);
				Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
				sh.flux = null;
				Ship3D tg = ship(game, hulls.get("wolf"), t, "target", 1, 0, 1e6f);
				tg.flux = null;
				tg.pos.set(0f, 0f, -800f);
				Ship3D near = ship(game, hulls.get("wolf"), t, "near", 1, 0, 1e6f);
				near.flux = null;
				near.pos.set(110f, 0f, -800f);
				Ship3D friend = ship(game, hulls.get("wolf"), t, "friend", 0, 0, 1e6f);
				friend.flux = null;
				friend.pos.set(-110f, 0f, -800f);
				WeaponSpec3D rp = launcher(true);
				rp.damage = 1000f;
				rp.maxAmmo = 1;
				if (v > 0) rp.impactBlast = blastSpec(1000f, 175f, 75f, v == 2);
				WeaponMount m = new WeaponMount("R", rp, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
				c.world = java.util.List.of(sh, tg, near, friend);
				run(c, sh, m, new Vec3(tg.pos), tg, 4f, tt -> true, t);
				nearLost[v] = near.hullMax - near.hull;
				friendLost[v] = friend.hullMax - friend.hull;
			}
			check("missile blasts hit neighbours", nearLost[0] == 0f && nearLost[1] > 200f && friendLost[1] == 0f && friendLost[2] > 200f,
					String.format("enemy beside the target lost %.0f without a blast, %.0f with; friend beside it %.0f (no FF), %.0f (FF blast)", nearLost[0],
							nearLost[1], friendLost[1], friendLost[2]));
		}

		// Rift Torpedo / Voltaic Cannon: fly through fighters without hitting them.
		{
			float[] fighterLost = new float[2], shipLost = new float[2];
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(23);
				Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
				sh.flux = null;
				Ship3D fighter = ship(game, hulls.get("wolf"), t, "fighter", 1, 0, 1e6f);
				fighter.fighter = true;
				fighter.flux = null;
				fighter.pos.set(0f, 0f, -400f);
				Ship3D tg = ship(game, hulls.get("eagle"), t, "eagle", 1, 0, 1e6f);
				tg.flux = null;
				tg.pos.set(0f, 0f, -1000f);
				WeaponSpec3D tp = launcher(false);
				tp.damage = 1000f;
				tp.maxAmmo = 1;
				tp.passFighters = v == 1;
				WeaponMount m = new WeaponMount("T", tp, new Vec3(0, 0, -30), new Vec3(0, 0, -1), 5f, true);
				c.world = java.util.List.of(sh, fighter, tg);
				run(c, sh, m, new Vec3(tg.pos), null, 4f, tt -> true, t);
				fighterLost[v] = fighter.hullMax - fighter.hull;
				shipLost[v] = tg.hullMax - tg.hull;
			}
			check("pass-through shots ignore fighters", fighterLost[0] > 0f && shipLost[0] == 0f && fighterLost[1] == 0f && shipLost[1] > 0f,
					String.format("fighter in the way lost %.0f / ship behind %.0f normally; %.0f / %.0f passing through", fighterLost[0], shipLost[0], fighterLost[1],
							shipLost[1]));
		}

		// Voltaic Discharge: arcs all round from the swarm (an enemy behind the gun), needs 10 fragments, spends none.
		{
			float[] lost = new float[2];
			float after = 0f;
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(23);
				Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
				sh.flux = null;
				sh.fragments = new s3d.core.FragmentSwarm(v == 0 ? 40 : 5, 0f);
				Ship3D behind = ship(game, hulls.get("wolf"), t, "behind", 1, 0, 1e6f);
				behind.flux = null;
				behind.pos.set(0f, 0f, 400f);
				WeaponSpec3D vd = gun("ENERGY", 1f, 50f, 500f, 1e4f);
				vd.emp = 100f;
				vd.cooldown = 0.25f;
				vd.arcGunConeDeg = 360f;
				vd.arcFromShip = true;
				vd.fragmentsNeeded = 10;
				WeaponMount m = new WeaponMount("V", vd, new Vec3(0, 5, -20), new Vec3(0, 0, -1), 10f, true);
				c.world = java.util.List.of(sh, behind);
				run(c, sh, m, new Vec3(0f, 0f, -1000f), null, 2f, tt -> true, t);
				lost[v] = behind.hullMax - behind.hull;
				if (v == 0) after = sh.fragments.count;
			}
			check("voltaic discharge arcs all round", lost[0] >= 300f && after == 40f && lost[1] == 0f,
					String.format("enemy behind the gun lost %.0f (50 every 0.25 s); swarm 40 -> %.0f; with 5 fragments it lost %.0f", lost[0], after, lost[1]));
		}

		// Inimical Emanation: reaches a phased ship; its repairs slow.
		{
			float[] lost = new float[2];
			float mult = 1f, left = 0f;
			for (int v = 0; v < 2; v++) {
				Combat c = new Combat();
				c.tuning = t;
				c.seed(23);
				Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
				sh.flux = null;
				Ship3D tg = ship(game, hulls.get("wolf"), t, "phased", 1, 0, 1e6f);
				tg.flux = null;
				tg.phased = true;
				tg.pos.set(0f, 0f, -600f);
				WeaponSpec3D ie = gun("ENERGY", 1f, 100f, 600f, 1e4f);
				ie.emp = 500f;
				ie.cooldown = 1f;
				ie.arcGunConeDeg = 30f;
				ie.arcFromShip = true;
				ie.arcHitsPhased = v == 1;
				ie.slowRepairMult = 0.5f;
				ie.slowRepairTime = 5f;
				WeaponMount m = new WeaponMount("I", ie, new Vec3(0, 5, -20), new Vec3(0, 0, -1), 10f, true);
				c.world = java.util.List.of(sh, tg);
				run(c, sh, m, new Vec3(tg.pos), null, 0.5f, tt -> true, t);
				lost[v] = tg.hullMax - tg.hull;
				if (v == 1) {
					mult = tg.repairRateMult;
					left = c.exotic.slowedRepairsLeft(tg);
				}
			}
			check("inimical emanation hits phased", lost[0] == 0f && lost[1] >= 100f && mult == 0.5f && left > 4f,
					String.format("phased ship lost %.0f to a plain arc gun, %.0f to the emanation; its repairs x%.1f for %.1f s more", lost[0], lost[1], mult, left));
		}

		// Fizzling at range: a missile with a long flight time flames out once it's its range away.
		{
			Combat c = new Combat();
			c.tuning = t;
			c.seed(23);
			Ship3D sh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			sh.flux = null;
			c.world = java.util.List.of(sh);
			WeaponSpec3D fz = launcher(false);
			fz.range = 500f;
			fz.flightTime = 100f;
			fz.maxAmmo = 1;
			fz.fizzleAtRange = true;
			WeaponMount m = new WeaponMount("Z", fz, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
			float at = -1f;
			for (float tt = 0; tt < 6f && at < 0f; tt += DT) {
				c.stepMount(sh, m, new Vec3(0f, 0f, -5000f), true, DT, t);
				c.stepMissiles(c.world, DT);
				for (s3d.core.Missile3D ms : c.missiles) if (ms.flamedOut()) at = ms.spawnPos.distance(ms.pos);
			}
			check("missiles fizzle at their range", Math.abs(at - 500f * k) < 30f, String.format("flamed out %.0f from launch (range %.0f in 3D)", at, 500f * k));
		}
	}

	/** A fighter of the given wing role launched from a carrier, with synthetic weapons. */
	static Ship3D fighter(File game, Map<String, GameData.Hull> hulls, Tuning t, String hullId, String role, Ship3D carrier, int n) throws Exception {
		Ship3D f = ship(game, hulls.get(hullId), t, hullId + n, carrier.owner, 50, 400);
		f.fighter = true;
		f.volume = f.volume.thickened(f.baseRadius * 0.35f); // as ShipBridge does for fighters
		f.carrier = carrier;
		f.wingRole = role;
		f.wingRange = 4000f;
		if ("BOMBER".equals(role)) {
			f.attackRunRange = 2000f;
			WeaponSpec3D torp = launcher(true);
			torp.damage = 1000f;
			torp.range = 1000f;
			torp.maxAmmo = 1;
			torp.missileMaxSpeed = 500f;
			WeaponMount tm = new WeaponMount("T", torp, new Vec3(0, 3, -5), new Vec3(0, 0, -1), 5f, true);
			tm.ammo = 1;
			f.weapons.add(tm);
		} else {
			WeaponSpec3D g = gun("ENERGY", 1f, 30f, 500f, 900f);
			g.cooldown = 0.25f;
			g.turnRate = 30f;
			f.weapons.add(new WeaponMount("G", g, new Vec3(0, 3, -8), new Vec3(0, 0, -1), 10f, true));
		}
		f.ai = new s3d.core.ShipAI3D(n);
		f.ai.homeDir.set(0f, 0f, carrier.owner == 0 ? 1f : -1f);
		f.pos.set(carrier.pos).add(new Vec3(n * 10f - 20f, carrier.radius * 0.3f, 0f));
		f.rot.set(carrier.rot);
		f.vel.set(carrier.vel);
		return f;
	}

	/** Steps a sim, handing landed fighters back like the host does; returns landings. */
	static int runWithLandings(s3d.core.Sim sim, float seconds) {
		s3d.core.PilotInput in = screen();
		int landings = 0;
		for (float time = 0; time < seconds; time += DT) {
			sim.update(in, DT, DT);
			for (java.util.Iterator<Ship3D> it = sim.ships.iterator(); it.hasNext(); ) {
				Ship3D s = it.next();
				if (s.fighter && s.landed) {
					it.remove();
					landings++;
				}
			}
		}
		return landings;
	}

	/** Fighters launch, strike, rearm, hunt fighters, regroup; PD and ships' guns treat them right. */
	static void fighterRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		// Sanity: plain shots at a fighter sitting still.
		{
			Combat c = new Combat();
			c.tuning = t;
			Ship3D sitter = ship(game, hulls.get("broadsword"), t, "broadsword", 1, 50, 1e9f);
			sitter.fighter = true;
			sitter.pos.set(0f, 0f, -300f);
			Ship3D shooter = ship(game, hulls.get("enforcer"), t, "enforcer", 0, 900, 1e9f);
			WeaponSpec3D g = gun("KINETIC", 0.5f, 40f, 1000f, 1000f);
			shooter.weapons.add(new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false));
			int hits = run(c, shooter, sitter, new Vec3(sitter.pos), 2f, t);
			check("shots hit a parked fighter", hits >= 6, hits + " hits of ~8 shots; fighter radius " + sitter.radius + ", scale " + sitter.scale);
		}

		// Strike: a Heron's bombers and fighters go for an Enforcer 3000 away; bombers land after their run.
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D heron = armed(game, hulls, t, "heron", "heron", 0);
		heron.weapons.clear();
		heron.landingLocal.set(0f, heron.volume.topAt(0f, 0f) + 2f, 0f);
		sim.addShip(heron);
		Ship3D enforcer = armed(game, hulls, t, "enforcer", "enforcer", 1);
		enforcer.weapons.clear();
		enforcer.hullMax = enforcer.hull = 1e6f;
		enforcer.pos.set(0f, 0f, -3000f);
		enforcer.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
		sim.addShip(enforcer);
		java.util.List<Ship3D> wing = new java.util.ArrayList<>();
		for (int i = 0; i < 3; i++) wing.add(fighter(game, hulls, t, "dagger", "BOMBER", heron, i));
		for (int i = 0; i < 3; i++) wing.add(fighter(game, hulls, t, "broadsword", "FIGHTER", heron, i + 3));
		for (Ship3D f : wing) sim.addShip(f);
		float before = damageTaken(enforcer);
		float maxLeash = 0f;
		int landed = 0;
		s3d.core.PilotInput in = screen();
		for (float time = 0; time < 60f; time += DT) {
			sim.update(in, DT, DT);
			for (Ship3D f : wing) if (sim.ships.contains(f)) maxLeash = Math.max(maxLeash, f.pos.distance(heron.pos));
			for (java.util.Iterator<Ship3D> it = sim.ships.iterator(); it.hasNext(); ) {
				Ship3D s = it.next();
				if (s.fighter && s.landed) {
					it.remove();
					if ("BOMBER".equals(s.wingRole)) landed++;
				}
			}
		}
		check("fighters strike and bombers rearm", damageTaken(enforcer) > before + 1000f && landed == 3 && maxLeash <= 4000f + 200f,
				String.format("damage on target %.0f, bombers landed to rearm %d/3, furthest from carrier %.0f (leash 4000)",
						damageTaken(enforcer) - before, landed, maxLeash));

		// Interceptors hunt fighters first: Talons vs Broadswords escorting a Dominator.
		sim = new s3d.core.Sim(t);
		Ship3D condor = armed(game, hulls, t, "condor", "condor", 0);
		condor.weapons.clear();
		sim.addShip(condor);
		Ship3D enemyCarrier = armed(game, hulls, t, "heron", "heron1", 1);
		enemyCarrier.weapons.clear();
		enemyCarrier.hullMax = enemyCarrier.hull = 1e6f;
		enemyCarrier.pos.set(0f, 0f, -2500f);
		enemyCarrier.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
		sim.addShip(enemyCarrier);
		java.util.List<Ship3D> talons = new java.util.ArrayList<>(), swords = new java.util.ArrayList<>();
		for (int i = 0; i < 4; i++) talons.add(fighter(game, hulls, t, "talon", "INTERCEPTOR", condor, i));
		for (int i = 0; i < 3; i++) swords.add(fighter(game, hulls, t, "broadsword", "FIGHTER", enemyCarrier, i + 10));
		for (Ship3D f : talons) sim.addShip(f);
		for (Ship3D f : swords) {
			f.hullMax = f.hull = 150f;
			f.weapons.clear(); // targets only
			sim.addShip(f);
		}
		int talonShots = 0;
		s3d.core.PilotInput in3 = screen();
		for (float time = 0; time < 40f; time += DT) {
			sim.update(in3, DT, DT);
			for (Ship3D f : talons) for (WeaponMount m : f.weapons) if (m.firedThisFrame && !f.destroyed) talonShots++;
		}
		int killed = 0;
		for (Ship3D f : swords) if (f.destroyed) killed++;
		check("interceptors hunt fighters", killed >= 2 && damageTaken(enemyCarrier) < 50f,
				String.format("%d/3 enemy fighters shot down (%d shots), damage on their carrier %.0f (interceptors left it alone)",
						killed, talonShots, damageTaken(enemyCarrier)));

		// Regroup: wings orbit the carrier instead of attacking.
		sim = new s3d.core.Sim(t);
		Ship3D carrier = armed(game, hulls, t, "heron", "heron", 0);
		carrier.weapons.clear();
		carrier.wingsRegroup = true;
		sim.addShip(carrier);
		Ship3D foe = armed(game, hulls, t, "enforcer", "enforcer", 1);
		foe.weapons.clear();
		foe.pos.set(0f, 0f, -3000f);
		sim.addShip(foe);
		java.util.List<Ship3D> escorts = new java.util.ArrayList<>();
		for (int i = 0; i < 3; i++) escorts.add(fighter(game, hulls, t, "broadsword", "FIGHTER", carrier, i));
		for (Ship3D f : escorts) sim.addShip(f);
		runWithLandings(sim, 20f);
		float meanDist = 0f;
		for (Ship3D f : escorts) meanDist += f.pos.distance(carrier.pos) / escorts.size();
		check("regrouped wings escort the carrier", meanDist < carrier.radius * 2.5f + 600f && damageTaken(foe) == 0f,
				String.format("mean distance from carrier %.0f (orbit %.0f), damage on enemy %.0f", meanDist, carrier.radius * 2.5f + 150f, damageTaken(foe)));

		// PD shoots fighters; an AI ship keeps its main guns on ships.
		sim = new s3d.core.Sim(t);
		Ship3D gunboat = armed(game, hulls, t, "enforcer", "enforcer", 0);
		gunboat.weapons.clear();
		WeaponSpec3D main = gun("KINETIC", 0.5f, 200f, 1200f, 900f);
		main.turnRate = 90f;
		WeaponSpec3D pdGun = gun("ENERGY", 1f, 40f, 500f / fk(t), 1000f); // PD-laser-like
		pdGun.pd = true;
		pdGun.cooldown = 0.1f;
		pdGun.turnRate = 360f;
		WeaponMount mainM = new WeaponMount("M", main, new Vec3(0, 8, -20), new Vec3(0, 0, -1), 300f, false);
		WeaponMount pdM = new WeaponMount("P", pdGun, new Vec3(0, 8, 0), new Vec3(0, 0, -1), 360f, false);
		gunboat.weapons.add(mainM);
		gunboat.weapons.add(pdM);
		gunboat.ai = new s3d.core.ShipAI3D(5);
		sim.addShip(gunboat);
		Ship3D enemyShip = armed(game, hulls, t, "hammerhead", "hammerhead", 1);
		enemyShip.weapons.clear();
		enemyShip.hullMax = enemyShip.hull = 1e6f;
		enemyShip.pos.set(0f, 0f, -1500f * fk(t));
		sim.addShip(enemyShip);
		Ship3D enemyCarrier2 = armed(game, hulls, t, "heron", "heron2", 1);
		enemyCarrier2.weapons.clear();
		enemyCarrier2.pos.set(3000f, 0f, -3000f);
		sim.addShip(enemyCarrier2);
		Ship3D raider = fighter(game, hulls, t, "broadsword", "FIGHTER", enemyCarrier2, 1);
		raider.pos.set(400f * fk(t), 150f * fk(t), -300f * fk(t)); // off the line to the enemy ship
		raider.hullMax = raider.hull = 300f;
		raider.weapons.clear();
		// It stays on the gunboat (a harmless gun, no carrier to go back to): this is about the gunboat's
		// PD, and a fighter heading home leaves PD reach sooner the longer ranges are.
		raider.weapons.add(new WeaponMount("R", gun("ENERGY", 1f, 0f, 300f, 900f), new Vec3(0, 2, -5), new Vec3(0, 0, -1), 20f, false));
		raider.carrier = null;
		sim.addShip(raider);
		int mainAtFighter = 0, frames = 0, pdShots = 0;

		s3d.core.PilotInput in2 = screen();
		for (float time = 0; time < 10f; time += DT) {
			sim.update(in2, DT, DT);
			frames++;
			if (pdM.firedThisFrame) pdShots++;

			if (!raider.destroyed && mainM.firedThisFrame) {
				Vec3 barrel = gunboat.rot.rotate(mainM.aimDir, new Vec3());
				Vec3 toRaider = new Vec3(raider.pos).sub(mainM.worldPos(gunboat, new Vec3())).normalize();
				if (barrel.dot(toRaider) > 0.99f) mainAtFighter++;
			}
		}
		check("PD kills fighters, main guns stay on ships", raider.destroyed && gunboat.ai.target == enemyShip && mainAtFighter == 0,
				String.format("fighter shot down=%s (hull %.0f, %d PD shots), AI target %s, main gun shots at the fighter %d", raider.destroyed,
						raider.hull, pdShots, gunboat.ai.target != null ? gunboat.ai.target.id : "none", mainAtFighter));
	}

	static s3d.core.ShipSystem3D system(String id, float in, float active, float out, float cooldown, int charges, float regen) {
		s3d.core.ShipSystem3D sys = new s3d.core.ShipSystem3D(id, id, s3d.core.ShipSystem3D.kindFor(id, "phasecloak".equals(id)));
		sys.chargeUp = in;
		sys.active = active;
		sys.chargeDown = out;
		sys.cooldown = cooldown;
		sys.maxAmmo = charges;
		sys.ammo = charges;
		sys.ammoPerSecond = regen;
		return sys;
	}

	/**
	 * A phase cloak as ShipBridge reads vanilla's on a Ziggurat: 2% of capacity to activate and per
	 * second, hard flux that can't dissipate while phased, coil stress to 33% top speed at 50% hard flux.
	 */
	static s3d.core.ShipSystem3D zigguratCloak(Ship3D s) {
		s3d.core.ShipSystem3D c = system("phasecloak", 0.5f, 0f, 0.5f, 2f, 0, 0f);
		c.toggle = true;
		c.fluxPerUse = c.fluxPerSecond = 0.02f * s.flux.maxFlux;
		c.hardFlux = true;
		c.hardDissipationAllowed = false;
		c.firingAllowed = false;
		c.coilMinSpeed = 0.33f;
		c.coilStressLevel = 0.5f;
		return c;
	}

	/**
	 * Phase cloak flux and speed as vanilla: the upkeep is hard flux that stays while phased (so no
	 * phase ship can stay in forever), paid on the ship's own 3x clock, and hard flux slows the phased
	 * ship down (phase coil stress) until it unphases and dissipates it.
	 */
	static void phaseRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.PilotInput in = screen();
		GameData.Hull zh = hulls.get("ziggurat");

		// You, phased 5 s: 320 to activate + 320/s, all hard, none of it dissipating; gone soon after you unphase.
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D me = ship(game, zh, t, "me", 0, 1500, 1e9f);
		me.cloak = zigguratCloak(me);
		sim.addShip(me);
		sim.setPlayer(me);
		me.cloak.activate(me);
		for (float tt = 0; tt < 5f; tt += DT) sim.update(in, DT, DT);
		float hardIn = me.flux.hardFlux;
		me.cloak.activate(me);
		for (float tt = 0; tt < 4f; tt += DT) sim.update(in, DT, DT);
		float hardOut = me.flux.hardFlux;
		float expect = 320f + 320f * 5f;
		check("phase upkeep is hard, stays phased", Math.abs(hardIn - expect) < expect * 0.08f && hardOut < hardIn * 0.1f,
				String.format("Ziggurat (16000 flux, 1200/s): hard flux after 5 s phased %.0f (vanilla %.0f), 4 s after unphasing %.0f", hardIn, expect, hardOut));

		// An AI phased for 2 s of battle time lives ~3x that on its own clock, and pays for it.
		sim = new s3d.core.Sim(t);
		Ship3D ai = ship(game, zh, t, "ai", 1, 1500, 1e9f);
		ai.cloak = zigguratCloak(ai);
		sim.addShip(ai);
		Ship3D watcher = ship(game, hulls.get("wolf"), t, "watcher", 0, 150, 1e9f);
		watcher.pos.set(0f, 0f, 6000f);
		sim.addShip(watcher);
		sim.setPlayer(watcher);
		ai.cloak.activate(ai);
		for (float tt = 0; tt < 2f; tt += DT) sim.update(in, DT, DT);
		check("AI phase upkeep on its own clock", ai.flux.hardFlux > 320f + 320f * 2f * 2.5f && ai.flux.hardFlux < 320f + 320f * 2f * 3f,
				String.format("hard flux after 2 s of battle phased %.0f (one clock would be %.0f, three %.0f)", ai.flux.hardFlux, 320f + 640f, 320f + 1920f));

		// Coil stress: full speed in, falling as hard flux builds, a third at half hard flux; back to full once it dissipates.
		sim = new s3d.core.Sim(t);
		me = ship(game, zh, t, "me", 0, 1500, 1e9f);
		me.cloak = zigguratCloak(me);
		me.flux.zeroFluxBoost = 0f; // the zero-flux boost goes as soon as the cloak costs flux; measure the coils alone
		me.throttle = 1f;
		sim.addShip(me);
		sim.setPlayer(me);
		for (float tt = 0; tt < 6f; tt += DT) sim.update(in, DT, DT);
		float v0 = me.speed();
		me.cloak.activate(me);
		for (float tt = 0; tt < 1f; tt += DT) sim.update(in, DT, DT);
		float v1 = me.speed();
		for (float tt = 0; tt < 5f; tt += DT) sim.update(in, DT, DT);
		float v6 = me.speed(), coil6 = me.sysTopSpeedMult;
		me.flux.flux = me.flux.hardFlux = me.flux.maxFlux * 0.5f;
		for (float tt = 0; tt < 3f; tt += DT) sim.update(in, DT, DT);
		float vHalf = me.speed();
		me.cloak.activate(me);
		for (float tt = 0; tt < 12f; tt += DT) sim.update(in, DT, DT);
		float vBack = me.speed();
		check("phase coil stress slows the ship", v1 > v0 * 0.9f && v6 < v1 && Math.abs(v6 / v0 - coil6) < 0.04f
						&& Math.abs(vHalf / v0 - 0.33f) < 0.05f && vBack > v0 * 0.97f,
				String.format("top speed %.0f; phased 1 s %.0f%%, 6 s %.0f%%, at 50%% hard flux %.0f%%; unphased 12 s later %.0f%%",
						v0, v1 / v0 * 100f, v6 / v0 * 100f, vHalf / v0 * 100f, vBack / v0 * 100f));

		// The AI boss under fire can't hide in phase all fight: it has to come out to let hard flux go.
		sim = new s3d.core.Sim(t);
		Ship3D zig = ship(game, zh, t, "zig", 1, 1500, 14000f);
		zig.cloak = zigguratCloak(zig);
		zig.ai = new s3d.core.ShipAI3D(5);
		zig.ai.homeDir.set(0f, 0f, -1f);
		sim.addShip(zig);
		Ship3D shooter = ship(game, hulls.get("lasher"), t, "lasher", 0, 300, 1e9f);
		shooter.pos.set(0f, 0f, 1200f * fk(t));
		WeaponSpec3D g = gun("HIGH_EXPLOSIVE", 2f, 150f, 900f, 1100f);
		g.cooldown = 0.15f;
		WeaponMount gm = new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false);
		shooter.weapons.add(gm);
		sim.addShip(shooter);
		int frames = 0, phasedFrames = 0, phaseOuts = 0;
		boolean was = false;
		float peakHard = 0f;
		for (float time = 0; time < 40f; time += DT) {
			sim.update(in, DT, DT);
			sim.combat.stepMount(shooter, gm, zig.pos, true, DT, t);
			zig.hull = zig.hullMax; // only its phasing is being measured
			boolean now = zig.cloak.on();
			if (was && !now) phaseOuts++;
			was = now;
			frames++;
			if (zig.phased) phasedFrames++;
			peakHard = Math.max(peakHard, zig.flux.hardFlux / zig.flux.maxFlux);
		}
		check("AI phase ship has to unphase", phasedFrames > 0 && phasedFrames < frames * 0.85f && phaseOuts >= 1,
				String.format("40 s under fire: phased %.0f%% of the time, came out %d times, hard flux peaked at %.0f%%",
						phasedFrames * 100f / frames, phaseOuts, peakHard * 100f));
	}

	/** Rows as the game's CSV loader gives them: one JSONObject per line, cells by column name (split on '|' here). */
	private static org.json.JSONArray csvRows(String columns, String... lines) throws Exception {
		String[] cols = columns.split("\\|");
		org.json.JSONArray a = new org.json.JSONArray();
		for (String line : lines) {
			String[] cells = line.split("\\|", -1);
			org.json.JSONObject o = new org.json.JSONObject();
			for (int i = 0; i < cols.length; i++) o.put(cols[i], i < cells.length ? cells[i] : "");
			a.put(o);
		}
		return a;
	}

	private static boolean problem(s3d.core.ModData d, String text) {
		for (String p : d.problems) if (p.contains(text)) return true;
		return false;
	}

	/**
	 * Other mods' data/config/s3d files (ModData): systems.csv maps a system to a 3D kind with its own
	 * numbers, weapon_effects.csv a script to an effect or a vanilla script's 3D version, hulls.csv a
	 * hull to vanilla 2D battles. Bad rows are named and skipped; a later mod's row replaces an earlier one's.
	 */
	static void modData(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.ModData d = new s3d.core.ModData();
		d.addSystems("modA", csvRows("id|kind|params",
				"mod_overdrive|BURN_DRIVE|burnDriveSpeed=600 burnDriveTurnMult=0.8",
				"#mod_commented|DAMPER|",
				"mod_cloak|IGNORE|",
				"mod_warp|WARP_DRIVE|",
				"mod_shell|damper|damperTaken=0.5"));
		d.addSystems("modB", csvRows("id|kind|params", "mod_cloak|DAMPER|"));
		s3d.core.ModData.SystemRow over = d.system("mod_overdrive"), cloak = d.system("mod_cloak"), shell = d.system("mod_shell");
		check("mod data: systems.csv rows", over != null && over.kind == s3d.core.ShipSystem3D.Kind.BURN_DRIVE && d.system("#mod_commented") == null
				&& d.system("mod_warp") == null && cloak != null && "modB".equals(cloak.mod) && cloak.kind == s3d.core.ShipSystem3D.Kind.DAMPER
				&& shell != null && shell.kind == s3d.core.ShipSystem3D.Kind.DAMPER && shell.params == null
				&& problem(d, "unknown kind 'WARP_DRIVE'") && problem(d, "unknown param 'damperTaken'") && problem(d, "replaces modA's row")
				&& d.problems.size() == 3 && s3d.core.Tuning.systemKeys().size() == 27,
				d.read + ", problems " + d.problems);

		// Its own numbers: a copy of the battle's tuning with the row's keys on top, the battle's own untouched.
		Tuning own = over.tuningFor(t);
		check("mod data: system's own numbers", own != null && own.sysBurnSpeed == 600f && own.sysBurnTurnMult == 0.8f && t.sysBurnSpeed != 600f
				&& own.sysDamperTaken == t.sysDamperTaken && own.weaponRangeMult == t.weaponRangeMult && over.tuningFor(t) == own
				&& cloak.tuningFor(t) == null,
				String.format("burn speed %.0f (battle's %.0f), turn %.2f (battle's %.2f)", own != null ? own.sysBurnSpeed : -1f, t.sysBurnSpeed,
						own != null ? own.sysBurnTurnMult : -1f, t.sysBurnTurnMult));

		// A modded system mapped to Burn Drive with burnDriveSpeed=600 outruns one on the battle's numbers.
		float[] speed = new float[2];
		for (int k = 0; k < 2; k++) {
			s3d.core.PilotInput in = screen();
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D wolf = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			wolf.system = new s3d.core.ShipSystem3D("mod_overdrive", "Overdrive", over.kind);
			wolf.system.chargeUp = 1f;
			wolf.system.active = 5f;
			wolf.system.chargeDown = 1f;
			wolf.system.cooldown = 10f;
			wolf.system.toggle = true;
			if (k == 1) wolf.system.tuning = own;
			sim.addShip(wolf);
			sim.setPlayer(wolf);
			wolf.throttle = 1f;
			for (float tt = 0; tt < 4f; tt += DT) sim.update(in, DT, DT);
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			for (float tt = 0; tt < 3f; tt += DT) sim.update(in, DT, DT);
			speed[k] = wolf.speed();
		}
		check("mod data: system plays its numbers", speed[1] > speed[0] * 1.2f,
				String.format("Overdrive (BURN_DRIVE) after 3 s: %.0f on the battle's numbers, %.0f with burnDriveSpeed=600", speed[0], speed[1]));

		d.addEffects("modA", csvRows("script|effect|params",
				"com.foo.MyBreach|ARMOR_STRIP|amount=300",
				"MyBurn|armor_burn|amount=200, ticks=5",
				"MyIon|IonCannonOnHitEffect|",
				"MyLights|VISUAL|",
				"MyBroken|ARMOR_STRIP|",
				"MyArc|EXTRA_ARC|chance=0.5 pierce=1 colour=2",
				"MyOdd|NOT A NAME|"));
		WeaponSpec3D a = new WeaponSpec3D(), b = new WeaponSpec3D(), c = new WeaponSpec3D();
		String na = s3d.core.ModData.apply(d.effect("com.foo.MyBreach"), a);
		String nb = s3d.core.ModData.apply(d.effect("org.bar.MyBurn"), b);
		String nc = s3d.core.ModData.apply(d.effect("MyArc"), c);
		s3d.core.ModData.EffectRow ion = d.effect("x.y.MyIon"), lights = d.effect("MyLights");
		check("mod data: weapon_effects.csv rows", a.armorBreak == 300f && b.armorBurn == 200f && b.armorBurnTicks == 5 && c.bonusArcChance == 0.5f
				&& c.bonusArcPierces && d.effect("org.other.MyBreach") == null // a full-name row matches only that class
				&& ion != null && "IonCannonOnHitEffect".equals(ion.vanillaScript) && ion.effect == null
				&& lights != null && lights.effect == s3d.core.ModData.Effect.VISUAL && d.effect("MyBroken") == null && d.effect("MyOdd") == null
				&& problem(d, "ARMOR_STRIP needs amount") && problem(d, "unknown param 'colour'") && problem(d, "unknown effect 'NOT A NAME'"),
				na + " / " + nb + " / " + nc + "; as vanilla: " + (ion != null ? ion.vanillaScript : null));

		d.addHulls("modA", csvRows("id|battle", "wolf|2d", "hound|sideways", "lasher|3D"));
		check("mod data: hulls.csv rows", d.hull("wolf") != null && d.hull("wolf").vanilla2d && d.hull("hound") == null && d.hull("lasher") != null
				&& !d.hull("lasher").vanilla2d && problem(d, "battle should be 2d or 3d"),
				d.read.toString());
	}

	/** Each system kind does its job; the AI uses them at sensible moments. */
	static void systemRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.PilotInput in = screen();

		// Burn Drive: much faster in a straight line, still steers (slower). Vanilla's spec says no turning;
		// the 3D one keeps sysBurnTurnMult of it so you can manoeuvre.
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D wolf = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		wolf.system = system("burndrive", 1f, 5f, 1f, 10f, 0, 0f);
		wolf.system.toggle = true;
		wolf.system.turningAllowed = false; // as vanilla's ship_systems.csv has it
		sim.addShip(wolf);
		sim.setPlayer(wolf);
		wolf.throttle = 1f;
		for (float tt = 0; tt < 4f; tt += DT) sim.update(in, DT, DT);
		float plain = wolf.speed();
		in.useSystem = true;
		sim.update(in, DT, DT);
		in.clearPressed();
		in.mouseX = 1280f; // full right stick
		float yaw0 = (float) Math.atan2(-wolf.rot.forward(new Vec3()).x, -wolf.rot.forward(new Vec3()).z);
		for (float tt = 0; tt < 3f; tt += DT) sim.update(in, DT, DT);
		float yaw1 = (float) Math.atan2(-wolf.rot.forward(new Vec3()).x, -wolf.rot.forward(new Vec3()).z);
		float burnt = wolf.speed();
		in.mouseX = 640f;
		float turned = Math.abs((float) Math.toDegrees(yaw1 - yaw0));
		check("burn drive: fast, still steers but slower", burnt > plain * 2f && turned > 15f && turned < 75f,
				String.format("speed %.0f -> %.0f, turned %.0f deg in 3 s at full stick (normally ~90)", plain, burnt, turned));

		// Phase Skimmer: three charges, each a jump; a fourth press does nothing.
		sim = new s3d.core.Sim(t);
		Ship3D skim = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		skim.system = system("displacer", 0.25f, 0f, 0.25f, 0f, 3, 0.1f);
		sim.addShip(skim);
		sim.setPlayer(skim);
		int jumps = 0;
		for (int k = 0; k < 4; k++) {
			Vec3 before = new Vec3(skim.pos);
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			for (float tt = 0; tt < 0.6f; tt += DT) sim.update(in, DT, DT);
			if (skim.pos.distance(before) > t.sysDisplacerDistance * 0.5f) jumps++;
		}
		check("phase skimmer: jumps per charge", jumps == 3, jumps + " jumps from 4 presses with 3 charges");

		// Ammo Feeder doubles ballistic fire; High Energy Focus adds energy damage.
		int[] shots = new int[2];
		for (int k = 0; k < 2; k++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D gb = ship(game, hulls.get("enforcer"), t, "gb", 0, 900, 1e9f);
			WeaponSpec3D g = gun("KINETIC", 0.5f, 50f, 1000f, 1000f);
			g.type = "BALLISTIC";
			g.cooldown = 0.5f;
			WeaponMount gm = new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false);
			gb.weapons.add(gm);
			if (k == 1) gb.sysBallisticRofMult = 2f;
			for (float tt = 0; tt < 4f; tt += DT) {
				c.stepMount(gb, gm, new Vec3(0f, 0f, -500f), true, DT, t);
				if (gm.firedThisFrame) shots[k]++;
			}
		}
		check("ammo feeder doubles ballistic rate", shots[1] >= shots[0] * 2 - 2, shots[0] + " shots normally, " + shots[1] + " fed");

		// Damper Field cuts hull/armor damage; Fortress Shield cuts shield flux.
		float[] lost = new float[2];
		for (int k = 0; k < 2; k++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D tgt = ship(game, hulls.get("hammerhead"), t, "hh", 1, 300, 1e9f);
			tgt.pos.set(0f, 0f, -600f);
			if (k == 1) tgt.sysDamageTakenMult = t.sysDamperTaken;
			Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
			sh.weapons.add(new WeaponMount("G", gun("ENERGY", 1f, 100f, 1000f, 1000f), new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false));
			run(c, sh, tgt, new Vec3(tgt.pos), 3f, t);
			lost[k] = damageTaken(tgt);
		}
		check("damper field cuts damage", lost[1] < lost[0] * 0.45f && lost[1] > 0f, String.format("damage taken %.0f normally, %.0f damped", lost[0], lost[1]));

		// Phase cloak: shots pass through; the phased ship can't fire.
		Combat c = new Combat();
		c.tuning = t;
		Ship3D ghost = ship(game, hulls.get("wolf"), t, "afflictor", 1, 150, 1e9f);
		ghost.pos.set(0f, 0f, -500f);
		ghost.phased = true;
		Ship3D sh = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		sh.weapons.add(new WeaponMount("G", gun("ENERGY", 1f, 100f, 1000f, 1000f), new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false));
		int hitsPhased = run(c, sh, ghost, new Vec3(ghost.pos), 2f, t);
		WeaponMount gg = new WeaponMount("G2", gun("ENERGY", 1f, 100f, 1000f, 1000f), new Vec3(0, 5, 0), new Vec3(0, 0, 1), 360f, false);
		ghost.weapons.add(gg);
		int ghostShots = 0;
		for (float tt = 0; tt < 1f; tt += DT) {
			c.stepMount(ghost, gg, sh.pos, true, DT, t);
			if (gg.firedThisFrame) ghostShots++;
		}
		check("phased: untouchable and can't fire", hitsPhased == 0 && ghostShots == 0 && damageTaken(ghost) == 0f,
				hitsPhased + " hits on the phased ship, " + ghostShots + " shots from it");

		// Flares lure a guided missile away from its target.
		int[] hitsOn = new int[2];
		for (int k = 0; k < 2; k++) {
			sim = new s3d.core.Sim(t);
			Ship3D prey = ship(game, hulls.get("hammerhead"), t, "prey", 0, 400, 1e9f);
			prey.system = system("flarelauncher", 0f, 0f, 0f, 5f, 5, 0f);
			if (k == 1) prey.ai = new s3d.core.ShipAI3D(1);
			sim.addShip(prey);
			Ship3D launcherShip = ship(game, hulls.get("enforcer"), t, "launcher", 1, 900, 1e9f);
			launcherShip.pos.set(0f, 0f, -2000f);
			sim.addShip(launcherShip);
			WeaponSpec3D harpoon = launcher(true);
			harpoon.missileMaxSpeed = 350f;
			harpoon.cooldown = 1.5f;
			harpoon.damage = 500f;
			WeaponMount hm = new WeaponMount("H", harpoon, new Vec3(0, 10, -40), new Vec3(0, 0, 1), 360f, false);
			launcherShip.weapons.add(hm);
			float before = damageTaken(prey);
			for (float tt = 0; tt < 14f; tt += DT) {
				sim.update(in, DT, DT);
				sim.combat.stepMount(launcherShip, hm, prey.pos, tt < 8f, prey, DT, t);
			}
			hitsOn[k] = Math.round((damageTaken(prey) - before) / 100f);
		}
		check("flares lure missiles away", hitsOn[1] < hitsOn[0], String.format("damage taken (x100) without flares %d, with the AI using flares %d", hitsOn[0], hitsOn[1]));

		// Your phase cloak slows everyone else; an AI's phase cloak doesn't slow the world.
		float[] otherMoved = new float[2], phaserMoved = new float[2];
		for (int k = 0; k < 2; k++) {
			sim = new s3d.core.Sim(t);
			Ship3D phaser = ship(game, hulls.get("wolf"), t, "phaser", 0, 150, 1e9f);
			phaser.cloak = system("phasecloak", 0f, 0f, 0f, 0f, 0, 0f);
			phaser.cloak.toggle = true;
			phaser.throttle = 1f;
			sim.addShip(phaser);
			Ship3D other = ship(game, hulls.get("lasher"), t, "other", 1, 150, 1e9f);
			other.mover = new ScriptedMover(new Vec3(0f, 0f, -3000f), 800f, 100f, 0f); // fixed course at 100/s of world time
			sim.addShip(other);
			if (k == 0) {
				sim.setPlayer(phaser);
			} else {
				Ship3D me = ship(game, hulls.get("hound"), t, "me", 0, 150, 1e9f);
				me.pos.set(0f, 0f, 3000f);
				sim.addShip(me);
				sim.setPlayer(me);
			}
			for (float tt = 0; tt < 2f; tt += DT) sim.update(in, DT, DT); // up to speed
			phaser.cloak.activate(phaser);
			sim.update(in, DT, DT);
			Vec3 p0 = new Vec3(phaser.pos), o0 = new Vec3(other.pos);
			float arc = 0f;
			Vec3 last = new Vec3(other.pos);
			for (float tt = 0; tt < 1f; tt += DT) {
				sim.update(in, DT, DT);
				arc += other.pos.distance(last);
				last.set(other.pos);
			}
			otherMoved[k] = arc;
			phaserMoved[k] = phaser.pos.distance(p0);
		}
		check("your phase slows the world, AI phase doesn't", otherMoved[0] < otherMoved[1] * 0.45f && phaserMoved[0] > 60f,
				String.format("per real second: with you phased the other ship moved %.0f (you %.0f); with an AI phased it moved %.0f",
						otherMoved[0], phaserMoved[0], otherMoved[1]));

		// AI uses Burn Drive to close a long gap.
		sim = new s3d.core.Sim(t);
		Ship3D runner = armed(game, hulls, t, "wolf", "runner", 0);
		runner.system = system("burndrive", 1f, 5f, 1f, 10f, 0, 0f);
		runner.system.toggle = true;
		runner.ai = new s3d.core.ShipAI3D(2);
		sim.addShip(runner);
		Ship3D far = armed(game, hulls, t, "dominator", "far", 1);
		far.weapons.clear();
		far.pos.set(0f, 0f, -6000f);
		sim.addShip(far);
		boolean used = false;
		for (float tt = 0; tt < 20f; tt += DT) {
			sim.update(in, DT, DT);
			if (runner.system.on()) used = true;
		}
		check("AI burns in to close the gap", used, "burn drive used by the AI: " + used);
	}

	/** Steps a lone ship's system for `seconds` in sim (ship modifiers reset each frame, as the sim does). */
	static void stepSystem(s3d.core.Sim sim, Ship3D s, float seconds) {
		for (float tt = 0; tt < seconds; tt += DT) {
			s.resetSystemEffects();
			s.system.step(s, sim, DT);
		}
	}

	/** Ship systems batch 2: autoforge, entropy, teleporter, mines, canister flak, targeting feed, lidar, termination sequence. */
	/** Combat Burn, Orion Device / Nova Burst trains, Quantum Disruptor, launcher drones' orbits. */
	static void systemsBatch3(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.PilotInput in = screen();
		float vk = t.speedMult;

		// Combat Burn: +100 top speed while on, and the ship still turns.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D on = ship(game, hulls.get("eagle"), t, "onslaught", 0, 400, 1e9f);
			on.system = system("combat_burn", 1f, 0f, 1f, 0f, 0, 0f);
			on.system.toggle = true;
			sim.addShip(on);
			sim.setPlayer(on);
			on.throttle = 1f;
			for (float tt = 0; tt < 6f; tt += DT) sim.update(in, DT, DT);
			float plain = on.speed();
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			for (float tt = 0; tt < 6f; tt += DT) sim.update(in, DT, DT);
			float burnt = on.speed();
			in.mouseX = 1280f;
			float yaw0 = (float) Math.atan2(-on.rot.forward(new Vec3()).x, -on.rot.forward(new Vec3()).z);
			for (float tt = 0; tt < 2f; tt += DT) sim.update(in, DT, DT);
			float yaw1 = (float) Math.atan2(-on.rot.forward(new Vec3()).x, -on.rot.forward(new Vec3()).z);
			in.mouseX = 640f;
			float gain = burnt - plain, turned = Math.abs((float) Math.toDegrees(yaw1 - yaw0));
			check("combat burn: +100 top speed, still turns", gain > 70f * vk && gain < 130f * vk && turned > 5f,
					String.format("speed %.0f -> %.0f (+%.0f, vanilla +100 = %.0f in 3D), turned %.0f deg in 2 s", plain, burnt, gain, 100f * vk, turned));
		}

		// Orion Device: one press fires all four charges a second apart; the jolts stack into a sprint, then it brakes.
		for (String id : new String[] {"orion_device", "nova_burst"}) {
			boolean nova = id.equals("nova_burst");
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D ret = ship(game, hulls.get("retribution") != null ? hulls.get("retribution") : hulls.get("eagle"), t, "ret", 0, 400, 1e9f);
			ret.system = nova ? system(id, 0.5f, 0.001f, 0f, 1f, 2, 0.1f) : system(id, 0f, 0.001f, 0f, 1f, 4, 0.2f);
			sim.addShip(ret);
			sim.setPlayer(ret);
			ret.throttle = 1f;
			for (float tt = 0; tt < 6f; tt += DT) sim.update(in, DT, DT);
			float normal = ret.speed();
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			int blasts = 0;
			float last = -1f, minGap = 99f, maxGap = 0f, peak = 0f, prevSince = 99f, tt = 0f, trainEnd = 0f;
			for (; tt < 12f; tt += DT) {
				sim.update(in, DT, DT);
				float since = ret.system.orion.sinceDetonation;
				if (since < prevSince) {
					blasts++;
					if (last >= 0f) {
						minGap = Math.min(minGap, tt - last);
						maxGap = Math.max(maxGap, tt - last);
					}
					last = tt;
				}
				prevSince = since;
				peak = Math.max(peak, ret.speed());
				if (!ret.system.orion.busy() && trainEnd == 0f && blasts > 0) trainEnd = tt;
			}
			float after = ret.speed();
			int want = nova ? 2 : 4;
			boolean ok = blasts == want && (want < 2 || minGap > 0.9f && maxGap < 1.8f) && peak > normal + 300f * vk && after < normal * 1.15f;
			check((nova ? "nova burst" : "orion device") + ": one press, every charge, jolts stack, then brakes", ok,
					String.format("%d detonations (want %d), %.2f-%.2f s apart; speed %.0f -> peak %.0f -> %.0f at 12 s", blasts, want,
							minGap == 99f ? 0f : minGap, maxGap, normal, peak, after));
		}

		// The AI fires the whole Orion train to close a long gap when it's lined up.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D tgt = ship(game, hulls.get("hammerhead"), t, "tgt", 1, 400, 1e9f);
			tgt.pos.set(0f, 0f, -9000f);
			sim.addShip(tgt);
			Ship3D ret = ship(game, hulls.get("retribution") != null ? hulls.get("retribution") : hulls.get("eagle"), t, "ret", 0, 400, 1e9f);
			ret.system = system("orion_device", 0f, 0.001f, 0f, 1f, 4, 0.2f);
			sim.addShip(ret);
			ret.ai = new s3d.core.ShipAI3D(3);
			ret.ai.profile(ret, t);
			for (float tt = 0; tt < 4f; tt += DT) sim.update(in, DT, DT);
			check("AI fires the Orion train at a far target", ret.system.ammo < 3.5f,
					String.format("charges left %.1f of 4 after 4 s lined up 9000 away", ret.system.ammo));
		}

		// Quantum Disruptor: the target (shield up) is knocked into a short pink overload; it drops its shield.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D tgt = ship(game, hulls.get("hammerhead"), t, "tgt", 1, 400, 1e9f);
			tgt.flux = flux(hulls.get("hammerhead"), t);
			tgt.flux.shieldOn = true;
			tgt.flux.unfold = 1f;
			tgt.pos.set(0f, 0f, -450f);
			sim.addShip(tgt);
			Ship3D har = ship(game, hulls.get("wolf"), t, "harbinger", 0, 150, 1e9f);
			har.system = system("acausaldisruptor", 0.5f, 0f, 1f, 20f, 0, 0f);
			har.system.range = 500f;
			har.system.disruptSeconds = 1f;
			sim.addShip(har);
			sim.setPlayer(har);
			sim.target = tgt;
			sim.update(in, DT, DT);
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			for (float tt = 0; tt < 0.7f; tt += DT) sim.update(in, DT, DT);
			boolean over = tgt.flux.overloaded(), shieldDown = !tgt.flux.shieldUp(), pink = tgt.overloadTint != null;
			for (float tt = 0; tt < 1.5f; tt += DT) sim.update(in, DT, DT);
			check("quantum disruptor overloads its target briefly", over && shieldDown && pink && !tgt.flux.overloaded(),
					String.format("overloaded %s, shield down %s, pink arcs %s; still overloaded 1.5 s later: %s", over, shieldDown, pink, tgt.flux.overloaded()));
		}

		// Launcher drones ride their orbit round the mothership, beyond its hull, facing out.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D host = ship(game, hulls.get("eagle"), t, "station", 1, 400, 1e9f);
			host.anchored = true;
			sim.addShip(host);
			Ship3D d = ship(game, hulls.get("wolf"), t, "drone", 1, 150, 1e9f);
			d.fighter = true;
			d.drone = true;
			d.carrier = host;
			d.droneOrbitRadius = 300f;
			d.droneOrbitSpeed = 100f;
			d.droneOrbitDir = 1;
			sim.addShip(d);
			d.pos.set(host.pos);
			d.ai = new s3d.core.ShipAI3D(5);
			d.ai.profile(d, t);
			for (float tt = 0; tt < 8f; tt += DT) sim.update(in, DT, DT); // launch out (300/s) and settle
			float a0 = d.droneOrbitAngle;
			for (float tt = 0; tt < 4f; tt += DT) sim.update(in, DT, DT);
			float r = d.pos.distance(host.pos), want = host.radius + 300f;
			float swept = d.droneOrbitAngle - a0, wantSwept = 100f * vk * 4f / want;
			Vec3 out = new Vec3(d.pos).sub(host.pos).normalize();
			float facing = d.rot.forward(new Vec3()).dot(out);
			check("launcher drones orbit their mothership", Math.abs(r - want) < want * 0.05f && Math.abs(swept - wantSwept) < wantSwept * 0.2f && facing > 0.9f,
					String.format("radius %.0f (want %.0f), swept %.2f rad in 4 s (want %.2f), facing out %.2f", r, want, swept, wantSwept, facing));
		}
	}

	/** Threat and Shrouded systems, Mote Attractor, Swarm Launcher / Churning Locus spawns. */
	static void systemsBatch4(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.PilotInput in = screen();
		float vk = t.speedMult;

		// Energy Lash on a friendly unit: its idle Incursion Mode goes off (+100 speed); the Overseer's cooldown follows the unit's DP.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D ov = ship(game, hulls.get("overseer_unit"), t, "overseer", 0, 400, 1e9f);
			ov.system = system("energy_lash", 1f, 0f, 0.1f, 0f, 0, 0f);
			ov.system.range = 1500f;
			sim.addShip(ov);
			Ship3D unit = ship(game, hulls.get("assault_unit"), t, "unit", 0, 400, 1e9f);
			unit.system = system("incursion_mode", 1f, 15f, 1f, 0f, 1, 0f);
			unit.system.ammo = 0f;
			unit.dp = 12f;
			unit.pos.set(300f, 0f, 0f);
			sim.addShip(unit);
			sim.setPlayer(unit);
			unit.throttle = 1f;
			for (float tt = 0; tt < 6f; tt += DT) sim.update(in, DT, DT);
			float plain = unit.speed();
			boolean selfFire = unit.system.ready(unit); // no charge of its own
			ov.system.ex.lashPick = unit;
			boolean lashed = ov.system.activate(ov);
			for (float tt = 0; tt < 1.2f; tt += DT) sim.update(in, DT, DT);
			boolean fired = unit.system.on();
			float cd = ov.system.cooldown;
			for (float tt = 0; tt < 6f; tt += DT) sim.update(in, DT, DT);
			float gain = unit.speed() - plain;
			check("energy lash fires a friendly's system", !selfFire && lashed && fired && Math.abs(cd - (2f + 12f * 0.33f)) < 0.01f && gain > 70f * vk && gain < 130f * vk,
					String.format("ready on its own %s, lash %s, unit system on %s, overseer cooldown %.2f (want %.2f), incursion +%.0f speed (want %.0f)",
							selfFire, lashed, fired, cd, 2f + 12f * 0.33f, gain, 100f * vk));
		}

		// Energy Lash on an enemy: an EMP arc (weapons knocked out); a phased enemy is knocked out of phase into a short overload.
		for (int phase = 0; phase < 2; phase++) {
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D ov = ship(game, hulls.get("overseer_unit"), t, "overseer", 0, 400, 1e9f);
			ov.system = system("energy_lash", 1f, 0f, 0.1f, 0f, 0, 0f);
			ov.system.range = 1500f;
			sim.addShip(ov);
			Ship3D en = ship(game, hulls.get("wolf"), t, "enemy", 1, 150, 1e9f);
			studWithGuns(en, 4);
			en.pos.set(0f, 0f, -600f);
			if (phase == 1) {
				en.system = system("phasecloak", 0.1f, 0f, 0.1f, 0.1f, 0, 0f);
				en.system.toggle = true;
			}
			sim.addShip(en);
			sim.update(in, DT, DT);
			if (phase == 1) {
				en.system.activate(en);
				for (float tt = 0; tt < 0.3f; tt += DT) sim.update(in, DT, DT);
			}
			boolean wasPhased = en.phased;
			ov.system.ex.lashPick = en;
			ov.system.activate(ov);
			for (float tt = 0; tt < 1.2f; tt += DT) sim.update(in, DT, DT);
			float cd = ov.system.cooldown;
			if (phase == 0) {
				int out = disabled(en);
				check("energy lash: EMP into an enemy", out >= 1 && cd >= 2f && cd <= 5f,
						String.format("%d of 4 weapons knocked out, cooldown %.1f (vanilla 2-5)", out, cd));
			} else {
				boolean over = en.flux.overloaded();
				check("energy lash: phased enemy overloads", wasPhased && over && !en.phased && cd >= 4f && cd <= 10f,
						String.format("phased %s, then overloaded %s and out of phase %s, cooldown %.1f (vanilla 2-5 x2)", wasPhased, over, !en.phased, cd));
			}
		}

		// Fragment Volley (lashed): fragment weapons get twice the reach, cooldowns cut to a second, reloaded; fragments regrow x10.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D u = ship(game, hulls.get("standoff_unit"), t, "standoff", 0, 400, 1e9f);
			u.fragments = new s3d.core.FragmentSwarm(150, 1f);
			WeaponSpec3D fw = gun("FRAGMENTATION", 1f, 50f, 600f, 600f);
			fw.fragmentsNeeded = 10;
			fw.maxAmmo = 5;
			fw.cooldown = 8f;
			WeaponMount m = new WeaponMount("F1", fw, new Vec3(0, 5, -20), new Vec3(0, 0, -1), 30f, false);
			m.ammo = 0f;
			m.cooldownLeft = 8f;
			u.weapons.add(m);
			u.system = system("fragment_volley", 1f, 3f, 1f, 60f, 1, 0f);
			u.system.ammo = 0f;
			sim.addShip(u);
			sim.update(in, DT, DT);
			u.system.lashed(u);
			for (float tt = 0; tt < 1.3f; tt += DT) sim.update(in, DT, DT);
			float range = u.sysFragmentRangeMult, regen = u.sysFragmentRegenMult;
			check("fragment volley: x2 reach, reloaded, fast regrowth", range == 2f && regen > 9.9f && m.ammo == 5f && m.cooldownLeft <= 1f,
					String.format("range x%.1f, regrowth x%.1f, ammo %.0f/5, cooldown %.1f", range, regen, m.ammo, m.cooldownLeft));
		}

		// Extraction Protocol (lashed): faster while on; as it powers down it vents and reloads every weapon.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D u = ship(game, hulls.get("skirmish_unit"), t, "skirmish", 0, 400, 1e9f);
			WeaponMount m = new WeaponMount("M1", launcher(false), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 30f, false);
			m.spec.maxAmmo = 10;
			m.ammo = 0f;
			u.weapons.add(m);
			u.flux.flux = u.flux.maxFlux * 0.8f;
			u.system = system("extraction_protocol", 1f, 5f, 1f, 0f, 1, 0f);
			u.system.ammo = 0f;
			sim.addShip(u);
			sim.update(in, DT, DT);
			u.system.lashed(u);
			boolean venting = false;
			for (float tt = 0; tt < 6.3f; tt += DT) {
				sim.update(in, DT, DT);
				venting |= u.flux.venting;
			}
			check("extraction protocol: vents and reloads as it ends", venting && m.ammo == 10f,
					String.format("vented %s, rack %.0f/10 (state %s)", venting, m.ammo, u.system.state));
		}

		// Convulsive Lunge: no aim, straight back 1000; the pull carries it most of the way, shield down at the peak.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D d = ship(game, hulls.get("shrouded_tendril"), t, "tendril", 1, 0, 1e9f);
			d.system = system("convulsive_lunge", 2f, 3f, 0.5f, 0f, 0, 0f);
			sim.addShip(d);
			sim.setPlayer(d);
			sim.update(in, DT, DT);
			Vec3 start = new Vec3(d.pos);
			d.system.activate(d);
			Vec3 dest = new Vec3(d.system.ex.lungeDest);
			boolean noShield = false;
			float miss = Float.MAX_VALUE, peak = 0f;
			for (float tt = 0; tt < 5.5f; tt += DT) {
				sim.update(in, DT, DT);
				noShield |= d.sysNoShield;
				miss = Math.min(miss, d.pos.distance(dest));
				peak = Math.max(peak, d.speed());
			}
			for (float tt = 0; tt < 3f; tt += DT) sim.update(in, DT, DT);
			float after = d.speed();
			check("convulsive lunge pulls toward its point", dest.distance(start) > 990f && miss < 150f && noShield && after < peak * 0.5f,
					String.format("point %.0f away, passed %.0f from it, shield off at peak %s, speed peak %.0f -> %.0f 3 s after", dest.distance(start), miss, noShield, peak, after));
		}

		// Darkened Gaze: the all-round shield faces back; on, it opens 90 at the front and the gaze beams fire; hull damage taken x2.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D eye = ship(game, hulls.get("shrouded_eye"), t, "eye", 1, 0, 1e9f);
			eye.system = system("darkened_gaze", 2f, 0f, 1f, 0f, 0, 0f);
			eye.system.toggle = true;
			WeaponMount b = new WeaponMount("G1", beam(100f, false), new Vec3(0, 5, -20), new Vec3(0, 0, -1), 5f, true);
			b.spec.range = 1000f;
			eye.gazeBeams.add(b);
			sim.addShip(eye);
			for (float tt = 0; tt < 2f; tt += DT) sim.update(in, DT, DT);
			float idleArc = eye.flux.arcOverrideDeg;
			boolean idleUp = eye.flux.shieldUp(), idleBeam = b.beamLevel > 0f;
			eye.system.activate(eye);
			for (float tt = 0; tt < 2.6f; tt += DT) sim.update(in, DT, DT);
			float onArc = eye.flux.arcOverrideDeg, taken = eye.sysHullTakenMult;
			boolean onBeam = b.beamLevel > 0f;
			check("darkened gaze: shield opens, beams fire", idleArc == 360f && idleUp && !idleBeam && Math.abs(onArc - 270f) < 1f && onBeam && Math.abs(taken - 2f) < 0.01f,
					String.format("idle: arc %.0f up %s beam %s; on: arc %.0f (want 270), beam %s, hull taken x%.1f", idleArc, idleUp, idleBeam, onArc, onBeam, taken));
		}

		// Tenebrous Expulsion: ejecta every 0.15 s for its second, within 45 deg of the aim; then it's pushed back.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D ml = ship(game, hulls.get("shrouded_maelstrom"), t, "maelstrom", 1, 0, 1e9f);
			ml.system = system("tenebrous_expulsion", 0.1f, 1f, 1f, 20f, 0, 0f);
			sim.addShip(ml);
			sim.update(in, DT, DT);
			Vec3 fwd = ml.rot.forward(new Vec3());
			ml.system.activate(ml);
			for (float tt = 0; tt < 1.15f; tt += DT) sim.update(in, DT, DT);
			int n = 0;
			float worst = 1f;
			for (s3d.core.SpawnRequest r : sim.combat.spawnRequests) {
				if (r.behaviour != Ship3D.Behaviour.EJECTA) continue;
				n++;
				worst = Math.min(worst, r.dir.dot(fwd));
			}
			for (float tt = 0; tt < 0.8f; tt += DT) sim.update(in, DT, DT);
			float back = -ml.vel.dot(fwd);
			check("tenebrous expulsion spits ejecta, backs off", n >= 6 && n <= 8 && worst > (float) Math.cos(Math.toRadians(46)) && back > 50f * vk,
					String.format("%d ejecta (want 6-7), widest %.0f deg off the aim, backing off at %.0f", n, Math.toDegrees(Math.acos(worst)), back));
		}

		// Shrouded vortex: chases the nearest enemy ship, rams it (decaying faster), and bursts on its target when it's gone.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D en = ship(game, hulls.get("eagle"), t, "eagle", 0, 0, 1e6f);
			en.pos.set(0f, 0f, -1500f);
			sim.addShip(en);
			Ship3D v = ship(game, hulls.get("shrouded_vortex"), t, "vortex", 1, 0, 5000f);
			v.fighter = false;
			v.behaviour = Ship3D.Behaviour.VORTEX;
			v.decayPerSecond = s3d.core.SpawnBehaviours.VORTEX_DECAY;
			v.deathBlast = blastSpec(2000f, v.radius * 2f + 150f, v.radius, false);
			v.system = system("shrouded_vortex", 2f, 3f, 0.5f, 0f, 0, 0f);
			v.ai = new s3d.core.ShipAI3D(9);
			v.ai.profile(v, t);
			sim.addShip(v);
			float died = -1f;
			for (float tt = 0; tt < 22f && died < 0f; tt += DT) {
				sim.update(in, DT, DT);
				if (v.destroyed) died = tt;
			}
			float lost = 1e6f - en.hull;
			check("shrouded vortex: chases, rams, bursts", died > 0f && died < 20f && v.rams >= 1 && lost > 500f,
					String.format("gone at %.1f s (no rams: 20 s), %d ram(s), eagle lost %.0f hull", died, v.rams, lost));
		}

		// Ejecta: slows to a tenth of its speed, decays away in 15 s, and bursts on what's near.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D e = ship(game, hulls.get("shrouded_ejecta"), t, "ejecta", 1, 0, 8000f);
			e.behaviour = Ship3D.Behaviour.EJECTA;
			e.decayPerSecond = s3d.core.SpawnBehaviours.EJECTA_DECAY;
			e.deathBlast = blastSpec(2000f, e.radius * 2f + 150f, e.radius, false);
			e.ai = new s3d.core.ShipAI3D(10);
			e.ai.profile(e, t);
			float top = e.params.fwdSpeed;
			e.vel.set(0f, 0f, -top);
			sim.addShip(e);
			float settle = top * 0.9f / e.params.fwdDecel + 0.5f;
			for (float tt = 0; tt < settle; tt += DT) sim.update(in, DT, DT);
			float slow = e.speed() / top;
			Ship3D en = ship(game, hulls.get("wolf"), t, "wolf", 0, 0, 1e6f);
			e.vel.set(0f, 0f, 0f);
			en.pos.set(e.pos).add(new Vec3(e.radius + en.radius + 20f, 0f, 0f));
			sim.addShip(en);
			float died = -1f;
			for (float tt = settle; tt < 17f && died < 0f; tt += DT) {
				sim.update(in, DT, DT);
				if (e.destroyed) died = tt;
			}
			float lost = 1e6f - en.hull;
			check("ejecta: slows, decays, bursts", slow < 0.12f && died > 14f && died < 16f && lost > 500f,
					String.format("speed %.2f of top after %.1f s (want 0.10), gone at %.1f s (want 15), wolf lost %.0f", slow, settle, died, lost));
		}

		// Construction Swarm: queues a build swarm; one build at a time; the swarm drifts out at a third speed, settles, builds.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D fab = ship(game, hulls.get("fabricator_unit"), t, "fab", 1, 400, 1e9f);
			fab.fragments = new s3d.core.FragmentSwarm(150, 0f);
			fab.system = system("construction_swarm", 0.1f, 0f, 0.4f, 0f, 0, 0f);
			sim.addShip(fab);
			sim.update(in, DT, DT);
			boolean ready0 = fab.system.ready(fab);
			fab.system.activate(fab);
			for (float tt = 0; tt < 0.3f; tt += DT) sim.update(in, DT, DT);
			int queued = 0;
			for (s3d.core.SpawnRequest r : sim.combat.spawnRequests) if (r.behaviour == Ship3D.Behaviour.CONSTRUCTION && r.pickBuild) queued++;
			Ship3D sw = ship(game, hulls.get("attack_swarm"), t, "swarm", 1, 0, 2000f);
			sw.fighter = true;
			sw.noHull = true;
			sw.behaviour = Ship3D.Behaviour.CONSTRUCTION;
			sw.carrier = fab;
			sw.travelLeft = 3f;
			sw.buildLeft = sw.buildTotal = 2f;
			sw.buildVariant = "skirmish_unit_Type100";
			sw.pos.set(fab.pos).add(new Vec3(0f, 0f, -fab.radius - 50f));
			sw.vel.set(0f, 0f, -sw.params.fwdSpeed);
			sw.ai = new s3d.core.ShipAI3D(11);
			sw.ai.profile(sw, t);
			sim.addShip(sw);
			for (float tt = 0; tt < 0.6f; tt += DT) sim.update(in, DT, DT);
			boolean blocked = !fab.system.ready(fab);
			Vec3 p0 = new Vec3(sw.pos);
			for (float tt = 0; tt < 2.4f; tt += DT) sim.update(in, DT, DT);
			float drift = sw.pos.distance(p0) / 2.4f, want = sw.params.fwdSpeed * s3d.core.SpawnBehaviours.CONSTRUCTION_SPEED_MULT;
			boolean early = sw.buildDone;
			for (float tt = 0; tt < 2.5f; tt += DT) sim.update(in, DT, DT);
			check("construction swarm: queues, one at a time, builds", ready0 && queued == 1 && blocked && Math.abs(drift - want) < want * 0.15f && !early && sw.buildDone,
					String.format("ready %s, queued %d, blocked while building %s, drift %.0f/s (want %.0f), built %s (not early %s)", ready0, queued, blocked, drift, want, sw.buildDone, !early));
		}

		// Mote Attractor: motes stream out to the cap and flock; they run down an incoming missile; the attractor draws them in.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D z = ship(game, hulls.get("ziggurat"), t, "ziggurat", 0, 400, 1e9f);
			z.system = system("mote_control", 0.1f, 0f, 0.4f, 0f, 0, 0f);
			WeaponSpec3D mote = launcher(true);
			mote.name = "mote";
			mote.missileMaxSpeed = 400f;
			mote.missileAccel = 650f;
			mote.missileTurnRate = 400f;
			mote.launchSpeed = 200f;
			mote.flightTime = 1e6f;
			mote.missileHp = 50f;
			mote.missileRadius = 4f;
			mote.ignoresFlares = true;
			z.system.motes.mote = mote;
			sim.addShip(z);
			Ship3D en = ship(game, hulls.get("wolf"), t, "wolf", 1, 150, 1e9f);
			en.pos.set(0f, 0f, -20000f);
			sim.addShip(en);
			for (float tt = 0; tt < 8f; tt += DT) sim.update(in, DT, DT);
			int motes = z.system.motes.list.size();
			float meanR = 0f;
			for (s3d.core.Missile3D m : z.system.motes.list) meanR += m.pos.distance(z.pos);
			meanR /= Math.max(1, motes);
			WeaponSpec3D torpedo = launcher(false); // torpedo-slow: motes are no wall against fast missiles (nor in vanilla)
			torpedo.missileMaxSpeed = 250f;
			torpedo.flightTime = 30f;
			s3d.core.Missile3D inbound = sim.combat.spawnMissile(en, torpedo, new Vec3(z.pos).add(new Vec3(0f, 0f, -1900f)), new Vec3(0f, 0f, 1f), 100f, null);
			float killedAt = -1f;
			for (float tt = 0; tt < 10f && killedAt < 0f; tt += DT) {
				sim.update(in, DT, DT);
				if (!inbound.alive || inbound.hp <= 0f) killedAt = inbound.pos.distance(z.pos);
			}
			z.system.activate(z);
			Vec3 at = new Vec3(z.system.motes.attractor);
			for (float tt = 0; tt < 4f; tt += DT) sim.update(in, DT, DT);
			float meanA = 0f;
			for (s3d.core.Missile3D m : z.system.motes.list) meanA += m.pos.distance(at);
			meanA /= Math.max(1, z.system.motes.list.size());
			float k = t.weaponRangeMult;
			check("mote attractor: flock, PD, attractor", motes >= 28 && motes <= 30 && meanR > z.radius + 150f * k && meanR < z.radius + 900f * k
					&& killedAt > z.radius && meanA < 250f * k,
					String.format("%d motes (cap 30) flocking %.0f out from the hull (vanilla settles ~600), missile run down %.0f from the centre, mean %.0f from the attractor after 4 s",
							motes, meanR - z.radius, killedAt, meanA));
		}

		// Swarm Launcher: holds once 4 swarms are out until only one is left.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D s = ship(game, hulls.get("hive_unit"), t, "hive", 0, 400, 1e9f);
			sim.addShip(s);
			WeaponSpec3D w = gun("FRAGMENTATION", 1f, 0f, 1500f, 500f);
			w.launchWing = "attack_swarm_wing";
			w.launchBehaviour = Ship3D.Behaviour.SWARM;
			w.launchMaxOut = 4;
			w.cooldown = 0.5f;
			WeaponMount m = new WeaponMount("SL", w, new Vec3(0, 5, -20), new Vec3(0, 0, -1), 360f, false);
			Vec3 aim = new Vec3(s.pos).add(new Vec3(0f, 0f, -1000f));
			java.util.List<s3d.core.SpawnRequest> q = sim.combat.spawnRequests;
			java.util.function.DoubleConsumer fire = secs -> {
				for (float tt = 0; tt < secs; tt += DT) {
					sim.update(in, DT, DT);
					sim.combat.stepMount(s, m, aim, true, DT, t);
				}
			};
			fire.accept(5.0);
			int full = q.size();
			q.remove(0);
			q.remove(0);
			fire.accept(2.0);
			int twoLeft = q.size();
			q.remove(0);
			fire.accept(0.7);
			int resumed = q.size();
			check("swarm launcher: 4 out, holds until 1 left", full == 4 && twoLeft == 2 && resumed >= 2,
					String.format("%d out after 5 s of fire (cap 4), %d with two left (holds), %d once only one was left (fires again)", full, twoLeft, resumed));
		}
	}

	static void systemsBatch2(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.PilotInput in = screen();

		// Missile Autoforge: refills a low rack by its base ammo; not usable while racks are full.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D hh = ship(game, hulls.get("hammerhead"), t, "hh", 0, 400, 1e9f);
			WeaponSpec3D l = launcher(true);
			l.maxAmmo = 10;
			l.baseMaxAmmo = 6;
			WeaponMount lm = new WeaponMount("L", l, new Vec3(0, 10, -30), new Vec3(0, 0, -1), 10f, true);
			hh.weapons.add(lm);
			hh.system = system("forgevats", 1f, 0f, 0f, 0f, 2, 0f);
			hh.system.reloadBelow = 0.6f;
			hh.system.reloadBaseOnly = true;
			sim.addShip(hh);
			stepSystem(sim, hh, DT);
			boolean fullReady = hh.system.ready(hh);
			lm.ammo = 2f;
			boolean used = hh.system.activate(hh);
			stepSystem(sim, hh, 1.5f);
			check("autoforge reloads low racks", !fullReady && used && Math.abs(lm.ammo - 8f) < 0.01f,
					String.format("usable with full racks %s; 2/10 -> %.0f after use (base ammo 6)", fullReady, lm.ammo));
		}

		// Entropy Amplifier: the target takes x1.5 from everything while it runs, even if stepped before its attacker.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D tgt = ship(game, hulls.get("hammerhead"), t, "tgt", 1, 400, 1e9f);
			tgt.pos.set(0f, 0f, -2000f * fk(t));
			sim.addShip(tgt); // stepped first: the amplifier must still stick
			Ship3D amp = ship(game, hulls.get("wolf"), t, "afflictor", 0, 150, 1e9f);
			amp.system = system("entropyamplifier", 0.5f, 10f, 1f, 10f, 0, 0f);
			amp.system.effectMult = 1.5f;
			amp.system.range = 1500f;
			sim.addShip(amp);
			sim.setPlayer(amp);
			sim.target = tgt;
			sim.update(in, DT, DT);
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			for (float tt = 0; tt < 2f; tt += DT) sim.update(in, DT, DT);
			float during = tgt.sysDamageTakenMult, shieldDuring = tgt.sysShieldDamageTakenMult;
			for (float tt = 0; tt < 11f; tt += DT) sim.update(in, DT, DT);
			float after = tgt.sysDamageTakenMult;
			tgt.pos.set(0f, 0f, -8000f);
			for (float tt = 0; tt < 12f; tt += DT) sim.update(in, DT, DT);
			boolean farUsable = amp.system.ready(amp);
			check("entropy amplifier: target takes more", Math.abs(during - 1.5f) < 0.01f && Math.abs(shieldDuring - 1.5f) < 0.01f && after == 1f && !farUsable,
					String.format("target's damage taken x%.2f (shield x%.2f) while on, x%.2f after; usable at 8000 away %s (reach %.0f)",
							during, shieldDuring, after, farUsable, 1500f * t.weaponRangeMult));
		}

		// Phase Teleporter: you jump to where you aim; an AI jumps in on a far target.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D hyp = ship(game, hulls.get("hammerhead"), t, "hyperion", 0, 300, 1e9f);
			hyp.system = system("phaseteleporter", 0.5f, 0f, 0.5f, 15f, 0, 0f);
			hyp.system.range = 1500f;
			sim.addShip(hyp);
			sim.setPlayer(hyp);
			sim.update(in, DT, DT);
			Vec3 aim = new Vec3(sim.aimPoint), p0 = new Vec3(hyp.pos);
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			for (float tt = 0; tt < 1f; tt += DT) sim.update(in, DT, DT);
			float jumped = hyp.pos.distance(p0), miss = hyp.pos.distance(aim);
			sim = new s3d.core.Sim(t);
			Ship3D ai = armed(game, hulls, t, "hammerhead", "hyperionAI", 0);
			ai.system = system("phaseteleporter", 0.5f, 0f, 0.5f, 15f, 0, 0f);
			ai.system.range = 1500f;
			ai.ai = new s3d.core.ShipAI3D(5);
			sim.addShip(ai);
			Ship3D far = armed(game, hulls, t, "dominator", "far", 1);
			far.weapons.clear();
			far.pos.set(0f, 0f, -9000f);
			sim.addShip(far);
			float before = ai.pos.distance(far.pos), biggestStep = 0f;
			Vec3 last = new Vec3(ai.pos);
			for (float tt = 0; tt < 6f; tt += DT) {
				sim.update(in, DT, DT);
				biggestStep = Math.max(biggestStep, ai.pos.distance(last));
				last.set(ai.pos);
			}
			// You jump to the aim point (convergence distance ahead); the AI jumps its full reach (1500 x rangeMult).
			check("phase teleporter jumps", jumped > t.weaponConvergence * 0.5f && miss < 150f && biggestStep > 1500f * fk(t),
					String.format("you: jumped %.0f to %.0f from the aim point; AI: one-frame jump of %.0f toward a target %.0f away",
							jumped, miss, biggestStep, before));
		}

		// Mine Strike: an AI lays a mine at its target, which blows on it.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D doom = armed(game, hulls, t, "hammerhead", "doom", 0);
			doom.weapons.clear();
			doom.system = system("mine_strike", 0.25f, 0f, 0.25f, 0f, 5, 0.2f);
			doom.system.range = 1000f;
			WeaponSpec3D mine = launcher(false);
			mine.damage = 2000f;
			mine.missileHp = 500f;
			mine.missileRadius = 20f;
			mine.mine = true;
			mine.proxRange = 175f;
			mine.proxRadius = 250f;
			mine.proxCore = 200f;
			mine.flightTime = 5f;
			mine.mineArmTime = 0.5f;
			mine.mineWindup = 1f;
			doom.system.payload = mine;
			doom.ai = new s3d.core.ShipAI3D(3);
			sim.addShip(doom);
			Ship3D victim = ship(game, hulls.get("enforcer"), t, "victim", 1, 0, 1e5f);
			victim.pos.set(0f, 0f, -1500f * fk(t));
			sim.addShip(victim);
			int mines = 0;
			for (float tt = 0; tt < 4f; tt += DT) {
				sim.update(in, DT, DT);
				for (s3d.core.Missile3D m : sim.combat.missiles) if (m.spec == mine && m.age < DT * 1.5f) mines++;
			}
			float lost = victim.hullMax - victim.hull;
			check("mine strike blows on the target", mines >= 1 && lost > 1000f,
					String.format("%d mine(s) laid by the AI, target hull lost %.0f (one blast up to 2000)", mines, lost));
		}

		// Canister Flak: the AI throws canisters when missiles come in; their fuses kill the missiles.
		int[] downed = new int[2];
		long flakSeed = Long.parseLong(System.getenv().getOrDefault("S3D_FLAK_SEED", "31"));
		for (int k = 0; k < 2; k++) {
			s3d.core.Sim sim = new s3d.core.Sim(t);
			s3d.core.ShipSystem3D.seed(flakSeed); // canister throws and fuses are random: a fixed roll so the check repeats
			sim.combat.seed(flakSeed);
			Ship3D sar = armed(game, hulls, t, "manticore", "manticore", 0);
			sar.weapons.clear();
			sar.ai = new s3d.core.ShipAI3D(4);
			if (k == 1) {
				sar.system = system("canister_flak", 0f, 0f, 0f, 3f, 2, 0.05f);
				WeaponSpec3D can = launcher(false);
				can.damage = 200f;
				can.missileHp = 100f;
				can.launchSpeed = 50f;
				can.missileMaxSpeed = 50f;
				can.missileAccel = 100f;
				can.flightTime = 2f;
				can.proxRange = 40f;
				can.proxRadius = 75f;
				can.proxCore = 50f;
				sar.system.payload = can;
				sar.system.burst = 5;
				for (GameData.Slot sl : GameData.slots(game, "manticore")) { // its real system slots, as ShipBridge reads them
					if (!"SYSTEM".equals(sl.type)) continue;
					float x = -sl.y, z = -sl.x;
					double a = Math.toRadians(sl.angle);
					sar.system.slots.add(new WeaponMount(sl.id, can, new Vec3(x, sar.volume.topAt(x, z) + 1f, z),
							new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)), sl.arc, false));
				}
			}
			sim.addShip(sar);
			Ship3D foe = ship(game, hulls.get("wolf"), t, "foe", 1, 150, 1e9f);
			foe.pos.set(0f, 0f, -5000f);
			sim.addShip(foe);
			java.util.List<s3d.core.Missile3D> incoming = new java.util.ArrayList<>();
			for (int i = 0; i < 8; i++) {
				s3d.core.Missile3D m = new s3d.core.Missile3D();
				m.spec = launcher(true);
				m.spec.missileMaxSpeed = 180f;
				m.spec.flightTime = 30f;
				m.owner = 1;
				m.source = foe;
				m.target = sar;
				m.hp = 150f;
				// In along the front slots' diagonals (where the cloud forms), staggered.
				Vec3 from = new Vec3(i % 2 == 0 ? -1f : 1f, (i / 2 % 2) * 0.2f - 0.1f, -1f).normalize();
				m.pos.set(from).scale(1200f + i * 60f);
				m.dir.set(from).scale(-1f);
				m.vel.set(m.dir).scale(180f);
				sim.combat.missiles.add(m);
				incoming.add(m);
			}
			for (float tt = 0; tt < 10f; tt += DT) sim.update(in, DT, DT);
			for (s3d.core.Missile3D m : incoming) if (m.hp <= 0f) downed[k]++;
		}
		check("canister flak screens missiles", downed[0] == 0 && downed[1] >= 3,
				String.format("incoming missiles killed: %d without the system, %d with canister flak (of 8; Manticore's 4 system slots)", downed[0], downed[1]));

		// Targeting Feed: the carrier's fighters hit 50% harder while it runs.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D heron = ship(game, hulls.get("eagle"), t, "heron", 0, 600, 1e9f);
			heron.system = system("targetingfeed", 0.5f, 20f, 0.5f, 10f, 0, 0f);
			heron.system.effectMult = 1.5f;
			sim.addShip(heron);
			Ship3D f = ship(game, hulls.get("wolf"), t, "talon", 0, 50, 400f);
			f.fighter = true;
			f.carrier = heron;
			sim.addShip(f);
			sim.setPlayer(heron);
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			for (float tt = 0; tt < 1f; tt += DT) sim.update(in, DT, DT);
			WeaponMount g = new WeaponMount("G", gun("ENERGY", 1f, 30f, 500f, 900f), new Vec3(0, 3, -8), new Vec3(0, 0, -1), 10f, true);
			f.weapons.add(g);
			sim.combat.stepMount(f, g, new Vec3(0f, 0f, -500f), true, DT, t);
			float shotMult = sim.combat.projectiles.isEmpty() ? 0f : sim.combat.projectiles.get(0).damageMult;
			check("targeting feed boosts fighters", Math.abs(f.sysDamageMult - 1.5f) < 0.01f && Math.abs(shotMult - 1.5f) < 0.01f && heron.sysDamageMult == 1f,
					String.format("fighter damage x%.2f, its shot x%.2f, carrier itself x%.2f", f.sysDamageMult, shotMult, heron.sysDamageMult));
		}

		// Lidar Array: silent wind-up (missiles may fire), then only hardpoint guns, at x3 rate and double range.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D inv = ship(game, hulls.get("dominator"), t, "invictus", 0, 1500, 1e9f);
			WeaponSpec3D hg = gun("KINETIC", 0.5f, 100f, 900f, 1200f);
			hg.type = "BALLISTIC";
			hg.cooldown = 1f;
			WeaponMount hp = new WeaponMount("HP", hg, new Vec3(0, 10, -80), new Vec3(0, 0, -1), 10f, true);
			WeaponSpec3D tg = gun("KINETIC", 0.5f, 100f, 900f, 1200f);
			tg.type = "BALLISTIC";
			tg.cooldown = 1f;
			WeaponMount tu = new WeaponMount("TU", tg, new Vec3(0, 10, 0), new Vec3(0, 0, -1), 180f, false);
			WeaponSpec3D ms = launcher(false);
			ms.cooldown = 0.5f;
			WeaponMount mi = new WeaponMount("MI", ms, new Vec3(0, 10, 20), new Vec3(0, 0, -1), 10f, true);
			inv.weapons.add(hp);
			inv.weapons.add(tu);
			inv.weapons.add(mi);
			inv.system = system("lidararray", 3.25f, 5f, 1f, 10f, 0, 0f);
			inv.system.lidarRange = 1f;
			inv.system.lidarRof = 2f;
			inv.system.lidarSpeed = 0.5f;
			sim.addShip(inv);
			Vec3 far = new Vec3(0f, 0f, -900f * t.weaponRangeMult * 1.8f); // past normal reach, inside the boosted one
			int[][] shots = new int[3][3]; // phase (normal, wind-up, active) x mount
			boolean[] reach = new boolean[3];
			for (int phase = 0; phase < 3; phase++) {
				if (phase == 1) inv.system.activate(inv);
				float dur = phase == 0 ? 3f : phase == 1 ? 3f : 4.5f;
				if (phase == 2) stepSystem(sim, inv, 0.3f); // finish the wind-up
				for (float tt = 0; tt < dur; tt += DT) {
					inv.resetSystemEffects();
					inv.system.step(inv, sim, DT);
					for (int i = 0; i < 3; i++) {
						WeaponMount m = inv.weapons.get(i);
						sim.combat.stepMount(inv, m, new Vec3(0f, 0f, -1000f), true, DT, t);
						if (m.firedThisFrame) shots[phase][i]++;
					}
				}
				reach[phase] = s3d.core.Combat.canReach(inv, hp, far, t);
			}
			boolean ok = shots[1][0] == 0 && shots[1][1] == 0 && shots[1][2] > 0 && shots[2][1] == 0
					&& shots[2][0] >= shots[0][0] * 2.5f * (4.5f / 3f) * 0.8f && !reach[0] && reach[2];
			check("lidar array: hardpoints only, faster, further", ok,
					String.format("hardpoint/turret/missile shots: normal %d/%d/%d in 3 s, wind-up %d/%d/%d, active %d/%d/%d in 4.5 s; reaches 1.8x range: before %s, active %s",
							shots[0][0], shots[0][1], shots[0][2], shots[1][0], shots[1][1], shots[1][2], shots[2][0], shots[2][1], shots[2][2], reach[0], reach[2]));
		}

		// Termination Sequence: a drone becomes a missile at the target and dies with it.
		{
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D tmp = ship(game, hulls.get("wolf"), t, "tempest", 0, 200, 1e9f);
			tmp.system = system("drone_strike", 0f, 0.05f, 0.2f, 0f, 0, 0f);
			WeaponSpec3D tm = launcher(true);
			tm.damage = 1200f;
			tm.damageType = "ENERGY";
			tm.armorMult = 1f;
			tm.launchSpeed = 100f;
			tm.missileMaxSpeed = 400f;
			tm.missileHp = 1e5f;
			tm.range = 1500f;
			tm.flightTime = 10f;
			tmp.system.payload = tm;
			sim.addShip(tmp);
			Ship3D drone = ship(game, hulls.get("wolf"), t, "terminator", 0, 50, 300f);
			drone.fighter = true;
			drone.carrier = tmp;
			drone.applyScale(0.3f);
			drone.pos.set(80f, 30f, 0f);
			sim.addShip(drone);
			Ship3D tgt = ship(game, hulls.get("enforcer"), t, "tgt", 1, 0, 1e5f);
			tgt.pos.set(0f, 0f, -2000f * fk(t));
			sim.addShip(tgt);
			sim.setPlayer(tmp);
			sim.target = tgt;
			sim.update(in, DT, DT);
			in.useSystem = true;
			sim.update(in, DT, DT);
			in.clearPressed();
			boolean rode = false;
			for (float tt = 0; tt < 8f; tt += DT) {
				sim.update(in, DT, DT);
				rode |= drone.carriedBy != null;
			}
			float lost = tgt.hullMax - tgt.hull;
			check("termination sequence: drone becomes a missile", rode && drone.destroyed && Math.abs(lost - 1200f) < 1f && sim.combat.missiles.isEmpty(),
					String.format("drone rode a missile %s, drone gone %s, target hull lost %.0f (1200 expected)", rode, drone.destroyed, lost));
		}
	}

	/** Ships don't pass through each other or wrecks; rams hurt both, the lighter bounces more; fighters pass. */
	static void collisionRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.PilotInput in = screen();
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D wolf = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 2000f);
		wolf.pos.set(0f, 0f, 300f);
		wolf.vel.set(0f, 0f, -120f);
		wolf.throttle = 1f;
		sim.addShip(wolf);
		sim.setPlayer(wolf);
		Ship3D dom = ship(game, hulls.get("dominator"), t, "dominator", 1, 1500, 14000f);
		dom.vel.set(0f, 0f, 20f);
		sim.addShip(dom);
		float wolfHull = wolf.hull, domTaken = damageTaken(dom);
		int overlapFrames = 0;
		float wolfAfter = 0f;
		for (float tt = 0; tt < 6f; tt += DT) {
			sim.update(in, DT, DT);
			if (tt > 4f && wolf.pos.distance(dom.pos) < (wolf.radius + dom.radius) * 0.3f) overlapFrames++;
			if (tt < 4.05f) wolfAfter = wolf.pos.z;
		}
		boolean bounced = wolf.pos.z > dom.pos.z;
		float domLost = damageTaken(dom) - domTaken;
		check("ram: no passing through, both hurt", overlapFrames == 0 && wolf.hull < wolfHull && domLost > 0f && domLost < wolfHull - wolf.hull && bounced,
				String.format("wolf lost %.0f hull (%.0f mass), dominator lost %.0f armor+hull (%.0f mass); wolf still in front=%s, overlapping frames %d",
						wolfHull - wolf.hull, wolf.mass, domLost, dom.mass, bounced, overlapFrames));

		// A wreck is solid too.
		sim = new s3d.core.Sim(t);
		Ship3D hound = ship(game, hulls.get("hound"), t, "hound", 0, 150, 1e9f);
		hound.pos.set(0f, 0f, 400f);
		hound.throttle = 1f;
		sim.addShip(hound);
		sim.setPlayer(hound);
		Ship3D wreck = ship(game, hulls.get("eagle"), t, "eagle", 1, 1000, 8000f);
		wreck.destroyed = true;
		sim.addShip(wreck);
		boolean through = false;
		for (float tt = 0; tt < 10f; tt += DT) {
			sim.update(in, DT, DT);
			if (hound.pos.z < wreck.pos.z - wreck.radius) through = true;
		}
		check("wrecks are solid", !through, "flew through the wreck=" + through + ", hound z " + Math.round(hound.pos.z));

		// Fighters fly over/through ships like vanilla.
		sim = new s3d.core.Sim(t);
		Ship3D carrier = ship(game, hulls.get("heron"), t, "heron", 0, 800, 1e9f);
		sim.addShip(carrier);
		Ship3D f = fighter(game, hulls, t, "broadsword", "FIGHTER", carrier, 1);
		f.ai = null;
		f.pos.set(0f, 0f, 300f);
		f.vel.set(0f, 0f, -150f);
		f.mover = new ScriptedMover(new Vec3(0f, 0f, -100000f), 100300f, 150f, 0f);
		sim.addShip(f);
		Ship3D enemy = ship(game, hulls.get("dominator"), t, "dominator", 1, 1500, 1e9f);
		sim.addShip(enemy);
		sim.setPlayer(carrier);
		float hullBefore = enemy.hull;
		for (float tt = 0; tt < 4f; tt += DT) sim.update(in, DT, DT);
		check("fighters don't collide", enemy.hull == hullBefore && f.hull == f.hullMax, "enemy hull lost " + Math.round(hullBefore - enemy.hull));
	}

	/** A heavy kinetic hit shoves a frigate, barely moves a capital, spins on off-centre hits, and flight assist recovers. */
	static void weightRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		WeaponSpec3D hellbore = gun("HIGH_EXPLOSIVE", 2f, 750f, 1500f, 800f);
		hellbore.impact = 200f;
		float[] dv = new float[2], spin = new float[2];
		String[] ids = {"wolf", "onslaught"};
		for (int k = 0; k < 2; k++) {
			Combat c = new Combat();
			c.tuning = t;
			Ship3D tgt = ship(game, hulls.get(ids[k]), t, ids[k], 1, 300, 1e9f);
			Ship3D sh = ship(game, hulls.get("enforcer"), t, "enforcer", 0, 900, 1e9f);
			sh.pos.set(-600f, 0f, 0f);
			sh.weapons.add(new WeaponMount("G", hellbore, new Vec3(0, 5, 0), new Vec3(1, 0, 0), 360f, false));
			// Aim at the target's bow so the hit is off-centre.
			Vec3 bow = tgt.rot.forward(new Vec3()).scale(tgt.radius * 0.5f).add(tgt.pos);
			for (float tt = 0; tt < 1.2f && tgt.vel.length() < 1e-3f; tt += DT) {
				c.stepWeapons(sh, bow, tt < DT, DT, t);
				c.stepProjectiles(java.util.List.of(sh, tgt), DT);
			}
			dv[k] = tgt.vel.length();
			spin[k] = (float) Math.toDegrees(tgt.angVel.length());
		}
		// Flight assist brings a shoved frigate back to rest.
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D wolf = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		sim.addShip(wolf);
		sim.setPlayer(wolf);
		sim.combat.push(wolf, new Vec3(0f, 0f, -30f).add(wolf.pos), new Vec3(1f, 0f, 0f), 30f * (200f + 75f));
		float shoved = wolf.vel.length();
		for (float tt = 0; tt < 3f; tt += DT) sim.update(screen(), DT, DT);
		check("hits have weight", dv[0] > 20f && dv[1] < dv[0] * 0.1f && spin[0] > 5f && wolf.vel.length() < shoved * 0.2f,
				String.format("hellbore shove: wolf %.0f u/s and %.0f deg/s spin, onslaught %.1f u/s and %.1f deg/s; a shoved wolf slows from %.0f to %.0f in 3 s",
						dv[0], spin[0], dv[1], spin[1], shoved, wolf.vel.length()));
	}

	/** The battle asks for the right sounds: weapon fire, beam loops, hits, kills. */
	static void soundEvents(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		Combat c = new Combat();
		c.tuning = t;
		c.sounds.enabled = true;
		Ship3D shooter = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		WeaponSpec3D g = gun("ENERGY", 1f, 200f, 1000f, 1000f);
		g.fireSound = "test_fire";
		shooter.weapons.add(new WeaponMount("G", g, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false));
		WeaponSpec3D b = beam(200f, false);
		b.beamLoopSound = "test_beam_loop";
		shooter.weapons.add(new WeaponMount("B", b, new Vec3(0, 5, 0), new Vec3(0, 0, -1), 360f, false));
		Ship3D target = ship(game, hulls.get("lasher"), t, "lasher", 1, 50, 600f);
		target.pos.set(0f, 0f, -400f);
		c.world = java.util.List.of(shooter, target);
		java.util.Map<String, Integer> heard = new java.util.TreeMap<>();
		int loopFrames = 0;
		for (float tt = 0; tt < 4f; tt += DT) {
			c.stepWeapons(shooter, target.pos, true, DT, t);
			c.stepProjectiles(c.world, DT);
			for (s3d.core.SoundEvents.Shot sh : c.sounds.shots) heard.merge(sh.id, 1, Integer::sum);
			for (s3d.core.SoundEvents.Loop l : c.sounds.loops) if (l.id.equals("test_beam_loop")) loopFrames++;
			c.sounds.clear();
		}
		boolean ok = heard.getOrDefault("test_fire", 0) > 3 && loopFrames > 60 && heard.keySet().stream().anyMatch(k -> k.startsWith("hit_hull"))
				&& heard.containsKey("explosion_ship");
		check("battle sounds requested", ok, "one-shots " + heard + ", beam loop frames " + loopFrames);
	}

	/** Two AI fleets start 5000 apart and fight: both sides must engage, kill, and stay in the fight area. */
	static void aiSkirmish(File game, Map<String, GameData.Hull> hulls, Tuning t, int seedOff, boolean report) throws Exception {
		s3d.core.Sim sim = new s3d.core.Sim(t);
		String[] mix = {"wolf", "lasher", "hammerhead", "eagle"};
		java.util.List<Ship3D> all = new java.util.ArrayList<>();
		for (int side = 0; side < 2; side++) {
			for (int i = 0; i < mix.length; i++) {
				Ship3D s = armed(game, hulls, t, mix[i], mix[i] + side, side);
				s.ai = new s3d.core.ShipAI3D(i + side * 7 + seedOff);
				s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
				// Side 1 is side 0 turned half round (x mirrored too), so neither side has the better layout.
				float x = (-600f + 400f * i) * (side == 0 || System.getenv("S3D_OLD_LAYOUT") != null ? 1f : -1f);
				s.pos.set(x, (i % 2) * 200f - 100f, side == 0 ? 2500f : -2500f);
				if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				sim.addShip(s);
				all.add(s);
			}
		}
		s3d.core.PilotInput in = screen();
		float[] taken = new float[2];
		int friendly = 0, kills = 0, framesInRange = 0, frames = 0;
		float maxFromCentre = 0f, firstContact = -1f;
		java.util.Map<Ship3D, Ship3D> lastHit = new java.util.HashMap<>();
		java.util.Map<Ship3D, Float> travelled = new java.util.HashMap<>();
		int overloads = 0, vents = 0, shieldFrames = 0, fluxFrames = 0, highFluxFrames = 0, shots = 0;
		float fluxSum = 0f, spreadSum = 0f, altSum = 0f;
		int spreadN = 0;
		java.util.Map<Ship3D, Boolean> wasOver = new java.util.HashMap<>(), wasVent = new java.util.HashMap<>();
		java.util.Map<String, Integer> perShip = new java.util.TreeMap<>();
		// Movement while fighting: how fast the line of sight to the nearest enemy swings, speed, turn rate.
		java.util.Map<Ship3D, Vec3> lastLos = new java.util.HashMap<>();
		float losRateSum = 0f, speedSum = 0f, speedSqSum = 0f, turnSum = 0f;
		int moveN = 0;
		for (float time = 0; time < 240f; time += DT) {
			java.util.Map<Ship3D, Vec3> before = new java.util.HashMap<>();
			for (Ship3D s : all) before.put(s, new Vec3(s.pos));
			sim.update(in, DT, DT);
			for (Ship3D s : all) {
				if (!s.destroyed && s.flux != null) {
					if (s.flux.overloaded() && !wasOver.getOrDefault(s, false)) overloads++;
					if (s.flux.venting && !wasVent.getOrDefault(s, false)) vents++;
					wasOver.put(s, s.flux.overloaded());
					wasVent.put(s, s.flux.venting);
					if (s.flux.shieldUp()) shieldFrames++;
					fluxSum += s.flux.level();
					fluxFrames++;
					if (s.flux.level() > t.aiFluxFireLimit) highFluxFrames++;
				}
				if (!s.destroyed) for (WeaponMount m : s.weapons) if (m.firedThisFrame) {
					shots++;
					perShip.merge(s.id, 1, Integer::sum);
				}
				travelled.merge(s, before.get(s).distance(s.pos), Float::sum);
				if (s.lastHitBy != null && lastHit.get(s) != s.lastHitBy) {
					lastHit.put(s, s.lastHitBy);
					if (s.lastHitBy.owner == s.owner) friendly++;
				}
				if (!s.destroyed) {
					maxFromCentre = Math.max(maxFromCentre, s.pos.length());
					frames++;
					float nearest = Float.MAX_VALUE;
					for (Ship3D o : all) if (o.owner != s.owner && !o.destroyed) nearest = Math.min(nearest, o.pos.distance(s.pos));
					if (nearest < s.ai.weaponRange) framesInRange++;
					Ship3D foe = null;
					for (Ship3D o : all) if (o.owner != s.owner && !o.destroyed && o.pos.distance(s.pos) <= nearest + 1e-3f) foe = o;
					if (foe != null && nearest < s.ai.weaponRange * 1.2f) {
						Vec3 los = new Vec3(s.pos).sub(foe.pos).normalize();
						Vec3 prev = lastLos.get(s);
						if (prev != null) {
							losRateSum += (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, prev.dot(los))))) / DT;
							float v = s.speed();
							speedSum += v;
							speedSqSum += v * v;
							turnSum += (float) Math.toDegrees(s.angVel.length());
							moveN++;
						}
						lastLos.put(s, los);
					} else {
						lastLos.remove(s);
					}
				}
			}
			if (firstContact < 0f) for (Ship3D s : all) if (damageTaken(s) > 0f) firstContact = time;
			if (System.getenv("S3D_AI_TRACE") != null && Math.round(time / DT) % 30 == 0) {
				for (Ship3D s : all) if (!s.destroyed && s.ai.move == s3d.core.ShipAI3D.Move.PASS) System.out.printf("   %5.1fs %s %s%n", time, s.id, s.ai.debugMove(s));
			}
			if (time > 30f) {
				for (Ship3D s : all) {
					Vec3 home = sim.fleetCentre(s.owner);
					if (s.destroyed || home == null) continue;
					spreadSum += s.pos.distance(home);
					altSum += Math.abs(s.pos.y - sim.fightAltitude());
					spreadN++;
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		for (Ship3D s : all) {
			taken[s.owner] += damageTaken(s);
			if (s.destroyed) kills++;
			sb.append(String.format(" %s%s(%s %.0fm %dp)", s.id, s.destroyed ? "x" : "", s.ai.axis.name().charAt(0), travelled.get(s), s.ai.passesMade));
		}
		boolean ok = taken[0] > 1000f && taken[1] > 1000f && kills >= 2 && maxFromCentre < 12000f && firstContact >= 0f && firstContact < 60f
				&& spreadSum / Math.max(1, spreadN) < 1500f;
		int passes = 0;
		for (Ship3D s : all) passes += s.ai.passesMade;
		if (System.getenv("S3D_AI_DEBUG") != null) for (Ship3D s : all) {
			System.out.printf("   %-12s speed %3.0f plans hold %d flank %d pass %d, runs %d, aborted %d%n", s.id, s.params.fwdSpeed,
					s.ai.plans[0], s.ai.plans[1], s.ai.plans[2], s.ai.passesMade, s.ai.passAborts);
		}
		if (!report) {
			int[] dead = new int[2];
			for (Ship3D s : all) if (s.destroyed) dead[s.owner]++;
			System.out.printf("seed %3d: taken %6.0f / %6.0f, dead %d / %d, contact %3.0fs, los %.1f deg/s, speed %.0f, turn %.1f deg/s, passes %d%n",
					seedOff, taken[0], taken[1], dead[0], dead[1], firstContact, losRateSum / Math.max(1, moveN), speedSum / Math.max(1, moveN),
					turnSum / Math.max(1, moveN), passes);
			return;
		}
		check("AI skirmish 4v4", ok, String.format("contact at %.0fs, damage taken %.0f / %.0f, %d destroyed, %d friendly-fire hits, max %.0f from centre, in range %.0f%% of ship-time",
				firstContact, taken[0], taken[1], kills, friendly, maxFromCentre, 100f * framesInRange / Math.max(1, frames)));
		System.out.println("   ships:" + sb);
		System.out.printf("   flux: avg level %.0f%%, above fire limit %.0f%% of ship-time, shields up %.0f%%, %d overloads, %d vents, %d shots%n",
				100f * fluxSum / Math.max(1, fluxFrames), 100f * highFluxFrames / Math.max(1, fluxFrames), 100f * shieldFrames / Math.max(1, fluxFrames),
				overloads, vents, shots);
		System.out.println("   shots per ship: " + perShip);
		float meanV = speedSum / Math.max(1, moveN);
		System.out.printf("   movement near the enemy: line of sight swings %.1f deg/s, speed %.0f (sd %.0f), turning %.1f deg/s%n",
				losRateSum / Math.max(1, moveN), meanV, (float) Math.sqrt(Math.max(0f, speedSqSum / Math.max(1, moveN) - meanV * meanV)), turnSum / Math.max(1, moveN));
		System.out.printf("   cohesion after 30 s: mean distance to own fleet centre %.0f, mean height off the fight %.0f%n",
				spreadSum / Math.max(1, spreadN), altSum / Math.max(1, spreadN));
	}

	/** A Conquest (broadside slots) picks a side and orbits with it facing the target. */
	static void aiBroadside(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		if (!hulls.containsKey("conquest")) {
			check("AI broadside (conquest)", false, "no conquest hull");
			return;
		}
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D conquest = armed(game, hulls, t, "conquest", "conquest", 0);
		conquest.ai = new s3d.core.ShipAI3D(1);
		conquest.pos.set(0f, 0f, 3000f);
		sim.addShip(conquest);
		Ship3D dummy = armed(game, hulls, t, "dominator", "dominator", 1);
		dummy.weapons.clear();
		dummy.hullMax = dummy.hull = 1e9f;
		sim.addShip(dummy);
		s3d.core.PilotInput in = screen();
		int onSide = 0, inRange = 0, frames = 0;
		float minD = Float.MAX_VALUE, maxD = 0f;
		for (float time = 0; time < 150f; time += DT) {
			sim.update(in, DT, DT);
			if (time < 30f) continue;
			frames++;
			Vec3 local = conquest.rot.inverseRotate(new Vec3(dummy.pos).sub(conquest.pos), new Vec3());
			float d = local.length();
			minD = Math.min(minD, d);
			maxD = Math.max(maxD, d);
			// In range: one of its broadside guns reaches the target (on a 2.5x capital the mounts sit
			// hundreds of units from the centre, so the centre distance alone says little).
			boolean reaches = false;
			for (WeaponMount m : conquest.weapons) if (!reaches && m.spec.kind != WeaponSpec3D.Kind.MISSILE && Combat.canReach(conquest, m, dummy.pos, t)) reaches = true;
			if (reaches) inRange++;
			boolean gunSide = conquest.ai.axis == s3d.core.ShipAI3D.Axis.PORT ? local.x < 0f : local.x > 0f;
			if (gunSide && Math.abs(local.x) > Math.abs(local.z)) onSide++;
		}
		boolean ok = conquest.ai.axis != s3d.core.ShipAI3D.Axis.NOSE && onSide > frames * 0.5f && inRange > frames * 0.6f && damageTaken(dummy) > 0f;
		check("AI broadside (conquest)", ok, String.format("axis %s, target on gun side %.0f%%, in range %.0f%% (%.0f-%.0f, range %.0f), damage dealt %.0f",
				conquest.ai.axis, 100f * onSide / frames, 100f * inRange / frames, minD, maxD, conquest.ai.weaponRange, damageTaken(dummy)));
	}

	/**
	 * Hardpoints on big hulls: an AI Ziggurat with tachyon-lance-like burst beams in its two 5 deg
	 * hardpoints (mounts on top of a 2.5x hull, tens of units off the centreline) against a manoeuvring
	 * AI Eagle. The lances must fire about as often as they're ready and land.
	 */
	static void aiHardpointLances(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		if (!hulls.containsKey("ziggurat")) {
			check("AI fires its hardpoint lances", false, "no ziggurat hull");
			return;
		}
		s3d.core.Sim sim = new s3d.core.Sim(t);
		sim.combat.seed(3L);
		Ship3D z = armed(game, hulls, t, "ziggurat", "ziggurat", 0);
		z.hullMax = z.hull = 1e6f;
		WeaponSpec3D lance = beam(1500f, true);
		lance.name = lance.id = "lance";
		lance.range = 1000f;
		lance.chargeUp = 0.5f;
		lance.chargeDown = 1f;
		lance.cooldown = 4f;
		lance.turnRate = 7f;
		lance.fluxPerSecond = 2000f;
		java.util.List<WeaponMount> lances = new java.util.ArrayList<>();
		for (int i = 0; i < z.weapons.size(); i++) {
			WeaponMount m = z.weapons.get(i);
			if (!m.hardpoint) continue;
			WeaponMount l = new WeaponMount(m.slotId, lance, m.localPos, m.restDir, 5f, true);
			z.weapons.set(i, l);
			lances.add(l);
		}
		z.ai = new s3d.core.ShipAI3D(0);
		sim.addShip(z);
		Ship3D foe = armed(game, hulls, t, "eagle", "eagle", 1);
		foe.hullMax = foe.hull = 1e6f;
		foe.ai = new s3d.core.ShipAI3D(10);
		foe.ai.homeDir.set(0f, 0f, -1f);
		foe.pos.set(300f, 100f, -1800f);
		foe.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
		sim.addShip(foe);
		s3d.core.PilotInput in = screen();
		boolean[] was = new boolean[lances.size()];
		int bursts = 0, full = 0, onTarget = 0;
		for (float time = 0; time < 90f; time += DT) {
			sim.update(in, DT, DT);
			for (int i = 0; i < lances.size(); i++) {
				WeaponMount l = lances.get(i);
				if (l.beamFiring && !was[i]) bursts++;
				was[i] = l.beamFiring;
				if (l.beamLevel >= 1f) {
					full++;
					if (l.beamHitting) onTarget++;
				}
			}
		}
		float landed = full > 0 ? (float) onTarget / full : 0f;
		// At vanilla ranges (rangeMult 1) the Eagle fights from about half the distance it did at 2x, so it
		// crosses the 5 deg hardpoints twice as fast: ~80% lands (100% at 2x).
		check("AI fires its hardpoint lances", lances.size() == 2 && bursts >= 16 && landed > (t.weaponRangeMult < 1.5f ? 0.7f : 0.8f),
				String.format("%d hardpoint lances, %d bursts in 90 s (one every ~6.5 s per lance when always ready: ~27), beam on the target %.0f%% of its full-power time",
						lances.size(), bursts, landed * 100f));
	}

	/**
	 * Manoeuvring against a target that doesn't shoot back (and doesn't turn): a wolf should make
	 * gun runs (in, past, out, about), seeing the target from several sides (octants of the sphere
	 * around it, in its frame), and still do damage; a hammerhead should keep doing damage while it
	 * weaves and repositions.
	 */
	static void aiManoeuvres(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		String[] hullsUsed = {"wolf", "hammerhead"};
		StringBuilder sb = new StringBuilder();
		boolean ok = true;
		for (String hullId : hullsUsed) {
			if (!hulls.containsKey(hullId) || !hulls.containsKey("dominator")) {
				check("AI manoeuvres", false, "missing hull " + hullId);
				return;
			}
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D ship = armed(game, hulls, t, hullId, hullId, 0);
			ship.ai = new s3d.core.ShipAI3D(3);
			ship.pos.set(0f, 0f, 2500f);
			sim.addShip(ship);
			Ship3D dummy = armed(game, hulls, t, "dominator", "dominator", 1);
			// Armed, so a careful pilot treats it as dangerous (no venting in its face, no standing off as if it
			// were harmless), but without a pilot of its own it never fires.
			dummy.hullMax = dummy.hull = 1e6f; // not 1e9: float steps there are 64, which swallows small hits
			sim.addShip(dummy);
			if (System.getenv("S3D_NOARMOR") != null) java.util.Arrays.fill(dummy.armor.frac, 0f);
			s3d.core.PilotInput in = screen();
			java.util.Set<Integer> octants = new java.util.HashSet<>();
			float lastHull = dummy.hull;
			Vec3 last = null;
			float losSum = 0f, speedMin = Float.MAX_VALUE, speedMax = 0f;
			int n = 0, shots = 0;
			float hullBefore = dummy.hull;
			java.util.Map<String, float[]> byPhase = new java.util.TreeMap<>(); // phase -> {seconds, shots, hull damage}
			// Each shot's fate: phase when fired -> {hits, misses, sum of distance at firing}
			java.util.Map<s3d.core.Projectile, String> born = new java.util.IdentityHashMap<>();
			java.util.Map<s3d.core.Projectile, Float> bornDist = new java.util.IdentityHashMap<>();
			java.util.Map<String, float[]> fate = new java.util.TreeMap<>();
			for (float time = 0; time < 150f; time += DT) {
				sim.update(in, DT, DT);
				if (time < 30f) {
					lastHull = dummy.hull;
					continue;
				}
				Vec3 local = dummy.rot.inverseRotate(new Vec3(ship.pos).sub(dummy.pos), new Vec3()).normalize();
				octants.add((local.x > 0 ? 1 : 0) | (local.y > 0 ? 2 : 0) | (local.z > 0 ? 4 : 0));
				if (last != null) losSum += (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, last.dot(local))))) / DT;
				last = local;
				speedMin = Math.min(speedMin, ship.speed());
				speedMax = Math.max(speedMax, ship.speed());
				float[] ph = byPhase.computeIfAbsent(ship.ai.phaseName(), k -> new float[3]);
				ph[0] += DT;
				for (WeaponMount m : ship.weapons) if (m.firedThisFrame) {
					shots++;
					ph[1]++;
				}
				ph[2] += lastHull - dummy.hull;
				lastHull = dummy.hull;
				java.util.Set<s3d.core.Projectile> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
				live.addAll(sim.combat.projectiles);
				for (java.util.Iterator<Map.Entry<s3d.core.Projectile, String>> it = born.entrySet().iterator(); it.hasNext(); ) {
					Map.Entry<s3d.core.Projectile, String> e = it.next();
					if (live.contains(e.getKey())) continue;
					float[] f = fate.computeIfAbsent(e.getValue(), k -> new float[3]);
					boolean hitIt = e.getKey().pos.distance(dummy.pos) < dummy.radius * 1.3f;
					if (hitIt && dummy.flux != null && dummy.flux.shieldUp()) e.setValue(e.getValue() + "/shield");
					f[hitIt ? 0 : 1]++;
					f[2] += bornDist.get(e.getKey());
					it.remove();
				}
				for (s3d.core.Projectile pr : sim.combat.projectiles) if (pr.source == ship && !born.containsKey(pr)) {
					born.put(pr, ship.ai.phaseName());
					bornDist.put(pr, pr.pos.distance(dummy.pos));
				}
				n++;
			}
			float dealt = damageTaken(dummy);
			if (System.getenv("S3D_AI_DEBUG") != null) for (Map.Entry<String, float[]> e : byPhase.entrySet()) {
				System.out.printf("   %s %-10s %5.1fs %4.0f shots %6.0f hull damage%n", hullId, e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
			}
			if (System.getenv("S3D_AI_DEBUG") != null) for (Map.Entry<String, float[]> e : fate.entrySet()) {
				float[] f = e.getValue();
				System.out.printf("   %s %-10s hit %3.0f missed %3.0f, mean range at firing %.0f%n", hullId, e.getKey(), f[0], f[1], f[2] / Math.max(1f, f[0] + f[1]));
			}
			// The wolf makes runs, so it sees the target from several sides; the hammerhead may rightly
			// stay on the side whose armor it has already broken.
			boolean shipOk = dealt > 2000f && ("wolf".equals(hullId) ? ship.ai.passesMade >= 2 && octants.size() >= 3 : true);
			ok &= shipOk;
			sb.append(String.format("%s: %d gun runs, %d octants, sight line %.1f deg/s, speed %.0f-%.0f, %d shots, damage %.0f (hull %.0f); ",
					hullId, ship.ai.passesMade, octants.size(), losSum / Math.max(1, n), speedMin, speedMax, shots, dealt, hullBefore - dummy.hull));
		}
		check("AI manoeuvres", ok, sb.toString());
	}

	/**
	 * A station from its variant: the core (anchored, invulnerable, turning slowly) and its modules
	 * (armed like the bench's other ships) attached where the data puts them: the module's anchor
	 * point on the station's slot, facing the slot's angle. First element is the core.
	 */
	static java.util.List<Ship3D> station(File game, Map<String, GameData.Hull> hulls, Tuning t, String variantId, int owner) throws Exception {
		String coreHull = GameData.variantHull(game, variantId);
		Ship3D core = ship(game, hulls.get(coreHull), t, coreHull, owner, 1600f, 40000f);
		core.sizeClass = "station";
		core.anchored = true;
		core.invulnerable = true;
		core.spinRate = (float) Math.toRadians(t.stationSpinDeg);
		core.ai = new s3d.core.ShipAI3D(500 + owner);
		return withModules(game, hulls, t, core, variantId, owner);
	}

	/** Core plus its variant's modules, attached the way the data says (anchor on the slot, slot angle). */
	static java.util.List<Ship3D> withModules(File game, Map<String, GameData.Hull> hulls, Tuning t, Ship3D core, String variantId, int owner) throws Exception {
		java.util.List<Ship3D> parts = new java.util.ArrayList<>();
		String coreHull = GameData.variantHull(game, variantId);
		parts.add(core);
		Map<String, GameData.Slot> slots = new java.util.HashMap<>();
		for (GameData.Slot sl : GameData.slots(game, coreHull)) slots.put(sl.id, sl);
		int i = 0;
		for (Map.Entry<String, String> e : GameData.variantModules(game, variantId).entrySet()) {
			GameData.Slot sl = slots.get(e.getKey());
			String modHull = GameData.variantHull(game, e.getValue());
			if (sl == null || modHull == null || !hulls.containsKey(modHull)) continue;
			Ship3D m = armed(game, hulls, t, modHull, modHull + "_" + i++, owner);
			m.hullMax = m.hull = 6000f;
			attachFromData(m, core, sl, GameData.moduleAnchor(game, modHull));
			m.ai = new s3d.core.ShipAI3D(600 + i + owner * 50);
			parts.add(m);
		}
		return parts;
	}

	/** Module on its parent as the data has it: the module's anchor point on the slot, facing the slot's angle. */
	static void attachFromData(Ship3D m, Ship3D parent, GameData.Slot sl, float[] anchor) {
		m.parent = parent;
		double a = Math.toRadians(sl.angle);
		// Module centre in the parent's ship coords (x forward, y left), then the 3D local frame.
		float cx = sl.x - (float) (Math.cos(a) * anchor[0] - Math.sin(a) * anchor[1]);
		float cy = sl.y - (float) (Math.sin(a) * anchor[0] + Math.cos(a) * anchor[1]);
		m.attachLocal.set(-cy, 0f, -cx);
		m.attachRot.setAxisAngle(0f, 1f, 0f, (float) a);
	}

	/**
	 * Station assault: four attackers against an orbital station. The core never takes damage and is
	 * never targeted, nor are the unarmed sections (structural spurs); the armed modules fire back,
	 * take the damage and die; modules stay rigidly in place as the station turns; nobody touches the
	 * structure (real hull shapes, not bounding spheres: at station scale those are far bigger than
	 * the hulls); the station doesn't get pushed.
	 */
	static void stationAssault(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		if (!hulls.containsKey("station1")) {
			check("station assault", false, "no station1 hull");
			return;
		}
		s3d.core.Sim sim = new s3d.core.Sim(t);
		java.util.List<Ship3D> st = station(game, hulls, t, "station1_Standard", 1);
		for (Ship3D p : st) sim.addShip(p);
		Ship3D core = st.get(0);
		java.util.List<Ship3D> attackers = new java.util.ArrayList<>();
		String[] mix = {"dominator", "eagle", "hammerhead", "enforcer"};
		for (int i = 0; i < mix.length; i++) {
			if (!hulls.containsKey(mix[i])) continue;
			Ship3D s = armed(game, hulls, t, mix[i], mix[i], 0);
			s.ai = new s3d.core.ShipAI3D(i * 3 + 1);
			s.ai.homeDir.set(0f, 0f, 1f);
			s.pos.set(-900f + 600f * i, (i % 2) * 150f, 4500f);
			sim.addShip(s);
			attackers.add(s);
		}
		// Rigid: each module's distance from the core and from its neighbour must not change.
		float[] d0 = new float[st.size()];
		for (int i = 1; i < st.size(); i++) d0[i] = st.get(i).pos.distance(core.pos);
		Vec3 corePos0 = new Vec3(core.pos);
		if (System.getenv("S3D_AI_DEBUG") != null) for (Ship3D p : st) {
			System.out.printf("   %s at %s, %d mounts, scale %.2f radius %.0f%n", p.id, p.pos, p.weapons.size(), p.scale, p.radius);
		}
		Vec3 fwd0 = core.rot.forward(new Vec3());
		s3d.core.PilotInput in = screen();
		float maxDrift = 0f, minGap = Float.MAX_VALUE;
		int coreTargeted = 0, unarmedTargeted = 0, frames = 0, stationShots = 0, overloads = 0, contactFrames = 0;
		float modulesDeadAt = -1f, peakFlux = 0f;
		int armedModules = 0;
		for (int i = 1; i < st.size(); i++) if (!st.get(i).unarmedPart()) armedModules++;
		java.util.Map<Ship3D, Boolean> wasOver = new java.util.HashMap<>();
		for (float time = 0; time < 300f && modulesDeadAt < 0f; time += DT) {
			sim.update(in, DT, DT);
			frames++;
			for (int i = 1; i < st.size(); i++) maxDrift = Math.max(maxDrift, Math.abs(st.get(i).pos.distance(core.pos) - d0[i]));
			for (Ship3D p : st) for (WeaponMount m : p.weapons) if (m.firedThisFrame) stationShots++;
			for (Ship3D a : attackers) if (a.flux != null && !a.destroyed) {
				peakFlux = Math.max(peakFlux, a.flux.level());
				if (a.flux.overloaded() && !wasOver.getOrDefault(a, false)) overloads++;
				wasOver.put(a, a.flux.overloaded());
			}
			for (Ship3D a : attackers) {
				if (a.destroyed) continue;
				if (a.ai.target == core) coreTargeted++;
				if (a.ai.target != null && a.ai.target.unarmedPart()) unarmedTargeted++;
				for (Ship3D p : st) {
					minGap = Math.min(minGap, p.surfaceDistance(a.pos) - a.radius);
					if (a.pos.distance(p.pos) < a.radius + p.radius && touching(a, p)) contactFrames++;
				}
			}
			boolean allDead = true;
			for (int i = 1; i < st.size(); i++) if (!st.get(i).destroyed && !st.get(i).unarmedPart()) allDead = false;
			if (allDead) modulesDeadAt = time;
		}
		float turned = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, fwd0.dot(core.rot.forward(new Vec3()))))));
		int lost = 0, modulesDead = 0;
		for (Ship3D a : attackers) if (a.destroyed) lost++;
		for (int i = 1; i < st.size(); i++) if (st.get(i).destroyed && !st.get(i).unarmedPart()) modulesDead++;
		float attackerDamage = 0f;
		for (Ship3D a : attackers) attackerDamage += damageTaken(a);
		// The station only has to hit back: at 4 deg/s (user, 2026-10-13) its sections sweep past the attackers
		// and land far less than at 1 deg/s, and most of the old ">500" came from attackers hurting each other.
		boolean ok = core.hull == core.hullMax && !core.destroyed && coreTargeted == 0 && unarmedTargeted == 0 && maxDrift < 0.5f
				// At vanilla ranges (rangeMult 1) attackers fight close beside the sections, and a slow hull sliding
				// sideways can graze the irregular section next to its target: a few frames, not a ram.
				&& core.pos.distance(corePos0) < 0.01f && armedModules > 0 && modulesDead == armedModules && stationShots > 0 && attackerDamage > 0f
				&& contactFrames <= (t.weaponRangeMult < 1.5f ? 15 : 0);
		check("station assault", ok, String.format("%d armed modules of %d, all destroyed at %.0fs (%d/%d), core hull %.0f/%.0f, core targeted %d frames, unarmed targeted %d frames, "
				+ "module drift %.2f, core moved %.2f, turned %.0f deg, attackers lost %d, damage to attackers %.0f (station shots %d, attacker peak flux %.0f%%, %d overloads), "
				+ "hull contact %d frames, closest %.0f from a hull",
				armedModules, st.size() - 1, modulesDeadAt, modulesDead, armedModules, core.hull, core.hullMax, coreTargeted, unarmedTargeted, maxDrift,
				core.pos.distance(corePos0), turned, lost, attackerDamage, stationShots, peakFlux * 100f, overloads, contactFrames, minGap));
	}

	/** Any of a's collision sample points inside b's real hull. */
	static boolean touching(Ship3D a, Ship3D b) {
		float[] pts = a.volume.collisionSamples();
		Vec3 p = new Vec3();
		for (int k = 0; k < pts.length; k += 3) {
			a.rot.rotate(pts[k] * a.scale, pts[k + 1] * a.scale, pts[k + 2] * a.scale, p).add(a.pos).sub(b.pos);
			b.rot.inverseRotate(p, p).scale(1f / b.scale);
			if (b.volume.contains(p.x, p.y, p.z)) return true;
		}
		return false;
	}

	/**
	 * A ship with modules (Onslaught Mk.I and its armour vambraces) flying: the modules stay in
	 * place on it, a shove on a module moves the whole ship, and killing the ship kills its modules.
	 */
	static void moduleShip(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		if (!hulls.containsKey("onslaught_mk1")) {
			check("ship with modules", false, "no onslaught_mk1 hull");
			return;
		}
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D core = armed(game, hulls, t, "onslaught_mk1", "onslaught_mk1", 0);
		core.ai = new s3d.core.ShipAI3D(5);
		core.pos.set(0f, 0f, 3000f);
		java.util.List<Ship3D> parts = withModules(game, hulls, t, core, "onslaught_mk1_Ancient", 0);
		for (Ship3D p : parts) sim.addShip(p);
		Ship3D dummy = armed(game, hulls, t, "dominator", "dominator", 1);
		dummy.weapons.clear();
		dummy.hullMax = dummy.hull = 1e6f;
		sim.addShip(dummy);
		float[] d0 = new float[parts.size()];
		for (int i = 1; i < parts.size(); i++) d0[i] = parts.get(i).pos.distance(core.pos);
		Vec3 start = new Vec3(core.pos);
		s3d.core.PilotInput in = screen();
		float drift = 0f;
		int moduleShots = 0;
		for (float time = 0; time < 40f; time += DT) {
			sim.update(in, DT, DT);
			for (int i = 1; i < parts.size(); i++) {
				drift = Math.max(drift, Math.abs(parts.get(i).pos.distance(core.pos) - d0[i]));
				for (WeaponMount m : parts.get(i).weapons) if (m.firedThisFrame) moduleShots++;
			}
		}
		float moved = core.pos.distance(start);
		// A shove on a module moves the ship it's part of.
		Ship3D mod = parts.size() > 1 ? parts.get(1) : null;
		Vec3 v0 = new Vec3(core.vel);
		if (mod != null) sim.combat.push(mod, mod.pos, new Vec3(1f, 0f, 0f), 2000f * core.mass / 100f);
		float shoved = new Vec3(core.vel).sub(v0).length();
		sim.combat.kill(core);
		int modsDead = 0;
		for (int i = 1; i < parts.size(); i++) if (parts.get(i).destroyed) modsDead++;
		boolean ok = parts.size() == 3 && drift < 0.5f && moved > 300f && shoved > 5f && modsDead == parts.size() - 1;
		check("ship with modules", ok, String.format("%d modules, ship flew %.0f, module drift %.2f, module shots %d, shove on a module moved the ship %.0f u/s, modules dead with the ship %d/%d",
				parts.size() - 1, moved, drift, moduleShots, shoved, modsDead, parts.size() - 1));
	}

	/**
	 * 15 v 15 AI battle: sim cost per frame must leave room for rendering. Judged on a second battle
	 * (same fleets, other seeds first): in a JVM's first battle the JIT keeps throwing away and
	 * rebuilding the hot AI and weapon code as things happen for the first time (a death, a raised
	 * shield, an overload, a missile shot down), and under Rosetta (the bench and the game both run
	 * the x86_64 JRE) every one of those costs 10-30 ms frames. That's warm-up, not sim cost: the first
	 * battle is reported, not judged. Frames over 8 ms are broken down (sim phases, slowest ship, the
	 * thread's CPU time, GC, JIT, allocation, what was in flight). Also checks that nothing goes NaN.
	 */
	static void aiLoad(File game, Tuning t) throws Exception {
		LoadRun first = aiLoadBattle(game, t, 1000);
		LoadRun run = aiLoadBattle(game, t, 0);
		System.out.printf("   first battle in this JVM (JIT warm-up, not judged): avg %.2f ms, worst %.2f ms, %s%n", first.avg(), first.worstNs / 1e6f, first.summary());
		if (first.worstFrame != null) System.out.println("     worst:" + first.worstFrame);
		System.out.printf("   judged battle: %s; GC in %d of %d frames, %.0f KB allocated per frame%n", run.summary(), run.gcFrames, run.frames, run.allocBytes / 1024f / run.frames);
		for (String f : run.slowFrames) System.out.println(f);
		System.out.printf("   manoeuvres: %d gun runs, %d flanking moves%n", run.runs, run.flanks);
		check("AI 15v15 + 40 fighters sim cost", run.avg() < 4f && run.slow <= 3, String.format("%d ships + %d fighters, avg %.2f ms, worst %.2f ms (at %.0f s), %d frames over 8 ms, up to %d shots in flight, %d destroyed in 90 s",
				run.ships, run.fighters, run.avg(), run.worstNs / 1e6f, run.worstAt, run.slow, run.maxProj, run.dead));
		check("AI 15v15 state stays finite", first.nan == null && run.nan == null,
				first.nan != null ? "first battle: " + first.nan : run.nan != null ? run.nan : "positions, velocities, aim, flux, shots and missiles finite in both battles");
	}

	/** One aiLoad battle's numbers. */
	private static final class LoadRun {
		int frames, slow, slowGc, slowJit, slowStalled, gcFrames, maxProj, dead, ships, fighters, runs, flanks;
		long totalNs, worstNs, allocBytes;
		float worstAt;
		String nan, worstFrame;
		final java.util.List<String> slowFrames = new java.util.ArrayList<>();

		float avg() {
			return totalNs / 1e6f / frames;
		}

		String summary() {
			return String.format("%d frames over 8 ms (%d during a GC, %d while the JIT compiled, %d with the thread not running: CPU time under half the wall time)",
					slow, slowGc, slowJit, slowStalled);
		}
	}

	/** The 15 v 15 with 40 fighters, 90 s; seedOffset 0 is the judged battle. Hull data is read afresh (the cached copy drifts as hulls are re-read). */
	static LoadRun aiLoadBattle(File game, Tuning t, int seedOffset) throws Exception {
		Map<String, GameData.Hull> hulls = GameData.hulls(game);
		s3d.core.Sim sim = new s3d.core.Sim(t);
		sim.combat.seed(1L + seedOffset);
		String[] mix = {"wolf", "lasher", "hammerhead", "eagle", "dominator", "conquest", "onslaught", "hound", "enforcer", "sunder", "medusa", "falcon", "lasher", "wolf", "hammerhead"};
		LoadRun r = new LoadRun();
		for (int side = 0; side < 2; side++) {
			for (int i = 0; i < mix.length; i++) {
				if (!hulls.containsKey(mix[i])) continue;
				Ship3D s = armed(game, hulls, t, mix[i], mix[i] + side + "_" + i, side);
				s.ai = new s3d.core.ShipAI3D(i + side * 31 + seedOffset);
				s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
				s.pos.set(-3000f + 430f * i, (i % 3) * 200f - 200f, side == 0 ? 2800f : -2800f);
				if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				sim.addShip(s);
				r.ships++;
			}
		}
		// Each side's first two ships act as carriers with 10 fighters each.
		for (int side = 0; side < 2; side++) {
			java.util.List<Ship3D> carriers = new java.util.ArrayList<>();
			for (Ship3D s : sim.ships) if (s.owner == side && !s.fighter && carriers.size() < 2) carriers.add(s);
			for (Ship3D c : carriers) {
				c.landingLocal.set(0f, c.volume.topAt(0f, 0f) + 2f, 0f);
				for (int i = 0; i < 10; i++) {
					Ship3D f = fighter(game, hulls, t, i % 3 == 0 ? "dagger" : "broadsword", i % 3 == 0 ? "BOMBER" : "FIGHTER", c, i + side * 40);
					sim.addShip(f);
					r.fighters++;
				}
			}
		}
		s3d.core.PilotInput in = screen();
		sim.profile = new s3d.core.Sim.Profile();
		java.util.List<java.lang.management.GarbageCollectorMXBean> gcs = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
		java.lang.management.CompilationMXBean jit = java.lang.management.ManagementFactory.getCompilationMXBean();
		java.lang.management.RuntimeMXBean jvm = java.lang.management.ManagementFactory.getRuntimeMXBean();
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		for (float time = 0; time < 90f; time += DT) {
			long gcCount = 0, gcMs = 0;
			for (java.lang.management.GarbageCollectorMXBean g : gcs) {
				gcCount -= g.getCollectionCount();
				gcMs -= g.getCollectionTime();
			}
			long jitMs = -jit.getTotalCompilationTime(), alloc = -threads.getCurrentThreadAllocatedBytes(), cpu = -threads.getCurrentThreadCpuTime();
			int dead = 0;
			for (Ship3D s : sim.ships) if (s.destroyed) dead--;
			int proj0 = sim.combat.projectiles.size(), miss0 = sim.combat.missiles.size(), fx0 = sim.combat.effects.size();
			long t0 = System.nanoTime();
			sim.update(in, DT, DT);
			long ns = System.nanoTime() - t0;
			cpu += threads.getCurrentThreadCpuTime();
			alloc += threads.getCurrentThreadAllocatedBytes();
			jitMs += jit.getTotalCompilationTime();
			for (java.lang.management.GarbageCollectorMXBean g : gcs) {
				gcCount += g.getCollectionCount();
				gcMs += g.getCollectionTime();
			}
			r.maxProj = Math.max(r.maxProj, sim.combat.projectiles.size() + sim.combat.missiles.size());
			if (r.nan == null) {
				String bad = notFinite(sim);
				if (bad != null) r.nan = String.format("%s at %.2f s", bad, time);
			}
			if (time <= 5f) continue;
			r.frames++;
			r.totalNs += ns;
			r.allocBytes += alloc;
			if (gcCount > 0) r.gcFrames++;
			boolean slow = ns > 8_000_000L;
			if (!slow && ns <= r.worstNs) continue;
			for (Ship3D s : sim.ships) if (s.destroyed) dead++;
			StringBuilder sb = new StringBuilder(String.format("     %5.1f s (jvm %d ms): %.2f ms, thread cpu %.2f ms;", time, jvm.getUptime(), ns / 1e6f, cpu / 1e6f));
			long[] ph = sim.profile.ns;
			for (int i = 0; i < ph.length; i++) if (ph[i] > 300_000L) sb.append(String.format(" %s %.2f", s3d.core.Sim.Profile.PHASES[i], ph[i] / 1e6f));
			if (sim.profile.worstShip != null) sb.append(String.format(" (slowest ship %s %.2f)", sim.profile.worstShip.id, sim.profile.worstShipNs / 1e6f));
			sb.append(String.format("; gc %d (%d ms), jit +%d ms, alloc %d KB; shots %d->%d, missiles %d->%d, effects %d->%d, deaths %d",
					gcCount, gcMs, jitMs, alloc / 1024, proj0, sim.combat.projectiles.size(), miss0, sim.combat.missiles.size(), fx0, sim.combat.effects.size(), dead));
			if (ns > r.worstNs) {
				r.worstNs = ns;
				r.worstAt = time;
				r.worstFrame = sb.toString();
			}
			if (slow) {
				r.slow++;
				if (gcCount > 0) r.slowGc++;
				if (jitMs > 0) r.slowJit++;
				if (cpu < ns / 2) r.slowStalled++;
				r.slowFrames.add(sb.toString());
			}
		}
		sim.profile = null;
		for (Ship3D s : sim.ships) {
			if (s.destroyed) r.dead++;
			if (s.ai != null && !s.fighter) {
				r.runs += s.ai.passesMade;
				r.flanks += s.ai.plans[s3d.core.ShipAI3D.Move.FLANK.ordinal()];
			}
		}
		return r;
	}

	/** First NaN / infinite position, velocity, rotation, aim, flux, shot or missile, or null. */
	static String notFinite(s3d.core.Sim sim) {
		for (Ship3D s : sim.ships) {
			if (!finite(s.pos) || !finite(s.vel) || !finite(s.angVel) || !Float.isFinite(s.rot.w + s.rot.x + s.rot.y + s.rot.z)) return "ship " + s.id + " motion";
			if (s.flux != null && !Float.isFinite(s.flux.flux + s.flux.hardFlux)) return "ship " + s.id + " flux";
			for (WeaponMount m : s.weapons) if (!finite(m.aimDir)) return "aim of " + m.slotId + " on " + s.id;
		}
		for (s3d.core.Missile3D m : sim.combat.missiles) if (!finite(m.pos) || !finite(m.vel)) return "missile " + m.spec.id;
		for (s3d.core.Projectile p : sim.combat.projectiles) if (!finite(p.pos) || !finite(p.vel)) return "shot " + p.spec.id;
		return null;
	}

	static boolean finite(Vec3 v) {
		return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z);
	}

	/** Ordered to retreat, an AI ship turns for home and runs. */
	/** Centre-to-centre distance out to which o's longest gun reaches (missiles left out), as the AI counts it. */
	static float gunReach(Ship3D o, Tuning t) {
		float r = 0f;
		for (WeaponMount m : o.weapons) if (m.spec.supported && !m.spec.pdOnly && m.spec.kind != WeaponSpec3D.Kind.MISSILE) r = Math.max(r, m.spec.range);
		return r > 0f ? r * t.weaponRangeMult + o.radius * 0.3f : 0f;
	}

	/** Gives every gun on s this range. */
	static void setRange(Ship3D s, float range) {
		for (WeaponMount m : s.weapons) m.spec.range = range;
	}

	/**
	 * Caution, two set pieces, each flown with caution on and off:
	 * (1) a Wolf with 800-range guns against an AI Enforcer with 600-range guns: it should fight from
	 *     outside the Enforcer's reach (it can: it's faster and outranges it);
	 * (2) a Hammerhead ordered to attack a parked target while a second enemy sits off to one side: it
	 *     should keep out of the second one's reach.
	 */
	static void aiCaution(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		float[] kiteIn = new float[2], kiteTaken = new float[2], kiteDealt = new float[2], sideIn = new float[2], sideDealt = new float[2];
		for (int k = 0; k < 2; k++) {
			float cau = k == 0 ? 0f : 1f;
			{
				s3d.core.Sim sim = new s3d.core.Sim(t);
				Ship3D wolf = armed(game, hulls, t, "wolf", "wolf", 0);
				setRange(wolf, 800f);
				wolf.ai = new s3d.core.ShipAI3D(3);
				wolf.ai.caution = cau;
				wolf.ai.opportunism = 0f;
				wolf.pos.set(0f, 0f, 2000f);
				sim.addShip(wolf);
				Ship3D enf = armed(game, hulls, t, "enforcer", "enforcer", 1);
				setRange(enf, 600f);
				enf.hullMax = enf.hull = 1e9f;
				enf.ai = new s3d.core.ShipAI3D(4);
				enf.ai.caution = 0f;
				enf.ai.opportunism = 0f;
				enf.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				sim.addShip(enf);
				s3d.core.PilotInput in = screen();
				int frames = 0, inReach = 0;
				for (float time = 0; time < 120f && !wolf.destroyed; time += DT) {
					sim.update(in, DT, DT);
					if (time < 20f) continue;
					frames++;
					if (wolf.pos.distance(enf.pos) < gunReach(enf, t)) inReach++;
				}
				kiteIn[k] = 100f * inReach / Math.max(1, frames);
				kiteTaken[k] = damageTaken(wolf);
				kiteDealt[k] = damageTaken(enf);
			}
			{
				s3d.core.Sim sim = new s3d.core.Sim(t);
				Ship3D hh = armed(game, hulls, t, "hammerhead", "hammerhead", 0);
				hh.ai = new s3d.core.ShipAI3D(5);
				hh.ai.caution = cau;
				hh.ai.opportunism = 0f;
				hh.pos.set(0f, 0f, 1800f);
				sim.addShip(hh);
				Ship3D tgt = armed(game, hulls, t, "enforcer", "target", 1);
				tgt.weapons.clear();
				tgt.hullMax = tgt.hull = 1e9f;
				sim.addShip(tgt);
				s3d.core.Order3D attack = new s3d.core.Order3D(s3d.core.Order3D.Kind.ATTACK); // this one, not the nearer gunship
				attack.ship = tgt;
				hh.order = attack;
				Ship3D side = armed(game, hulls, t, "enforcer", "side", 1); // parked, guns on board but no pilot: never fires
				side.hullMax = side.hull = 1e9f;
				side.pos.set(900f, 0f, 300f);
				side.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(-90)); // nose toward the fight
				sim.addShip(side);
				s3d.core.PilotInput in = screen();
				int frames = 0, inReach = 0;
				float reach = gunReach(side, t);
				for (float time = 0; time < 120f; time += DT) {
					sim.update(in, DT, DT);
					if (time < 20f) continue;
					frames++;
					if (hh.pos.distance(side.pos) < reach) inReach++;
				}
				sideIn[k] = 100f * inReach / Math.max(1, frames);
				sideDealt[k] = damageTaken(tgt) + damageTaken(side);
			}
		}
		check("AI caution: stands off a shorter-ranged ship", kiteIn[1] < kiteIn[0] * 0.5f && kiteIn[1] < 25f && kiteDealt[1] > kiteDealt[0] * 0.5f,
				String.format("Wolf (800 guns) vs Enforcer (600 guns): in its reach %.0f%% of the time careless, %.0f%% cautious; damage taken %.0f / %.0f, dealt %.0f / %.0f",
						kiteIn[0], kiteIn[1], kiteTaken[0], kiteTaken[1], kiteDealt[0], kiteDealt[1]));
		check("AI caution: keeps out of a second enemy's reach", sideIn[1] < sideIn[0] * 0.6f && sideDealt[1] > sideDealt[0] * 0.5f,
				String.format("Hammerhead on a target with another enemy 900 off: in the other's reach %.0f%% careless, %.0f%% cautious; damage dealt %.0f / %.0f",
						sideIn[0], sideIn[1], sideDealt[0], sideDealt[1]));
	}

	/**
	 * Opportunism, two set pieces, each with opportunism off and on:
	 * (1) a parked gun platform whose target has a friendly sitting in the line of fire, and a second
	 *     enemy in reach off to the side: it should shoot the second one rather than hold fire;
	 * (2) the same platform held at 90% flux with a zero-flux gun: that gun should keep firing.
	 */
	static void aiOpportunism(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		int[] shots = new int[2], freeShots = new int[2];
		float[] otherLost = new float[2];
		for (int k = 0; k < 2; k++) {
			float opp = k;
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D gun = armed(game, hulls, t, "enforcer", "platform", 0);
			gun.anchored = true; // a fixed gun platform: targets, manages flux and fires, doesn't fly
			gun.ai = new s3d.core.ShipAI3D(6);
			gun.ai.caution = 0f;
			gun.ai.opportunism = opp;
			gun.flux.ventRateMult = 0f; // can't vent (part 2 holds its flux high)
			sim.addShip(gun);
			Ship3D a = armed(game, hulls, t, "enforcer", "a", 1);
			a.weapons.clear();
			a.hullMax = a.hull = 1e9f;
			a.pos.set(0f, 0f, -600f);
			sim.addShip(a);
			Ship3D friend = armed(game, hulls, t, "enforcer", "friend", 0);
			friend.weapons.clear();
			friend.hullMax = friend.hull = 1e9f;
			friend.pos.set(0f, 0f, -300f);
			sim.addShip(friend);
			Ship3D b = armed(game, hulls, t, "enforcer", "b", 1);
			b.weapons.clear();
			b.hullMax = b.hull = 1e9f;
			b.pos.set(350f, 0f, -620f); // a little further than the target, so it's the second choice
			sim.addShip(b);
			s3d.core.PilotInput in = screen();
			for (float time = 0; time < 15f; time += DT) {
				sim.update(in, DT, DT);
				for (WeaponMount m : gun.weapons) if (m.firedThisFrame) shots[k]++;
			}
			otherLost[k] = damageTaken(b);
			// (2) Flux held at 90%: only a gun that costs no flux may fire.
			WeaponMount free = null;
			for (WeaponMount m : gun.weapons) if (free == null && Combat.canReach(gun, m, a.pos, t)) free = m;
			free.spec.fluxPerShot = 0f;
			friend.pos.set(2000f, 0f, 2000f); // out of the way now
			for (float time = 0; time < 10f; time += DT) {
				gun.flux.flux = gun.flux.maxFlux * 0.9f;
				gun.flux.hardFlux = 0f;
				sim.update(in, DT, DT);
				if (free.firedThisFrame) freeShots[k]++;
			}
		}
		check("AI opportunism: blocked guns find another target", otherLost[1] > otherLost[0] + 300f && shots[1] > shots[0],
				String.format("friendly in the line to the target: %d shots, %.0f damage to the other enemy without; %d shots, %.0f with",
						shots[0], otherLost[0], shots[1], otherLost[1]));
		check("AI opportunism: zero-flux guns fire at high flux", freeShots[0] == 0 && freeShots[1] >= 5,
				String.format("at 90%% flux the zero-flux gun fired %d times without, %d with", freeShots[0], freeShots[1]));
	}

	/**
	 * New AI against old (caution and opportunism off), mirrored 6v6 fleets, each seed fought twice with
	 * the sides swapped so neither layout favours one. Prints damage dealt, ships lost, time spent inside
	 * enemy guns' reach, and the mounts' fire stats per AI.
	 */
	static void aiAB(File game, Map<String, GameData.Hull> hulls, Tuning t, int seeds) throws Exception {
		String[] mix = {"wolf", "lasher", "hammerhead", "enforcer", "eagle", "dominator"};
		float[] dealt = new float[2];
		int[] lost = new int[2], won = new int[2];
		long[] exposedFrames = new long[2], shipFrames = new long[2], shotCount = new long[2];
		long[][] fire = new long[2][7];
		for (int seed = 0; seed < seeds; seed++) {
			for (int newSide = 0; newSide < 2; newSide++) {
				s3d.core.Sim sim = new s3d.core.Sim(t);
				java.util.List<Ship3D> all = new java.util.ArrayList<>();
				for (int side = 0; side < 2; side++) {
					for (int i = 0; i < mix.length; i++) {
						Ship3D s = armed(game, hulls, t, mix[i], mix[i] + side, side);
						s.ai = new s3d.core.ShipAI3D(i + side * 7 + seed * 13);
						if (side != newSide) {
							s.ai.caution = 0f;
							s.ai.opportunism = 0f;
						}
						s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
						float x = (-1000f + 400f * i) * (side == 0 ? 1f : -1f);
						s.pos.set(x, (i % 2) * 200f - 100f, side == 0 ? 2500f : -2500f);
						if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
						sim.addShip(s);
						all.add(s);
					}
				}
				s3d.core.PilotInput in = screen();
				for (float time = 0; time < 240f; time += DT) {
					sim.update(in, DT, DT);
					for (Ship3D s : all) {
						if (s.destroyed) continue;
						int who = s.owner == newSide ? 1 : 0;
						shipFrames[who]++;
						for (WeaponMount m : s.weapons) if (m.firedThisFrame) shotCount[who]++;
						for (Ship3D o : all) {
							if (o.owner != s.owner && !o.destroyed && o.pos.distance(s.pos) < gunReach(o, t)) {
								exposedFrames[who]++;
								break;
							}
						}
					}
				}
				float[] d = new float[2];
				int[] l = new int[2];
				for (Ship3D s : all) {
					int who = s.owner == newSide ? 1 : 0;
					d[1 - who] += damageTaken(s);
					if (s.destroyed) l[who]++;
					for (int i = 0; i < 7; i++) fire[who][i] += s.ai.fireStats[i];
				}
				for (int i = 0; i < 2; i++) {
					dealt[i] += d[i];
					lost[i] += l[i];
				}
				if (d[1] > d[0]) won[1]++;
				else won[0]++;
				System.out.printf("seed %2d, new AI on side %d: damage dealt old %6.0f / new %6.0f, ships lost old %d / new %d%n", seed, newSide, d[0], d[1], l[0], l[1]);
			}
		}
		String[] who = {"old", "new"};
		for (int i = 0; i < 2; i++) {
			long[] f = fire[i];
			System.out.printf("%s: damage dealt %.0f, ships lost %d, ahead on damage in %d of %d, inside enemy reach %.0f%% of ship-time, %.2f shots per ship-second%n",
					who[i], dealt[i], lost[i], won[i], seeds * 2, 100f * exposedFrames[i] / Math.max(1, shipFrames[i]), shotCount[i] / Math.max(1f, shipFrames[i] * DT));
			System.out.printf("   mounts with an enemy in reach: fire %.0f%%, held: barrel not on %.0f%%, flux %.0f%%, friendly in line %.0f%%, missile %.0f%%; switched target %d%n",
					100f * f[1] / Math.max(1, f[0]), 100f * f[2] / Math.max(1, f[0]), 100f * f[3] / Math.max(1, f[0]), 100f * f[4] / Math.max(1, f[0]), 100f * f[5] / Math.max(1, f[0]), f[6]);
		}
	}

	static void aiRetreat(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D runner = armed(game, hulls, t, "hammerhead", "hammerhead", 1);
		runner.ai = new s3d.core.ShipAI3D(3);
		runner.ai.homeDir.set(0f, 0f, -1f);
		runner.rot.setAxisAngle(0, 1, 0, (float) Math.PI); // facing +Z, away from home
		sim.addShip(runner);
		Ship3D chaser = armed(game, hulls, t, "wolf", "wolf", 0);
		chaser.pos.set(0f, 0f, 1500f);
		sim.addShip(chaser);
		runner.retreatOrdered = true;
		s3d.core.PilotInput in = screen();
		for (float time = 0; time < 40f; time += DT) sim.update(in, DT, DT);
		float speedHome = -runner.vel.z;
		float expect = runner.params.fwdSpeed * 40f * 0.6f; // turning round costs some of the 40 s
		check("AI retreat", -runner.pos.z > expect && speedHome > runner.params.fwdSpeed * 0.8f,
				String.format("ran %.0f toward home in 40 s (at least %.0f expected), speed toward home %.0f of %.0f",
						-runner.pos.z, expect, speedHome, runner.params.fwdSpeed));
	}

	/** An armed hull with its guns taken away and no pilot: something to guard, chase or avoid. */
	static Ship3D dummy(File game, Map<String, GameData.Hull> hulls, Tuning t, String hullId, String id, int owner, float x, float z) throws Exception {
		Ship3D d = armed(game, hulls, t, hullId, id, owner);
		d.weapons.clear();
		d.pos.set(x, 0f, z);
		return d;
	}

	static s3d.core.Order3D order(s3d.core.Order3D.Kind kind, float x, float z, float radius) {
		s3d.core.Order3D o = new s3d.core.Order3D(kind);
		o.point.set(x, 0f, z);
		o.radius = radius;
		return o;
	}

	/**
	 * Orders from the command map: an escort keeps with its ship, ignores an enemy far from it and
	 * fights one that comes close; a ship told to hold a point goes there and stays, leaving an
	 * enemy far from the point alone; a capturer ends up inside the objective's ring; an attack
	 * order goes after the ordered (farther) enemy; a ship told to avoid an area stays out of it and
	 * leaves the enemy inside alone.
	 */
	static void aiOrders(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.PilotInput in = screen();
		StringBuilder sb = new StringBuilder();
		boolean ok = true;
		{ // escort
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D big = armed(game, hulls, t, "dominator", "dominator", 0);
			big.ai = new s3d.core.ShipAI3D(1);
			big.order = order(s3d.core.Order3D.Kind.HOLD, 0f, -4000f, 300f);
			Ship3D esc = armed(game, hulls, t, "wolf", "wolf", 0);
			esc.pos.set(900f, 0f, 700f);
			esc.ai = new s3d.core.ShipAI3D(2);
			esc.order = new s3d.core.Order3D(s3d.core.Order3D.Kind.ESCORT);
			esc.order.ship = big;
			Ship3D far = dummy(game, hulls, t, "hound", "far", 1, 6000f, -2500f);
			for (Ship3D x : new Ship3D[] {big, esc, far}) sim.addShip(x);
			float sum = 0f, worst = 0f;
			int n = 0;
			boolean chasedFar = false;
			for (float time = 0; time < 40f; time += DT) {
				sim.update(in, DT, DT);
				if (esc.ai.target == far) chasedFar = true;
				if (time > 15f) {
					float d = esc.pos.distance(big.pos);
					sum += d;
					worst = Math.max(worst, d);
					n++;
				}
			}
			Ship3D near = dummy(game, hulls, t, "hound", "near", 1, big.pos.x + 700f, big.pos.z - 500f);
			near.hullMax = near.hull = 1e6f;
			sim.addShip(near);
			boolean engaged = false;
			float strayed = 0f;
			for (float time = 0; time < 20f; time += DT) {
				sim.update(in, DT, DT);
				if (esc.ai.target == near) engaged = true;
				strayed = Math.max(strayed, esc.pos.distance(big.pos));
			}
			boolean pass = n > 0 && sum / n < 1200f && !chasedFar && engaged && strayed < 3500f;
			ok &= pass;
			sb.append(String.format("escort: mean %.0f / worst %.0f from its ship, chased the far enemy %b, engaged the near one %b (strayed up to %.0f); ",
					sum / Math.max(1, n), worst, chasedFar, engaged, strayed));
		}
		{ // hold a point, an enemy far from it
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D h = armed(game, hulls, t, "hammerhead", "hammerhead", 0);
			h.ai = new s3d.core.ShipAI3D(3);
			h.order = order(s3d.core.Order3D.Kind.HOLD, 3000f, -3000f, 400f);
			Ship3D far = dummy(game, hulls, t, "wolf", "far", 1, -4000f, 3000f);
			sim.addShip(h);
			sim.addShip(far);
			float settled = -1f;
			boolean chased = false;
			for (float time = 0; time < 90f; time += DT) {
				sim.update(in, DT, DT);
				if (h.ai.target == far) chased = true;
				float d = h.pos.distance(h.order.point);
				if (d < h.order.radius + 200f && settled < 0f) settled = time;
				if (d > h.order.radius + 400f) settled = -1f;
			}
			float end = h.pos.distance(h.order.point);
			boolean pass = settled >= 0f && !chased && end < h.order.radius + 200f;
			ok &= pass;
			sb.append(String.format("hold: there from %.0f s, %.0f from the point at the end, chased the far enemy %b; ", settled, end, chased));
		}
		{ // capture an objective
			s3d.core.Sim sim = new s3d.core.Sim(t);
			s3d.core.Objective3D obj = new s3d.core.Objective3D("nav_buoy", "Nav Buoy");
			obj.pos.set(-2500f, 0f, -2500f);
			obj.radius = 300f;
			sim.objectives.add(obj);
			Ship3D e = armed(game, hulls, t, "eagle", "eagle", 0);
			e.ai = new s3d.core.ShipAI3D(4);
			e.order = new s3d.core.Order3D(s3d.core.Order3D.Kind.CAPTURE);
			e.order.objective = obj;
			e.order.radius = obj.radius;
			sim.addShip(e);
			float on = 0f;
			for (float time = 0; time < 120f; time += DT) {
				sim.update(in, DT, DT);
				if (obj.flatDistance(e.pos) < obj.radius) on += DT;
			}
			boolean pass = on > 30f && obj.flatDistance(e.pos) < obj.radius;
			ok &= pass;
			sb.append(String.format("capture: %.0f s inside the ring, %.0f from it at the end; ", on, obj.flatDistance(e.pos)));
		}
		{ // attack the ordered ship, not the nearest
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D a = armed(game, hulls, t, "lasher", "lasher", 0);
			a.ai = new s3d.core.ShipAI3D(5);
			Ship3D near = dummy(game, hulls, t, "wolf", "near", 1, 600f, -800f);
			Ship3D far = dummy(game, hulls, t, "hound", "far", 1, -2500f, -3500f);
			a.order = new s3d.core.Order3D(s3d.core.Order3D.Kind.ATTACK);
			a.order.ship = far;
			for (Ship3D x : new Ship3D[] {a, near, far}) sim.addShip(x);
			float d0 = a.pos.distance(far.pos);
			int onFar = 0, frames = 0;
			for (float time = 0; time < 30f; time += DT) {
				sim.update(in, DT, DT);
				frames++;
				if (a.ai.target == far) onFar++;
			}
			boolean pass = onFar > frames * 0.95f && a.pos.distance(far.pos) < d0 * 0.6f;
			ok &= pass;
			sb.append(String.format("attack: on the ordered ship %.0f%% of the time, closed from %.0f to %.0f; ", 100f * onFar / frames, d0, a.pos.distance(far.pos)));
		}
		{ // avoid an area with an enemy in it
			s3d.core.Sim sim = new s3d.core.Sim(t);
			Ship3D v = armed(game, hulls, t, "enforcer", "enforcer", 0);
			v.ai = new s3d.core.ShipAI3D(6);
			v.pos.set(0f, 0f, 1200f);
			v.order = order(s3d.core.Order3D.Kind.AVOID, 0f, -2000f, 1500f);
			Ship3D inside = dummy(game, hulls, t, "wolf", "inside", 1, 0f, -2000f);
			Ship3D outside = dummy(game, hulls, t, "wolf", "outside", 1, 3500f, 1500f);
			for (Ship3D x : new Ship3D[] {v, inside, outside}) sim.addShip(x);
			float closest = Float.MAX_VALUE;
			boolean wentIn = false, foughtOutside = false;
			for (float time = 0; time < 40f; time += DT) {
				sim.update(in, DT, DT);
				closest = Math.min(closest, v.pos.distance(v.order.point));
				if (v.ai.target == inside) wentIn = true;
				if (v.ai.target == outside) foughtOutside = true;
			}
			boolean pass = closest > v.order.radius * 0.9f && !wentIn && foughtOutside;
			ok &= pass;
			sb.append(String.format("avoid: closest %.0f to the area's centre (radius %.0f), targeted the enemy inside %b, fought the one outside %b (destroyed %b)",
					closest, v.order.radius, wentIn, foughtOutside, outside.destroyed));
		}
		check("AI orders", ok, sb.toString());
	}

	/** Fires continuously at aim for the given seconds; returns the number of hits on target. */
	static int run(Combat c, Ship3D shooter, Ship3D target, Vec3 aim, float seconds, Tuning t) {
		int hits = 0;
		java.util.List<Ship3D> ships = java.util.List.of(shooter, target);
		for (float time = 0; time < seconds; time += DT) {
			c.stepWeapons(shooter, aim, true, DT, t);
			int before = c.effects.size();
			c.stepProjectiles(ships, DT);
			for (int i = before; i < c.effects.size(); i++) {
				s3d.core.Effect.Kind k = c.effects.get(i).kind;
				if (k == s3d.core.Effect.Kind.DAMAGE_TEXT) hits++;
				if (k == s3d.core.Effect.Kind.EXPLOSION) sawExplosion = true;
			}
			c.stepEffects(DT);
		}
		return hits;
	}

	@SuppressWarnings("unused")
	private static Quat unused() {
		return new Quat();
	}
}
