package s3d.core;

import s3d.math.Vec3;

/**
 * A 3D combat pilot for AI ships (enemies and the player's allies). It flies with the same FlightModel and
 * controls as the player, so it obeys the hull's real speed and turn limits.
 *
 * Since 2026-10-14 its fighting follows AI Tweaks (Halke's Starsector mod, the user's reference AI; public domain,
 * nothing copied, its ideas rebuilt for 3D and checked fight by fight against the previous pilot in the AI arena):
 * <ul>
 * <li>Targets (pickTarget, attackScore): with enemies within 1.5x its guns' reach it scores them: little turning
 * to bring the guns round, inside the guns' reach (more so where all of them reach), high on flux, no Damper brick,
 * big ships for big slow ships, one friends are already on, one that can't fight back, the one it's on while it's
 * still in front, never one behind a friendly hull. With none near it moves on the enemy's battle groups (fleet
 * segmentation): quick ships go for any, big slow ones for the main group's big ships, not lone frigates.</li>
 * <li>Range: where its shortest primary gun reaches (PD and missiles left out), a little inside.</li>
 * <li>Manoeuvre (orbitCourse): a spot at that range on the side of the target away from the other threats (threat
 * vector: enemies weighted by size squared and nearness), friends on the same target each taking their own slice
 * round it, one on one circling it on the pilot's own plane; flown on the thrusters with the nose (or a broadside)
 * on the target, matching its velocity. Out of reach it pursues flat out.</li>
 * <li>Flux (VentModule's rules): fire to 93% flux; back off on hard flux over 75%, flux heading past 93%, or shields
 * down under a beating, away from the threats until it's safe, then hold just out of its own reach and vent; vent
 * whenever safe at half flux (a third when idle); "safe" weighs fire on its way and guns that will reach it during
 * the vent against hull x hull-level^4. A badly hurt target is finished first.</li>
 * </ul>
 * On top of that, its own: never idle (a target out of reach is chased, one in reach that isn't getting shot at
 * pulls the range in and is flanked; a hopeless chase is given up; with nothing to fight it hunts the nearest enemy;
 * a back-off that can't shake its chaser turns to fight); a weave (jinks, dodges); frigate gun runs (PASS); FLANK
 * plans to broken armor; aggression (Tuning.aiAggression: presses in when ahead, fires to 93%), caution
 * (Tuning.aiCaution: no runs into crowds, flanking spots out of other guns) and opportunism (Tuning.aiOpportunism:
 * blocked guns switch targets, zero-flux guns never hold). The enemy admiral's escort and hold orders say where to
 * fight first, its capture orders end once the point is taken; your own orders are followed as given.
 *
 * Station sections and ship modules don't fly (see turretStep): they pick a target, manage flux and shields, and
 * fire; attackers hold their range on the throttle round them. A station's invulnerable core is never targeted.
 *
 * Fighters fly differently (see fighterStep): out of the bay, then attack runs (close in, fire, break away, come
 * round again) on targets within their wing's range of the carrier; fighters and interceptors hunt enemy fighters
 * first, bombers and assault wings go for ships (the carrier's target first); support wings, and every wing while
 * its carrier has them regrouped, escort the carrier. Out of ordnance or badly hurt, they land to rearm.
 */
public final class ShipAI3D {
	public enum Axis { NOSE, PORT, STARBOARD }

	public Ship3D target;
	/** Direction of this ship's own map edge (world, unit); retreats fly this way. */
	public final Vec3 homeDir = new Vec3(0f, 0f, 1f);
	public Axis axis = Axis.NOSE;
	/** Preferred fighting distance and main weapon range (3D units). */
	public float engageRange = 800f, weaponRange = 1000f;
	/** Furthest any of its weapons reaches, missiles included (3D units): where it starts being a danger. */
	public float maxReach;
	/**
	 * Where its forward hardpoints sit (firepower-weighted, local, unscaled; zero if they don't carry much of
	 * the nose's firepower). A nose ship points these at its target rather than its centre: on big hulls the
	 * mounts sit tens of units above and beside the centreline, more than a 5 deg hardpoint can make up.
	 */
	private final Vec3 hardpointAim = new Vec3(), tG = new Vec3();
	/** Projectile speed of the main weapons, for leading. */
	public float leadSpeed = 800f;
	/** Firepower of its guns (dps, missiles left out): the yardstick for how outgunned it is somewhere. */
	public float gunDps;
	/**
	 * How far out along its gun axis its main guns sit from the centre (firepower-weighted, 3D units):
	 * a big hull's guns reach that much further than weaponRange, counted from its centre.
	 */
	public float mountLead;
	/** This pilot's caution, opportunism and aggression (see Tuning.aiCaution / aiOpportunism / aiAggression); NaN = the tuning's. */
	public float caution = Float.NaN, opportunism = Float.NaN, aggression = Float.NaN;
	/**
	 * Debug (bench): mount-frames with an enemy in reach; of those, shots allowed, and holds because the
	 * barrel wasn't on yet, for flux, for a friendly in the way, by missile discipline; then blocked shots
	 * that found another enemy instead.
	 */
	public final int[] fireStats = new int[7];
	/** Backing out of the target's reach to let flux drain (from ~80% flux until it's down to ~half; see scanGuns). */
	private boolean coolingOff;
	/** Cooling off but too slow in reverse to get out of the guns on us: turned tail and running (see disengage). */
	private boolean running;
	private float runQuiet;
	/** Cooling off with guns on us that it can neither back out of nor outrun: it fights on instead (fluxLimit). */
	private boolean cornered;
	/**
	 * Never idle: seconds since a gun (not PD) last fired at an enemy; how long it's been in reach of its target
	 * without firing; how far the fighting range has been pulled in because of that (1 = not at all); how long a
	 * chase has gone without gaining; the target given up on as out of reach, and for how long it's out of favour.
	 */
	private float sinceFired, stalled, pressK = 1f, chaseStall, lastGap = -1f, giveUpLeft;
	private Ship3D giveUp;
	private boolean forceFlank;
	/** Caution has set this frame's fighting range: outside the target's guns (it's outranged, or we're cooling off). */
	private boolean standingOff;
	/** Running out to the range we want (too slow in reverse), for how long, and off to which side (world). */
	private boolean opening;
	private float openTime;
	private final Vec3 openSide = new Vec3();
	/** This frame: enemy gun dps that reaches us, from everyone and from all but the target; the way out of the others' reach (world). */
	private float gunsOnUs, gunsOnUsOthers;
	private final Vec3 awayDir = new Vec3();

	private final ShipControls c = new ShipControls();
	private boolean profiled;
	private float retarget, strafeSign = 1f, phase, quiet;
	private final Vec3 tA = new Vec3(), tB = new Vec3(), tC = new Vec3(), tD = new Vec3(), tE = new Vec3(), tF = new Vec3();
	private final Vec3 tH = new Vec3(), tI = new Vec3(), tJ = new Vec3(), tK = new Vec3();
	private final java.util.Random rng;

	// ---- manoeuvring (ships) ----
	public enum Move { HOLD, FLANK, PASS }
	public Move move = Move.HOLD;
	/** Gun runs made so far (for the bench and the log). */
	public int passesMade;
	/** Debug: plans picked (HOLD, FLANK, PASS) and gun runs abandoned. */
	public final int[] plans = new int[3];
	public int passAborts;
	private Ship3D planTarget;
	private float planLeft, rangeBias, throttleBias, orbitTilt;
	/** Weave: the local strafe being flown (x starboard, y up) and the one it's easing toward. */
	private float jinkX, jinkY, jinkGoalX, jinkGoalY, jinkLeft;
	/** FLANK: where to fight from, as a direction from the target in the target's own frame. */
	private final Vec3 stationLocal = new Vec3();
	/** PASS: 0 run in, 1 run out, 2 come about; which side of the target to pass on (world). */
	private int passStage, passesLeft;
	private float passTime, dodgeCool;
	private boolean passArmed;
	private final Vec3 passSide = new Vec3(), passExit = new Vec3();
	/** Incoming fire this frame (from manageFlux) and where it comes from (world). */
	private float threatNow;
	private final Vec3 threatDir = new Vec3();

	/** What the pilot is doing this frame (debug: the arena tool and traces). */
	public enum Activity { RETREAT, SYSTEM_BACK_OFF, ORDER, HUNT, NO_TARGET, HELPLESS, COOL_OFF, DISENGAGE, STAND_OFF, OPEN_RANGE, PURSUE, ENGAGE, FLANK, PASS, BROADSIDE }
	public Activity activity = Activity.NO_TARGET;

	public ShipAI3D(int seed) {
		strafeSign = (seed & 1) == 0 ? 1f : -1f;
		phase = (seed * 0.618f) % 1f * 6.283f;
		retarget = (seed % 7) * 0.1f; // stagger re-targeting across ships
		rng = new java.util.Random(seed * 7919L + 17L);
		float oa = rng.nextFloat() * 6.283f;
		orbitAxis.set((float) Math.cos(oa) * 0.35f, rng.nextBoolean() ? 1f : -1f, (float) Math.sin(oa) * 0.35f).normalize();
	}

	/** Reads the weapon layout once: which side to fight with, at what range, with which shot speed. */
	public void profile(Ship3D s, Tuning t) {
		profiled = true;
		float[] weight = new float[3];
		float[] rangeSum = new float[3], speedSum = new float[3], leadSum = new float[3];
		Vec3[] axes = {new Vec3(0, 0, -1), new Vec3(-1, 0, 0), new Vec3(1, 0, 0)};
		float missileRange = 0f, hardpointDps = 0f;
		maxReach = weaponsReach(s, t);
		gunDps = 0f;
		hardpointAim.set(0f, 0f, 0f);
		for (WeaponMount m : s.weapons) {
			WeaponSpec3D w = m.spec;
			if (!w.supported || w.pdOnly || w.flareLauncher) continue; // flares are decoys, not firepower
			if (w.kind == WeaponSpec3D.Kind.MISSILE) {
				missileRange = Math.max(missileRange, w.range);
				continue;
			}
			gunDps += dps(w) * (w.pd ? 0.5f : 1f);
			float dps = dps(w) * (m.hardpoint ? 1.2f : 1f);
			if (w.pd) dps *= 0.5f; // PD guns help, but don't decide how the ship fights
			if (m.hardpoint && !w.pd && m.restDir.dot(axes[0]) > 0.985f) { // within 10 deg of the nose
				hardpointAim.addScaled(m.localPos, dps);
				hardpointDps += dps;
			}
			for (int i = 0; i < 3; i++) {
				float ang = (float) Math.acos(Math.max(-1f, Math.min(1f, m.restDir.dot(axes[i]))));
				if (ang > m.halfArcRad + 0.09f) continue;
				weight[i] += dps;
				rangeSum[i] += dps * w.range;
				speedSum[i] += dps * Sim.shotSpeed(w);
				leadSum[i] += dps * Math.max(0f, m.localPos.dot(axes[i])) * s.scale;
			}
		}
		weight[0] *= 1.15f; // ties go to the nose: easier to fly, and how most hulls are laid out
		int best = 0;
		for (int i = 1; i < 3; i++) if (weight[i] > weight[best]) best = i;
		axis = Axis.values()[best];
		float raw = weight[best];
		if (best == 0) raw /= 1.15f;
		mountLead = 0f;
		if (raw > 0f) {
			weaponRange = rangeSum[best] / raw * t.weaponRangeMult;
			leadSpeed = speedSum[best] / raw;
			mountLead = leadSum[best] / raw;
		} else if (missileRange > 0f) {
			axis = Axis.NOSE;
			weaponRange = missileRange * t.weaponRangeMult;
			leadSpeed = 600f;
		} else {
			weaponRange = 600f * t.weaponRangeMult;
		}
		engageRange = Math.max(sizeFloor(s), weaponRange * t.aiRangeFrac);
		if (axis == Axis.NOSE && hardpointDps > 0f && hardpointDps >= 0.3f * weight[0] / 1.15f) hardpointAim.scale(1f / hardpointDps);
		else hardpointAim.set(0f, 0f, 0f);
		profilePrimary(s, t, axes[best]);
	}

	/**
	 * AI Tweaks' primary weapons: the guns on the fighting side (no missiles; PD only on hardpoints, or when
	 * there's nothing else), each with its reach counted from the ship's centre. Sorted shortest first, with the
	 * share of their dps that reaches each range (see dpsFraction).
	 */
	private float[] primaryRange = new float[0], primaryDpsFrom = new float[0];
	/** Reach of the shortest and the longest primary gun (centre-based, 3D units). */
	public float minRange, maxRange;

	private void profilePrimary(Ship3D s, Tuning t, Vec3 axisLocal) {
		float[] r = new float[s.weapons.size()], d = new float[r.length];
		int n = 0;
		for (int pass = 0; pass < 2 && n == 0; pass++) {
			for (WeaponMount m : s.weapons) {
				WeaponSpec3D w = m.spec;
				if (!w.supported || w.pdOnly || w.flareLauncher || w.kind == WeaponSpec3D.Kind.MISSILE) continue;
				if (pass == 0 && w.pd && !m.hardpoint) continue;
				float ang = (float) Math.acos(Math.max(-1f, Math.min(1f, m.restDir.dot(axisLocal))));
				if (ang > m.halfArcRad + 0.09f) continue;
				float range = w.range * t.weaponRangeMult + Math.max(0f, m.localPos.dot(axisLocal)) * s.scale;
				int i = n++;
				while (i > 0 && r[i - 1] > range) { // insertion sort, shortest first
					r[i] = r[i - 1];
					d[i] = d[i - 1];
					i--;
				}
				r[i] = range;
				d[i] = dps(w);
			}
		}
		// d[i] becomes the dps of guns i.. (those reaching at least r[i]), as a share of all of it.
		for (int i = n - 2; i >= 0; i--) d[i] += d[i + 1];
		float all = n > 0 ? Math.max(1e-3f, d[0]) : 1f;
		for (int i = 0; i < n; i++) d[i] /= all;
		primaryRange = java.util.Arrays.copyOf(r, n);
		primaryDpsFrom = java.util.Arrays.copyOf(d, n);
		minRange = n > 0 ? r[0] : weaponRange + mountLead;
		maxRange = n > 0 ? r[n - 1] : weaponRange + mountLead;
	}

	/** Share of the primary guns' dps that reaches range (centre-based). */
	private float dpsFraction(float range) {
		for (int i = 0; i < primaryRange.length; i++) if (primaryRange[i] >= range) return primaryDpsFrom[i];
		return 0f;
	}

	/** Furthest any of the ship's weapons reaches (3D units); from the AI's profile when it has one. */
	static float reachOf(Ship3D o, Tuning t) {
		return o.ai != null && o.ai.profiled ? o.ai.maxReach : weaponsReach(o, t);
	}

	private static float weaponsReach(Ship3D o, Tuning t) {
		float r = 0f;
		for (WeaponMount m : o.weapons) if (m.spec.supported && !m.spec.flareLauncher) r = Math.max(r, m.spec.range);
		return r * t.weaponRangeMult;
	}

	static float dps(WeaponSpec3D w) {
		if (w.kind == WeaponSpec3D.Kind.BEAM) return w.dps;
		float cycle = Math.max(0.05f, w.cooldown + w.chargeTime + Math.max(0, w.burstSize - 1) * w.burstDelay);
		return w.damage * Math.max(1, w.burstSize) / cycle;
	}

	private float cautionOf(Tuning t) {
		if (reckless) return 0f; // Incursion Mode: caution thrown away
		return Float.isNaN(caution) ? t.aiCaution : caution;
	}

	/** How hard this pilot presses (see Tuning.aiAggression); Incursion Mode doubles it. */
	private float aggressionOf(Tuning t) {
		float a = Float.isNaN(aggression) ? t.aiAggression : aggression;
		return reckless ? Math.max(2f, a * 2f) : a;
	}

	/** Set each frame from the ship: Incursion Mode makes the pilot reckless. */
	private boolean reckless;

	/** Counts down the retarget timer; true (and restarts it at 1 s) when it's time to look again. */
	boolean retargetDue(float dt) {
		retarget -= dt;
		if (retarget > 0f) return false;
		retarget = 1f;
		return true;
	}

	private float opportunismOf(Tuning t) {
		return Float.isNaN(opportunism) ? t.aiOpportunism : opportunism;
	}

	/** Mounts sit off a hull's centre: an enemy's guns count as reaching from this fraction of its radius nearer. */
	private static final float MOUNT_OFFSET = 0.3f;

