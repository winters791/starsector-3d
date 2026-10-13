package s3d.core;

import java.util.ArrayList;
import java.util.List;

import s3d.math.Quat;
import s3d.math.Vec3;

/** A ship in the 3D simulation. Pure data plus a few helpers; no Starsector types. */
public final class Ship3D {
	public final String id;
	public String name;
	public String hullName;
	/** Variant and role ("Custom Combat Freighter") for your ship panel; null if unknown. */
	public String variantName;
	public int owner;

	public final Vec3 pos = new Vec3();
	public final Vec3 vel = new Vec3();
	/** Smoothed acceleration (world), estimated each frame; used to lead turning targets. */
	public final Vec3 accel = new Vec3();
	final Vec3 prevVel = new Vec3();
	boolean hasPrevVel;
	public final Quat rot = new Quat();
	/** Body-frame angular velocity, rad/s: x = pitch, y = yaw, z = roll. */
	public final Vec3 angVel = new Vec3();

	/** -1..1; negative = reverse. */
	public float throttle;
	public FlightParams params;
	/** Movement stats as first read (no temporary boosts); live re-reads may lower them, never raise them. */
	public FlightParams baseParams;
	/** Mass (vanilla), for collisions. */
	public float mass = 1000f;
	/** Effective radius in the 3D world (= baseRadius * scale). */
	public float radius;
	/** Vanilla collision radius. */
	public float baseRadius;
	/** "frigate", "destroyer", "cruiser", "capital" or "fighter"; picks the per-class scale. */
	public String sizeClass = "frigate";
	/** Uniform 3D size multiplier for the whole ship (mesh, engines, radius). */
	public float scale = 1f;

	public final List<EngineMount> engines = new ArrayList<>();
	/** Render-side data (a ShipModel.Source); the renderer builds {@link #model} from it lazily. */
	public Object modelSource;
	/** Opaque handle owned by the renderer (a ShipModel). */
	public Object model;

	// Cosmetic state, render only
	public float visualBank;
	public float thrustVisual;
	public float strafeVisualX, strafeVisualY;

	// ---- combat ----
	public final List<WeaponMount> weapons = new ArrayList<>();
	public final List<WeaponGroup3D> groups = new ArrayList<>();
	/** Index into groups fired by the trigger (vanilla's selected group). */
	public int selectedGroup;
	public float hull = 1f, hullMax = 1f;
	public ArmorShell armor;
	/** Solid shape for hit tests (unscaled local frame); null = can't be hit. */
	public HullVolume volume;
	public boolean destroyed;
	public float armorTakenMult = 1f, hullTakenMult = 1f;
	/** Brief glow after being hit (render only). */
	public float hitFlash;
	/** Sandbox-only scripted motion (dumb target); null for piloted ships. */
	public ScriptedMover mover;
	/** AI pilot; null for the player's ship and for sandbox targets. */
	public ShipAI3D ai;

	// ---- fighters ----
	/** A fighter from a carrier's wing (small, flown by the fighter behaviour of ShipAI3D). */
	public boolean fighter;
	/** Carrier it launched from (null = orphaned). */
	public Ship3D carrier;
	/** Vanilla wing role: FIGHTER, INTERCEPTOR, BOMBER, ASSAULT or SUPPORT. */
	public String wingRole = "FIGHTER";
	/** How far from its carrier the wing will go (3D units), and the bombers' release distance. */
	public float wingRange = 4000f, attackRunRange = 600f;
	/** Set by the fighter AI when it's back at its bay: the host lands it (vanilla refits and relaunches). */
	public boolean landed;
	/** Carrier: its wings regroup around it instead of attacking (vanilla's fighter toggle). */
	public boolean wingsRegroup;
	/** Carrier: where its fighters land (unscaled local; first launch bay, else the hull top centre). */
	public final Vec3 landingLocal = new Vec3();

	/** This carrier's landing spot relative to its centre, scaled but not rotated. */
	public Vec3 bayLocalFor(Vec3 out) {
		return out.set(landingLocal).scale(scale);
	}
	/** Flux, shield, vent and overload; null = no flux limits (test ships). */
	public ShieldFlux flux;

