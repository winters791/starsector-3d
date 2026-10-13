package s3d.core;

import s3d.math.Vec3;

/**
 * A ship system (or a phase cloak) rebuilt for 3D. Timing, charges, flux costs and what's blocked
 * while it runs come from the vanilla system's data; what it does is one of the kinds below, with
 * magnitudes from the "systems" section of the tuning file. Vanilla's own system scripts aren't
 * used (the puppets' systems stay disabled).
 *
 * Life cycle like vanilla: IDLE -> IN (charge-up, effect ramps 0..1) -> ACTIVE -> OUT (charge-down,
 * effect ramps 1..0) -> COOLDOWN -> IDLE. Toggles stay ACTIVE until switched off. Charges regenerate
 * at their own rate. Flux per second is paid while IN/ACTIVE; if it can't be paid, the system shuts.
 */
public final class ShipSystem3D {
	public enum Kind {
		BURN_DRIVE, MANEUVERING_JETS, PLASMA_JETS, MICROBURN, DISPLACER, PHASE_TELEPORTER,
		AMMO_FEED, HIGH_ENERGY_FOCUS, FAST_MISSILE_RACKS, MISSILE_AUTOFORGE, LIDAR_ARRAY,
		FORTRESS_SHIELD, DAMPER, TEMPORAL_SHELL, PHASE_CLOAK,
		EMP_EMITTER, FLARES, RECALL, ENTROPY_AMPLIFIER, MINE_STRIKE, CANISTER_FLAK,
		TARGETING_FEED, DRONE_STRIKE, COMBAT_BURN, ORION_DEVICE, NOVA_BURST, RESERVE_DEPLOYMENT, ACAUSAL_DISRUPTOR,
		/** Station / ship drone launchers: vanilla's puppet launches the drones (the host keeps that system on); 3D flies them. */
		DRONE_LAUNCHER,
		ENERGY_LASH, FRAGMENT_VOLLEY, INCURSION_MODE, EXTRACTION_PROTOCOL, CONSTRUCTION_SWARM,
		CONVULSIVE_LUNGE, DARKENED_GAZE, TENEBROUS_EXPULSION, MOTE_CONTROL,
		UNSUPPORTED
	}

	public enum State { IDLE, IN, ACTIVE, OUT, COOLDOWN }

	// ---- spec ----
	public final String id, name;
	public final Kind kind;
	public float chargeUp, active, chargeDown, cooldown;
	public int maxAmmo;
	public float ammoPerSecond;
	public float fluxPerUse, fluxPerSecond;
	public boolean toggle, hardFlux;
	/** What vanilla blocks while it runs. */
	public boolean firingAllowed = true, turningAllowed = true, shieldAllowed = true, dissipationAllowed = true, alwaysAccelerate;
	/** False = hard flux can't dissipate while it runs (the phase cloak: its upkeep is hard flux that stays until you unphase). */
	public boolean hardDissipationAllowed = true;
	/**
	 * Phase cloak, vanilla's "phase coil stress": while cloaked, top speed falls from 100% to coilMinSpeed
	 * as hard flux climbs to coilStressLevel of capacity (0 = always at the minimum, negative = off).
	 * Skills and hullmods add top speed / acceleration while cloaked (percent, then multiplier).
	 */
	public float coilMinSpeed = 1f, coilStressLevel = -1f;
	public float phaseSpeedPercent, phaseSpeedMult = 1f, phaseAccelPercent, phaseAccelMult = 1f;
	/** Vanilla sound ids for using, running and switching off the system (null = none). */
	public String useSound, loopSound, offSound;
	/** EMP emitter: range, damage and EMP per arc. Also the reach of the systems that act at a distance (vanilla map units). */
	public float range = 1000f, damage, empDamage;
	/**
	 * Missile Autoforge: usable once a missile rack is below this fraction of its ammo; refills each by
	 * its base ammo (reloadBaseOnly) or to full.
	 */
	public float reloadBelow = 1f;
	public boolean reloadBaseOnly;
	/** Entropy Amplifier: damage-taken multiplier on its target. Targeting Feed: damage multiplier on the carrier's fighters. */
	public float effectMult = 1f;
	/** Lidar Array at full effect: range bonus (fraction), rate-of-fire multiplier bonus, shot speed bonus (fraction). */
	public float lidarRange, lidarRof, lidarSpeed;
	/** Mine Strike: the mine. Canister Flak: the canister. Termination Sequence: the missile a drone becomes. */
	public WeaponSpec3D payload;
	/** Canister Flak: the hidden system slots it throws canisters from, and canisters per slot per use. */
	public final java.util.List<WeaponMount> slots = new java.util.ArrayList<>();
	public int burst = 1;
	/** Termination Sequence: drones turned into missiles per use. */
	public int dronesPerUse = 1;
	/** For the log: what the host set up for this system. */
	public String note;
	/** Its own effect sizes, from another mod's data/config/s3d/systems.csv row (null = the battle's tuning). */
	public Tuning tuning;
	/** How it looks while it runs (vanilla's jitter, weapon glow, engine and shield colours). */
	public SystemLook look;
	/** Orion Device / Nova Burst: the charges, the push and the train (null for other kinds). */
	public OrionDrive orion;
	/** Threat and Shrouded systems: their own state and numbers (null for other kinds). */
	public ExoticSystems ex;
	/** Mote Attractor: the motes and the attractor (null for other kinds). */
	public MoteControl motes;
	/** Quantum Disruptor: how long its target is knocked into overload (vanilla's DISRUPTION_DUR). */
	public float disruptSeconds = 1f;
	/**
	 * Set when the system does something only the host can (Reserve Deployment's extra fighters come
	 * from the vanilla carrier's bays); the host does it and clears this.
	 */
	public boolean hostRequest;

