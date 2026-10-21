package s3d.tools;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.json.JSONArray;
import org.json.JSONObject;

import s3d.core.ArmorShell;
import s3d.core.ChaseCamera;
import s3d.core.EngineMount;
import s3d.core.PilotInput;
import s3d.core.Ship3D;
import s3d.core.ShipAI3D;
import s3d.core.ShipSystem3D;
import s3d.core.Sim;
import s3d.core.Tuning;
import s3d.core.WeaponGroup3D;
import s3d.core.WeaponMount;
import s3d.core.WeaponSpec3D;
import s3d.math.Vec3;
import s3d.render.Renderer3D;
import s3d.render.ShipModel;
import s3d.render.SpriteImage;

/**
 * Cinematic stills of big AI-vs-AI battles for the forum thread (dev tool), rendered through the real
 * renderer with no HUD. Ships come from the game's own data: variants, weapon_data.csv and the weapon,
 * projectile and missile files (ranges, damage, colours, beam widths, missile sprites), hull points,
 * armor, flux and shields from ship_data.csv, shield and engine colours from the hull and engine
 * styles, carriers' wings, and the simpler ship systems. Hullmods, skills and weapon scripts aren't in.
 *
 * Each battle is fought once; at set times every camera of the shot list renders a frame at
 * SS x 1920x1080 and is scaled down: build/forum_shots/battles/<battle>/<time>s_<camera>.png.
 *
 * Run: S3D_SHOTS_ONLY=<battle> (optional), MAIN=s3d.tools.ForumShots via build.sh's run_tool.
 */
public final class ForumShots {
	private static final int W = 1920, H = 1080;
	private static final int SS = Integer.parseInt(env("S3D_SHOTS_SS", "2"));
	private static final float DT = 1f / 60f;

	private static File game;
	private static Map<String, GameData.Hull> hulls;
	private static Map<String, List<String>> shipRows, weaponRows, wingRows, systemRows;
	private static List<String> shipHead, weaponHead, wingHead, systemHead;
	private static JSONObject hullStyles, engineStyles;

	public static void main(String[] args) throws Exception {
		game = new File(args[0]);
		File build = new File(args[1]);
		MeshPreview.modDir = build.getParentFile();
		MeshPreview.createContext();
		Tuning t = GameData.modTuning(build);
		hulls = GameData.hulls(game);
		Renderer3D renderer = new Renderer3D();
		s3d.render.GameFiles.opener = path -> new java.io.FileInputStream(new File(game, path));
		renderer.init();
		String settings = Files.readString(new File(game, "data/config/settings.json").toPath(), StandardCharsets.UTF_8);
		s3d.render.FxSprites.loader = (category, key) -> {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(settings);
			return m.find() ? new SpriteImage(ImageIO.read(new File(game, m.group(1)))) : null;
		};
		loadTables();
		File out = new File(build, "forum_shots/battles");
		String only = System.getenv("S3D_SHOTS_ONLY");
		for (Battle b : battles()) {
			if (only != null && !only.equals(b.name)) continue;
			fight(b, t, renderer, new File(out, b.name));
		}
	}

	static String env(String k, String def) {
		String v = System.getenv(k);
		return v == null || v.isEmpty() ? def : v;
	}

	// ---- the battles ----

	static final class Battle {
		final String name;
		final String[][] fleets;
		final float[] times;
		final long seed;

		Battle(String name, long seed, float[] times, String[] a, String[] b) {
			this.name = name;
			this.seed = seed;
			this.times = times;
			fleets = new String[][] {a, b};
		}
	}

	/** Twenty a side, biggest first (they take the middle of the front rank). */
	static List<Battle> battles() {
		List<Battle> out = new ArrayList<>();
		float[] times = {8f, 11f, 14f, 17f, 20f, 26f, 34f, 44f, 56f};
		out.add(new Battle("hegemony_vs_tritach", 7L, times,
				new String[] {"onslaught_Elite", "onslaught_Standard", "legion_Strike", "dominator_Support", "dominator_AntiCV", "mora_Strike",
						"eagle_Assault", "eagle_Balanced", "enforcer_Assault", "enforcer_Elite", "enforcer_Escort", "condor_Strike", "manticore_Assault",
						"centurion_Assault", "lasher_Assault", "lasher_Standard", "brawler_Assault", "wolf_hegemony_Assault", "hound_hegemony_Standard",
						"monitor_Escort"},
				new String[] {"paragon_Elite", "astral_Elite", "odyssey_Balanced", "aurora_Assault", "aurora_Balanced", "aurora_Strike",
						"fury_Attack", "fury_Support", "heron_Attack", "medusa_Attack", "medusa_CS", "medusa_PD", "apogee_Balanced", "omen_PD",
						"tempest_Attack", "tempest_Attack", "tempest_Attack", "wolf_Assault", "wolf_Assault", "shrike_Attack"}));
		out.add(new Battle("coalition_vs_remnant", 11L, times,
				new String[] {"invictus_Standard", "conquest_Elite", "conquest_Standard", "retribution_Standard", "champion_Assault",
						"champion_Strike", "gryphon_Standard", "eagle_Assault", "falcon_Attack", "falcon_CS", "heron_Strike", "sunder_Assault",
						"medusa_Attack", "enforcer_Assault", "manticore_Balanced", "centurion_Assault", "hammerhead_Overdriven", "wolf_Assault",
						"kite_Interceptor", "brawler_Assault"},
				new String[] {"radiant_Assault", "radiant_Strike", "radiant_Standard", "brilliant_Standard", "brilliant_Standard",
						"brilliant_Support", "brilliant_Standard", "scintilla_Strike", "scintilla_Support", "scintilla_Strike", "scintilla_Support",
						"glimmer_Assault", "glimmer_Support", "glimmer_Assault", "glimmer_Support", "glimmer_Assault", "lumen_Standard",
						"lumen_Standard", "lumen_Standard", "lumen_Standard"}));
		return out;
	}

