package s3d.core;

import java.util.Random;

import s3d.math.Vec3;

/**
 * The Threat's and the Shrouded's ship systems, rebuilt from how vanilla's scripts behave. One per
 * system that needs one (its state lives here); ShipSystem3D calls in every frame and at the moments
 * that matter. Numbers default to vanilla's public constants; the host fills them from the live
 * scripts where they're exposed.
 *
 * Threat: an Overseer's Energy Lash is how the other units' systems fire at all (they hold no charge
 * of their own): it lashes a friendly unit whose system would help right now (Fragment Volley,
 * Incursion Mode, Extraction Protocol) and that system goes off, or it lashes an enemy with heavy EMP.
 * A Fabricator's Construction Swarm sends out a swarm that builds a new ship where it stops.
 *
 * Shrouded: Convulsive Lunge (Tendril, Maw, vortices) yanks the ship toward a point 1000 away;
 * Darkened Gaze (Eye) turns its all-round shield to the back, opens it at the front and fires the
 * gaze beams through the gap; Tenebrous Expulsion (Maelstrom) spits out ejecta and backs off.
 */
public final class ExoticSystems {
	// ---- Energy Lash (vanilla EnergyLashSystemScript) ----
	public float lashEmp = 1500f, lashDamage = 0f;
	public float lashMinCooldown = 2f, lashMaxCooldown = 10f, lashCooldownPerDp = 0.33f;
	public float lashMinEnemyCooldown = 2f, lashMaxEnemyCooldown = 5f, lashPhaseCooldownMult = 2f, lashPhaseOverload = 1f;
	/** The friendly unit or enemy the AI wants lashed (the player's comes from the target lock / reticle). */
	public Ship3D lashPick;
	/** Vanilla's lash colours: the EMP fringe, its bright version on a phased target. */
	public static final float[] EMP_FRINGE = {0.51f, 0.61f, 0.57f}, EMP_FRINGE_BRIGHT = {0.84f, 1f, 0.93f};

	// ---- Fragment Volley, Incursion Mode, Extraction Protocol ----
	public float volleyRangeMult = 2f, volleyRegenMult = 10f;
	public float incursionSpeed = 100f, incursionAmmoRegen = 5f, incursionDissipation = 3f;
	public float extractionSpeed = 100f;
	private boolean extractionVented;

	// ---- Construction Swarm ----
	/** Fewest fragments any buildable Threat ship costs (vanilla's MIN_FRAGMENTS); set by the host. */
	public int minFragments = 30;

	// ---- Convulsive Lunge ----
	public float lungeDistance = 1000f, lungeSpring = 4f, lungeFriction = 1000f;
	/** Where the lunge pulls (set as it charges), and whether the windup is a Maw's (stronger particle pull). */
	public Vec3 lungeDest;
	public boolean maw;

	// ---- Darkened Gaze ----
	public float gazeOpeningDeg = 90f, gazeTurnReduction = 0.85f, gazeHullTakenMult = 2f;

	// ---- Tenebrous Expulsion ----
	public float expelInterval = 0.15f, expelArcDeg = 90f, expelBackOff = 500f;
	public String ejectaWing = "shrouded_ejecta_wing";
	public Vec3 expelDir;
	private float expelTimer;

	private static final Random RND = new Random(11);

	/** Every frame, whatever state the system is in. */
	void step(Ship3D s, ShipSystem3D sys, Sim sim, float dt) {
		switch (sys.kind) {
			case DARKENED_GAZE:
				gaze(s, sys, sim, dt);
				break;
			case EXTRACTION_PROTOCOL:
				if (sys.state == ShipSystem3D.State.OUT && !extractionVented) {
					// Vanilla: as it powers down it vents and every weapon (not the system slots') is reloaded.
					extractionVented = true;
					if (s.flux != null) s.flux.vent();
					for (WeaponMount m : s.weapons) if (m.spec.maxAmmo > 0) m.ammo = m.spec.maxAmmo;
				} else if (sys.state != ShipSystem3D.State.OUT) {
					extractionVented = false;
				}
				break;
			default:
		}
	}

	/** Picks targets and directions as it's switched on. */
	void activated(Ship3D s, ShipSystem3D sys, Sim sim) {
		switch (sys.kind) {
			case ENERGY_LASH:
				sys.sysTarget = lashTarget(s, sys, sim);
				break;
			case CONVULSIVE_LUNGE: {
				Vec3 aim = aimPoint(s, sim);
				Vec3 dir = aim != null ? new Vec3(aim).sub(s.pos) : s.rot.forward(new Vec3()).scale(-1f); // vanilla: no aim, straight back
				if (dir.lengthSq() < 1e-4f) s.rot.forward(dir);
				lungeDest = new Vec3(s.pos).addScaled(dir.normalize(), lungeDistance);
				break;
			}
			case TENEBROUS_EXPULSION: {
				Vec3 aim = aimPoint(s, sim);
				expelDir = aim != null ? new Vec3(aim).sub(s.pos) : s.rot.forward(new Vec3());
				if (expelDir.lengthSq() < 1e-4f) s.rot.forward(expelDir);
				expelDir.normalize();
				expelTimer = expelInterval;
				break;
			}
			default:
		}
	}

