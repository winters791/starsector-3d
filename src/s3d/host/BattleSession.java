package s3d.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.combat.ReserveWingStats;
import com.fs.starfarer.api.impl.combat.dweller.ShroudedEjectaShipCreator;
import com.fs.starfarer.api.impl.combat.dweller.ShroudedVortexShipCreator;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;

import org.json.JSONArray;
import org.json.JSONObject;

import s3d.core.ArmorShell;
import s3d.core.CommandView;
import s3d.core.Objective3D;
import s3d.core.Order3D;
import s3d.core.Ship3D;
import s3d.core.ShipAI3D;
import s3d.core.WeaponMount;
import s3d.math.Vec3;

/**
 * Milestone 4: a real campaign battle played in 3D.
 *
 * Vanilla runs the battle as usual (deployment, command points, reinforcements, the enemy
 * admiral, CR timers, the end-of-battle result); its ships are puppets that never move or shoot on
 * their own. Every deployed ship gets a 3D twin: yours is flown by you, everyone else by
 * {@link ShipAI3D}. Each frame the puppets are moved to their twin's position (mapped back onto the
 * 2D map, so vanilla's command map and admiral see the real situation), and 3D damage is written
 * onto them: hull every frame, armor by direction twice a second, kills as vanilla kills, and
 * ships that retreat in 3D leave the vanilla map through their own edge. Vanilla then produces the
 * campaign outcome (losses, damage, CR, salvage) from the puppets.
 *
 * Fighters: vanilla still launches wings from the carrier puppets, replaces losses at the
 * replacement rate and refits landed fighters. Each launched fighter gets a 3D twin leaving from
 * its bay on the carrier's 3D hull, flown by ShipAI3D's fighter behaviour; a 3D kill kills the
 * puppet (vanilla counts the loss), a 3D landing calls the bay's land() (vanilla refits and
 * relaunches, which mirrors a fresh twin). Your wings attack or regroup with the wings key (Z).
 *
 * Stations and ships with modules: the core (a station's hub, or a ship like the Onslaught Mk.I)
 * gets a twin first, then each module a twin attached to it where vanilla has it (offset and
 * angle read from the live puppets), which fights as a turret. A station's core stays where vanilla
 * put it, turns slowly (Axial Rotation) and takes no damage (Vast Bulk); its modules take the
 * damage and are killed the vanilla way, and vanilla decides when the station is beaten. Module
 * puppets aren't moved: vanilla keeps them on their parent.
 *
 * Orders and objectives: twice a second each AI ship's vanilla assignment (Tab command map, or the
 * enemy admiral's) becomes its 3D order: escort, defend and rally, capture/control/assault,
 * engage/intercept/harass/strike, avoid; anything else means fighting freely. Objectives (nav buoys,
 * sensor arrays, comm relays) are mirrored where vanilla has them; ships take them by sitting on
 * them, which puts their puppets on them for vanilla's own capture (with a fallback if vanilla
 * doesn't flip one). Their bonuses reach 3D through the puppets: weapon ranges (sensor arrays' ECM)
 * are re-read twice a second, top speed may rise by a nav bonus.
 *
 * Orders from the 3D tactical view (T, {@link s3d.core.CommandView}) are written into the same
 * vanilla task manager as the Tab map's, command points and all, so they come back the same way.
 * Vanilla's waypoints are flat; the height you gave a point is kept here and put back when the
 * order is read.
 */
final class BattleSession extends Session3D {
	private static final Logger log = Global.getLogger(BattleSession.class);

	/** Vanilla ship -> its 3D twin. */
	private final Map<ShipAPI, Ship3D> twins = new IdentityHashMap<>();
	/** Every vanilla ship we've taken control of, with its original collision class (for hand-back). */
	private final Map<ShipAPI, CollisionClass> frozen = new IdentityHashMap<>();
	/** Frozen but not in 3D (fighters, escape pods): parked here. */
	private final Map<ShipAPI, Vector2f> parked = new IdentityHashMap<>();
	/** Puppets the 3D side asked for (spawned wings and ships), with how their twins come in. */
	private final Map<ShipAPI, s3d.core.SpawnRequest> spawned = new IdentityHashMap<>();
	/** Twins whose swarm / shroud look has been read from vanilla's live effect. */
	private final Map<String, Integer> spawnLogged = new java.util.HashMap<>();
	private final java.util.Set<Ship3D> swarmLooked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	/** Puppets already killed or retreated: no longer moved or written to. */
	private final Set<ShipAPI> finished = new HashSet<>();
	/** Retreated puppets waiting for vanilla to count them; time sent. */
	private final Map<ShipAPI, Float> retreatPending = new IdentityHashMap<>();
	/** Killed puppets waiting for vanilla to register the kill; time killed. */
	private final Map<ShipAPI, Float> killPending = new IdentityHashMap<>();
	/** Invulnerable station cores whose sections are all gone; time the last one died. */
	private final Map<ShipAPI, Float> strippedSince = new IdentityHashMap<>();
	/** Vanilla objective -> its 3D view; objectives one side holds alone: {side, since}. */
	private final Map<BattleObjectiveAPI, Objective3D> objectives = new IdentityHashMap<>();
	private final Map<Objective3D, float[]> holding = new IdentityHashMap<>();
	private boolean ordersFailed, objectivesFailed;
	/** Twins made this frame, placed once their whole structure is in: stations, then free ships. */
	private final List<Ship3D> newStations = new ArrayList<>(), newShips = new ArrayList<>();

	/** 3D units per vanilla map unit: across (x) and along the line between the fleets (z, battle.lengthMult times longer). */
	private final float mapW, mapH, scale, scaleZ, edgeZ;
	private int deployed, graves, parkedCount, launches, landings, fighterLosses, fightersDismissed;
	private float fighterLogTimer = 10f, manoeuvreLogTimer = 20f;
	private boolean handedBack, handBackArmed;
	private float clock, armorTimer, ordersTimer, handBackTimer;
	/**
	 * The exact 3D points you ordered ships to, by the vanilla waypoint's map spot (see heightKey):
	 * vanilla's waypoints are flat and kept within +/-6000 map units of the centre (seen in play), so
	 * the height and the real spot are put back when the order is read.
	 */
	private final Map<Long, Vec3> waypointPoints = new java.util.HashMap<>();
	private boolean commandsFailed;

	/** Null if this battle can be played in 3D, else why not. */
	static String unsupported(CombatEngineAPI engine, s3d.core.Tuning t) {
		for (ShipAPI s : engine.getShips()) {
			s3d.core.ModData.HullRow row = ModDataLoader.hullRow(s.getHullSpec());
			if (row != null && row.vanilla2d) return s.getHullSpec().getHullId() + " (" + row.mod + "'s hulls.csv keeps its battles 2D)";
		}
		if (t.battleStations) return null;
		for (ShipAPI s : engine.getShips()) {
			if (s.isStation() || s.isStationModule()) return "station battle (battle.stations is off)";
			if (s.getChildModulesCopy() != null && !s.getChildModulesCopy().isEmpty()) return "multi-module ship " + s.getHullSpec().getHullId() + " (battle.stations is off)";
		}
		return null;
	}

	/** Deployment is done and at least one of your ships is on the field. */
	static boolean ready(CombatEngineAPI engine) {
		CombatUIAPI ui = engine.getCombatUI();
		if (ui != null && ui.isShowingDeploymentDialog()) return false;
		if (engine.isUIShowingDialog()) return false;
		for (ShipAPI s : engine.getShips()) if (s.getOwner() == 0 && mirrorable(s) && !s.isStation() && !isModule(s)) return true;
		return false;
	}

	private static boolean mirrorable(ShipAPI s) {
		return (s.getOwner() == 0 || s.getOwner() == 1) && !s.isFighter() && !s.isShuttlePod() && !s.isHulk() && s.isAlive()
				&& s.getHullSpec() != null;
	}

	/** A station section or a ship's module (attached to a parent ship). */
	private static boolean isModule(ShipAPI s) {
		return s.getParentStation() != null && s.getParentStation() != s;
	}

	BattleSession(CombatEngineAPI engine, TuningLoader cfg) {
		super(engine, cfg);
		mapW = engine.getMapWidth();
		mapH = engine.getMapHeight();
		scale = cfg.tuning.battleMapScale;
		scaleZ = scale * cfg.tuning.battleLengthMult;
		edgeZ = mapH * 0.5f * scaleZ;
		sim.arenaHalfX = mapW * 0.5f * scale;
		sim.arenaHalfZ = edgeZ;
		BuildPicker.clear();
		managePuppets(0f);
		syncObjectives();

		ShipAPI flag = engine.getPlayerShip();
		Ship3D player = flag != null ? twins.get(flag) : null;
		if (player == null || player.owner != 0) player = pickNextPlayer(null);
		if (player != null) {
			takeControl(player, false);
			sim.camera.introTo(player, 2f); // swoop in from far behind as the 3D view fades in
		}
		log.info(String.format("S3D: campaign battle in 3D: map %.0f x %.0f, 3D edges at z = +/-%.0f, %d ships mirrored, you fly %s; tuning #%s",
				mapW, mapH, edgeZ, twins.size(), player != null ? player.name : "nothing", cfg.hash));
	}

	// ---- puppets -----------------------------------------------------------------------------

