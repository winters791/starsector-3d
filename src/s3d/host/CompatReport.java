package s3d.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.loading.ShotBehaviorSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.loading.WithSourceMod;

import s3d.core.ModData;
import s3d.core.ShipSystem3D;

/**
 * Written at game start to saves/common/s3d_compatibility.txt.data: for vanilla and each enabled mod,
 * the ship systems, weapon scripts and hulls 3D doesn't handle (and what it guessed or was told),
 * plus what the mods' data/config/s3d files said and any problems in them. A modder fixes what's
 * listed with those files; MODDING.md is the reference.
 */
final class CompatReport {
	private static final Logger log = Global.getLogger(CompatReport.class);
	static final String FILE = "s3d_compatibility.txt";
	/** writeTextFileToCommon takes at most 1 MB. */
	private static final int MAX_CHARS = 900_000;
	private static final String VANILLA = "vanilla";

	/** One mod's (or vanilla's) findings. */
	private static final class Section {
		final String title;
		final List<String> systems = new ArrayList<>(), weapons = new ArrayList<>(), hulls = new ArrayList<>();
		int systemCount, systemGaps, weaponCount, weaponGaps, hullCount, hullGaps;

		Section(String title) {
			this.title = title;
		}

		boolean empty() {
			return systemCount + weaponCount + hullCount == 0;
		}
	}

	private final ModData data;
	private final Map<String, Section> sections = new LinkedHashMap<>();
	/** System id -> hulls that have it (system or right-click), for "on: ..." lines. */
	private final Map<String, List<String>> systemUsers = new HashMap<>();
	/** Script class names (full and simple) some weapon uses, to spot weapon_effects.csv rows that match nothing. */
	private final Set<String> scriptsSeen = new HashSet<>();
	private final List<String> rowProblems = new ArrayList<>();
	private int weaponFilesMissing;