	// ---- state ----
	public State state = State.IDLE;
	public float stateTime, effect, ammo;
	private float pulseTimer;
	private boolean fired;
	/** Entropy Amplifier: the ship it's working on (picked when used). */
	public Ship3D sysTarget;
	/** Canister Flak: canisters still to throw from each slot, and each slot's wait until the next. */
	private int[] slotLeft;
	private float[] slotWait;
	/** The sim this system was last stepped in (to find targets when it's used). */
	private Sim sim;
	private static final java.util.Random RND = new java.util.Random();

	/** Fixes the random rolls (canister directions, speeds and fuses, slot waits) so a benchmark repeats. */
	public static void seed(long seed) {
		RND.setSeed(seed);
	}

	public ShipSystem3D(String id, String name, Kind kind) {
		this.id = id;
		this.name = name;
		this.kind = kind;
		this.look = SystemLook.defaults(kind);
		if (kind == Kind.ORION_DEVICE) orion = OrionDrive.orion();
		else if (kind == Kind.NOVA_BURST) orion = OrionDrive.nova();
		switch (kind) {
			case ENERGY_LASH: case FRAGMENT_VOLLEY: case INCURSION_MODE: case EXTRACTION_PROTOCOL: case CONSTRUCTION_SWARM:
			case CONVULSIVE_LUNGE: case DARKENED_GAZE: case TENEBROUS_EXPULSION:
				ex = new ExoticSystems();
				break;
			case MOTE_CONTROL:
				motes = new MoteControl();
				break;
			default:
		}
	}

	/** Threat units' systems that only an Overseer's Energy Lash can fire (they hold no charge of their own). */
	public boolean lashActivated() {
		return kind == Kind.FRAGMENT_VOLLEY || kind == Kind.INCURSION_MODE || kind == Kind.EXTRACTION_PROTOCOL;
	}

	/** An Overseer's lash hit this ship: its system gets a charge and goes off (vanilla's hitWithEnergyLash). */
	public void lashed(Ship3D s) {
		if (state != State.IDLE) return;
		ammo = Math.max(ammo, 1f);
		activate(s);
	}

	/** Maps a vanilla system id to the 3D kind that rebuilds it (UNSUPPORTED if there's none yet). */
	public static Kind kindFor(String id, boolean phaseCloak) {
		return kindFor(id, phaseCloak, null, null);
	}

