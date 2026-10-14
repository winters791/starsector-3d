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
import s3d.core.ShipSystem3D.Kind;
import s3d.core.Sim;
import s3d.core.Tuning;
import s3d.math.Quat;
import s3d.math.Vec3;
import s3d.render.GLCaps;
import s3d.render.HudRenderer;
import s3d.render.ObjHull;
import s3d.render.Renderer3D;
import s3d.render.ShipModel;
import s3d.render.SpriteImage;

/**
 * Renders relief meshes through the real renderer/shaders on this machine's OpenGL (same legacy
 * context type as the game) and writes contact sheets to build/preview/. Also times a crowded scene.
 */
public final class MeshPreview {
	private static final int VW = 640, VH = 400;
	/** The mod folder, where hand-made hull models live (data/meshes/hulls). */
	private static File modDir;
	/** S3D_MESH=relief draws every hull as the sprite relief, ignoring hand-made models. */
	private static final boolean USE_MODELS = !"relief".equals(System.getenv("S3D_MESH"));

	public static void main(String[] args) throws Exception {
		File game = new File(args[0]);
		File build = new File(args[1]);
		List<String> ids = new ArrayList<>();
		for (int i = 2; i < args.length; i++) ids.add(args[i]);
		if (ids.isEmpty()) ids = List.of("wolf", "hammerhead", "eagle", "onslaught");

		modDir = build.getParentFile();
		boolean pbuffer = createContext();
		System.out.println("context: " + (pbuffer ? "pbuffer" : "window") + "  " + GLCaps.summary());

		Tuning t = GameData.modTuning(build);
		Map<String, GameData.Hull> hulls = GameData.hulls(game);
		Renderer3D renderer = new Renderer3D();
		s3d.render.GameFiles.opener = path -> new java.io.FileInputStream(new File(game, path)); // vanilla's HUD font and icons
		renderer.init();
		String settings = new String(java.nio.file.Files.readAllBytes(new File(game, "data/config/settings.json").toPath()), java.nio.charset.StandardCharsets.UTF_8);
		s3d.render.FxSprites.loader = (category, key) -> { // vanilla's sprite sheets, by their settings.json key
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(settings);
			return m.find() ? new SpriteImage(ImageIO.read(new File(game, m.group(1)))) : null;
		};
		int[] out = createCaptureTarget(VW, VH);
		File dir = new File(build, "preview");
		dir.mkdirs();
		if ("hullModels".equals(System.getenv("S3D_PREVIEW_ONLY"))) { // S3D_PREVIEW_ONLY=hullModels ./build.sh preview [ids]
			hullModelScene(game, hulls, args.length > 2 ? ids : null, renderer, dir);
			if (!pbuffer) Display.destroy();
			return;
		}
		if ("systemFx".equals(System.getenv("S3D_PREVIEW_ONLY"))) { // S3D_PREVIEW_ONLY=systemFx ./build.sh preview
			systemFxScene(game, hulls, t, renderer, dir);
			if (!pbuffer) Display.destroy();
			return;
		}
		if ("exoticFx".equals(System.getenv("S3D_PREVIEW_ONLY"))) { // S3D_PREVIEW_ONLY=exoticFx ./build.sh preview
			exoticFxScene(game, hulls, t, renderer, dir);
			if (!pbuffer) Display.destroy();
			return;
		}
		if ("deathFx".equals(System.getenv("S3D_PREVIEW_ONLY"))) { // S3D_PREVIEW_ONLY=deathFx ./build.sh preview
			deathFxScene(game, hulls, t, renderer, dir);
			if (!pbuffer) Display.destroy();
			return;
		}
		if ("shipPanel".equals(System.getenv("S3D_PREVIEW_ONLY"))) { // S3D_PREVIEW_ONLY=shipPanel ./build.sh preview
			shipPanelScene(game, hulls, t, renderer, dir);
			if (!pbuffer) Display.destroy();
			return;
		}
		if ("tactical".equals(System.getenv("S3D_PREVIEW_ONLY"))) { // S3D_PREVIEW_ONLY=tactical ./build.sh preview
			tacticalScene(game, hulls, t, renderer, dir);
			if (!pbuffer) Display.destroy();
			return;
		}
		if ("controls".equals(System.getenv("S3D_PREVIEW_ONLY"))) { // S3D_PREVIEW_ONLY=controls ./build.sh preview
			controlsScene(game, hulls, t, renderer, dir);
			if (!pbuffer) Display.destroy();
			return;
		}
		if ("retro".equals(System.getenv("S3D_PREVIEW_ONLY"))) { // S3D_PREVIEW_ONLY=retro ./build.sh preview
			retroScene(game, hulls, t, renderer, dir);
			if (!pbuffer) Display.destroy();
			return;
		}

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
		shipPanelScene(game, hulls, t, renderer, dir);
		stationScene(game, hulls, t, renderer, dir);
		benchmark(game, hulls, t, renderer, out[0], dir);
		if (pbuffer) System.out.println("done (pbuffer)");
		else Display.destroy();
	}

	private static Ship3D makeShip(File game, GameData.Hull h, Tuning t, String id) throws Exception {
		return makeShip(game, h, t, id, USE_MODELS);
	}

	private static Ship3D makeShip(File game, GameData.Hull h, Tuning t, String id, boolean model) throws Exception {
		GameData.readShipFile(game, h);
		SpriteImage img = new SpriteImage(ImageIO.read(new File(game, h.spritePath)));
		Ship3D s = new Ship3D(id);
		s.name = id;
		s.hullName = id;
		s.params = new FlightParams(h.speed, h.accel, h.decel, h.turn, h.turnAccel);
		s.baseRadius = (float) Math.hypot(img.w, img.h) * 0.5f;
		s.radius = s.baseRadius;
		s.sizeClass = h.sizeClass;
		ObjHull obj = model ? hullModel(h.id, img) : null;
		s.modelSource = new ShipModel.Source(h.spritePath, img, h.centerX, img.h - h.centerYFromBottom, obj);
		s.engines.addAll(h.engines);
		return s;
	}

	/** data/meshes/hulls, or $S3D_MODEL_DIR (relative to the mod folder) for staged work in progress. */
	private static File modelDir() {
		String d = System.getenv("S3D_MODEL_DIR");
		if (d == null || d.isEmpty()) return new File(modDir, "data/meshes/hulls");
		File f = new File(d);
		return f.isAbsolute() ? f : new File(modDir, d);
	}

	/** The hull's hand-made model from the mod folder (as the game would find it), or null. */
	private static ObjHull hullModel(String hullId, SpriteImage img) throws Exception {
		File f = new File(modelDir(), hullId + ".obj");
		if (!f.isFile()) return null;
		ObjHull obj = ObjHull.parse("data/meshes/hulls/" + f.getName(), java.nio.file.Files.readString(f.toPath()));
		if (!obj.fits(img)) {
			System.out.println(f.getName() + " was made for a " + obj.spriteW + "x" + obj.spriteH + " sprite; " + hullId + "'s is " + img.w + "x" + img.h);
			return null;
		}
		return obj;
	}

