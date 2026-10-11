package s3d.tools;

import static org.lwjgl.opengl.EXTFramebufferObject.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

import s3d.core.ChaseCamera;
import s3d.core.FlightParams;
import s3d.core.PilotInput;
import s3d.core.Ship3D;
import s3d.core.Sim;
import s3d.core.Tuning;
import s3d.math.Quat;
import s3d.math.Vec3;
import s3d.render.GLCaps;
import s3d.render.HudRenderer;
import s3d.render.Renderer3D;
import s3d.render.ShipModel;
import s3d.render.SpriteImage;

/**
 * Renders relief meshes through the real renderer/shaders on this machine's OpenGL (same legacy
 * context type as the game) and writes contact sheets to build/preview/. Also times a crowded scene.
 */
public final class MeshPreview {
	private static final int VW = 640, VH = 400;

	public static void main(String[] args) throws Exception {
		File game = new File(args[0]);
		File build = new File(args[1]);
		List<String> ids = new ArrayList<>();
		for (int i = 2; i < args.length; i++) ids.add(args[i]);
		if (ids.isEmpty()) ids = List.of("wolf", "hammerhead", "eagle", "onslaught");

		boolean pbuffer = createContext();
		System.out.println("context: " + (pbuffer ? "pbuffer" : "window") + "  " + GLCaps.summary());

		Tuning t = GameData.modTuning(build);
		Map<String, GameData.Hull> hulls = GameData.hulls(game);
		Renderer3D renderer = new Renderer3D();
		renderer.init();
		int[] out = createCaptureTarget(VW, VH);
		File dir = new File(build, "preview");
		dir.mkdirs();

		for (String id : ids) {
			GameData.Hull h = hulls.get(id);
			if (h == null) {
				System.out.println("unknown hull " + id);
				continue;
			}
			Ship3D s = makeShip(game, h, t, id);
			Sim sim = new Sim(t);
			sim.addShip(s);
			sim.setPlayer(s);
			s.thrustVisual = 0.9f;
			ShipModel m = Renderer3D.modelFor(s, t);
			System.out.println(id + ": " + m.info.summary());

			float r = s.radius;
			// eye position (relative to the ship), label
			Object[][] views = {
					{new Vec3(0, r * 4.2f, 0.01f), "top (compare with sprite)"},
					{new Vec3(r * 1.6f, r * 1.1f, -r * 2.0f), "3/4 front, above"},
					{new Vec3(r * 2.8f, 0f, 0f), "side, level"},
					{new Vec3(-r * 1.4f, -r * 1.3f, r * 1.8f), "3/4 rear, below"},
					{new Vec3(0f, -r * 3.0f, 0.01f), "straight below"},
					{null, "in-game chase view + HUD"},
			};
			BufferedImage sheet = new BufferedImage(VW * 3, VH * 2, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = sheet.createGraphics();
			for (int v = 0; v < views.length; v++) {
				PilotInput in = new PilotInput();
				in.screenW = VW;
				in.screenH = VH;
				in.mouseX = VW * 0.62f;
				in.mouseY = VH * 0.58f;
				boolean chase = views[v][0] == null;
				if (chase) {
					sim.camera.snapTo(s);
					sim.camera.update(s, t, 0.016f, false, 0f, 0f, VW / (float) VH);
					sim.steeringActive = true;
				} else {
					lookAt(sim.camera, (Vec3) views[v][0], new Vec3(), t, VW / (float) VH);
				}
				renderer.renderScene(sim, VW, VH, out[0]);
				if (chase) {
					HudRenderer.Info info = new HudRenderer.Info();
					info.alerts.add("STARSECTOR 3D  preview  " + id);
					info.perf.add("FPS --");
					renderer.renderHud(sim, in, info, VW, VH);
				}
				BufferedImage img = read(VW, VH);
				Graphics2D ig = img.createGraphics();
				ig.setColor(java.awt.Color.WHITE);
				ig.drawString(id + " - " + views[v][1], 8, VH - 10);
				ig.dispose();
				g.drawImage(img, (v % 3) * VW, (v / 3) * VH, null);
			}
			g.dispose();
			File f = new File(dir, id + ".png");
			ImageIO.write(sheet, "png", f);
			System.out.println("wrote " + f);
		}

		profileComparison(game, hulls, t, renderer, dir);
		writeMissionIcon(game, hulls, t, renderer, build);
		flightStrip(game, hulls, t, renderer, dir);
		scaleCheck(game, hulls, t, renderer, dir);
		combatScene(game, hulls, t, renderer, dir);
		missileScene(game, hulls, t, renderer, dir);
		battleScene(game, hulls, t, renderer, dir);
		empScene(game, hulls, t, renderer, dir);
		fighterScene(game, hulls, t, renderer, dir);
		stationScene(game, hulls, t, renderer, dir);
		benchmark(game, hulls, t, renderer, out[0], dir);
		if (pbuffer) System.out.println("done (pbuffer)");
		else Display.destroy();
	}

	private static Ship3D makeShip(File game, GameData.Hull h, Tuning t, String id) throws Exception {
		GameData.readShipFile(game, h);
		SpriteImage img = new SpriteImage(ImageIO.read(new File(game, h.spritePath)));
		Ship3D s = new Ship3D(id);
		s.name = id;
		s.hullName = id;
		s.params = new FlightParams(h.speed, h.accel, h.decel, h.turn, h.turnAccel);
		s.baseRadius = (float) Math.hypot(img.w, img.h) * 0.5f;
		s.radius = s.baseRadius;
		s.sizeClass = h.sizeClass;
		s.modelSource = new ShipModel.Source(h.spritePath, img, h.centerX, img.h - h.centerYFromBottom);
		s.engines.addAll(h.engines);
		return s;
	}

	private static void lookAt(ChaseCamera cam, Vec3 eye, Vec3 target, Tuning t, float aspect) {
		cam.pos.set(eye);
		Vec3 fwd = new Vec3(target).sub(eye);
		Vec3 up = Math.abs(fwd.y) > 0.95f * fwd.length() ? new Vec3(0, 0, -1) : new Vec3(0, 1, 0);
		cam.rot.setLookRotation(fwd, up);
		cam.fovYRad = (float) Math.toRadians(t.camFovDeg);
		cam.proj.perspective(cam.fovYRad, aspect, 1f, 100000f);
		cam.view.view(cam.rot, cam.pos);
		cam.viewProj.mul(cam.proj, cam.view);
	}

	/**
	 * Scripted flight through Sim.update with the real input path: full throttle, reticle held up and
	 * to the right. Frames should show the ship banking/pitching toward the reticle, the camera lagging,
	 * the reference ships sliding left/down, and the velocity marker trailing the nose.
	 */
	private static void flightStrip(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 640, h = 400;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		Ship3D p = makeShip(game, hulls.get("wolf"), t, "wolf");
		p.name = "Testbed";
		sim.addShip(p);
		sim.setPlayer(p);
		String[] refs = {"onslaught", "eagle", "hammerhead"};
		float[][] pos = {{0f, -250f, -2600f}, {1300f, 350f, -1600f}, {-900f, -150f, -1100f}};
		for (int i = 0; i < refs.length; i++) {
			Ship3D r = makeShip(game, hulls.get(refs[i]), t, refs[i]);
			r.name = "Reference " + refs[i];
			r.owner = 1;
			r.pos.set(pos[i][0], pos[i][1], pos[i][2]);
			r.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(70 * i + 90));
			sim.addShip(r);
		}
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f + 0.25f * h; // right of centre
		in.mouseY = h * 0.5f + 0.15f * h; // above centre
		p.throttle = 1f;
		p.vel.set(0, 0, -p.params.derive(t).fwdSpeed);
		HudRenderer.Info info = new HudRenderer.Info();
		BufferedImage sheet = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		float[] captureAt = {0.1f, 0.6f, 1.2f, 2.0f};
		float time = 0f;
		int shot = 0;
		while (shot < captureAt.length) {
			sim.update(in, 1f / 60f, 1f / 60f);
			time += 1f / 60f;
			if (time >= captureAt[shot]) {
				renderer.renderScene(sim, w, h, target[0]);
				info.alerts.clear();
				Vec3 f = p.rot.forward(new Vec3());
				info.alerts.add(String.format("t=%.1fs  heading %.0f  pitch %.0f  speed %.0f", time,
						Math.toDegrees(Math.atan2(f.x, -f.z)), Math.toDegrees(Math.asin(f.y)), p.speed()));
				renderer.renderHud(sim, in, info, w, h);
				g.drawImage(read(w, h), (shot % 2) * w, (shot / 2) * h, null);
				shot++;
			}
		}
		g.dispose();
		ImageIO.write(sheet, "png", new File(dir, "flight_strip.png"));
		System.out.println("wrote flight_strip.png");
	}