	/**
	 * As above; a system we don't know by id (another mod's) falls back on its vanilla system type or AI
	 * type where those pin down what it does (a reload, a skimmer, a teleporter, an entropy amplifier).
	 */
	public static Kind kindFor(String id, boolean phaseCloak, String type, String aiType) {
		if (phaseCloak) return Kind.PHASE_CLOAK;
		if (id == null) return Kind.UNSUPPORTED;
		switch (id) {
			case "burndrive": return Kind.BURN_DRIVE;
			case "maneuveringjets": return Kind.MANEUVERING_JETS;
			case "plasmajets": return Kind.PLASMA_JETS;
			case "microburn": case "microburn_omega": return Kind.MICROBURN;
			case "displacer": case "displacer_degraded": case "displacer_threat": return Kind.DISPLACER;
			case "phaseteleporter": return Kind.PHASE_TELEPORTER;
			case "ammofeed": return Kind.AMMO_FEED;
			case "highenergyfocus": return Kind.HIGH_ENERGY_FOCUS;
			case "fastmissileracks": return Kind.FAST_MISSILE_RACKS;
			case "forgevats": case "forgevats_station": return Kind.MISSILE_AUTOFORGE;
			case "lidararray": return Kind.LIDAR_ARRAY;
			case "fortressshield": return Kind.FORTRESS_SHIELD;
			case "damper": case "damper_omega": return Kind.DAMPER;
			case "temporalshell": return Kind.TEMPORAL_SHELL;
			case "phasecloak": return Kind.PHASE_CLOAK;
			case "emp": return Kind.EMP_EMITTER;
			case "flarelauncher": case "flarelauncher_active": case "flarelauncher_fighter": return Kind.FLARES;
			case "recalldevice": return Kind.RECALL;
			case "entropyamplifier": return Kind.ENTROPY_AMPLIFIER;
			case "mine_strike": case "mine_strike_station": return Kind.MINE_STRIKE;
			case "canister_flak": return Kind.CANISTER_FLAK;
			case "targetingfeed": return Kind.TARGETING_FEED;
			case "drone_strike": return Kind.DRONE_STRIKE;
			case "combat_burn": return Kind.COMBAT_BURN;
			case "orion_device": return Kind.ORION_DEVICE;
			case "nova_burst": return Kind.NOVA_BURST;
			case "reservewing": return Kind.RESERVE_DEPLOYMENT;
			case "acausaldisruptor": return Kind.ACAUSAL_DISRUPTOR;
			case "energy_lash": return Kind.ENERGY_LASH;
			case "fragment_volley": return Kind.FRAGMENT_VOLLEY;
			case "incursion_mode": return Kind.INCURSION_MODE;
			case "extraction_protocol": return Kind.EXTRACTION_PROTOCOL;
			case "construction_swarm": return Kind.CONSTRUCTION_SWARM;
			case "convulsive_lunge": case "shrouded_vortex": return Kind.CONVULSIVE_LUNGE;
			case "darkened_gaze": return Kind.DARKENED_GAZE;
			case "tenebrous_expulsion": return Kind.TENEBROUS_EXPULSION;
			case "mote_control": return Kind.MOTE_CONTROL;
			default:
				if ("DRONE_LAUNCHER".equals(type)) return Kind.DRONE_LAUNCHER;
				if ("RESERVE_DEPLOYMENT".equals(aiType)) return Kind.RESERVE_DEPLOYMENT;
				if ("ACAUSAL_DISRUPTOR".equals(aiType)) return Kind.ACAUSAL_DISRUPTOR;
				if ("ENTROPY_AMPLIFIER".equals(aiType)) return Kind.ENTROPY_AMPLIFIER;
				if ("AMMO_RELOAD".equals(type)) return Kind.MISSILE_AUTOFORGE;
				if ("DISPLACER".equals(type)) return Kind.DISPLACER;
				if ("TELEPORTER".equals(type)) return Kind.PHASE_TELEPORTER;
				return Kind.UNSUPPORTED;
		}
	}

	public boolean supported() {
		return kind != Kind.UNSUPPORTED;
	}

	public boolean on() {
		return state == State.IN || state == State.ACTIVE;
	}

	public boolean ready(Ship3D s) {
		if (!supported() || state != State.IDLE) return false;
		if (maxAmmo > 0 && ammo < 1f) return false;
		if (s.flux != null && s.flux.disabled()) return false;
		if (!usable(s)) return false;
		return s.flux == null || s.flux.maxFlux <= 0f || s.flux.flux + fluxPerUse <= s.flux.maxFlux;
	}

	/** Kinds that need something to work on: a low missile rack, a target in reach, a drone. */
	private boolean usable(Ship3D s) {
		if (ex != null && sim != null && !ex.usable(s, this, sim)) return false;
		switch (kind) {
			case MISSILE_AUTOFORGE:
				for (WeaponMount m : s.weapons) {
					if (m.spec.kind == WeaponSpec3D.Kind.MISSILE && m.spec.maxAmmo > 0 && m.ammo < m.spec.maxAmmo * reloadBelow) return true;
				}
				return false;
			case ENTROPY_AMPLIFIER:
				return sim != null && validTarget(s, aimedShip(s, sim), range * sim.tuning.weaponRangeMult);
			case DRONE_STRIKE: {
				if (sim == null || payload == null) return false;
				if (!validTarget(s, aimedShip(s, sim), payload.range * sim.tuning.weaponRangeMult)) return false;
				for (Ship3D f : sim.ships) if (isDrone(s, f)) return true;
				return false;
			}
			case MINE_STRIKE:
				return sim != null && payload != null && (s == sim.player ? sim.hasAimPoint : aimedShip(s, sim) != null);
			case CANISTER_FLAK:
				return payload != null && !slots.isEmpty();
			default:
				return true;
		}
	}

