package s3d.host;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.json.JSONArray;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;

import s3d.core.ModData;

/**
 * Reads data/config/s3d/systems.csv, weapon_effects.csv and hulls.csv from every enabled mod, one mod
 * at a time in load order (the game's own spreadsheet merge picks between mods "in an undefined
 * manner", and we want to say whose row won). At game start and again as each 3D battle starts, so
 * a modder can edit the files without restarting.
 */
final class ModDataLoader {
	private static final Logger log = Global.getLogger(ModDataLoader.class);
	private static ModData data;

	private ModDataLoader() {}

	/** The rows read last (reads them the first time). */
	static ModData data() {
		if (data == null) reload();
		return data;
	}

	static ModData reload() {
		ModData d = new ModData();
		Map<String, String> seen = new HashMap<>(); // file|content -> mod, in case a per-mod read ever falls back to another mod's copy
		try {
			for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) {
				String id = m.getId();
				d.addSystems(id, csv(ModData.SYSTEMS, id, seen));
				d.addEffects(id, csv(ModData.EFFECTS, id, seen));
				d.addHulls(id, csv(ModData.HULLS, id, seen));
			}
		} catch (Throwable t) {
			log.warn("S3D: couldn't read the mods' data/config/s3d files", t);
		}
		for (String r : d.read) log.info("S3D: mod data: " + r);
		for (String p : d.problems) log.warn("S3D: mod data: " + p);
		data = d;
		return d;
	}

	/** The hulls.csv row for this hull, or for the hull it's a skin or (D) version of; null if none. */
	static ModData.HullRow hullRow(ShipHullSpecAPI spec) {
		if (spec == null) return null;
		ModData d = data();
		ModData.HullRow r = d.hull(spec.getHullId());
		if (r == null) r = d.hull(spec.getBaseHullId());
		if (r == null) r = d.hull(spec.getDParentHullId());
		if (r == null && spec.getDParentHull() != null) r = d.hull(spec.getDParentHull().getBaseHullId());
		return r;
	}

	private static JSONArray csv(String path, String modId, Map<String, String> seen) {
		JSONArray rows;
		try {
			rows = Global.getSettings().loadCSV(path, modId);
		} catch (Throwable missing) {
			return null; // this mod has no such file
		}
		if (rows == null || rows.length() == 0) return rows;
		String key = path + "|" + rows.toString();
		String first = seen.putIfAbsent(key, modId);
		if (first != null) {
			log.info("S3D: mod data: " + modId + "'s " + path + " is the same as " + first + "'s; read once");
			return null;
		}
		return rows;
	}
}