	/** Builds o's gun reach table (Ship3D.gunRanges / gunDpsReaching). */
	private static void gunTable(Ship3D o) {
		int n = 0;
		float[] r = new float[o.weapons.size()], d = new float[r.length];
		for (WeaponMount m : o.weapons) {
			WeaponSpec3D w = m.spec;
			if (!w.supported || w.pdOnly || w.flareLauncher || w.kind == WeaponSpec3D.Kind.MISSILE) continue;
			int i = n++;
			while (i > 0 && r[i - 1] < w.range) { // insertion sort, longest first
				r[i] = r[i - 1];
				d[i] = d[i - 1];
				i--;
			}
			r[i] = w.range;
			d[i] = dps(w) * (w.pd ? 0.5f : 1f);
		}
		for (int i = 1; i < n; i++) d[i] += d[i - 1];
		o.gunDpsReaching = java.util.Arrays.copyOf(d, n);
		o.gunRanges = java.util.Arrays.copyOf(r, n);
	}

	/** Dps of o's guns that reach dist (3D units) from their mounts. */
	static float gunsReaching(Ship3D o, float dist, Tuning t) {
		if (o.gunRanges == null) gunTable(o);
		float dps = 0f;
		for (int i = 0; i < o.gunRanges.length && o.gunRanges[i] * t.weaponRangeMult >= dist; i++) dps = o.gunDpsReaching[i];
		return dps;
	}

	/** Centre-to-centre distance out to which o's longest gun reaches: its range plus how far out from the centre its mounts sit. */
	static float gunReach(Ship3D o, Tuning t) {
		if (o.gunRanges == null) gunTable(o);
		return o.gunRanges.length > 0 ? o.gunRanges[0] * t.weaponRangeMult + o.radius * MOUNT_OFFSET : 0f;
	}

	/**
	 * Gun dps enemy o can bring on a ship of ours at world point p, counting its guns as reaching `margin`
	 * further than they do (to keep clear of the edge). An overloaded or venting enemy's guns count a
	 * quarter, guns facing away a bit over half (it has to turn first). With awayOut, adds the way out of
	 * its reach (away from it, weighted by those guns).
	 */
	private float gunsOn(Sim sim, Ship3D s, Ship3D o, Vec3 p, float margin, Vec3 awayOut) {
		if (o.owner == s.owner || o.destroyed || o.fighter || o.weapons.isEmpty()) return 0f;
		float dx = p.x - o.pos.x, dy = p.y - o.pos.y, dz = p.z - o.pos.z;
		float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float dps = gunsReaching(o, dist - o.radius * MOUNT_OFFSET - margin, sim.tuning);
		if (dps <= 0f) return 0f;
		if (o.flux != null && o.flux.disabled()) dps *= 0.25f;
		if (dist > 1e-3f) {
			Vec3 axis = o.ai != null && o != sim.player ? o.ai.gunAxisWorld(o, tI) : o.rot.forward(tI);
			dps *= 0.6f + 0.4f * Math.max(0f, (axis.x * dx + axis.y * dy + axis.z * dz) / dist);
			if (awayOut != null) awayOut.addScaled(tI.set(dx, dy, dz), dps / dist);
		}
		return dps;
	}

	/** Enemy gun dps reaching world point p from every enemy but `skip` (see gunsOn). */
	private float exposure(Sim sim, Ship3D s, Vec3 p, Ship3D skip, float margin, Vec3 awayOut) {
		float total = 0f;
		for (Ship3D o : sim.ships) if (o != skip) total += gunsOn(sim, s, o, p, margin, awayOut);
		return total;
	}

	/** How outgunned a ship of ours would be under `dps` of enemy guns: about 1 = as much as its own guns. */
	private float outgunned(float dps) {
		return dps / Math.max(50f, gunDps);
	}

	/** Who has us under their guns this frame (gunsOnUs, gunsOnUsOthers, awayDir). */
	private void scanGuns(Sim sim, Ship3D s, float dt) {
		awayDir.set(0f, 0f, 0f);
		float margin = 60f + s.radius * 0.3f;
		gunsOnUsOthers = exposure(sim, s, s.pos, target, margin, awayDir);
		gunsOnUs = gunsOnUsOthers + (target != null ? gunsOn(sim, s, target, s.pos, margin, null) : 0f);
		ShieldFlux f = s.flux;
		float level = f != null ? f.level() : 0f;
		float hard = f != null && f.maxFlux > 0f ? f.hardFlux / f.maxFlux : 0f;
		finishing = finishingTarget(s, level, Math.min(1f, aggressionOf(sim.tuning)));
		trackDamage(s, dt);
		trackFlux(f, level, dt);
		{
			// AI Tweaks' back-off rules: hard flux past 75% (soft flux from our own guns drains while we hold fire),
			// flux heading past 93% at the rate of the last four seconds, shields down and taking a beating, or, with no
			// shield, too much flux to fire. Back in once flux is all but gone. Aggression shifts the thresholds a little;
			// a target nearly dead is finished first.
			boolean shield = f != null && f.hasShield();
			float shift = FlightModel.clamp(0.1f * (aggressionOf(sim.tuning) - 1f), -0.15f, 0.1f);
			boolean underFire = f != null && recentDamage() / Math.max(1f, f.maxFlux) > 0.2f;
			boolean start = !shield && level > 0.85f + shift || shield && (hard > 0.75f + shift || level + fluxDelta() > 0.93f + shift * 0.5f
					|| underFire && !f.shieldOn);
			boolean stop = level <= 0.05f || f != null && !f.canVent() && !ventSafe && level <= 0.2f;
			// Backing off that gets nowhere (it can't shake what's on it, so it never gets to vent, and the flux isn't
			// draining either): turn and fight for a while instead (in 3D reverse and strafing are slow, so running
			// from anything as quick as us is hopeless).
			backoffTime = coolingOff ? backoffTime + dt : 0f;
			if (coolingOff && backoffTime > 10f && !ventSafe && fluxDelta() > -0.05f) {
				coolingOff = false;
				noBackoffLeft = 8f;
			}
			noBackoffLeft -= dt;
			if (!coolingOff) coolingOff = start && !finishing && (noBackoffLeft <= 0f || level > 0.95f);
			else if (stop || finishing) coolingOff = false;
		}
		if (!coolingOff) running = cornered = false;
	}

	/** Seconds spent backing off this time; seconds left of fighting on after a back-off that got nowhere. */
	private float backoffTime, noBackoffLeft;

	/** Damage taken (hull, armor, and shield hits as hard flux), in one-second buckets over the last four seconds. */
	private final float[] dmgBuckets = new float[4];
	private float bucketClock, lastHullArmor = -1f, lastHardAbs;
	/** Flux level every quarter second over the last four and a bit (ring), for its trend. */
	private final float[] fluxHist = new float[18];
	private int fluxIdx, fluxN;
	private float fluxClock;
	/** Venting would be safe right now (safeToVent, refreshed four times a second). */
	private boolean ventSafe;

	private void trackDamage(Ship3D s, float dt) {
		ShieldFlux f = s.flux;
		float ha = s.hull + (s.armor != null ? s.armor.rating * s.armor.averageFraction() : 0f);
		float hardAbs = f != null ? f.hardFlux : 0f;
		if (lastHullArmor >= 0f) {
			float d = Math.max(0f, lastHullArmor - ha);
			boolean ownHard = f == null || f.venting || s.cloak != null && s.cloak.on(); // a phase cloak's upkeep is hard flux too
			if (!ownHard) d += Math.max(0f, hardAbs - lastHardAbs) / Math.max(0.1f, f.fluxPerDamage);
			dmgBuckets[0] += d;
		}
		lastHullArmor = ha;
		lastHardAbs = hardAbs;
		bucketClock += dt;
		while (bucketClock >= 1f) {
			bucketClock -= 1f;
			for (int i = dmgBuckets.length - 1; i > 0; i--) dmgBuckets[i] = dmgBuckets[i - 1];
			dmgBuckets[0] = 0f;
		}
	}

	private float recentDamage() {
		float d = 0f;
		for (float b : dmgBuckets) d += b;
		return d;
	}

	private void trackFlux(ShieldFlux f, float level, float dt) {
		if (f != null && f.venting) {
			fluxN = 0; // a vent says nothing about the trend
			return;
		}
		fluxClock -= dt;
		if (fluxClock > 0f) return;
		fluxClock = 0.25f;
		fluxHist[fluxIdx] = level;
		fluxIdx = (fluxIdx + 1) % fluxHist.length;
		if (fluxN < fluxHist.length) fluxN++;
	}

	/** How much the flux level rose over the last four seconds or so. */
	private float fluxDelta() {
		if (fluxN < 2) return 0f;
		int newest = (fluxIdx - 1 + fluxHist.length) % fluxHist.length;
		int oldest = fluxN < fluxHist.length ? (fluxIdx - fluxN + fluxHist.length) % fluxHist.length : fluxIdx;
		return fluxHist[newest] - fluxHist[oldest];
	}

	/** Pressing on at high flux to finish a badly hurt target (see finishingTarget). */
	private boolean finishing;
	private float ventCheck;

	/**
	 * Finish the target rather than back off: it's in reach, worse hurt than we are, not hiding behind a healthy
	 * shield, and the more hurt it is the more of our flux we'll spend on it (AI Tweaks' rule, rebuilt). Agile
	 * frigates are left out: they get away.
	 */
	private boolean finishingTarget(Ship3D s, float level, float agg) {
		Ship3D tg = target;
		if (tg == null || tg.fighter || agility(tg) >= 1f || tg.fixedPart() && tg.unarmedPart()) return false;
		if (tg.flux != null && tg.flux.shieldUp() && tg.flux.level() < 0.8f) return false;
		if (gap(s) > weaponRange * 1.05f) return false;
		float th = tg.hull / Math.max(1f, tg.hullMax);
		if (th > s.hull / Math.max(1f, s.hullMax)) return false;
		float dmg = 1f - th;
		return level < Math.min(0.95f, dmg * dmg + 0.1f * agg);
	}

	/** Seconds a vent from here would take. */
	private static float ventTime(Sim sim, ShieldFlux f) {
		return f.flux / Math.max(1f, f.dissipation * sim.tuning.ventRateMult * f.ventRateMult);
	}

	/**
	 * Would venting now be survivable (after AI Tweaks' vent safety check, rebuilt): the fire already on its way
	 * plus a share of what the enemy guns that will reach us during the vent can do, against what the hull can
	 * take. A hurt ship is far more careful (hull x hull-level^4), and a small one (under 1700 hull left) vents
	 * only with nothing coming at all.
	 */
	private boolean safeToVent(Sim sim, Ship3D s, float duration) {
		float frac = s.hull / Math.max(1f, s.hullMax);
		float hp = s.hull * frac < 1700f ? 0f : s.hull * frac * frac * frac * frac;
		if (threatNow > hp * 0.095f) return false;
		if (recentDamage() < hp * 0.03f) return true; // hardly touched lately: go
		float guns = exposure(sim, s, s.pos, null, 60f + s.radius * 0.3f + 30f * duration, null);
		return threatNow + guns * duration <= hp * 0.1f;
	}

	/**
	 * Caution: how hard to get out of the reach of enemies other than the target (0 = none of them has us
	 * under its guns); the way out is awayDir.
	 */
	private float cautionUrge(Tuning t) {
		float cau = cautionOf(t);
		if (cau <= 0f || gunsOnUsOthers <= 0f || awayDir.lengthSq() < 1e-6f) return cautionUrgeDebug = 0f;
		// Aggression: guns that don't outgun us are shrugged off, and less so while we're ahead in the duel.
		float out = outgunned(gunsOnUsOthers) - 0.35f * Math.min(1f, aggressionOf(t));
		if (out <= 0f) return cautionUrgeDebug = 0f;
		return cautionUrgeDebug = cau * Math.min(2f, out) * (1f - 0.5f * Math.max(0f, duel));
	}

	/** Strafes along awayDir (so the nose can stay on the target). */
	private void strafeAway(Ship3D s, float urge) {
		Vec3 local = s.rot.inverseRotate(tH.set(awayDir).normalize(), tH);
		float k = Math.min(1f, urge);
		c.strafeX = FlightModel.clamp(c.strafeX + local.x * k, -1f, 1f);
		c.strafeY = FlightModel.clamp(c.strafeY + local.y * k * 0.7f, -1f, 1f);
	}

	/** One frame: pick a target, steer, move and fight. */
	public void step(Sim sim, Ship3D s, float dt) {
		Tuning t = sim.tuning;
		if (!profiled) profile(s, t);
		if (dt <= 0f) return;
		reckless = s.sysReckless;
		if (s.behaviour != null && SpawnBehaviours.step(this, sim, s, dt)) return; // construction swarms, ejecta, vortices
		if (s.drone) {
			droneStep(sim, s, dt);
			return;
		}
		if (s.fighter) {
			fighterStep(sim, s, dt);
			return;
		}
		if (s.fixedPart()) {
			turretStep(sim, s, dt);
			return;
		}
		retarget -= dt;
		giveUpLeft -= dt;
		if (giveUpLeft <= 0f) giveUp = null;
		if (returnLeft > 0f) returnLeft -= dt;
		Order3D order = currentOrder(sim, s);
		if (order != lastOrder && (order == null || !order.sameAs(lastOrder))) {
			retarget = 0f; // new orders: pick again now
			planLeft = 0f;
		}
		lastOrder = order;
		// A fight that has drawn us too far from where the order wants us is dropped.
		if (target != null && order != null && strayed(s, order)) {
			target = null;
			move = Move.HOLD;
		}
		boolean onRun = move == Move.PASS && target != null && !target.destroyed; // finish the run on the same target
		if (target == null || target.destroyed || !sim.ships.contains(target) || !onRun && (retarget <= 0f || helplessEnemyNear(sim, s))) {
			retarget = t.aiRetargetTime;
			Ship3D was = target;
			target = pickTarget(sim, s);
			if (target != was) newTarget();
		}
		scanGuns(sim, s, dt);
		updateThreats(sim, s);
		duel = target != null ? advantage(sim, s) : 0f;
		c.clearAxes();
		c.autoLevel = true;
		manageFlux(sim, s, dt);
		manageSystems(sim, s);

		Vec3 want = tA; // desired nose direction (world)
		float throttle;
		boolean helpless = s.flux != null && s.flux.disabled();
		if (s.retreatOrdered) {
			activity = Activity.RETREAT;
			want.set(homeDir);
			throttle = 1f;
		} else if (s.sysBackOff && target != null) {
			activity = Activity.SYSTEM_BACK_OFF;
			// Extraction Protocol: pull straight back from the target, guns still going.
			want.set(s.pos).sub(target.pos).normalize();
			throttle = 1f;
		} else if (target == null && order != null && !huntUnder(order)) {
			activity = Activity.ORDER;
			throttle = followOrder(sim, s, order, want);
		} else if (target == null) {
			throttle = hunt(sim, s, want, dt);
		} else if (helpless) {
			activity = Activity.HELPLESS;
			// Overloaded or venting: keep facing the fight and back off slowly while an enemy is close,
			// rather than running for the map edge.
			want.set(target.pos).sub(s.pos).normalize();
			float backTo = engageRange * 1.2f;
			boolean careful = cautionOf(t) > 0f && !target.fixedPart();
			if (careful) backTo = Math.max(backTo, gunReach(target, t) + 60f); // out of its guns, in full reverse
			throttle = target.pos.distance(s.pos) < backTo ? careful ? -1f : -0.5f : 0f;
			weave(sim, s, dt);
			c.strafeX = jinkX;
			c.strafeY = jinkY;
			float urge = cautionUrge(t);
			if (urge > 0f) strafeAway(s, urge);
		} else {
			throttle = fightCourse(sim, s, want, dt);
		}
		if (!s.retreatOrdered) stayWithFight(sim, s, want, order);
		if (!s.retreatOrdered && order != null && order.kind == Order3D.Kind.AVOID) keepOut(s, order, want);
		avoidCollisions(sim, s, want);
		steer(s, want);
		s.throttle = FlightModel.approach(s.throttle, throttle, 1.5f * dt);
		FlightModel.step(s, c, t, dt);
		fireWeapons(sim, s, dt);
	}

	/** -1..1, how the duel with the target stands this frame (see advantage); above 0 we're ahead. */
	private float duel;

	/** A new target: the stall breaker and the chase start over. */
	private void newTarget() {
		pressK = 1f;
		stalled = 0f;
		chaseStall = 0f;
		lastGap = -1f;
		forceFlank = false;
	}