	/** An enemy this system can work on, whose hull is within reach (3D units). */
	private static boolean validTarget(Ship3D s, Ship3D t, float reach) {
		return t != null && !t.destroyed && !t.phased && !t.invulnerable && t.owner != s.owner && t.surfaceDistance(s.pos) <= reach;
	}

	/** The ship a system aims at: yours = your locked target (else the one nearest the reticle); an AI's = its target. */
	static Ship3D aimedShip(Ship3D s, Sim sim) {
		if (s == sim.player) return sim.aimTarget;
		return s.ai != null ? s.ai.target : null;
	}

	private static boolean isDrone(Ship3D carrier, Ship3D f) {
		return f.fighter && f.carrier == carrier && !f.destroyed && f.carriedBy == null;
	}

	/** Use / toggle. Returns true if something happened. */
	public boolean activate(Ship3D s) {
		if (toggle && on()) {
			state = State.OUT;
			stateTime = 0f;
			justStopped = true;
			return true;
		}
		if (!ready(s)) return false;
		if (fluxPerUse > 0f && s.flux != null && !s.flux.addSystemFlux(fluxPerUse, hardFlux)) return false;
		boolean train = orion != null && orion.trainLeft > 0; // a train's own next detonation
		if (maxAmmo > 0) ammo -= 1f;
		state = State.IN;
		stateTime = 0f;
		fired = false;
		pulseTimer = 0f;
		justUsed = true;
		if (kind == Kind.ENTROPY_AMPLIFIER) sysTarget = aimedShip(s, sim); // usable() made sure it's valid
		if (kind == Kind.ACAUSAL_DISRUPTOR) sysTarget = disruptorTarget(s);
		if (ex != null && sim != null) ex.activated(s, this, sim);
		if (motes != null && sim != null) motes.used(s, this, sim);
		if (orion != null) orion.trainLeft = train ? orion.trainLeft - 1 : maxAmmo > 0 ? Math.max(0, (int) ammo) : 0; // one press: every charge left
		return true;
	}

	/**
	 * Quantum Disruptor's pick, as vanilla's: the ship's target if it's within reach (hull edges), else
	 * the nearest enemy ship (not a fighter) that is; null = nothing to disrupt.
	 */
	private Ship3D disruptorTarget(Ship3D s) {
		if (sim == null) return null;
		float reach = range * sim.tuning.weaponRangeMult;
		Ship3D t = aimedShip(s, sim);
		if (t != null && (t.fighter || t.owner == s.owner || t.destroyed || t.pos.distance(s.pos) > reach + s.radius + t.radius)) t = null;
		if (t == null) {
			float best = Float.MAX_VALUE;
			for (Ship3D o : sim.ships) {
				if (o.owner == s.owner || o.destroyed || o.fighter || o.invulnerable || o.unarmedPart()) continue;
				float d = o.pos.distance(s.pos);
				if (d <= reach + s.radius + o.radius && d < best) {
					best = d;
					t = o;
				}
			}
		}
		return t != null && t.flux != null && !t.flux.disabled() ? t : null;
	}

	/** Set by activate/switch-off for the sim to turn into a sound (cleared after). */
	public boolean justUsed, justStopped;

	/** Seconds until usable again (0 = ready or running). */
	public float waitTime() {
		switch (state) {
			case COOLDOWN: return Math.max(0f, cooldown - stateTime);
			case OUT: return Math.max(0f, chargeDown - stateTime) + cooldown;
			default: return 0f;
		}
	}