	static void fight(Battle b, Tuning t, Renderer3D renderer, File dir) throws Exception {
		dir.mkdirs();
		Sim sim = new Sim(t);
		sim.combat.seed(b.seed);
		ShipSystem3D.seed(b.seed);
		sim.arenaHalfX = 12000f;
		sim.arenaHalfZ = 12000f;
		List<Ship3D> fighters = new ArrayList<>();
		Map<Ship3D, Vec3> fighterOffset = new HashMap<>();
		for (int side = 0; side < 2; side++) {
			String[] f = b.fleets[side];
			for (int i = 0; i < f.length; i++) {
				Ship3D s = realShip(f[i], f[i] + "_" + side + "_" + i, side, t);
				s.ai = new ShipAI3D(i + side * 31 + (int) b.seed * 977);
				s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
				// Two ranks of ten, big ships in the middle of the front one, heights staggered.
				int rank = i < 10 ? 0 : 1, k = i % 10;
				float slot = (k % 2 == 0 ? 1f : -1f) * ((k + 1) / 2) * 720f - 360f;
				float z = 2900f + rank * 700f;
				s.pos.set(slot * (side == 0 ? 1f : -1f), ((i * 7) % 5 - 2) * 90f + rank * 120f, side == 0 ? z : -z);
				if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				sim.addShip(s);
				for (Ship3D ftr : wings(f[i], s, t)) {
					sim.addShip(ftr);
					fighters.add(ftr);
					fighterOffset.put(ftr, new Vec3(ftr.pos));
					ftr.pos.add(s.pos);
				}
			}
		}
		System.out.printf("%s: %d ships, %d fighters%n", b.name, sim.ships.size() - fighters.size(), fighters.size());
		PilotInput in = new PilotInput();
		in.screenW = W;
		in.screenH = H;
		int[] target = MeshPreview.createCaptureTarget(W * SS, H * SS);
		int next = 0;
		float end = b.times[b.times.length - 1] + 0.5f;
		for (float time = 0f; time < end; time += DT) {
			sim.update(in, DT, DT);
			in.clearPressed();
			// Landed fighters (back at their bay to rearm) relaunch at once.
			for (Ship3D ftr : fighters) {
				if (!ftr.landed || ftr.carrier == null || ftr.carrier.destroyed) continue;
				ftr.landed = false;
				ftr.hull = ftr.hullMax;
				ftr.pos.set(ftr.carrier.pos).add(fighterOffset.get(ftr));
				ftr.rot.set(ftr.carrier.rot);
				ftr.vel.set(ftr.carrier.vel);
				for (WeaponMount m : ftr.weapons) m.ammo = m.spec.maxAmmo;
			}
			if (next < b.times.length && time >= b.times[next]) {
				shoot(sim, t, renderer, target[0], dir, b.times[next]);
				next++;
			}
		}
	}

	// ---- cameras ----