	/** While it runs (effect > 0). */
	void apply(Ship3D s, ShipSystem3D sys, Sim sim, float e, float dt) {
		Tuning t = sim.tuning;
		switch (sys.kind) {
			case FRAGMENT_VOLLEY:
				s.sysFragmentRegenMult *= 1f + (volleyRegenMult - 1f) * e;
				if (sys.state == ShipSystem3D.State.ACTIVE) {
					// Fragment weapons: twice the reach, cooldowns cut to a second, reloaded, firing at will; the rest hold.
					s.sysFragmentRangeMult *= volleyRangeMult;
					s.sysFragmentVolley = true;
					for (WeaponMount m : s.weapons) {
						if (!Combat.fragmentWeapon(m.spec)) continue;
						m.cooldownLeft = Math.min(m.cooldownLeft, 1f);
						if (m.spec.maxAmmo > 0) m.ammo = m.spec.maxAmmo;
					}
				}
				break;
			case INCURSION_MODE: {
				s.sysSpeedFlat += incursionSpeed * e;
				float acc = s.params != null ? Math.max(1f, s.params.accel) : 100f;
				s.sysAccelMult *= 1f + 2f * incursionSpeed * e / acc;
				s.sysAmmoRegenMult *= 1f + (incursionAmmoRegen - 1f) * e;
				s.sysDissipationMult *= 1f + (incursionDissipation - 1f) * e;
				s.sysReckless = true;
				break;
			}
			case EXTRACTION_PROTOCOL: {
				s.sysSpeedFlat += extractionSpeed * e;
				float acc = s.params != null ? Math.max(1f, s.params.accel) : 100f;
				s.sysAccelMult *= 1f + 2f * extractionSpeed * e / acc;
				s.sysBackOff = true;
				break;
			}
			case CONVULSIVE_LUNGE:
				s.sysBrake = true; // vanilla: no thrust of its own, it decelerates while the lunge pulls
				if (e > 0.85f && sys.state != ShipSystem3D.State.OUT) s.sysNoShield = true;
				if (sys.state == ShipSystem3D.State.ACTIVE && lungeDest != null) {
					// A spring to the point, less a constant friction: strong while far, nothing inside 250.
					Vec3 d = new Vec3(lungeDest).sub(s.pos);
					float dist = d.length();
					float force = lungeSpring * dist;
					force -= Math.min(force, lungeFriction);
					if (dist > 1e-3f) s.vel.addScaled(d.scale(1f / dist), force * dt);
				}
				if (sys.state == ShipSystem3D.State.OUT) lungeDest = null;
				break;
			case DARKENED_GAZE:
				s.sysTurnMult *= 1f - gazeTurnReduction * e;
				s.sysHullTakenMult *= 1f + (gazeHullTakenMult - 1f) * e;
				break;
			case TENEBROUS_EXPULSION:
				if (expelDir == null) break;
				if (sys.state == ShipSystem3D.State.ACTIVE) {
					expelTimer += dt;
					while (expelTimer >= expelInterval) {
						expelTimer -= expelInterval;
						Vec3 dir = new Vec3(expelDir);
						Combat.jitterDir(dir, (float) Math.toRadians(expelArcDeg * 0.5f), RND);
						sim.combat.spawnRequests.add(new SpawnRequest(ejectaWing, true, s, s.pos, dir, 0.5f + RND.nextFloat(), Ship3D.Behaviour.EJECTA));
						sim.combat.sounds.play("system_tenebrous_expulsion_fire", s.pos, 1f);
					}
				} else if (sys.state == ShipSystem3D.State.OUT) {
					s.vel.addScaled(expelDir, -expelBackOff * t.speedMult * dt); // pushed back by what it threw
				}
				break;
			default:
		}
	}

	/** The moment it's fully up. */
	void fullyActive(Ship3D s, ShipSystem3D sys, Sim sim) {
		switch (sys.kind) {
			case ENERGY_LASH:
				lash(s, sys, sim);
				break;
			case CONSTRUCTION_SWARM: {
				WeaponMount slot = sys.slots.isEmpty() ? null : sys.slots.get(RND.nextInt(sys.slots.size()));
				Vec3 at = slot != null ? slot.worldPos(s, new Vec3()) : new Vec3(s.pos);
				Vec3 dir = slot != null ? s.rot.rotate(slot.restDir, new Vec3()) : s.rot.forward(new Vec3());
				SpawnRequest r = new SpawnRequest(SpawnBehaviours.CONSTRUCTION_SWARM_WING, true, s, at, dir, 1f, Ship3D.Behaviour.CONSTRUCTION);
				r.pickBuild = true;
				sim.combat.spawnRequests.add(r);
				break;
			}
			default:
		}
	}