	/**
	 * Advances the state machine, pays upkeep, and applies the effect to the ship's system
	 * modifiers (reset by the sim every frame before this runs).
	 */
	public void step(Ship3D s, Sim sim, float dt) {
		this.sim = sim;
		if (dt <= 0f || !supported()) return;
		if (ex != null) ex.step(s, this, sim, dt);
		if (motes != null) motes.step(s, this, sim, dt);
		if (orion != null) {
			orion.step(s, sim, dt);
			if (orion.trainLeft > 0 && state == State.IDLE) {
				if (ready(s)) activate(s); // the train's next detonation, a cooldown after the last
				else orion.trainLeft = 0; // overloaded, out of flux: the train stops
			}
		}
		if (kind == Kind.CANISTER_FLAK) throwCanisters(s, sim, dt);
		if (maxAmmo > 0 && ammo < maxAmmo) ammo = Math.min(maxAmmo, ammo + ammoPerSecond * dt);
		if (on() && s.flux != null && s.flux.disabled()) {
			state = State.OUT; // overloaded or venting shuts systems down
			stateTime = 0f;
		}
		if (on() && fluxPerSecond > 0f && s.flux != null && !s.flux.addSystemFlux(fluxPerSecond * dt, hardFlux)) {
			state = State.OUT;
			stateTime = 0f;
		}
		stateTime += dt;
		switch (state) {
			case IN:
				effect = chargeUp > 0f ? Math.min(1f, stateTime / chargeUp) : 1f;
				if (stateTime >= chargeUp) {
					state = State.ACTIVE;
					stateTime = 0f;
					effect = 1f;
					onFullyActive(s, sim);
				}
				break;
			case ACTIVE:
				effect = 1f;
				if (!toggle && stateTime >= active) {
					state = State.OUT;
					stateTime = 0f;
				}
				break;
			case OUT:
				effect = chargeDown > 0f ? Math.max(0f, 1f - stateTime / chargeDown) : 0f;
				if (stateTime >= chargeDown) {
					state = cooldown > 0f ? State.COOLDOWN : State.IDLE;
					stateTime = 0f;
					effect = 0f;
				}
				break;
			case COOLDOWN:
				effect = 0f;
				if (stateTime >= cooldown) {
					state = State.IDLE;
					stateTime = 0f;
				}
				break;
			default:
				effect = 0f;
		}
		if (effect > 0f) apply(s, sim, dt);
	}

	/** One-shot effects at the end of charge-up. */
	private void onFullyActive(Ship3D s, Sim sim) {
		if (fired) return;
		fired = true;
		Tuning t = tuning != null ? tuning : sim.tuning;
		switch (kind) {
			case DISPLACER: {
				// Short blink along the way the ship is going (its nose if it's nearly still).
				Vec3 dir = s.vel.lengthSq() > 100f ? new Vec3(s.vel).normalize() : s.rot.forward(new Vec3());
				s.pos.addScaled(dir, t.sysDisplacerDistance * Math.max(0.5f, s.scale)); // the jitter (SystemLook) is the visual
				break;
			}
			case FAST_MISSILE_RACKS:
				for (WeaponMount m : s.weapons) {
					if (m.spec.kind == WeaponSpec3D.Kind.MISSILE && m.spec.maxAmmo > 0) m.ammo = m.spec.maxAmmo;
				}
				break;
			case FLARES:
				sim.combat.launchFlares(s, t.sysFlareCount, t.sysFlareLife);
				break;
			case RECALL:
				for (Ship3D f : sim.ships) if (f.fighter && f.carrier == s && !f.destroyed) f.landed = true;
				break;
			case MISSILE_AUTOFORGE:
				for (WeaponMount m : s.weapons) {
					if (m.spec.kind != WeaponSpec3D.Kind.MISSILE || m.spec.maxAmmo <= 0) continue;
					int add = reloadBaseOnly && m.spec.baseMaxAmmo > 0 ? m.spec.baseMaxAmmo : m.spec.maxAmmo;
					m.ammo = Math.min(m.spec.maxAmmo, m.ammo + add);
				}
				break;
			case PHASE_TELEPORTER:
				teleport(s, sim);
				break;
			case MINE_STRIKE:
				layMine(s, sim);
				break;
			case CANISTER_FLAK:
				if (slotLeft == null || slotLeft.length != slots.size()) {
					slotLeft = new int[slots.size()];
					slotWait = new float[slots.size()];
				}
				for (int i = 0; i < slotLeft.length; i++) {
					slotLeft[i] += Math.max(1, burst);
					slotWait[i] = 0f;
				}
				break;
			case DRONE_STRIKE:
				launchDrones(s, sim);
				break;
			case ORION_DEVICE:
			case NOVA_BURST:
				orion.fire(s, this, sim);
				break;
			case RESERVE_DEPLOYMENT:
				hostRequest = true; // extra fighters from the vanilla carrier's bays
				break;
			case ENERGY_LASH:
			case CONSTRUCTION_SWARM:
				ex.fullyActive(s, this, sim);
				break;
			case ACAUSAL_DISRUPTOR: {
				Ship3D tg = sysTarget;
				if (tg != null && !tg.destroyed && tg.flux != null && !tg.flux.disabled()) {
					tg.flux.disrupt(disruptSeconds);
					tg.overloadTint = new float[] {1f, 0.61f, 1f}; // vanilla's disruption overload colour
					sim.combat.sounds.play("disabled_large", tg.pos, 0.8f);
				}
				break;
			}
			default:
		}
	}