	@Override
	protected void managePuppets(float realDt) {
		if (handedBack) return;
		clock += realDt;
		Set<ShipAPI> present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		present.addAll(engine.getShips());
		// Ships first, then modules (their parent's twin must exist), then fighters (so a wing
		// launched this frame finds its carrier's twin, which may be a station's hangar module).
		spawnRequested();
		for (int pass = 0; pass < 3; pass++) {
			for (ShipAPI ship : engine.getShips()) {
				int kind = ship.isFighter() || ship.isDrone() ? 2 : isModule(ship) ? 1 : 0;
				if (kind != pass) continue;
				if (frozen.containsKey(ship) || finished.contains(ship)) continue;
				if (ship.isHulk() || !ship.isAlive()) continue; // wrecks that predate us: leave them be
				if (pass == 1 && !twins.containsKey(ship.getParentStation()) && !frozen.containsKey(ship.getParentStation())) continue; // parent first
				if (pass == 2 && (ship.isDrone() ? dronePending(ship) : carrierPending(ship))) continue; // its carrier isn't in 3D yet: try next frame
				freeze(ship);
				s3d.core.SpawnRequest req = spawned.remove(ship);
				if (req != null) {
					if (!mirrorSpawned(ship, req)) parked.put(ship, parkingSpot(ship));
				} else if (pass == 1) {
					if (!mirrorModule(ship)) parked.put(ship, null); // left where vanilla has it
				} else if (ship.isDrone()) {
					if (!mirrorDrone(ship)) parked.put(ship, parkingSpot(ship));
				} else if (mirrorable(ship)) mirror(ship);
				else if (!ship.isFighter() || !mirrorFighter(ship)) parked.put(ship, parkingSpot(ship));
			}
		}
		settleNewcomers();
		for (Map.Entry<ShipAPI, Ship3D> e : new ArrayList<>(twins.entrySet())) {
			ShipAPI ship = e.getKey();
			if (finished.contains(ship)) continue;
			if (!present.contains(ship)) {
				// Vanilla took it out on its own (it shouldn't); drop the twin so the two sides agree.
				if (e.getValue().fighter) log.info("S3D: " + e.getValue().name + " (fighter/drone) left the vanilla battle; removing its 3D twin");
				else log.warn("S3D: " + e.getValue().name + " left the vanilla battle by itself; removing its 3D twin");
				finished.add(ship);
				if (!e.getValue().destroyed && !e.getValue().fighter) sim.combat.jumpOut(e.getValue()); // usually a retreat vanilla finished early
				removeTwin(e.getValue());
				continue;
			}
			syncPuppet(ship, e.getValue());
		}
		for (Map.Entry<ShipAPI, Vector2f> e : parked.entrySet()) {
			ShipAPI ship = e.getKey();
			if (e.getValue() == null) continue; // a module without a twin: stays on its parent
			ship.getLocation().set(e.getValue());
			ship.getVelocity().set(0f, 0f);
			ship.setAngularVelocity(0f);
		}
		checkPending();
		if (overlay) {
			// Point the hidden 2D camera off the map so vanilla draws (and plays) as little as possible.
			engine.getViewport().set(mapW, mapH, 1000f, 1000f);
		}
	}

	private void freeze(ShipAPI ship) {
		frozen.put(ship, ship.getCollisionClass());
		ship.setCollisionClass(CollisionClass.NONE);
		ship.setShipAI(new InertShipAI());
		ship.setHoldFire(true);
		// A puppet's Burn Drive etc. would leak into its stats. Drone launchers stay on: vanilla launches the drones, 3D flies them.
		ship.setShipSystemDisabled(!(ship.getSystem() instanceof DroneLauncherShipSystemAPI));
	}

	/** New vanilla ship on the field: make its 3D twin at its own side's entry line. */
	private void mirror(ShipAPI ship) {
		// Stations, and hulls with modules that can't move (the small orbital station), stay where vanilla put them.
		boolean immobile = ship.isShipWithModules() && ship.getMutableStats().getMaxSpeed().getModifiedValue() < 1f;
		if (ship.isStation() || immobile) {
			mirrorStation(ship);
			return;
		}
		try {
			int n = deployed++;
			Ship3D s = ShipBridge.toShip3D(ship, "b" + n, cfg.tuning);
			s.hull = Math.min(s.hullMax, ship.getHitpoints());
			sim.addShip(s); // applies the class scale; the placement below needs the real radius
			float side = s.owner == 0 ? 1f : -1f;
			Vector2f loc = ship.getLocation();
			float halfW = mapW * 0.5f * scale * 0.9f;
			float x = Math.max(-halfW, Math.min(halfW, loc.x * scale * cfg.tuning.battleDeploySpread));
			float y = (((n * 37) % 5) - 2) * 0.5f * cfg.tuning.battleDeployAltitude;
			float z = side * Math.max(edgeZ * 0.5f, edgeZ - cfg.tuning.battleEdgeMargin - s.radius); // from its own edge
			s.pos.set(x, y, z);
			for (int tries = 0; tries < 12 && overlapsAny(s); tries++) {
				s.pos.x += (tries % 2 == 0 ? 1f : -1f) * (tries + 1) * s.radius * 2.2f;
			}
			if (side < 0f) s.rot.setAxisAngle(0f, 1f, 0f, (float) Math.PI);
			newShips.add(s);
			s.ai = new ShipAI3D(n);
			s.ai.homeDir.set(0f, 0f, side);
			s.ai.profile(s, cfg.tuning);
			s.throttle = 0.6f;
			s.rot.forward(s.vel).scale(s.params.fwdSpeed * 0.5f);
			twins.put(ship, s);
			log.info(String.format("S3D: %s entered the 3D battle at (%.0f, %.0f, %.0f), hull %.0f/%.0f, AI fights %s at %.0f (weapon range %.0f)",
					s.name, s.pos.x, s.pos.y, s.pos.z, s.hull, s.hullMax, s.ai.axis, s.ai.engageRange, s.ai.weaponRange));
		} catch (Throwable t) {
			log.error("S3D: could not mirror " + ship.getName() + " (" + ship.getHullSpec().getHullId() + "); it stays a parked puppet", t);
			parked.put(ship, parkingSpot(ship));
		}
	}

	/**
	 * A station's core: placed where vanilla has it (mapped like the command map), facing the same
	 * way, anchored; turns if it has Axial Rotation, invulnerable if it has Vast Bulk. Its weapons, if
	 * any, fight like a turret's.
	 */
	private void mirrorStation(ShipAPI ship) {
		try {
			int n = deployed++;
			Ship3D s = ShipBridge.toShip3D(ship, "b" + n, cfg.tuning);
			s.hull = Math.min(s.hullMax, ship.getHitpoints());
			s.anchored = true;
			s.invulnerable = ship.getVariant() != null && ship.getVariant().hasHullMod("vastbulk");
			boolean spins = ship.getVariant() != null && ship.getVariant().hasHullMod("axialrotation");
			s.spinRate = spins ? (float) Math.toRadians(cfg.tuning.stationSpinDeg) : 0f;
			sim.addShip(s);
			Vector2f loc = ship.getLocation();
			s.pos.set(Math.max(-sim.arenaHalfX, Math.min(sim.arenaHalfX, loc.x * scale)), 0f, Math.max(-edgeZ, Math.min(edgeZ, -loc.y * scaleZ)));
			s.rot.setAxisAngle(0f, 1f, 0f, (float) Math.toRadians(ship.getFacing() - 90f)); // vanilla facing -> 3D nose
			s.vel.set(0f, 0f, 0f);
			s.ai = new ShipAI3D(n);
			s.ai.homeDir.set(0f, 0f, s.owner == 0 ? 1f : -1f);
			twins.put(ship, s);
			newStations.add(s);
			log.info(String.format("S3D: station %s (%s) in 3D at (%.0f, %.0f, %.0f), radius %.0f, %s, %s, %d weapons on the core",
					s.name, s.hullName, s.pos.x, s.pos.y, s.pos.z, s.radius, s.invulnerable ? "core invulnerable (Vast Bulk)" : "core can be damaged",
					spins ? "turning " + cfg.tuning.stationSpinDeg + " deg/s" : "not turning", s.weapons.size()));
		} catch (Throwable t) {
			log.error("S3D: could not mirror station " + ship.getName() + " (" + ship.getHullSpec().getHullId() + "); it stays a puppet", t);
			parked.put(ship, null);
		}
	}

	/**
	 * A station section or a ship's module: a twin attached to its parent's twin at the offset and
	 * angle vanilla has it at right now (both puppets still where vanilla laid them out). False if
	 * the parent has no twin or something failed.
	 */
	private boolean mirrorModule(ShipAPI ship) {
		ShipAPI parentShip = ship.getParentStation();
		Ship3D parent = twins.get(parentShip);
		if (parent == null) return false;
		try {
			int n = deployed++;
			Ship3D m = ShipBridge.toShip3D(ship, "m" + n, cfg.tuning);
			m.hull = Math.min(m.hullMax, ship.getHitpoints());
			m.parent = parent;
			// Offset in the parent's ship space (x forward, y left), then the 3D local frame (x starboard, -z nose).
			Vector2f d = Vector2f.sub(ship.getLocation(), parentShip.getLocation(), null);
			double f = Math.toRadians(parentShip.getFacing());
			float fwd = (float) (d.x * Math.cos(f) + d.y * Math.sin(f));
			float left = (float) (-d.x * Math.sin(f) + d.y * Math.cos(f));
			m.attachLocal.set(-left, 0f, -fwd);
			m.attachRot.setAxisAngle(0f, 1f, 0f, (float) Math.toRadians(ship.getFacing() - parentShip.getFacing()));
			sim.addShip(m); // takes the parent's scale and position
			m.ai = new ShipAI3D(n);
			m.ai.homeDir.set(parent.ai != null ? parent.ai.homeDir : new Vec3(0f, 0f, m.owner == 0 ? 1f : -1f));
			twins.put(ship, m);
			log.info(String.format("S3D: module %s on %s at local (%.0f, %.0f) angle %.0f, hull %.0f/%.0f, %d weapons",
					m.hullName, parent.name, -left, -fwd, ship.getFacing() - parentShip.getFacing(), m.hull, m.hullMax, m.weapons.size()));
			return true;
		} catch (Throwable t) {
			log.error("S3D: could not mirror module " + ship.getHullSpec().getHullId() + " of " + parentShip.getName() + "; it stays a puppet", t);
			return false;
		}
	}

