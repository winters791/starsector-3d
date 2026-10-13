package s3d.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What other mods tell 3D about their own content: data/config/s3d/systems.csv, weapon_effects.csv
 * and hulls.csv, read from each enabled mod in load order (a later mod's row replaces an earlier
 * one's). Pure data: the host reads the files, the bench feeds rows in directly. The column and
 * value reference for modders is MODDING.md in the mod folder.
 */
public final class ModData {
	public static final String SYSTEMS = "data/config/s3d/systems.csv";
	public static final String EFFECTS = "data/config/s3d/weapon_effects.csv";
	public static final String HULLS = "data/config/s3d/hulls.csv";

	/** Weapon effects a row can give a script, with their numbers. A name that isn't one of these is a vanilla script to copy. */
	public enum Effect {
		/** EMP arcs that can go through a shield, more likely the more hard flux it holds (Ion Cannon). Uses the weapon's EMP. */
		EMP_ARCS,
		/** amount: armor stripped round a hull hit, on top of the damage (Breach). */
		ARMOR_STRIP,
		/** amount: armor burned away where it hit, over ticks (default 10) about a second apart (Disintegrator). */
		ARMOR_BURN,
		/** amount: extra damage when the missile hits a shield (Squall). Missiles only. */
		SHIELD_BONUS,
		/** chance (0-1) of an extra EMP arc on a hull hit; pierce=1 lets it go through shields at high hard flux (Sabot, Pilum). Uses the weapon's EMP. */
		EXTRA_ARC,
		/** cone: no shell, each shot is an instant arc to the nearest enemy within this cone, full angle in degrees (Shock Repeater). */
		ARC_GUN,
		/** Looks only: nothing to do in 3D, and it isn't reported as missing. */
		VISUAL,
		/** Leave it out on purpose: not modelled, and not reported as missing. */
		IGNORE
	}

	/** A ship system another mod mapped: the 3D kind it plays as (null = IGNORE), and its own numbers. */
	public static final class SystemRow {
		public final String id, mod;
		public final ShipSystem3D.Kind kind;
		/** Keys of s3d_tuning.json's "systems" section, or null. */
		public final JSONObject params;
		private Tuning base, tuned;

		SystemRow(String id, String mod, ShipSystem3D.Kind kind, JSONObject params) {
			this.id = id;
			this.mod = mod;
			this.kind = kind;
			this.params = params;
		}

		public boolean ignored() {
			return kind == null;
		}

		/** The battle's tuning with this row's numbers on top (null if it has none). Rebuilt when the tuning reloads. */
		public Tuning tuningFor(Tuning t) {
			if (params == null || t == null) return null;
			if (base != t) {
				tuned = t.withSystems(params);
				base = t;
			}
			return tuned;
		}

		@Override
		public String toString() {
			return (kind != null ? kind.name() : "IGNORE") + (params != null ? " " + params : "");
		}
	}

	/** A weapon script another mod described: one of the effects, or (vanillaScript) the 3D version of a vanilla one. */
	public static final class EffectRow {
		public final String script, mod;
		public final Effect effect;
		/** Set instead of effect: the simple class name of a vanilla script whose 3D version this gets. */
		public final String vanillaScript;
		public final Map<String, Float> params;

		EffectRow(String script, String mod, Effect effect, String vanillaScript, Map<String, Float> params) {
			this.script = script;
			this.mod = mod;
			this.effect = effect;
			this.vanillaScript = vanillaScript;
			this.params = params;
		}

		public float num(String key, float def) {
			Float v = params.get(key);
			return v != null ? v : def;
		}

		@Override
		public String toString() {
			return (effect != null ? effect.name() : "as " + vanillaScript) + (params.isEmpty() ? "" : " " + params);
		}
	}

	/** A hull another mod flagged: battles with it stay vanilla 2D. */
	public static final class HullRow {
		public final String id, mod;
		public final boolean vanilla2d;

		HullRow(String id, String mod, boolean vanilla2d) {
			this.id = id;
			this.mod = mod;
			this.vanilla2d = vanilla2d;
		}
	}

	private final Map<String, SystemRow> systems = new LinkedHashMap<>();
	private final Map<String, EffectRow> effects = new LinkedHashMap<>();
	private final Map<String, HullRow> hulls = new LinkedHashMap<>();
	/** Rows that couldn't be used, or were replaced by a later mod's: "mod: file: what", for the log and the report. */
	public final List<String> problems = new ArrayList<>();
	/** Rows read per mod and file ("mod: systems.csv 3 rows"), for the report. */
	public final List<String> read = new ArrayList<>();
	private static Set<String> systemKeys;

	public SystemRow system(String id) {
		return id != null ? systems.get(id) : null;
	}

	/** A row for this script's full class name, else for its simple name. */
	public EffectRow effect(String className) {
		if (className == null || className.isEmpty()) return null;
		EffectRow r = effects.get(className);
		return r != null ? r : effects.get(simpleName(className));
	}

	public HullRow hull(String id) {
		return id != null ? hulls.get(id) : null;
	}

	public java.util.Collection<SystemRow> systems() {
		return systems.values();
	}

	public java.util.Collection<EffectRow> effects() {
		return effects.values();
	}

	public java.util.Collection<HullRow> hulls() {
		return hulls.values();
	}

	public boolean isEmpty() {
		return systems.isEmpty() && effects.isEmpty() && hulls.isEmpty();
	}

	public static String simpleName(String className) {
		return className.substring(className.lastIndexOf('.') + 1);
	}

	/** systems.csv rows: id, kind, params ("burnDriveSpeed=450 burnDriveTurnMult=0.7"). */
	public void addSystems(String mod, JSONArray rows) {
		if (systemKeys == null) systemKeys = Tuning.systemKeys();
		int n = 0;
		for (int i = 0; rows != null && i < rows.length(); i++) {
			JSONObject r = rows.optJSONObject(i);
			String id = cell(r, "id");
			if (id == null) continue;
			String where = mod + ": systems.csv: " + id;
			String kindName = cell(r, "kind");
			if (kindName == null) {
				problems.add(where + ": no kind");
				continue;
			}
			ShipSystem3D.Kind kind = null;
			if (!"IGNORE".equalsIgnoreCase(kindName)) {
				try {
					kind = ShipSystem3D.Kind.valueOf(kindName.toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException e) {
					kind = ShipSystem3D.Kind.UNSUPPORTED;
				}
				if (kind == ShipSystem3D.Kind.UNSUPPORTED) {
					problems.add(where + ": unknown kind '" + kindName + "'");
					continue;
				}
			}
			JSONObject params = null;
			Map<String, String> raw = params(cell(r, "params"), where);
			if (!raw.isEmpty()) {
				params = new JSONObject();
				for (Map.Entry<String, String> e : raw.entrySet()) {
					if (!systemKeys.contains(e.getKey())) {
						problems.add(where + ": unknown param '" + e.getKey() + "' (see the \"systems\" section of s3d_tuning.json)");
						continue;
					}
					Float f = number(e.getValue());
					try {
						params.put(e.getKey(), f != null ? (Object) f.doubleValue() : e.getValue());
					} catch (org.json.JSONException ex) {
						problems.add(where + ": bad param '" + e.getKey() + "'");
					}
				}
				if (params.length() == 0) params = null;
			}
			SystemRow old = systems.put(id, new SystemRow(id, mod, kind, params));
			if (old != null) problems.add(where + ": replaces " + old.mod + "'s row");
			n++;
		}
		if (n > 0) read.add(mod + ": systems.csv, " + n + (n == 1 ? " row" : " rows"));
	}

	/** weapon_effects.csv rows: script (full or simple class name), effect (an Effect or a vanilla script's simple name), params. */
	public void addEffects(String mod, JSONArray rows) {
		int n = 0;
		for (int i = 0; rows != null && i < rows.length(); i++) {
			JSONObject r = rows.optJSONObject(i);
			String script = cell(r, "script");
			if (script == null) continue;
			String where = mod + ": weapon_effects.csv: " + script;
			String name = cell(r, "effect");
			if (name == null) {
				problems.add(where + ": no effect");
				continue;
			}
			Effect effect = null;
			String vanilla = null;
			try {
				effect = Effect.valueOf(name.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				if (!name.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
					problems.add(where + ": unknown effect '" + name + "'");
					continue;
				}
				vanilla = simpleName(name);
			}
			Map<String, Float> nums = new LinkedHashMap<>();
			for (Map.Entry<String, String> e : params(cell(r, "params"), where).entrySet()) {
				Float f = number(e.getValue());
				if (f == null) problems.add(where + ": param '" + e.getKey() + "' isn't a number");
				else nums.put(e.getKey(), f);
			}
			String missing = effect != null ? missingParam(effect, nums) : null;
			if (missing != null) {
				problems.add(where + ": " + effect + " needs " + missing);
				continue;
			}
			for (String k : nums.keySet()) {
				if (effect == null || !paramsOf(effect).contains(k)) problems.add(where + ": unknown param '" + k + "' for " + (effect != null ? effect : name));
			}
			EffectRow old = effects.put(script, new EffectRow(script, mod, effect, vanilla, nums));
			if (old != null) problems.add(where + ": replaces " + old.mod + "'s row");
			n++;
		}
		if (n > 0) read.add(mod + ": weapon_effects.csv, " + n + (n == 1 ? " row" : " rows"));
	}

	/** hulls.csv rows: id (a hull id; skins and (D) hulls follow their base hull's row), battle ("2d" or "3d"). */
	public void addHulls(String mod, JSONArray rows) {
		int n = 0;
		for (int i = 0; rows != null && i < rows.length(); i++) {
			JSONObject r = rows.optJSONObject(i);
			String id = cell(r, "id");
			if (id == null) continue;
			String where = mod + ": hulls.csv: " + id;
			String battle = cell(r, "battle");
			if (battle == null || !(battle.equalsIgnoreCase("2d") || battle.equalsIgnoreCase("3d"))) {
				problems.add(where + ": battle should be 2d or 3d, not '" + battle + "'");
				continue;
			}
			HullRow old = hulls.put(id, new HullRow(id, mod, battle.equalsIgnoreCase("2d")));
			if (old != null) problems.add(where + ": replaces " + old.mod + "'s row");
			n++;
		}
		if (n > 0) read.add(mod + ": hulls.csv, " + n + (n == 1 ? " row" : " rows"));
	}

	/**
	 * Puts an effect row's numbers on the weapon. Returns what it does, for the log (null for VISUAL,
	 * IGNORE and vanilla-script rows, which the host handles).
	 */
	public static String apply(EffectRow r, WeaponSpec3D ws) {
		if (r.effect == null) return null;
		switch (r.effect) {
			case EMP_ARCS:
				ws.empArcs = true;
				return "EMP arcs through shields";
			case ARMOR_STRIP:
				ws.armorBreak = r.num("amount", 0f);
				return String.format("strips %.0f armor on a hull hit", ws.armorBreak);
			case ARMOR_BURN:
				ws.armorBurn = r.num("amount", 0f);
				ws.armorBurnTicks = Math.max(1, Math.round(r.num("ticks", 10f)));
				return String.format("burns %.0f armor over %d ticks", ws.armorBurn, ws.armorBurnTicks);
			case SHIELD_BONUS:
				ws.shieldBonus = r.num("amount", 0f);
				return String.format("+%.0f damage on shields", ws.shieldBonus);
			case EXTRA_ARC:
				ws.bonusArcChance = Math.max(0f, Math.min(1f, r.num("chance", 0f)));
				ws.bonusArcPierces = r.num("pierce", 0f) > 0f;
				return String.format("%.0f%% chance of an EMP arc on a hit%s", ws.bonusArcChance * 100f, ws.bonusArcPierces ? " (through shields at high hard flux)" : "");
			case ARC_GUN:
				ws.arcGunConeDeg = r.num("cone", 0f);
				return String.format("arcs to the nearest enemy in a %.0f deg cone", ws.arcGunConeDeg);
			default:
				return null;
		}
	}

	private static List<String> paramsOf(Effect e) {
		switch (e) {
			case ARMOR_STRIP: case SHIELD_BONUS: return List.of("amount");
			case ARMOR_BURN: return List.of("amount", "ticks");
			case EXTRA_ARC: return List.of("chance", "pierce");
			case ARC_GUN: return List.of("cone");
			default: return List.of();
		}
	}

	private static String missingParam(Effect e, Map<String, Float> nums) {
		switch (e) {
			case ARMOR_STRIP: case ARMOR_BURN: case SHIELD_BONUS: return nums.containsKey("amount") ? null : "amount";
			case EXTRA_ARC: return nums.containsKey("chance") ? null : "chance";
			case ARC_GUN: return nums.containsKey("cone") ? null : "cone";
			default: return null;
		}
	}

	/** A trimmed cell, or null if it's empty or a comment row (first cell starting with #). */
	private static String cell(JSONObject r, String col) {
		if (r == null) return null;
		String v = r.optString(col, "").trim();
		if (v.isEmpty() || v.startsWith("#")) return null;
		return v;
	}

	/** "a=1 b=2" (spaces, commas or semicolons between pairs) -> ordered map. */
	private Map<String, String> params(String text, String where) {
		Map<String, String> out = new LinkedHashMap<>();
		if (text == null) return out;
		for (String pair : text.split("[\\s,;]+")) {
			if (pair.isEmpty()) continue;
			int eq = pair.indexOf('=');
			if (eq <= 0 || eq == pair.length() - 1) {
				problems.add(where + ": param '" + pair + "' should be key=value");
				continue;
			}
			out.put(pair.substring(0, eq), pair.substring(eq + 1));
		}
		return out;
	}

	private static Float number(String s) {
		try {
			return Float.parseFloat(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
