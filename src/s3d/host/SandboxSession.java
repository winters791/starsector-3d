package s3d.host;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.mission.FleetSide;

import s3d.core.ReferenceLineup;
import s3d.core.ScriptedMover;
import s3d.core.Ship3D;
import s3d.math.Vec3;

/**
 * The flight sandbox mission, hosted inside a vanilla mission battle. The vanilla ships are
 * parked, frozen puppets; the player flies a 3D copy of their flagship among inert 3D copies of
 * the enemy ships (scale references) and a circling target drone. Nothing is fed back to vanilla.
 */
final class SandboxSession extends Session3D {
	private static final Logger log = Global.getLogger(SandboxSession.class);

	private final Set<ShipAPI> frozen = new HashSet<>();
	private final Map<ShipAPI, Vector2f> parking = new HashMap<>();
	private final Map<String, ShipAPI> spawnedHulls = new HashMap<>();
	private final List<Ship3D> refs = new java.util.ArrayList<>();
	private int hullIndex;
	private int parkedPlayer, parkedEnemy;

	static boolean ready(CombatEngineAPI engine) {
		ShipAPI p = engine.getPlayerShip();
		if (p == null || p.isShuttlePod() || p.isFighter() || p.isHulk() || p.getHullSpec() == null) return false;
		CombatUIAPI ui = engine.getCombatUI();
		return ui == null || !ui.isShowingDeploymentDialog();
	}

	SandboxSession(CombatEngineAPI engine) throws Exception {
		super(engine, TuningLoader.load());
		engine.setDoNotEndCombat(true);

		ShipAPI flagship = engine.getPlayerShip();
		Ship3D player = ShipBridge.toShip3D(flagship, "player", cfg.tuning);
		sim.addShip(player);
		sim.setPlayer(player);
		String variant = flagship.getVariant() != null ? flagship.getVariant().getHullVariantId() : null;
		hullIndex = Math.max(0, cfg.tuning.sandboxHulls.indexOf(variant));
		if (variant != null) spawnedHulls.put(variant, flagship);

		spawnReferenceLineup();
		spawnMovingTarget();
		freezePuppets();
		log.info("S3D: sandbox started with " + sim.ships.size() + " ships; tuning #" + cfg.hash
				+ (cfg.error != null ? " (" + cfg.error + ")" : ""));
	}

	/**
	 * Spawns the configured reference hulls on the enemy side (so the set doesn't depend on what the
	 * enemy AI chooses to deploy) and parks their 3D copies side by side, centred, at one distance in
	 * front of the player, smallest to largest, so relative size reads directly. Ships the AI deploys
	 * on its own stay frozen puppets with no 3D copy.
	 */
	private void spawnReferenceLineup() {
		int i = 0;
		for (String variantId : cfg.tuning.sandboxReferences) {
			try {
				if (Global.getSettings().getVariant(variantId) == null) {
					log.warn("S3D: unknown reference variant " + variantId);
					continue;
				}
				ShipAPI puppet = engine.getFleetManager(FleetSide.ENEMY)
						.spawnShipOrWing(variantId, new Vector2f(-6000f + 400f * i, 7000f), 270f);
				Ship3D ref = ShipBridge.toShip3D(puppet, "ref" + i, cfg.tuning);
				ref.thrustVisual = 0.3f;
				sim.addShip(ref); // applies the per-class scale, which the layout needs
				refs.add(ref);
				i++;
			} catch (Throwable t) {
				log.error("S3D: could not spawn reference " + variantId + ", skipping it", t);
			}
		}
		layoutReferences();
	}

	/** A frigate flying a tilted circle behind the lineup, for practising leads. */
	private void spawnMovingTarget() {
		String variantId = cfg.tuning.sandboxMovingTarget;
		if (variantId == null || variantId.isEmpty() || Global.getSettings().getVariant(variantId) == null) return;
		try {
			ShipAPI puppet = engine.getFleetManager(FleetSide.ENEMY).spawnShipOrWing(variantId, new Vector2f(4000f, 7000f), 270f);
			Ship3D drone = ShipBridge.toShip3D(puppet, "drone", cfg.tuning);
			drone.name = "Target Drone";
			sim.addShip(drone);
			float speed = Math.max(40f, drone.params.maxSpeed * 0.7f);
			drone.mover = new ScriptedMover(new Vec3(0f, 250f, -cfg.tuning.sandboxLineupDepth - 1100f), 800f, speed, 25f);
			drone.mover.step(drone, 0.001f);
			log.info("S3D: moving target " + variantId + " circling at " + Math.round(speed) + " u/s");
		} catch (Throwable t) {
			log.error("S3D: could not spawn moving target " + variantId, t);
		}
	}

