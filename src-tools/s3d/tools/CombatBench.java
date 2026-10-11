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
		if (System.getenv("S3D_SKIRMISH_SWEEP") != null) {
			// Many seeds of the 4v4, one line each: for judging AI changes beyond one lucky/unlucky run.
			int n = Integer.parseInt(System.getenv("S3D_SKIRMISH_SWEEP"));
			for (int seed = 0; seed < n; seed++) aiSkirmish(game, hulls, t, seed * 13, false);
			return;
		}

		armorMath();
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
		empRules(game, hulls, t);
		missileRules(game, hulls, t);
		fighterRules(game, hulls, t);
		systemRules(game, hulls, t);
		collisionRules(game, hulls, t);
		soundEvents(game, hulls, t);
		weightRules(game, hulls, t);
		aiSkirmish(game, hulls, t, 0, true);
		aiManoeuvres(game, hulls, t);
		stationAssault(game, hulls, t);
		moduleShip(game, hulls, t);
		aiBroadside(game, hulls, t);
		aiRetreat(game, hulls, t);
		aiOrders(game, hulls, t);
		aiLoad(game, hulls, t);
		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
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

	static void armorMath() {
		Combat c = new Combat();
		// Vanilla: 50 kinetic hit strength vs 1500 armor is capped at 85% reduction -> 100*0.5*0.15 = 7.5 to armor.
		float kin = 100f * 0.5f * Math.max(1f - c.maxArmorDamageReduction, 50f / (50f + 1500f));
		float he = 100f * 2f * Math.max(1f - c.maxArmorDamageReduction, 200f / (200f + 1500f));
		check("armor formula (kinetic vs heavy)", Math.abs(kin - 7.5f) < 0.01f, String.format("kinetic %.2f, HE %.2f armor dmg per 100", kin, he));
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
			target.pos.set(0, 0, -650);
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
		check("AI omni shield faces the fire", enforcer.flux.shieldOn && lostLate < 0.2f * Math.max(1f, lostEarly * 3f) && enforcer.flux.flux > 0f,
				String.format("hull/armor lost first 2 s %.0f, last 6 s %.0f; flux %.0f/%.0f, shield %s", lostEarly, lostLate,
						enforcer.flux.flux, enforcer.flux.maxFlux, enforcer.flux.shieldOn ? "up" : "down"));
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
		WeaponSpec3D pdGun = gun("ENERGY", 1f, 40f, 500f, 1000f); // PD-laser-like
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
		enemyShip.pos.set(0f, 0f, -1500f);
		sim.addShip(enemyShip);
		Ship3D enemyCarrier2 = armed(game, hulls, t, "heron", "heron2", 1);
		enemyCarrier2.weapons.clear();
		enemyCarrier2.pos.set(3000f, 0f, -3000f);
		sim.addShip(enemyCarrier2);
		Ship3D raider = fighter(game, hulls, t, "broadsword", "FIGHTER", enemyCarrier2, 1);
		raider.pos.set(400f, 150f, -300f); // off the line to the enemy ship
		raider.hullMax = raider.hull = 300f;
		raider.weapons.clear();
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

	/** Each system kind does its job; the AI uses them at sensible moments. */
	static void systemRules(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.PilotInput in = screen();

		// Burn Drive: much faster in a straight line, turning nearly gone.
		s3d.core.Sim sim = new s3d.core.Sim(t);
		Ship3D wolf = ship(game, hulls.get("wolf"), t, "wolf", 0, 150, 1e9f);
		wolf.system = system("burndrive", 1f, 5f, 1f, 10f, 0, 0f);
		wolf.system.toggle = true;
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
		check("burn drive: fast, barely turns", burnt > plain * 2f && Math.abs(Math.toDegrees(yaw1 - yaw0)) < 45f,
				String.format("speed %.0f -> %.0f, turned %.0f deg in 3 s at full stick (normally ~90)", plain, burnt, Math.abs(Math.toDegrees(yaw1 - yaw0))));

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
			if (d < conquest.ai.weaponRange) inRange++;
			boolean gunSide = conquest.ai.axis == s3d.core.ShipAI3D.Axis.PORT ? local.x < 0f : local.x > 0f;
			if (gunSide && Math.abs(local.x) > Math.abs(local.z)) onSide++;
		}
		boolean ok = conquest.ai.axis != s3d.core.ShipAI3D.Axis.NOSE && onSide > frames * 0.5f && inRange > frames * 0.6f && damageTaken(dummy) > 0f;
		check("AI broadside (conquest)", ok, String.format("axis %s, target on gun side %.0f%%, in range %.0f%% (%.0f-%.0f, range %.0f), damage dealt %.0f",
				conquest.ai.axis, 100f * onSide / frames, 100f * inRange / frames, minD, maxD, conquest.ai.weaponRange, damageTaken(dummy)));
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
			dummy.weapons.clear();
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
	 * never targeted; modules fire back, take the damage and die; modules stay rigidly in place as
	 * the station turns; nobody touches the structure (real hull shapes, not bounding spheres: at
	 * station scale those are far bigger than the hulls); the station doesn't get pushed.
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
		int coreTargeted = 0, frames = 0, stationShots = 0, overloads = 0, contactFrames = 0;
		float modulesDeadAt = -1f, peakFlux = 0f;
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
				for (Ship3D p : st) {
					minGap = Math.min(minGap, p.surfaceDistance(a.pos) - a.radius);
					if (a.pos.distance(p.pos) < a.radius + p.radius && touching(a, p)) contactFrames++;
				}
			}
			boolean allDead = true;
			for (int i = 1; i < st.size(); i++) if (!st.get(i).destroyed) allDead = false;
			if (allDead) modulesDeadAt = time;
		}
		float turned = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, fwd0.dot(core.rot.forward(new Vec3()))))));
		int lost = 0, modulesDead = 0;
		for (Ship3D a : attackers) if (a.destroyed) lost++;
		for (int i = 1; i < st.size(); i++) if (st.get(i).destroyed) modulesDead++;
		float attackerDamage = 0f;
		for (Ship3D a : attackers) attackerDamage += damageTaken(a);
		boolean ok = core.hull == core.hullMax && !core.destroyed && coreTargeted == 0 && maxDrift < 0.5f
				&& core.pos.distance(corePos0) < 0.01f && modulesDead == st.size() - 1 && attackerDamage > 500f && contactFrames == 0;
		check("station assault", ok, String.format("%d modules, all destroyed at %.0fs (%d/%d), core hull %.0f/%.0f, core targeted %d frames, "
				+ "module drift %.2f, core moved %.2f, turned %.0f deg, attackers lost %d, damage to attackers %.0f (station shots %d, attacker peak flux %.0f%%, %d overloads), "
				+ "hull contact %d frames, closest %.0f from a hull",
				st.size() - 1, modulesDeadAt, modulesDead, st.size() - 1, core.hull, core.hullMax, coreTargeted, maxDrift,
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

	/** 15 v 15 AI battle: sim cost per frame must leave room for rendering. */
	static void aiLoad(File game, Map<String, GameData.Hull> hulls, Tuning t) throws Exception {
		s3d.core.Sim sim = new s3d.core.Sim(t);
		String[] mix = {"wolf", "lasher", "hammerhead", "eagle", "dominator", "conquest", "onslaught", "hound", "enforcer", "sunder", "medusa", "falcon", "lasher", "wolf", "hammerhead"};
		for (int side = 0; side < 2; side++) {
			for (int i = 0; i < mix.length; i++) {
				if (!hulls.containsKey(mix[i])) continue;
				Ship3D s = armed(game, hulls, t, mix[i], mix[i] + side + "_" + i, side);
				s.ai = new s3d.core.ShipAI3D(i + side * 31);
				s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
				s.pos.set(-3000f + 430f * i, (i % 3) * 200f - 200f, side == 0 ? 2800f : -2800f);
				if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				sim.addShip(s);
			}
		}
		// Each side's first two ships act as carriers with 10 fighters each.
		int fighters = 0, slow = 0;
		float worstAt = 0f;
		for (int side = 0; side < 2; side++) {
			java.util.List<Ship3D> carriers = new java.util.ArrayList<>();
			for (Ship3D s : sim.ships) if (s.owner == side && !s.fighter && carriers.size() < 2) carriers.add(s);
			for (Ship3D c : carriers) {
				c.landingLocal.set(0f, c.volume.topAt(0f, 0f) + 2f, 0f);
				for (int i = 0; i < 10; i++) {
					Ship3D f = fighter(game, hulls, t, i % 3 == 0 ? "dagger" : "broadsword", i % 3 == 0 ? "BOMBER" : "FIGHTER", c, i + side * 40);
					sim.addShip(f);
					fighters++;
				}
			}
		}
		s3d.core.PilotInput in = screen();
		long total = 0, worst = 0;
		int frames = 0, maxProj = 0;
		for (float time = 0; time < 90f; time += DT) {
			long t0 = System.nanoTime();
			sim.update(in, DT, DT);
			long ns = System.nanoTime() - t0;
			if (time > 5f) {
				total += ns;
				if (ns > worst) {
					worst = ns;
					worstAt = time;
				}
				if (ns > 8_000_000L) slow++;
				frames++;
			}
			maxProj = Math.max(maxProj, sim.combat.projectiles.size() + sim.combat.missiles.size());
		}
		int dead = 0, runs = 0, flanks = 0;
		for (Ship3D s : sim.ships) {
			if (s.destroyed) dead++;
			if (s.ai != null && !s.fighter) {
				runs += s.ai.passesMade;
				flanks += s.ai.plans[s3d.core.ShipAI3D.Move.FLANK.ordinal()];
			}
		}
		float avg = total / 1e6f / frames;
		System.out.printf("   manoeuvres: %d gun runs, %d flanking moves%n", runs, flanks);
		check("AI 15v15 + 40 fighters sim cost", avg < 4f && slow <= 3, String.format("%d ships + %d fighters, avg %.2f ms, worst %.2f ms (at %.0f s), %d frames over 8 ms, up to %d shots in flight, %d destroyed in 90 s",
				sim.ships.size() - fighters, fighters, avg, worst / 1e6f, worstAt, slow, maxProj, dead));
	}

	/** Ordered to retreat, an AI ship turns for home and runs. */
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