	/**
	 * How the duel with the target stands, -1 (losing: our flux high and theirs low, hurt, outgunned where we
	 * are) to 1 (it can't fight back, or we're well ahead on flux, hull and guns). Aggression presses in on
	 * the upper hand; caution's pull-backs ease off while we have it.
	 */
	private float advantage(Sim sim, Ship3D s) {
		ShieldFlux tf = target.flux, f = s.flux;
		if (tf != null && tf.disabled()) return 1f;
		float a = ((tf != null ? tf.level() : 0f) - (f != null ? f.level() : 0f)) * 1.2f;
		a += (target.hull / Math.max(1f, target.hullMax) - s.hull / Math.max(1f, s.hullMax)) * 0.5f;
		// Our guns against the guns on us: twice theirs is +0.3, half is -0.3.
		float odds = Math.max(50f, gunDps) / Math.max(50f, gunsOnUs);
		a += FlightModel.clamp((float) (Math.log(odds) / Math.log(2)) * 0.3f, -0.5f, 0.5f);
		return FlightModel.clamp(a, -1f, 1f);
	}

	/**
	 * No ship to fight. Head for the nearest enemy at full speed (only fighters left, a station's unarmed
	 * parts, or everything worth shooting is out of the order's ground for an admiral's order) and let the
	 * guns take what comes; among fighters keep moving. Nothing at all: cruise on. Returns the throttle.
	 */
	private float hunt(Sim sim, Ship3D s, Vec3 want, float dt) {
		Ship3D near = null;
		float best = Float.MAX_VALUE;
		for (Ship3D o : sim.ships) {
			if (o.destroyed || o.owner == s.owner || o.invulnerable) continue;
			float d = o.pos.distance(s.pos) - Sim.nearSide(o, s.pos);
			if (d < best) {
				best = d;
				near = o;
			}
		}
		if (near == null) {
			activity = Activity.NO_TARGET;
			want.set(s.rot.forward(tB));
			return 0.3f;
		}
		activity = Activity.HUNT;
		weave(sim, s, dt);
		c.strafeX = jinkX * 0.5f;
		c.strafeY = jinkY * 0.5f;
		want.set(near.pos).sub(s.pos).normalize();
		if (near.fighter && best < weaponRange * 0.6f) {
			// In among fighters: circle through them rather than ram one.
			want.addScaled(s.rot.right(tB), strafeSign * 0.8f).normalize();
			return 0.6f;
		}
		return best < Math.max(s.radius * 2f, weaponRange * 0.5f) ? 0.4f : 1f;
	}

	// ---- orders ------------------------------------------------------------------------------

	private Order3D lastOrder;
	private static final Vec3 STILL = new Vec3();

	/** The ship's order, or null if there is none or what it refers to is gone. */
	private static Order3D currentOrder(Sim sim, Ship3D s) {
		Order3D o = s.order;
		if (o == null) return null;
		switch (o.kind) {
			case ESCORT:
				return gone(sim, o.ship) || o.ship == s || o.ship.owner != s.owner ? null : o;
			case ATTACK:
				return gone(sim, o.ship) || o.ship.owner == s.owner || o.ship.invulnerable ? null : o;
			case AVOID:
				return o.ship != null && gone(sim, o.ship) ? null : o;
			case CAPTURE:
				// An admiral's capture order is done once the objective is ours: the ship is free to fight.
				return o.admiral && o.objective != null && o.objective.owner == s.owner ? null : o;
			default:
				return o;
		}
	}

	/** After straying too far on an admiral's order: seconds left of keeping to the order's ground on the way back. */
	private float returnLeft;

	/**
	 * An admiral's escort or hold order says where to fight, not what may be fought: enemies on its ground come
	 * first, but with nothing there the ship goes after the rest (pulled back if the chase strays far).
	 */
	private boolean huntUnder(Order3D o) {
		return o.admiral && returnLeft <= 0f && (o.kind == Order3D.Kind.ESCORT || o.kind == Order3D.Kind.HOLD);
	}

	private static boolean gone(Sim sim, Ship3D x) {
		return x == null || x.destroyed || !sim.ships.contains(x);
	}

	/**
	 * How far from the order's anchor an enemy may be and still be ours to fight: escorts guard
	 * the ship they're with, holders and capturers the ground round their point.
	 */
	private float leash(Order3D o) {
		switch (o.kind) {
			case ESCORT: return Math.max(1500f, weaponRange * 1.2f) + (o.ship != null ? o.ship.radius : 0f);
			case HOLD:
			case CAPTURE: return Math.max(2000f, weaponRange * 1.5f) + o.radius;
			default: return Float.MAX_VALUE;
		}
	}

	/** An enemy this order lets us pick (escorts and holders guard their ground, avoiders don't go in). */
	private boolean allowedTarget(Order3D o, Ship3D e) {
		switch (o.kind) {
			case ESCORT:
			case HOLD:
			case CAPTURE:
				if (huntUnder(o)) return true; // an admiral's escort or hold: anyone, its ground first (pickTarget)
				return e.pos.distance(o.anchor()) - Sim.nearSide(e, o.anchor()) <= leash(o);
			case AVOID:
				return e.pos.distance(o.anchor()) > o.radius;
			default:
				return true;
		}
	}

	/** Chasing has taken us well beyond the order's ground (an admiral's escort or hold: far beyond it; then back for a while). */
	private boolean strayed(Ship3D s, Order3D o) {
		if (o.kind != Order3D.Kind.ESCORT && o.kind != Order3D.Kind.HOLD && o.kind != Order3D.Kind.CAPTURE) return false;
		boolean loose = huntUnder(o);
		boolean far = s.pos.distance(o.anchor()) > leash(o) * (loose ? 2.5f : 1.4f);
		if (far && loose) returnLeft = 8f;
		return far;
	}

	/**
	 * No enemy to fight: do what the order says. Escorts keep a slot off the escorted ship's side
	 * (port or starboard, a little behind), matching its speed; holders and capturers go to their
	 * point (each to its own spot round it, capturers well inside the capture ring) and wait there
	 * facing the enemy. Returns the throttle.
	 */
	private float followOrder(Sim sim, Ship3D s, Order3D o, Vec3 want) {
		Vec3 enemy = sim.mobileCentre(s.owner == 0 ? 1 : 0);
		Vec3 face = enemy != null ? tE.set(enemy).sub(s.pos) : tE.set(homeDir).scale(-1f);
		if (face.lengthSq() < 1f) face.set(homeDir).scale(-1f);
		face.normalize();
		switch (o.kind) {
			case ESCORT: {
				Ship3D e = o.ship;
				float off = e.radius * 1.6f + s.radius * 2f + 150f;
				Vec3 slot = e.rot.rotate(strafeSign * off, (phase > 3.14f ? 1f : -1f) * s.radius * 0.5f, e.radius * 0.6f + s.radius, tD).add(e.pos);
				return moveTo(s, slot, e.vel, s.radius + 60f, e.rot.forward(tE), want);
			}
			case HOLD:
			case CAPTURE: {
				float ring = o.kind == Order3D.Kind.CAPTURE ? o.radius * 0.35f : o.radius * 0.6f;
				Vec3 spot = tD.set((float) Math.cos(phase) * ring, ((phase * 3f) % 2f - 1f) * 60f, (float) Math.sin(phase) * ring).add(o.anchor());
				return moveTo(s, spot, STILL, Math.max(60f, ring * 0.5f), face, want);
			}
			default:
				want.set(s.rot.forward(tB));
				return 0.2f;
		}
	}

	/**
	 * Fly to goal (moving at goalVel) and stop there, closing no faster than the ship can brake;
	 * within `arrive` of it, hold position facing `face`. Returns the throttle.
	 */
	private float moveTo(Ship3D s, Vec3 goal, Vec3 goalVel, float arrive, Vec3 face, Vec3 want) {
		FlightParams p = s.params;
		Vec3 to = tB.set(goal).sub(s.pos);
		float d = to.length();
		Vec3 fwd = s.rot.forward(tC);
		if (d > arrive) {
			want.set(to).scale(1f / d);
			float e = d - arrive * 0.5f;
			float rate = Math.min(p.fwdSpeed, Math.min((float) Math.sqrt(2f * Math.max(1f, p.fwdDecel) * e) * 0.8f, e * 0.6f + 10f));
			float along = goalVel.dot(want) + rate;
			float lined = FlightModel.clamp((fwd.dot(want) + 0.3f) / 1.3f, 0.15f, 1f); // ease off while turning toward it
			return FlightModel.clamp(along / Math.max(1f, p.fwdSpeed), -0.5f, 1f) * lined;
		}
		// There: face the way the order wants, and drift back onto the spot with the thrusters.
		want.set(face);
		Vec3 local = s.rot.inverseRotate(to.scale(1f / Math.max(1f, arrive)), tD);
		c.strafeX = FlightModel.clamp(local.x, -0.6f, 0.6f);
		c.strafeY = FlightModel.clamp(local.y, -0.6f, 0.6f);
		return FlightModel.clamp((goalVel.dot(fwd) - local.z * 20f) / Math.max(1f, p.fwdSpeed), -0.4f, 1f);
	}

	/** AVOID: turn away from the area while inside it or close to its edge. */
	private void keepOut(Ship3D s, Order3D o, Vec3 want) {
		Vec3 away = tB.set(s.pos).sub(o.anchor());
		float d = away.length(), edge = o.radius * 1.15f;
		if (d >= edge) return;
		if (d < 1f) away.set(homeDir);
		else away.scale(1f / d);
		want.addScaled(away, FlightModel.clamp((edge - d) / Math.max(1f, o.radius * 0.3f), 0.3f, 2.5f)).normalize();
	}

	/** A station section, module or station core: no flying, just targets, flux, shields and guns. */
	private void turretStep(Sim sim, Ship3D s, float dt) {
		retarget -= dt;
		if (target == null || target.destroyed || !sim.ships.contains(target) || retarget <= 0f) {
			retarget = sim.tuning.aiRetargetTime;
			target = pickTarget(sim, s);
		}
		scanGuns(sim, s, dt);
		manageFlux(sim, s, dt);
		fireWeapons(sim, s, dt);
	}

	/**
	 * Sets the nose direction for the fight; returns the throttle. Three things a ship can be doing, never
	 * nothing: pursuing a target beyond its guns (straight at it, flat out), fighting it (hold, flank or gun
	 * runs, weaving), or disengaging to vent (backing off with the guns still on it, or, too slow in reverse
	 * to get out of the guns on it, turning tail until they're off it).
	 */
	private float fightCourse(Sim sim, Ship3D s, Vec3 want, float dt) {
		Tuning t = sim.tuning;
		if (planTarget != target) planLeft = 0f; // new target, new plan
		planLeft -= dt;
		if (planLeft <= 0f) plan(sim, s);
		weave(sim, s, dt);
		Vec3 to = tB.set(target.pos).sub(s.pos);
		float dist = Math.max(1f, to.length());
		Vec3 toN = tC.set(to).scale(1f / dist);
		float fluxLevel = s.flux != null ? s.flux.level() : 0f;
		float gapNow = gap(s);
		trackStalls(s, gapNow, dt);
		float engageRange = fightRange(sim, s, fluxLevel);
		float wobble = (float) Math.sin(sim.time * 0.35f + phase);
		activity = coolingOff && standingOff ? Activity.COOL_OFF : standingOff ? Activity.STAND_OFF : Activity.ENGAGE;
		if (coolingOff && !target.fixedPart()) return backOffCourse(sim, s, want, toN, dist, dt);
		if (coolingOff && disengage(s, want, toN, dt)) return 1f;
		if (move != Move.PASS && pursuing(s, gapNow, engageRange)) {
			// Out of reach: straight for where it will be, flat out; the weave only a little.
			activity = Activity.PURSUE;
			Vec3 lead = tD;
			if (!Combat.intercept(s.pos, STILL, target.pos, target.vel, Math.max(30f, s.params.fwdSpeed), lead)) lead.set(target.pos);
			want.set(lead).sub(s.pos).normalize();
			// Still turning toward it: don't fly off the wrong way meanwhile (a capital takes most of a minute to
			// come about); the thrusters push toward it.
			Vec3 local = s.rot.inverseRotate(want, tE);
			float lined = -local.z;
			c.strafeX = FlightModel.clamp(jinkX * 0.3f + (lined < 0.8f ? local.x : 0f), -1f, 1f);
			c.strafeY = FlightModel.clamp(jinkY * 0.3f + (lined < 0.8f ? local.y : 0f), -1f, 1f);
			return FlightModel.clamp((lined + 0.2f) / 0.8f, 0f, 1f);
		}
		if (axis == Axis.NOSE) {
			if (move == Move.PASS) {
				float th = passCourse(sim, s, want, toN, dist, engageRange, fluxLevel, dt);
				if (move == Move.PASS) {
					activity = Activity.PASS;
					return th;
				}
			}
			if (!target.fixedPart()) {
				// AI Tweaks' manoeuvre: nose on the lead, thrusters to the attack spot; the weave on top.
				aimAt(s, want);
				float th = orbitCourse(sim, s, toN, dist, engageRange);
				float k = dist < weaponRange * 1.2f ? 0.6f : 0.3f;
				c.strafeX = FlightModel.clamp(c.strafeX + jinkX * k, -1f, 1f);
				c.strafeY = FlightModel.clamp(c.strafeY + jinkY * k, -1f, 1f);
				if (move == Move.FLANK && activity == Activity.ENGAGE) activity = Activity.FLANK;
				return th;
			}
			// A station section or module: hold the range on the throttle, flanking round it (it doesn't move).
			if (openRange(s, toN, dist, engageRange, dt)) {
				activity = Activity.OPEN_RANGE;
				want.set(toN).scale(-1f).addScaled(openSide, 0.6f).normalize();
				c.strafeX = jinkX * 0.5f;
				c.strafeY = jinkY * 0.5f;
				return 1f;
			}
			Vec3 lead = tD;
			if (!Combat.interceptCurved(s.pos, s.vel, target.pos, target.vel, target.accel, Math.max(50f, leadSpeed), lead)) lead.set(target.pos);
			want.set(lead).sub(s.pos);
			if (hardpointAim.lengthSq() > 0f) want.sub(s.rot.rotate(hardpointAim.x * s.scale, hardpointAim.y * s.scale, hardpointAim.z * s.scale, tG));
			want.normalize();
			// Weave all the time (less on the way in); FLANK adds a slide round the target on top.
			float k = dist < weaponRange * 1.2f ? 1f : 0.4f;
			c.strafeX = jinkX * k;
			c.strafeY = jinkY * k;
			if (move == Move.FLANK) {
				flank(s, toN);
				if (activity == Activity.ENGAGE) activity = Activity.FLANK;
			}
			float urge = cautionUrge(t);
			if (urge > 0f) {
				strafeAway(s, urge);
				// The guns on us are round or past the target: stand further back, still within our own reach.
				float back = -tH.set(awayDir).normalize().dot(toN);
				if (back > 0f) engageRange = Math.min(Math.max(engageRange, weaponRange * 0.95f), engageRange * (1f + 0.4f * Math.min(1f, urge) * back));
			}
			return rangeThrottle(s, toN, dist, engageRange, standingOff ? -1f : -0.5f);
		}
		if (activity == Activity.ENGAGE) activity = Activity.BROADSIDE;
		// Broadside: fly across the line to the target with the gun side facing it, leaning in or
		// out to hold range. The orbit's plane is tilted per plan and bobs, so it isn't flat.
		Vec3 up = Math.abs(toN.y) > 0.9f ? s.rot.up(tD) : tD.set(0f, 1f, 0f);
		up.addScaled(tE.set(toN).scale(-toN.dot(up)), 1f).normalize();
		Vec3 side = tE.set(toN).cross(up);
		up.scale((float) Math.cos(orbitTilt)).addScaled(side, (float) Math.sin(orbitTilt)).normalize();
		Vec3 across = tJ.set(up).cross(toN).normalize(); // target on the starboard side when flying this way
		if (axis == Axis.PORT) across.scale(-1f);
		// Lean in or out to hold the orbit's radius (hard once the target is slipping out of reach: flying across
		// the line drifts outward).
		float radial = FlightModel.clamp((dist - engageRange) / Math.max(1f, engageRange) * (dist > reachOut() * 0.9f ? 4f : 1.6f), -0.7f, 1.5f);
		// Coming into reach: nose in first, swinging the guns round as it arrives (sideways all the way in is slow).
		float inbound = FlightModel.clamp((gapNow - reachOut()) / Math.max(1f, reachOut() * 0.3f), 0f, 1f);
		want.set(across).scale(1f - inbound).addScaled(toN, radial + 2f * inbound).addScaled(up, wobble * 0.35f * (1f - inbound)).normalize();
		if (inbound <= 0f && !target.fixedPart()) {
			// AI Tweaks' way with broadsides: the gun side kept on the target, the attack spot reached on the engines
			// (along the nose, which runs round the target) and thrusters (in and out).
			want.set(across).addScaled(toN, FlightModel.clamp(radial, -0.3f, 0.3f)).normalize();
			float th = orbitCourse(sim, s, toN, dist, engageRange);
			c.strafeY = FlightModel.clamp(c.strafeY + jinkY * 0.5f, -1f, 1f);
			return th;
		}
		float urge = cautionUrge(t);
		if (urge > 0f) want.addScaled(tH.set(awayDir).normalize(), Math.min(1f, urge) * 0.8f).normalize();
		c.strafeY = jinkY * 0.7f;
		if (inbound > 0f) return 1f;
		return FlightModel.clamp(0.65f + throttleBias + 0.15f * (float) Math.sin(sim.time * 0.3f + phase * 2f), 0.3f, 1f);
	}