	// ---- looks only (render) ----
	/** Extra thrusters that light only while a movement system runs (vanilla's system-activated engines). */
	public final java.util.List<EngineMount> systemEngines = new java.util.ArrayList<>();
	/** Lidar dishes (vanilla decorative weapons): fire their ranging beams while a Lidar Array winds up. Never fire for real. */
	public final java.util.List<WeaponMount> lidarDishes = new java.util.ArrayList<>();
	/** Vent clouds: core and fringe colour (the hull style's). */
	public float[] ventCore = {1f, 1f, 1f}, ventFringe = {0.49f, 0f, 0.61f};
	/** Overload arcs (the hull style's). */
	public float[] overloadColor = {0.75f, 0.6f, 1f};
	/** Engine health (EMP), per entry in engines; down = seconds until repaired. Null until initComponents. */
	public float[] engineHp, engineDown;
	public float engineMaxHp = 100f;
	/** Seconds a disabled weapon / engine takes to come back (hull stats included), and EMP taken multiplier. */
	public float weaponRepairTime = 10f, engineRepairTime = 8f, empTakenMult = 1f;
	/** Repair speed of disabled weapons and engines (slowed by Reality Disruptor / Inimical Emanation arcs). */
	public float repairRateMult = 1f;
	/** Threat fragment swarm (fragment weapons need it), or null. */
	public FragmentSwarm fragments;
	/** Dweller (shrouded) ships: how much of a Hungering Rift heal they take (0 = can't be healed that way). */
	public float riftHealMult;
	// ---- ship system ----
	/** The hull's system (F) and its phase cloak (right click, instead of a shield), or null. */
	public ShipSystem3D system, cloak;
	/** What running systems do this frame (reset every frame, then set by the systems). */
	public float sysSpeedMult = 1f, sysSpeedFlat, sysAccelMult = 1f, sysTurnMult = 1f, sysTurnAccelMult = 1f;
	public float sysBallisticRofMult = 1f, sysBallisticFluxMult = 1f, sysEnergyDamageMult = 1f;
	public float sysDamageTakenMult = 1f, sysShieldDamageTakenMult = 1f;
	public boolean sysNoFire, sysNoShield, sysNoDissipation, sysFullThrottle, sysForceShield;
	/** Damage multiplier on all of this ship's weapons this frame (a carrier's Targeting Feed on its fighters). */
	public float sysDamageMult = 1f;
	/**
	 * Lidar Array this frame: 1 = winding up (only missiles may fire), 2 = firing (only hardpoint
	 * ballistic/energy guns, not beams, plus missiles), with these range / rate of fire / shot speed bonuses.
	 */
	public int sysLidar;
	public float sysLidarRangeMult = 1f, sysLidarRofMult = 1f, sysLidarSpeedMult = 1f;
	/** A drone flying as a Termination Sequence missile: carried along by it instead of flying itself. */
	public Missile3D carriedBy;
	/** Time dilation (temporal shell, phase): this ship's own flight and weapons run this much faster. */
	public float timeMult = 1f;
	/** Phase cloak: phased = intangible and can't fire; phaseLevel 0..1 for the look. */
	public boolean phased;
	public float phaseLevel;

	public void resetSystemEffects() {
		sysSpeedMult = sysAccelMult = sysTurnMult = sysTurnAccelMult = 1f;
		sysSpeedFlat = 0f;
		sysBallisticRofMult = sysBallisticFluxMult = sysEnergyDamageMult = 1f;
		sysDamageTakenMult = sysShieldDamageTakenMult = 1f;
		sysNoFire = sysNoShield = sysNoDissipation = sysFullThrottle = sysForceShield = false;
		sysDamageMult = 1f;
		sysLidar = 0;
		sysLidarRangeMult = sysLidarRofMult = sysLidarSpeedMult = 1f;
		timeMult = 1f;
		phased = false;
		phaseLevel = 0f;
	}

	/** Extra top speed this frame (vanilla zero-flux boost). */
	public float speedBonus;
	/** Sim time this ship last damaged the player (for HUD threat markers). */
	public float lastHitPlayerAt = -100f;
	/** Ship whose weapon last damaged this one (kill credit). */
	public Ship3D lastHitBy;
	/** Ordered to leave the battle: AI flies to its own edge; the host removes it once there. */
	public boolean retreatOrdered;
	/** Standing order from vanilla's command map (escort, hold, capture, attack, avoid); null = fight freely. */
	public Order3D order;
	/**
	 * AI: this ship's guns (missiles left out) by vanilla range, longest first, and the dps of all the guns
	 * reaching at least that far. Built on first use by ShipAI3D.gunsReaching (other ships' pilots ask it
	 * how much of this ship's firepower reaches a point).
	 */
	public float[] gunRanges, gunDpsReaching;

	// ---- stations and modules ----
	/**
	 * Module: rigidly attached to this ship (a station's sections, a ship's armour modules), placed
	 * on it every frame; it doesn't fly but aims and fires on its own. Null = a free ship.
	 */
	public Ship3D parent;
	/** Module placement in the parent's unscaled local frame, and its rotation relative to the parent. */
	public final Vec3 attachLocal = new Vec3();
	public final Quat attachRot = new Quat();
	/** Station core: never moves; turns about the world vertical at spinRate (rad/s, vanilla Axial Rotation). */
	public boolean anchored;
	public float spinRate;
	/** Takes no damage (vanilla Vast Bulk, a station's core); shots and ships still stop on it. */
	public boolean invulnerable;
	/** Has fighter launch bays (a carrier, or a station's hangar module). */
	public boolean hasBays;

