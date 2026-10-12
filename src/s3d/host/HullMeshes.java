package s3d.host;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;

import s3d.render.ObjHull;
import s3d.render.SpriteImage;

/**
 * Finds hand-made hull models: {@code data/meshes/hulls/<hullId>.obj}, looked up through the game's
 * resource loader (so another mod can ship models for its own hulls, or replace ours). A skin or
 * (D) hull without its own file uses its base hull's model, painted with its own sprite.
 */
final class HullMeshes {
	private static final Logger log = Global.getLogger(HullMeshes.class);
	static final String DIR = "data/meshes/hulls/";

	/** Path -> parsed model, or null when there is no such file. Cleared by {@link #reload()}. */
	private static final Map<String, ObjHull> FILES = new HashMap<>();
	private static final Set<String> LOGGED = new java.util.HashSet<>();

	private HullMeshes() {}

	/** The model for this hull, or null to use the sprite relief. */
	static ObjHull find(ShipHullSpecAPI spec, SpriteImage sprite) {
		if (spec == null) return null;
		Set<String> ids = new LinkedHashSet<>();
		ids.add(spec.getHullId());
		ids.add(spec.getBaseHullId());
		ids.add(spec.getDParentHullId());
		if (spec.getDParentHull() != null) ids.add(spec.getDParentHull().getBaseHullId());
		for (String id : ids) {
			if (id == null || id.isEmpty()) continue;
			ObjHull obj = load(DIR + id + ".obj");
			if (obj == null) continue;
			if (!obj.fits(sprite)) {
				if (LOGGED.add(spec.getHullId() + "|unfit"))
					log.warn("S3D: " + obj.name + " was made for a " + obj.spriteW + "x" + obj.spriteH + " sprite but "
							+ spec.getHullId() + "'s is " + sprite.w + "x" + sprite.h + "; using the relief");
				return null;
			}
			if (LOGGED.add(spec.getHullId() + "|" + obj.name))
				log.info("S3D: " + spec.getHullId() + " uses the model " + obj.name + " (" + obj.triangles() + " triangles)");
			return obj;
		}
		return null;
	}

	private static ObjHull load(String path) {
		if (FILES.containsKey(path)) return FILES.get(path);
		ObjHull obj = null;
		String text = null;
		try {
			text = Global.getSettings().loadText(path);
		} catch (Throwable missing) {
			// no model for this hull
		}
		if (text != null) {
			try {
				obj = ObjHull.parse(path, text);
				if (obj.triangles() == 0) {
					log.warn("S3D: " + path + " has no faces; ignored");
					obj = null;
				}
			} catch (RuntimeException e) {
				log.warn("S3D: can't read " + path + " (" + e + "); using the relief");
			}
		}
		FILES.put(path, obj);
		return obj;
	}

	/**
	 * Re-reads every model file looked up so far (and checks again for ones that were missing).
	 * @return true when anything changed, so meshes need rebuilding
	 */
	static boolean reload() {
		Map<String, ObjHull> old = new HashMap<>(FILES);
		FILES.clear();
		LOGGED.clear();
		boolean changed = false;
		for (Map.Entry<String, ObjHull> e : old.entrySet()) {
			ObjHull now = load(e.getKey());
			ObjHull was = e.getValue();
			if ((now == null) != (was == null) || now != null && now.hash != was.hash) {
				changed = true;
				log.info("S3D: " + e.getKey() + (now == null ? " is gone" : was == null ? " appeared" : " changed"));
			}
		}
		return changed;
	}
}
