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
	/** Projectile speed of the main weapons, for leading. */
	public float leadSpeed = 800f;

	private final ShipControls c = new ShipControls();
	private boolean profiled;
	private float retarget, strafeSign = 1f, phase, quiet;
	private final Vec3 tA = new Vec3(), tB = new Vec3(), tC = new Vec3(), tD = new Vec3(), tE = new Vec3(), tF = new Vec3();
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
		float[] rangeSum = new float[3], speedSum = new float[3];
		Vec3[] axes = {new Vec3(0, 0, -1), new Vec3(-1, 0, 0), new Vec3(1, 0, 0)};
		float missileRange = 0f;
		for (WeaponMount m : s.weapons) {
			WeaponSpec3D w = m.spec;
			if (!w.supported || w.pdOnly) continue;
			if (w.kind == WeaponSpec3D.Kind.MISSILE) {
				missileRange = Math.max(missileRange, w.range);
				continue;
			}
			float dps = dps(w) * (m.hardpoint ? 1.2f : 1f);
			if (w.pd) dps *= 0.5f; // PD guns help, but don't decide how the ship fights
			for (int i = 0; i < 3; i++) {
				float ang = (float) Math.acos(Math.max(-1f, Math.min(1f, m.restDir.dot(axes[i]))));
				if (ang > m.halfArcRad + 0.09f) continue;
				weight[i] += dps;
				rangeSum[i] += dps * w.range;
				speedSum[i] += dps * Sim.shotSpeed(w);
			}
		}
		weight[0] *= 1.15f; // ties go to the nose: easier to fly, and how most hulls are laid out
		int best = 0;
		for (int i = 1; i < 3; i++) if (weight[i] > weight[best]) best = i;
		axis = Axis.values()[best];
		float raw = weight[best];
		if (best == 0) raw /= 1.15f;
		if (raw > 0f) {
			weaponRange = rangeSum[best] / raw * t.weaponRangeMult;
			leadSpeed = speedSum[best] / raw;
		} else if (missileRange > 0f) {
			axis = Axis.NOSE;
			weaponRange = missileRange * t.weaponRangeMult;
			leadSpeed = 600f;
		} else {
			weaponRange = 600f * t.weaponRangeMult;
		}
		engageRange = Math.max(s.radius * 2.5f, weaponRange * t.aiRangeFrac);
	}

	static float dps(WeaponSpec3D w) {
		if (w.kind == WeaponSpec3D.Kind.BEAM) return w.dps;
		float cycle = Math.max(0.05f, w.cooldown + w.chargeTime + Math.max(0, w.burstSize - 1) * w.burstDelay);
		return w.damage * Math.max(1, w.burstSize) / cycle;
	}

	/** One frame: pick a target, steer, move and fight. */
	public void step(Sim sim, Ship3D s, float dt) {
		Tuning t = sim.tuning;
		if (!profiled) profile(s, t);
		if (dt <= 0f) return;
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
		} else if (target == null && order != null) {
			throttle = followOrder(sim, s, order, want);
		} else if (target == null) {
			want.set(s.rot.forward(tB));
			throttle = 0.2f;
		} else if (helpless) {
			// Overloaded or venting: keep facing the fight and back off slowly while an enemy is close,
			// rather than running for the map edge.
			want.set(target.pos).sub(s.pos).normalize();
			throttle = target.pos.distance(s.pos) < engageRange * 1.2f ? -0.5f : 0f;
			weave(sim, s, dt);
			c.strafeX = jinkX;
			c.strafeY = jinkY;
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
			Vec3 lead = tD;
			if (!Combat.interceptCurved(s.pos, s.vel, target.pos, target.vel, target.accel, Math.max(50f, leadSpeed), lead)) lead.set(target.pos);
			want.set(lead).sub(s.pos).normalize();
			// Weave all the time (less on the way in); FLANK adds a slide round the target on top.
			float k = dist < weaponRange * 1.2f ? 1f : 0.4f;
			c.strafeX = jinkX * k;
			c.strafeY = jinkY * k;
			if (move == Move.FLANK) flank(s, toN);
			return rangeThrottle(s, toN, dist, engageRange);
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
		if (tf != null && tf.disabled()) r *= 0.6f; // overloaded or venting: move in for the kill
		else if (tf != null && tf.level() > 0.8f && fluxLevel < 0.4f) r *= 0.8f; // flux-locked: press
		if (target.fixedPart()) {
			// A station section or module: the range counts from its near side (where the shots land),
			// clear of its real shape (its collision circle can be far bigger than the hull).
			Vec3 out = tF.set(s.pos).sub(target.pos);
			float reach = out.lengthSq() > 1f ? target.reachToward(out.normalize()) : target.radius;
			return Math.max(reach + s.radius * 1.6f + 100f, Math.max(s.radius * 2.5f, r) + reach * 0.85f);
		}
		return Math.max(s.radius + target.radius + 100f, Math.max(s.radius * 2.5f, r));
	}

	/** Distance to the target, counted to its near side for a station section or module. */
	private float gap(Ship3D s) {
		return target.pos.distance(s.pos) - Sim.nearSide(target, s.pos);
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
		if (ag >= 0.75f && faster && fit && near && !station && (!covered || helpless || bigger) && rng.nextFloat() < t.aiPassChance * ag) {
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
		if (rng.nextFloat() < t.aiFlankChance && pickStation(s)) move = Move.FLANK;
		plans[move.ordinal()]++;
	}

	/**
	 * A new angle to fight from: the current bearing swung 50-110 deg round the target, the best of
	 * a few tries, preferring the side where its armor is already stripped, then away from its main
	 * guns, and not straight over or under it. Staying put is one of the options (it wins when the
	 * armor facing us is already broken), in which case the plan becomes HOLD.
	 */
	private boolean pickStation(Ship3D s) {
		Vec3 bearing = tB.set(s.pos).sub(target.pos).normalize();
		Vec3 guns = target.ai != null ? target.ai.gunAxisWorld(target, tC) : target.rot.forward(tC);
		float best = stationScore(bearing, guns) + 0.15f; // a little inertia: moving costs firing time
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
			float score = stationScore(cand, guns) + rng.nextFloat() * 0.4f;
			if (score > best) {
				best = score;
				bestDir.set(cand);
				moved = true;
			}
		}
		target.rot.inverseRotate(bestDir, stationLocal);
		return moved;
	}

	/** How good it is to fight from world direction dir (from the target): broken armor, away from its guns, fairly level. */
	private float stationScore(Vec3 dir, Vec3 guns) {
		return (1f - armorFacing(target, dir)) * 2f - dir.dot(guns) * 0.6f - Math.abs(dir.y) * 0.4f;
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
			jinkGoalY = py / len * sign * max * 0.7f;
			jinkLeft = 1f + 0.8f * rng.nextFloat();
		} else if (jinkLeft <= 0f) {
			float a = rng.nextFloat() * 6.283f;
			float mag = Math.min(max, t.aiStrafe * 0.5f) + (max - Math.min(max, t.aiStrafe * 0.5f)) * rng.nextFloat();
			jinkGoalX = (float) Math.cos(a) * mag;
			jinkGoalY = (float) Math.sin(a) * mag * 0.6f;
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
	 * Shield up while fire is inbound (and for a moment after); down when quiet so hard flux drains.
	 * Vent when flux is high and nothing is inbound, or when it's nearly full and the shield is
	 * about to give anyway.
	 */
	private void manageFlux(Sim sim, Ship3D s, float dt) {
		ShieldFlux f = s.flux;
		float threat = sim.threat(s, threatDir);
		threatNow = threat;
		if (f == null || f.disabled()) return;
		quiet = threat > 0f ? 0f : quiet + dt;
		if (f.hasShield()) {
			boolean want = threat > 0f || quiet < 0.6f && f.shieldOn;
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
		boolean safe = nearest > weaponRange * 0.9f;
		if (f.level() > 0.7f && (safe && quiet > 0.5f || quiet > 1.5f && f.level() > 0.8f)) f.vent();
	}

	/**
	 * Throttle that holds the fighting range by matching the target's speed along the line between
	 * the ships, closing or opening at a rate that can still brake in time (no overshoot, no dash in
	 * and out).
	 */
	private float rangeThrottle(Ship3D s, Vec3 toN, float dist, float range) {
		FlightParams p = s.params;
		float e = dist - range;
		float rate = Math.min(p.fwdSpeed, Math.min((float) Math.sqrt(2f * Math.max(1f, p.fwdDecel) * Math.abs(e)) * 0.8f, Math.abs(e) * 0.6f));
		float targetAlong = target.vel.dot(toN); // how fast the target moves away from us
		float wantAlong = targetAlong + Math.signum(e) * rate;
		return FlightModel.clamp(wantAlong / Math.max(1f, p.fwdSpeed), -0.5f, 1f);
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
			default:
				return false; // RECALL and anything else: the player's call
		}
	}

	/** Nearest live enemy, slightly preferring whoever is shooting at us and the current target (no flip-flopping). */
	private Ship3D pickTarget(Sim sim, Ship3D s) {
		Order3D order = currentOrder(sim, s);
		if (order != null && order.kind == Order3D.Kind.ATTACK) return order.ship;
		Ship3D best = null;
		float bestScore = Float.MAX_VALUE;
		for (Ship3D o : sim.ships) {
			if (o.destroyed || o.owner == s.owner || o.fighter || o.invulnerable) continue; // ships fight ships; PD handles fighters
			if (order != null && !allowedTarget(order, o)) continue;
			// Distance from us, plus half the distance from our fleet, so a fleet tends to engage together.
			Vec3 home = sim.mobileCentre(s.owner);
			float d = o.pos.distance(s.pos) + (home != null ? 0.5f * o.pos.distance(home) : 0f);
			if (o == target) d *= 0.75f;
			if (o == s.lastHitBy) d *= 0.8f;
			if (o.flux != null && o.flux.disabled()) d *= 0.4f; // overloaded or venting: pounce
			if (d < bestScore) {
				bestScore = d;
				best = o;
			}
		}
		return best;
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
			throttle = attackRun(s, want, dt);
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
	private float attackRun(Ship3D s, Vec3 want, float dt) {
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
		return 1f;
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
			float d = away.length();
			// A station section or core doesn't move: keep clear of its real shape plus our own size
			// (its collision circle can be far bigger than the hull, and at station scale a margin
			// proportional to it would keep us out of gun range).
			float clear = !o.fixedPart() ? (s.radius + o.radius) * 1.6f
					: (d > 1e-3f ? o.reachToward(tE.set(away).scale(1f / d)) : o.radius) + s.radius * 1.6f + 80f;
			if (d >= clear || d < 1e-3f) continue;
			float k = (clear - d) / clear;
			push.addScaled(away.scale(1f / d), k * 2.5f);
			// Strafe out of the way too, which works even while the nose is busy.
			Vec3 local = s.rot.inverseRotate(away, tD);
			c.strafeX = FlightModel.clamp(c.strafeX + Math.signum(local.x) * k * 2f, -1f, 1f);
			c.strafeY = FlightModel.clamp(c.strafeY + Math.signum(local.y) * k * 2f, -1f, 1f);
		}
		if (push.lengthSq() > 0f) want.add(push).normalize();
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

	/**
	 * Every mount engages on its own: PD takes missiles first, everything else the AI's target if it
	 * can reach the lead, else the nearest enemy it can reach. Ship-killing shots are held while the
	 * barrel is still swinging onto the lead or a friendly is in the way.
	 */
	private void fireWeapons(Sim sim, Ship3D s, float dt) {
		Vec3 aim = new Vec3();
		Ship3D[] tgt = new Ship3D[1];
		float coneCos = (float) Math.cos(Math.toRadians(sim.tuning.aiFireConeDeg));
		for (WeaponMount m : s.weapons) {
			boolean engage = sim.autofireAim(s, m, target, aim, tgt);
			boolean fire = engage;
			if (engage && tgt[0] != null) {
				Vec3 mw = m.worldPos(s, tC);
				Vec3 dir = tD.set(aim).sub(mw);
				float len = dir.length();
				if (len > 1e-3f) {
					dir.scale(1f / len);
					boolean guided = m.spec.kind == WeaponSpec3D.Kind.MISSILE && m.spec.guided;
					Vec3 barrel = s.rot.rotate(m.aimDir, new Vec3());
					if (!guided && barrel.dot(dir) < coneCos) fire = false;
					if (!Sim.missileWorthFiring(s, m, tgt[0])) fire = false;
					boolean helpless = tgt[0].flux != null && tgt[0].flux.disabled();
					float limit = helpless ? 0.98f : sim.tuning.aiFluxFireLimit;
					if (s.flux != null && s.flux.level() > limit && !m.spec.pd) fire = false;
					if (fire && friendlyInLine(sim, s, mw, aim)) fire = false;
				}
			}
			sim.combat.stepMount(s, m, engage ? aim : null, fire, tgt[0], dt, sim.tuning);
		}
	}

	private static boolean friendlyInLine(Sim sim, Ship3D s, Vec3 from, Vec3 to) {
		for (Ship3D o : sim.ships) {
			if (Combat.sameStructure(o, s) || o.destroyed || o.owner != s.owner) continue; // own structure: shots pass through
			if (Combat.segmentSphere(from, to, o.pos, o.radius * 0.8f) >= 0f) return true;
		}
		return false;
	}
}