	/**
	 * A section with no guns and no bays (station structural spurs, armour, struts; a ship's armour
	 * modules): nobody picks it as a target, it only soaks up the shots that hit it. Vanilla ends a
	 * station once its armed sections are gone (user, 2026-10-13: ships kept grinding through spurs).
	 */
	public boolean unarmedPart() {
		return parent != null && weapons.isEmpty() && !hasBays;
	}

	/** The free ship or station this part belongs to (itself if it isn't a module). */
	public Ship3D root() {
		Ship3D r = this;
		while (r.parent != null) r = r.parent;
		return r;
	}

	/** Part of a structure that doesn't fly by itself: a module, or an anchored station core. */
	public boolean fixedPart() {
		return parent != null || anchored;
	}

	/**
	 * How far the hull really reaches from its centre toward world direction dir (unit), scaled:
	 * its solid shape, not vanilla's collision circle (a station core's circle can be much bigger).
	 * Radius if it has no shape.
	 */
	public float reachToward(Vec3 dir) {
		if (volume == null) return radius;
		Vec3 l = rot.inverseRotate(dir, new Vec3());
		float flat = (float) Math.hypot(l.x, l.z);
		float across = volume.reachToward(l.x, l.z) * scale;
		float up = Math.max(1f, Math.max(volume.maxY, -volume.minY) * scale);
		if (across <= 0f) return up;
		float k = (flat / across) * (flat / across) + (l.y / up) * (l.y / up); // ellipse through both
		return k > 1e-8f ? (float) (1.0 / Math.sqrt(k)) : across;
	}

	/** Furthest the hull reaches from its centre (3D units), from its solid shape; radius if it has none. */
	public float extent() {
		return volume != null ? volume.extent() * scale : radius;
	}

	/**
	 * Distance from point p to the near side of this hull: centre distance less how far the hull
	 * reaches toward p.
	 */
	public float surfaceDistance(Vec3 p) {
		Vec3 d = new Vec3(p).sub(pos);
		float len = d.length();
		if (len < 1e-3f) return 0f;
		return len - reachToward(d.scale(1f / len));
	}

	public void resetDamage() {
		hull = hullMax;
		if (armor != null) armor.repair();
		destroyed = false;
		hitFlash = 0f;
		angVel.set(0f, 0f, 0f);
		if (flux != null) flux.reset();
		initComponents(engineMaxHp);
		if (system != null) system.reset();
		if (cloak != null) cloak.reset();
		resetSystemEffects();
		repairRateMult = 1f;
		if (fragments != null) fragments.count = fragments.size;
	}

	/** Back-reference for the host (e.g. the vanilla puppet ShipAPI). Never read by core code. */
	public Object hostRef;

	public Ship3D(String id) {
		this.id = id;
	}

	public void initComponents(float engineMax) {
		engineMaxHp = engineMax;
		engineHp = new float[engines.size()];
		engineDown = new float[engines.size()];
		java.util.Arrays.fill(engineHp, engineMax);
	}

	/** 1 with every engine working, 0 = flamed out. */
	public float engineThrust() {
		if (engineDown == null || engineDown.length == 0) return 1f;
		int up = 0;
		for (float d : engineDown) if (d <= 0f) up++;
		return up / (float) engineDown.length;
	}

	/** Counts down disabled weapons and engines; repaired ones come back at full health. */
	public void stepRepairs(float dt) {
		dt *= repairRateMult;
		for (WeaponMount m : weapons) {
			if (m.disabledFor <= 0f) continue;
			m.disabledFor -= dt;
			if (m.disabledFor <= 0f) {
				m.disabledFor = 0f;
				m.hp = m.maxHp;
			}
		}
		if (engineDown == null) return;
		for (int i = 0; i < engineDown.length; i++) {
			if (engineDown[i] <= 0f) continue;
			engineDown[i] -= dt;
			if (engineDown[i] <= 0f) {
				engineDown[i] = 0f;
				engineHp[i] = engineMaxHp;
			}
		}
	}

	public void applyScale(float s) {
		scale = s;
		radius = baseRadius * s;
	}

	/** Updates the smoothed acceleration estimate from this frame's velocity change. */
	public void trackAcceleration(float dt) {
		if (dt <= 0f) return;
		if (hasPrevVel) {
			float k = 1f - (float) Math.exp(-dt / 0.2f);
			accel.x += ((vel.x - prevVel.x) / dt - accel.x) * k;
			accel.y += ((vel.y - prevVel.y) / dt - accel.y) * k;
			accel.z += ((vel.z - prevVel.z) / dt - accel.z) * k;
		}
		prevVel.set(vel);
		hasPrevVel = true;
	}

	public float speed() {
		return vel.length();
	}

	/** Signed speed along the nose. */
	public float forwardSpeed() {
		return vel.dot(rot.forward(new Vec3()));
	}
}