	/** Some systems need something to work on before they can be switched on. */
	boolean usable(Ship3D s, ShipSystem3D sys, Sim sim) {
		switch (sys.kind) {
			case ENERGY_LASH:
				return lashTarget(s, sys, sim) != null;
			case CONSTRUCTION_SWARM: {
				if (s.fragments == null || s.fragments.active() < minFragments) return false;
				for (Ship3D o : sim.ships) if (o.behaviour == Ship3D.Behaviour.CONSTRUCTION && o.carrier == s && !o.destroyed) return false; // one at a time
				return true;
			}
			default:
				return true;
		}
	}

	// ---- Energy Lash -------------------------------------------------------------------------------

	/** The lash's target: the AI's pick or your lock / the ship under the reticle if valid and in reach, else the nearest valid ship. */
	Ship3D lashTarget(Ship3D s, ShipSystem3D sys, Sim sim) {
		float reach = sys.range * sim.tuning.weaponRangeMult;
		Ship3D want = s == sim.player ? (sim.target != null ? sim.target : sim.aimTarget) : lashPick;
		if (want != null && validLash(s, want) && inReach(s, want, reach)) return want;
		if (s != sim.player && lashPick != null) return null; // the AI wanted that one: not yet
		Ship3D best = null;
		float bestD = Float.MAX_VALUE;
		for (Ship3D o : sim.ships) {
			if (o == s || !validLash(s, o) || !inReach(s, o, reach)) continue;
			float d = o.pos.distance(s.pos);
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best;
	}

	private static boolean inReach(Ship3D s, Ship3D o, float reach) {
		return o.pos.distance(s.pos) <= reach + s.radius + o.radius;
	}

	/** Vanilla: any enemy ship (not fighters), or a friendly unit whose idle system a lash fires. */
	public static boolean validLash(Ship3D s, Ship3D o) {
		if (o == null || o.destroyed || o.fighter || o.behaviour != null) return false;
		if (o.owner != s.owner) return true;
		ShipSystem3D os = o.system;
		return os != null && os.lashActivated() && os.state == ShipSystem3D.State.IDLE && (o.flux == null || !o.flux.disabled());
	}

	/** The lash goes out: fires a friendly unit's system, or EMP arcs from every system slot into an enemy. */
	private void lash(Ship3D s, ShipSystem3D sys, Sim sim) {
		Ship3D t = sys.sysTarget;
		Combat c = sim.combat;
		if (t == null || t.destroyed || !sim.ships.contains(t)) return;
		Vec3 main = mainSlot(s, sys);
		if (t.owner == s.owner) {
			Effect arc = new Effect(Effect.Kind.ARC, main, Math.max(18f, 12f * s.scale), 0.4f).color(EMP_FRINGE[0], EMP_FRINGE[1], EMP_FRINGE[2]);
			arc.end.set(t.pos);
			arc.vel.set(t.vel);
			c.effects.add(arc);
			c.sounds.play("energy_lash_fire", s.pos, 1f);
			c.sounds.play("energy_lash_friendly_impact", t.pos, 1f);
			if (t.system != null) t.system.lashed(t);
			sys.cooldown = Math.min(lashMaxCooldown, lashMinCooldown + t.dp * lashCooldownPerDp);
		} else {
			boolean phase = t.phasedNow();
			int n = Math.max(1, sys.slots.size());
			for (int i = 0; i < n; i++) {
				Vec3 from = sys.slots.isEmpty() ? main : sys.slots.get(i).worldPos(s, new Vec3());
				float[] col = phase ? EMP_FRINGE_BRIGHT : EMP_FRINGE;
				c.strikeArc(s, from, t, phase ? 0f : lashDamage, phase ? 0f : lashEmp, col, Math.max(12f, 9f * s.scale), 0.03f * i, i == 0 ? "energy_lash_enemy_impact" : null);
				c.sounds.play("energy_lash_fire_at_enemy", s.pos, 1f);
			}
			if (phase && t.flux != null) { // vanilla: a phased ship's field breaks down into a short overload
				t.flux.disrupt(lashPhaseOverload);
				t.overloadTint = EMP_FRINGE_BRIGHT;
			}
			float cd = lashMinEnemyCooldown + (lashMaxEnemyCooldown - lashMinEnemyCooldown) * RND.nextFloat();
			if (phase) cd *= lashPhaseCooldownMult;
			sys.cooldown = Math.min(lashMaxCooldown, cd);
		}
		lashPick = null;
	}

	private static Vec3 mainSlot(Ship3D s, ShipSystem3D sys) {
		for (WeaponMount m : sys.slots) if ("MEDIUM".equals(m.spec.size)) return m.worldPos(s, new Vec3());
		return sys.slots.isEmpty() ? new Vec3(s.pos) : sys.slots.get(0).worldPos(s, new Vec3());
	}

	/**
	 * How much a lash on this friendly unit helps right now, 0..1 (vanilla's usefulness levels):
	 * Fragment Volley when its target is within twice its fragment weapons' reach, Incursion Mode when
	 * its target is within gun range + 750, Extraction Protocol when its flux (and empty racks) are
	 * high and it's backing off. Closer to the Overseer counts a little more.
	 */
	public static float lashUsefulness(Ship3D overseer, Ship3D unit, Sim sim) {
		ShipSystem3D sys = unit.system;
		if (sys == null || !sys.lashActivated() || sys.state != ShipSystem3D.State.IDLE) return 0f;
		Ship3D target = unit.ai != null ? unit.ai.target : null;
		float flux = unit.flux != null ? unit.flux.level() : 0f;
		float toOverseer = unit.pos.distance(overseer.pos) - unit.radius - overseer.radius;
		float near = toOverseer < 1000f ? (1f - Math.max(0f, toOverseer - 500f) / 500f) * 0.25f : 0f;
		switch (sys.kind) {
			case FRAGMENT_VOLLEY: {
				if (target == null) return 0f;
				float range = Float.MAX_VALUE;
				for (WeaponMount m : unit.weapons) if (Combat.fragmentWeapon(m.spec)) range = Math.min(range, m.spec.range * sim.tuning.weaponRangeMult);
				if (range == Float.MAX_VALUE) return 0f;
				float dist = target.pos.distance(unit.pos) - unit.radius - target.radius;
				return dist < range * 2f * 0.8f && dist < 3000f ? Math.min(1f, 0.5f + Math.min(0.5f, flux) + near) : 0f;
			}
			case INCURSION_MODE: {
				if (target == null) return 0f;
				float range = 0f;
				for (WeaponMount m : unit.weapons) if (m.spec.kind != WeaponSpec3D.Kind.MISSILE) range = Math.max(range, m.spec.range * sim.tuning.weaponRangeMult);
				float dist = target.pos.distance(unit.pos) - unit.radius - target.radius;
				return dist < range + 750f ? Math.min(1f, 0.5f + Math.min(0.5f, flux) + near) : 0f;
			}
			case EXTRACTION_PROTOCOL: {
				float need = 0f, use = 0f;
				for (WeaponMount m : unit.weapons) {
					if (m.spec.maxAmmo <= 0) continue;
					use += 1f;
					if (m.spec.ammoPerSecond > 0f) need += 1f - m.ammo / m.spec.maxAmmo;
				}
				float level = use > 0f ? Math.min(1f, flux + 0.25f * need / use) : flux;
				float threshold = target != null && target.flux != null && target.flux.level() > flux ? 0.9f : 0.7f;
				return level > threshold ? (level - threshold) / (1f - threshold) : 0f;
			}
			default:
				return 0f;
		}
	}

	// ---- Darkened Gaze -----------------------------------------------------------------------------

	/**
	 * Always (vanilla runs its script while idle): the Eye's all-round shield faces backward and stays
	 * up; charging opens a 90-degree gap at the front, and while it's fully on the gaze beams (vanilla
	 * decorative beam weapons) fire through it, straight ahead.
	 */
	private void gaze(Ship3D s, ShipSystem3D sys, Sim sim, float dt) {
		if (s.flux != null && s.flux.hasShield()) {
			s.flux.facingOverride = BACKWARD;
			s.flux.arcOverrideDeg = 360f - gazeOpeningDeg * sys.effect;
			s.sysForceShield = !s.flux.disabled();
		}
		boolean firing = sys.state == ShipSystem3D.State.ACTIVE;
		Vec3 aim = new Vec3();
		for (WeaponMount m : s.gazeBeams) {
			m.worldPos(s, aim);
			aim.addScaled(s.rot.forward(new Vec3()), m.spec.range * sim.tuning.weaponRangeMult);
			sim.combat.stepMount(s, m, aim, firing, dt, sim.tuning);
		}
	}

	private static final Vec3 BACKWARD = new Vec3(0f, 0f, 1f);

	// ---- shared ---------------------------------------------------------------------------------------

	/** Where the ship means the system to go: yours = the aim point, an AI's = its target. */
	private static Vec3 aimPoint(Ship3D s, Sim sim) {
		if (s == sim.player && sim.hasAimPoint) return new Vec3(sim.aimPoint);
		if (s.ai != null && s.ai.target != null && !s.ai.target.destroyed) return new Vec3(s.ai.target.pos);
		return null;
	}
}