	/**
	 * Scale sanity: (1) the sandbox start view — reference lineup at one distance in front of the Wolf;
	 * (2) chase-camera framing when flying a Wolf, a Hammerhead and an Onslaught.
	 */
	private static void scaleCheck(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 960, h = 600;
		int[] target = createCaptureTarget(w, h);
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		HudRenderer.Info info = new HudRenderer.Info();

		Sim sim = new Sim(t);
		Ship3D wolf = makeShip(game, hulls.get("wolf"), t, "wolf");
		wolf.name = "Testbed";
		sim.addShip(wolf);
		sim.setPlayer(wolf);
		wolf.pos.set(0f, 0f, 200f); // start a little further back so the whole lineup is in view
		java.util.List<Ship3D> refs = new ArrayList<>();
		for (String id : new String[] {"wolf", "lasher", "hammerhead", "sunder", "eagle", "dominator", "onslaught"}) {
			Ship3D r = makeShip(game, hulls.get(id), t, id);
			r.name = id;
			r.owner = 1;
			sim.addShip(r);
			refs.add(r);
		}
		float gap = 150f, width = -gap;
		for (Ship3D r : refs) width += 2f * Math.max(r.radius, 30f) + gap;
		s3d.core.ReferenceLineup lineup = new s3d.core.ReferenceLineup(-width * 0.5f, t.sandboxLineupDepth, gap);
		for (Ship3D r : refs) lineup.place(r);
		for (int i = 0; i < 30; i++) sim.update(in, 1f / 60f, 1f / 60f);
		renderer.renderScene(sim, w, h, target[0]);
		info.alerts.add("sandbox start: reference lineup " + Math.round(t.sandboxLineupDepth) + " units ahead");
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "scale_lineup.png"));

		BufferedImage sheet = new BufferedImage(w * 3 / 2 * 3 / 2, h / 2 * 3 / 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		String[] own = {"wolf", "hammerhead", "onslaught"};
		for (int k = 0; k < own.length; k++) {
			Sim one = new Sim(t);
			Ship3D s = makeShip(game, hulls.get(own[k]), t, own[k]);
			s.name = own[k];
			s.thrustVisual = 0.6f;
			one.addShip(s);
			one.setPlayer(s);
			for (int i = 0; i < 30; i++) one.update(in, 1f / 60f, 1f / 60f);
			one.steeringActive = false;
			renderer.renderScene(one, w, h, target[0]);
			info.alerts.clear();
			info.alerts.add("flying: " + own[k]);
			renderer.renderHud(one, in, info, w, h);
			g.drawImage(read(w, h), k * w / 2, 0, w / 2, h / 2, null);
		}
		g.dispose();
		ImageIO.write(sheet.getSubimage(0, 0, w * 3 / 2, h / 2), "png", new File(dir, "scale_own_ship.png"));
		System.out.println("wrote scale_lineup.png, scale_own_ship.png");
	}

	/** Adds mounts from the hull's slots with the variant's weapons (generic stats; the game supplies real ones). */
	private static void arm(File game, Ship3D s, String hullId, String variantId) throws Exception {
		Map<String, String> fit = GameData.variantWeapons(game, variantId);
		for (GameData.Slot slot : GameData.slots(game, hullId)) {
			String wid = fit.get(slot.id);
			if (wid == null) continue;
			org.json.JSONObject wj = GameData.weapon(game, wid);
			s3d.core.WeaponSpec3D ws = new s3d.core.WeaponSpec3D();
			ws.id = wid;
			ws.name = wid;
			ws.type = wj.optString("type", "?");
			ws.supported = true;
			ws.damage = 100f;
			ws.range = 700f;
			ws.projectileSpeed = 900f;
			ws.cooldown = 0.35f;
			ws.turnRate = 120f;
			ws.projLength = 40f;
			ws.projWidth = 8f;
			ws.coreColor = new float[] {1f, 1f, 1f};
			ws.fringeColor = "ENERGY".equals(ws.type) ? new float[] {0.4f, 0.7f, 1f} : new float[] {1f, 0.75f, 0.35f};
			if (wid.startsWith("ion")) { // ion weapons: EMP that arcs through loaded shields
				ws.emp = 300f;
				ws.empArcs = true;
				ws.damage = 25f;
			}
			if ("beam".equals(wj.optString("specClass"))) {
				ws.kind = s3d.core.WeaponSpec3D.Kind.BEAM;
				ws.dps = 150f;
				ws.chargeUp = 0.1f;
				ws.chargeDown = 0.2f;
				ws.burstBeam = true;
				ws.burstDuration = 0.6f;
				ws.cooldown = 0.4f;
				ws.beamWidth = 10f;
				ws.range = 400f;
				ws.pd = wid.startsWith("pd");
				ws.fringeColor = new float[] {1f, 0.35f, 0.3f};
			} else if ("MISSILE".equals(ws.type)) {
				ws.kind = s3d.core.WeaponSpec3D.Kind.MISSILE;
				ws.damage = 500f;
				ws.range = 2500f;
				ws.launchSpeed = 150f;
				ws.missileMaxSpeed = 450f;
				ws.missileAccel = 500f;
				ws.missileTurnRate = 120f;
				ws.guided = true;
				ws.flightTime = 8f;
				ws.missileHp = 120f;
				ws.missileRadius = 10f;
				ws.cooldown = 0.8f;
				ws.maxAmmo = 4;
				ws.missileGlow = new float[] {1f, 0.55f, 0.25f};
				String projId = wj.optString("projectileSpecId", null);
				if (projId != null) {
					org.json.JSONObject pj = GameData.lenientJson(new File(game, "data/weapons/proj/" + projId + ".proj"));
					String ms = pj.optString("sprite", null);
					if (ms != null) {
						SpriteImage mi = new SpriteImage(ImageIO.read(new File(game, ms)));
						ws.missileModelSource = new ShipModel.Source(ms, mi, mi.w * 0.5f, mi.h * 0.5f, true);
					}
				}
			}
			boolean hp = "HARDPOINT".equals(slot.mount);
			float x = -slot.y, z = -slot.x;
			float y = s.volume != null ? s.volume.topAt(x, z) + 1f : 0f;
			double a = Math.toRadians(slot.angle);
			s3d.core.WeaponMount m = new s3d.core.WeaponMount(slot.id, ws, new Vec3(x, y, z),
					new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)), slot.arc, hp);
			m.ammo = ws.maxAmmo;
			String sprite = wj.optString(hp ? "hardpointSprite" : "turretSprite", null);
			if (sprite != null) {
				SpriteImage img = new SpriteImage(ImageIO.read(new File(game, sprite)));
				m.modelSource = new ShipModel.Source(sprite, img, img.w * 0.5f, hp ? img.h * 0.75f : img.h * 0.5f, true);
			}
			s.weapons.add(m);
		}
		for (Object[] g : GameData.variantGroups(game, variantId)) {
			s3d.core.WeaponGroup3D g3 = new s3d.core.WeaponGroup3D();
			g3.autofire = (Boolean) g[0];
			g3.alternating = (Boolean) g[1];
			@SuppressWarnings("unchecked")
			java.util.List<String> slotIds = (java.util.List<String>) g[2];
			for (s3d.core.WeaponMount m : s.weapons) if (slotIds.contains(m.slotId)) g3.mounts.add(m);
			if (!g3.mounts.isEmpty()) s.groups.add(g3);
		}
	}

	/** Player Wolf firing at a targeted Eagle with lead pips, impacts, radar and weapon panel. */
	private static void combatScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1280, h = 800;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		Ship3D wolf = makeShip(game, hulls.get("wolf"), t, "wolf");
		wolf.name = "Testbed";
		sim.addShip(wolf);
		wolf.volume = ShipModel.geometry((ShipModel.Source) wolf.modelSource, t).volume;
		arm(game, wolf, "wolf", "wolf_Assault");
		sim.setPlayer(wolf);

		Ship3D eagle = makeShip(game, hulls.get("eagle"), t, "eagle");
		eagle.name = "Reference Cruiser";
		eagle.owner = 1;
		sim.addShip(eagle);
		eagle.volume = ShipModel.geometry((ShipModel.Source) eagle.modelSource, t).volume;
		eagle.armor = new s3d.core.ArmorShell(1000f);
		eagle.hullMax = eagle.hull = 5000f;
		arm(game, eagle, "eagle", "eagle_Assault");
		eagle.pos.set(120f, -40f, -650f);
		eagle.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(-70));

		Ship3D drone = makeShip(game, hulls.get("hound"), t, "hound");
		drone.name = "Target Drone";
		drone.owner = 1;
		sim.addShip(drone);
		drone.volume = ShipModel.geometry((ShipModel.Source) drone.modelSource, t).volume;
		drone.mover = new s3d.core.ScriptedMover(new Vec3(0f, 250f, -2300f), 800f, 130f, 25f);

		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		// Like a player: no R press (auto target), keep the reticle on the lead circle and hold fire.
		sim.update(in, 1f / 60f, 1f / 60f);
		in.fire = true;
		float[] clip = new float[4];
		for (int i = 0; i < 110; i++) {
			if (sim.hasLead) {
				sim.camera.viewProj.transform(sim.leadPoint.x, sim.leadPoint.y, sim.leadPoint.z, clip);
				if (clip[3] > 0f) {
					in.mouseX = (clip[0] / clip[3] * 0.5f + 0.5f) * w;
					in.mouseY = (clip[1] / clip[3] * 0.5f + 0.5f) * h;
				}
			}
			sim.update(in, 1f / 60f, 1f / 60f);
			in.clearPressed();
		}
		renderer.renderScene(sim, w, h, target[0]);
		HudRenderer.Info info = new HudRenderer.Info();
		info.alerts.add("STARSECTOR 3D  combat preview");
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "combat.png"));

		// Close look at the Wolf's turrets on their slots.
		lookAt(sim.camera, new Vec3(wolf.radius * 0.9f, wolf.radius * 0.9f, -wolf.radius * 0.6f).add(wolf.pos), new Vec3(wolf.pos), t, w / (float) h);
		renderer.renderScene(sim, w, h, target[0]);
		ImageIO.write(read(w, h), "png", new File(dir, "turrets.png"));
		System.out.println("wrote combat.png, turrets.png (" + sim.combat.projectiles.size() + " shots in flight, target hull "
				+ Math.round(eagle.hull) + ", armor " + Math.round(eagle.armor.averageFraction() * 100) + "%)");
	}

	/**
	 * Small AI fleet battle with shields and flux: you (a Wolf) fly into it holding the reticle on
	 * the lead; the frame shows the minimap, flux bars and raised shields. Also a close-up of the
	 * shield that was hit most recently.
	 */
	private static void battleScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1280, h = 800;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		sim.arenaHalfX = 5000f;
		sim.arenaHalfZ = 4500f;
		sim.combat.mergeNumbers = true;
		String[][] fleets = {{"wolf:wolf_Assault", "hammerhead:hammerhead_Balanced", "eagle:eagle_Assault", "enforcer:enforcer_Assault"},
				{"lasher:lasher_Standard", "hammerhead:hammerhead_Elite", "dominator:dominator_Assault", "wolf:wolf_CS"}};
		Ship3D player = null;
		for (int side = 0; side < 2; side++) {
			for (int i = 0; i < fleets[side].length; i++) {
				String[] hv = fleets[side][i].split(":");
				GameData.Hull hull = hulls.get(hv[0]);
				Ship3D s = makeShip(game, hull, t, hv[0] + side);
				s.owner = side;
				sim.addShip(s);
				s.volume = ShipModel.geometry((ShipModel.Source) s.modelSource, t).volume;
				arm(game, s, hv[0], hv[1]);
				for (s3d.core.WeaponMount m : s.weapons) m.spec.fluxPerShot = m.spec.damage * 0.8f;
				s.flux = CombatBench.flux(hull, t);
				float big = "cruiser".equals(s.sizeClass) ? 3f : "destroyer".equals(s.sizeClass) ? 2f : 1f;
				s.armor = new s3d.core.ArmorShell(300f * big);
				s.hullMax = s.hull = 2000f * big;
				s.pos.set(-900f + 600f * i, (i % 2) * 150f, side == 0 ? 1800f : -1800f);
				if (i > 0 && side == 0) s.pos.x += 2500f; // your wingmen start off to the side, so the fleet marker shows
				if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				if (side == 0 && i == 0) {
					player = s;
					s.name = "Testbed";
					s.hullMax = s.hull = 1e6f;
				} else {
					s.ai = new s3d.core.ShipAI3D(i + side * 5);
					s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
				}
			}
		}
		sim.setPlayer(player);
		player.flux.shieldOn = true;
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		float[] clip = new float[4];
		for (float time = 0f; time < 26f; time += 1f / 60f) {
			in.throttleUp = time < 1.5f;
			in.fire = sim.hasLead;
			if (sim.hasLead) {
				sim.camera.viewProj.transform(sim.leadPoint.x, sim.leadPoint.y, sim.leadPoint.z, clip);
				if (clip[3] > 0f) {
					in.mouseX += ((clip[0] / clip[3] * 0.5f + 0.5f) * w - in.mouseX) * 0.2f;
					in.mouseY += ((clip[1] / clip[3] * 0.5f + 0.5f) * h - in.mouseY) * 0.2f;
				}
			}
			sim.update(in, 1f / 60f, 1f / 60f);
			in.clearPressed();
		}
		renderer.renderScene(sim, w, h, target[0]);
		HudRenderer.Info info = new HudRenderer.Info();
		info.alerts.add("STARSECTOR 3D  battle preview");
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "battle.png"));

		Ship3D best = null;
		float bestAge = Float.MAX_VALUE;
		for (Ship3D s : sim.ships) {
			if (s.destroyed || s.flux == null || !s.flux.shieldUp()) continue;
			for (float a : s.flux.hitAge) if (a < bestAge) { bestAge = a; best = s; }
		}
		StringBuilder st = new StringBuilder();
		for (Ship3D s : sim.ships) {
			st.append(String.format(" %s%s(flux %.0f%%%s)", s.id, s.destroyed ? "x" : "", s.flux.level() * 100f,
					s.flux.shieldUp() ? " shield" : s.flux.overloaded() ? " OVERLOAD" : s.flux.venting ? " vent" : ""));
		}
		if (best != null) {
			Vec3 f = best.rot.rotate(best.flux.facing, new Vec3());
			Vec3 eye = new Vec3(best.pos).addScaled(f, best.flux.radius * best.scale * 2.6f).add(new Vec3(0f, best.radius * 0.8f, 0f));
			lookAt(sim.camera, eye, new Vec3(best.pos), t, w / (float) h);
			renderer.renderScene(sim, w, h, target[0]);
			ImageIO.write(read(w, h), "png", new File(dir, "shield.png"));
		}
		System.out.println("wrote battle.png" + (best != null ? ", shield.png (" + best.id + ")" : "") + ":" + st);
	}

	/** A Heron's wing (bombers + fighters) striking an Enforcer, seen from your Wolf flying escort. */
	private static void fighterScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1280, h = 800;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		sim.arenaHalfX = 5000f;
		sim.arenaHalfZ = 4500f;
		sim.combat.mergeNumbers = true;
		Ship3D wolf = makeShip(game, hulls.get("wolf"), t, "wolf");
		wolf.name = "Testbed";
		sim.addShip(wolf);
		wolf.volume = ShipModel.geometry((ShipModel.Source) wolf.modelSource, t).volume;
		arm(game, wolf, "wolf", "wolf_Assault");
		wolf.flux = CombatBench.flux(hulls.get("wolf"), t);
		wolf.pos.set(250f, 60f, -300f);
		sim.setPlayer(wolf);
		Ship3D heron = makeShip(game, hulls.get("heron"), t, "heron");
		heron.name = "Heron";
		sim.addShip(heron);
		heron.volume = ShipModel.geometry((ShipModel.Source) heron.modelSource, t).volume;
		heron.flux = CombatBench.flux(hulls.get("heron"), t);
		heron.landingLocal.set(0f, heron.volume.topAt(0f, 0f) + 2f, 0f);
		heron.hullMax = heron.hull = 6000f;
		heron.armor = new s3d.core.ArmorShell(800f);
		Ship3D enf = makeShip(game, hulls.get("enforcer"), t, "enforcer");
		enf.owner = 1;
		sim.addShip(enf);
		enf.volume = ShipModel.geometry((ShipModel.Source) enf.modelSource, t).volume;
		arm(game, enf, "enforcer", "enforcer_Assault");
		enf.flux = CombatBench.flux(hulls.get("enforcer"), t);
		enf.hullMax = enf.hull = 1e6f;
		enf.armor = new s3d.core.ArmorShell(900f);
		enf.pos.set(0f, 0f, -2600f);
		enf.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
		for (int i = 0; i < 6; i++) {
			String hull = i < 3 ? "dagger" : "broadsword";
			Ship3D f = makeShip(game, hulls.get(hull), t, hull + i);
			f.owner = 0;
			f.fighter = true;
			f.carrier = heron;
			f.wingRole = i < 3 ? "BOMBER" : "FIGHTER";
			f.attackRunRange = 2000f;
			sim.addShip(f);
			f.volume = ShipModel.geometry((ShipModel.Source) f.modelSource, t).volume.thickened(f.baseRadius * 0.35f);
			arm(game, f, hull, i < 3 ? "dagger_Bomber" : "broadsword_Fighter");
			f.flux = CombatBench.flux(hulls.get(hull), t);
			f.armor = new s3d.core.ArmorShell(50f);
			f.hullMax = f.hull = 400f;
			f.pos.set(heron.pos).add(new Vec3(i * 12f - 30f, 20f, -40f));
			f.ai = new s3d.core.ShipAI3D(i);
		}
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		for (float time = 0f; time < 12f; time += 1f / 60f) {
			sim.update(in, 1f / 60f, 1f / 60f);
			in.clearPressed();
			for (java.util.Iterator<Ship3D> it = sim.ships.iterator(); it.hasNext(); ) if (it.next().landed) it.remove();
		}
		renderer.renderScene(sim, w, h, target[0]);
		HudRenderer.Info info = new HudRenderer.Info();
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "fighters.png"));
		Ship3D near = null;
		for (Ship3D s : sim.ships) if (s.fighter && !s.destroyed && (near == null || s.pos.distance(enf.pos) < near.pos.distance(enf.pos))) near = s;
		if (near != null) {
			Vec3 back = near.rot.forward(new Vec3()).scale(-90f).add(new Vec3(0f, 35f, 0f));
			lookAt(sim.camera, new Vec3(near.pos).add(back), new Vec3(near.pos).add(near.rot.forward(new Vec3()).scale(200f)), t, w / (float) h);
			renderer.renderScene(sim, w, h, target[0]);
			ImageIO.write(read(w, h), "png", new File(dir, "fighters_close.png"));
		}
		// Spectating the Heron, which is after the Enforcer; the Enforcer has taken armor damage on its bow.
		heron.ai = new s3d.core.ShipAI3D(9);
		for (int az = 0; az < 3; az++) enf.armor.strip(s3d.core.ArmorShell.sector(0f, -60f, 1) + az - 1, 700f);
		enf.armor.strip(s3d.core.ArmorShell.sector(40f, 10f, 0), 400f);
		enf.weapons.get(0).disabledFor = 5f;
		// The damaged Enforcer as your target: the hologram on the right.
		sim.target = enf;
		sim.update(in, 1f / 60f, 1f / 60f);
		renderer.renderScene(sim, w, h, target[0]);
		renderer.renderHud(sim, in, new HudRenderer.Info(), w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "target_holo.png"));
		PilotInput spec = new PilotInput();
		spec.screenW = w;
		spec.screenH = h;
		spec.mouseX = w * 0.5f;
		spec.mouseY = h * 0.5f;
		spec.toggleSpectate = true;
		sim.update(spec, 1f / 60f, 1f / 60f);
		spec.clearPressed();
		while (sim.viewShip != heron) {
			spec.cycleView = 1;
			sim.update(spec, 1f / 60f, 1f / 60f);
			spec.clearPressed();
		}
		heron.ai.target = enf;
		for (int i = 0; i < 90; i++) {
			sim.update(spec, 1f / 60f, 1f / 60f);
			heron.ai.target = enf;
		}
		renderer.renderScene(sim, w, h, target[0]);
		HudRenderer.Info sinfo = new HudRenderer.Info();
		for (String a : t.keys.keySet()) sinfo.keyNames.put(a, t.keys.get(a));
		renderer.renderHud(sim, spec, sinfo, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "spectate.png"));
		int up = 0;
		for (Ship3D s : sim.ships) if (s.fighter && !s.destroyed) up++;
		System.out.println("wrote fighters.png (" + up + " fighters out, enforcer damage " + Math.round(enf.hullMax - enf.hull) + " hull)");
	}

	/** A station (core + modules) from its variant, with meshes, as the preview draws it. */
	private static List<Ship3D> stationParts(File game, Map<String, GameData.Hull> hulls, Tuning t, String variantId, int owner) throws Exception {
		List<Ship3D> parts = new ArrayList<>();
		String coreHull = GameData.variantHull(game, variantId);
		Ship3D core = makeShip(game, hulls.get(coreHull), t, coreHull);
		core.sizeClass = "station";
		core.owner = owner;
		core.anchored = true;
		core.invulnerable = true;
		core.spinRate = (float) Math.toRadians(t.stationSpinDeg);
		parts.add(core);
		Map<String, GameData.Slot> slots = new java.util.HashMap<>();
		for (GameData.Slot sl : GameData.slots(game, coreHull)) slots.put(sl.id, sl);
		int i = 0;
		for (Map.Entry<String, String> e : GameData.variantModules(game, variantId).entrySet()) {
			GameData.Slot sl = slots.get(e.getKey());
			String modHull = GameData.variantHull(game, e.getValue());
			if (sl == null || modHull == null || !hulls.containsKey(modHull)) continue;
			Ship3D m = makeShip(game, hulls.get(modHull), t, modHull + "_" + i++);
			m.owner = owner;
			CombatBench.attachFromData(m, core, sl, GameData.moduleAnchor(game, modHull));
			arm(game, m, modHull, e.getValue());
			m.flux = CombatBench.flux(hulls.get(modHull), t);
			m.armor = new s3d.core.ArmorShell(1000f);
			m.hullMax = m.hull = 8000f;
			parts.add(m);
		}
		return parts;
	}

	/**
	 * Stations: top-down sheets of three station layouts (compare with vanilla's), and an orbital
	 * station under attack from a 3/4 view.
	 */
	private static void stationScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		String[] variants = {"station1_Standard", "station2_midline_Standard", "station3_hightech_Standard"};
		BufferedImage sheet = new BufferedImage(VW * 3, VH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		for (int v = 0; v < variants.length; v++) {
			if (GameData.variantHull(game, variants[v]) == null) continue;
			Sim sim = new Sim(t);
			List<Ship3D> parts = stationParts(game, hulls, t, variants[v], 1);
			for (Ship3D p : parts) {
				sim.addShip(p);
				p.volume = ShipModel.geometry((ShipModel.Source) p.modelSource, t).volume;
			}
			float extent = 0f;
			for (Ship3D p : parts) extent = Math.max(extent, p.pos.length() + p.radius);
			lookAt(sim.camera, new Vec3(0f, extent * 2.3f, 0.01f), new Vec3(), t, VW / (float) VH);
			renderer.renderScene(sim, VW, VH, createCaptureTarget(VW, VH)[0]);
			BufferedImage img = read(VW, VH);
			Graphics2D ig = img.createGraphics();
			ig.setColor(java.awt.Color.WHITE);
			ig.drawString(variants[v] + " - top, " + (parts.size() - 1) + " modules", 8, VH - 10);
			ig.dispose();
			g.drawImage(img, v * VW, 0, null);
		}
		g.dispose();
		ImageIO.write(sheet, "png", new File(dir, "stations_top.png"));

		int w = 1280, h = 800;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		sim.arenaHalfX = 6000f;
		sim.arenaHalfZ = 6000f;
		sim.combat.mergeNumbers = true;
		List<Ship3D> parts = stationParts(game, hulls, t, "station1_Standard", 1);
		for (Ship3D p : parts) {
			sim.addShip(p);
			p.volume = ShipModel.geometry((ShipModel.Source) p.modelSource, t).volume;
			p.ai = new s3d.core.ShipAI3D(parts.indexOf(p) + 40);
		}
		String[][] attackers = {{"dominator", "dominator_Assault"}, {"eagle", "eagle_Assault"}, {"hammerhead", "hammerhead_Balanced"}};
		Ship3D lead = null;
		for (int i = 0; i < attackers.length; i++) {
			if (!hulls.containsKey(attackers[i][0])) continue;
			Ship3D a = makeShip(game, hulls.get(attackers[i][0]), t, attackers[i][0]);
			a.owner = 0;
			sim.addShip(a);
			a.volume = ShipModel.geometry((ShipModel.Source) a.modelSource, t).volume;
			arm(game, a, attackers[i][0], attackers[i][1]);
			a.flux = CombatBench.flux(hulls.get(attackers[i][0]), t);
			a.armor = new s3d.core.ArmorShell(800f);
			a.hullMax = a.hull = 1e6f;
			a.pos.set(-1200f + 1200f * i, 200f * (i % 2), 3200f);
			a.ai = new s3d.core.ShipAI3D(i + 1);
			a.ai.homeDir.set(0f, 0f, 1f);
			if (lead == null) lead = a;
		}
		sim.setPlayer(lead);
		lead.ai = new s3d.core.ShipAI3D(77); // flown by the AI too, for the picture
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		for (float time = 0f; time < 25f; time += 1f / 60f) {
			sim.update(in, 1f / 60f, 1f / 60f);
			in.clearPressed();
		}
		float extent = 0f;
		for (Ship3D p : parts) extent = Math.max(extent, p.pos.length() + p.radius);
		lookAt(sim.camera, new Vec3(extent * 1.6f, extent * 1.0f, extent * 2.2f), new Vec3(0f, 0f, extent * 0.4f), t, w / (float) h);
		renderer.renderScene(sim, w, h, target[0]);
		ImageIO.write(read(w, h), "png", new File(dir, "station_assault.png"));
		// Your chase view with a station section as the target (hologram panel, minimap).
		lead.ai = null;
		Ship3D section = null;
		for (Ship3D p : parts) if (p.parent != null && !p.destroyed && p.weapons.size() > 0 && (section == null || p.pos.distance(lead.pos) < section.pos.distance(lead.pos))) section = p;
		sim.target = section;
		sim.camera.snapTo(lead);
		sim.update(in, 1f / 60f, 1f / 60f);
		renderer.renderScene(sim, w, h, target[0]);
		HudRenderer.Info hinfo = new HudRenderer.Info();
		for (String a : t.keys.keySet()) hinfo.keyNames.put(a, t.keys.get(a));
		renderer.renderHud(sim, in, hinfo, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "station_target.png"));
		int dead = 0;
		for (Ship3D p : parts) if (p.destroyed) dead++;
		System.out.println("wrote stations_top.png, station_assault.png, station_target.png (" + dead + " of " + (parts.size() - 1) + " modules destroyed after 25 s)");
	}

	/** Ion fire on an Enforcer whose shield is loaded with hard flux: frame taken as an arc goes through. */
	private static void empScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1280, h = 800;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		Ship3D wolf = makeShip(game, hulls.get("wolf"), t, "wolf");
		sim.addShip(wolf);
		wolf.volume = ShipModel.geometry((ShipModel.Source) wolf.modelSource, t).volume;
		arm(game, wolf, "wolf", "wolf_Assault");
		sim.setPlayer(wolf);
		Ship3D enf = makeShip(game, hulls.get("enforcer"), t, "enforcer");
		enf.owner = 1;
		sim.addShip(enf);
		enf.volume = ShipModel.geometry((ShipModel.Source) enf.modelSource, t).volume;
		arm(game, enf, "enforcer", "enforcer_Assault");
		enf.flux = CombatBench.flux(hulls.get("enforcer"), t);
		enf.flux.shieldOn = true;
		enf.flux.unfold = 1f;
		enf.flux.flux = enf.flux.hardFlux = enf.flux.maxFlux * 0.85f;
		enf.engines.addAll(hulls.get("enforcer").engines);
		enf.initComponents(150f);
		enf.armor = new s3d.core.ArmorShell(900f);
		enf.hullMax = enf.hull = 1e6f;
		enf.pos.set(0f, 0f, -650f);
		enf.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(160));
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		s3d.core.WeaponMount ion = null;
		for (s3d.core.WeaponMount m : wolf.weapons) if (m.spec.empArcs) ion = m;
		boolean caught = false;
		for (int i = 0; i < 1200 && !caught && ion != null; i++) {
			enf.flux.flux = enf.flux.hardFlux = Math.max(enf.flux.hardFlux, enf.flux.maxFlux * 0.85f);
			sim.update(in, 1f / 60f, 1f / 60f);
			sim.combat.stepMount(wolf, ion, enf.pos, true, 1f / 60f, t);
			for (s3d.core.Effect e : sim.combat.effects) if (e.kind == s3d.core.Effect.Kind.ARC && e.age01() < 0.3f) caught = true;
		}
		lookAt(sim.camera, new Vec3(enf.pos).add(new Vec3(-260f, 120f, 120f)), new Vec3(enf.pos), t, w / (float) h);
		renderer.renderScene(sim, w, h, target[0]);
		ImageIO.write(read(w, h), "png", new File(dir, "emp.png"));
		enf.phaseLevel = 1f; // what a phased ship looks like
		enf.phased = true;
		renderer.renderScene(sim, w, h, target[0]);
		ImageIO.write(read(w, h), "png", new File(dir, "phase.png"));
		int off = 0;
		for (s3d.core.WeaponMount m : enf.weapons) if (m.disabledFor > 0f) off++;
		System.out.println("wrote emp.png (arc caught=" + caught + ", enforcer weapons disabled " + off + "/" + enf.weapons.size()
				+ ", engines " + Math.round(enf.engineThrust() * 100) + "%)");
	}

	/** Harpoons out toward the cruiser while its torpedoes come in and the Wolf's PD lasers engage them. */
	private static void missileScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1280, h = 800;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		Ship3D wolf = makeShip(game, hulls.get("wolf"), t, "wolf");
		wolf.name = "Testbed";
		wolf.hullMax = wolf.hull = 1500f;
		wolf.armor = new s3d.core.ArmorShell(150f);
		sim.addShip(wolf);
		wolf.volume = ShipModel.geometry((ShipModel.Source) wolf.modelSource, t).volume;
		arm(game, wolf, "wolf", "wolf_Assault");
		sim.setPlayer(wolf);
		wolf.selectedGroup = 1; // harpoons
		Ship3D eagle = makeShip(game, hulls.get("eagle"), t, "eagle");
		eagle.name = "Reference Cruiser";
		eagle.owner = 1;
		eagle.hullMax = eagle.hull = 5000f;
		eagle.armor = new s3d.core.ArmorShell(1000f);
		sim.addShip(eagle);
		eagle.volume = ShipModel.geometry((ShipModel.Source) eagle.modelSource, t).volume;
		arm(game, eagle, "eagle", "eagle_Assault");
		eagle.pos.set(250f, 80f, -1300f);
		eagle.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(160));
		sim.incomingFire = true;
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		in.fire = true;
		for (int i = 0; i < 150; i++) {
			sim.update(in, 1f / 60f, 1f / 60f);
			in.clearPressed();
		}
		renderer.renderScene(sim, w, h, target[0]);
		HudRenderer.Info info = new HudRenderer.Info();
		info.alerts.add("STARSECTOR 3D  missiles + point defence preview");
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "missiles.png"));
		System.out.println("wrote missiles.png (" + sim.combat.missiles.size() + " missiles in flight, wolf hull "
				+ Math.round(wolf.hull) + ", eagle hull " + Math.round(eagle.hull) + ")");
	}

	/** Same hulls, same views: the old "round" profile (left two) vs the new "hard" profile (right two). */
	private static void profileComparison(File game, Map<String, GameData.Hull> hulls, Tuning base, Renderer3D renderer, File dir) throws Exception {
		String[] ids = {"wolf", "hammerhead", "eagle", "onslaught"};
		int vw = 560, vh = 360;
		int[] target = createCaptureTarget(vw, vh);
		BufferedImage sheet = new BufferedImage(vw * 4, vh * ids.length, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		for (int row = 0; row < ids.length; row++) {
			for (int prof = 0; prof < 2; prof++) {
				Tuning t = GameData.modTuning(dir.getParentFile());
				t.meshProfile = prof == 0 ? "round" : "hard";
				if (prof == 0) {
					t.bumpStrength = 0f;
					t.meshDetailAmp = 0.06f;
				}
				Ship3D s = makeShip(game, hulls.get(ids[row]), t, ids[row]);
				Sim sim = new Sim(t);
				sim.addShip(s);
				sim.setPlayer(s);
				s.thrustVisual = 0.5f;
				float r = s.radius;
				Vec3[] eyes = {new Vec3(r * 1.05f, r * 0.7f, -r * 0.9f), new Vec3(-r * 1.15f, r * 0.45f, r * 0.85f)};
				for (int v = 0; v < 2; v++) {
					lookAt(sim.camera, eyes[v], new Vec3(), t, vw / (float) vh);
					renderer.renderScene(sim, vw, vh, target[0]);
					BufferedImage img = read(vw, vh);
					Graphics2D ig = img.createGraphics();
					ig.setColor(java.awt.Color.WHITE);
					ig.drawString(ids[row] + (prof == 0 ? "  OLD (round)" : "  NEW (hard surface)"), 8, vh - 10);
					ig.dispose();
					g.drawImage(img, (prof * 2 + v) * vw, row * vh, null);
				}
			}
		}
		g.dispose();
		ImageIO.write(sheet, "png", new File(dir, "profile_compare.png"));
		System.out.println("wrote profile_compare.png");
	}

	/** 3/4 view of each hull with different plate styles, to pick the most mechanical-looking default. */
	private static void plateVariants(File game, Map<String, GameData.Hull> hulls, Renderer3D renderer, File dir) throws Exception {
		String[] ids = {"wolf", "hammerhead", "eagle", "onslaught"};
		String[] labels = {"D: 3 tiers, sharp bevel (current detail)", "D1: no brightness displacement", "D2: D1 + half-strength detail map", "D3: D1 + no detail map"};
		int vw = 560, vh = 380;
		int[] target = createCaptureTarget(vw, vh);
		BufferedImage sheet = new BufferedImage(vw * labels.length, vh * ids.length, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		for (int row = 0; row < ids.length; row++) {
			for (int v = 0; v < labels.length; v++) {
				Tuning t = GameData.modTuning(dir.getParentFile());
				t.meshProfile = "hard";
				t.meshTierLumInfluence = 0f;
				t.meshTiers = 3;
				t.meshTierInsetFrac = 0.32f;
				t.meshTierHeight = 0.15f;
				t.meshBevelFrac = 0.14f;
				if (v >= 1) t.meshDetailAmp = 0f;
				if (v == 2) t.bumpStrength = 0.4f;
				if (v == 3) t.bumpStrength = 0f;
				Ship3D s = makeShip(game, hulls.get(ids[row]), t, ids[row]);
				Sim sim = new Sim(t);
				sim.addShip(s);
				sim.setPlayer(s);
				s.thrustVisual = 0.5f;
				float r = s.radius;
				lookAt(sim.camera, new Vec3(r * 0.85f, r * 0.62f, -r * 0.72f), new Vec3(), t, vw / (float) vh);
				renderer.renderScene(sim, vw, vh, target[0]);
				BufferedImage img = read(vw, vh);
				Graphics2D ig = img.createGraphics();
				ig.setColor(java.awt.Color.WHITE);
				ig.drawString(ids[row] + "  " + labels[v], 8, vh - 10);
				ig.dispose();
				g.drawImage(img, v * vw, row * vh, null);
			}
		}
		g.dispose();
		ImageIO.write(sheet, "png", new File(dir, "plate_variants.png"));
		System.out.println("wrote plate_variants.png");
	}

	/** 256x256 icon for the Missions screen: a Wolf at 3/4 view, engines lit. */
	private static void writeMissionIcon(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File build) throws Exception {
		int size = 256;
		int[] target = createCaptureTarget(size, size);
		Ship3D s = makeShip(game, hulls.get("wolf"), t, "wolf");
		s.thrustVisual = 1f;
		Sim sim = new Sim(t);
		sim.addShip(s);
		sim.setPlayer(s);
		float r = s.radius;
		lookAt(sim.camera, new Vec3(r * 1.35f, r * 0.9f, -r * 0.9f), new Vec3(0f, 0f, r * 0.25f), t, 1f);
		renderer.renderScene(sim, size, size, target[0]);
		File icon = new File(build.getParentFile(), "data/missions/s3d_sandbox/icon.jpg");
		ImageIO.write(read(size, size), "jpg", icon);
		System.out.println("wrote " + icon);
	}

	/** 60 mixed hulls in a loose formation, chase camera; reports GPU-inclusive ms per frame. */
	private static void benchmark(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, int fbo, File dir) throws Exception {
		String[] mix = {"wolf", "lasher", "hammerhead", "eagle", "dominator", "onslaught"};
		Sim sim = new Sim(t);
		java.util.Random rnd = new java.util.Random(7);
		for (int i = 0; i < 60; i++) {
			GameData.Hull h = hulls.get(mix[i % mix.length]);
			Ship3D s = makeShip(game, h, t, mix[i % mix.length] + i);
			s.pos.set((rnd.nextFloat() - 0.5f) * 4000f, (rnd.nextFloat() - 0.5f) * 1200f, -rnd.nextFloat() * 4000f - 300f);
			s.rot.setAxisAngle(0, 1, 0, rnd.nextFloat() * 6.28f).mulLocal(new Quat().setAxisAngle(1, 0, 0, (rnd.nextFloat() - 0.5f) * 1.2f));
			s.thrustVisual = 0.8f;
			sim.addShip(s);
		}
		sim.setPlayer(sim.ships.get(0));
		sim.ships.get(0).pos.set(0, 0, 0);
		sim.ships.get(0).rot.identity();
		int w = 1280, h = 800;
		int[] big = createCaptureTarget(w, h);
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.55f;
		in.mouseY = h * 0.52f;
		HudRenderer.Info info = new HudRenderer.Info();
		for (int i = 0; i < 5; i++) frame(sim, renderer, in, info, w, h, big[0]); // warm-up (mesh builds, JIT)
		int frames = 120;
		long t0 = System.nanoTime();
		for (int i = 0; i < frames; i++) {
			sim.update(in, 1f / 60f, 1f / 60f);
			frame(sim, renderer, in, info, w, h, big[0]);
		}
		double ms = (System.nanoTime() - t0) / 1e6 / frames;
		System.out.printf("benchmark: 60 ships, %dk tris/frame, %.2f ms/frame incl. glFinish at %dx%d (MSAA %dx)%n",
				renderer.lastTriangles / 1000, ms, w, h, renderer.msaaSamples());
		ImageIO.write(read(w, h), "png", new File(dir, "benchmark.png"));
	}

	private static void frame(Sim sim, Renderer3D r, PilotInput in, HudRenderer.Info info, int w, int h, int fbo) {
		r.renderScene(sim, w, h, fbo);
		r.hud().render(sim, in, info, w, h);
		GL11.glFinish();
	}

	private static boolean createContext() throws Exception {
		try {
			if ((Pbuffer.getCapabilities() & Pbuffer.PBUFFER_SUPPORTED) != 0) {
				Pbuffer pb = new Pbuffer(16, 16, new PixelFormat(8, 24, 0), null);
				pb.makeCurrent();
				return true;
			}
		} catch (Throwable e) {
			System.out.println("pbuffer unavailable (" + e + "), using a small window");
		}
		Display.setDisplayMode(new DisplayMode(160, 100));
		Display.setTitle("S3D mesh preview");
		Display.create();
		return false;
	}

	private static int[] createCaptureTarget(int w, int h) {
		int tex = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		int fbo = glGenFramebuffersEXT();
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
		glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, tex, 0);
		int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
		if (status != GL_FRAMEBUFFER_COMPLETE_EXT) throw new IllegalStateException("capture FBO incomplete " + status);
		return new int[] {fbo, tex};
	}

	private static BufferedImage read(int w, int h) {
		ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
		GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = ((h - 1 - y) * w + x) * 4;
				img.setRGB(x, y, (px.get(i) & 255) << 16 | (px.get(i + 1) & 255) << 8 | (px.get(i + 2) & 255));
			}
		}
		return img;
	}
}