	/**
	 * Phase Teleporter: a jump to where you aim (an AI picks its own spot), up to the system's range;
	 * it never ends up inside another hull.
	 */
	private void teleport(Ship3D s, Sim sim) {
		float reach = range * sim.tuning.weaponRangeMult;
		Vec3 dest = null;
		if (s == sim.player) {
			if (sim.hasAimPoint) dest = new Vec3(sim.aimPoint);
		} else if (s.ai != null) {
			dest = s.ai.teleportDestination(sim, s, reach);
		}
		if (dest == null) dest = new Vec3(s.pos).addScaled(s.rot.forward(new Vec3()), reach);
		Vec3 d = new Vec3(dest).sub(s.pos);
		if (d.length() > reach) dest.set(s.pos).addScaled(d.normalize(), reach);
		for (Ship3D o : sim.ships) {
			if (o == s || o.destroyed || o.fighter) continue;
			Vec3 away = new Vec3(dest).sub(o.pos);
			float clear = o.extent() + s.radius;
			float len = away.length();
			if (len >= clear) continue;
			if (len < 1e-3f) away.set(s.pos).sub(o.pos);
			if (away.lengthSq() < 1e-6f) away.set(0f, 1f, 0f);
			dest.set(o.pos).addScaled(away.normalize(), clear);
		}
		s.pos.set(dest); // the jitter (SystemLook) is the visual
	}

	/**
	 * Mine Strike: a mine appears where you aim (an AI: where its target is heading), within the
	 * system's range; if that's inside a hull, just outside it on the near side.
	 */
	private void layMine(Ship3D s, Sim sim) {
		float reach = range * sim.tuning.weaponRangeMult + s.radius;
		Ship3D t = aimedShip(s, sim);
		Vec3 at;
		if (s == sim.player) at = new Vec3(sim.aimPoint);
		else if (t != null) at = new Vec3(t.pos).addScaled(t.vel, payload.mineArmTime + payload.mineWindup);
		else return;
		Vec3 d = new Vec3(at).sub(s.pos);
		if (d.length() > reach) at.set(s.pos).addScaled(d.normalize(), reach);
		for (Ship3D o : sim.ships) {
			if (o.destroyed || o.fighter) continue;
			Vec3 toward = new Vec3(s.pos).sub(o.pos);
			if (toward.lengthSq() < 1e-6f) continue;
			if (o.surfaceDistance(at) > payload.missileRadius) continue;
			// Inside (or touching) a hull: out to its surface on the side facing the layer.
			toward.normalize();
			at.set(o.pos).addScaled(toward, o.reachToward(toward) + payload.missileRadius * 2f + 20f);
		}
		Vec3 jitter = new Vec3(RND.nextFloat() - 0.5f, RND.nextFloat() - 0.5f, RND.nextFloat() - 0.5f).scale(60f);
		sim.combat.spawnMissile(s, payload, at.add(jitter), new Vec3(RND.nextFloat() - 0.5f, 0f, RND.nextFloat() - 0.5f), 0f, null);
		// Vanilla's mine fades in through a pink jitter: a pink shimmer where it appears.
		sim.combat.effects.add(new Effect(Effect.Kind.FLASH, at, payload.missileRadius * 6f, 0.5f).color(1f, 0.6f, 1f));
	}

	/**
	 * Canister Flak: each slot throws its canisters one by one, a quarter to one second apart, at random
	 * speeds and fuses. Vanilla throws them along the slot's arc in its plane; in 3D they spread through
	 * a cone round the slot's direction (at least 45 deg half-angle) so the cloud screens from above and
	 * below too.
	 */
	private void throwCanisters(Ship3D s, Sim sim, float dt) {
		if (slotLeft == null || payload == null || s.destroyed) return;
		for (int i = 0; i < slotLeft.length; i++) {
			if (slotLeft[i] <= 0) continue;
			slotWait[i] -= dt;
			if (slotWait[i] > 0f) continue;
			slotWait[i] = 0.25f + 0.75f * RND.nextFloat();
			slotLeft[i]--;
			WeaponMount m = slots.get(i);
			Vec3 dir = randomInCone(m.restDir, Math.max(m.halfArcRad, (float) Math.toRadians(45)));
			s.rot.rotate(dir, dir);
			float speed = Math.max(payload.launchSpeed, payload.missileMaxSpeed) * (0.25f + 0.75f * RND.nextFloat());
			Missile3D c = sim.combat.spawnMissile(s, payload, m.worldPos(s, new Vec3()), dir, speed, null);
			c.lifeMult = 0.25f + 0.75f * RND.nextFloat();
			sim.combat.sounds.play(payload.fireSound, c.pos, 0.8f);
		}
	}