	/**
	 * Each hull that has a hand-made model, relief (top row) against model (bottom row) from the same
	 * five angles plus the chase view: build/preview/model_<id>.png.
	 */
	private static void hullModelScene(File game, Map<String, GameData.Hull> hulls, List<String> only, Renderer3D renderer, File dir) throws Exception {
		File[] files = modelDir().listFiles((d, n) -> n.endsWith(".obj"));
		if (files == null) return;
		int vw = 520, vh = 340;
		int[] target = createCaptureTarget(vw, vh);
		for (File f : files) {
			String id = f.getName().substring(0, f.getName().length() - 4);
			if (only != null && !only.contains(id) || hulls.get(id) == null) continue;
			Tuning t = GameData.modTuning(dir.getParentFile());
			String[] labels = {"top", "3/4 front, above", "side", "3/4 rear, below", "below", "chase view"};
			BufferedImage sheet = new BufferedImage(vw * labels.length, vh * 2, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = sheet.createGraphics();
			for (int row = 0; row < 2; row++) {
				Ship3D s = makeShip(game, hulls.get(id), t, id, row == 1);
				Sim sim = new Sim(t);
				sim.addShip(s);
				sim.setPlayer(s);
				s.thrustVisual = 0.6f;
				ShipModel m = Renderer3D.modelFor(s, t);
				System.out.println(id + (row == 1 ? " model: " : " relief: ") + m.info.summary());
				float r = (float) Math.hypot(m.info.maxX - m.info.minX, m.info.maxZ - m.info.minZ) * 0.5f * s.scale;
				Vec3[] eyes = {new Vec3(0, r * 1.75f, 0.01f), new Vec3(r * 0.8f, r * 0.5f, -r * 0.95f), new Vec3(r * 1.5f, r * 0.04f, 0f),
						new Vec3(-r * 0.75f, -r * 0.6f, r * 0.95f), new Vec3(0f, -r * 1.75f, 0.01f), null};
				for (int v = 0; v < labels.length; v++) {
					if (eyes[v] == null) {
						sim.camera.snapTo(s);
						sim.camera.update(s, t, 0.016f, false, 0f, 0f, vw / (float) vh);
					} else {
						lookAt(sim.camera, eyes[v], new Vec3(), t, vw / (float) vh);
					}
					renderer.renderScene(sim, vw, vh, target[0]);
					BufferedImage img = read(vw, vh);
					Graphics2D ig = img.createGraphics();
					ig.setColor(java.awt.Color.WHITE);
					ig.drawString(id + (row == 1 ? "  MODEL  " : "  relief  ") + labels[v], 8, vh - 10);
					ig.dispose();
					g.drawImage(img, v * vw, row * vh, null);
				}
			}
			g.dispose();
			File out = new File(dir, "model_" + id + ".png");
			ImageIO.write(sheet, "png", out);
			System.out.println("wrote " + out);
			closeUps(game, hulls.get(id), id, t, renderer, dir);
		}
	}

	/** The model large from three angles (front-above, rear-above, side-below): model_<id>_close.png. */
	private static void closeUps(File game, GameData.Hull h, String id, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int vw = 900, vh = 600;
		int[] target = createCaptureTarget(vw, vh);
		Ship3D s = makeShip(game, h, t, id, true);
		Sim sim = new Sim(t);
		sim.addShip(s);
		sim.setPlayer(s);
		s.thrustVisual = 0.6f;
		ShipModel m = Renderer3D.modelFor(s, t);
		float r = (float) Math.hypot(m.info.maxX - m.info.minX, m.info.maxZ - m.info.minZ) * 0.5f * s.scale;
		s.thrustVisual = 0.15f; // flames down, so the engine end shows
		Vec3[] eyes = {new Vec3(r * 0.95f, r * 0.6f, -r * 1.05f), new Vec3(-r * 0.6f, r * 0.45f, r * 1.3f), new Vec3(r * 1.45f, -r * 0.4f, -r * 0.2f),
				new Vec3(r * 0.12f, r * 0.25f, r * 1.4f)};
		String[] labels = {"front, above", "rear, above", "side, below", "behind"};
		BufferedImage sheet = new BufferedImage(vw * eyes.length, vh, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		for (int v = 0; v < eyes.length; v++) {
			lookAt(sim.camera, eyes[v], new Vec3(), t, vw / (float) vh);
			renderer.renderScene(sim, vw, vh, target[0]);
			BufferedImage img = read(vw, vh);
			Graphics2D ig = img.createGraphics();
			ig.setColor(java.awt.Color.WHITE);
			ig.drawString(id + "  MODEL  " + labels[v], 8, vh - 10);
			ig.dispose();
			g.drawImage(img, v * vw, 0, null);
		}
		g.dispose();
		File out = new File(dir, "model_" + id + "_close.png");
		ImageIO.write(sheet, "png", out);
		System.out.println("wrote " + out);
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
			String[] row = weaponRow(game, wid);
			if (row != null) {
				ws.name = row[0];
				ws.damageType = row[1].isEmpty() ? null : row[1];
			}
			ws.type = wj.optString("type", "?");
			ws.size = wj.optString("size", null);
			java.util.List<String> iconLayers = new java.util.ArrayList<>(); // as ShipBridge: turret under, base, barrel
			for (String key : new String[] {"turretUnderSprite", "turretSprite", "turretGunSprite"}) {
				String sp = wj.optString(key, "");
				if (!sp.isEmpty()) iconLayers.add(sp);
			}
			if (!wj.optString("turretSprite", "").isEmpty()) ws.iconSprites = iconLayers.toArray(new String[0]);
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
			if (sprite != null && !new File(game, sprite).isFile()) {
				System.out.println("  (no sprite file " + sprite + " for " + wid + ")");
			} else if (sprite != null) {
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

	/** Weapon display name and damage type from weapon_data.csv, by id (null if not listed). */
	private static String[] weaponRow(File game, String id) throws Exception {
		if (weaponCsv == null) {
			weaponCsv = new java.util.HashMap<>();
			List<String> lines = java.nio.file.Files.readAllLines(new File(game, "data/weapons/weapon_data.csv").toPath(),
					java.nio.charset.StandardCharsets.ISO_8859_1);
			List<String> head = GameData.csv(lines.get(0));
			int name = head.indexOf("name"), idCol = head.indexOf("id"), type = head.indexOf("type");
			for (String line : lines.subList(1, lines.size())) {
				List<String> r = GameData.csv(line);
				if (r.size() > Math.max(type, idCol) && !r.get(idCol).isEmpty()) weaponCsv.put(r.get(idCol), new String[] {r.get(name), r.get(type)});
			}
		}
		return weaponCsv.get(id);
	}

	private static Map<String, String[]> weaponCsv;

	/**
	 * Your ship panel (bottom left) at the game's resolution, two states: an Eagle with its Assault
	 * groups, Maneuvering Jets cooling down, shields up on some hard flux, a gun knocked out and a
	 * missile rack part spent, locked on a damaged Enforcer (both holograms); then a Hammerhead on
	 * zero flux with its Ammo Feeder running and group 2 selected.
	 */
	private static void shipPanelScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1920, h = 1200;
		int[] target = createCaptureTarget(w, h);
		String[][] ships = {{"eagle", "eagle_Assault", "ISS Zenith", "Eagle-class", "Assault Heavy Cruiser", "maneuveringjets", "Maneuvering Jets"},
				{"hammerhead", "hammerhead_Elite", "ISS Lucky Break", "Hammerhead-class", "Elite Destroyer", "ammofeed", "Accelerated Ammo Feeder"},
				{"ziggurat", "ziggurat_Experimental", "TTS Xenophornica", "Ziggurat-class", "Experimental", "mote_control", "Mote Attractor"}};
		for (int v = 0; v < ships.length; v++) {
			String[] d = ships[v];
			Sim sim = new Sim(t);
			sim.arenaHalfX = 5000f;
			sim.arenaHalfZ = 4500f;
			Ship3D p = makeShip(game, hulls.get(d[0]), t, d[0]);
			p.name = d[2];
			p.hullName = d[3];
			p.variantName = d[4];
			sim.addShip(p);
			p.volume = ShipModel.geometry((ShipModel.Source) p.modelSource, t).volume;
			arm(game, p, d[0], d[1]);
			p.flux = CombatBench.flux(hulls.get(d[0]), t);
			p.hullMax = v == 0 ? 8000f : 5000f;
			p.hull = v == 0 ? 6150f : p.hullMax;
			p.armor = new s3d.core.ArmorShell(v == 0 ? 1000f : 500f);
			p.system = new s3d.core.ShipSystem3D(d[5], d[6], s3d.core.ShipSystem3D.kindFor(d[5], false));
			p.system.chargeUp = p.system.chargeDown = 0.5f;
			p.system.active = 6f;
			p.system.cooldown = 12f;
			sim.setPlayer(p);
			Ship3D enf = makeShip(game, hulls.get("enforcer"), t, "enforcer");
			enf.name = "HSS Remember the 200th";
			enf.owner = 1;
			sim.addShip(enf);
			enf.volume = ShipModel.geometry((ShipModel.Source) enf.modelSource, t).volume;
			arm(game, enf, "enforcer", "enforcer_Assault");
			enf.flux = CombatBench.flux(hulls.get("enforcer"), t);
			enf.hullMax = enf.hull = 4000f;
			enf.armor = new s3d.core.ArmorShell(900f);
			enf.pos.set(150f, 40f, -1800f);
			enf.rot.setAxisAngle(0, 1, 0, 2.6f);
			PilotInput in = new PilotInput();
			in.screenW = w;
			in.screenH = h;
			in.mouseX = w * 0.5f;
			in.mouseY = h * 0.5f;
			sim.target = enf;
			for (int i = 0; i < 30; i++) {
				sim.update(in, 1f / 60f, 1f / 60f);
				in.clearPressed();
			}
			if (v == 0) {
				for (int az = 0; az < 3; az++) p.armor.strip(s3d.core.ArmorShell.sector(-40f, 30f, 1) + az - 1, 900f);
				for (int az = 0; az < 2; az++) enf.armor.strip(s3d.core.ArmorShell.sector(0f, -60f, 1) + az, 800f);
				enf.hull = 2300f;
				p.flux.flux = 5200f;
				p.flux.hardFlux = 2100f;
				p.flux.shieldOn = true;
				p.flux.unfold = 1f;
				p.system.state = s3d.core.ShipSystem3D.State.COOLDOWN;
				p.system.stateTime = 4f;
				if (p.weapons.size() > 1) p.weapons.get(1).disabledFor = 6f;
				for (s3d.core.WeaponMount m : p.weapons) if (m.spec.maxAmmo > 0) m.ammo = 1f;
				for (s3d.core.WeaponMount m : p.weapons) if (m.spec.maxAmmo <= 0 && m.disabledFor <= 0f) {
					m.cooldownLeft = m.spec.cooldown * 0.6f; // the guns of group 1 cycling
					break;
				}
				p.selectedGroup = 0;
			} else if (v == 2) {
				// Phased with 30% hard flux: coil stress has top speed at 60%.
				p.cloak = CombatBench.zigguratCloak(p);
				p.cloak.state = s3d.core.ShipSystem3D.State.ACTIVE;
				p.cloak.effect = 1f;
				p.flux.flux = p.flux.hardFlux = p.flux.maxFlux * 0.3f;
				sim.update(in, 1f / 60f, 1f / 60f);
				p.selectedGroup = 0;
			} else {
				p.flux.flux = p.flux.hardFlux = 0f;
				p.system.state = s3d.core.ShipSystem3D.State.ACTIVE;
				p.system.stateTime = 2f;
				p.selectedGroup = Math.min(1, p.groups.size() - 1);
			}
			renderer.renderScene(sim, w, h, target[0]);
			HudRenderer.Info info = new HudRenderer.Info();
			if (v == 0) { // vanilla's status list as in a campaign fight (bottom entry first), vanilla's own icons
				String[][] st = {{"graphics/icons/tactical/cr_tactical3.png", "Ion storm", "reduced peak time", "d"},
						{"graphics/icons/tactical/cr_tactical3.png", "Ion storm", "faster CR degradation", "d"},
						{"graphics/icons/tactical/cr_tactical.png", "Combat readiness 60%", "standard performance", ""},
						{"graphics/icons/tactical/cr_tactical.png", "Peak active performance", "remaining time: 243 sec", ""},
						{"graphics/warroom/objective_nav_buoy.png", "Coordinated maneuvers: 19%", "+19% top speed", ""},
						{"graphics/warroom/objective_sensor_array.png", "ECM rating: 24%", "-8% enemy weapon range", ""},
						{"graphics/warroom/objective_sensor_array.png", "Enemy ECM rating: 6%", "-1% weapon range", "d"},
						{"graphics/warroom/objective_comm_relay.png", "Command network", "+50% CP recovery rate", ""}};
				for (String[] e : st) {
					HudRenderer.Status s = new HudRenderer.Status();
					s.icon = e[0];
					s.title = e[1];
					s.data = e[2];
					s.debuff = !e[3].isEmpty();
					info.statuses.add(s);
				}
			}
			renderer.renderHud(sim, in, info, w, h);
			BufferedImage img = read(w, h);
			String name = v == 0 ? "ship_panel" : v == 1 ? "ship_panel_b" : "ship_panel_phase";
			ImageIO.write(img, "png", new File(dir, name + ".png"));
			ImageIO.write(img.getSubimage(0, h - 380, 760, 380), "png", new File(dir, name + "_crop.png"));
			StringBuilder groups = new StringBuilder();
			for (s3d.core.WeaponGroup3D g : p.groups) groups.append(" [").append(g.label()).append(g.autofire ? " auto" : "").append(']');
			System.out.println("wrote " + name + ".png: " + d[0] + groups);
		}
	}

	/**
	 * A ship you killed, frame by frame (blast, shockwave, debris, secondary pops, burning wreck, the
	 * kill marker round the reticle), then an ally jumping out on retreat: death_fx.png, jump_out.png.
	 */
	private static void deathFxScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 960, h = 600;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		sim.combat.numbersFor = null;
		Ship3D wolf = makeShip(game, hulls.get("wolf"), t, "wolf");
		sim.addShip(wolf);
		arm(game, wolf, "wolf", "wolf_Assault");
		sim.setPlayer(wolf);
		Ship3D eagle = makeShip(game, hulls.get("eagle"), t, "eagle");
		eagle.owner = 1;
		sim.addShip(eagle);
		arm(game, eagle, "eagle", "eagle_Assault");
		eagle.pos.set(120f, 30f, -1000f);
		eagle.rot.setAxisAngle(0, 1, 0, 1.2f);
		eagle.vel.set(20f, 0f, 10f);
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		for (int i = 0; i < 30; i++) sim.update(in, 1f / 60f, 1f / 60f);
		sim.combat.numbersFor = wolf;
		eagle.lastHitBy = wolf;
		sim.combat.clock = sim.time;
		sim.combat.kill(eagle);
		float[] at = {0.05f, 0.35f, 0.9f, 1.7f, 3f, 7f};
		java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
		float tt = 0f;
		for (float stop : at) {
			while (tt < stop) {
				sim.update(in, 1f / 60f, 1f / 60f);
				tt += 1f / 60f;
			}
			renderer.renderScene(sim, w, h, target[0]);
			renderer.renderHud(sim, in, new HudRenderer.Info(), w, h);
			frames.add(read(w, h));
		}
		ImageIO.write(gridOf(frames, 3, at), "png", new File(dir, "death_fx.png"));

		Sim sim2 = new Sim(t);
		Ship3D me = makeShip(game, hulls.get("wolf"), t, "wolf");
		sim2.addShip(me);
		sim2.setPlayer(me);
		Ship3D ham = makeShip(game, hulls.get("hammerhead"), t, "hammerhead");
		sim2.addShip(ham);
		ham.pos.set(-150f, 20f, -700f);
		ham.rot.setAxisAngle(0, 1, 0, 0.4f);
		ham.rot.forward(ham.vel).scale(200f);
		for (int i = 0; i < 20; i++) sim2.update(in, 1f / 60f, 1f / 60f);
		sim2.combat.jumpOut(ham);
		sim2.ships.remove(ham);
		float[] at2 = {0.03f, 0.15f, 0.4f};
		frames.clear();
		tt = 0f;
		for (float stop : at2) {
			while (tt < stop) {
				sim2.update(in, 1f / 60f, 1f / 60f);
				tt += 1f / 60f;
			}
			renderer.renderScene(sim2, w, h, target[0]);
			frames.add(read(w, h));
		}
		ImageIO.write(gridOf(frames, 3, at2), "png", new File(dir, "jump_out.png"));

		// Battle start: the camera swoops in from far behind; then a heavy hit from the left shows its arc.
		Sim sim3 = new Sim(t);
		Ship3D eag = makeShip(game, hulls.get("eagle"), t, "eagle");
		sim3.addShip(eag);
		arm(game, eag, "eagle", "eagle_Assault");
		eag.hullMax = eag.hull = 8000f;
		sim3.setPlayer(eag);
		sim3.camera.introTo(eag, 2f);
		float[] at3 = {0.02f, 0.7f, 1.4f, 2.4f};
		frames.clear();
		tt = 0f;
		for (float stop : at3) {
			while (tt < stop) {
				sim3.update(in, 1f / 60f, 1f / 60f);
				tt += 1f / 60f;
			}
			renderer.renderScene(sim3, w, h, target[0]);
			frames.add(read(w, h));
		}
		sim3.combat.hurtAt = sim3.time;
		sim3.combat.hurtAmount = 600f;
		Vec3 left = sim3.camera.rot.right(new Vec3()).scale(-1500f).add(eag.pos);
		sim3.combat.hurtFrom.set(left);
		sim3.update(in, 1f / 60f, 1f / 60f);
		renderer.renderScene(sim3, w, h, target[0]);
		renderer.renderHud(sim3, in, new HudRenderer.Info(), w, h);
		frames.add(read(w, h));
		ImageIO.write(gridOf(frames, 3, new float[] {0.02f, 0.7f, 1.4f, 2.4f, 2.42f}), "png", new File(dir, "intro_hurt.png"));
		System.out.println("wrote death_fx.png, jump_out.png, intro_hurt.png");
	}

	/**
	 * One tile per look: venting, overload, then each system kind running (and a displacer blink),
	 * after a second of battle time so particles have built up: system_fx.png.
	 */
	/** Threat and Shrouded looks: fragment swarms, lashes, swarms, the shroud and its systems, motes. One tile each. */
	private static void exoticFxScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 960, h = 600;
		int[] target = createCaptureTarget(w, h);
		String[] tiles = {"THREAT FRAGMENT SWARM", "ENERGY LASH -> FRIENDLY", "ENERGY LASH -> ENEMY (EMP)", "FRAGMENT VOLLEY",
				"INCURSION MODE", "ATTACK SWARM", "CONSTRUCTION SWARM", "SHROUDED TENDRIL", "CONVULSIVE LUNGE (charging)", "DARKENED GAZE",
				"TENEBROUS EXPULSION", "SHROUDED VORTEX", "MOTES (flocking)", "MOTES (attractor)"};
		java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
		for (String label : tiles) {
			Sim sim = new Sim(t);
			PilotInput in = new PilotInput();
			in.screenW = w;
			in.screenH = h;
			in.mouseX = w * 0.5f;
			in.mouseY = h * 0.5f;
			String hullId;
			switch (label) {
				case "THREAT FRAGMENT SWARM": case "INCURSION MODE": hullId = "assault_unit"; break;
				case "ENERGY LASH -> FRIENDLY": case "ENERGY LASH -> ENEMY (EMP)": hullId = "overseer_unit"; break;
				case "FRAGMENT VOLLEY": hullId = "standoff_unit"; break;
				case "ATTACK SWARM": hullId = "hive_unit"; break;
				case "CONSTRUCTION SWARM": hullId = "fabricator_unit"; break;
				case "SHROUDED TENDRIL": case "CONVULSIVE LUNGE (charging)": hullId = "shrouded_tendril"; break;
				case "DARKENED GAZE": hullId = "shrouded_eye"; break;
				case "TENEBROUS EXPULSION": hullId = "shrouded_maelstrom"; break;
				case "SHROUDED VORTEX": hullId = "wolf"; break;
				default: hullId = "ziggurat";
			}
			Ship3D p = exoticShip(game, hulls, t, sim, hullId, 0);
			sim.setPlayer(p);
			p.throttle = 0.3f;
			boolean threat = hullId.endsWith("_unit");
			if (threat) { // vanilla's base swarm by hull size
				String sc = String.valueOf(p.sizeClass).toUpperCase();
				p.fragments = new s3d.core.FragmentSwarm(sc.contains("CAPITAL") ? 100 : sc.contains("CRUISER") ? 60 : sc.contains("DESTROYER") ? 40 : 20, 0f);
			}
			if (hullId.startsWith("shrouded_")) p.shrouded = true;
			float run = 1.2f;
			switch (label) {
				case "ENERGY LASH -> FRIENDLY": case "ENERGY LASH -> ENEMY (EMP)": {
					boolean friendly = label.contains("FRIENDLY");
					Ship3D o = exoticShip(game, hulls, t, sim, friendly ? "assault_unit" : "enforcer", friendly ? 0 : 1);
					o.pos.set(220f, 40f, -650f);
					if (friendly) {
						o.fragments = new s3d.core.FragmentSwarm(40, 0f);
						o.system = CombatBench.system("incursion_mode", 1f, 15f, 1f, 0f, 1, 0f);
						o.system.ammo = 0f;
					}
					p.system = CombatBench.system("energy_lash", 1f, 0f, 0.1f, 0f, 0, 0f);
					p.system.range = 1500f;
					sim.update(in, 1f / 60f, 1f / 60f);
					p.system.ex.lashPick = o;
					p.system.activate(p);
					run = 1.1f;
					break;
				}
				case "FRAGMENT VOLLEY": case "INCURSION MODE": {
					p.system = CombatBench.system(label.equals("FRAGMENT VOLLEY") ? "fragment_volley" : "incursion_mode", 1f, 15f, 1f, 0f, 1, 0f);
					sim.update(in, 1f / 60f, 1f / 60f);
					p.system.lashed(p);
					run = 1.4f;
					break;
				}
				case "ATTACK SWARM": case "CONSTRUCTION SWARM": {
					boolean build = label.startsWith("CONSTRUCTION");
					Ship3D sw = exoticShip(game, hulls, t, sim, "attack_swarm", 0);
					sw.noHull = true;
					sw.fighter = true;
					sw.fragments = new s3d.core.FragmentSwarm(build ? 80 : 50, 0f);
					sw.behaviour = build ? Ship3D.Behaviour.CONSTRUCTION : null;
					sw.pos.set(0f, 40f, -420f);
					if (build) {
						sw.swarmLook = s3d.core.SwarmLook.swarm(80, 50f * 2f);
						sw.swarmLook.flashFringe = new float[] {0.51f, 0.61f, 0.57f, 1f};
						sw.buildLeft = sw.buildTotal = 30f;
						sw.buildVariant = "skirmish_unit_Type100";
						sw.ai = new s3d.core.ShipAI3D(3);
						sw.ai.profile(sw, t);
					}
					break;
				}
				case "CONVULSIVE LUNGE (charging)":
					p.system = CombatBench.system("convulsive_lunge", 2f, 3f, 0.5f, 0f, 0, 0f);
					sim.update(in, 1f / 60f, 1f / 60f);
					p.system.activate(p);
					run = 1.6f;
					break;
				case "DARKENED GAZE": {
					p.system = CombatBench.system("darkened_gaze", 2f, 0f, 1f, 0f, 0, 0f);
					p.system.toggle = true;
					s3d.core.WeaponSpec3D gb = CombatBench.beam(100f, false);
					gb.range = 1000f;
					gb.beamWidth = 20f;
					gb.coreColor = new float[] {1f, 0.8f, 0.8f};
					gb.fringeColor = new float[] {0.6f, 0f, 0.12f};
					p.gazeBeams.add(new s3d.core.WeaponMount("G", gb, new Vec3(0f, 10f, -p.radius * 0.4f), new Vec3(0, 0, -1), 5f, true));
					sim.update(in, 1f / 60f, 1f / 60f);
					p.system.activate(p);
					run = 2.6f;
					break;
				}
				case "TENEBROUS EXPULSION":
					for (int i = 0; i < 6; i++) {
						Ship3D e = exoticShip(game, hulls, t, sim, "shrouded_ejecta", 0);
						e.shrouded = true;
						e.behaviour = Ship3D.Behaviour.EJECTA;
						e.ai = new s3d.core.ShipAI3D(10 + i);
						e.ai.profile(e, t);
						double a = (i - 2.5) * 0.25;
						e.pos.set((float) Math.sin(a) * (350f + 60f * i), 20f * (i % 3), -(float) Math.cos(a) * (350f + 60f * i) - p.radius * 0.5f);
						e.vel.set((float) Math.sin(a) * 60f, 0f, -(float) Math.cos(a) * 60f);
					}
					run = 0.8f;
					break;
				case "SHROUDED VORTEX": {
					Ship3D v = exoticShip(game, hulls, t, sim, "shrouded_vortex", 1);
					v.shrouded = true;
					v.behaviour = Ship3D.Behaviour.VORTEX;
					v.ai = new s3d.core.ShipAI3D(20);
					v.ai.profile(v, t);
					v.pos.set(0f, 30f, -700f);
					run = 0.8f;
					break;
				}
				case "MOTES (flocking)": case "MOTES (attractor)": {
					p.system = CombatBench.system("mote_control", 0.1f, 0f, 0.4f, 0f, 0, 0f);
					s3d.core.WeaponSpec3D mote = CombatBench.launcher(true);
					mote.missileMaxSpeed = 400f;
					mote.missileAccel = 650f;
					mote.launchSpeed = 200f;
					mote.flightTime = 1e6f;
					mote.missileRadius = 11f;
					mote.missileGlow = new float[] {0.39f, 0.65f, 1f};
					p.system.motes.mote = mote;
					run = 7f;
					break;
				}
				default:
			}
			for (float tt = 0f; tt < run; tt += 1f / 60f) sim.update(in, 1f / 60f, 1f / 60f);
			if (label.equals("MOTES (attractor)")) {
				p.system.activate(p);
				for (float tt = 0f; tt < 1.6f; tt += 1f / 60f) sim.update(in, 1f / 60f, 1f / 60f);
			}
			renderer.renderScene(sim, w, h, target[0]);
			frames.add(read(w, h));
		}
		BufferedImage grid = gridOf(frames, 4, new float[tiles.length]);
		java.awt.Graphics2D g = grid.createGraphics();
		int fw = w / 2, fh = h / 2;
		for (int i = 0; i < tiles.length; i++) {
			g.setColor(java.awt.Color.BLACK);
			g.fillRect((i % 4) * fw, (i / 4) * fh, fw, 22);
			g.setColor(java.awt.Color.WHITE);
			g.drawString(tiles[i], (i % 4) * fw + 8, (i / 4) * fh + 16);
		}
		g.dispose();
		ImageIO.write(grid, "png", new File(dir, "exotic_fx.png"));
		System.out.println("wrote exotic_fx.png");
	}

	private static Ship3D exoticShip(File game, Map<String, GameData.Hull> hulls, Tuning t, Sim sim, String hullId, int owner) throws Exception {
		Ship3D s = makeShip(game, hulls.get(hullId), t, hullId);
		s.owner = owner;
		sim.addShip(s);
		s.volume = ShipModel.geometry((ShipModel.Source) s.modelSource, t).volume;
		s.flux = CombatBench.flux(hulls.get(hullId), t);
		if (hullId.startsWith("shrouded_")) {
			// The hull's shroud as vanilla's ship creators size it (in game they're read from the live shroud).
			s.shrouded = true;
			s3d.core.SwarmLook l = s3d.core.SwarmLook.shroud();
			switch (hullId) {
				case "shrouded_tendril": l.members = 70; l.maxOffset = 80f; l.darkArea = 1.25f; break;
				case "shrouded_eye": l.maxOffset = 130f; l.darkClearCentre = 50f; break;
				case "shrouded_maelstrom": l.members = 200; l.maxOffset = 250f; l.flashPerSecond *= 2f; break;
				case "shrouded_ejecta": l.members = 30; l.maxOffset = 120f; l.darkBatch = 3; l.darkHighContrast = true; l.darkSize = 1.5f; break;
				case "shrouded_vortex": l.members = 30; l.maxOffset = 50f; l.darkArea = 1.25f; break;
				default:
			}
			s.swarmLook = l;
		}
		return s;
	}

	private static void systemFxScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 960, h = 600;
		int[] target = createCaptureTarget(w, h);
		Object[][] tiles = {
				{"VENTING", "eagle", null}, {"OVERLOAD", "eagle", null}, {"BURN DRIVE", "enforcer", Kind.BURN_DRIVE}, {"PLASMA JETS", "eagle", Kind.PLASMA_JETS},
				{"MICROBURN", "enforcer", Kind.MICROBURN}, {"DISPLACER (charging)", "eagle", Kind.DISPLACER}, {"PHASE TELEPORTER (charging)", "eagle", Kind.PHASE_TELEPORTER},
				{"DAMPER FIELD", "eagle", Kind.DAMPER}, {"TEMPORAL SHELL", "eagle", Kind.TEMPORAL_SHELL}, {"FORTRESS SHIELD", "eagle", Kind.FORTRESS_SHIELD},
				{"PHASE CLOAK", "eagle", Kind.PHASE_CLOAK}, {"HIGH ENERGY FOCUS", "eagle", Kind.HIGH_ENERGY_FOCUS}, {"AMMO FEED", "eagle", Kind.AMMO_FEED},
				{"ENTROPY AMPLIFIER (target red)", "eagle", Kind.ENTROPY_AMPLIFIER}, {"MINE STRIKE", "eagle", Kind.MINE_STRIKE}, {"EMP EMITTER", "eagle", Kind.EMP_EMITTER},
				{"COMBAT BURN", "onslaught", Kind.COMBAT_BURN}, {"ORION DEVICE (first jolt)", "retribution", Kind.ORION_DEVICE},
				{"NOVA BURST", "retribution", Kind.NOVA_BURST}, {"QUANTUM DISRUPTOR (target pink)", "eagle", Kind.ACAUSAL_DISRUPTOR},
		};
		java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
		String[] labels = new String[tiles.length];
		for (int ti = 0; ti < tiles.length; ti++) {
			String label = (String) tiles[ti][0], hullId = (String) tiles[ti][1];
			s3d.core.ShipSystem3D.Kind kind = (s3d.core.ShipSystem3D.Kind) tiles[ti][2];
			labels[ti] = label;
			Sim sim = new Sim(t);
			Ship3D p = makeShip(game, hulls.get(hullId), t, hullId);
			sim.addShip(p);
			arm(game, p, hullId, hullId + "_Assault");
			p.volume = ShipModel.geometry((ShipModel.Source) p.modelSource, t).volume;
			for (s3d.core.WeaponMount wm : p.weapons) wm.localPos.y = p.volume.topAt(wm.localPos.x, wm.localPos.z) + 1f; // on the hull, as in game
			p.flux = CombatBench.flux(hulls.get(hullId), t);
			sim.setPlayer(p);
			p.throttle = 0.4f;
			Ship3D enemy = null;
			if (kind == Kind.ENTROPY_AMPLIFIER || kind == Kind.EMP_EMITTER || kind == Kind.ACAUSAL_DISRUPTOR) {
				enemy = makeShip(game, hulls.get("enforcer"), t, "enforcer");
				enemy.owner = 1;
				sim.addShip(enemy);
				enemy.volume = ShipModel.geometry((ShipModel.Source) enemy.modelSource, t).volume;
				enemy.pos.set(250f, 60f, kind == Kind.ENTROPY_AMPLIFIER ? -900f : -500f);
				if (kind == Kind.ACAUSAL_DISRUPTOR) {
					enemy.flux = CombatBench.flux(hulls.get("enforcer"), t);
					enemy.flux.disrupt(30f);
					enemy.overloadTint = new float[] {1f, 0.61f, 1f};
				}
			}
			if ("VENTING".equals(label)) {
				p.flux.flux = p.flux.maxFlux * 0.8f;
				p.flux.venting = true;
			} else if ("OVERLOAD".equals(label)) {
				p.flux.overloadLeft = 20f;
			} else if (kind != null) {
				s3d.core.ShipSystem3D sys = new s3d.core.ShipSystem3D(kind.name().toLowerCase(), label, kind);
				boolean charging = kind == Kind.DISPLACER || kind == Kind.PHASE_TELEPORTER || kind == Kind.ACAUSAL_DISRUPTOR;
				sys.active = 60f;
				sys.chargeUp = charging ? 10f : 0.2f;
				sys.state = charging ? s3d.core.ShipSystem3D.State.IN : s3d.core.ShipSystem3D.State.ACTIVE;
				sys.stateTime = charging ? 7f : 0f;
				sys.effect = 1f;
				sys.sysTarget = enemy;
				sys.range = 1500f;
				sys.damage = 50f;
				sys.empDamage = 100f;
				if (kind == Kind.FORTRESS_SHIELD) p.flux.shieldOn = true;
				if (kind == Kind.ORION_DEVICE || kind == Kind.NOVA_BURST) { // fired for real: the drop, then the blast
					sys.state = s3d.core.ShipSystem3D.State.IDLE;
					sys.effect = 0f;
					sys.chargeUp = kind == Kind.NOVA_BURST ? 0.5f : 0f;
					sys.active = 0.001f;
					sys.cooldown = 1f;
					sys.maxAmmo = 1;
					sys.ammo = 1f;
				}
				if (kind == Kind.COMBAT_BURN) sys.toggle = true;
				if (kind == Kind.PHASE_CLOAK) p.cloak = sys;
				else p.system = sys;
			}
			PilotInput in = new PilotInput();
			in.screenW = w;
			in.screenH = h;
			in.mouseX = w * 0.5f;
			in.mouseY = h * 0.5f;
			if (p.system != null && (kind == Kind.ORION_DEVICE || kind == Kind.NOVA_BURST)) {
				sim.update(in, 1f / 60f, 1f / 60f);
				p.system.activate(p);
			}
			float run = kind == Kind.ORION_DEVICE ? 0.7f : kind == Kind.NOVA_BURST ? 0.9f : 1.2f;
			for (float tt = 0f; tt < run; tt += 1f / 60f) {
				if ("VENTING".equals(label)) p.flux.flux = Math.max(p.flux.flux, p.flux.maxFlux * 0.6f);
				sim.update(in, 1f / 60f, 1f / 60f);
			}
			renderer.renderScene(sim, w, h, target[0]);
			frames.add(read(w, h));
		}
		BufferedImage grid = gridOf(frames, 4, new float[tiles.length]);
		java.awt.Graphics2D g = grid.createGraphics();
		int fw = w / 2, fh = h / 2;
		for (int i = 0; i < labels.length; i++) {
			g.setColor(java.awt.Color.BLACK);
			g.fillRect((i % 4) * fw, (i / 4) * fh, fw, 22);
			g.setColor(java.awt.Color.WHITE);
			g.drawString(labels[i], (i % 4) * fw + 8, (i / 4) * fh + 16);
		}
		g.dispose();
		ImageIO.write(grid, "png", new File(dir, "system_fx.png"));

		// Orion Device and Nova Burst seen from the side (an observer's chase view): the drop, the blast, the jolt.
		java.util.List<BufferedImage> side = new java.util.ArrayList<>();
		float[] at = {0.3f, 0.6f, 1.2f};
		for (Kind kind : new Kind[] {Kind.ORION_DEVICE, Kind.NOVA_BURST}) {
			Sim sim = new Sim(t);
			Ship3D obs = makeShip(game, hulls.get("wolf"), t, "wolf");
			sim.addShip(obs);
			obs.pos.set(1500f, 150f, 300f);
			obs.rot.setAxisAngle(0, 1, 0, (float) (Math.PI * 0.5)); // nose toward -x: looking across at the Retribution
			sim.setPlayer(obs);
			Ship3D ret = makeShip(game, hulls.get("retribution"), t, "retribution");
			sim.addShip(ret);
			ret.volume = ShipModel.geometry((ShipModel.Source) ret.modelSource, t).volume;
			s3d.core.ShipSystem3D sys = new s3d.core.ShipSystem3D(kind.name().toLowerCase(), kind.name(), kind);
			sys.chargeUp = kind == Kind.NOVA_BURST ? 0.5f : 0f;
			sys.active = 0.001f;
			sys.cooldown = 1f;
			sys.maxAmmo = 1;
			sys.ammo = 1f;
			ret.system = sys;
			ret.throttle = 0.6f;
			PilotInput in = new PilotInput();
			in.screenW = w;
			in.screenH = h;
			in.mouseX = w * 0.5f;
			in.mouseY = h * 0.5f;
			for (int i = 0; i < 30; i++) sim.update(in, 1f / 60f, 1f / 60f);
			sys.activate(ret);
			float tt = 0f;
			for (float stop : at) {
				float until = stop + (kind == Kind.NOVA_BURST ? 0.5f : 0f);
				while (tt < until) {
					sim.update(in, 1f / 60f, 1f / 60f);
					tt += 1f / 60f;
				}
				renderer.renderScene(sim, w, h, target[0]);
				side.add(read(w, h));
			}
		}
		ImageIO.write(gridOf(side, 3, new float[] {0.3f, 0.6f, 1.2f, 0.3f, 0.6f, 1.2f}), "png", new File(dir, "orion_side.png"));
		System.out.println("wrote system_fx.png, orion_side.png");
	}