	// ---- manoeuvring after AI Tweaks (the user's reference AI; public domain), rebuilt for 3D ----

	/** Where the enemy is, weighted by size squared and nearness (unit, world; zero = none near): AI Tweaks' threat vector. */
	private final Vec3 threatVec = new Vec3();
	private int threatsNear;
	/** This pilot's own plane for circling a target one-on-one (unit, world; mostly vertical axis). */
	private final Vec3 orbitAxis = new Vec3();
	/** Backing off: how far from the target it holds once safe (-1 = not set), and the jink on its way out. */
	private float backoffDist = -1f, backoffTurn;
	private final Vec3 backoffSkew = new Vec3();

	/** How far out enemies count as threats: three times its guns' reach, 2500-5000. */
	private float threatRange() {
		return FlightModel.clamp(Math.max(maxRange, weaponRange) * 3f, 2500f, 5000f);
	}

	/** The threat vector: enemy ships within threatRange, weighted by deployment weight squared, fading out toward the edge. */
	private void updateThreats(Sim sim, Ship3D s) {
		float r = threatRange(), r2 = r * r;
		threatVec.set(0f, 0f, 0f);
		threatsNear = 0;
		for (Ship3D o : sim.ships) {
			if (o.owner == s.owner || o.destroyed || o.fighter || o.parent != null || o.invulnerable) continue; // a structure counts once
			float dx = o.pos.x - s.pos.x, dy = o.pos.y - s.pos.y, dz = o.pos.z - s.pos.z;
			float d2 = dx * dx + dy * dy + dz * dz;
			if (d2 >= r2 || d2 < 1f) continue;
			threatsNear++;
			float dp = deployWeight(o);
			float w = (r2 - d2) / r2 * dp * dp / (float) Math.sqrt(d2);
			threatVec.x += dx * w;
			threatVec.y += dy * w;
			threatVec.z += dz * w;
		}
		if (threatVec.lengthSq() > 1e-12f) threatVec.normalize();
		else threatVec.set(0f, 0f, 0f);
	}

	/** One on one: the target is the only enemy near, and it isn't quicker than us. */
	private boolean oneOnOne(Ship3D s) {
		return threatsNear <= 1 && !coolingOff && (target.params == null || target.params.fwdSpeed <= s.params.fwdSpeed);
	}

	/**
	 * AI Tweaks' attack manoeuvre, in 3D: a spot at the fighting range from the target, on the side away from the
	 * other threats (every enemy in front of us, none round the back), or, one on one, sliding round it on this
	 * pilot's own plane; spread from friends on the same target so nobody blocks anybody's shots (AI Tweaks'
	 * attack coordinator). The ship flies there on its thrusters with the nose on the target, holding the range
	 * and matching the target's velocity (drive). A FLANK plan's spot (broken armor, away from its guns) wins.
	 */
	private float orbitCourse(Sim sim, Ship3D s, Vec3 toN, float dist, float range) {
		Vec3 bearing = tE.set(toN).scale(-1f); // from the target to us
		Vec3 dir = tF;
		if (move == Move.FLANK) {
			target.rot.rotate(stationLocal, dir);
		} else if (target.fixedPart() && target.parent != null) {
			// A station section or module: from outside the structure, not across it.
			dir.set(target.pos).sub(target.root().pos);
			if (dir.lengthSq() < 1f) dir.set(bearing);
			dir.normalize().add(bearing).normalize();
		} else if (threatVec.lengthSq() < 0.5f || dist > threatRange()) {
			dir.set(bearing);
		} else if (oneOnOne(s)) {
			// Circle it: the bearing turned 60 deg round this pilot's own axis.
			Vec3 ax = tH.set(orbitAxis).addScaled(bearing, -orbitAxis.dot(bearing));
			if (ax.lengthSq() < 1e-4f) ax.set(1f, 0f, 0f).addScaled(bearing, -bearing.x);
			ax.normalize();
			dir.set(ax).cross(bearing).scale(0.866f).addScaled(bearing, 0.5f).normalize();
		} else {
			dir.set(threatVec).scale(-1f);
		}
		coordinate(sim, s, dir, range);
		dir.y = FlightModel.clamp(dir.y, -0.5f, 0.5f); // keep the fight roughly level
		dir.normalize();
		return driveTo(s, bearing, dir, dist, range);
	}

	/**
	 * Holds `range` from the target while sliding round it from `bearing` (unit, from the target to us) toward
	 * `dir` (unit), matching its velocity: closing or opening at a rate it can still brake from, and round the
	 * sphere as fast as the thrusters allow. Returns the throttle.
	 */
	private float driveTo(Ship3D s, Vec3 bearing, Vec3 dir, float dist, float range) {
		FlightParams p = s.params;
		float err = dist - range;
		float vr = Math.min(p.fwdSpeed, Math.min((float) Math.sqrt(2f * Math.max(1f, p.fwdDecel) * Math.abs(err)) * 0.8f, Math.abs(err) * 0.6f));
		float cos = FlightModel.clamp(bearing.dot(dir), -1f, 1f);
		Vec3 tan = tJ.set(dir).addScaled(bearing, -cos);
		float arc = (float) Math.acos(cos) * range;
		float vt = 0f;
		if (tan.lengthSq() > 1e-6f) {
			tan.normalize();
			vt = Math.min(p.strafeSpeed * 1.2f, Math.min((float) Math.sqrt(2f * Math.max(1f, p.strafeAccel) * arc) * 0.8f, arc * 0.5f));
		}
		Vec3 v = tK.set(target.vel).addScaled(bearing, -Math.signum(err) * vr).addScaled(tan, vt);
		return drive(s, v);
	}

	/** Thrusters and throttle for velocity v (world) whichever way the nose points. Returns the throttle. */
	private float drive(Ship3D s, Vec3 v) {
		FlightParams p = s.params;
		Vec3 l = s.rot.inverseRotate(v, tH);
		float fwd = -l.z;
		c.strafeX = FlightModel.clamp(l.x / Math.max(1f, p.strafeSpeed), -1f, 1f);
		c.strafeY = FlightModel.clamp(l.y / Math.max(1f, p.verticalSpeed), -1f, 1f);
		return fwd >= 0f ? Math.min(1f, fwd / Math.max(1f, p.fwdSpeed)) : Math.max(-1f, fwd / Math.max(1f, p.revSpeed));
	}

	/** AI Tweaks' attack coordinator, per ship: friends fighting the same target each take their own slice round it. */
	private void coordinate(Sim sim, Ship3D s, Vec3 dir, float range) {
		for (Ship3D o : sim.ships) {
			if (o == s || o.owner != s.owner || o.destroyed || o.fighter || o.ai == null || o.ai.target != target || o.fixedPart()) continue;
			Vec3 ob = tJ.set(o.pos).sub(target.pos);
			float od = ob.length();
			if (od < 1f || od > range * 1.6f) continue;
			ob.scale(1f / od);
			float need = (s.radius + o.radius) * 1.6f / Math.max(1f, range); // its slice (rad)
			float cos = FlightModel.clamp(dir.dot(ob), -1f, 1f);
			float ang = (float) Math.acos(cos);
			if (ang >= need) continue;
			Vec3 away = tK.set(dir).addScaled(ob, -cos);
			if (away.lengthSq() < 1e-6f) away.set(ob).cross(tI.set(0f, 1f, 0f)).scale(s.id.compareTo(o.id) < 0 ? 1f : -1f);
			if (away.lengthSq() < 1e-6f) continue;
			dir.addScaled(away.normalize(), Math.min(1f, (need - ang) * 1.5f)).normalize();
		}
	}

	/**
	 * Backing off to vent (AI Tweaks' vent module): while it isn't safe, away from the threats (turning tail if
	 * it's quick and too slow in reverse), the line jinking every few seconds; once safe, holding just out of its
	 * own reach of the target on the side away from the threats, nose on the target, venting (manageFlux).
	 */
	private float backOffCourse(Sim sim, Ship3D s, Vec3 want, Vec3 toN, float dist, float dt) {
		activity = Activity.COOL_OFF;
		Vec3 away = tF;
		if (threatVec.lengthSq() > 0.5f) away.set(threatVec).scale(-1f);
		else away.set(toN).scale(-1f);
		if (!ventSafe) {
			backoffDist = -1f;
			if (disengage(s, want, toN, dt)) return 1f;
			backoffTurn -= dt;
			if (backoffTurn <= 0f) {
				backoffTurn = 2f + 2f * rng.nextFloat();
				float a = (rng.nextFloat() - 0.5f) * 40f;
				a += 20f * Math.signum(a);
				randomPerpendicular(away, backoffSkew).scale((float) Math.tan(Math.toRadians(a)));
			}
			away.add(backoffSkew).normalize();
			aimAt(s, want);
			return drive(s, tK.set(away).scale(s.params.fwdSpeed));
		}
		float hold = maxRange + target.reachToward(tE.set(toN).scale(-1f));
		backoffDist = backoffDist < 0f ? Math.max(hold, dist * 1.1f) : Math.max(backoffDist, dist);
		aimAt(s, want);
		return driveTo(s, tE.set(toN).scale(-1f), away, dist, backoffDist);
	}

	/** Nose on the lead (hardpoints too). */
	private void aimAt(Ship3D s, Vec3 want) {
		Vec3 lead = tD;
		if (!Combat.interceptCurved(s.pos, s.vel, target.pos, target.vel, target.accel, Math.max(50f, leadSpeed), lead)) lead.set(target.pos);
		want.set(lead).sub(s.pos);
		if (hardpointAim.lengthSq() > 0f) want.sub(s.rot.rotate(hardpointAim.x * s.scale, hardpointAim.y * s.scale, hardpointAim.z * s.scale, tG));
		want.normalize();
	}

	/**
	 * Out of reach of the target and not fighting it: chase. A gun run in progress keeps its own course; a ship
	 * cooling off stays out on purpose.
	 */
	private boolean pursuing(Ship3D s, float gapNow, float range) {
		if (coolingOff) return gapNow > range * 1.6f; // backing off to vent: only a long way out is a chase
		if (!target.fixedPart()) return gapNow > Math.max(range * 1.25f + 100f, reachOut() * 1.05f + target.radius * 0.5f);
		if (standingOff || target.fixedPart()) return gapNow > Math.max(weaponRange, range) * 1.25f;
		return gapNow - target.radius * 0.5f > reachOut() * 1.05f;
	}

	/**
	 * The never-idle bookkeeping, once a frame while fighting: in reach of the target but no gun has fired for
	 * 3 s (a friendly in the way, a hardpoint that can't line up, a target behind its shield): pull the fighting
	 * range in and, after a while, find another angle; chasing without gaining for 6 s: give it up for a nearer
	 * enemy (it's out of favour for 10 s).
	 */
	private void trackStalls(Ship3D s, float gapNow, float dt) {
		boolean inReach = gapNow <= weaponRange;
		boolean canFight = gunDps > 0f && !coolingOff && !(s.flux != null && s.flux.disabled()) && !target.phased;
		if (inReach && canFight && sinceFired > 3f) {
			pressK = Math.max(0.45f, pressK - 0.08f * dt);
			stalled += dt;
			if (stalled > 2.5f && move == Move.HOLD && axis == Axis.NOSE) {
				forceFlank = true;
				planLeft = 0f;
				stalled = 0f;
			}
		} else {
			stalled = 0f;
			if (sinceFired < 0.5f) pressK = Math.min(1f, pressK + 0.04f * dt);
		}
		boolean gaining = lastGap < 0f || gapNow < lastGap - 0.2f * s.params.fwdSpeed * dt;
		if (!inReach && !coolingOff && !gaining) chaseStall += dt;
		else chaseStall = Math.max(0f, chaseStall - 2f * dt);
		lastGap = gapNow;
		if (chaseStall > 6f && (lastOrder == null || lastOrder.kind != Order3D.Kind.ATTACK)) { // an ordered target is chased regardless
			giveUp = target;
			giveUpLeft = 10f;
			retarget = 0f;
			chaseStall = 0f;
		}
	}

	/**
	 * Cooling off, still under guns we can't back out of (too slow in reverse for what's on us, but quicker
	 * going forward): turn tail and run, jinking, until no gun reaches us for a moment, then turn back to face
	 * the fight and vent (manageFlux). Returns false when backing off facing the target will do.
	 */
	private boolean disengage(Ship3D s, Vec3 want, Vec3 toN, float dt) {
		if (target.fixedPart() || s.params == null) return false;
		float chase = target.params != null ? target.params.fwdSpeed : 0f;
		if (!running) {
			cornered = gunsOnUs > 0f && s.params.revSpeed < chase * 0.9f && s.params.fwdSpeed < chase * 1.05f;
			if (gunsOnUs <= 0f || s.params.revSpeed >= chase * 0.9f || s.params.fwdSpeed < chase * 1.05f) return false;
			running = true;
			runQuiet = 0f;
		}
		runQuiet = gunsOnUs > 0f ? 0f : runQuiet + dt;
		if (runQuiet > 1f) {
			running = false;
			return false;
		}
		activity = Activity.DISENGAGE;
		// Away from the target and from everyone else whose guns reach us, a little off the line so the
		// target's shots have to track us.
		want.set(toN).scale(-1f);
		if (awayDir.lengthSq() > 1e-6f) want.addScaled(tH.set(awayDir).normalize(), 0.7f);
		want.addScaled(s.rot.right(tH), strafeSign * 0.25f).normalize();
		c.strafeX = jinkX;
		c.strafeY = jinkY;
		return true;
	}

	/**
	 * The range to fight at right now: the profiled range, breathing in and out over time and per plan, further
	 * off as our flux builds, closer in on a target that can't fight back. Aggression pulls it in while we're
	 * ahead in the duel; the stall breaker pulls it in when we're not getting shots off. Caution keeps a ship
	 * that outranges its target just outside the target's guns; cooling off, every ship backs out of them.
	 */
	private float fightRange(Sim sim, Ship3D s, float fluxLevel) {
		float agg = Math.min(1.5f, aggressionOf(sim.tuning));
		boolean tweaks = !target.fixedPart();
		float r;
		if (tweaks) {
			// AI Tweaks' attack range: where the shortest primary gun just reaches. Vanilla counts range to the target's
			// hull; here a gun fires once the target's centre is in range, so it's the range itself, a little inside.
			r = minRange * 0.92f * (1f + 0.05f * (float) Math.sin(sim.time * 0.23f + phase));
			if (duel > 0f) r *= 1f - 0.15f * agg * duel; // ahead: press in a little
		} else {
			r = engageRange * (1f + rangeBias + 0.1f * (float) Math.sin(sim.time * 0.23f + phase));
			r *= 1f + (0.6f - 0.25f * Math.min(1f, agg)) * Math.max(0f, fluxLevel - 0.5f) / 0.5f; // stand off so the shield and dissipation catch up
			if (duel > 0f) r *= 1f - 0.3f * agg * duel; // ahead: press in
		}
		r *= pressK;
		ShieldFlux tf = target.flux;
		boolean targetHelpless = tf != null && tf.disabled();
		float cau = Math.min(1f, cautionOf(sim.tuning));
		float edge = target.fixedPart() ? 0f : gunReach(target, sim.tuning) + 40f + s.radius * 0.3f; // just outside its guns
		boolean outranged = cau > 0f && !target.fixedPart() && edge <= reachOut() * 0.92f;
		// Overloaded or venting: move in for the kill; unless we outrange it, when our guns already reach
		// it from outside its own (moving in, we'd still be in its face when it comes back).
		if (targetHelpless) r *= outranged ? 1f - 0.4f * (1f - cau) : 0.6f;
		else if (tf != null && tf.level() > 0.8f && fluxLevel < 0.4f) r *= 0.8f; // flux-locked: press
		if (target.fixedPart()) {
			standingOff = false;
			// A station section or module: the range counts from its near side (where the shots land),
			// clear of its real shape (its collision circle can be far bigger than the hull).
			Vec3 out = tF.set(s.pos).sub(target.pos);
			float reach = out.lengthSq() > 1f ? target.reachToward(out.normalize()) : target.radius;
			return Math.max(reach + s.radius * 1.6f + 100f, Math.max(sizeFloor(s), r) + reach * 0.85f);
		}
		standingOff = false;
		if (tweaks) return Math.max(s.radius + target.radius + 100f, r); // backing off is backOffCourse's
		if (!targetHelpless) {
			if (coolingOff && edge > r * 0.95f) {
				// Venting needs the guns off us: back right out of its reach (any pilot; caution only makes it sooner).
				r = Math.max(r, edge);
				standingOff = true;
			} else if (outranged && edge > r * 0.95f && duel < 0.5f) {
				// Kite a target we outrange from just outside its guns, unless we're well ahead in the duel anyway.
				r = Math.max(r, r + (edge - r) * cau);
				standingOff = true;
			}
		}
		return Math.max(s.radius + target.radius + 100f, Math.max(sizeFloor(s), r));
	}