	private CompatReport(ModData data) {
		this.data = data;
		sections.put(VANILLA, new Section("Vanilla"));
		for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) {
			sections.put(m.getId(), new Section(m.getName() + " (" + m.getId() + ")"));
		}
	}

	/** Builds the report and writes it; never throws (it must not break game start). */
	static void write() {
		long t0 = System.nanoTime();
		try {
			CompatReport r = new CompatReport(ModDataLoader.reload());
			r.scan();
			String text = r.text();
			if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "\n... (cut here: the file can be at most 1 MB)\n";
			Global.getSettings().writeTextFileToCommon(FILE, text);
			log.info(String.format("S3D: compatibility report: %s -> saves/common/%s.data (%.0f ms)", r.summary(), FILE,
					(System.nanoTime() - t0) / 1e6));
		} catch (Throwable t) {
			log.warn("S3D: couldn't write the compatibility report", t);
		}
	}

	private void scan() {
		for (ShipHullSpecAPI h : Global.getSettings().getAllShipHullSpecs()) {
			if (h.isDHull()) continue; // (D) hulls share their base hull's system and weapons
			for (String id : new String[] {h.getShipSystemId(), h.getShipDefenseId()}) {
				if (id != null && !id.isEmpty()) systemUsers.computeIfAbsent(id, k -> new ArrayList<>()).add(h.getHullId());
			}
		}
		for (ShipSystemSpecAPI s : Global.getSettings().getAllShipSystemSpecs()) system(s);
		for (WeaponSpecAPI w : Global.getSettings().getAllWeaponSpecs()) weapon(w);
		for (ShipHullSpecAPI h : Global.getSettings().getAllShipHullSpecs()) {
			if (!h.isDHull()) hull(h);
		}
		checkRows();
	}

	// ---- ship systems ----

	private void system(ShipSystemSpecAPI s) {
		Section sec = section(s);
		sec.systemCount++;
		String name = s.getId() + " \"" + s.getName() + "\"";
		ModData.SystemRow row = data.system(s.getId());
		if (row != null) {
			sec.systems.add("  told      " + name + " -> " + (row.ignored() ? "left out on purpose" : row.kind.name()
					+ (row.params != null ? " " + row.params : "")) + " (" + row.mod + "'s systems.csv)");
			return;
		}
		JSONObject j = s.getSpecJson();
		String type = j != null ? j.optString("type", null) : null, ai = j != null ? j.optString("aiType", null) : null;
		boolean cloak = s.isPhaseCloak() || "PHASE_CLOAK".equals(type);
		if (ShipSystem3D.kindFor(s.getId(), cloak) != ShipSystem3D.Kind.UNSUPPORTED) return; // rebuilt in 3D by id
		ShipSystem3D.Kind k = ShipSystem3D.kindFor(s.getId(), cloak, type, ai);
		if (k != ShipSystem3D.Kind.UNSUPPORTED) {
			sec.systems.add("  guessed   " + name + " -> " + k + " (from its type " + type + (ai != null ? " / AI " + ai : "") + ")" + users(s.getId()));
			return;
		}
		sec.systemGaps++;
		String script = s.getStatsScriptClassName();
		sec.systems.add("  NOT IN 3D " + name + " (type " + type + (ai != null ? ", AI " + ai : "") + (script != null && !script.isEmpty() ? ", " + script : "")
				+ ")" + users(s.getId()));
	}

	private String users(String systemId) {
		List<String> on = systemUsers.get(systemId);
		if (on == null || on.isEmpty()) return "";
		int shown = Math.min(on.size(), 6);
		return "; on " + String.join(", ", on.subList(0, shown)) + (on.size() > shown ? " (+" + (on.size() - shown) + ")" : "");
	}

	/** True if 3D does something with this system (by id, by its type, or a mod's row says what). */
	private boolean systemHandled(String id) {
		ShipSystemSpecAPI s = id != null && !id.isEmpty() ? systemSpec(id) : null;
		if (s == null) return true; // not a system (a shield)
		ModData.SystemRow row = data.system(id);
		if (row != null) return true;
		JSONObject j = s.getSpecJson();
		String type = j != null ? j.optString("type", null) : null;
		boolean cloak = s.isPhaseCloak() || "PHASE_CLOAK".equals(type);
		return ShipSystem3D.kindFor(id, cloak, type, j != null ? j.optString("aiType", null) : null) != ShipSystem3D.Kind.UNSUPPORTED;
	}

	// ---- weapons ----

	private void weapon(WeaponSpecAPI w) {
		Section sec = section(w);
		sec.weaponCount++;
		List<String> gaps = new ArrayList<>(), told = new ArrayList<>();
		boolean decorative = w.getType() == WeaponType.DECORATIVE;
		if (w.isBeam()) {
			if (w instanceof BeamWeaponSpecAPI) script(((BeamWeaponSpecAPI) w).getEffectClassName(), "beam", gaps, told, decorative);
			else gaps.add("not fired: a beam without beam data");
		} else if (!(w instanceof ProjectileWeaponSpecAPI)) {
			gaps.add("not fired: not a projectile or beam weapon");
		} else {
			Object ps = ((ProjectileWeaponSpecAPI) w).getProjectileSpec();
			if (ps instanceof MissileSpecAPI) {
				MissileSpecAPI ms = (MissileSpecAPI) ps;
				if (ms.getHullSpec() == null || ms.getHullSpec().getEngineSpec() == null) gaps.add("not fired: a missile without engine data");
				script(ms.getOnHitClassName(), "on-hit", gaps, told, decorative);
				script(ms.getOnFireClassName(), "on-fire", gaps, told, decorative);
				String onFire = ms.getOnFireClassName();
				behaviour(ms.getBehaviorSpec(), onFire != null && onFire.endsWith(".DEMEffect"), gaps);
			} else if (ps instanceof ProjectileSpecAPI) {
				ProjectileSpecAPI p = (ProjectileSpecAPI) ps;
				script(p.getOnHitClassName(), "on-hit", gaps, told, decorative);
				script(p.getOnFireClassName(), "on-fire", gaps, told, decorative);
				behaviour(p.getBehaviorSpec(), false, gaps);
			} else {
				gaps.add("not fired: unknown projectile type");
			}
		}
		script(everyFrameScript(w.getWeaponId()), "every-frame", gaps, told, decorative);
		String name = w.getWeaponId() + " \"" + w.getWeaponName() + "\"";
		if (!gaps.isEmpty()) {
			sec.weaponGaps++;
			sec.weapons.add("  GAP       " + name + ": " + String.join("; ", gaps));
		}
		for (String t : told) sec.weapons.add("  told      " + name + ": " + t);
	}

	/** One script: fine, told by a row (noted in told), or a gap. */
	private void script(String className, String where, List<String> gaps, List<String> told, boolean decorative) {
		if (className == null || className.isEmpty()) return;
		String simple = ModData.simpleName(className);
		scriptsSeen.add(className);
		scriptsSeen.add(simple);
		ModData.EffectRow row = data.effect(className);
		if (row != null) {
			if (row.vanillaScript != null && !knownScript(row.vanillaScript)) {
				gaps.add(where + " script " + simple + ": " + row.mod + "'s weapon_effects.csv maps it to " + row.vanillaScript
						+ ", which 3D doesn't know");
			} else {
				told.add(where + " script " + simple + " -> " + row + " (" + row.mod + "'s weapon_effects.csv)");
			}
			return;
		}
		if (knownScript(simple)) return;
		gaps.add(where + " script " + className + " not modelled" + (decorative ? " (a decorative weapon: probably VISUAL)" : ""));
	}

	private static boolean knownScript(String simple) {
		return ShipBridge.MODELLED_EFFECTS.contains(simple) || ShipBridge.VISUAL_EFFECTS.contains(simple) || ExoticBridge.EFFECTS.contains(simple);
	}

	/** Shot behaviours ShipBridge.behaviour() rebuilds: proximity fuses, MIRVs, and DEM missiles (CUSTOM with the DEM script). */
	private static void behaviour(ShotBehaviorSpecAPI b, boolean dem, List<String> gaps) {
		if (b == null || b.getBehavorString() == null) return;
		String kind = b.getBehavorString();
		if ("PROXIMITY_FUSE".equals(kind) || "MIRV".equals(kind) || "CUSTOM".equals(kind) && dem) return;
		gaps.add(kind + " behaviour not modelled");
	}

	/** The weapon's every-frame script, from its .wpn file (the spec doesn't say); null if none or the file isn't where its id says. */
	private String everyFrameScript(String weaponId) {
		try {
			JSONObject j = Global.getSettings().loadJSON("data/weapons/" + weaponId + ".wpn");
			String s = j.optString("everyFrameEffect", null);
			return s != null && !s.isEmpty() ? s : null;
		} catch (Throwable notThere) {
			weaponFilesMissing++;
			return null;
		}
	}

	// ---- hulls ----

	private void hull(ShipHullSpecAPI h) {
		Section sec = section(h);
		sec.hullCount++;
		String name = h.getHullId() + " \"" + h.getHullName() + "\"";
		ModData.HullRow row = ModDataLoader.hullRow(h);
		if (row != null) sec.hulls.add("  told      " + name + ": battles with it stay " + (row.vanilla2d ? "2D" : "3D") + " (" + row.mod + "'s hulls.csv)");
		List<String> gaps = new ArrayList<>();
		if (!systemHandled(h.getShipSystemId())) gaps.add("system " + h.getShipSystemId() + " not in 3D");
		if (!systemHandled(h.getShipDefenseId())) gaps.add("right-click system " + h.getShipDefenseId() + " not in 3D");
		if (!gaps.isEmpty()) {
			sec.hullGaps++;
			sec.hulls.add("  GAP       " + name + ": " + String.join("; ", gaps));
		}
	}

	// ---- the data files themselves ----

	private void checkRows() {
		for (ModData.SystemRow r : data.systems()) {
			if (systemSpec(r.id) == null) rowProblems.add(r.mod + ": systems.csv: " + r.id + ": no ship system has this id");
		}
		for (ModData.HullRow r : data.hulls()) {
			if (hullSpec(r.id) == null) rowProblems.add(r.mod + ": hulls.csv: " + r.id + ": no hull has this id");
		}
		for (ModData.EffectRow r : data.effects()) {
			if (!scriptsSeen.contains(r.script)) rowProblems.add(r.mod + ": weapon_effects.csv: " + r.script + ": no weapon uses this script");
			if (r.vanillaScript != null && !knownScript(r.vanillaScript)) rowProblems.add(r.mod + ": weapon_effects.csv: " + r.script
					+ ": " + r.vanillaScript + " is neither an effect nor a script 3D knows");
		}
	}

	private static ShipSystemSpecAPI systemSpec(String id) {
		try {
			return Global.getSettings().getShipSystemSpec(id);
		} catch (Throwable none) {
			return null;
		}
	}

	private static ShipHullSpecAPI hullSpec(String id) {
		try {
			return Global.getSettings().getHullSpec(id);
		} catch (Throwable none) {
			return null;
		}
	}

	private Section section(WithSourceMod spec) {
		ModSpecAPI m = spec.getSourceMod();
		String key = m != null ? m.getId() : VANILLA;
		return sections.computeIfAbsent(key, k -> new Section(m != null ? m.getName() + " (" + k + ")" : "Vanilla"));
	}

	// ---- output ----

	private String summary() {
		int sys = 0, sysGaps = 0, weapons = 0, weaponGaps = 0, hulls = 0, hullGaps = 0;
		for (Section s : sections.values()) {
			sys += s.systemCount;
			sysGaps += s.systemGaps;
			weapons += s.weaponCount;
			weaponGaps += s.weaponGaps;
			hulls += s.hullCount;
			hullGaps += s.hullGaps;
		}
		return String.format("%d ship systems (%d not in 3D), %d weapons (%d with gaps), %d hulls (%d with gaps), %d data-file problems",
				sys, sysGaps, weapons, weaponGaps, hulls, hullGaps, data.problems.size() + rowProblems.size());
	}

	private String text() {
		StringBuilder b = new StringBuilder();
		b.append("Starsector 3D ").append(S3DModPlugin.VERSION).append(" compatibility report, game ").append(Global.getSettings().getVersionString())
				.append(", written ").append(java.time.LocalDateTime.now().withNano(0)).append(" at game start.\n");
		b.append("What 3D doesn't handle, per mod. A mod (or a small patch mod) can fill the gaps with\n");
		b.append("data/config/s3d/systems.csv, weapon_effects.csv and hulls.csv: see mods/Starsector3D/MODDING.md.\n");
		b.append("NOT IN 3D = the system does nothing in 3D. GAP = part of the weapon or hull isn't in 3D (it still fights).\n");
		b.append("guessed = 3D picked a kind from the system's type: check it plays right. told = a data file said so.\n\n");
		b.append("Total: ").append(summary()).append(".\n\n");

		b.append("Enabled mods, in load order:\n");
		for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) {
			b.append("  ").append(m.getId()).append(" \"").append(m.getName()).append("\" ").append(m.getVersion()).append('\n');
		}
		b.append("\nData files read:\n");
		if (data.read.isEmpty()) b.append("  none (no enabled mod has data/config/s3d/*.csv rows)\n");
		for (String r : data.read) b.append("  ").append(r).append('\n');
		List<String> problems = new ArrayList<>(data.problems);
		problems.addAll(rowProblems);
		b.append("\nProblems in data files:\n");
		if (problems.isEmpty()) b.append("  none\n");
		for (String p : problems) b.append("  ").append(p).append('\n');
		if (weaponFilesMissing > 0) {
			b.append("\n").append(weaponFilesMissing).append(" weapons have no data/weapons/<id>.wpn file, so their every-frame scripts weren't checked.\n");
		}

		for (Section s : sections.values()) {
			if (s.empty()) continue;
			b.append("\n== ").append(s.title).append(" ==\n");
			part(b, "Ship systems", s.systemCount, s.systemGaps, "not in 3D", s.systems);
			part(b, "Weapons", s.weaponCount, s.weaponGaps, "with gaps", s.weapons);
			part(b, "Hulls", s.hullCount, s.hullGaps, "with gaps", s.hulls);
		}
		return b.toString();
	}

	private static void part(StringBuilder b, String what, int count, int gaps, String gapWord, List<String> lines) {
		if (count == 0) return;
		b.append(what).append(": ").append(count).append(", ").append(gaps == 0 ? "all in 3D" : gaps + " " + gapWord).append('\n');
		lines.sort(null);
		for (String l : lines) b.append(l).append('\n');
	}
}