	/**
	 * Once this frame's twins are all in (a station's sections included): a station that would poke
	 * out of the battle area is moved in until all of it fits (at station scale it can be thousands
	 * of units across; the command map keeps showing it where vanilla has it), and a ship that
	 * arrived inside or against a station is moved out beside it.
	 */
	private void settleNewcomers() {
		for (Ship3D core : newStations) {
			if (core.destroyed || !sim.ships.contains(core)) continue;
			float ext = sim.structureExtent(core);
			float hx = sim.arenaHalfX * 0.8f - ext, hz = sim.arenaHalfZ * 0.8f - ext;
			float x = hx > 0f ? Math.max(-hx, Math.min(hx, core.pos.x)) : 0f;
			float z = hz > 0f ? Math.max(-hz, Math.min(hz, core.pos.z)) : 0f;
			if (Math.abs(x - core.pos.x) + Math.abs(z - core.pos.z) > 1f) {
				log.info(String.format("S3D: station %s reaches %.0f from its centre; moved from (%.0f, %.0f) to (%.0f, %.0f) so all of it is inside the battle area",
						core.name, ext, core.pos.x, core.pos.z, x, z));
				core.pos.x = x;
				core.pos.z = z;
				for (Ship3D m : sim.ships) if (m.parent != null && m.root() == core) s3d.core.Sim.attach(m);
			}
		}
		newStations.clear();
		for (Ship3D s : newShips) {
			if (s.destroyed || !sim.ships.contains(s)) continue;
			for (Ship3D o : sim.ships) {
				if (!o.anchored || o.destroyed) continue;
				float need = sim.structureExtent(o) + s.radius * 2f + 200f;
				float dx = s.pos.x - o.pos.x, dz = s.pos.z - o.pos.z, d = (float) Math.hypot(dx, dz);
				if (d >= need) continue;
				if (d < 1f) {
					dx = 0f;
					dz = s.owner == 0 ? 1f : -1f;
					d = 1f;
				}
				s.pos.x = o.pos.x + dx / d * need;
				s.pos.z = o.pos.z + dz / d * need;
				log.info(String.format("S3D: %s arrived inside station %s; moved out to (%.0f, %.0f, %.0f)", s.name, o.name, s.pos.x, s.pos.y, s.pos.z));
			}
		}
		newShips.clear();
	}

	/** A fighter whose carrier is alive but hasn't got its 3D twin yet (it will, this frame or next). */
	private boolean carrierPending(ShipAPI fighter) {
		FighterWingAPI wing = fighter.getWing();
		ShipAPI c = wing != null ? wing.getSourceShip() : null;
		return c != null && c.isAlive() && !c.isHulk() && !twins.containsKey(c) && !frozen.containsKey(c) && mirrorable(c);
	}

	/** A fighter vanilla just launched: its 3D twin leaves from the bay on the carrier's 3D hull. */
	private boolean mirrorFighter(ShipAPI ship) {
		FighterWingAPI wing = ship.getWing();
		Ship3D carrier = wing != null && wing.getSourceShip() != null ? twins.get(wing.getSourceShip()) : null;
		if (carrier == null || carrier.destroyed || !sim.ships.contains(carrier)) return false;
		try {
			int n = deployed++;
			Ship3D f = ShipBridge.toShip3D(ship, "f" + n, cfg.tuning);
			f.fighter = true;
			f.carrier = carrier;
			f.wingRole = wing.getRole() != null ? wing.getRole().name() : "FIGHTER";
			f.wingRange = Math.max(1500f, wing.getRange());
			if (wing.getSpec() != null && wing.getSpec().getAttackRunRange() > 0f) f.attackRunRange = wing.getSpec().getAttackRunRange();
			f.hull = Math.min(f.hullMax, ship.getHitpoints());
			sim.addShip(f);
			Vec3 local = new Vec3(carrier.landingLocal);
			FighterLaunchBayAPI bay = wing.getSource();
			if (bay != null && bay.getWeaponSlot() != null) ShipBridge.launchPoint(carrier, bay.getWeaponSlot(), local);
			carrier.rot.rotate(local.x * carrier.scale, local.y * carrier.scale, local.z * carrier.scale, f.pos).add(carrier.pos);
			f.pos.add(new Vec3((n % 3 - 1) * 8f, (n % 2) * 6f, 0f)); // wingmates don't stack exactly
			f.rot.set(carrier.rot);
			Vec3 fwd = carrier.rot.forward(new Vec3()), up = carrier.rot.up(new Vec3());
			f.vel.set(carrier.vel).addScaled(fwd, f.params.fwdSpeed * 0.6f).addScaled(up, 25f);
			f.throttle = 1f;
			f.ai = new ShipAI3D(n);
			f.ai.homeDir.set(0f, 0f, f.owner == 0 ? 1f : -1f);
			f.ai.profile(f, cfg.tuning);
			twins.put(ship, f);
			launches++;
			return true;
		} catch (Throwable t) {
			log.error("S3D: could not mirror fighter " + ship.getHullSpec().getHullId() + "; it stays a parked puppet", t);
			return false;
		}
	}

	/**
	 * What the 3D side wants put into the battle (attack and construction swarms, ejecta, vortices, a
	 * finished construction): spawned through vanilla's fleet manager at the matching 2D spot, each new
	 * puppet remembered so its twin comes in where and how it was launched.
	 */
	private void spawnRequested() {
		List<s3d.core.SpawnRequest> reqs = sim.combat.spawnRequests;
		if (reqs.isEmpty()) return;
		for (s3d.core.SpawnRequest r : new ArrayList<>(reqs)) {
			ShipAPI src = r.source != null && r.source.hostRef instanceof ShipAPI ? (ShipAPI) r.source.hostRef : null;
			int owner = src != null ? src.getOriginalOwner() : r.source != null ? r.source.owner : 1;
			try {
				if (r.pickBuild && !pickBuild(src, r)) continue;
				CombatFleetManagerAPI fm = engine.getFleetManager(owner);
				Vector2f at = to2D(r.pos, new Vector2f());
				float facing = (float) Math.toDegrees(Math.atan2(-r.dir.z, r.dir.x));
				fm.setSuppressDeploymentMessages(true);
				ShipAPI leader = fm.spawnShipOrWing(r.id, at, facing, 0f, null);
				fm.setSuppressDeploymentMessages(false);
				if (leader == null) continue;
				if (leader.getWing() != null && src != null) leader.getWing().setSourceShip(src);
				List<ShipAPI> members = leader.getWing() != null ? leader.getWing().getWingMembers() : List.of(leader);
				for (ShipAPI m : members) spawned.put(m, r);
				if (r.behaviour == null) log.info("S3D: " + (r.source != null ? r.source.name : "?") + " brought " + r.id + " into the battle");
				else if (spawnLogged.merge(r.id, 1, Integer::sum) <= 3) { // the first few of each kind, to confirm they come out
					log.info(String.format("S3D: %s spawned %s (%s, %d member(s)) at (%.0f, %.0f)", r.source != null ? r.source.name : "?", r.id,
							r.behaviour, members.size(), at.x, at.y));
				}
			} catch (Throwable t) {
				log.warn("S3D: could not spawn " + r.id + " (" + t + ")");
			}
		}
		reqs.clear();
	}

	/** Vanilla's Fabricator picks what its construction swarm builds (by what its side has out); its cost leaves the Fabricator. */
	private boolean pickBuild(ShipAPI fabricator, s3d.core.SpawnRequest r) {
		if (fabricator == null) return false;
		BuildPicker.Pick pick = BuildPicker.pick(fabricator);
		if (pick == null) {
			log.info("S3D: " + fabricator.getName() + "'s construction swarm found nothing to build");
			return false;
		}
		Ship3D fab = r.source;
		if (fab.fragments != null && fab.fragments.active() < pick.fragments) return false;
		if (fab.fragments != null) fab.fragments.count -= pick.fragments;
		fabricator.setCurrentCR(Math.max(0f, fabricator.getCurrentCR() - pick.cr));
		r.buildVariant = pick.variantId;
		r.buildSeconds = pick.buildSeconds;
		r.travelSeconds = pick.travelSeconds;
		r.buildSizeMult = pick.radiusMult;
		log.info(String.format("S3D: %s sends a construction swarm to build %s (%d fragments, %.0f s)", fabricator.getName(), pick.variantId, pick.fragments, pick.buildSeconds));
		return true;
	}

