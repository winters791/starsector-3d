package s3d.core;

import s3d.math.Vec3;

/**
 * A simple 3D combat pilot for AI ships (enemies and the player's allies). It flies with the same
 * FlightModel and controls as the player, so it obeys the hull's real speed and turn limits.
 *
 * Each ship works out once which way its guns point (nose, port or starboard; whichever side
 * carries the most firepower) and fights by keeping that side on its target at about
 * aiRangeFrac of its main weapons' range: nose ships point at the lead, broadside ships orbit with
 * the gun side facing in. Every mount fires on its own like a vanilla autofire group, holding fire
 * when a friendly is in the line of fire. Ordered to retreat, it flies straight for its own map edge.
 *
 * Manoeuvring: every few seconds (frigates often, capitals rarely) a ship picks a plan. HOLD keeps
 * the range; FLANK slides round the target to a new angle, preferring its sides, rear, top and
 * belly over its guns; PASS (agile ships faster than their target) makes gun runs, flying past the
 * target, out, and swinging round for another. On top of that ships weave (a sideways/vertical
 * strafe that changes direction now and then, and jinks away from heavy incoming fire), their
 * fighting range breathes in and out, and they close in on a target that's overloaded, venting or
 * flux-locked.
 *
 * Flux: the shield goes up when fire is about to land and comes down when it's quiet (so hard flux
 * can drain); high flux means holding ship-killing fire and standing further off; with no threat
 * near it vents. Overloaded or venting, it faces the fight and backs off slowly. Enemies that are
 * overloaded or venting are the preferred targets, and get fired on even at high flux.
 *
 * Caution (Tuning.aiCaution): ships keep out of enemy guns' reach where they can. Against a target
 * they outrange they fight from just outside its guns; enemies other than the target that have them
 * under their guns push them out sideways (and further back when they're round the target); at high
 * flux they back right out of the target's reach to cool off, and vent once no guns reach them.
 * Flanking spots, gun runs and targets inside the enemy's crowd count against.
 *
 * Opportunism (Tuning.aiOpportunism): a mount whose shot is blocked (a friendly in the way, a missile
 * not worth it) turns to another enemy it can reach; among enemies in reach it takes the better shot
 * (helpless, unshielded from where it is, nearly dead); weapons that cost no flux never hold for flux,
 * and shots that will land on armor are fired at higher flux. Ships pick targets that are hurt,
 * flux-locked, already in front of their guns or outranged; fighters dogfight enemy fighters at about
 * their speed instead of flashing past them.
 *
 * Orders (vanilla's command map, via {@link Order3D}): escorts stay with their ship and fight only
 * what comes near it; HOLD and CAPTURE go to their point and fight only round it (capturers sit
 * inside the capture ring); ATTACK goes after its ship; AVOID keeps out of its area and doesn't
 * chase into it. A fight that draws a ship too far from its order's ground is dropped.
 *
 * Station sections and ship modules don't fly (see turretStep): they pick a target, manage flux and
 * shields, and fire. A station's invulnerable core is never picked as a target.
 *
 * Fighters fly differently (see fighterStep): out of the bay, then attack runs (close in, fire,
 * break away, come round again) on targets within their wing's range of the carrier; fighters and
 * interceptors hunt enemy fighters first, bombers and assault wings go for ships (the carrier's
 * target first); support wings, and every wing while its carrier has them regrouped, escort the
 * carrier. Out of ordnance or badly hurt, they fly back to their bay and land to rearm.
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
	/** This pilot's caution and opportunism (see Tuning.aiCaution / aiOpportunism); NaN = the tuning's. */
	public float caution = Float.NaN, opportunism = Float.NaN;
	/**
	 * Debug (bench): mount-frames with an enemy in reach; of those, shots allowed, and holds because the
	 * barrel wasn't on yet, for flux, for a friendly in the way, by missile discipline; then blocked shots
	 * that found another enemy instead.
	 */
	public final int[] fireStats = new int[7];
	/** Backing out of the target's reach to let flux drain (from 75% flux until it's under 40%). */
	private boolean coolingOff;
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

	public ShipAI3D(int seed) {
		strafeSign = (seed & 1) == 0 ? 1f : -1f;
		phase = (seed * 0.618f) % 1f * 6.283f;
		retarget = (seed % 7) * 0.1f; // stagger re-targeting across ships
		rng = new java.util.Random(seed * 7919L + 17L);
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
	private void scanGuns(Sim sim, Ship3D s) {
		awayDir.set(0f, 0f, 0f);
		float margin = 60f + s.radius * 0.3f;
		gunsOnUsOthers = exposure(sim, s, s.pos, target, margin, awayDir);
		gunsOnUs = gunsOnUsOthers + (target != null ? gunsOn(sim, s, target, s.pos, margin, null) : 0f);
		float level = s.flux != null ? s.flux.level() : 0f;
		if (level > 0.75f) coolingOff = true;
		else if (level < 0.4f) coolingOff = false;
	}

	/**
	 * Caution: how hard to get out of the reach of enemies other than the target (0 = none of them has us
	 * under its guns); the way out is awayDir.
	 */
	private float cautionUrge(Tuning t) {
		float cau = cautionOf(t);
		if (cau <= 0f || gunsOnUsOthers <= 0f || awayDir.lengthSq() < 1e-6f) return 0f;
		return cau * Math.min(2f, outgunned(gunsOnUsOthers));
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
			target = pickTarget(sim, s);
		}
		scanGuns(sim, s);
		c.clearAxes();
		c.autoLevel = true;
		manageFlux(sim, s, dt);
		manageSystems(sim, s);

		Vec3 want = tA; // desired nose direction (world)
		float throttle;
		boolean helpless = s.flux != null && s.flux.disabled();
		if (s.retreatOrdered) {
			want.set(homeDir);
			throttle = 1f;
		} else if (s.sysBackOff && target != null) {
			// Extraction Protocol: pull straight back from the target, guns still going.
			want.set(s.pos).sub(target.pos).normalize();
			throttle = 1f;
		} else if (target == null && order != null) {
			throttle = followOrder(sim, s, order, want);
		} else if (target == null) {
			want.set(s.rot.forward(tB));
			throttle = 0.2f;
		} else if (helpless) {
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
			default:
				return o;
		}
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
				return e.pos.distance(o.anchor()) - Sim.nearSide(e, o.anchor()) <= leash(o);
			case AVOID:
				return e.pos.distance(o.anchor()) > o.radius;
			default:
				return true;
		}
	}

	/** Chasing has taken us well beyond the order's ground. */
	private boolean strayed(Ship3D s, Order3D o) {
		if (o.kind != Order3D.Kind.ESCORT && o.kind != Order3D.Kind.HOLD && o.kind != Order3D.Kind.CAPTURE) return false;
		return s.pos.distance(o.anchor()) > leash(o) * 1.4f;
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
		scanGuns(sim, s);
		manageFlux(sim, s, dt);
		fireWeapons(sim, s, dt);
	}

	/** Sets the nose direction for the fight; returns the throttle. */
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
		float engageRange = fightRange(sim, s, fluxLevel);
		float wobble = (float) Math.sin(sim.time * 0.35f + phase);
		if (axis == Axis.NOSE) {
			if (move == Move.PASS) {
				float th = passCourse(sim, s, want, toN, dist, engageRange, fluxLevel, dt);
				if (move == Move.PASS) return th;
			}
			if (openRange(s, toN, dist, engageRange, dt)) {
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
			if (move == Move.FLANK) flank(s, toN);
			float urge = cautionUrge(t);
			if (urge > 0f) {
				strafeAway(s, urge);
				// The guns on us are round or past the target: stand further back, still within our own reach.
				float back = -tH.set(awayDir).normalize().dot(toN);
				if (back > 0f) engageRange = Math.min(Math.max(engageRange, weaponRange * 0.95f), engageRange * (1f + 0.4f * Math.min(1f, urge) * back));
			}
			return rangeThrottle(s, toN, dist, engageRange, standingOff ? -1f : -0.5f);
		}
		// Broadside: fly across the line to the target with the gun side facing it, leaning in or
		// out to hold range. The orbit's plane is tilted per plan and bobs, so it isn't flat.
		Vec3 up = Math.abs(toN.y) > 0.9f ? s.rot.up(tD) : tD.set(0f, 1f, 0f);
		up.addScaled(tE.set(toN).scale(-toN.dot(up)), 1f).normalize();
		Vec3 side = tE.set(toN).cross(up);
		up.scale((float) Math.cos(orbitTilt)).addScaled(side, (float) Math.sin(orbitTilt)).normalize();
		Vec3 across = new Vec3(up).cross(toN).normalize(); // target on the starboard side when flying this way
		if (axis == Axis.PORT) across.scale(-1f);
		float radial = FlightModel.clamp((dist - engageRange) / Math.max(1f, engageRange) * 1.6f, -0.7f, 0.9f);
		want.set(across).addScaled(toN, radial).addScaled(up, wobble * 0.35f).normalize();
		float urge = cautionUrge(t);
		if (urge > 0f) want.addScaled(tH.set(awayDir).normalize(), Math.min(1f, urge) * 0.8f).normalize();
		c.strafeY = jinkY * 0.7f;
		if (dist > weaponRange * 1.6f) return 1f;
		return FlightModel.clamp(0.65f + throttleBias + 0.15f * (float) Math.sin(sim.time * 0.3f + phase * 2f), 0.3f, 1f);
	}

	/**
	 * The range to fight at right now: the profiled range, breathing in and out over time and per
	 * plan, further off as our flux builds, closer in on a target that can't fight back.
	 */
	private float fightRange(Sim sim, Ship3D s, float fluxLevel) {
		float r = engageRange * (1f + rangeBias + 0.1f * (float) Math.sin(sim.time * 0.23f + phase));
		r *= 1f + 0.6f * Math.max(0f, fluxLevel - 0.5f) / 0.5f; // stand off so the shield and dissipation catch up
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
		if (cau > 0f && !targetHelpless) {
			// Out of its guns' reach where we can: fight a target we outrange from just outside its guns,
			// and back right out of them to cool off at high flux.
			if ((coolingOff || outranged) && edge > r * 0.95f) {
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
		boolean crowded = cau > 0f && cau * outgunned(exposure(sim, s, target.pos, target, 0f, null)) > 0.5f;
		float theirs = gunReach(target, t); // an unarmed target's "reach" is harmless: runs are fine
		boolean outranged = cau > 0f && theirs > 0f && theirs + 40f + s.radius * 0.3f <= reachOut() * 0.92f; // even helpless: it may be back before the run's done
		if (ag >= 0.75f && faster && fit && near && !station && !crowded && !outranged && (!covered || helpless || bigger && cau < 0.5f)
				&& rng.nextFloat() < t.aiPassChance * ag) {
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
		// Vent when flux-locked and not being shot at: right away if no enemy is close, after a short
		// lull if one is (vanilla AI vents mid-fight too; it backs off while venting).
		float nearest = Float.MAX_VALUE;
		for (Ship3D o : sim.ships) {
			if (o.owner != s.owner && !o.destroyed) nearest = Math.min(nearest, o.pos.distance(s.pos));
		}
		// Caution: safe only once no enemy guns reach us (fighters and caution 0: no enemy close).
		boolean safe = s.fighter || cautionOf(sim.tuning) <= 0f ? nearest > weaponRange * 0.9f : gunsOnUs <= 0f;
		if (f.level() > 0.7f && (safe && quiet > 0.5f || quiet > 1.5f && f.level() > 0.8f)) f.vent();
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
	 * Keeps the fight together: a ship far from its own fleet with nothing in range drifts back
	 * toward it (unless it's been ordered somewhere); nobody leaves the battle area or the altitude
	 * band around the fight.
	 */
	private void stayWithFight(Sim sim, Ship3D s, Vec3 want, Order3D order) {
		Tuning t = sim.tuning;
		Vec3 pull = tB.set(0f, 0f, 0f);
		Vec3 home = sim.mobileCentre(s.owner);
		boolean sent = order != null && order.kind != Order3D.Kind.AVOID; // ordered somewhere: that's where it belongs
		if (home != null && !sent) {
			float d = home.distance(s.pos);
			// Busy: something in range, or working on a station (its attackers spread round it, so
			// their centre can be inside it).
			boolean busy = target != null && (gap(s) < weaponRange || target.fixedPart() && gap(s) < weaponRange * 3f);
			if (!busy && d > t.aiCohesion) pull.add(tC.set(home).sub(s.pos).normalize().scale(Math.min(1.5f, (d - t.aiCohesion) / t.aiCohesion + 0.5f)));
		}
		if (sim.arenaHalfX > 0f) {
			float ox = Math.abs(s.pos.x) - sim.arenaHalfX * 0.85f, oz = Math.abs(s.pos.z) - sim.arenaHalfZ * 0.85f;
			if (ox > 0f) pull.x -= Math.signum(s.pos.x) * Math.min(2f, ox / 500f);
			if (oz > 0f) pull.z -= Math.signum(s.pos.z) * Math.min(2f, oz / 500f);
		}
		float centreY = sim.fightAltitude();
		float oy = Math.abs(s.pos.y - centreY) - t.aiAltitudeBand;
		if (oy > 0f) pull.y -= Math.signum(s.pos.y - centreY) * Math.min(2f, oy / 400f);
		if (pull.lengthSq() > 0f) want.add(pull).normalize();
	}

	/**
	 * When to use the ship system and phase cloak, per kind. Toggles are switched off again when
	 * their reason goes away.
	 */
	private void manageSystems(Sim sim, Ship3D s) {
		ShipSystem3D sys = s.system;
		if (sys != null && sys.supported()) {
			boolean want = wantSystem(sim, s, sys);
			if (sys.toggle) {
				if (want != sys.on() && (want ? sys.ready(s) : true)) sys.activate(s);
			} else if (want && sys.ready(s)) {
				sys.activate(s);
			}
		}
		ShipSystem3D cloak = s.cloak;
		if (cloak != null && cloak.supported()) {
			float threat = threatNow;
			float level = s.flux != null ? s.flux.level() : 0f;
			// Phase out of heavy incoming fire; come back to shoot when it's quiet, or before flux runs out.
			boolean want = cloak.on() ? (threat > 0f || quiet < 0.5f) && level < 0.85f : threat > s.hullMax * 0.02f && level < 0.6f;
			if (want != cloak.on() && (want ? cloak.ready(s) : true)) cloak.activate(s);
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

	/**
	 * Nearest live enemy, slightly preferring whoever is shooting at us and the current target (no
	 * flip-flopping); opportunism prefers openings (see opening), caution avoids targets we'd have to
	 * fight inside their friends' reach.
	 */
	private Ship3D pickTarget(Sim sim, Ship3D s) {
		Order3D order = currentOrder(sim, s);
		if (order != null && order.kind == Order3D.Kind.ATTACK) return order.ship;
		Ship3D best = null;
		float bestScore = Float.MAX_VALUE;
		float cau = cautionOf(sim.tuning), opp = Math.min(1f, opportunismOf(sim.tuning));
		Vec3 axisNow = gunAxisWorld(s, tH);
		for (Ship3D o : sim.ships) {
			if (o.destroyed || o.owner == s.owner || o.fighter || o.invulnerable) continue; // ships fight ships; PD handles fighters
			if (o.unarmedPart()) continue; // spurs and armour: only a station's guns and bays are worth shooting
			if (order != null && !allowedTarget(order, o)) continue;
			// Distance from us, plus half the distance from our fleet, so a fleet tends to engage together.
			Vec3 home = sim.mobileCentre(s.owner);
			float d = o.pos.distance(s.pos) + (home != null ? 0.5f * o.pos.distance(home) : 0f);
			if (o == target) d *= 0.75f;
			if (o == s.lastHitBy) d *= 0.8f;
			if (o.flux != null && o.flux.disabled()) d *= 0.4f; // overloaded or venting: pounce
			if (opp > 0f) d *= 1f - (1f - opening(sim, s, o, axisNow)) * opp;
			if (cau > 0f && !o.fixedPart()) {
				// Where we'd fight it from: inside its friends' reach makes it a worse pick.
				float od = Math.max(1f, o.pos.distance(s.pos));
				Vec3 spot = tJ.set(s.pos).sub(o.pos).scale(Math.min(od, engageRange) / od).add(o.pos);
				d *= 1f + cau * 0.5f * Math.min(2f, outgunned(exposure(sim, s, spot, o, 0f, null)));
			}
			if (d < bestScore) {
				bestScore = d;
				best = o;
			}
		}
		return best;
	}

	/** 1 for an ordinary target, lower for an opening: nearly dead, flux-locked, already in front of our guns, or outranged by us. */
	private float opening(Sim sim, Ship3D s, Ship3D o, Vec3 axisNow) {
		float k = 1f;
		if (o.hull < o.hullMax * 0.4f) k *= 0.8f;
		if (o.flux != null && !o.flux.disabled() && o.flux.level() > 0.75f) k *= 0.85f;
		float dist = o.pos.distance(s.pos);
		if (dist > 1e-3f && tK.set(o.pos).sub(s.pos).scale(1f / dist).dot(axisNow) > 0.9f) k *= 0.85f;
		float theirs = o.fixedPart() ? 0f : gunReach(o, sim.tuning);
		if (theirs > 0f && theirs + 40f + s.radius * 0.3f <= reachOut() * 0.92f) k *= 0.85f;
		return k;
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
		}
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
		if (opp <= 0f) return limit;
		if (m.spec.fluxPerShot <= 0f && m.spec.fluxPerSecond <= 0f) return 2f;
		if (!Sim.shieldCovers(tg, from, tE)) limit = Math.max(limit, limit + (0.95f - limit) * Math.min(1f, opp));
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