	/** Centre-to-centre distance its main guns reach: their range plus how far out from the centre they sit. */
	private float reachOut() {
		return weaponRange + mountLead;
	}

	/**
	 * Big hulls fight from further off (2.5 radii), but never from beyond most of their guns' reach: at
	 * vanilla ranges a capital at 3D scale (radius 550-600) would otherwise sit out of range for good.
	 */
	private float sizeFloor(Ship3D s) {
		return Math.min(s.radius * 2.5f, reachOut() * 0.85f);
	}

	/** Distance to the target, counted to its near side for a station section or module. */
	private float gap(Ship3D s) {
		return target.pos.distance(s.pos) - Sim.nearSide(target, s.pos);
	}

	/**
	 * Caution: caught well inside the range it's standing off at, a ship that's too slow in reverse to
	 * get clear of its target (but faster going forward) turns away and runs out, off to one side, then
	 * comes about once it's nearly there (or after 8 s).
	 */
	private boolean openRange(Ship3D s, Vec3 toN, float dist, float range, float dt) {
		if (!standingOff || target.params == null) {
			opening = false;
			return false;
		}
		if (!opening) {
			float chase = target.params.fwdSpeed;
			if (dist > range * 0.75f || s.params.revSpeed >= chase * 0.9f || s.params.fwdSpeed < chase * 1.2f) return false;
			opening = true;
			openTime = 0f;
			randomPerpendicular(toN, openSide);
			openSide.y *= 0.3f; // mostly level
		}
		openTime += dt;
		if (dist > range * 0.95f || openTime > 8f) opening = false;
		return opening;
	}

	/** 1 for frigates down to 0.3 for capitals: how often and how hard a hull manoeuvres. */
	static float agility(Ship3D s) {
		if (s.fixedPart()) return 0f; // stations and modules don't move
		switch (s.sizeClass) {
			case "destroyer": return 0.75f;
			case "cruiser": return 0.45f;
			case "capital": return 0.3f;
			default: return 1f;
		}
	}

	/** Picks the next few seconds' plan: hold, flank to a new angle, or gun runs. */
	private void plan(Sim sim, Ship3D s) {
		Tuning t = sim.tuning;
		float ag = agility(s);
		planTarget = target;
		planLeft = (5f + 9f * (1f - ag)) * (0.7f + 0.6f * rng.nextFloat());
		rangeBias = (rng.nextFloat() * 2f - 1f) * 0.2f;
		throttleBias = (rng.nextFloat() * 2f - 1f) * 0.2f;
		orbitTilt = (float) Math.toRadians((rng.nextFloat() * 2f - 1f) * 35f);
		move = Move.HOLD;
		if (axis != Axis.NOSE || target == null) return;
		if (forceFlank) {
			// Stalled in reach (see trackStalls): another angle, straight away.
			forceFlank = false;
			if (pickStation(sim, s)) {
				move = Move.FLANK;
				plans[move.ordinal()]++;
				return;
			}
		}
		float agg = Math.min(1f, aggressionOf(t));
		float fluxLevel = s.flux != null ? s.flux.level() : 0f;
		float targetSpeed = target.fixedPart() || target.params == null ? 0f : target.params.fwdSpeed;
		boolean faster = s.params.fwdSpeed >= targetSpeed * 0.9f; // at least about as quick
		boolean fit = fluxLevel < 0.6f && s.hull > s.hullMax * 0.5f;
		boolean near = target.pos.distance(s.pos) < weaponRange * 1.8f; // runs start from striking distance
		// Runs go after a target whose guns are busy elsewhere, one that can't shoot back right now,
		// or a bigger, slower hull that can't track a frigate flashing past; a head-on run into a
		// same-size ship that's fighting us is how frigates die.
		Vec3 fromTarget = tB.set(s.pos).sub(target.pos).normalize();
		Vec3 guns = target.ai != null && target != sim.player ? target.ai.gunAxisWorld(target, tC) : target.rot.forward(tC);
		boolean onUs = target == sim.player || target.ai != null && target.ai.target == s; // AI-less ships (sandbox props) shoot nobody
		boolean covered = onUs && guns.dot(fromTarget) > 0.3f;
		boolean helpless = target.flux != null && target.flux.disabled();
		boolean bigger = agility(target) < ag - 0.2f;
		boolean station = target.root().anchored; // a run past a station section ends in the station
		// Caution: no runs at a target whose friends have the ground round it under their guns.
		// A target we outrange is fought from outside its guns, not run past; and a run into a bigger hull
		// whose guns are on us is for the bold.
		float cau = cautionOf(t);
		boolean crowded = cau > 0f && cau * (outgunned(exposure(sim, s, target.pos, target, 0f, null)) - 0.35f * agg) > 0.5f;
		float theirs = gunReach(target, t); // an unarmed target's "reach" is harmless: runs are fine
		boolean outranged = cau > 0f && theirs > 0f && theirs + 40f + s.radius * 0.3f <= reachOut() * 0.92f; // even helpless: it may be back before the run's done
		// Aggression: runs past a bigger hull even with its guns on us, and a bit more often.
		if (ag >= 0.75f && faster && fit && near && !station && !crowded && !outranged && (!covered || helpless || bigger && (cau < 0.5f || agg >= 0.75f))
				&& rng.nextFloat() < t.aiPassChance * ag * (0.8f + 0.4f * agg)) {
			move = Move.PASS;
			passStage = 0;
			passTime = 0f;
			passArmed = false;
			passesLeft = 1 + rng.nextInt(2);
			plans[Move.PASS.ordinal()]++;
			planLeft = 40f; // a pass plan ends itself
			randomPerpendicular(tB.set(target.pos).sub(s.pos).normalize(), passSide);
			return;
		}
		if (rng.nextFloat() < t.aiFlankChance && pickStation(sim, s)) move = Move.FLANK;
		plans[move.ordinal()]++;
	}

	/**
	 * A new angle to fight from: the current bearing swung 50-110 deg round the target, the best of
	 * a few tries, preferring the side where its armor is already stripped, then away from its main
	 * guns, and not straight over or under it. Staying put is one of the options (it wins when the
	 * armor facing us is already broken), in which case the plan becomes HOLD.
	 */
	private boolean pickStation(Sim sim, Ship3D s) {
		Vec3 bearing = tB.set(s.pos).sub(target.pos).normalize();
		Vec3 guns = target.ai != null ? target.ai.gunAxisWorld(target, tC) : target.rot.forward(tC);
		float best = stationScore(sim, s, bearing, guns) + 0.15f; // a little inertia: moving costs firing time
		boolean moved = false;
		Vec3 cand = tD, axisV = tE, bestDir = tF.set(bearing);
		// A module: not from inside its structure (between it and the core or the ship it's on).
		Vec3 outward = target.parent != null ? new Vec3(target.pos).sub(target.root().pos) : null;
		if (outward != null) outward.y = 0f;
		if (outward != null && outward.lengthSq() > 1f) outward.normalize();
		else outward = null;
		for (int i = 0; i < 6; i++) {
			randomPerpendicular(bearing, axisV);
			float ang = (float) Math.toRadians(50f + 60f * rng.nextFloat());
			// Rotate the bearing about axisV (perpendicular to it): bearing cos + (axis x bearing) sin.
			cand.set(axisV).cross(bearing).scale((float) Math.sin(ang)).addScaled(bearing, (float) Math.cos(ang));
			cand.y = FlightModel.clamp(cand.y, -0.55f, 0.55f);
			cand.normalize();
			if (outward != null && cand.dot(outward) < 0f) continue;
			float score = stationScore(sim, s, cand, guns) + rng.nextFloat() * 0.4f;
			if (score > best) {
				best = score;
				bestDir.set(cand);
				moved = true;
			}
		}
		target.rot.inverseRotate(bestDir, stationLocal);
		return moved;
	}

	/**
	 * How good it is to fight from world direction dir (from the target): broken armor, away from its guns,
	 * fairly level, and (caution) out of reach of the other enemies' guns.
	 */
	private float stationScore(Sim sim, Ship3D s, Vec3 dir, Vec3 guns) {
		float score = (1f - armorFacing(target, dir)) * 2f - dir.dot(guns) * 0.6f - Math.abs(dir.y) * 0.4f;
		float cau = cautionOf(sim.tuning);
		if (cau > 0f) {
			Vec3 spot = tH.set(target.pos).addScaled(dir, Math.max(engageRange, target.pos.distance(s.pos)));
			score -= cau * 0.8f * Math.min(2f, outgunned(exposure(sim, s, spot, target, 0f, null)));
		}
		return score;
	}

	/** Fraction (0..1) of armor left on the part of ship t that faces world direction dir. */
	static float armorFacing(Ship3D t, Vec3 dir) {
		if (t.armor == null || t.armor.rating <= 0f) return 1f;
		Vec3 local = t.rot.inverseRotate(dir, new Vec3());
		int surface = local.y > 0.6f ? 0 : local.y < -0.6f ? 2 : 1;
		return t.armor.armorAt(ArmorShell.sector(local.x, local.z, surface)) / t.armor.rating;
	}

	/** The world direction a ship's main guns face (its nose, or the broadside it fights with). */
	public Vec3 gunAxisWorld(Ship3D s, Vec3 out) {
		switch (axis) {
			case PORT: return s.rot.rotate(-1f, 0f, 0f, out);
			case STARBOARD: return s.rot.rotate(1f, 0f, 0f, out);
			default: return s.rot.forward(out);
		}
	}

	/** FLANK: strafe round the target toward the station, keeping the nose on it; HOLD once there. */
	private void flank(Ship3D s, Vec3 toN) {
		Vec3 station = target.rot.rotate(stationLocal, tE);
		Vec3 bearing = tF.set(toN).scale(-1f);
		float cos = FlightModel.clamp(station.dot(bearing), -1f, 1f);
		float angle = (float) Math.acos(cos);
		if (angle < (float) Math.toRadians(12f)) {
			move = Move.HOLD;
			return;
		}
		// Slide along the sphere round the target: the station minus its part along the bearing.
		Vec3 slide = station.addScaled(bearing, -cos);
		if (slide.lengthSq() < 1e-6f) randomPerpendicular(bearing, slide);
		slide.normalize();
		Vec3 local = s.rot.inverseRotate(slide, tF);
		float k = Math.min(1f, angle / (float) Math.toRadians(35f));
		c.strafeX = FlightModel.clamp(local.x * k + jinkX * 0.3f, -1f, 1f);
		c.strafeY = FlightModel.clamp(local.y * k + jinkY * 0.3f, -1f, 1f);
	}

	/**
	 * PASS: run in at full speed (guns on the lead, then on a point beside the target), past it,
	 * out to fighting range, come about and go again. Drops back to HOLD when the runs are done, the
	 * ship is hurt or flux is building. Returns the throttle.
	 */
	private float passCourse(Sim sim, Ship3D s, Vec3 want, Vec3 toN, float dist, float range, float fluxLevel, float dt) {
		passTime += dt;
		if (fluxLevel > 0.9f || s.hull < s.hullMax * 0.35f || passTime > 20f || dist > weaponRange * 2.5f) {
			passAborts++;
			move = Move.HOLD;
			planLeft = 3f + 3f * rng.nextFloat();
			return 0f;
		}
		Vec3 fwd = s.rot.forward(tD);
		float clearance = (s.radius + target.radius) * 2f + 60f;
		switch (passStage) {
			case 0: { // run in
				passSide.addScaled(toN, -passSide.dot(toN));
				if (passSide.lengthSq() < 1e-4f) randomPerpendicular(toN, passSide);
				passSide.normalize();
				Vec3 lead = tE;
				if (!Combat.interceptCurved(s.pos, s.vel, target.pos, target.vel, target.accel, Math.max(50f, leadSpeed), lead)) lead.set(target.pos);
				lead.sub(s.pos).normalize();
				Vec3 by = tF.set(target.pos).addScaled(passSide, clearance).sub(s.pos).normalize();
				float w = FlightModel.clamp((dist - range * 0.5f) / Math.max(1f, range * 0.5f), 0f, 1f);
				want.set(by).scale(1f - w).addScaled(lead, w).normalize();
				c.strafeX = jinkX * 0.5f;
				c.strafeY = jinkY * 0.5f;
				if (toN.dot(fwd) > 0.8f) passArmed = true; // lined up: from here on, turning away means we're past
				if (dist < clearance * 1.3f || passArmed && dist < range && toN.dot(fwd) < 0.2f) {
					passStage = 1;
					passTime = 0f;
					passesMade++;
					// Out the far side, peeling off a little to the pass side and up or down.
					passExit.set(fwd).addScaled(passSide, 0.35f).addScaled(tE.set(0f, 1f, 0f), (rng.nextFloat() * 2f - 1f) * 0.35f).normalize();
				}
				return 1f;
			}
			case 1: // run out
				want.set(passExit);
				if (dist > range * (0.7f + 0.3f * rng.nextFloat()) || passTime > 4f) {
					passStage = 2;
					passTime = 0f;
				}
				return 1f;
			default: { // come about
				want.set(toN);
				if (toN.dot(fwd) > 0.85f) {
					if (--passesLeft > 0 && fluxLevel < 0.6f) {
						passStage = 0;
						passTime = 0f;
						passArmed = false;
						// Next run on a different side.
						Vec3 turned = tE.set(toN).cross(passSide).normalize();
						float a = (float) Math.toRadians(90f + 90f * rng.nextFloat());
						passSide.scale((float) Math.cos(a)).addScaled(turned, (float) Math.sin(a)).normalize();
					} else {
						move = Move.HOLD;
						planLeft = 3f + 4f * rng.nextFloat();
					}
				}
				return 0.55f; // tighter turn
			}
		}
	}

	/**
	 * The weave: every 1-4 s (slower for big hulls) the sideways/vertical strafe swings to a new
	 * direction and strength, eased in. Heavy incoming fire triggers a jink across its path.
	 */
	private void weave(Sim sim, Ship3D s, float dt) {
		Tuning t = sim.tuning;
		float ag = agility(s);
		float max = t.aiWeave * (0.4f + 0.6f * ag);
		jinkLeft -= dt;
		dodgeCool -= dt;
		boolean dodge = threatNow > s.hullMax * 0.015f && dodgeCool <= 0f && threatDir.lengthSq() > 1e-6f;
		if (dodge) {
			dodgeCool = 1.2f + 2.5f * (1f - ag) + 0.6f * rng.nextFloat();
			Vec3 local = s.rot.inverseRotate(tE.set(threatDir).normalize(), tE);
			float px = -local.y, py = local.x;
			float len = (float) Math.hypot(px, py);
			if (len < 0.2f) {
				float a = rng.nextFloat() * 6.283f;
				px = (float) Math.cos(a);
				py = (float) Math.sin(a);
				len = 1f;
			}
			float sign = rng.nextBoolean() ? 1f : -1f;
			jinkGoalX = px / len * sign * max;
			jinkGoalY = py / len * sign * max * 0.7f / t.verticalMult; // weave at the vertical pace it had before up/down got faster
			jinkLeft = 1f + 0.8f * rng.nextFloat();
		} else if (jinkLeft <= 0f) {
			float a = rng.nextFloat() * 6.283f;
			float mag = Math.min(max, t.aiStrafe * 0.5f) + (max - Math.min(max, t.aiStrafe * 0.5f)) * rng.nextFloat();
			jinkGoalX = (float) Math.cos(a) * mag;
			jinkGoalY = (float) Math.sin(a) * mag * 0.6f / t.verticalMult;
			jinkLeft = (1.2f + 2.5f * rng.nextFloat()) * (1.6f - 0.6f * ag);
		}
		float ease = (dodge ? 4f : 1.5f) * dt;
		jinkX = FlightModel.approach(jinkX, jinkGoalX, ease);
		jinkY = FlightModel.approach(jinkY, jinkGoalY, ease);
	}

