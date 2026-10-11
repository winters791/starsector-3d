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
		BURN_DRIVE, MANEUVERING_JETS, PLASMA_JETS, MICROBURN, DISPLACER,
		AMMO_FEED, HIGH_ENERGY_FOCUS, FAST_MISSILE_RACKS,
		FORTRESS_SHIELD, DAMPER, TEMPORAL_SHELL, PHASE_CLOAK,
		EMP_EMITTER, FLARES, RECALL, UNSUPPORTED
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
	/** Vanilla sound ids for using, running and switching off the system (null = none). */
	public String useSound, loopSound, offSound;
	/** EMP emitter: range, damage and EMP per arc. */
	public float range = 1000f, damage, empDamage;

	// ---- state ----
	public State state = State.IDLE;
	public float stateTime, effect, ammo;
	private float pulseTimer;
	private boolean fired;

	public ShipSystem3D(String id, String name, Kind kind) {
		this.id = id;
		this.name = name;
		this.kind = kind;
	}

	/** Maps a vanilla system id to the 3D kind that rebuilds it (UNSUPPORTED if there's none yet). */
	public static Kind kindFor(String id, boolean phaseCloak) {
		if (phaseCloak) return Kind.PHASE_CLOAK;
		if (id == null) return Kind.UNSUPPORTED;
		switch (id) {
			case "burndrive": return Kind.BURN_DRIVE;
			case "maneuveringjets": return Kind.MANEUVERING_JETS;
			case "plasmajets": return Kind.PLASMA_JETS;
			case "microburn": case "microburn_omega": return Kind.MICROBURN;
			case "displacer": return Kind.DISPLACER;
			case "ammofeed": return Kind.AMMO_FEED;
			case "highenergyfocus": return Kind.HIGH_ENERGY_FOCUS;
			case "fastmissileracks": return Kind.FAST_MISSILE_RACKS;
			case "fortressshield": return Kind.FORTRESS_SHIELD;
			case "damper": case "damper_omega": return Kind.DAMPER;
			case "temporalshell": return Kind.TEMPORAL_SHELL;
			case "phasecloak": return Kind.PHASE_CLOAK;
			case "emp": return Kind.EMP_EMITTER;
			case "flarelauncher": case "flarelauncher_active": case "flarelauncher_fighter": return Kind.FLARES;
			case "recalldevice": return Kind.RECALL;
			default: return Kind.UNSUPPORTED;
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
		return s.flux == null || s.flux.maxFlux <= 0f || s.flux.flux + fluxPerUse <= s.flux.maxFlux;
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
		if (maxAmmo > 0) ammo -= 1f;
		state = State.IN;
		stateTime = 0f;
		fired = false;
		pulseTimer = 0f;
		justUsed = true;
		return true;
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
		if (dt <= 0f || !supported()) return;
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
		Tuning t = sim.tuning;
		switch (kind) {
			case DISPLACER: {
				// Short blink along the way the ship is going (its nose if it's nearly still).
				Vec3 dir = s.vel.lengthSq() > 100f ? new Vec3(s.vel).normalize() : s.rot.forward(new Vec3());
				Vec3 from = new Vec3(s.pos);
				s.pos.addScaled(dir, t.sysDisplacerDistance * Math.max(0.5f, s.scale));
				sim.combat.effects.add(new Effect(Effect.Kind.FLASH, from, s.radius * 1.5f, 0.4f).color(0.6f, 0.4f, 1f));
				sim.combat.effects.add(new Effect(Effect.Kind.FLASH, s.pos, s.radius * 1.5f, 0.4f).color(0.6f, 0.4f, 1f));
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
			default:
		}
	}

	/** Continuous effects, scaled by the effect level. */
	private void apply(Ship3D s, Sim sim, float dt) {
		Tuning t = sim.tuning;
		float e = effect;
		if (!firingAllowed) s.sysNoFire = true;
		if (!turningAllowed) s.sysTurnMult *= 1f - e;
		if (!shieldAllowed) s.sysNoShield = true;
		if (!dissipationAllowed) s.sysNoDissipation = true;
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
			default:
		}
	}

	public void reset() {
		state = State.IDLE;
		stateTime = effect = 0f;
		ammo = maxAmmo;
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
