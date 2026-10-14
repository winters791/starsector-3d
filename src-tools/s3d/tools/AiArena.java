package s3d.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import s3d.core.Objective3D;
import s3d.core.Order3D;
import s3d.core.Ship3D;
import s3d.core.ShipAI3D;
import s3d.core.Sim;
import s3d.core.Tuning;
import s3d.core.WeaponMount;
import s3d.math.Vec3;

/**
 * AI arena (dev tool): big mixed battles, measured for how the pilots fight rather than who wins.
 * The headline number is idle time: a ship that hasn't fired at an enemy for 3 s, isn't closing on
 * one and isn't retreating, backing off overloaded or venting. Idle ship-time is broken down by what
 * the pilot was doing (ShipAI3D.activity when it has one).
 *
 * Run: MAIN=s3d.tools.AiArena (args: game dir, build dir). Env: S3D_ARENA_SEEDS (default 3),
 * S3D_ARENA_ONLY (scenario name), S3D_ARENA_TIME (seconds, default 300), S3D_ARENA_VERBOSE.
 */
public final class AiArena {
	private static final float DT = 1f / 60f;
	/** No shot at an enemy for this long, not closing, not retreating: idle. */
	private static final float IDLE_AFTER = 3f;
	private static final boolean TRACE = System.getenv("S3D_ARENA_TRACE") != null;
	private static final String WATCH = System.getenv("S3D_ARENA_WATCH");

	public static void main(String[] args) throws Exception {
		File game = new File(args[0]);
		File build = new File(args[1]);
		Tuning t = GameData.modTuning(build);
		Map<String, GameData.Hull> hulls = GameData.hulls(game);
		String tune = System.getenv("S3D_ARENA_TUNE"); // "aiCaution=0.5,aiCohesion=4000": Tuning fields by name
		if (tune != null) for (String kv : tune.split(",")) {
			String[] p = kv.split("=");
			Tuning.class.getField(p[0].trim()).setFloat(t, Float.parseFloat(p[1].trim()));
		}
		int seeds = Integer.parseInt(env("S3D_ARENA_SEEDS", "3"));
		float seconds = Float.parseFloat(env("S3D_ARENA_TIME", "300"));
		String only = System.getenv("S3D_ARENA_ONLY");
		if (System.getenv("S3D_ARENA_AB") != null) {
			ab(game, hulls, t, System.getenv("S3D_ARENA_AB"), seeds, seconds, only);
			return;
		}
		Totals all = new Totals("ALL");
		for (String sc : new String[] {"lines", "admiral", "boss", "kite", "brawl"}) {
			if (only != null && !only.equals(sc)) continue;
			Totals tot = new Totals(sc);
			for (int seed = 0; seed < seeds; seed++) {
				Result r = run(game, hulls, t, sc, seed, seconds);
				tot.add(r);
				all.add(r);
				if (System.getenv("S3D_ARENA_VERBOSE") != null) System.out.println("  " + r.line());
			}
			System.out.println(tot.report());
		}
		if (only == null) System.out.println(all.report());
	}

	static String env(String k, String def) {
		String v = System.getenv(k);
		return v == null || v.isEmpty() ? def : v;
	}

	/** One battle's numbers. */
	static final class Result {
		String scenario;
		int seed;
		long shipFrames, idleFrames, engagedFrames, firingFrames;
		long[] sideIdle = new long[2], sideFrames = new long[2];
		float[] dealt = new float[2];
		int[] lost = new int[2], ships = new int[2];
		float firstKill = -1f, end = -1f, worstIdle;
		String worstIdleShip = "", worstIdleWhy = "";
		final Map<String, Long> idleWhy = new TreeMap<>();

		String line() {
			return String.format("%-8s seed %d: idle %4.1f%%, in reach %3.0f%%, firing %3.0f%%, dealt %6.0f / %6.0f, lost %d/%d of %d/%d, first kill %3.0fs, over at %s, worst idle %.0fs (%s: %s)",
					scenario, seed, 100f * idleFrames / Math.max(1, shipFrames), 100f * engagedFrames / Math.max(1, shipFrames), 100f * firingFrames / Math.max(1, shipFrames),
					dealt[0], dealt[1], lost[0], lost[1], ships[0], ships[1], firstKill, end < 0f ? "-" : String.format("%.0fs", end), worstIdle, worstIdleShip, worstIdleWhy);
		}
	}

	static final class Totals {
		final String name;
		long shipFrames, idleFrames, engagedFrames, firingFrames;
		int battles, decided, kills;
		float killTime, worstIdle, endSum;
		String worst = "";
		final Map<String, Long> why = new TreeMap<>();