	/** Which part of the plan is being flown: HOLD, FLANK, or PASS-in / PASS-out / PASS-about. */
	public String phaseName() {
		if (move != Move.PASS) return move.name();
		return passStage == 0 ? "PASS-in" : passStage == 1 ? "PASS-out" : "PASS-about";
	}

	/** Debug line for the arena's idle traces: range, throttle, closing speed, steering and caution state. */
	public String debugState(Ship3D s) {
		if (target == null) return activity + " no target, throttle " + String.format("%.2f", s.throttle);
		Vec3 to = new Vec3(target.pos).sub(s.pos);
		float d = to.length();
		to.scale(1f / Math.max(1f, d));
		return String.format("%s %s dist %.0f range %.0f wr %.0f thr %.2f v %.0f/%.0f closing %.0f fwd.to %.2f flux %.2f cool %b standOff %b urge %.2f gunsOnUs %.0f others %.0f tgtVelAway %.0f",
				activity, axis, d, engageRange, weaponRange, s.throttle, s.speed(), s.params.fwdSpeed, s.vel.dot(to) - target.vel.dot(to), s.rot.forward(new Vec3()).dot(to),
				s.flux != null ? s.flux.level() : 0f, coolingOff, standingOff, gunsOnUsOthers > 0f ? cautionUrgeDebug : 0f, gunsOnUs, gunsOnUsOthers, target.vel.dot(to));
	}

	private float cautionUrgeDebug;

	/** Debug line for traces. */
	public String debugMove(Ship3D s) {
		if (target == null) return move + " no target";
		Vec3 to = new Vec3(target.pos).sub(s.pos);
		float d = to.length();
		return String.format("%s stage %d t %.1f dist %.0f range %.0f facing %.2f flux %.2f armed %b tgt %s left %.1f", move, passStage, passTime, d, engageRange,
				to.scale(1f / Math.max(1f, d)).dot(s.rot.forward(new Vec3())), s.flux != null ? s.flux.level() : 0f, passArmed, target.id, planLeft);
	}

	/** A random unit vector perpendicular to n (unit). */
	private Vec3 randomPerpendicular(Vec3 n, Vec3 out) {
		Vec3 helper = Math.abs(n.y) < 0.9f ? new Vec3(0f, 1f, 0f) : new Vec3(1f, 0f, 0f);
		Vec3 a = new Vec3(n).cross(helper).normalize();
		Vec3 b = new Vec3(n).cross(a).normalize();
		float ang = rng.nextFloat() * 6.283f;
		return out.set(a).scale((float) Math.cos(ang)).addScaled(b, (float) Math.sin(ang));
	}

	/**
	 * Shield up while an enemy has us within its reach and flux is comfortable (shields take seconds
	 * to unfold, so waiting for a shot to be on its way is too late), and whenever fire is inbound (and
	 * for a moment after); down when quiet and flux is high, so hard flux drains. Vent when flux is high
	 * and nothing is inbound, or when it's nearly full and the shield is about to give anyway.
	 */
	private void manageFlux(Sim sim, Ship3D s, float dt) {
		ShieldFlux f = s.flux;
		float threat = sim.threat(s, threatDir);
		threatNow = threat;
		if (f == null || f.disabled()) return;
		quiet = threat > 0f ? 0f : quiet + dt;
		if (f.hasShield()) {
			boolean exposed = f.level() < sim.tuning.aiShieldFlux && underGuns(sim, s, f.unfoldTime);
			boolean want = threat > 0f || exposed || quiet < 0.6f && f.shieldOn;
			if (f.level() > 0.97f && threat <= 0f) want = false;
			// Nearly full: take the hit on armor rather than overload, while the hull can afford it.
			float roomFlux = (f.maxFlux - f.flux) / Math.max(1e-3f, f.fluxPerDamage);
			if (f.level() > 0.9f && threat > roomFlux * 0.5f && s.hull > s.hullMax * 0.5f) want = false;
			if (want != f.shieldOn) f.toggleShield();
		}
		if (s.fighter) {
			if (f.level() > 0.7f && quiet > 0.5f) f.vent();
			return;
		}
		// Vent whenever it's safe (safeToVent) and worth it: backing off to cool, flux at half or more, or a
		// third with nothing to shoot at; never in the middle of finishing a target. Checked four times a second.
		float level = f.level();
		// AI Tweaks' vent rules: vent whenever it's safe (safeToVent, with a second's optimism on the vent's length) and
		// worth it: backing off, flux at half or more behind a shield, a shieldless ship with a long vent ahead between
		// shots, a third with nothing to do. Never in the middle of finishing a target.
		boolean idleNow = sinceFired > 2f && gunsOnUs <= 0f && (target == null || gap(s) > weaponRange);
		ventCheck -= dt;
		if (ventCheck <= 0f) {
			ventCheck = 0.25f;
			float vt = ventTime(sim, f);
			ventSafe = safeToVent(sim, s, Math.max(0f, vt - 1f));
			boolean worth = coolingOff || f.hasShield() && level >= 0.5f || !f.hasShield() && vt > 2f && sinceFired > 1f || level >= 0.3f && idleNow;
			// Station sections and modules can't step back out of the fight: they vent only when it's needed.
			if (s.fixedPart()) worth = level >= 0.8f;
			if (worth && level > 0.05f && !finishing && ventSafe && f.canVent()) f.vent();
		}
	}

	/**
	 * True if an enemy has this ship within its weapons' reach, or will before a shield raised now is
	 * fully up (closing speed x unfold time).
	 */
	private static boolean underGuns(Sim sim, Ship3D s, float unfoldTime) {
		for (Ship3D o : sim.ships) {
			if (o.owner == s.owner || o.destroyed || o.phased || o.weapons.isEmpty()) continue;
			float dx = o.pos.x - s.pos.x, dy = o.pos.y - s.pos.y, dz = o.pos.z - s.pos.z;
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			float closing = dist > 1e-3f ? -((o.vel.x - s.vel.x) * dx + (o.vel.y - s.vel.y) * dy + (o.vel.z - s.vel.z) * dz) / dist : 0f;
			if (dist - s.radius <= reachOf(o, sim.tuning) + Math.max(0f, closing) * unfoldTime) return true;
		}
		return false;
	}

	/**
	 * Throttle that holds the fighting range by matching the target's speed along the line between
	 * the ships, closing or opening at a rate that can still brake in time (no overshoot, no dash in
	 * and out). minThrottle: how hard it may back off (-0.5 normally, -1 = full reverse).
	 */
	private float rangeThrottle(Ship3D s, Vec3 toN, float dist, float range, float minThrottle) {
		FlightParams p = s.params;
		float e = dist - range;
		float rate = Math.min(p.fwdSpeed, Math.min((float) Math.sqrt(2f * Math.max(1f, p.fwdDecel) * Math.abs(e)) * 0.8f, Math.abs(e) * 0.6f));
		float targetAlong = target.vel.dot(toN); // how fast the target moves away from us
		float wantAlong = targetAlong + Math.signum(e) * rate;
		// Standing off (caution): back away as fast as needed, up to full reverse (reverse throttle is a share of reverse speed).
		if (wantAlong < 0f && minThrottle < -0.5f) return FlightModel.clamp(wantAlong / Math.max(1f, p.revSpeed), minThrottle, 0f);
		return FlightModel.clamp(wantAlong / Math.max(1f, p.fwdSpeed), minThrottle, 1f);
	}

	/**
	 * Keeps the fight together: a ship far from its own fleet with nothing to fight drifts back toward it
	 * (unless it's been ordered somewhere); one with a target, or hunting, is never pulled off it (it used to be,
	 * while its target was out of reach, which stalled chases half-way). Nobody leaves the battle area or the
	 * altitude band around the fight.
	 */
	private void stayWithFight(Sim sim, Ship3D s, Vec3 want, Order3D order) {
		Tuning t = sim.tuning;
		Vec3 pull = tB.set(0f, 0f, 0f);
		Vec3 home = sim.mobileCentre(s.owner);
		boolean sent = order != null && order.kind != Order3D.Kind.AVOID; // ordered somewhere: that's where it belongs
		if (home != null && !sent) {
			float d = home.distance(s.pos);
			boolean busy = target != null || activity == Activity.HUNT;
			if (!busy && d > t.aiCohesion) pull.add(tC.set(home).sub(s.pos).normalize().scale(Math.min(1.5f, (d - t.aiCohesion) / t.aiCohesion + 0.5f)));
		}
		if (sim.arenaHalfX > 0f) {
			float ox = Math.abs(s.pos.x) - sim.arenaHalfX * 0.85f, oz = Math.abs(s.pos.z) - sim.arenaHalfZ * 0.85f;
			if (ox > 0f) pull.x -= Math.signum(s.pos.x) * Math.min(2f, ox / 500f);
			if (oz > 0f) pull.z -= Math.signum(s.pos.z) * Math.min(2f, oz / 500f);
		}
		if (!sent) { // an order's point has its own height (tactical view): no pull back to the fight's altitude
			float centreY = sim.fightAltitude();
			float oy = Math.abs(s.pos.y - centreY) - t.aiAltitudeBand;
			if (oy > 0f) pull.y -= Math.signum(s.pos.y - centreY) * Math.min(2f, oy / 400f);
		}
		if (pull.lengthSq() > 0f) want.add(pull).normalize();
	}

	/**
	 * When to use the ship system and phase cloak, per kind. Toggles are switched off again when
	 * their reason goes away.
	 */
	private void manageSystems(Sim sim, Ship3D s) {
		useSystem(sim, s, s.system);
		ShipSystem3D cloak = s.cloak;
		if (cloak != null && cloak.kind != ShipSystem3D.Kind.PHASE_CLOAK) {
			useSystem(sim, s, cloak); // a right-click system that isn't a cloak (Invictus: Canister Flak)
		} else if (cloak != null) {
			float threat = threatNow;
			float level = s.flux != null ? s.flux.level() : 0f;
			// Phase out of heavy incoming fire; come back to shoot when it's quiet, or before flux runs out.
			boolean want = cloak.on() ? (threat > 0f || quiet < 0.5f) && level < 0.85f : threat > s.hullMax * 0.02f && level < 0.6f;
			if (want != cloak.on() && (want ? cloak.ready(s) : true)) cloak.activate(s);
		}
	}

	private void useSystem(Sim sim, Ship3D s, ShipSystem3D sys) {
		if (sys == null || !sys.supported()) return;
		boolean want = wantSystem(sim, s, sys);
		if (sys.toggle) {
			if (want != sys.on() && (want ? sys.ready(s) : true)) sys.activate(s);
		} else if (want && sys.ready(s)) {
			sys.activate(s);
		}
	}

	private boolean wantSystem(Sim sim, Ship3D s, ShipSystem3D sys) {
		float dist = target != null ? gap(s) : Float.MAX_VALUE;
		float level = s.flux != null ? s.flux.level() : 0f;
		boolean inRange = dist < weaponRange;
		boolean helpless = s.flux != null && s.flux.disabled();
		Vec3 fwd = s.rot.forward(new Vec3());
		boolean facing = target != null && new Vec3(target.pos).sub(s.pos).normalize().dot(fwd) > 0.97f;
		switch (sys.kind) {
			case BURN_DRIVE:
				// Straight-line dash: close a long gap while lined up, or run when retreating.
				return s.retreatOrdered || (target != null && facing && dist > engageRange * 2f && (sys.on() ? dist > engageRange * 1.3f : true));
			case MICROBURN:
			case PLASMA_JETS:
				return s.retreatOrdered || helpless || target != null && dist > engageRange * 1.6f && facing;
			case MANEUVERING_JETS:
				return target != null && (dist < weaponRange * 1.2f || dist > engageRange * 1.6f);
			case DISPLACER:
				return threatNow > s.hullMax * 0.03f;
			case AMMO_FEED:
			case HIGH_ENERGY_FOCUS:
			case TEMPORAL_SHELL:
				return inRange && level < 0.7f && target != null && !target.phased;
			case FORTRESS_SHIELD:
				return s.flux != null && s.flux.shieldOn && (threatNow > 0f || quiet < 1f) && level > 0.5f;
			case DAMPER: {
				boolean shieldCovers = s.flux != null && s.flux.shieldUp();
				return !shieldCovers && threatNow > s.hullMax * 0.01f;
			}
			case EMP_EMITTER: {
				float r = sys.range * sim.tuning.weaponRangeMult;
				for (Ship3D o : sim.ships) if (o.owner != s.owner && !o.destroyed && o.pos.distance(s.pos) - o.radius < r * 0.8f) return true;
				return false;
			}
			case FLARES:
				for (Missile3D m : sim.combat.missiles) {
					if (m.owner != s.owner && !m.flare && m.target == s && m.spec.guided && m.pos.distance(s.pos) < 1500f) return true;
				}
				return false;
			case FAST_MISSILE_RACKS: {
				float have = 0f, max = 0f;
				for (WeaponMount m : s.weapons) {
					if (m.spec.kind != WeaponSpec3D.Kind.MISSILE || m.spec.maxAmmo <= 0) continue;
					have += m.ammo;
					max += m.spec.maxAmmo;
				}
				return max > 0f && have < max * 0.4f;
			}
			case MISSILE_AUTOFORGE:
				return true; // only usable once a rack is low (ShipSystem3D checks)
			case ENTROPY_AMPLIFIER:
			case DRONE_STRIKE:
				// Both check reach and target themselves; use them once actually fighting it.
				return target != null && !target.phased && dist < weaponRange * 1.1f;
			case MINE_STRIKE: {
				if (target == null || target.phased || target.surfaceDistance(s.pos) >= sys.range * sim.tuning.weaponRangeMult) return false;
				for (Missile3D m : sim.combat.missiles) if (m.source == s && m.spec.mine) return false; // one mine at a time
				return true;
			}
			case PHASE_TELEPORTER:
				// Jump in to close a long gap, or out of a beating.
				return target != null && dist > engageRange * 1.8f || threatNow > s.hullMax * 0.04f && s.hull < s.hullMax * 0.5f;
			case CANISTER_FLAK: {
				// Throw the cloud when missiles are about a second and a half out (canisters only last a few seconds), or fighters are close.
				for (Missile3D m : sim.combat.missiles) {
					if (m.owner == s.owner || m.flare || m.flamedOut() || m.spec.mine) continue;
					if (m.pos.distance(s.pos) - s.radius < Math.max(250f, m.vel.length() * 1.5f)) return true;
				}
				for (Ship3D o : sim.ships) {
					if (o.fighter && o.owner != s.owner && !o.destroyed && o.pos.distance(s.pos) - s.radius < 500f) return true;
				}
				return false;
			}
			case TARGETING_FEED:
				for (Ship3D f : sim.ships) {
					if (!f.fighter || f.carrier != s || f.destroyed || f.ai == null || f.ai.target == null) continue;
					if (f.pos.distance(f.ai.target.pos) < 2500f) return true; // its wings are on someone
				}
				return false;
			case COMBAT_BURN:
				// A gentler, steerable burn: on to close a gap or to run, off once it's in the fight.
				return s.retreatOrdered || target != null && dist > engageRange * (sys.on() ? 1.2f : 1.6f)
						&& new Vec3(target.pos).sub(s.pos).normalize().dot(fwd) > 0.9f;
			case ORION_DEVICE:
			case NOVA_BURST:
				// One press fires the whole train of jolts: lined up on a far target, or running for the edge.
				return sys.orion != null && !sys.orion.busy() && (s.retreatOrdered || target != null && facing && dist > engageRange * 1.8f);
			case RESERVE_DEPLOYMENT:
				for (Ship3D f : sim.ships) {
					if (f.fighter && f.carrier == s && !f.destroyed && f.ai != null && f.ai.target != null && f.pos.distance(f.ai.target.pos) < 2500f) return true;
				}
				return target != null && dist < weaponRange * 1.5f;
			case ACAUSAL_DISRUPTOR: {
				// Knock the target's shield down (and its guns off) just as ours can follow up.
				if (target == null || target.fighter || target.flux == null || target.flux.disabled() || !inRange) return false;
				float reach = sys.range * sim.tuning.weaponRangeMult + s.radius + target.radius;
				return target.pos.distance(s.pos) <= reach && (target.flux.shieldUp() || threatNow > 0f);
			}
			case ENERGY_LASH: {
				// Lash the friendly unit whose system would help most right now; else lash the target.
				ExoticSystems ex = sys.ex;
				float reach = sys.range * sim.tuning.weaponRangeMult;
				Ship3D pick = null;
				float best = 0.5f;
				for (Ship3D o : sim.ships) {
					if (o == s || o.owner != s.owner || !ExoticSystems.validLash(s, o) || o.pos.distance(s.pos) > reach + s.radius + o.radius) continue;
					float u = ExoticSystems.lashUsefulness(s, o, sim);
					if (u > best) {
						best = u;
						pick = o;
					}
				}
				if (pick == null && target != null && !target.fighter && target.pos.distance(s.pos) <= reach + s.radius + target.radius) pick = target;
				ex.lashPick = pick;
				return pick != null;
			}
			case CONSTRUCTION_SWARM:
				return true; // usable only with the fragments for a build and none under way (ExoticSystems)
			case CONVULSIVE_LUNGE:
				return target != null && dist > weaponRange * 0.6f && dist < weaponRange + sys.ex.lungeDistance && !target.phased;
			case DARKENED_GAZE: {
				float reach = 0f;
				for (WeaponMount m : s.gazeBeams) reach = Math.max(reach, m.spec.range * sim.tuning.weaponRangeMult);
				return target != null && !target.phased && new Vec3(target.pos).sub(s.pos).normalize().dot(fwd) > 0.9f && dist < reach;
			}
			case TENEBROUS_EXPULSION:
				return target != null && !target.phased && dist < 2000f;
			case MOTE_CONTROL:
				return target != null && !target.fighter && sys.motes != null && !sys.motes.attracting() && sys.motes.list.size() >= 10
						&& target.pos.distance(s.pos) < sys.motes.range * sim.tuning.weaponRangeMult + target.radius;
			case FRAGMENT_VOLLEY:
			case INCURSION_MODE:
			case EXTRACTION_PROTOCOL:
				return false; // only an Overseer's lash fires these
			case LIDAR_ARRAY:
				// Long wind-up with the guns silent: only when lined up on a target its hardpoints will reach.
				return target != null && facing && dist < weaponRange * (1f + sys.lidarRange) && level < 0.6f && !target.phased;
			default:
				return false; // RECALL and anything else: the player's call
		}
	}