	/**
	 * A puppet the 3D side asked for: its twin comes in at the requested spot (wing members a little
	 * apart), leaving along the requested direction at its share of its own top speed, with its
	 * behaviour (swarms and construction swarms are drawn as their swarm only; ejecta and vortices
	 * decay and blow up when they die).
	 */
	private boolean mirrorSpawned(ShipAPI ship, s3d.core.SpawnRequest r) {
		try {
			int n = deployed++;
			Ship3D s = ShipBridge.toShip3D(ship, "s" + n, cfg.tuning);
			s.fighter = ship.isFighter();
			s.hull = Math.min(s.hullMax, ship.getHitpoints());
			s.behaviour = r.behaviour;
			s.launchedBy = r.launcher;
			if (r.behaviour == s3d.core.Ship3D.Behaviour.CONSTRUCTION) s.carrier = r.source; // one build under way per Fabricator
			sim.addShip(s);
			int idx = 0;
			for (s3d.core.SpawnRequest o : spawned.values()) if (o == r) idx++;
			s.pos.set(r.pos).add(new Vec3((idx % 3 - 1) * 15f, (idx % 2) * 10f, 0f));
			s.rot.setLookRotation(r.dir, new Vec3(0f, 1f, 0f));
			float top = s.params != null ? s.params.fwdSpeed : 100f;
			s.vel.set(r.dir).scale(top * r.speedFraction);
			if (r.behaviour != null) {
				switch (r.behaviour) {
					case SWARM:
						s.noHull = true;
						break;
					case CONSTRUCTION:
						s.noHull = true;
						s.travelLeft = r.travelSeconds;
						s.buildLeft = s.buildTotal = r.buildSeconds;
						s.buildVariant = r.buildVariant;
						s.hullMax *= r.buildSizeMult;
						s.hull = s.hullMax;
						ExoticBridge.constructionLook(s, r.buildSizeMult);
						swarmLooked.add(s);
						break;
					case EJECTA:
						s.decayPerSecond = s3d.core.SpawnBehaviours.EJECTA_DECAY;
						s.deathBlast = deathBlast(ship, ShroudedEjectaShipCreator.EXPLOSION_DAMAGE);
						break;
					case VORTEX:
						s.decayPerSecond = s3d.core.SpawnBehaviours.VORTEX_DECAY;
						s.deathBlast = deathBlast(ship, ShroudedVortexShipCreator.EXPLOSION_DAMAGE);
						break;
					default:
				}
			}
			s.ai = new ShipAI3D(n);
			s.ai.homeDir.set(0f, 0f, s.owner == 0 ? 1f : -1f);
			s.ai.profile(s, cfg.tuning);
			twins.put(ship, s);
			if (r.behaviour == null) newShips.add(s);
			return true;
		} catch (Throwable t) {
			log.error("S3D: could not mirror spawned " + ship.getHullSpec().getHullId() + "; it stays a parked puppet", t);
			return false;
		}
	}

	/** Vanilla's blast for a shrouded unit's death: energy, enemies only, full inside its hull, out to its explosion radius. */
	private static s3d.core.WeaponSpec3D deathBlast(ShipAPI ship, float damage) {
		s3d.core.WeaponSpec3D w = new s3d.core.WeaponSpec3D();
		w.id = "shroud_burst";
		w.name = "Shroud burst";
		w.damage = damage;
		w.damageType = "ENERGY";
		w.type = "ENERGY";
		w.proxRadius = com.fs.starfarer.api.loading.DamagingExplosionSpec.getShipExplosionRadius(ship);
		w.proxCore = ship.getCollisionRadius();
		w.fringeColor = new float[] {0.59f, 0f, 0.12f};
		w.blastSound = "explosion_ship";
		return w;
	}

	/** A construction swarm finished: the ship it built comes in where it stood, and the swarm is gone. */
	private void finishBuild(ShipAPI swarm, Ship3D s) {
		if (s.buildVariant != null) {
			s3d.core.SpawnRequest r = new s3d.core.SpawnRequest(s.buildVariant, false, s.carrier != null ? s.carrier : s, s.pos, s.rot.forward(new Vec3()), 0f, null);
			sim.combat.spawnRequests.add(r);
			sim.combat.effects.add(new s3d.core.Effect(s3d.core.Effect.Kind.FLASH, s.pos, s.radius * 3f, 0.6f).color(0.51f, 0.61f, 0.57f));
		}
		finished.add(swarm);
		removeTwin(s);
		if (engine.isEntityInPlay(swarm)) engine.removeEntity(swarm);
	}

	/** A drone whose mothership is alive but hasn't got its 3D twin yet. */
	private boolean dronePending(ShipAPI drone) {
		ShipAPI c = drone.getDroneSource();
		return c != null && c.isAlive() && !c.isHulk() && !twins.containsKey(c) && !frozen.containsKey(c);
	}

	/**
	 * A drone vanilla's launcher just put out (station drones, point-defence drones): its 3D twin
	 * launches out of the mothership to its orbit, set by the launcher's own behaviour table (radius
	 * beyond the hull, speed, direction, facing), spread round by its slot index.
	 */
	private boolean mirrorDrone(ShipAPI ship) {
		ShipAPI src = ship.getDroneSource();
		Ship3D host = src != null ? twins.get(src) : null;
		if (host == null || host.destroyed || !sim.ships.contains(host)) return false;
		try {
			int n = deployed++;
			Ship3D d = ShipBridge.toShip3D(ship, "d" + n, cfg.tuning);
			d.fighter = true;
			d.drone = true;
			d.carrier = host;
			d.hull = Math.min(d.hullMax, ship.getHitpoints());
			int index = 0, maxDrones = 1;
			JSONObject beh = null;
			if (src.getSystem() instanceof DroneLauncherShipSystemAPI) {
				DroneLauncherShipSystemAPI dl = (DroneLauncherShipSystemAPI) src.getSystem();
				index = Math.max(0, dl.getIndex(ship));
				JSONObject spec = dl.getSpecJson();
				if (spec != null) {
					maxDrones = Math.max(1, spec.optInt("maxDrones", 1));
					beh = droneBehaviour(spec.optJSONArray("droneBehavior"), index);
				}
			}
			d.droneOrbitRadius = beh != null ? (float) beh.optDouble("orbitRadius", 100) : 100f;
			d.droneOrbitSpeed = beh != null ? (float) beh.optDouble("orbitSpeed", 100) : 100f;
			d.droneOrbitDir = beh != null && beh.optInt("orbitDir", 1) < 0 ? -1 : 1;
			d.droneFaceEnemy = beh != null && beh.optBoolean("faceEnemy", false);
			float initial = beh != null ? (float) beh.optDouble("initialOrbitAngle", 0) : 0f;
			d.droneOrbitAngle = (float) Math.toRadians(initial + index * 360f / maxDrones);
			sim.addShip(d);
			d.pos.set(host.pos);
			d.rot.set(host.rot);
			d.vel.set(host.vel);
			d.ai = new ShipAI3D(n);
			d.ai.homeDir.set(0f, 0f, d.owner == 0 ? 1f : -1f);
			d.ai.profile(d, cfg.tuning);
			twins.put(ship, d);
			log.info(String.format("S3D: drone %s #%d from %s: orbit %.0f beyond the hull at %.0f/s, %s", ship.getHullSpec().getHullId(), index,
					host.name, d.droneOrbitRadius, d.droneOrbitSpeed, d.droneFaceEnemy ? "faces enemies" : "faces outward"));
			return true;
		} catch (Throwable t) {
			log.error("S3D: could not mirror drone " + ship.getHullSpec().getHullId() + "; it stays a parked puppet", t);
			return false;
		}
	}

	/** The launcher's behaviour entry whose droneIndex list has this index (else the first). */
	private static JSONObject droneBehaviour(JSONArray list, int index) {
		if (list == null || list.length() == 0) return null;
		for (int i = 0; i < list.length(); i++) {
			JSONObject b = list.optJSONObject(i);
			JSONArray idx = b != null ? b.optJSONArray("droneIndex") : null;
			if (idx == null) continue;
			for (int k = 0; k < idx.length(); k++) if (idx.optInt(k, -1) == index) return b;
		}
		return list.optJSONObject(0);
	}

	/**
	 * Reserve Deployment went off in 3D: vanilla's own script works the carrier puppet's launch bays
	 * (faster replacements, extra craft for a while), and the extra fighters it launches get their 3D
	 * twins like any others.
	 */
	private void reserveDeployment(ShipAPI ship, Ship3D s) {
		try {
			new ReserveWingStats().apply(ship.getMutableStats(), "s3d_reservewing", ShipSystemStatsScript.State.ACTIVE, 1f);
			log.info("S3D: " + s.name + " used Reserve Deployment; vanilla's bays launch the extra fighters");
		} catch (Throwable t) {
			log.warn("S3D: Reserve Deployment on " + s.name + " failed (" + t + ")");
		}
	}

	/** The 3D fighter is back at its carrier: hand it to its bay so vanilla refits and relaunches it. */
	private void landFighter(ShipAPI ship, Ship3D f) {
		removeTwin(f);
		twins.remove(ship);
		frozen.remove(ship); // a relaunch of this same puppet gets mirrored afresh
		FighterLaunchBayAPI bay = ship.getWing() != null ? ship.getWing().getSource() : null;
		try {
			if (bay != null && bay.getShip() != null && bay.getShip().isAlive()) {
				bay.land(ship);
				landings++;
				return;
			}
		} catch (Throwable t) {
			log.warn("S3D: landing " + ship.getHullSpec().getHullId() + " failed (" + t + "); removing it instead");
		}
		finished.add(ship);
		engine.removeEntity(ship);
	}

	private boolean overlapsAny(Ship3D s) {
		for (Ship3D o : sim.ships) {
			if (o == s || o.destroyed) continue;
			if (o.pos.distance(s.pos) < (o.radius + s.radius) * 1.3f) return true;
		}
		return false;
	}

