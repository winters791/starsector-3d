package s3d.host;

import java.util.List;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/**
 * Registered for every battle via data/config/settings.json. Decides whether this battle is
 * played in 3D (campaign battles, missions and the refit / main menu simulator, unless
 * battle.enabled is off; never the title screen's background battle) and forwards the frame to
 * the session. Any exception hands the battle back to vanilla 2D for the
 * rest of the fight instead of crashing.
 */
public class CombatHostPlugin extends BaseEveryFrameCombatPlugin {
	private static final Logger log = Global.getLogger(CombatHostPlugin.class);
	private CombatEngineAPI engine;
	private enum Mode { VANILLA, BATTLE }

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
		boolean titleScreen = Global.getCurrentState() == GameState.TITLE; // the main menu's background fight
		String kind = mission != null ? "mission" : campaignSim || engine.isSimulation() ? "simulation" : "campaign battle";
		mode = Mode.VANILLA;
		battleCfg = null;
		String why = "";
		if (titleScreen) {
			why = " (title screen)";
		} else {
			battleCfg = TuningLoader.load();
			if (battleCfg.tuning.battleEnabled) mode = Mode.BATTLE;
			else why = " (battle.enabled is off in s3d_tuning.json)";
		}
		BattleLog.battleInit(engine, mode == Mode.BATTLE ? "3D " + kind : null);
		log.info("S3D: battle start (mission=" + mission + ", campaign=" + engine.isInCampaign() + ", campaignSim=" + campaignSim
				+ ", simulation=" + engine.isSimulation() + ") -> " + (mode == Mode.BATTLE ? "3D " + kind : "vanilla 2D" + why));
		if (mode != Mode.VANILLA) ModDataLoader.reload(); // other mods' data/config/s3d files, re-read so edits show without a restart
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
			BattleLog.tick();
			if (session == null) {
				String unsupported = BattleSession.unsupported(engine, battleCfg.tuning);
				if (unsupported != null) {
					log.info("S3D: this battle stays vanilla 2D: " + unsupported);
					BattleLog.end("stayed vanilla 2D: " + unsupported);
					mode = Mode.VANILLA;
					return;
				}
				if (!BattleSession.ready(engine)) return;
				session = new BattleSession(engine, battleCfg);
			}
			if (session.done()) {
				BattleLog.end("handed back to vanilla 2D");
				return;
			}
			session.advance(amount);
			if (engine.isCombatOver()) BattleLog.end("battle over");
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
		String state = "";
		try {
			if (session != null) state = "; " + session.state();
		} catch (Throwable ignored) {
			// the state is a nice-to-have
		}
		BattleLog.failing("3D switched off after an error in " + where + state);
		log.error("S3D: 3D layer disabled after an error in " + where + "; the battle continues in vanilla 2D", t); // writes the bug-report files
		if (session != null) {
			try {
				session.shutdown();
			} catch (Throwable t2) {
				log.error("S3D: hand-back to vanilla failed too", t2);
			}
		}
		if (engine != null && engine.getCombatUI() != null) {
			engine.getCombatUI().addMessage(0, "Starsector 3D hit an error and switched off (saved to saves/common/s3d_last_error.txt.data)");
		}
	}
}