	/**
	 * Phase Teleporter: where an AI jumps. Hurt and under fire: straight away from the fire. Otherwise
	 * to its fighting distance from the target, off to one side of the line between them (a flank).
	 */
	public Vec3 teleportDestination(Sim sim, Ship3D s, float reach) {
		if (threatNow > s.hullMax * 0.04f && s.hull < s.hullMax * 0.5f && threatDir.lengthSq() > 1e-6f) {
			return new Vec3(s.pos).addScaled(new Vec3(threatDir).normalize(), -reach);
		}
		if (target == null) return null;
		Vec3 from = new Vec3(s.pos).sub(target.pos);
		if (from.lengthSq() < 1e-6f) from.set(homeDir);
		from.normalize();
		Vec3 side = new Vec3(from).cross(new Vec3(0f, 1f, 0f));
		if (side.lengthSq() < 1e-6f) side.set(1f, 0f, 0f);
		side.normalize().scale(strafeSign);
		Vec3 dir = from.scale(0.6f).addScaled(side, 0.8f).normalize();
		return new Vec3(target.pos).addScaled(dir, engageRange + target.radius);
	}

	// ---- targeting after AI Tweaks (the user's reference AI; public domain), rebuilt for 3D ----

	/** A hull's weight in fights, like vanilla deployment points: frigate 5, destroyer 10, cruiser 18, capital 35. */
	static float deployWeight(Ship3D o) {
		switch (o.sizeClass) {
			case "destroyer": return 10f;
			case "cruiser": return 18f;
			case "capital":
			case "station": return 35f;
			default: return 5f;
		}
	}

	/** Frigates, destroyers and anything quick (AI Tweaks: 150 vanilla speed): they may hunt small fry anywhere. */
	static boolean fast(Ship3D o, Tuning t) {
		return "frigate".equals(o.sizeClass) || "destroyer".equals(o.sizeClass) || o.params != null && o.params.maxSpeed >= 150f;
	}

	/**
	 * The enemy fleet split into battle groups (AI Tweaks' fleet segmentation): ships within 2500 of each other
	 * belong together, but a frigate can't link two groups. The primary group is the one with the most weight;
	 * others count if they hold a capital or a quarter of its weight. Refreshed once a second per side.
	 */
	private static final class Groups {
		float time = -99f;
		final java.util.List<Ship3D> primary = new java.util.ArrayList<>(), all = new java.util.ArrayList<>();
	}

	private static final java.util.Map<Sim, Groups[]> GROUPS = new java.util.WeakHashMap<>();

