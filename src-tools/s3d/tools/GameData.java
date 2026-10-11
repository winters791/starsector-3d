package s3d.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import s3d.core.EngineMount;
import s3d.core.Tuning;

/**
 * Dev-tool only: reads hull numbers and sprite info straight from the local install at runtime
 * (nothing is copied into the mod). In-game the mod uses the API's spec objects instead.
 */
final class GameData {

	static final class Hull {
		String id, spritePath;
		float speed, accel, decel, turn, turnAccel;
		float centerX, centerYFromBottom;
		String sizeClass = "frigate";
		float maxFlux, dissipation, shieldArc, shieldUpkeep, shieldEff = 1f, shieldRadius, mass;
		String shieldType = "NONE";
		final List<EngineMount> engines = new ArrayList<>();
	}

	private GameData() {}

	static Tuning modTuning(File buildDir) throws Exception {
		File f = new File(buildDir.getParentFile(), "data/config/s3d_tuning.json");
		return Tuning.fromJson(new JSONObject(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
	}

	static Map<String, Hull> hulls(File gameJavaDir) throws Exception {
		Map<String, Hull> out = new HashMap<>();
		List<String> lines = Files.readAllLines(new File(gameJavaDir, "data/hulls/ship_data.csv").toPath(), StandardCharsets.UTF_8);
		List<String> header = csv(lines.get(0));
		int iId = header.indexOf("id"), iSpeed = header.indexOf("max speed"), iAcc = header.indexOf("acceleration"),
				iDec = header.indexOf("deceleration"), iTurn = header.indexOf("max turn rate"), iTacc = header.indexOf("turn acceleration");
		for (int i = 1; i < lines.size(); i++) {
			List<String> r = csv(lines.get(i));
			if (r.size() <= iTacc || r.get(iId).isEmpty() || r.get(iSpeed).isEmpty()) continue;
			try {
				Hull h = new Hull();
				h.id = r.get(iId);
				h.speed = Float.parseFloat(r.get(iSpeed));
				h.accel = Float.parseFloat(r.get(iAcc));
				h.decel = Float.parseFloat(r.get(iDec));
				h.turn = Float.parseFloat(r.get(iTurn));
				h.turnAccel = Float.parseFloat(r.get(iTacc));
				h.maxFlux = num(r, header.indexOf("max flux"));
				h.mass = num(r, header.indexOf("mass"));
				h.dissipation = num(r, header.indexOf("flux dissipation"));
				int iType = header.indexOf("shield type");
				if (iType >= 0 && iType < r.size() && !r.get(iType).isEmpty()) h.shieldType = r.get(iType);
				h.shieldArc = num(r, header.indexOf("shield arc"));
				h.shieldUpkeep = num(r, header.indexOf("shield upkeep"));
				h.shieldEff = num(r, header.indexOf("shield efficiency"));
				out.put(h.id, h);
			} catch (NumberFormatException ignored) {
				// header-like or incomplete rows
			}
		}
		return out;
	}

	private static float num(List<String> r, int i) {
		if (i < 0 || i >= r.size() || r.get(i).isEmpty()) return 0f;
		try {
			return Float.parseFloat(r.get(i));
		} catch (NumberFormatException e) {
			return 0f;
		}
	}

	/** Fills sprite path, centre and engine slots from data/hulls/&lt;id&gt;.ship. */
	static void readShipFile(File gameJavaDir, Hull h) throws Exception {
		String text = Files.readString(new File(gameJavaDir, "data/hulls/" + h.id + ".ship").toPath(), StandardCharsets.UTF_8);
		StringBuilder sb = new StringBuilder();
		for (String line : text.split("\n")) {
			int hash = line.indexOf('#');
			sb.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
		}
		JSONObject o = new JSONObject(sb.toString());
		h.spritePath = o.getString("spriteName");
		switch (o.optString("hullSize", "FRIGATE")) {
			case "DESTROYER": h.sizeClass = "destroyer"; break;
			case "CRUISER": h.sizeClass = "cruiser"; break;
			case "CAPITAL_SHIP": h.sizeClass = "capital"; break;
			case "FIGHTER": h.sizeClass = "fighter"; break;
			default: h.sizeClass = "frigate";
		}
		JSONArray c = o.getJSONArray("center");
		h.centerX = (float) c.getDouble(0);
		h.centerYFromBottom = (float) c.getDouble(1);
		h.shieldRadius = (float) o.optDouble("shieldRadius", 0);
		JSONArray engines = o.optJSONArray("engineSlots");
		if (engines != null) {
			for (int i = 0; i < engines.length(); i++) {
				JSONObject e = engines.getJSONObject(i);
				JSONArray loc = e.getJSONArray("location");
				h.engines.add(EngineMount.fromShipSpace((float) loc.getDouble(0), (float) loc.getDouble(1),
						(float) e.optDouble("angle", 180), (float) e.optDouble("length", 30), (float) e.optDouble("width", 10),
						1f, 0.55f, 0.3f));
			}
		}
	}

	static final class Slot {
		String id, mount, type, size;
		float x, y, angle, arc;
	}

	static JSONObject lenientJson(File f) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (String line : Files.readString(f.toPath(), StandardCharsets.UTF_8).split("\n")) {
			int hash = line.indexOf('#');
			sb.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
		}
		return new JSONObject(sb.toString());
	}

	static List<Slot> slots(File game, String hullId) throws Exception {
		List<Slot> out = new ArrayList<>();
		JSONArray arr = lenientJson(new File(game, "data/hulls/" + hullId + ".ship")).optJSONArray("weaponSlots");
		if (arr == null) return out;
		for (int i = 0; i < arr.length(); i++) {
			JSONObject o = arr.getJSONObject(i);
			Slot s = new Slot();
			s.id = o.getString("id");
			s.mount = o.optString("mount", "TURRET");
			s.type = o.optString("type", "");
			s.size = o.optString("size", "SMALL");
			s.angle = (float) o.optDouble("angle", 0);
			s.arc = (float) o.optDouble("arc", 0);
			JSONArray loc = o.getJSONArray("locations");
			s.x = (float) loc.getDouble(0);
			s.y = (float) loc.getDouble(1);
			out.add(s);
		}
		return out;
	}

	/** A variant's hull id. */
	static String variantHull(File game, String variantId) throws Exception {
		File f = findFile(new File(game, "data/variants"), variantId + ".variant");
		return f != null ? lenientJson(f).optString("hullId", null) : null;
	}

	/** slotId -> module variant id, from a station or multi-module variant. */
	static Map<String, String> variantModules(File game, String variantId) throws Exception {
		Map<String, String> out = new java.util.LinkedHashMap<>();
		File f = findFile(new File(game, "data/variants"), variantId + ".variant");
		if (f == null) return out;
		JSONArray mods = lenientJson(f).optJSONArray("modules");
		if (mods == null) return out;
		for (int i = 0; i < mods.length(); i++) {
			JSONObject m = mods.getJSONObject(i);
			for (java.util.Iterator<?> it = m.keys(); it.hasNext(); ) {
				String k = (String) it.next();
				out.put(k, m.getString(k));
			}
		}
		return out;
	}

	/** A module hull's attachment point (ship coords, relative to its centre), or (0, 0). */
	static float[] moduleAnchor(File game, String hullId) throws Exception {
		JSONArray a = lenientJson(new File(game, "data/hulls/" + hullId + ".ship")).optJSONArray("moduleAnchor");
		return a != null ? new float[] {(float) a.getDouble(0), (float) a.getDouble(1)} : new float[2];
	}

	/** slotId -> weaponId from a variant's weapon groups. */
	static Map<String, String> variantWeapons(File game, String variantId) throws Exception {
		Map<String, String> out = new HashMap<>();
		File f = findFile(new File(game, "data/variants"), variantId + ".variant");
		if (f == null) return out;
		JSONArray groups = lenientJson(f).optJSONArray("weaponGroups");
		if (groups == null) return out;
		for (int i = 0; i < groups.length(); i++) {
			JSONObject w = groups.getJSONObject(i).optJSONObject("weapons");
			if (w == null) continue;
			for (java.util.Iterator<?> it = w.keys(); it.hasNext(); ) {
				String k = (String) it.next();
				out.put(k, w.getString(k));
			}
		}
		return out;
	}

	/** Variant weapon groups as {autofire, alternating, slotIds...}. */
	static List<Object[]> variantGroups(File game, String variantId) throws Exception {
		List<Object[]> out = new ArrayList<>();
		File f = findFile(new File(game, "data/variants"), variantId + ".variant");
		if (f == null) return out;
		JSONArray groups = lenientJson(f).optJSONArray("weaponGroups");
		if (groups == null) return out;
		for (int i = 0; i < groups.length(); i++) {
			JSONObject g = groups.getJSONObject(i);
			JSONObject w = g.optJSONObject("weapons");
			List<String> slots = new ArrayList<>();
			if (w != null) for (java.util.Iterator<?> it = w.keys(); it.hasNext(); ) slots.add((String) it.next());
			out.add(new Object[] {g.optBoolean("autofire", false), "ALTERNATING".equals(g.optString("mode")), slots});
		}
		return out;
	}

	static JSONObject weapon(File game, String weaponId) throws Exception {
		return lenientJson(new File(game, "data/weapons/" + weaponId + ".wpn"));
	}

	private static File findFile(File dir, String name) {
		File[] kids = dir.listFiles();
		if (kids == null) return null;
		for (File k : kids) {
			if (k.isDirectory()) {
				File f = findFile(k, name);
				if (f != null) return f;
			} else if (k.getName().equals(name)) {
				return k;
			}
		}
		return null;
	}

	static List<String> csv(String line) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean q = false;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == '"') q = !q;
			else if (ch == ',' && !q) {
				out.add(cur.toString().trim());
				cur.setLength(0);
			} else cur.append(ch);
		}
		out.add(cur.toString().trim());
		return out;
	}
}
