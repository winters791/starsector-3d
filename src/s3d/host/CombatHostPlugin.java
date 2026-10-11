package s3d.host;

import java.util.List;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/**
 * Registered for every battle via data/config/settings.json. Decides whether this battle is
 * played in 3D (the sandbox mission, and real campaign battles unless battle.enabled is off) and
 * forwards the frame to the session. Any exception hands the battle back to vanilla 2D for the
 * rest of the fight instead of crashing.
 */
public class CombatHostPlugin extends BaseEveryFrameCombatPlugin {
	private static final Logger log = Global.getLogger(CombatHostPlugin.class);
	public static final String SANDBOX_MISSION = "s3d_sandbox";

	private CombatEngineAPI engine;
	private enum Mode { VANILLA, SANDBOX, CAMPAIGN }

	private Session3D session;
	private Mode mode = Mode.VANILLA;
	private TuningLoader battleCfg;
	private boolean failed;

	@Override
	public void init(CombatEngineAPI engine) {
		this.engine = engine;
		session = null; // in case the game reuses plugin instances across battles
		failed = false;
		CursorHider.restore(); // never leave a previous battle's hidden cursor behind
		String mission = engine.isMission() ? engine.getMissionId() : null;
		boolean campaignSim = engine.isInCampaignSim();
		mode = Mode.VANILLA;
		battleCfg = null;
		String why = "";
		if (SANDBOX_MISSION.equals(mission)) {
			mode = Mode.SANDBOX;
		} else if (engine.isInCampaign() && !campaignSim && !engine.isSimulation() && !engine.isMission()) {
			battleCfg = TuningLoader.load();
			if (battleCfg.tuning.battleEnabled) mode = Mode.CAMPAIGN;
			else why = " (battle.enabled is off in s3d_tuning.json)";
		}
		log.info("S3D: battle start (mission=" + mission + ", campaign=" + engine.isInCampaign() + ", campaignSim=" + campaignSim
				+ ", simulation=" + engine.isSimulation() + ") -> "
				+ (mode == Mode.SANDBOX ? "3D sandbox" : mode == Mode.CAMPAIGN ? "3D campaign battle" : "vanilla 2D" + why));
	}

	@Override
	public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
		if (session == null || failed || session.done()) return;
		try {
			session.processInput(events);
		} catch (Throwable t) {
			fail("input", t);
		}
	}

	@Override
	public void advance(float amount, List<InputEventAPI> events) {
		if (mode == Mode.VANILLA || failed) return;
		try {
			if (session == null) {
				if (mode == Mode.SANDBOX) {
					if (!SandboxSession.ready(engine)) return;
					session = new SandboxSession(engine);
				} else {
					String unsupported = BattleSession.unsupported(engine, battleCfg.tuning);
					if (unsupported != null) {
						log.info("S3D: this battle stays vanilla 2D: " + unsupported + " (not supported in 3D yet)");
						mode = Mode.VANILLA;
						return;
					}
					if (!BattleSession.ready(engine)) return;
					session = new BattleSession(engine, battleCfg);
				}
			}
			if (session.done()) return;
			session.advance(amount);
		} catch (Throwable t) {
			fail("advance", t);
		}
	}

	@Override
	public void renderInUICoords(ViewportAPI viewport) {
		if (session == null || failed || session.done()) return;
		try {
			session.render();
		} catch (Throwable t) {
			fail("render", t);
		}
	}

	private void fail(String where, Throwable t) {
		failed = true;
		log.error("S3D: 3D layer disabled after an error in " + where + "; the battle continues in vanilla 2D", t);
		if (session != null) {
			try {
				session.shutdown();
			} catch (Throwable t2) {
				log.error("S3D: hand-back to vanilla failed too", t2);
			}
		}
		if (engine != null && engine.getCombatUI() != null) {
			engine.getCombatUI().addMessage(0, "Starsector 3D hit an error and switched off (details in starsector.log)");
		}
	}
}