	private static Groups groupsAgainst(Sim sim, int owner) {
		Groups[] pair = GROUPS.computeIfAbsent(sim, k -> new Groups[] {new Groups(), new Groups()});
		Groups g = pair[owner == 0 ? 0 : 1];
		if (sim.time - g.time < 1f && sim.time >= g.time) return g;
		g.time = sim.time;
		g.primary.clear();
		g.all.clear();
		java.util.List<Ship3D> fleet = new java.util.ArrayList<>();
		for (Ship3D o : sim.ships) if (o.owner != owner && !o.destroyed && !o.fighter && !o.invulnerable && !o.unarmedPart()) fleet.add(o);
		int n = fleet.size();
		if (n == 0) return g;
		int[] grp = new int[n];
		for (int i = 0; i < n; i++) grp[i] = i;
		float link = 2500f;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				if ("frigate".equals(fleet.get(j).root().sizeClass)) continue; // can't link through a frigate
				if ("frigate".equals(fleet.get(i).root().sizeClass) && grp[i] != i) continue; // a frigate joins one group
				if (grp[i] == grp[j] || fleet.get(i).pos.distance(fleet.get(j).pos) > link) continue;
				int from = grp[i], to = grp[j];
				for (int k = 0; k < n; k++) if (grp[k] == from) grp[k] = to;
			}
		}
		float[] dp = new float[n];
		boolean[] capital = new boolean[n];
		for (int i = 0; i < n; i++) {
			dp[grp[i]] += deployWeight(fleet.get(i));
			if ("capital".equals(fleet.get(i).root().sizeClass)) capital[grp[i]] = true;
		}
		int best = 0;
		for (int i = 1; i < n; i++) if (dp[i] > dp[best]) best = i;
		for (int i = 0; i < n; i++) {
			int k = grp[i];
			if (k == best) g.primary.add(fleet.get(i));
			if (k == best || capital[k] || dp[k] * 4f >= dp[best]) g.all.add(fleet.get(i));
		}
		return g;
	}

	/** Where its guns can reach from: 1.5x the longest primary gun (AI Tweaks' attack search range). */
	private float searchRange() {
		return Math.max(maxRange, weaponRange) * 1.5f;
	}

	/**
	 * Target picking after AI Tweaks: with enemies within reach of a fight, the best-scoring one (attackScore);
	 * with none, a target to move on: the nearest of the enemy's battle groups for a quick ship, the nearest big
	 * ship of its main group for a slow one (big ships don't chase lone frigates to the edge of the map), else
	 * the nearest enemy. Orders still pick or limit targets as before.
	 */
	private Ship3D pickTarget(Sim sim, Ship3D s) {
		Order3D order = currentOrder(sim, s);
		if (order != null && order.kind == Order3D.Kind.ATTACK) return order.ship;
		float search = searchRange();
		Ship3D best = null;
		float bestScore = -Float.MAX_VALUE;
		boolean guard = order != null && huntUnder(order);
		for (Ship3D o : sim.ships) {
			if (o.destroyed || o.owner == s.owner || o.fighter || o.invulnerable || o.unarmedPart()) continue;
			if (order != null && !allowedTarget(order, o)) continue;
			if (o.surfaceDistance(s.pos) > search) continue;
			float e = attackScore(sim, s, o);
			if (guard) e -= Math.max(0f, o.pos.distance(order.anchor()) - leash(order)) / Math.max(1f, maxRange);
			if (o == giveUp) e -= 2f;
			if (e > bestScore) {
				bestScore = e;
				best = o;
			}
		}
		if (best != null) return best;
		// Nothing to fight here: something to move on.
		boolean quick = fast(s, sim.tuning);
		Groups g = groupsAgainst(sim, s.owner);
		Ship3D near = null;
		float nearD = Float.MAX_VALUE;
		for (int pass = 0; pass < 3 && near == null; pass++) {
			java.util.List<Ship3D> pool = pass == 0 ? g.all : pass == 1 ? g.primary : sim.ships;
			for (Ship3D o : pool) {
				if (o.destroyed || o.owner == s.owner || o.fighter || o.invulnerable || o.unarmedPart()) continue;
				if (order != null && !allowedTarget(order, o)) continue;
				boolean big = !"frigate".equals(o.root().sizeClass);
				// Quick ships take any group's ship; slow ones a group's big ships, and another group only if it's close.
				if (pass < 2 && !quick && !big) continue;
				float d = o.pos.distance(s.pos);
				if (pass == 0 && !quick && !g.primary.contains(o) && d > Math.max(threatRange(), maxRange * 2f)) continue;
				if (o == giveUp) d *= 3f;
				if (d < nearD) {
					nearD = d;
					near = o;
				}
			}
		}
		return near;
	}

	/**
	 * How good a target o is to shoot (higher is better), after AI Tweaks' evaluateTarget: near the guns'
	 * line (seconds of turning), within the guns' reach (more so where all of them reach), high on flux (a phase
	 * ship low on flux is a waste of time), no Damper brick, big ships for big slow ships, one friends are on
	 * already, one that can't fight back, the one we're on if it's still in front of the guns; an ordered one
	 * above all, and never one behind a friendly hull.
	 */
	private float attackScore(Sim sim, Ship3D s, Ship3D o) {
		float e = 0f;
		Vec3 to = tK.set(o.pos).sub(s.pos);
		float d = Math.max(1f, to.length());
		to.scale(1f / d);
		float off = (float) Math.acos(FlightModel.clamp(to.dot(gunAxisWorld(s, tJ)), -1f, 1f));
		e -= off / Math.max(0.05f, s.params != null ? s.params.yawRate : 1f) * 0.2f;
		float gapTo = d - o.reachToward(tJ.set(to).scale(-1f));
		float frac = dpsFraction(gapTo + 10f);
		e -= gapTo / Math.max(1f, maxRange) / Math.max(0.05f, frac);
		float flux = o.flux != null ? o.flux.level() : 0f;
		boolean phase = o.cloak != null && o.cloak.kind == ShipSystem3D.Kind.PHASE_CLOAK;
		e -= (1f - flux) * (phase ? 2f : 0.5f);
		if (o.system != null && o.system.kind == ShipSystem3D.Kind.DAMPER && !"frigate".equals(o.root().sizeClass)) e -= 1f;
		if (!fast(s, sim.tuning) && ("cruiser".equals(s.sizeClass) || "capital".equals(s.sizeClass))) {
			String c = o.root().sizeClass;
			if ("frigate".equals(c)) e -= 1f;
			else if ("cruiser".equals(c)) e += 0.5f;
			else if ("capital".equals(c) || "station".equals(c)) e += 1f;
		}
		if (!"frigate".equals(o.root().sizeClass)) {
			for (Ship3D f : sim.ships) {
				if (f != s && f.owner == s.owner && !f.destroyed && !f.fighter && f.ai != null && f.ai.target == o && !"frigate".equals(f.sizeClass)) {
					e += 1f; // finish what friends are on
					break;
				}
			}
		}
		if (o.flux != null && o.flux.disabled()) e += 2f;
		if (o == target && gapTo - 10f <= weaponRange && off < (float) Math.toRadians(60f)) e += 1f;
		if (o == s.lastHitBy) e += 0.3f;
		if (friendlyBetween(sim, s, o, d)) e -= 16f;
		return e;
	}

	/** A friendly hull (no fighters or quick ships, which move out of the way) between us and o. */
	private static boolean friendlyBetween(Sim sim, Ship3D s, Ship3D o, float d) {
		for (Ship3D f : sim.ships) {
			if (f == s || f.owner != s.owner || f.destroyed || f.fighter || Combat.sameStructure(f, s) || fast(f, sim.tuning)) continue;
			if (f.pos.distance(s.pos) > d) continue;
			if (Combat.segmentSphere(s.pos, o.pos, f.pos, f.radius * 0.8f) >= 0f) return true;
		}
		return false;
	}

	/** An overloaded/venting enemy within weapon range that isn't already our target. */
	private boolean helplessEnemyNear(Sim sim, Ship3D s) {
		if (target != null && target.flux != null && target.flux.disabled()) return false;
		for (Ship3D o : sim.ships) {
			if (o.owner == s.owner || o.destroyed || o.fighter || o == target || o.flux == null || !o.flux.disabled()) continue;
			if (o.pos.distance(s.pos) < weaponRange) return true;
		}
		return false;
	}

	// ---- fighters ---------------------------------------------------------------------------

	private float launchTimer = 0.8f, breakTimer;
	private final Vec3 breakDir = new Vec3();
	private boolean returning;

	private void fighterStep(Sim sim, Ship3D s, float dt) {
		Tuning t = sim.tuning;
		c.clearAxes();
		c.autoLevel = true;
		manageFlux(sim, s, dt);
		manageSystems(sim, s);
		Ship3D carrier = s.carrier != null && !s.carrier.destroyed && sim.ships.contains(s.carrier) ? s.carrier : null;
		if (carrier == null) returning = false; // orphaned: fight on
		else if (!returning && (ordnanceSpent(s) || s.hull < s.hullMax * 0.4f)) returning = true;

		retarget -= dt;
		if (retarget <= 0f || target == null || target.destroyed || !sim.ships.contains(target)) {
			retarget = 1f;
			target = pickFighterTarget(sim, s, carrier);
		}
		Vec3 want = tA;
		float throttle = 1f;
		boolean escorting = carrier != null && (carrier.wingsRegroup || "SUPPORT".equals(s.wingRole))
				&& (target == null || target.pos.distance(carrier.pos) > carrier.radius * 3f + 700f);
		if (launchTimer > 0f) {
			launchTimer -= dt;
			s.rot.forward(want);
			want.y += 0.35f;
			want.normalize();
		} else if (returning) {
			// Home on the bay, easing off as it gets close so it can actually reach it (not circle it).
			Vec3 to = bayWorld(carrier, tB).sub(s.pos);
			float d = Math.max(1f, to.length());
			want.set(to).scale(1f / d);
			throttle = FlightModel.clamp(d / (carrier.radius * 3f + 300f), 0.3f, 1f);
			if (d < carrier.radius * 0.7f + 30f) s.landed = true;
		} else if (escorting || target == null) {
			if (carrier != null) escort(s, carrier, want);
			else s.rot.forward(want);
			throttle = carrier != null ? 0.8f : 0.4f;
		} else {
			throttle = attackRun(s, want, dt, opportunismOf(t));
		}
		avoidCollisions(sim, s, want);
		steer(s, want);
		s.throttle = FlightModel.approach(s.throttle, throttle, 2f * dt);
		FlightModel.step(s, c, t, dt);
		fireWeapons(sim, s, dt);
	}

	/** All non-reloading limited-ammo weapons empty (bombers after their run). */
	private static boolean ordnanceSpent(Ship3D s) {
		boolean any = false;
		for (WeaponMount m : s.weapons) {
			if (!m.spec.supported || m.spec.maxAmmo <= 0 || m.spec.ammoPerSecond > 0f) continue;
			any = true;
			if (m.ammo >= 1f) return false;
		}
		return any;
	}

	private static Vec3 bayWorld(Ship3D carrier, Vec3 out) {
		return carrier.rot.rotate(carrier.bayLocalFor(out), out).add(carrier.pos);
	}

	/**
	 * Targets within the wing's range of its carrier: fighters/interceptors/support prefer enemy
	 * fighters, bombers/assault prefer ships and take the carrier's own target first (yours: what
	 * you've locked or are aiming at).
	 */
	private Ship3D pickFighterTarget(Sim sim, Ship3D s, Ship3D carrier) {
		Vec3 anchor = carrier != null ? carrier.pos : s.pos;
		boolean striker = "BOMBER".equals(s.wingRole) || "ASSAULT".equals(s.wingRole);
		Ship3D ordered = carrier == null ? null
				: carrier == sim.player ? (sim.target != null ? sim.target : sim.aimTarget)
				: carrier.ai != null ? carrier.ai.target : null;
		if (ordered != null && (ordered.destroyed || ordered.owner == s.owner || ordered.invulnerable)) ordered = null;
		if (striker && ordered != null && !ordered.fighter && ordered.pos.distance(anchor) < s.wingRange) return ordered;
		Ship3D best = null;
		float bestScore = Float.MAX_VALUE;
		for (Ship3D o : sim.ships) {
			if (o.destroyed || o.owner == s.owner || o.invulnerable || o.pos.distance(anchor) > s.wingRange) continue;
			if (o.unarmedPart() && o != ordered) continue;
			float score = o.pos.distance(s.pos);
			if (o.fighter) score *= striker ? 3f : 0.4f;
			if (o == ordered) score *= 0.5f;
			if (o == target) score *= 0.8f;
			if (score < bestScore) {
				bestScore = score;
				best = o;
			}
		}
		return best;
	}

	/** Close in on the lead, fire, break away past the target, come round again. */
	private float attackRun(Ship3D s, Vec3 want, float dt, float opp) {
		if (breakTimer > 0f) {
			breakTimer -= dt;
			want.set(breakDir);
			return 1f;
		}
		Vec3 to = tB.set(target.pos).sub(s.pos);
		float dist = Math.max(1f, to.length());
		Vec3 lead = tC;
		if (!Combat.interceptCurved(s.pos, s.vel, target.pos, target.vel, target.accel, Math.max(50f, leadSpeed), lead)) lead.set(target.pos);
		want.set(lead).sub(s.pos).normalize();
		boolean striker = "BOMBER".equals(s.wingRole) || "ASSAULT".equals(s.wingRole);
		float breakDist = target.fighter ? target.radius * 2f + 60f
				: striker ? Math.max(target.radius + 150f, s.attackRunRange * 0.5f)
				: Math.max(target.radius * 1.3f + 100f, weaponRange * 0.3f);
		if (dist < breakDist) {
			// Break away to one side and a little up, opening the distance for the next pass.
			Vec3 side = s.rot.right(tD).scale(strafeSign);
			breakDir.set(side).addScaled(to, -0.6f / dist).add(new Vec3(0f, 0.35f, 0f)).normalize();
			breakTimer = 1.3f + 0.5f * (float) Math.abs(Math.sin(phase));
			strafeSign = -strafeSign;
		}
		if (opp > 0f && target.fighter && dist < weaponRange * 0.8f) {
			// Dogfight (opportunism): inside gun range, ease off toward the target's own speed so the guns
			// stay on it longer instead of flashing past (at vanilla ranges a full-speed pass is ~1.5 s of fire).
			float away = target.vel.dot(to) / dist; // how fast it runs from us
			float want2 = away + (dist - weaponRange * 0.35f) * 0.8f;
			float th = FlightModel.clamp(want2 / Math.max(1f, s.params.fwdSpeed), 0.35f, 1f);
			return 1f - (1f - th) * Math.min(1f, opp);
		}
		return 1f;
	}

	/** Drone: its orbit radius now (it launches out from the hull to its orbit). */
	private float droneR = -1f;

	/**
	 * A launcher's drone: rides its orbit round the mothership (radius beyond the hull, speed and
	 * direction from the launcher's data), launching out to it first; points away from the mothership,
	 * or at an enemy in reach if it's a point-defence drone; its guns pick their own targets.
	 */
	private void droneStep(Sim sim, Ship3D s, float dt) {
		Ship3D host = s.carrier;
		retarget -= dt;
		if (retarget <= 0f || target == null || target.destroyed || !sim.ships.contains(target)) {
			retarget = 1f;
			target = pickTarget(sim, s);
		}
		if (host == null || host.destroyed || !sim.ships.contains(host)) {
			s.pos.addScaled(s.vel, dt); // mothership gone: drift on, still shooting
		} else {
			float want = host.radius + s.droneOrbitRadius;
			if (droneR < 0f) droneR = Math.min(want, Math.max(host.radius * 0.8f, host.pos.distance(s.pos))); // out of the hull's edge
			droneR = FlightModel.approach(droneR, want, 300f * sim.tuning.speedMult * dt); // vanilla's launch speed
			s.droneOrbitAngle += s.droneOrbitDir * s.droneOrbitSpeed * sim.tuning.speedMult / Math.max(1f, droneR) * dt;
			Vec3 f = host.rot.forward(tA), r = host.rot.right(tB);
			float ca = (float) Math.cos(s.droneOrbitAngle), sa = (float) Math.sin(s.droneOrbitAngle);
			Vec3 off = tC.set(f).scale(ca * droneR).addScaled(r, sa * droneR);
			Vec3 next = tD.set(host.pos).add(off);
			s.vel.set(next).sub(s.pos).scale(1f / dt);
			s.pos.set(next);
		}
		Vec3 look = null;
		if (s.droneFaceEnemy && target != null && target.pos.distance(s.pos) < Math.max(600f, weaponRange * 1.2f)) look = tE.set(target.pos).sub(s.pos);
		else if (host != null && !host.destroyed) look = tE.set(s.pos).sub(host.pos);
		if (look != null && look.lengthSq() > 1e-4f) s.rot.setLookRotation(look.normalize(), new Vec3(0f, 1f, 0f));
		manageFlux(sim, s, dt);
		fireWeapons(sim, s, dt);
	}

	/** Circle the carrier a little outside its hull. */
	private void escort(Ship3D s, Ship3D carrier, Vec3 want) {
		float r = carrier.radius * 2.5f + 150f;
		Vec3 rel = tB.set(s.pos).sub(carrier.pos);
		float d = Math.max(1f, rel.length());
		Vec3 out = tC.set(rel).scale(1f / d);
		Vec3 tangent = tD.set(0f, strafeSign, 0f).cross(out);
		if (tangent.lengthSq() < 1e-4f) tangent.set(1f, 0f, 0f);
		tangent.normalize();
		want.set(tangent).addScaled(out, FlightModel.clamp((r - d) / r * 2f, -1.5f, 1.5f)).normalize();
	}

	/** Bends the desired heading away from any ship we're about to fly into. */
	private void avoidCollisions(Sim sim, Ship3D s, Vec3 want) {
		Vec3 push = tB.set(0f, 0f, 0f);
		for (Ship3D o : sim.ships) {
			if (o == s || o.destroyed) continue;
			if (o.fighter && !s.fighter) continue; // ships don't swerve for fighters
			Vec3 away = tC.set(s.pos).sub(o.pos);
			float dNow = away.length(), d = dNow;
			if (o.fixedPart() && dNow - o.extent() > s.radius * 1.6f + 80f + s.vel.length()) continue; // well clear of all of it
			// A station section or core doesn't move: keep clear of its real shape plus our own size
			// (its collision circle can be far bigger than the hull, and at station scale a margin
			// proportional to it would keep us out of gun range).
			float clear = !o.fixedPart() ? (s.radius + o.radius) * 1.6f
					: (d > 1e-3f ? structureReach(o, tE.set(away).scale(1f / d), s.radius / d) : o.radius) + s.radius * 1.6f + 80f;
			if (o.fixedPart()) {
				// It won't get out of the way: keep clear of where we'll be in a second, too (at speed a ship
				// carries well past the point where it starts to turn away).
				float ax = away.x + s.vel.x, ay = away.y + s.vel.y, az = away.z + s.vel.z;
				d = Math.min(d, (float) Math.sqrt(ax * ax + ay * ay + az * az));
			}
			if (d >= clear || dNow < 1e-3f) continue;
			// Near a structure, how far into the margin round its hull we are (not into its whole reach, which
			// at station scale made the push too weak to stop ships scraping a section next to their target).
			float k = !o.fixedPart() ? (clear - d) / clear : Math.min(2f, (clear - d) / (s.radius * 1.6f + 80f));
			push.addScaled(away.scale(1f / dNow), k * 2.5f);
			// Strafe out of the way too, which works even while the nose is busy.
			Vec3 local = s.rot.inverseRotate(away, tD);
			c.strafeX = FlightModel.clamp(c.strafeX + Math.signum(local.x) * k * 2f, -1f, 1f);
			c.strafeY = FlightModel.clamp(c.strafeY + Math.signum(local.y) * k * 2f, -1f, 1f);
		}
		if (push.lengthSq() > 0f) want.add(push).normalize();
	}

	/**
	 * How far a structure's hull reaches toward us: the most of its reach toward dir (unit, from it to us)
	 * and toward the edges of our own outline as seen from it (spread: our radius over the distance). A
	 * station section is irregular; along the line to our centre it can reach far less than beside it.
	 */
	private float structureReach(Ship3D o, Vec3 dir, float spread) {
		float r = o.reachToward(dir);
		float k = Math.min(1f, spread * 1.2f);
		Vec3 u = tJ.set(dir).cross(Math.abs(dir.y) < 0.9f ? tK.set(0f, 1f, 0f) : tK.set(1f, 0f, 0f)).normalize();
		Vec3 v = tK.set(dir).cross(u).normalize();
		for (int i = 0; i < 4; i++) {
			Vec3 side = i < 2 ? u : v;
			float sign = (i & 1) == 0 ? k : -k;
			r = Math.max(r, o.reachToward(tF.set(dir).addScaled(side, sign).normalize()));
		}
		return r;
	}

	/** Virtual stick toward a world direction: rate commands that brake before overshooting. */
	private void steer(Ship3D s, Vec3 dirWorld) {
		Vec3 d = s.rot.inverseRotate(dirWorld, tB);
		float yawErr = (float) Math.atan2(d.x, -d.z); // >0: target to starboard
		float pitchErr = (float) Math.atan2(d.y, Math.hypot(d.x, d.z)); // >0: target above
		FlightParams p = s.params;
		c.stickX = rateCommand(yawErr, p.yawRate, p.yawAccel) / Math.max(1e-3f, p.yawRate);
		c.stickY = rateCommand(pitchErr, p.pitchRate, p.pitchAccel) / Math.max(1e-3f, p.pitchRate);
		c.stickX = FlightModel.clamp(c.stickX, -1f, 1f);
		c.stickY = FlightModel.clamp(c.stickY, -1f, 1f);
	}

	private static float rateCommand(float err, float maxRate, float accel) {
		float mag = Math.abs(err);
		float rate = Math.min(maxRate, Math.min(3f * mag * maxRate, (float) Math.sqrt(2f * accel * mag) * 0.85f));
		return Math.signum(err) * rate;
	}

	/** Why a mount holds its fire (0 = it fires); the numbers index fireStats. */
	private static final int FIRE = 0, HOLD_CONE = 2, HOLD_FLUX = 3, HOLD_FRIENDLY = 4, HOLD_MISSILE = 5, SWITCHED = 6;
	private final Vec3 fAim = new Vec3(), fAimFirst = new Vec3(), fBarrel = new Vec3();
	private final Ship3D[] fTgt = new Ship3D[1];
	private float fireCos, fireSin;

	/**
	 * Every mount engages on its own: PD takes missiles first, everything else the AI's target if it
	 * can reach the lead, else the best other enemy it can reach. Ship-killing shots are held while the
	 * barrel is still swinging onto the lead or a friendly is in the way; with opportunism a mount whose
	 * shot is blocked (friendly in the way, missile not worth it) turns to another enemy it can reach.
	 */
	private void fireWeapons(Sim sim, Ship3D s, float dt) {
		float opp = opportunismOf(sim.tuning);
		fireCos = (float) Math.cos(Math.toRadians(sim.tuning.aiFireConeDeg));
		fireSin = (float) Math.sin(Math.toRadians(sim.tuning.aiFireConeDeg));
		Vec3 aim = fAim;
		Ship3D[] tgt = fTgt;
		boolean shotAtShip = false;
		for (WeaponMount m : s.weapons) {
			boolean engage = sim.autofireAim(s, m, target, null, opp, aim, tgt);
			int hold = FIRE;
			if (engage && tgt[0] != null) {
				fireStats[0]++;
				hold = holdReason(sim, s, m, aim, tgt[0], opp);
				if (opp > 0f && (hold == HOLD_FRIENDLY || hold == HOLD_MISSILE)) {
					Ship3D first = tgt[0];
					fAimFirst.set(aim);
					if (sim.autofireAim(s, m, target, first, opp, aim, tgt) && tgt[0] != null) {
						fireStats[SWITCHED]++;
						hold = holdReason(sim, s, m, aim, tgt[0], opp);
					} else { // nobody else in reach: keep tracking the first
						aim.set(fAimFirst);
						tgt[0] = first;
					}
				}
				fireStats[hold == FIRE ? 1 : hold]++;
			}
			sim.combat.stepMount(s, m, engage ? aim : null, engage && hold == FIRE, tgt[0], dt, sim.tuning);
			if (!m.spec.pd && tgt[0] != null && !tgt[0].fighter && (m.firedThisFrame || m.beamFiring)) shotAtShip = true;
		}
		sinceFired = shotAtShip ? 0f : sinceFired + dt;
	}

	/** FIRE, or why this mount holds its shot at tg (aim = the lead point). */
	private int holdReason(Sim sim, Ship3D s, WeaponMount m, Vec3 aim, Ship3D tg, float opp) {
		Vec3 mw = m.worldPos(s, tC);
		Vec3 dir = tD.set(aim).sub(mw);
		float len = dir.length();
		if (len <= 1e-3f) return FIRE;
		dir.scale(1f / len);
		boolean guided = m.spec.kind == WeaponSpec3D.Kind.MISSILE && m.spec.guided;
		Vec3 barrel = s.rot.rotate(m.aimDir, fBarrel);
		// On target: the barrel within the cone of the lead, widened by the hull round it (a hardpoint
		// held at the edge of its arc can't swing any closer; see Sim.arcPad).
		float sp = Math.min(1f, Sim.arcPad(tg) / len); // sin of the pad's angle
		float onTarget = fireCos * (float) Math.sqrt(1f - sp * sp) - fireSin * sp; // cos(cone + pad); the cone is small, so never past 180
		if (!guided && barrel.dot(dir) < onTarget) {
			// Still swinging on. With opportunism, a shot that will be blocked once it's on counts as blocked
			// now, so the mount looks elsewhere instead of swinging back and forth between the two.
			if (opp > 0f && !Sim.missileWorthFiring(s, m, tg)) return HOLD_MISSILE;
			if (opp > 0f && friendlyInLine(sim, s, mw, aim)) return HOLD_FRIENDLY;
			return HOLD_CONE;
		}
		if (!Sim.missileWorthFiring(s, m, tg)) return HOLD_MISSILE;
		if (s.flux != null && !m.spec.pd && s.flux.level() > fluxLimit(sim, m, tg, mw, opp)) return HOLD_FLUX;
		if (friendlyInLine(sim, s, mw, aim)) return HOLD_FRIENDLY;
		return FIRE;
	}

	/**
	 * Flux level above which a ship-killing shot is held, keeping room for the shield. Anything goes into
	 * a helpless target; with opportunism, weapons that cost no flux never hold, and a shot that will
	 * land on armor (no shield in the way from here) is worth more of our flux.
	 */
	private float fluxLimit(Sim sim, WeaponMount m, Ship3D tg, Vec3 from, float opp) {
		if (tg.flux != null && tg.flux.disabled()) return 0.98f;
		float limit = sim.tuning.aiFluxFireLimit;
		// Aggression fires on to 93% flux (AI Tweaks' hold-fire level); finishing a target, to 95%.
		limit += Math.max(0f, 0.93f - limit) * Math.min(1f, aggressionOf(sim.tuning));
		if (finishing) limit = Math.max(limit, 0.95f);
		if (opp <= 0f) return limit;
		if (m.spec.fluxPerShot <= 0f && m.spec.fluxPerSecond <= 0f) return 2f;
		if (!Sim.shieldCovers(tg, from, tE)) limit = Math.max(limit, limit + (0.95f - limit) * Math.min(1f, opp));
		if (cornered) limit = Math.max(limit, 0.95f); // can't get away to vent: go down fighting
		return limit;
	}

	private static boolean friendlyInLine(Sim sim, Ship3D s, Vec3 from, Vec3 to) {
		for (Ship3D o : sim.ships) {
			if (Combat.sameStructure(o, s) || o.destroyed || o.owner != s.owner) continue; // own structure: shots pass through
			if (Combat.segmentSphere(from, to, o.pos, o.radius * 0.8f) >= 0f) return true;
		}
		return false;
	}
}