	/** Fighters and pods wait in a corner of their own half, out of the way. */
	private Vector2f parkingSpot(ShipAPI ship) {
		int i = parkedCount++;
		float y = (ship.getOwner() == 0 ? -1f : 1f) * (mapH * 0.5f - 700f);
		return new Vector2f(-mapW * 0.5f + 700f + (i % 20) * 120f, y);
	}

	/**
	 * Puppet follows its twin on the 2D map (clamped inside it), standing still. Modules are left to
	 * vanilla (it keeps them on their parent); a station only turns.
	 */
	private void syncPuppet(ShipAPI ship, Ship3D s) {
		if (s.parent != null) return;
		if (s.anchored) {
			Vec3 f = s.rot.forward(new Vec3());
			ship.getVelocity().set(0f, 0f);
			ship.setAngularVelocity(0f);
			if (f.x * f.x + f.z * f.z > 1e-4f) ship.setFacing((float) Math.toDegrees(Math.atan2(-f.z, f.x)));
			return;
		}
		Vector2f p = to2D(s.pos, new Vector2f());
		ship.getLocation().set(p);
		ship.getVelocity().set(0f, 0f);
		ship.setAngularVelocity(0f);
		Vec3 f = s.rot.forward(new Vec3());
		if (f.x * f.x + f.z * f.z > 1e-4f) ship.setFacing((float) Math.toDegrees(Math.atan2(-f.z, f.x)));
	}

	private Vector2f to2D(Vec3 pos, Vector2f out) {
		float hx = mapW * 0.5f - 300f, hy = mapH * 0.5f - 300f;
		out.set(Math.max(-hx, Math.min(hx, pos.x / scale)), Math.max(-hy, Math.min(hy, -pos.z / scaleZ)));
		return out;
	}

	// ---- write-back ----------------------------------------------------------------------------

	@Override
	protected void afterSim(float dt, float realDt) {
		if (handedBack) return;
		ordersTimer -= realDt;
		boolean readOrders = ordersTimer <= 0f;
		if (readOrders) {
			ordersTimer = 0.5f;
			syncObjectives();
			refreshWeaponRanges();
		}
		armorTimer -= realDt;
		boolean writeArmor = armorTimer <= 0f;
		if (writeArmor) armorTimer = 0.5f;

		for (Map.Entry<ShipAPI, Ship3D> e : new ArrayList<>(twins.entrySet())) {
			ShipAPI ship = e.getKey();
			Ship3D s = e.getValue();
			if (finished.contains(ship)) continue;
			if (s.destroyed) {
				killPuppet(ship, s);
				continue;
			}
			if (!ship.isAlive() || ship.isHulk()) {
				vanillaKilled(ship, s); // e.g. a station core vanilla counts as beaten once its sections are gone
				continue;
			}
			if (s.behaviour == s3d.core.Ship3D.Behaviour.CONSTRUCTION && s.buildDone) {
				finishBuild(ship, s);
				continue;
			}
			if ((s.fragments != null || s.shrouded) && !swarmLooked.contains(s) && ExoticBridge.swarmLook(s, ship)) swarmLooked.add(s);
			if (s.system != null && s.system.hostRequest) {
				s.system.hostRequest = false;
				if (s.system.kind == s3d.core.ShipSystem3D.Kind.RESERVE_DEPLOYMENT) reserveDeployment(ship, s);
			}
			if (s.fixedPart()) {
				if (Math.abs(ship.getHitpoints() - s.hull) > 0.5f) ship.setHitpoints(Math.max(1f, s.hull));
				syncFlux(ship, s);
				if (writeArmor) writeArmor(ship, s);
				if (s.anchored && s.invulnerable) checkStationBeaten(ship, s);
				continue;
			}
			if (s.fighter) {
				if (s.landed) landFighter(ship, s);
				else if (Math.abs(ship.getHitpoints() - s.hull) > 0.5f) ship.setHitpoints(Math.max(1f, s.hull));
				continue;
			}
			if (readOrders) {
				boolean ordered = retreatOrdered(ship);
				if (ordered != s.retreatOrdered) {
					log.info("S3D: " + s.name + (ordered ? " ordered to retreat" : " retreat order cancelled"));
					s.retreatOrdered = ordered;
					// A retreating AI carrier calls its wings back so they leave with it (yours stay as you set them).
					if (s != sim.player && ship.hasLaunchBays()) {
						s.wingsRegroup = ordered;
						ship.setPullBackFighters(ordered);
					}
				}
				Order3D o = s.ai != null ? readOrder(ship, s) : null;
				if (o != null) o.admiral = ship.getOwner() != 0 || ship.isAlly(); // the enemy's or an ally's admiral, not yours
				if (o == null ? s.order != null : !o.sameAs(s.order)) {
					log.info("S3D: " + s.name + (o != null ? " ordered: " + o : " has no orders: fights freely"));
					s.order = o;
				}
			}
			if (s.retreatOrdered && pastOwnEdge(s)) {
				retreatPuppet(ship, s);
				continue;
			}
			if (Math.abs(ship.getHitpoints() - s.hull) > 0.5f) ship.setHitpoints(Math.max(1f, s.hull));
			syncFlux(ship, s);
			if (writeArmor) writeArmor(ship, s);
		}
		dismissStrandedFighters();
		fighterLogTimer -= realDt;
		if (fighterLogTimer <= 0f && launches + landings + fighterLosses > 0) {
			fighterLogTimer = 10f;
			int up = 0;
			for (Ship3D s : sim.ships) if (s.fighter && !s.destroyed) up++;
			log.info("S3D: fighters: " + up + " flying; so far " + launches + " launched, " + landings + " landed to rearm, " + fighterLosses + " lost, "
					+ fightersDismissed + " left with their side");
		}
		manoeuvreLogTimer -= realDt;
		if (manoeuvreLogTimer <= 0f) {
			manoeuvreLogTimer = 20f;
			int runs = 0, flanks = 0, holds = 0;
			for (Ship3D s : sim.ships) {
				if (s.fighter || s.ai == null || s == sim.player) continue;
				runs += s.ai.passesMade;
				flanks += s.ai.plans[s3d.core.ShipAI3D.Move.FLANK.ordinal()];
				holds += s.ai.plans[s3d.core.ShipAI3D.Move.HOLD.ordinal()];
			}
			if (runs + flanks + holds > 0) log.info("S3D: AI manoeuvres so far: " + runs + " gun runs, " + flanks + " flanking moves, " + holds + " holds");
		}
		followVanillaPlayerShip(); // command never passes by itself: only by your shuttle (tactical view) or vanilla's Tab map
		if (handBackTimer > 0f) {
			handBackTimer -= realDt;
			if (handBackTimer <= 0f) handBackArmed = false;
		}
	}

	/** Shown on the command map; kept just under capacity so vanilla never overloads a puppet. */
	private static void syncFlux(ShipAPI ship, Ship3D s) {
		if (s.flux == null || ship.getFluxTracker() == null) return;
		float cap = s.flux.maxFlux * 0.99f;
		ship.getFluxTracker().setCurrFlux(Math.min(cap, s.flux.flux));
		ship.getFluxTracker().setHardFlux(Math.min(cap, s.flux.hardFlux));
	}

	/**
	 * Vanilla should end a Vast Bulk station once its armed sections are destroyed (spurs and armour
	 * don't count; nobody targets them). If the core is still alive a few seconds after the last one
	 * died, remove it as destroyed ourselves, so the battle can't get stuck on an invulnerable core.
	 */
	private void checkStationBeaten(ShipAPI ship, Ship3D s) {
		int modules = 0, alive = 0;
		for (Ship3D o : sim.ships) {
			if (o.parent != s || o.unarmedPart()) continue;
			modules++;
			if (!o.destroyed) alive++;
		}
		if (modules == 0 || alive > 0) {
			strippedSince.remove(ship);
			return;
		}
		Float since = strippedSince.get(ship);
		if (since == null) {
			strippedSince.put(ship, clock);
			log.info("S3D: every armed section of station " + s.name + " destroyed; waiting for vanilla to end the station");
		} else if (clock - since > 6f) {
			log.warn("S3D: vanilla kept station " + s.name + " alive with no armed sections left; removing it as destroyed");
			strippedSince.remove(ship);
			engine.getFleetManager(ship.getOwner()).removeDeployed(ship, false);
			engine.removeEntity(ship);
			for (Ship3D o : sim.ships) // spurs and armour still standing go with the core
				if (o.parent == s && !o.destroyed && o.hostRef instanceof ShipAPI) engine.removeEntity((ShipAPI) o.hostRef);
			vanillaKilled(ship, s);
		}
	}

	/** Vanilla ended this ship itself (a beaten station's core, a module whose parent died): end the twin too. */
	private void vanillaKilled(ShipAPI ship, Ship3D s) {
		finished.add(ship);
		sim.combat.kill(s); // explodes; its modules go with it
		for (Ship3D o : sim.ships) if (o.parent == s && o.hostRef instanceof ShipAPI) finished.add((ShipAPI) o.hostRef);
		log.info("S3D: vanilla ended " + s.name + " (" + s.hullName + ", hulk=" + ship.isHulk() + "); its 3D twin goes too");
	}

	private boolean pastOwnEdge(Ship3D s) {
		return s.owner == 0 ? s.pos.z > edgeZ : s.pos.z < -edgeZ;
	}