	/** Centres the reference hulls side by side; re-run after a tuning reload changes their scale. */
	private void layoutReferences() {
		float gap = 150f, width = -gap;
		for (Ship3D r : refs) width += 2f * Math.max(r.radius, 30f) + gap;
		ReferenceLineup lineup = new ReferenceLineup(-width * 0.5f, cfg.tuning.sandboxLineupDepth, gap);
		for (Ship3D r : refs) lineup.place(r);
		log.info("S3D: reference lineup of " + refs.size() + " hulls, " + Math.round(width) + " units wide, "
				+ Math.round(cfg.tuning.sandboxLineupDepth) + " ahead");
	}

	@Override
	protected boolean onSessionKey(int key) {
		if (key == cfg.key("nextHull")) switchHull(1);
		else if (key == cfg.key("prevHull")) switchHull(-1);
		else if (key == cfg.key("resetTargets")) {
			input.resetTargets = true;
			toast("Everything repaired and rearmed");
		} else if (key == cfg.key("incomingFire")) {
			input.toggleIncoming = true;
			toast(sim.incomingFire ? "Incoming fire off" : "Incoming fire ON: enemy missile launchers fire at you");
		} else return false;
		return true;
	}

	@Override
	protected void fillAlerts(List<String> alerts) {
		// The sandbox has nothing to warn about; the hull name shows as a toast when you switch.
	}

	@Override
	protected void afterTuningReload(boolean meshChanged) {
		layoutReferences();
	}

	/** Keeps every vanilla ship parked, still, intangible and AI-less in the hidden 2D battle. */
	@Override
	protected void managePuppets(float realDt) {
		freezePuppets();
	}

	private void freezePuppets() {
		for (ShipAPI s : engine.getShips()) {
			if (!frozen.contains(s)) {
				frozen.add(s);
				s.setCollisionClass(CollisionClass.NONE);
				if (s != engine.getPlayerShip()) s.setShipAI(new InertShipAI());
				s.setHoldFire(true);
				Vector2f park = s.getOwner() == 0
						? new Vector2f(-6000f + 350f * parkedPlayer++, -6500f)
						: new Vector2f(-6000f + 350f * parkedEnemy++, 6500f);
				parking.put(s, park);
			}
			Vector2f park = parking.get(s);
			if (park != null) s.getLocation().set(park);
			s.getVelocity().set(0f, 0f);
			s.setAngularVelocity(0f);
		}
		if (overlay) {
			// Point the hidden 2D camera at empty space so vanilla draws as little as possible.
			engine.getViewport().set(-500f, -500f, 1000f, 1000f);
		}
	}

	private void switchHull(int delta) {
		List<String> hulls = cfg.tuning.sandboxHulls;
		if (hulls.isEmpty()) return;
		hullIndex = Math.floorMod(hullIndex + delta, hulls.size());
		String variantId = hulls.get(hullIndex);
		try {
			ShipAPI puppet = spawnedHulls.get(variantId);
			if (puppet == null || puppet.isHulk()) {
				if (Global.getSettings().getVariant(variantId) == null) {
					toast("Unknown variant: " + variantId);
					return;
				}
				puppet = engine.getFleetManager(FleetSide.PLAYER).spawnShipOrWing(variantId, new Vector2f(0f, -7000f), 90f);
				spawnedHulls.put(variantId, puppet);
				freezePuppets();
			}
			Ship3D old = sim.player;
			Ship3D next = ShipBridge.toShip3D(puppet, "player-" + variantId, cfg.tuning);
			next.pos.set(old.pos);
			next.rot.set(old.rot);
			next.throttle = old.throttle;
			sim.ships.remove(old);
			sim.addShip(next);
			sim.setPlayer(next);
			toast(next.hullName);
		} catch (Throwable t) {
			log.error("S3D: could not switch to " + variantId, t);
			toast("Could not switch to " + variantId + " (see starsector.log)");
		}
	}
}