	/** A random direction within half-angle `half` of unit vector axis, spread evenly over the cone's solid angle. */
	private static Vec3 randomInCone(Vec3 axis, float half) {
		float cosMax = (float) Math.cos(Math.min(half, Math.PI));
		float c = 1f - RND.nextFloat() * (1f - cosMax), sn = (float) Math.sqrt(Math.max(0f, 1f - c * c));
		double phi = RND.nextDouble() * Math.PI * 2;
		Vec3 u = new Vec3(Math.abs(axis.y) < 0.9f ? 0f : 1f, Math.abs(axis.y) < 0.9f ? 1f : 0f, 0f).cross(axis).normalize();
		Vec3 v = new Vec3(axis).cross(u);
		return new Vec3(axis).scale(c).addScaled(u, sn * (float) Math.cos(phi)).addScaled(v, sn * (float) Math.sin(phi)).normalize();
	}

	/** Termination Sequence: the drones nearest the target become missiles at it (each drone rides its missile and dies with it). */
	private void launchDrones(Ship3D s, Sim sim) {
		Ship3D t = aimedShip(s, sim);
		if (t == null || payload == null) return;
		java.util.List<Ship3D> drones = new java.util.ArrayList<>();
		for (Ship3D f : sim.ships) if (isDrone(s, f)) drones.add(f);
		drones.sort((a, b) -> Float.compare(a.pos.distance(t.pos), b.pos.distance(t.pos)));
		for (int i = 0; i < Math.min(dronesPerUse, drones.size()); i++) {
			Ship3D d = drones.get(i);
			Vec3 dir = new Vec3(t.pos).sub(d.pos);
			Missile3D m = sim.combat.spawnMissile(s, payload, d.pos, dir, Math.max(payload.launchSpeed, d.speed()), t);
			m.vel.set(d.vel).addScaled(m.dir, Math.max(0f, payload.launchSpeed));
			sim.combat.carry(m, d);
		}
	}