	/** Frames side by side in rows, half size, each with its time stamp. */
	private static BufferedImage gridOf(java.util.List<BufferedImage> frames, int cols, float[] times) {
		int fw = frames.get(0).getWidth() / 2, fh = frames.get(0).getHeight() / 2;
		int rows = (frames.size() + cols - 1) / cols;
		BufferedImage out = new BufferedImage(fw * cols, fh * rows, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int i = 0; i < frames.size(); i++) {
			int x = (i % cols) * fw, y = (i / cols) * fh;
			g.drawImage(frames.get(i), x, y, fw, fh, null);
			g.setColor(java.awt.Color.WHITE);
			g.drawString(String.format("t = %.2f s", times[i]), x + 8, y + 16);
		}
		g.dispose();
		return out;
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
	/**
	 * The tactical view as a command view: tactical_orders.png (two wingmen selected, one holding a
	 * point up high, one escorting you, the cursor on an enemy), tactical_height.png (dragging a move
	 * order's height), tactical_ring.png (the ring of other orders, AVOID picked).
	 */
	/**
	 * The controls card over a paused fight at the game's 1920x1200: controls_flight.png (flight face)
	 * and controls_command.png (the command view's face).
	 */
	private static void controlsScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1920, h = 1200;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		sim.arenaHalfX = 6000f;
		sim.arenaHalfZ = 5000f;
		String[][] ships = {{"eagle", "eagle_Assault"}, {"wolf", "wolf_Assault"}, {"enforcer", "enforcer_Assault"}, {"lasher", "lasher_Standard"}};
		Ship3D player = null;
		for (int i = 0; i < ships.length; i++) {
			GameData.Hull hull = hulls.get(ships[i][0]);
			Ship3D s = makeShip(game, hull, t, ships[i][0] + i);
			s.owner = i < 2 ? 0 : 1;
			s.name = ships[i][0];
			sim.addShip(s);
			s.volume = ShipModel.geometry((ShipModel.Source) s.modelSource, t).volume;
			arm(game, s, ships[i][0], ships[i][1]);
			s.flux = CombatBench.flux(hull, t);
			s.armor = new s3d.core.ArmorShell(400f);
			s.hullMax = s.hull = 5000f;
			s.pos.set(-500f + 600f * (i % 2), 120f * i - 150f, s.owner == 0 ? 300f * i : -1500f - 300f * i);
			if (s.owner == 1) s.rot.setAxisAngle(0, 1, 0, 2.8f);
			if (i == 0) player = s;
		}
		sim.setPlayer(player);
		sim.target = sim.ships.get(2);
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		float dt = 1f / 60f;
		for (int i = 0; i < 40; i++) {
			sim.update(in, dt, dt);
			in.clearPressed();
		}
		sim.paused = true;
		HudRenderer.Info info = new HudRenderer.Info();
		info.controls = 1f;
		renderer.renderScene(sim, w, h, target[0]);
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "controls_flight.png"));
		in.toggleTactical = true;
		sim.update(in, dt, dt);
		in.clearPressed();
		in.toggleTactical = false;
		for (int i = 0; i < 90; i++) sim.update(in, dt, dt);
		renderer.renderScene(sim, w, h, target[0]);
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "controls_command.png"));
		System.out.println("wrote controls_flight.png, controls_command.png");
	}

	private static void tacticalScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1280, h = 800;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		sim.arenaHalfX = 6000f;
		sim.arenaHalfZ = 5000f;
		String[][] fleets = {{"eagle:eagle_Assault", "hammerhead:hammerhead_Balanced", "wolf:wolf_Assault", "enforcer:enforcer_Assault"},
				{"lasher:lasher_Standard", "hammerhead:hammerhead_Elite", "dominator:dominator_Assault", "wolf:wolf_CS"}};
		Ship3D player = null;
		List<Ship3D> mine = new ArrayList<>(), theirs = new ArrayList<>();
		for (int side = 0; side < 2; side++) {
			for (int i = 0; i < fleets[side].length; i++) {
				String[] hv = fleets[side][i].split(":");
				GameData.Hull hull = hulls.get(hv[0]);
				Ship3D s = makeShip(game, hull, t, hv[0] + side);
				s.owner = side;
				s.name = hv[0];
				sim.addShip(s);
				s.volume = ShipModel.geometry((ShipModel.Source) s.modelSource, t).volume;
				arm(game, s, hv[0], hv[1]);
				s.flux = CombatBench.flux(hull, t);
				float big = "cruiser".equals(s.sizeClass) ? 3f : "destroyer".equals(s.sizeClass) ? 2f : 1f;
				s.armor = new s3d.core.ArmorShell(300f * big);
				s.hullMax = s.hull = 1e6f;
				s.pos.set(-1200f + 800f * i, (i % 2) * 350f - 150f, side == 0 ? 1600f : -1900f);
				if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				s.ai = new s3d.core.ShipAI3D(i + side * 5);
				s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
				(side == 0 ? mine : theirs).add(s);
				if (side == 0 && i == 0) player = s;
			}
		}
		player.ai = null;
		sim.setPlayer(player);
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		float dt = 1f / 60f;
		for (float time = 0f; time < 1.5f; time += dt) {
			in.toggleTactical = time == 0f;
			sim.update(in, dt, dt);
		}
		in.toggleTactical = false;
		in.wheelNotches = 0f;
		sim.combat.kill(theirs.get(3)); // a burning wreck: left out of the tactical view
		for (float time = 0f; time < 1.5f; time += dt) sim.update(in, dt, dt);
		// Orders as the host would have read them back: one holds a point up high, one escorts you.
		Ship3D holder = mine.get(1), escort = mine.get(2);
		holder.order = new s3d.core.Order3D(s3d.core.Order3D.Kind.HOLD);
		holder.order.point.set(holder.pos.x - 1400f, holder.pos.y + 1200f, holder.pos.z - 900f);
		holder.order.radius = 400f;
		escort.order = new s3d.core.Order3D(s3d.core.Order3D.Kind.ESCORT);
		escort.order.ship = player;
		Ship3D attacker = mine.get(3);
		attacker.order = new s3d.core.Order3D(s3d.core.Order3D.Kind.ATTACK);
		attacker.order.ship = theirs.get(2);
		sim.paused = true;
		sim.command.selected.add(holder);
		sim.command.selected.add(attacker);
		sim.shuttleModelSource = new s3d.render.ShipModel.Source("graphics/ships/shuttle.png", new SpriteImage(ImageIO.read(new File(game, "graphics/ships/shuttle.png"))), 9f, 11f);
		sim.startTransfer(attacker); // the command shuttle on its way (paused: it stays mid-flight)
		sim.shuttle.pos.set(player.pos).lerp(attacker.pos, 0.45f).add(new Vec3(0f, 60f, 0f));
		sim.update(in, dt, dt);
		float[] sc = new float[3];
		sim.camera.toScreen(theirs.get(1).pos, w, h, sc);
		in.mouseX = sc[0];
		in.mouseY = sc[1];
		sim.update(in, dt, dt);
		HudRenderer.Info info = new HudRenderer.Info();
		info.commandPoints = 4;
		sim.command.fleetButtons = true; // off here; on / confirming in tactical_ring.png
		renderer.renderScene(sim, w, h, target[0]);
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "tactical_orders.png"));

		// Height drag on empty space
		in.mouseX = w * 0.62f;
		in.mouseY = h * 0.35f;
		in.mouseRight = true;
		sim.update(in, dt, dt);
		for (int i = 1; i <= 12; i++) {
			in.mouseY += 12f;
			sim.update(in, dt, dt);
		}
		renderer.renderScene(sim, w, h, target[0]);
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "tactical_height.png"));
		in.mouseRight = false;
		sim.update(in, dt, dt);

		// Ring
		in.mouseX = w * 0.62f;
		in.mouseY = h * 0.35f;
		in.mouseRight = true;
		for (float time = 0f; time < 0.45f; time += dt) sim.update(in, dt, dt);
		sim.command.fullAssault = true;
		sim.command.retreatConfirm = 60f;
		in.mouseRight = false; // held still and let go: the ring stays open
		sim.update(in, dt, dt);
		in.mouseX += 60f;
		sim.update(in, dt, dt);
		renderer.renderScene(sim, w, h, target[0]);
		renderer.renderHud(sim, in, info, w, h);
		ImageIO.write(read(w, h), "png", new File(dir, "tactical_ring.png"));
		System.out.println("wrote tactical_orders.png, tactical_height.png, tactical_ring.png; orders: " + sim.command.issued);
	}

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

	/**
	 * The retro filter's variants on one frozen battle frame, at the game's 1920x1200: an Onslaught
	 * close up and a wider view past your Eagle. retro_<view>_<variant>.png full size, plus
	 * retro_sheet.png (all variants, half size) and retro_zoom.png (1:1 crops, 2x nearest).
	 */
	private static void retroScene(File game, Map<String, GameData.Hull> hulls, Tuning t, Renderer3D renderer, File dir) throws Exception {
		int w = 1920, h = 1200;
		int[] target = createCaptureTarget(w, h);
		Sim sim = new Sim(t);
		sim.arenaHalfX = 5000f;
		sim.arenaHalfZ = 4500f;
		String[][] fleets = {{"eagle:eagle_Assault", "hammerhead:hammerhead_Balanced", "wolf:wolf_Assault"},
				{"onslaught:onslaught_Standard", "lasher:lasher_Standard", "enforcer:enforcer_Assault"}};
		Ship3D player = null, onslaught = null;
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
				s.armor = new s3d.core.ArmorShell(1500f);
				s.hullMax = s.hull = 1e6f; // nobody dies: the frame should be full of ships
				s.pos.set(-700f + 700f * i, (i % 2) * 120f, side == 0 ? 900f : -900f);
				if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				if (side == 0 && i == 0) {
					player = s;
				} else {
					s.ai = new s3d.core.ShipAI3D(i + side * 5);
					s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
				}
				if (hv[0].equals("onslaught")) onslaught = s;
			}
		}
		sim.setPlayer(player);
		PilotInput in = new PilotInput();
		in.screenW = w;
		in.screenH = h;
		in.mouseX = w * 0.5f;
		in.mouseY = h * 0.5f;
		for (float time = 0f; time < 9f; time += 1f / 60f) {
			in.throttleUp = time < 1f;
			in.fire = sim.hasLead;
			sim.update(in, 1f / 60f, 1f / 60f);
			in.clearPressed();
		}
		float or = onslaught.radius;
		Vec3 oFwd = onslaught.rot.forward(new Vec3()), oRight = onslaught.rot.right(new Vec3());
		Vec3 closeEye = new Vec3(onslaught.pos).addScaled(oFwd, or * 0.9f).addScaled(oRight, or * 0.75f).add(new Vec3(0f, or * 0.45f, 0f));
		Vec3 pFwd = player.rot.forward(new Vec3());
		Vec3 wideEye = new Vec3(player.pos).addScaled(pFwd, -player.radius * 4f).add(new Vec3(0f, player.radius * 1.6f, 0f));
		Vec3 wideAt = new Vec3(player.pos).addScaled(pFwd, player.radius * 6f);
		String[] views = {"close", "wide"};
		Vec3[][] cams = {{closeEye, new Vec3(onslaught.pos).addScaled(oFwd, or * 0.2f)}, {wideEye, wideAt}};
		// variant: filter, pixelSize, colorBits, wobble
		Object[][] variants = {{"off", 3, 5, 0f}, {"ships", 3, 5, 0f}, {"scene", 3, 5, 0f}, {"ships", 2, 5, 0f}, {"ships", 4, 4, 0f}, {"ships", 3, 5, 1f}};
		String[] names = {"off", "ships", "scene", "ships_px2", "ships_px4_4bit", "ships_wobble"};
		String saved = t.retroFilter;
		int savedPx = t.retroPixelSize, savedBits = t.retroColorBits;
		float savedWobble = t.retroWobble;
		int sw = w / 2, sh = h / 2;
		BufferedImage sheet = new BufferedImage(sw * views.length, sh * variants.length, BufferedImage.TYPE_INT_RGB);
		BufferedImage zoom = new BufferedImage(480 * 2 * variants.length, 300 * 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics(), zg = zoom.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		zg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		for (int v = 0; v < views.length; v++) {
			lookAt(sim.camera, cams[v][0], cams[v][1], t, w / (float) h);
			for (int k = 0; k < variants.length; k++) {
				t.retroFilter = (String) variants[k][0];
				t.retroPixelSize = (Integer) variants[k][1];
				t.retroColorBits = (Integer) variants[k][2];
				t.retroWobble = (Float) variants[k][3];
				renderer.renderScene(sim, w, h, target[0]);
				BufferedImage img = read(w, h);
				ImageIO.write(img, "png", new File(dir, "retro_" + views[v] + "_" + names[k] + ".png"));
				g.drawImage(img, v * sw, k * sh, sw, sh, null);
				g.setColor(java.awt.Color.WHITE);
				g.drawString(views[v] + "  " + names[k], v * sw + 8, k * sh + sh - 10);
				if (v == 0) {
					BufferedImage crop = img.getSubimage(w / 2 - 240, h / 2 - 150, 480, 300);
					zg.drawImage(crop, k * 960, 0, 960, 600, null);
					zg.setColor(java.awt.Color.WHITE);
					zg.drawString(names[k], k * 960 + 8, 590);
				}
			}
		}
		g.dispose();
		zg.dispose();
		t.retroFilter = saved;
		t.retroPixelSize = savedPx;
		t.retroColorBits = savedBits;
		t.retroWobble = savedWobble;
		ImageIO.write(sheet, "png", new File(dir, "retro_sheet.png"));
		ImageIO.write(zoom, "png", new File(dir, "retro_zoom.png"));
		System.out.println("wrote retro_sheet.png, retro_zoom.png and retro_<view>_<variant>.png");
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