	/** Every camera of the shot list at this moment. */
	static void shoot(Sim sim, Tuning t, Renderer3D renderer, int fbo, File dir, float time) throws Exception {
		Vec3[] centre = {sim.fleetCentre(0), sim.fleetCentre(1)};
		int[] alive = new int[2];
		for (Ship3D s : sim.ships) if (!s.destroyed && !s.fighter) alive[s.owner]++;
		if (centre[0] == null || centre[1] == null) return;
		Vec3 axis = new Vec3(centre[1]).sub(centre[0]);
		float gap = axis.length();
		axis.normalize();
		Vec3 up = new Vec3(0f, 1f, 0f);
		Vec3 side = new Vec3(up).cross(axis).normalize();
		Vec3 mid = new Vec3(centre[0]).add(centre[1]).scale(0.5f);
		float spread = Math.max(spread(sim, 0, centre[0]), spread(sim, 1, centre[1]));
		String tag = String.format("%02ds", Math.round(time));
		Ship3D[] flag = {biggest(sim, 0), biggest(sim, 1)};
		List<Object[]> cams = new ArrayList<>();

		// Side-on telephoto at the meeting line: both lines face each other across the frame.
		float d = Math.max(gap * 1.1f, spread * 1.2f) + 1500f;
		cams.add(new Object[] {"side_tele", new Vec3(mid).addScaled(side, d).addScaled(up, d * 0.08f), mid, 24f});
		cams.add(new Object[] {"side_tele_low", new Vec3(mid).addScaled(side, -d).addScaled(up, -d * 0.06f), mid, 24f});
		// Head-on: just ahead of one line, level, looking down the axis at the other charging in.
		for (int s = 0; s < 2; s++) {
			Vec3 fwd = s == 0 ? axis : new Vec3(axis).scale(-1f);
			Vec3 eye = new Vec3(centre[s]).addScaled(fwd, -900f).addScaled(up, 160f);
			cams.add(new Object[] {"headon_" + s, eye, new Vec3(centre[1 - s]), 42f});
		}
		// Behind one whole line, raised, looking over its backs at the other line coming on.
		for (int s = 0; s < 2; s++) {
			Vec3 fwd = s == 0 ? axis : new Vec3(axis).scale(-1f);
			Vec3 eye = new Vec3(centre[s]).addScaled(fwd, -(1800f + spread * 0.25f)).addScaled(up, 650f);
			cams.add(new Object[] {"behind_line_" + s, eye, new Vec3(centre[1 - s]).addScaled(up, -200f), 44f});
		}
		// Three-quarter view from out past one flank: both lines recede across the frame.
		for (int s = 0; s < 2; s++) {
			float fl = s == 0 ? 1f : -1f;
			Vec3 eye = new Vec3(mid).addScaled(side, fl * (spread + 2200f)).addScaled(axis, -fl * gap * 0.35f).addScaled(up, 900f);
			cams.add(new Object[] {"flank34_" + s, eye, new Vec3(mid).addScaled(side, -fl * spread * 0.15f), 40f});
		}
		// Over the flagship's shoulder at the enemy line.
		for (int s = 0; s < 2; s++) {
			Ship3D f = flag[s];
			if (f == null) continue;
			Vec3 toEnemy = new Vec3(centre[1 - s]).sub(f.pos).normalize();
			Vec3 right = new Vec3(up).cross(toEnemy).normalize();
			float r = f.radius;
			Vec3 eye = new Vec3(f.pos).addScaled(toEnemy, -r * 3.2f).addScaled(right, r * 1.3f).addScaled(up, r * 0.9f);
			Vec3 look = new Vec3(f.pos).addScaled(toEnemy, r * 10f);
			cams.add(new Object[] {"shoulder_" + s, eye, look, 48f});
		}
		// Low hero angle under a flagship, looking up past it toward the fight.
		for (int s = 0; s < 2; s++) {
			Ship3D f = flag[s];
			if (f == null) continue;
			Vec3 toEnemy = new Vec3(centre[1 - s]).sub(f.pos).normalize();
			Vec3 right = new Vec3(up).cross(toEnemy).normalize();
			float r = f.radius;
			Vec3 eye = new Vec3(f.pos).addScaled(toEnemy, r * 2.4f).addScaled(right, -r * 1.6f).addScaled(up, -r * 1.1f);
			Vec3 look = new Vec3(f.pos).addScaled(toEnemy, -r * 0.4f).addScaled(up, r * 0.3f);
			cams.add(new Object[] {"hero_" + s, eye, look, 55f});
		}
		// High three-quarter overview of the whole fight.
		cams.add(new Object[] {"overview", new Vec3(mid).addScaled(side, d * 0.5f).addScaled(axis, -d * 0.4f).addScaled(up, d * 0.35f), mid, 36f});
		// Broadside duel: the closest pair of big enemies, side-on to the line between them.
		Ship3D[] duel = duel(sim);
		if (duel != null) {
			Vec3 a = duel[0].pos, bb = duel[1].pos;
			Vec3 m = new Vec3(a).add(bb).scale(0.5f);
			Vec3 los = new Vec3(bb).sub(a);
			float sep = los.length();
			los.normalize();
			Vec3 perp = new Vec3(up).cross(los).normalize();
			float back = sep * 1.1f + (duel[0].radius + duel[1].radius);
			cams.add(new Object[] {"duel", new Vec3(m).addScaled(perp, back).addScaled(up, back * 0.18f), m, 34f});
			cams.add(new Object[] {"duel_low", new Vec3(m).addScaled(perp, -back * 0.8f).addScaled(los, -sep * 0.35f).addScaled(up, -back * 0.12f), m, 40f});
		}
		// The hottest spot: the ship taking the most fire, framed from beside its attacker.
		Ship3D hot = hottest(sim);
		if (hot != null && hot.lastHitBy != null && !hot.lastHitBy.destroyed) {
			Ship3D by = hot.lastHitBy;
			Vec3 los = new Vec3(hot.pos).sub(by.pos);
			float dist = los.length();
			los.normalize();
			Vec3 right = new Vec3(up).cross(los).normalize();
			Vec3 eye = new Vec3(by.pos).addScaled(los, -by.radius * 2.5f).addScaled(right, by.radius * 1.4f + dist * 0.12f).addScaled(up, by.radius * 0.8f);
			cams.add(new Object[] {"action", eye, new Vec3(hot.pos).addScaled(los, -dist * 0.35f), 40f});
			Vec3 eye2 = new Vec3(hot.pos).addScaled(los, hot.radius * 3.2f).addScaled(right, -hot.radius * 1.5f).addScaled(up, hot.radius * 0.6f);
			cams.add(new Object[] {"target", eye2, new Vec3(hot.pos).addScaled(los, -dist * 0.4f), 46f});
		}

		// A contact sheet of the moment (labelled thumbnails) to pick from: sheets/<time>.png.
		int tw = W / 4, th = H / 4;
		BufferedImage sheet = new BufferedImage(tw * 4, th * ((cams.size() + 3) / 4), BufferedImage.TYPE_INT_RGB);
		Graphics2D sg = sheet.createGraphics();
		sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int i = 0; i < cams.size(); i++) {
			Object[] c = cams.get(i);
			Vec3 eye = (Vec3) c[1], look = (Vec3) c[2];
			lookAt(sim.camera, eye, look, (Float) c[3], W / (float) H, eye.distance(look));
			renderer.renderScene(sim, W * SS, H * SS, fbo);
			BufferedImage img = downscale(MeshPreview.read(W * SS, H * SS));
			ImageIO.write(img, "png", new File(dir, tag + "_" + c[0] + ".png"));
			sg.drawImage(img, (i % 4) * tw, (i / 4) * th, tw, th, null);
			sg.setColor(java.awt.Color.YELLOW);
			sg.drawString(tag + "_" + c[0], (i % 4) * tw + 6, (i / 4) * th + 16);
		}
		sg.dispose();
		File sheets = new File(dir, "sheets");
		sheets.mkdirs();
		ImageIO.write(sheet, "png", new File(sheets, tag + ".png"));
		System.out.printf("  %s: %d v %d ships, %d shots, %d missiles, gap %.0f -> %d frames%n", tag, alive[0], alive[1],
				sim.combat.projectiles.size(), sim.combat.missiles.size(), gap, cams.size());
	}

	static float spread(Sim sim, int owner, Vec3 c) {
		float m = 0f;
		for (Ship3D s : sim.ships) if (!s.destroyed && !s.fighter && s.owner == owner) m = Math.max(m, s.pos.distance(c));
		return m;
	}

	/** The closest pair of enemy cruisers or capitals (else destroyers) that are within weapon reach of each other. */
	static Ship3D[] duel(Sim sim) {
		Ship3D[] best = null;
		float bestScore = Float.MAX_VALUE;
		for (Ship3D a : sim.ships) {
			if (a.destroyed || a.fighter || a.owner != 0 || "frigate".equals(a.sizeClass)) continue;
			for (Ship3D b : sim.ships) {
				if (b.destroyed || b.fighter || b.owner != 1 || "frigate".equals(b.sizeClass)) continue;
				float d = a.pos.distance(b.pos);
				if (d > 1600f) continue;
				float score = d - (a.radius + b.radius) * 2f; // bigger ships win ties
				if (score < bestScore) {
					bestScore = score;
					best = new Ship3D[] {a, b};
				}
			}
		}
		return best;
	}

	static Ship3D biggest(Sim sim, int owner) {
		Ship3D best = null;
		for (Ship3D s : sim.ships) if (!s.destroyed && !s.fighter && s.owner == owner && (best == null || s.radius > best.radius)) best = s;
		return best;
	}

	/** The ship with the most fresh hits on its shield, else the most hurt one still flying. */
	static Ship3D hottest(Sim sim) {
		Ship3D best = null;
		float bestScore = 0f;
		for (Ship3D s : sim.ships) {
			if (s.destroyed || s.fighter || s.lastHitBy == null) continue;
			float score = 0f;
			if (s.flux != null && s.flux.hitAge != null) for (float a : s.flux.hitAge) if (a < 0.5f) score += 1f;
			score += (1f - s.hull / s.hullMax) * 3f + s.radius * 0.002f;
			if (score > bestScore) {
				bestScore = score;
				best = s;
			}
		}
		return best;
	}

	static void lookAt(ChaseCamera cam, Vec3 eye, Vec3 target, float fovDeg, float aspect, float dist) {
		cam.pos.set(eye);
		Vec3 fwd = new Vec3(target).sub(eye);
		Vec3 up = Math.abs(fwd.y) > 0.95f * fwd.length() ? new Vec3(0, 0, -1) : new Vec3(0, 1, 0);
		cam.rot.setLookRotation(fwd, up);
		cam.fovYRad = (float) Math.toRadians(fovDeg);
		cam.proj.perspective(cam.fovYRad, aspect, Math.max(2f, dist * 0.002f), 200000f);
		cam.view.view(cam.rot, cam.pos);
		cam.viewProj.mul(cam.proj, cam.view);
	}

	static BufferedImage downscale(BufferedImage src) {
		if (SS == 1) return src;
		BufferedImage cur = src;
		int w = src.getWidth(), h = src.getHeight();
		while (w > W) { // halve with bilinear until at size: a box-ish filter for the supersample
			w = Math.max(W, w / 2);
			h = Math.max(H, h / 2);
			BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = next.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(cur, 0, 0, w, h, null);
			g.dispose();
			cur = next;
		}
		return cur;
	}

	// ---- ships from the game's data ----

	static void loadTables() throws Exception {
		Object[] s = table("data/hulls/ship_data.csv", "id");
		shipHead = cast(s[0]);
		shipRows = cast(s[1]);
		Object[] w = table("data/weapons/weapon_data.csv", "id");
		weaponHead = cast(w[0]);
		weaponRows = cast(w[1]);
		Object[] g = table("data/hulls/wing_data.csv", "id");
		wingHead = cast(g[0]);
		wingRows = cast(g[1]);
		Object[] y = table("data/shipsystems/ship_systems.csv", "id");
		systemHead = cast(y[0]);
		systemRows = cast(y[1]);
		hullStyles = GameData.lenientJson(new File(game, "data/config/hull_styles.json"));
		engineStyles = GameData.lenientJson(new File(game, "data/config/engine_styles.json"));
	}

	@SuppressWarnings("unchecked")
	static <T> T cast(Object o) {
		return (T) o;
	}

	static Object[] table(String path, String key) throws Exception {
		List<String> lines = Files.readAllLines(new File(game, path).toPath(), StandardCharsets.ISO_8859_1);
		List<String> head = GameData.csv(lines.get(0));
		int k = head.indexOf(key);
		Map<String, List<String>> rows = new HashMap<>();
		StringBuilder pending = new StringBuilder();
		for (String line : lines.subList(1, lines.size())) {
			// Quoted cells can run over lines: join until the quotes balance.
			pending.append(pending.length() > 0 ? "\n" : "").append(line);
			if (pending.chars().filter(c -> c == '"').count() % 2 != 0) continue;
			List<String> r = GameData.csv(pending.toString());
			pending.setLength(0);
			if (r.size() > k && !r.get(k).isEmpty() && !r.get(k).startsWith("#")) rows.putIfAbsent(r.get(k), r);
		}
		return new Object[] {head, rows};
	}

	static String cell(List<String> head, List<String> row, String col) {
		int i = head.indexOf(col);
		return row == null || i < 0 || i >= row.size() ? "" : row.get(i).trim();
	}

	static float num(List<String> head, List<String> row, String col, float def) {
		String v = cell(head, row, col);
		if (v.isEmpty()) return def;
		try {
			return Float.parseFloat(v);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	static List<String> keys(JSONObject o) {
		List<String> out = new ArrayList<>();
		for (java.util.Iterator<?> it = o.keys(); it.hasNext(); ) out.add((String) it.next());
		return out;
	}

	static float[] rgb(JSONArray a, float[] def) {
		if (a == null || a.length() < 3) return def;
		return new float[] {a.optInt(0) / 255f, a.optInt(1) / 255f, a.optInt(2) / 255f};
	}

	static JSONObject variant(String variantId) throws Exception {
		File f = findVariant(new File(game, "data/variants"), variantId + ".variant");
		if (f == null) throw new IllegalArgumentException("no variant " + variantId);
		return GameData.lenientJson(f);
	}

	static File findVariant(File dir, String name) {
		File[] fs = dir.listFiles();
		if (fs == null) return null;
		for (File f : fs) {
			if (f.isDirectory()) {
				File r = findVariant(f, name);
				if (r != null) return r;
			} else if (f.getName().equals(name)) {
				return f;
			}
		}
		return null;
	}

	/** A ship as the game would field it from this variant (no hullmods, skills or weapon scripts). */
	static Ship3D realShip(String variantId, String id, int owner, Tuning t) throws Exception {
		JSONObject v = variant(variantId);
		String hullId = v.getString("hullId");
		File skin = new File(game, "data/hulls/skins/" + hullId + ".skin");
		if (!hulls.containsKey(hullId) && skin.isFile()) hullId = GameData.lenientJson(skin).optString("baseHullId", hullId); // a skin: its base hull
		GameData.Hull h = hulls.get(hullId);
		if (h == null) throw new IllegalArgumentException("no hull " + hullId);
		Ship3D s = MeshPreview.makeShip(game, h, t, id);
		s.owner = owner;
		s.name = s.hullName = hullId;
		s.volume = ShipModel.geometry((ShipModel.Source) s.modelSource, t).volume;
		JSONObject ship = GameData.lenientJson(new File(game, "data/hulls/" + hullId + ".ship"));
		engines(s, ship);
		List<String> row = shipRows.get(hullId);
		float fallbackHull = "capital".equals(h.sizeClass) ? 14000 : "cruiser".equals(h.sizeClass) ? 8000 : "destroyer".equals(h.sizeClass) ? 4000 : 1800;
		s.hullMax = s.hull = num(shipHead, row, "hitpoints", fallbackHull);
		s.armor = new ArmorShell(num(shipHead, row, "armor rating", 300f));
		s.flux = CombatBench.flux(h, t);
		if (h.mass > 0f) s.mass = h.mass;
		JSONObject style = hullStyles.optJSONObject(ship.optString("style", "LOW_TECH"));
		if (style != null) {
			s.flux.innerColor = rgb(style.optJSONArray("shieldInnerColor"), s.flux.innerColor);
			s.flux.ringColor = rgb(style.optJSONArray("shieldRingColor"), s.flux.ringColor);
		}
		s.system = system(cell(shipHead, row, "system id"));
		arm(s, hullId, v);
		return s;
	}

	/** Engine flames in their engine style's colour (vanilla's look, not the tools' default orange). */
	static void engines(Ship3D s, JSONObject ship) throws Exception {
		JSONArray es = ship.optJSONArray("engineSlots");
		if (es == null) return;
		s.engines.clear();
		for (int i = 0; i < es.length(); i++) {
			JSONObject e = es.getJSONObject(i);
			JSONArray loc = e.getJSONArray("location");
			float[] c = {1f, 0.55f, 0.3f};
			String st = e.optString("style", "LOW_TECH");
			if ("CUSTOM".equals(st) && e.optJSONObject("styleSpec") != null) c = rgb(e.getJSONObject("styleSpec").optJSONArray("engineColor"), c);
			else if (engineStyles.optJSONObject(st) != null) c = rgb(engineStyles.getJSONObject(st).optJSONArray("engineColor"), c);
			s.engines.add(EngineMount.fromShipSpace((float) loc.getDouble(0), (float) loc.getDouble(1), (float) e.optDouble("angle", 180),
					(float) e.optDouble("length", 30), (float) e.optDouble("width", 10), c[0], c[1], c[2]));
		}
	}

	/** Movement and defence systems with their ship_systems.csv timing; others are left off. */
	static ShipSystem3D system(String id) {
		if (id.isEmpty()) return null;
		ShipSystem3D.Kind kind = ShipSystem3D.kindFor(id, false);
		switch (kind) {
			case BURN_DRIVE: case MANEUVERING_JETS: case PLASMA_JETS: case MICROBURN: case COMBAT_BURN: case FORTRESS_SHIELD: case DAMPER:
			case AMMO_FEED: case HIGH_ENERGY_FOCUS: case TEMPORAL_SHELL: case FAST_MISSILE_RACKS: case FLARES:
				break;
			default:
				return null;
		}
		List<String> r = systemRows.get(id);
		if (r == null) return null;
		ShipSystem3D s = new ShipSystem3D(id, cell(systemHead, r, "name"), kind);
		s.chargeUp = num(systemHead, r, "charge up", 0f);
		s.active = num(systemHead, r, "active", 0f);
		s.chargeDown = num(systemHead, r, "down", 0f);
		s.cooldown = num(systemHead, r, "cooldown", 0f);
		int uses = (int) num(systemHead, r, "max uses", 0f);
		s.maxAmmo = uses > 0 && uses < 1000 ? uses : 0;
		s.ammoPerSecond = num(systemHead, r, "regen", 0f);
		s.ammo = s.maxAmmo;
		s.fluxPerUse = num(systemHead, r, "flux/use", 0f);
		s.fluxPerSecond = num(systemHead, r, "flux/second", 0f);
		s.toggle = "TRUE".equalsIgnoreCase(cell(systemHead, r, "toggle"));
		s.firingAllowed = !"TRUE".equalsIgnoreCase(cell(systemHead, r, "noFiring"));
		s.turningAllowed = !"TRUE".equalsIgnoreCase(cell(systemHead, r, "noTurning"));
		s.shieldAllowed = !"TRUE".equalsIgnoreCase(cell(systemHead, r, "noShield"));
		s.dissipationAllowed = !"TRUE".equalsIgnoreCase(cell(systemHead, r, "noDissipation"));
		return s;
	}

	/** The variant's weapons, from weapon_data.csv and the weapon / projectile files. */
	static void arm(Ship3D s, String hullId, JSONObject v) throws Exception {
		Map<String, String> fit = new HashMap<>();
		JSONArray groups = v.optJSONArray("weaponGroups");
		if (groups != null) for (int i = 0; i < groups.length(); i++) {
			JSONObject w = groups.getJSONObject(i).optJSONObject("weapons");
			if (w != null) for (String k : keys(w)) fit.put(k, w.getString(k));
		}
		JSONObject ship = GameData.lenientJson(new File(game, "data/hulls/" + hullId + ".ship"));
		JSONObject builtIn = ship.optJSONObject("builtInWeapons");
		if (builtIn != null) for (String k : keys(builtIn)) fit.putIfAbsent(k, builtIn.getString(k));
		Map<String, WeaponMount> bySlot = new HashMap<>();
		for (GameData.Slot slot : GameData.slots(game, hullId)) {
			String wid = fit.get(slot.id);
			if (wid == null || "DECORATIVE".equals(slot.type) || "SYSTEM".equals(slot.type) || "LAUNCH_BAY".equals(slot.type)) continue;
			File wf = new File(game, "data/weapons/" + wid + ".wpn");
			if (!wf.isFile()) continue;
			JSONObject wj = GameData.lenientJson(wf);
			WeaponSpec3D ws = weaponSpec(wid, wj);
			if (ws == null) continue;
			boolean hp = "HARDPOINT".equals(slot.mount);
			float x = -slot.y, z = -slot.x;
			float y = s.volume != null ? s.volume.topAt(x, z) + 1f : 0f;
			double a = Math.toRadians(slot.angle);
			WeaponMount m = new WeaponMount(slot.id, ws, new Vec3(x, y, z), new Vec3((float) -Math.sin(a), 0f, (float) -Math.cos(a)), slot.arc, hp);
			m.ammo = ws.maxAmmo;
			if (!"HIDDEN".equals(slot.mount)) {
				m.modelSource = sprite(wj.optString(hp ? "hardpointSprite" : "turretSprite", null), hp);
				m.gunModelSource = sprite(wj.optString(hp ? "hardpointGunSprite" : "turretGunSprite", null), hp);
			}
			s.weapons.add(m);
			bySlot.put(slot.id, m);
		}
		if (groups != null) for (int i = 0; i < groups.length(); i++) {
			JSONObject g = groups.getJSONObject(i);
			WeaponGroup3D g3 = new WeaponGroup3D();
			g3.autofire = g.optBoolean("autofire", false);
			g3.alternating = "ALTERNATING".equals(g.optString("mode"));
			JSONObject w = g.optJSONObject("weapons");
			if (w != null) for (String k : keys(w)) if (bySlot.containsKey(k)) g3.mounts.add(bySlot.get(k));
			if (!g3.mounts.isEmpty()) s.groups.add(g3);
		}
	}

	private static final Map<String, ShipModel.Source> SPRITES = new HashMap<>();

	static ShipModel.Source sprite(String path, boolean hardpoint) throws Exception {
		if (path == null || path.isEmpty() || !new File(game, path).isFile()) return null;
		String key = path + hardpoint;
		if (!SPRITES.containsKey(key)) {
			SpriteImage img = new SpriteImage(ImageIO.read(new File(game, path)));
			SPRITES.put(key, new ShipModel.Source(path, img, img.w * 0.5f, hardpoint ? img.h * 0.75f : img.h * 0.5f, true));
		}
		return SPRITES.get(key);
	}

	static WeaponSpec3D weaponSpec(String wid, JSONObject wj) throws Exception {
		List<String> r = weaponRows.get(wid);
		if (r == null) return null;
		List<String> hd = weaponHead;
		WeaponSpec3D ws = new WeaponSpec3D();
		ws.id = wid;
		ws.name = cell(hd, r, "name");
		ws.type = wj.optString("type", "?");
		ws.size = wj.optString("size", null);
		ws.supported = true;
		ws.range = num(hd, r, "range", 600f);
		ws.turnRate = num(hd, r, "turn rate", 30f);
		ws.emp = num(hd, r, "emp", 0f);
		ws.impact = num(hd, r, "impact", 0f);
		ws.damageType = cell(hd, r, "type");
		switch (ws.damageType) {
			case "KINETIC": ws.shieldMult = 2f; ws.armorMult = 0.5f; break;
			case "HIGH_EXPLOSIVE": ws.shieldMult = 0.5f; ws.armorMult = 2f; break;
			case "FRAGMENTATION": ws.shieldMult = 0.25f; ws.armorMult = 0.25f; break;
			default:
		}
		String hints = cell(hd, r, "hints");
		ws.pd = hints.contains("PD");
		ws.pdOnly = hints.contains("PD_ONLY");
		ws.strike = hints.contains("STRIKE");
		int ammo = (int) num(hd, r, "ammo", 0f);
		if (ammo > 0) {
			ws.maxAmmo = ws.baseMaxAmmo = ammo;
			ws.ammoPerSecond = num(hd, r, "ammo/sec", 0f);
		}
		if ("beam".equals(wj.optString("specClass"))) {
			ws.kind = WeaponSpec3D.Kind.BEAM;
			ws.dps = ws.damage = num(hd, r, "damage/second", 100f);
			ws.chargeUp = num(hd, r, "chargeup", 0.1f);
			ws.chargeDown = num(hd, r, "chargedown", 0.2f);
			float burst = num(hd, r, "burst size", 0f);
			ws.burstBeam = burst > 0f;
			ws.burstDuration = burst;
			ws.cooldown = num(hd, r, "burst delay", 0f);
			ws.beamWidth = (float) wj.optDouble("width", 10);
			ws.beamSpeed = ws.projectileSpeed = num(hd, r, "beam speed", 10000f);
			ws.fluxPerSecond = num(hd, r, "energy/second", 0f);
			ws.coreColor = rgb(wj.optJSONArray("coreColor"), new float[] {1f, 1f, 1f});
			ws.fringeColor = rgb(wj.optJSONArray("fringeColor"), new float[] {1f, 0.4f, 0.3f});
			ws.empArcs = wid.startsWith("ionbeam") || wid.equals("tachyonlance");
			return ws;
		}
		ws.damage = num(hd, r, "damage/shot", 50f);
		ws.cooldown = Math.max(0.05f, num(hd, r, "chargedown", 0.5f));
		ws.chargeTime = num(hd, r, "chargeup", 0f);
		ws.burstSize = Math.max(1, (int) num(hd, r, "burst size", 1f));
		ws.burstDelay = num(hd, r, "burst delay", 0f);
		ws.fluxPerShot = num(hd, r, "energy/shot", 0f) / ws.burstSize;
		ws.minSpread = num(hd, r, "min spread", 0f);
		ws.maxSpread = Math.max(ws.minSpread, num(hd, r, "max spread", 0f));
		ws.spreadBuildup = num(hd, r, "spread/shot", 0f);
		ws.spreadDecay = num(hd, r, "spread decay/sec", 0f);
		ws.projectileSpeed = num(hd, r, "proj speed", 800f);
		ws.empArcs = wid.startsWith("ion");
		String projId = wj.optString("projectileSpecId", "");
		File pf = new File(game, "data/weapons/proj/" + projId + ".proj");
		JSONObject pj = pf.isFile() ? GameData.lenientJson(pf) : new JSONObject();
		if ("missile".equals(pj.optString("specClass"))) {
			ws.kind = WeaponSpec3D.Kind.MISSILE;
			ws.launchSpeed = num(hd, r, "launch speed", 100f);
			ws.missileMaxSpeed = ws.projectileSpeed;
			ws.flightTime = num(hd, r, "flight time", 8f);
			ws.missileHp = num(hd, r, "proj hitpoints", 100f);
			JSONObject eng = pj.optJSONObject("engineSpec");
			ws.missileAccel = eng != null ? (float) eng.optDouble("acc", 500) : 500f;
			ws.missileTurnRate = eng != null ? (float) eng.optDouble("turnRate", 0) : 0f;
			ws.missileTurnAccel = eng != null ? (float) eng.optDouble("turnAcc", 0) : 0f;
			ws.missileRadius = Math.max(4f, (float) pj.optDouble("collisionRadius", 10));
			ws.explosionRadius = (float) pj.optDouble("explosionRadius", ws.missileRadius * 3f);
			String type = pj.optString("missileType", "MISSILE");
			ws.guidance = "HEATSEEKER".equals(type) ? WeaponSpec3D.Guidance.HEATSEEKER
					: "MISSILE_TWO_STAGE_SECOND_UNGUIDED".equals(type) ? WeaponSpec3D.Guidance.TWO_STAGE : WeaponSpec3D.Guidance.HOMING;
			ws.guided = ws.missileTurnRate > 1f && !"ROCKET".equals(type) && !"BOMB".equals(type);
			float[] glow = rgb(pj.optJSONArray("glowColor"), null);
			JSONArray slots = pj.optJSONArray("engineSlots");
			if (glow == null && slots != null && slots.length() > 0 && slots.getJSONObject(0).optJSONObject("styleSpec") != null)
				glow = rgb(slots.getJSONObject(0).getJSONObject("styleSpec").optJSONArray("engineColor"), null);
			if (glow == null) glow = rgb(pj.optJSONArray("explosionColor"), ws.missileGlow);
			ws.missileGlow = glow;
			ws.fringeColor = glow;
			String ms = pj.optString("sprite", null);
			if (ms != null && new File(game, ms).isFile()) {
				SpriteImage mi = new SpriteImage(ImageIO.read(new File(game, ms)));
				ws.missileModelSource = new ShipModel.Source(ms, mi, mi.w * 0.5f, mi.h * 0.5f, true);
			}
			return ws;
		}
		ws.projLength = (float) pj.optDouble("length", 30);
		ws.projWidth = (float) pj.optDouble("width", 6);
		ws.coreColor = rgb(pj.optJSONArray("coreColor"), new float[] {1f, 1f, 1f});
		float[] fringe = rgb(pj.optJSONArray("fringeColor"), null);
		if (fringe == null) fringe = rgb(pj.optJSONArray("glowColor"), "ENERGY".equals(ws.type) ? new float[] {0.4f, 0.7f, 1f} : new float[] {1f, 0.75f, 0.35f});
		ws.fringeColor = fringe;
		return ws;
	}

	/** The carrier's wings, launched round it (positions relative to the carrier). */
	static List<Ship3D> wings(String variantId, Ship3D carrier, Tuning t) throws Exception {
		List<Ship3D> out = new ArrayList<>();
		JSONArray ws = variant(variantId).optJSONArray("wings");
		if (ws == null) return out;
		int n = 0;
		for (int i = 0; i < ws.length(); i++) {
			List<String> r = wingRows.get(ws.optString(i));
			if (r == null) continue;
			String fv = cell(wingHead, r, "variant");
			if (findVariant(new File(game, "data/variants"), fv + ".variant") == null) {
				System.out.println("  (no variant file for wing " + ws.optString(i) + ": " + fv + ", left out)");
				continue;
			}
			int num = (int) num(wingHead, r, "num", 2f);
			for (int k = 0; k < num; k++) {
				Ship3D f = realShip(fv, carrier.id + "_w" + i + "_" + k, carrier.owner, t);
				f.fighter = true;
				f.carrier = carrier;
				f.wingRole = cell(wingHead, r, "role");
				f.attackRunRange = num(wingHead, r, "attackRunRange", 500f);
				f.volume = f.volume.thickened(f.baseRadius * 0.35f);
				f.ai = new ShipAI3D(n * 13 + carrier.owner);
				f.rot.set(carrier.rot);
				float ring = carrier.baseRadius * 0.6f + 40f;
				f.pos.set((float) Math.cos(n * 0.9) * ring, 40f + (n % 3) * 15f, (float) Math.sin(n * 0.9) * ring);
				out.add(f);
				n++;
			}
		}
		return out;
	}
}