		Totals(String name) {
			this.name = name;
		}

		void add(Result r) {
			battles++;
			shipFrames += r.shipFrames;
			idleFrames += r.idleFrames;
			engagedFrames += r.engagedFrames;
			firingFrames += r.firingFrames;
			kills += r.lost[0] + r.lost[1];
			if (r.firstKill >= 0f) killTime += r.firstKill;
			if (r.end >= 0f) {
				decided++;
				endSum += r.end;
			}
			if (r.worstIdle > worstIdle) {
				worstIdle = r.worstIdle;
				worst = String.format("%s seed %d %s: %s", r.scenario, r.seed, r.worstIdleShip, r.worstIdleWhy);
			}
			for (Map.Entry<String, Long> e : r.idleWhy.entrySet()) why.merge(e.getKey(), e.getValue(), Long::sum);
		}

		String report() {
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("%-8s %d battles: IDLE %.1f%% of ship-time, enemy in reach %.0f%%, firing %.0f%%, %d ships destroyed, first kill avg %.0fs, decided %d (avg %.0fs); longest idle %.0fs (%s)%n",
					name, battles, 100f * idleFrames / Math.max(1, shipFrames), 100f * engagedFrames / Math.max(1, shipFrames), 100f * firingFrames / Math.max(1, shipFrames),
					kills, killTime / Math.max(1, battles), decided, endSum / Math.max(1, decided), worstIdle, worst));
			if (!why.isEmpty()) {
				sb.append("         idle by cause:");
				why.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
						.forEach(e -> sb.append(String.format(" %s %.1f%%", e.getKey(), 100f * e.getValue() / Math.max(1, shipFrames))));
			}
			return sb.toString();
		}
	}

	private static Order3D escort(Ship3D of) {
		Order3D o = new Order3D(Order3D.Kind.ESCORT);
		o.ship = of;
		o.label = "DEFEND";
		o.admiral = true;
		return o;
	}

	/**
	 * A/B: the same fleets on both sides, pilots on side `bSide` given the per-pilot settings in spec
	 * ("caution=0.5,aggression=1.5": ShipAI3D fields), every seed fought twice with the sides swapped.
	 */
	static void ab(File game, Map<String, GameData.Hull> hulls, Tuning t, String spec, int seeds, float seconds, String only) throws Exception {
		String[] scs = only != null ? new String[] {only} : new String[] {"brawl", "lines"};
		float[] dealt = new float[2];
		int[] lost = new int[2], ahead = new int[2];
		long[] idle = new long[2], frames = new long[2];
		for (String sc : scs) {
			for (int seed = 0; seed < seeds; seed++) {
				for (int bSide = 0; bSide < 2; bSide++) {
					AB = spec;
					abSide = bSide;
					Result r = run(game, hulls, t, sc, seed, seconds);
					AB = null;
					float dA = r.dealt[1 - bSide], dB = r.dealt[bSide];
					dealt[0] += dA;
					dealt[1] += dB;
					lost[0] += r.lost[1 - bSide];
					lost[1] += r.lost[bSide];
					idle[0] += r.sideIdle[1 - bSide];
					idle[1] += r.sideIdle[bSide];
					frames[0] += r.sideFrames[1 - bSide];
					frames[1] += r.sideFrames[bSide];
					if (dB > dA) ahead[1]++;
					else ahead[0]++;
					System.out.printf("%-6s seed %d, B on side %d: dealt A %6.0f / B %6.0f, lost A %d / B %d, over at %s%n", sc, seed, bSide, dA, dB,
							r.lost[1 - bSide], r.lost[bSide], r.end < 0f ? "-" : String.format("%.0fs", r.end));
				}
			}
		}
		System.out.printf("A (tuning): dealt %.0f, lost %d, ahead in %d, idle %.1f%%%nB (%s): dealt %.0f, lost %d, ahead in %d, idle %.1f%%%n",
				dealt[0], lost[0], ahead[0], 100f * idle[0] / Math.max(1, frames[0]), spec, dealt[1], lost[1], ahead[1], 100f * idle[1] / Math.max(1, frames[1]));
	}

	private static String AB;
	private static int abSide;

	static Result run(File game, Map<String, GameData.Hull> hulls, Tuning t, String sc, int seed, float seconds) throws Exception {
		Sim sim = new Sim(t);
		sim.combat.seed(1L + seed * 101L);
		List<Ship3D> all = new ArrayList<>();
		String[][] fleets;
		switch (sc) {
			case "boss":
				fleets = new String[][] {{"aurora", "fury", "eagle", "falcon", "medusa", "hammerhead", "sunder", "wolf", "tempest", "dominator"},
						{"onslaught", "enforcer", "enforcer", "hammerhead", "hammerhead"}};
				break;
			case "kite":
				fleets = new String[][] {{"wolf", "tempest", "lasher", "hound", "medusa", "hammerhead", "omen", "brawler"},
						{"onslaught", "conquest", "legion", "paragon"}};
				break;
			case "brawl":
				fleets = new String[][] {{"wolf", "lasher", "hammerhead", "enforcer", "eagle", "dominator", "sunder", "centurion"},
						{"wolf", "lasher", "hammerhead", "enforcer", "eagle", "dominator", "sunder", "centurion"}};
				break;
			default: // lines, admiral
				fleets = new String[][] {{"onslaught", "conquest", "dominator", "eagle", "aurora", "falcon", "medusa", "hammerhead", "sunder", "enforcer", "wolf", "lasher"},
						{"onslaught", "paragon", "dominator", "eagle", "aurora", "falcon", "medusa", "hammerhead", "sunder", "enforcer", "wolf", "lasher"}};
		}
		Result r = new Result();
		r.scenario = sc;
		r.seed = seed;
		for (int side = 0; side < 2; side++) {
			String[] f = fleets[side];
			for (int i = 0; i < f.length; i++) {
				if (!hulls.containsKey(f[i])) continue;
				Ship3D s = CombatBench.armed(game, hulls, t, f[i], f[i] + side + "_" + i, side);
				if ("boss".equals(sc) && side == 1 && i == 0) {
					s.hullMax = s.hull = 60000f; // the boss
					s.armor = new s3d.core.ArmorShell(2500f);
				}
				s.ai = new ShipAI3D(i + side * 31 + seed * 977);
				if (AB != null && side == abSide) for (String kv : AB.split(",")) {
					String[] p = kv.split("=");
					ShipAI3D.class.getField(p[0].trim()).setFloat(s.ai, Float.parseFloat(p[1].trim()));
				}
				s.ai.homeDir.set(0f, 0f, side == 0 ? 1f : -1f);
				// Vanilla-like deployment: a long line at each side's edge, big ships in the middle.
				float slot = (i % 2 == 0 ? 1f : -1f) * ((i + 1) / 2) * (f.length > 8 ? 900f : 1100f);
				s.pos.set(slot * (side == 0 ? 1f : -1f), (i % 3) * 125f - 125f, side == 0 ? 2700f : -2700f);
				if (side == 1) s.rot.setAxisAngle(0, 1, 0, (float) Math.PI);
				sim.addShip(s);
				all.add(s);
				r.ships[side]++;
			}
		}
		if ("admiral".equals(sc) || "boss".equals(sc)) {
			// The enemy admiral: escorts on its flagship, two ships sent to take objectives out on the wings.
			Ship3D flag = null;
			for (Ship3D s : all) if (s.owner == 1 && flag == null) flag = s;
			int n = 0;
			for (Ship3D s : all) {
				if (s.owner != 1 || s == flag) continue;
				n++;
				if ("boss".equals(sc) || n <= 4) s.order = escort(flag);
			}
			if ("admiral".equals(sc)) {
				for (int k = 0; k < 2; k++) {
					Objective3D obj = new Objective3D("nav_buoy", "Nav " + k);
					obj.pos.set(k == 0 ? -4500f : 4500f, 0f, 0f);
					obj.radius = 400f;
					sim.objectives.add(obj);
				}
				int k = 0;
				for (Ship3D s : all) {
					if (s.owner != 1 || s.order != null || s == flag || k >= 2) continue;
					s.order = new Order3D(Order3D.Kind.CAPTURE);
					s.order.objective = sim.objectives.get(k);
					s.order.radius = sim.objectives.get(k).radius;
					s.order.label = "CAPTURE";
					s.order.admiral = true;
					k++;
				}
			}
		}
		s3d.core.PilotInput in = CombatBench.screen();
		Map<Ship3D, Float> lastFired = new HashMap<>(), idleSince = new HashMap<>(), lastDist = new HashMap<>();
		java.lang.reflect.Field activity = null;
		try {
			activity = ShipAI3D.class.getField("activity");
		} catch (NoSuchFieldException e) {
			// older AI: coarse causes only
		}
		for (float time = 0; time < seconds; time += DT) {
			sim.update(in, DT, DT);
			// Objectives: whoever is alone on one owns it (the host does this from vanilla in game).
			for (Objective3D o : sim.objectives) {
				int[] on = new int[2];
				for (Ship3D s : all) if (!s.destroyed && o.flatDistance(s.pos) < o.radius) on[s.owner]++;
				if (on[0] > 0 && on[1] == 0) o.owner = 0;
				if (on[1] > 0 && on[0] == 0) o.owner = 1;
			}
			int[] alive = new int[2];
			for (Ship3D s : all) {
				if (s.destroyed) continue;
				alive[s.owner]++;
				if (WATCH != null && s.id.equals(WATCH) && Math.round(time / DT) % 120 == 0)
					System.out.printf("    watch %5.1fs %s hull %.0f%%: %s%n", time, s.id, 100f * s.hull / s.hullMax, s.ai.debugState(s));
				boolean fired = false;
				for (WeaponMount m : s.weapons) if (m.firedThisFrame && !m.spec.pd) fired = true;
				if (fired) lastFired.put(s, time);
				r.shipFrames++;
				r.sideFrames[s.owner]++;
				float lf = lastFired.getOrDefault(s, -99f);
				boolean firing = time - lf < IDLE_AFTER;
				if (firing) r.firingFrames++;
				Ship3D tg = s.ai.target;
				float nearest = Float.MAX_VALUE;
				for (Ship3D o : all) if (o.owner != s.owner && !o.destroyed) nearest = Math.min(nearest, o.pos.distance(s.pos) - o.radius * 0.3f);
				if (nearest < s.ai.weaponRange) r.engagedFrames++;
				boolean closing = false;
				if (tg != null && !tg.destroyed) {
					float d = tg.pos.distance(s.pos);
					Float prev = lastDist.get(s);
					if (prev != null && (prev - d) / DT > 0.3f * s.params.fwdSpeed) closing = true;
					lastDist.put(s, d);
				} else {
					lastDist.remove(s);
				}
				boolean retreating = s.retreatOrdered || s.flux != null && s.flux.disabled();
				// Pulling out to vent counts as retreating; travelling to an order's spot or toward the enemy as busy.
				String act = activity != null ? String.valueOf(activity.get(s.ai)) : "";
				float speed = s.speed();
				if ("DISENGAGE".equals(act) || "COOL_OFF".equals(act) && tg != null && s.vel.dot(new Vec3(s.pos).sub(tg.pos).normalize()) > 0.15f * s.params.fwdSpeed) retreating = true;
				if (("ORDER".equals(act) || "HUNT".equals(act)) && speed > 0.4f * s.params.fwdSpeed) closing = true;
				if (firing || closing || retreating) {
					idleSince.remove(s);
					continue;
				}
				Float since = idleSince.get(s);
				if (since == null) {
					idleSince.put(s, time);
					since = time;
				}
				r.idleFrames++;
				r.sideIdle[s.owner]++;
				String why = cause(s, tg, activity);
				r.idleWhy.merge(why, 1L, Long::sum);
				if (TRACE && time - since > 5f && Math.round(time / DT) % 120 == 0) System.out.printf("    %s %5.1fs idle %4.1fs %s: %s%n", sc, time, time - since, s.id, s.ai.debugState(s));
				if (time - since > r.worstIdle) {
					r.worstIdle = time - since;
					r.worstIdleShip = s.id;
					r.worstIdleWhy = why + String.format(" (target %s at %.0f, range %.0f, flux %.0f%%)", tg != null ? tg.id : "-",
							tg != null ? tg.pos.distance(s.pos) : 0f, s.ai.weaponRange, s.flux != null ? s.flux.level() * 100f : 0f);
				}
			}
			int dead0 = r.ships[0] - alive[0], dead1 = r.ships[1] - alive[1];
			if (r.firstKill < 0f && dead0 + dead1 > 0) r.firstKill = time;
			if (alive[0] == 0 || alive[1] == 0 || "boss".equals(sc) && all.get(r.ships[0]).destroyed) {
				r.end = time;
				break;
			}
		}
		for (Ship3D s : all) {
			r.dealt[1 - s.owner] += CombatBench.damageTaken(s);
			if (s.destroyed) r.lost[s.owner]++;
		}
		return r;
	}

	private static String cause(Ship3D s, Ship3D tg, java.lang.reflect.Field activity) {
		if (activity != null) {
			try {
				Object a = activity.get(s.ai);
				if (a != null) return a.toString();
			} catch (IllegalAccessException e) {
				// fall through
			}
		}
		if (tg == null) return s.order != null ? "order " + s.order.kind + " (no target)" : "no target";
		float d = tg.pos.distance(s.pos);
		String o = s.order != null ? "order " + s.order.kind + ", " : "";
		if (d > s.ai.weaponRange * 1.3f) return o + "target out of reach";
		return o + "target in reach, " + s.ai.move;
	}
}