	/** Vanilla orders: the ship's own retreat assignment, its side's full retreat, or vanilla flagging it. */
	private boolean retreatOrdered(ShipAPI ship) {
		try {
			if (ship.isRetreating()) return true;
			CombatFleetManagerAPI fm = engine.getFleetManager(ship.getOwner());
			DeployedFleetMemberAPI dfm = fm.getDeployedFleetMember(ship);
			CombatTaskManagerAPI tm = fm.getTaskManager(dfm != null && dfm.isAlly());
			if (tm == null) return false;
			if (tm.isInFullRetreat()) return true;
			AssignmentInfo a = tm.getAssignmentFor(ship);
			return a != null && a.getType() == CombatAssignmentType.RETREAT;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Vanilla's assignment for this ship as a 3D order, or null to fight freely (search and
	 * destroy, recon, refit, no assignment). Retreat is read separately. An order aimed at a
	 * station (or a module) means holding the ground round the whole structure.
	 */
	private Order3D readOrder(ShipAPI ship, Ship3D s) {
		if (ordersFailed) return null;
		try {
			CombatFleetManagerAPI fm = engine.getFleetManager(ship.getOwner());
			DeployedFleetMemberAPI dfm = fm.getDeployedFleetMember(ship);
			CombatTaskManagerAPI tm = fm.getTaskManager(dfm != null && dfm.isAlly());
			AssignmentInfo a = tm != null ? tm.getAssignmentFor(ship) : null;
			if (a == null || a.getType() == null) return null;
			String type = a.getType().name();
			Object target = a.getTarget();
			Ship3D twin = null;
			if (target instanceof DeployedFleetMemberAPI) {
				ShipAPI ts = ((DeployedFleetMemberAPI) target).getShip();
				twin = ts != null ? twins.get(ts) : null;
				if (twin != null && (twin.destroyed || !sim.ships.contains(twin))) twin = null;
			}
			Objective3D obj = target instanceof BattleObjectiveAPI ? objectives.get(target) : null;
			Vec3 at = null;
			if (target instanceof AssignmentTargetAPI) {
				Vector2f loc = ((AssignmentTargetAPI) target).getLocation();
				if (loc != null) {
					Vec3 exact = waypointPoints.get(heightKey(loc));
					at = exact != null ? new Vec3(exact) : new Vec3(loc.x * scale, 0f, -loc.y * scaleZ);
				}
			}
			float holdR = cfg.tuning.battleHoldRadius * scale;
			if (twin != null && twin.fixedPart() && !"AVOID".equals(type)) {
				Ship3D root = twin.root();
				return order(type, Order3D.Kind.HOLD, root.pos, sim.structureExtent(root) + holdR);
			}
			switch (type) {
				case "LIGHT_ESCORT":
				case "MEDIUM_ESCORT":
				case "HEAVY_ESCORT":
				case "DEFEND":
				case "RALLY_TASK_FORCE":
				case "RALLY_CARRIER":
				case "RALLY_CIVILIAN":
				case "RALLY_STRIKE_FORCE":
				case "RALLY_FIGHTERS":
					if (twin != null && twin.owner == s.owner && twin != s) {
						Order3D o = new Order3D(Order3D.Kind.ESCORT);
						o.label = type;
						o.ship = twin;
						return o;
					}
					return at != null ? order(type, Order3D.Kind.HOLD, at, holdR) : null;
				case "CAPTURE":
				case "CONTROL":
				case "ASSAULT":
					if (obj != null) {
						Order3D o = order(type, Order3D.Kind.CAPTURE, obj.pos, obj.radius);
						o.objective = obj;
						return o;
					}
					return at != null ? order(type, Order3D.Kind.HOLD, at, holdR) : null;
				case "ENGAGE":
				case "INTERCEPT":
				case "HARASS":
				case "STRIKE":
					if (twin != null && twin.owner != s.owner) {
						Order3D o = new Order3D(Order3D.Kind.ATTACK);
						o.label = type;
						o.ship = twin;
						return o;
					}
					return at != null ? order(type, Order3D.Kind.HOLD, at, holdR) : null;
				case "AVOID": {
					if (twin == null && at == null) return null;
					Ship3D around = twin != null ? twin.root() : null;
					Order3D o = order(type, Order3D.Kind.AVOID, around != null ? new Vec3() : at, // round a ship: it moves (Order3D.anchor)
							cfg.tuning.battleAvoidRadius * scale + (around != null ? sim.structureExtent(around) : 0f));
					o.ship = around;
					return o;
				}
				default:
					return null;
			}
		} catch (Throwable t) {
			ordersFailed = true;
			log.error("S3D: could not read vanilla orders; 3D pilots will fight freely (retreats still work)", t);
			return null;
		}
	}

	/** A vanilla waypoint's map spot, to 5 map units, as a key for the height it was given in 3D. */
	private static long heightKey(Vector2f loc) {
		return ((long) Math.round(loc.x / 5f) << 32) ^ (Math.round(loc.y / 5f) & 0xffffffffL);
	}

	// ---- orders from the tactical view ------------------------------------------------------------

	@Override
	protected void carryOutCommands() {
		List<CommandView.Command> list = new ArrayList<>(sim.command.issued);
		sim.command.issued.clear();
		sim.command.fleetButtons = !handedBack && !commandsFailed;
		if (handedBack || commandsFailed) return;
		try { // the fleet buttons show vanilla's own state
			CombatTaskManagerAPI tm = engine.getFleetManager(0).getTaskManager(false);
			sim.command.fullAssault = tm != null && tm.isFullAssault();
			sim.command.fullRetreat = tm != null && tm.isInFullRetreat();
		} catch (Throwable t) {
			sim.command.fleetButtons = false;
		}
		for (CommandView.Command c : list) {
			try {
				carryOut(c);
			} catch (Throwable t) {
				commandsFailed = true;
				sim.command.flag("ORDERS OFF");
				log.error("S3D: could not give the order " + c + "; tactical view orders are off for this battle (Tab map still works)", t);
				return;
			}
		}
		if (!list.isEmpty()) ordersTimer = 0f; // read them back next frame, not in half a second
	}

	@Override
	protected int commandPoints() {
		try {
			CombatTaskManagerAPI tm = engine.getFleetManager(0).getTaskManager(false);
			return tm != null ? tm.getCommandPointsLeft() : -1;
		} catch (Throwable t) {
			return -1;
		}
	}

	/** One order from the tactical view as a vanilla assignment (or retreat / search and destroy). */
	private void carryOut(CommandView.Command c) {
		CombatFleetManagerAPI fm = engine.getFleetManager(0);
		CombatTaskManagerAPI tm = fm.getTaskManager(false);
		if (tm == null) return;
		List<DeployedFleetMemberAPI> members = new ArrayList<>();
		for (Ship3D s : c.ships) {
			DeployedFleetMemberAPI m = s.hostRef instanceof ShipAPI ? fm.getDeployedFleetMember((ShipAPI) s.hostRef) : null;
			if (m != null) members.add(m);
		}
		if (c.kind == CommandView.Kind.FULL_ASSAULT) { // vanilla's fleet-wide modes: no command point
			tm.setFullAssault(!tm.isFullAssault());
			log.info("S3D: tactical view: full assault " + (tm.isFullAssault() ? "on" : "off"));
			return;
		}
		if (c.kind == CommandView.Kind.FULL_RETREAT) {
			if (!tm.isInFullRetreat()) tm.orderFullRetreat();
			log.info("S3D: tactical view: full retreat ordered");
			return;
		}
		if (!c.ships.isEmpty() && members.isEmpty()) return;
		int cpBefore = tm.getCommandPointsLeft();
		if (cpBefore < 1) {
			sim.command.flag("NO CP");
			log.info("S3D: tactical view order " + c + " not given: no command points left");
			return;
		}
		if (c.kind == CommandView.Kind.CLEAR || c.kind == CommandView.Kind.RETREAT) {
			boolean first = true; // one command point for the whole order, as on the Tab map
			for (DeployedFleetMemberAPI m : members) {
				if (c.kind == CommandView.Kind.RETREAT) tm.orderRetreat(m, first, false);
				else tm.orderSearchAndDestroy(m, first);
				first = false;
			}
			log.info("S3D: tactical view order " + c + " (command points " + cpBefore + " -> " + tm.getCommandPointsLeft() + ")");
			return;
		}
		AssignmentTargetAPI target = null;
		CombatAssignmentType type = null;
		switch (c.kind) {
			case ENGAGE:
			case AVOID:
			case ESCORT:
				if (c.ship != null) {
					ShipAPI puppet = c.ship.root().hostRef instanceof ShipAPI ? (ShipAPI) c.ship.root().hostRef : null;
					target = puppet != null ? engine.getFleetManager(puppet.getOwner()).getDeployedFleetMember(puppet) : null;
				} else if (c.kind == CommandView.Kind.AVOID) {
					target = waypoint(tm, c.point);
				}
				type = c.kind == CommandView.Kind.ENGAGE ? CombatAssignmentType.ENGAGE
						: c.kind == CommandView.Kind.AVOID ? CombatAssignmentType.AVOID
						: members.size() >= 3 ? CombatAssignmentType.HEAVY_ESCORT
						: members.size() == 2 ? CombatAssignmentType.MEDIUM_ESCORT : CombatAssignmentType.LIGHT_ESCORT;
				break;
			case CAPTURE: {
				Object o = c.objective != null ? c.objective.hostRef : null;
				if (o instanceof BattleObjectiveAPI) {
					BattleObjectiveAPI obj = (BattleObjectiveAPI) o;
					target = obj;
					type = obj.getOwner() == 0 ? CombatAssignmentType.CONTROL
							: obj.getOwner() == 1 ? CombatAssignmentType.ASSAULT : CombatAssignmentType.CAPTURE;
				}
				break;
			}
			case MOVE:
				target = waypoint(tm, c.point);
				type = CombatAssignmentType.RALLY_TASK_FORCE;
				break;
			default:
				break;
		}
		if (target == null || type == null) {
			log.info("S3D: tactical view order " + c + " has no vanilla target; not given");
			return;
		}
		AssignmentInfo info = tm.createAssignment(type, target, true);
		if (info == null) {
			sim.command.flag("NO CP");
			log.info("S3D: vanilla refused " + type + " for " + c + " (command points " + cpBefore + ")");
			return;
		}
		for (DeployedFleetMemberAPI m : members) tm.giveAssignment(m, info, false);
		log.info("S3D: tactical view order " + c + " -> vanilla " + type + (members.isEmpty() ? " (vanilla picks the ships)" : "")
				+ " (command points " + cpBefore + " -> " + tm.getCommandPointsLeft() + ")");
	}

	/** A vanilla waypoint under a 3D point; the exact point is kept for when the order is read back. */
	private AssignmentTargetAPI waypoint(CombatTaskManagerAPI tm, Vec3 p) {
		Vector2f loc = to2D(p, new Vector2f());
		AssignmentTargetAPI w = tm.createWaypoint2(loc, false);
		if (w == null) return null;
		Vector2f at = w.getLocation() != null ? w.getLocation() : loc;
		waypointPoints.put(heightKey(at), new Vec3(p));
		if (Math.abs(at.x - loc.x) > 5f || Math.abs(at.y - loc.y) > 5f)
			log.info(String.format("S3D: vanilla put the waypoint at (%.0f, %.0f), asked (%.0f, %.0f); 3D pilots go to the spot you clicked", at.x, at.y, loc.x, loc.y));
		return w;
	}

	private static Order3D order(String label, Order3D.Kind kind, Vec3 at, float radius) {
		Order3D o = new Order3D(kind);
		o.label = label;
		o.point.set(at);
		o.radius = radius;
		return o;
	}

	/**
	 * Objectives: mirror new ones, follow vanilla's owner (toasting changes), count who's on each.
	 * If one side has been alone on an objective for battle.captureFallback seconds and vanilla
	 * still hasn't given it to them, it's given here (logged as a warning).
	 */
	private void syncObjectives() {
		if (objectivesFailed) return;
		try {
			List<BattleObjectiveAPI> list = engine.getObjectives();
			if (list == null) return;
			for (BattleObjectiveAPI o : list) {
				Objective3D v = objectives.get(o);
				int owner = side(o.getOwner());
				if (v == null) {
					v = new Objective3D(o.getType(), o.getDisplayName() != null ? o.getDisplayName() : o.getType());
					v.pos.set(o.getLocation().x * scale, 0f, -o.getLocation().y * scaleZ);
					v.radius = cfg.tuning.battleCaptureRadius * scale;
					v.owner = owner;
					v.hostRef = o;
					objectives.put(o, v);
					sim.objectives.add(v);
					log.info(String.format("S3D: objective %s (%s) in 3D at (%.0f, %.0f), held by %s", v.name, v.type, v.pos.x, v.pos.z, owner < 0 ? "nobody" : "side " + owner));
				} else if (owner != v.owner) {
					log.info("S3D: " + v.name + " now held by " + (owner < 0 ? "nobody" : "side " + owner) + " (was " + (v.owner < 0 ? "nobody" : "side " + v.owner) + ")");
					v.owner = owner;
				}
				v.present[0] = v.present[1] = 0;
				for (Ship3D s : sim.ships) {
					if (!s.destroyed && !s.fighter && s.parent == null && v.flatDistance(s.pos) < v.radius) v.present[s.owner == 0 ? 0 : 1]++;
				}
				int h = v.holder();
				if (h < 0 || h == v.owner) {
					holding.remove(v);
					continue;
				}
				float[] since = holding.get(v);
				if (since == null || since[0] != h) {
					holding.put(v, new float[] {h, clock});
				} else if (clock - since[1] > cfg.tuning.battleCaptureFallback) {
					log.warn(String.format("S3D: side %d has been alone on %s for %.0f s and vanilla hasn't given it to them; taking it in 3D", h, v.name, clock - since[1]));
					o.setOwner(h);
					holding.remove(v);
				}
			}
		} catch (Throwable t) {
			objectivesFailed = true;
			log.error("S3D: could not read battle objectives; they won't show or be taken in 3D", t);
		}
	}

	private static int side(int owner) {
		return owner == 0 || owner == 1 ? owner : -1;
	}

	/**
	 * Weapon ranges follow vanilla's as the battle goes (sensor arrays' ECM penalty, anything else
	 * that changes them mid-battle); the AI re-reads its fighting range when they do.
	 */
	private void refreshWeaponRanges() {
		for (Ship3D s : twins.values()) {
			if (s.destroyed || s.fighter) continue;
			String example = null;
			for (WeaponMount m : s.weapons) {
				if (!(m.hostRef instanceof WeaponAPI)) continue;
				float r = ((WeaponAPI) m.hostRef).getRange();
				if (r <= 0f || Math.abs(r - m.spec.range) < 0.5f) continue;
				if (example == null) example = String.format("%s %.0f -> %.0f", m.spec.name, m.spec.range, r);
				m.spec.range = r;
			}
			if (example == null) continue;
			log.info("S3D: weapon ranges of " + s.name + " changed in vanilla (" + example + ")");
			if (s.ai != null) s.ai.profile(s, cfg.tuning);
		}
	}

	/**
	 * Armor: each vanilla cell takes the remaining armor of the 3D sector in its direction from the
	 * ship's centre (averaged over top, side and underside). Only ever lowers a cell.
	 */
	private static void writeArmor(ShipAPI ship, Ship3D s) {
		ArmorGridAPI grid = ship.getArmorGrid();
		if (grid == null || s.armor == null || s.armor.rating <= 0f) return;
		float[][] cells = grid.getGrid();
		float max = grid.getMaxArmorInCell();
		Vector2f c = ship.getLocation();
		double f = Math.toRadians(ship.getFacing());
		float cos = (float) Math.cos(f), sin = (float) Math.sin(f);
		boolean changed = false;
		for (int x = 0; x < cells.length; x++) {
			for (int y = 0; y < cells[x].length; y++) {
				Vector2f loc = grid.getLocation(x, y);
				float rx = loc.x - c.x, ry = loc.y - c.y;
				float fwd = rx * cos + ry * sin, left = -rx * sin + ry * cos; // ship space
				int az = ArmorShell.sector(-left, -fwd, 0); // 3D local: x = starboard, z = aft
				float frac = 0f;
				for (int el = 0; el < ArmorShell.EL; el++) frac += s.armor.frac[el * ArmorShell.AZ + az];
				float value = frac / ArmorShell.EL * max;
				if (value < cells[x][y] - 0.5f) {
					grid.setArmorValue(x, y, value);
					changed = true;
				}
			}
		}
		if (changed) ship.syncWithArmorGridState();
	}

	/**
	 * The 3D ship died: kill the puppet the vanilla way so the campaign records the loss. It's moved
	 * to an empty spot first so its explosion can't damage other puppets.
	 */
	private void killPuppet(ShipAPI ship, Ship3D s) {
		finished.add(ship);
		// Modules die where they are (vanilla keeps them on their parent); free ships in an empty corner.
		Vector2f grave = s.fixedPart() ? new Vector2f(ship.getLocation()) : graveSpot();
		ship.getLocation().set(grave);
		ship.getVelocity().set(0f, 0f);
		ship.setHitpoints(1f);
		ShipAPI killer = s.lastHitBy != null && s.lastHitBy.hostRef instanceof ShipAPI ? (ShipAPI) s.lastHitBy.hostRef : null;
		engine.applyDamage(ship, grave, 1e6f, DamageType.ENERGY, 0f, true, false, killer, false);
		killPending.put(ship, clock);
		if (!s.fighter) {
			log.info("S3D: " + s.name + " destroyed in 3D" + (killer != null ? " by " + s.lastHitBy.name : "")
					+ "; puppet killed (hulk=" + ship.isHulk() + ", hp=" + ship.getHitpoints() + ")");
		}
		if (s.fighter) fighterLosses++;
	}

	private Vector2f graveSpot() {
		int i = graves++;
		float x = (i % 2 == 0 ? 1f : -1f) * (mapW * 0.5f - 900f);
		float y = -mapH * 0.5f + 1200f + (i / 2) * 1000f % Math.max(1000f, mapH - 2400f);
		return new Vector2f(x, y);
	}

	/** The 3D ship reached its own edge under a retreat order: the puppet leaves the map the same way. */
	private void retreatPuppet(ShipAPI ship, Ship3D s) {
		finished.add(ship);
		Vector2f p = to2D(s.pos, new Vector2f());
		float outY = (s.owner == 0 ? -1f : 1f) * (mapH * 0.5f + 800f);
		ship.setRetreating(true, false);
		ship.getLocation().set(p.x, outY);
		ship.getVelocity().set(0f, s.owner == 0 ? -100f : 100f);
		retreatPending.put(ship, clock);
		sim.combat.jumpOut(s);
		removeTwin(s);
		log.info("S3D: " + s.name + " retreated in 3D; puppet sent off the " + (s.owner == 0 ? "bottom" : "top") + " edge");
	}

	/**
	 * Fighters can't outlast their side: a wing whose carrier retreated goes with it, and wings
	 * whose carriers died fight on only while their side still has ships here. Their puppets are
	 * pinned to the 3D twins and never reach a map edge, so left alone they would keep vanilla from
	 * ending the battle after the last ship of their side has gone.
	 */
	private void dismissStrandedFighters() {
		boolean[] hasShips = new boolean[2];
		for (Ship3D s : sim.ships) if (!s.destroyed && !s.fighter && (s.owner == 0 || s.owner == 1)) hasShips[s.owner] = true;
		for (Map.Entry<ShipAPI, Ship3D> e : new ArrayList<>(twins.entrySet())) {
			ShipAPI ship = e.getKey();
			Ship3D f = e.getValue();
			if (!f.fighter || f.destroyed || finished.contains(ship)) continue;
			boolean carrierLeft = f.carrier != null && !f.carrier.destroyed && !sim.ships.contains(f.carrier);
			boolean sideGone = f.owner >= 0 && f.owner < 2 && !hasShips[f.owner];
			if (carrierLeft || sideGone) dismissFighter(ship, f, carrierLeft ? "its carrier left" : "no ships left on its side");
		}
		for (Map.Entry<ShipAPI, Vector2f> e : new ArrayList<>(parked.entrySet())) {
			ShipAPI ship = e.getKey();
			if (!ship.isFighter() || finished.contains(ship)) continue;
			int side = ship.getOwner();
			ShipAPI c = ship.getWing() != null ? ship.getWing().getSourceShip() : null;
			boolean carrierGone = c == null || !c.isAlive() || c.isHulk() || finished.contains(c) || !engine.isEntityInPlay(c);
			if (carrierGone || (side == 0 || side == 1) && !hasShips[side]) {
				parked.remove(ship);
				dismissFighter(ship, null, "parked, carrier or side gone");
			}
		}
	}

	private void dismissFighter(ShipAPI ship, Ship3D f, String why) {
		finished.add(ship);
		if (f != null) removeTwin(f);
		if (engine.isEntityInPlay(ship)) engine.removeEntity(ship);
		fightersDismissed++;
		log.info("S3D: fighter " + ship.getHullSpec().getHullId() + " (side " + ship.getOwner() + ") left the battle: " + why);
	}

	/** Makes sure vanilla counted kills and retreats; falls back to removing the puppet directly. */
	private void checkPending() {
		for (Map.Entry<ShipAPI, Float> e : new ArrayList<>(retreatPending.entrySet())) {
			ShipAPI ship = e.getKey();
			CombatFleetManagerAPI fm = engine.getFleetManager(ship.getOwner());
			FleetMemberAPI member = ship.getFleetMember();
			boolean counted = member != null && fm.getRetreatedCopy().contains(member);
			if (counted || !engine.isEntityInPlay(ship)) {
				log.info("S3D: vanilla " + (counted ? "counted " : "removed ") + ship.getName() + " as retreated");
				retreatPending.remove(ship);
			} else if (clock - e.getValue() > 1.5f) {
				log.warn("S3D: vanilla didn't pick up the retreat of " + ship.getName() + "; removing it as retreated directly");
				fm.removeDeployed(ship, true);
				engine.removeEntity(ship);
				retreatPending.remove(ship);
			}
		}
		for (Map.Entry<ShipAPI, Float> e : new ArrayList<>(killPending.entrySet())) {
			ShipAPI ship = e.getKey();
			if (ship.isHulk() || !ship.isAlive() || !engine.isEntityInPlay(ship)) {
				log.info("S3D: vanilla registered the loss of " + ship.getName() + " (hulk=" + ship.isHulk() + ")");
				killPending.remove(ship);
			} else if (clock - e.getValue() > 1.5f) {
				log.warn("S3D: puppet " + ship.getName() + " survived the kill (hp " + ship.getHitpoints() + "); removing it as destroyed");
				engine.getFleetManager(ship.getOwner()).removeDeployed(ship, false);
				engine.removeEntity(ship);
				killPending.remove(ship);
			}
		}
	}

	private void removeTwin(Ship3D s) {
		sim.ships.remove(s);
		if (sim.target == s) sim.target = null;
		for (Ship3D o : sim.ships) if (o.ai != null && o.ai.target == s) o.ai.target = null;
	}

	// ---- the player's ship ---------------------------------------------------------------------

	/** Vanilla's transfer-command can change the flagship; follow it. */
	private void followVanillaPlayerShip() {
		ShipAPI vp = engine.getPlayerShip();
		Ship3D t = vp != null ? twins.get(vp) : null;
		if (t != null && t != sim.player && !t.destroyed && !t.fighter && !t.fixedPart() && sim.ships.contains(t) && t.owner == 0) {
			log.info("S3D: vanilla moved command to " + t.name + "; flying it in 3D");
			takeControl(t, false);
		}
	}

	@Override
	protected void transferArrived(Ship3D s) {
		if (s.destroyed || !sim.ships.contains(s)) return;
		takeControl(s, true);
	}

	/** Your nearest live ship (not an ally's), else null. */
	private Ship3D pickNextPlayer(Ship3D from) {
		Ship3D best = null;
		float bestD = Float.MAX_VALUE;
		for (Map.Entry<ShipAPI, Ship3D> e : twins.entrySet()) {
			Ship3D s = e.getValue();
			if (s.owner != 0 || s.destroyed || s.fighter || s.fixedPart() || !sim.ships.contains(s) || e.getKey().isAlly()) continue;
			float d = from != null ? s.pos.distance(from.pos) : -s.hullMax; // at the start: the biggest ship
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return best;
	}

	private void takeControl(Ship3D s, boolean announce) {
		Ship3D old = sim.player;
		if (old != null && old != s && !old.destroyed && sim.ships.contains(old)) {
			old.ai = new ShipAI3D(deployed++);
			old.ai.homeDir.set(0f, 0f, 1f);
		}
		s.ai = null;
		sim.setPlayer(s);
		if (announce) sim.camera.glideTo(s, 0.9f); // fly over to the new ship rather than cut
		ShipAPI puppet = s.hostRef instanceof ShipAPI ? (ShipAPI) s.hostRef : null;
		if (puppet != null && engine.getPlayerShip() != puppet) {
			try {
				engine.setPlayerShipExternal(puppet);
			} catch (Throwable t) {
				log.warn("S3D: could not make " + s.name + " vanilla's player ship", t);
			}
		}
		if (announce) log.info("S3D: command transferred to " + s.name); // no toast (no-text HUD)
	}

	// ---- hand-back -----------------------------------------------------------------------------

	/** Gives the battle back to vanilla 2D: puppets get their collision and AI back where they stand. */
	private void handBack(String why) {
		if (handedBack) return;
		handedBack = true;
		int n = 0;
		for (Map.Entry<ShipAPI, CollisionClass> e : frozen.entrySet()) {
			ShipAPI ship = e.getKey();
			if (finished.contains(ship) || ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
			ship.setCollisionClass(e.getValue());
			ship.resetDefaultAI();
			ship.setHoldFire(false);
			ship.setShipSystemDisabled(false);
			n++;
		}
		super.shutdown();
		log.info("S3D: battle handed back to vanilla 2D (" + why + "), " + n + " ships restored");
		if (engine.getCombatUI() != null) engine.getCombatUI().addMessage(0, "Starsector 3D: battle continues in vanilla 2D");
	}

	@Override
	boolean done() {
		return handedBack;
	}

	@Override
	void shutdown() {
		handBack("shutdown");
	}

	// ---- UI ------------------------------------------------------------------------------------

	@Override
	protected boolean yieldToVanilla() {
		return engine.isCombatOver();
	}

	@Override
	protected boolean passThrough(int key) {
		return key == Keyboard.KEY_TAB; // vanilla command map: orders, deploying reinforcements
	}

	@Override
	protected boolean runWhileVanillaUi() {
		return true; // vanilla's clock (CR, command points) keeps running behind the command map
	}

	@Override
	protected void togglePause() {
		engine.setPaused(!engine.isPaused());
	}

	@Override
	protected boolean onSessionKey(int key) {
		if (key == cfg.key("wings")) {
			toggleWings();
			return true;
		}
		if (key != cfg.key("handBack")) return false;
		if (handBackArmed) {
			handBack("player pressed " + k("handBack") + " twice");
		} else {
			handBackArmed = true;
			handBackTimer = 2f;
			toast("Press " + k("handBack") + " again to finish this battle in vanilla 2D");
		}
		return true;
	}

	/** Your carrier's wings: attack (your target first) or regroup around you. Vanilla's flag follows. */
	private void toggleWings() {
		Ship3D p = sim.player;
		ShipAPI puppet = p != null && p.hostRef instanceof ShipAPI ? (ShipAPI) p.hostRef : null;
		if (puppet == null || !puppet.hasLaunchBays()) {
			toast("No fighter wings on this ship");
			return;
		}
		p.wingsRegroup = !p.wingsRegroup; // the weapon panel's WINGS line shows it
		puppet.setPullBackFighters(p.wingsRegroup);
	}

	@Override
	protected void fillAlerts(List<String> alerts) {
		Ship3D p = sim.player;
		if (p == null) return;
		if (p.destroyed || !sim.ships.contains(p)) return; // no prompt: you pick your next ship in the tactical view
		if (p.retreatOrdered) {
			float toGo = edgeZ - p.pos.z;
			alerts.add(toGo > 0f ? String.format("RETREAT: fly back past your starting side, %.0f to go", toGo) : "RETREAT: leaving the battle");
		}
	}
}