	/** Continuous effects, scaled by the effect level. */
	private void apply(Ship3D s, Sim sim, float dt) {
		Tuning t = tuning != null ? tuning : sim.tuning;
		float e = effect;
		if (!firingAllowed) s.sysNoFire = true;
		if (!turningAllowed && kind != Kind.BURN_DRIVE) s.sysTurnMult *= 1f - e; // Burn Drive keeps some steering (sysBurnTurnMult)
		if (!shieldAllowed) s.sysNoShield = true;
		if (!dissipationAllowed) s.sysNoDissipation = true;
		if (!hardDissipationAllowed) s.sysNoHardDissipation = true;
		if (alwaysAccelerate) s.sysFullThrottle = true;
		switch (kind) {
			case BURN_DRIVE:
				s.sysSpeedFlat += t.sysBurnSpeed * e;
				s.sysAccelMult *= 1f + (t.sysBurnAccelMult - 1f) * e;
				s.sysTurnMult *= 1f - (1f - t.sysBurnTurnMult) * e;
				s.sysFullThrottle = true;
				break;
			case MANEUVERING_JETS:
				s.sysSpeedMult *= 1f + t.sysJetsSpeed * e;
				s.sysAccelMult *= 1f + (t.sysJetsAccelMult - 1f) * e;
				s.sysTurnMult *= 1f + t.sysJetsTurn * e;
				s.sysTurnAccelMult *= 1f + (t.sysJetsAccelMult - 1f) * e;
				break;
			case PLASMA_JETS:
				s.sysSpeedMult *= 1f + t.sysPlasmaSpeed * e;
				s.sysAccelMult *= 1f + (t.sysJetsAccelMult - 1f) * e;
				s.sysTurnMult *= 1f + t.sysJetsTurn * e;
				break;
			case FRAGMENT_VOLLEY: case INCURSION_MODE: case EXTRACTION_PROTOCOL:
			case CONVULSIVE_LUNGE: case DARKENED_GAZE: case TENEBROUS_EXPULSION:
				ex.apply(s, this, sim, e, dt);
				break;
			case COMBAT_BURN: {
				// Vanilla: +100 top speed and +100 acceleration while it runs (top speed falls back as it powers down).
				if (state != State.OUT) s.sysSpeedFlat += 100f * e;
				float acc = s.params != null ? Math.max(1f, s.params.accel) : 100f;
				s.sysAccelMult *= 1f + 100f * e / acc;
				break;
			}
			case MICROBURN:
				if (state == State.ACTIVE) {
					s.sysSpeedFlat += t.sysMicroburnSpeed;
					s.sysAccelMult *= 10f;
					s.sysFullThrottle = true;
				}
				break;
			case AMMO_FEED:
				s.sysBallisticRofMult *= 1f + (t.sysAmmoFeedRof - 1f) * e;
				s.sysBallisticFluxMult *= 1f - (1f - t.sysAmmoFeedFlux) * e;
				break;
			case HIGH_ENERGY_FOCUS:
				s.sysEnergyDamageMult *= 1f + (t.sysEnergyFocusDamage - 1f) * e;
				break;
			case FORTRESS_SHIELD:
				s.sysShieldDamageTakenMult *= 1f - (1f - t.sysFortressShieldTaken) * e;
				s.sysForceShield = true;
				break;
			case DAMPER:
				s.sysDamageTakenMult *= 1f - (1f - t.sysDamperTaken) * e;
				break;
			case TEMPORAL_SHELL:
				s.timeMult *= 1f + (t.sysTemporalTimeMult - 1f) * e;
				break;
			case PHASE_CLOAK:
				s.phaseLevel = e;
				if (e > 0.5f) s.phased = true;
				s.timeMult *= 1f + (t.sysPhaseTimeMult - 1f) * e;
				s.sysSpeedMult *= (1f + phaseSpeedPercent / 100f * e) * (1f + (phaseSpeedMult - 1f) * e);
				s.sysAccelMult *= (1f + phaseAccelPercent / 100f * e) * (1f + (phaseAccelMult - 1f) * e);
				s.sysTopSpeedMult *= coilSpeed(s, e);
				break;
			case EMP_EMITTER:
				if (state == State.ACTIVE) {
					pulseTimer -= dt;
					if (pulseTimer <= 0f) {
						pulseTimer = t.sysEmpInterval;
						sim.combat.empEmitterPulse(s, range, damage, empDamage, t.sysEmpTargets);
					}
				}
				break;
			case ENTROPY_AMPLIFIER: {
				// The target takes more damage from everything, hull, armor and shield (the sim resets every
				// ship's modifiers before any system runs, so this sticks for the frame).
				Ship3D tg = sysTarget;
				if (tg == null || tg.destroyed) break;
				float k = 1f + (effectMult - 1f) * e;
				tg.sysDamageTakenMult *= k;
				tg.sysShieldDamageTakenMult *= k; // the red jitter on it is drawn from SystemLook.targetJitter
				break;
			}
			case TARGETING_FEED:
				for (Ship3D f : sim.ships) {
					if (f.fighter && f.carrier == s && !f.destroyed) f.sysDamageMult *= 1f + (effectMult - 1f) * e;
				}
				break;
			case LIDAR_ARRAY:
				if (state == State.IN) {
					s.sysLidar = 1; // winding up: only missiles may fire
				} else {
					s.sysLidar = 2;
					s.sysLidarRangeMult *= 1f + lidarRange * e;
					s.sysLidarRofMult *= 1f + lidarRof * e;
					s.sysLidarSpeedMult *= 1f + lidarSpeed * e;
				}
				break;
			default:
		}
	}

	/**
	 * Phase coil stress, as vanilla's: hard flux over coilStressLevel of capacity pushes top speed down
	 * to coilMinSpeed (scaled by how far the cloak is in, e). 1 = no stress.
	 */
	public float coilSpeed(Ship3D s, float e) {
		if (coilStressLevel < 0f || s.flux == null || s.flux.maxFlux <= 0f) return 1f;
		float stress = coilStressLevel <= 0f ? 1f : Math.min(1f, s.flux.hardFlux / s.flux.maxFlux / coilStressLevel);
		return coilMinSpeed + (1f - coilMinSpeed) * (1f - stress * e);
	}

	public void reset() {
		state = State.IDLE;
		stateTime = effect = 0f;
		ammo = maxAmmo;
		sysTarget = null;
		slotLeft = null;
	}

	public String status() {
		switch (state) {
			case IN: return "CHARGING";
			case ACTIVE: return toggle ? "ON" : "ACTIVE";
			case OUT: return "WINDING DOWN";
			case COOLDOWN: return String.format("COOLDOWN %.0fs", waitTime());
			default:
				if (maxAmmo > 0 && ammo < 1f) return "RECHARGING";
				return "READY";
		}
	}
}
