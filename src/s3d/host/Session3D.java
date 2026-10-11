package s3d.host;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import s3d.core.FlightParams;
import s3d.core.PilotInput;
import s3d.core.Ship3D;
import s3d.core.Sim;
import s3d.core.Tuning;
import s3d.core.WeaponMount;
import s3d.render.GLStateGuard;
import s3d.render.HudRenderer;
import s3d.render.Renderer3D;
import s3d.render.ShipModel;

/**
 * What every 3D battle has in common, whether it's the sandbox mission or a real campaign battle:
 * the 3D sim, player input, rendering over the hidden vanilla battle, the HUD, the perf readout,
 * yielding to vanilla menus, and tuning reloads. Subclasses decide which ships exist and what
 * happens to the vanilla puppets.
 */
abstract class Session3D {
	private static final Logger log = Global.getLogger(Session3D.class);
	protected static final Renderer3D RENDERER = new Renderer3D();

	protected final CombatEngineAPI engine;
	protected final Sim sim;
	protected TuningLoader cfg;
	protected final PilotInput input = new PilotInput();
	private final HudRenderer.Info hud = new HudRenderer.Info();

	protected boolean overlay = true;
	private boolean showHelp, showPerf, showHud = true;
	private final boolean[] groupKeyDown = new boolean[7];
	/** Top-row and numpad 1-7 (both select weapon groups). */
	private static final int[] NUMPAD = {Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3, Keyboard.KEY_NUMPAD4,
			Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6, Keyboard.KEY_NUMPAD7};
	private int groupLogBudget = 30;
	private boolean lastVanillaUi, loggedFirstFrame;
	private float statRefreshTimer;
	private String toast;
	private float toastTime;

	// perf
	private long lastAdvanceNanos, lastRenderNanos, perfLogNanos;
	private float frameMs = 16.7f, worstFrameMs, simMs, renderMs;
	private int framesSinceLog;
	private float worstSinceLog, sumSinceLog;

	Session3D(CombatEngineAPI engine, TuningLoader cfg) {
		this.engine = engine;
		this.cfg = cfg;
		sim = new Sim(cfg.tuning);
		showPerf = cfg.tuning.perfDefault;
		sim.combat.mergeNumbers = true;
		sim.combat.sounds.enabled = cfg.tuning.sound;
		try {
			sim.combat.maxArmorDamageReduction = Global.getSettings().getFloat("maxArmorDamageReduction");
			sim.combat.minArmorFraction = Global.getSettings().getFloat("minArmorFraction");
			sim.combat.dpsToHitStrengthMult = Global.getSettings().getFloat("dpsToHitStrengthMult");
		} catch (Throwable t) {
			log.warn("S3D: armor settings unavailable, using 0.85 / 0.05", t);
		}
		engine.getViewport().setExternalControl(true);
	}

	// ---- hooks -------------------------------------------------------------------------------

	/** Every frame, even while vanilla UI is showing: keep the puppets in line. */
	protected abstract void managePuppets(float realDt);

	/** After the sim step (only while the 3D view is up). */
	protected void afterSim(float dt, float realDt) {}

	/** Session-specific keys; return true if handled. */
	protected abstract boolean onSessionKey(int key);

	/** Keys vanilla must still receive while the 3D view is up (Esc always does). */
	protected boolean passThrough(int key) {
		return false;
	}

	/** Short warnings for the player (shown above the status line); usually none. */
	protected abstract void fillAlerts(List<String> alerts);

	protected abstract String[] sessionHelpLines();

	/** Extra reasons to stay out of the way (e.g. the battle is over). */
	protected boolean yieldToVanilla() {
		return false;
	}

	/** Keep the 3D battle running (hands off the controls) while vanilla's menus are up. */
	protected boolean runWhileVanillaUi() {
		return false;
	}

	protected void togglePause() {
		input.togglePause = true;
	}

	protected void afterTuningReload(boolean meshChanged) {}

	/** True once the session has handed the battle back to vanilla for good. */
	boolean done() {
		return false;
	}

	// ---- input -------------------------------------------------------------------------------

	void processInput(List<InputEventAPI> events) {
		boolean vanillaUi = vanillaUiActive() || yieldToVanilla();
		for (InputEventAPI e : events) {
			if (e.isKeyboardEvent() && e.isKeyDownEvent() && groupKey(e.getEventValue()) >= 0 && groupLogBudget > 0) {
				groupLogBudget--;
				log.info("S3D: weapon group key " + (groupKey(e.getEventValue()) + 1) + " event (consumed=" + e.isConsumed()
						+ ", vanillaUi=" + vanillaUi + ", overlay=" + overlay + ")");
			}
			if (e.isConsumed()) continue;
			if (e.isKeyboardEvent()) {
				int key = e.getEventValue();
				if (key == Keyboard.KEY_ESCAPE || vanillaUi || passThrough(key)) continue;
				if (e.isKeyDownEvent() && !e.isRepeat()) onKeyPressed(key);
				if (overlay || key == cfg.key("toggleOverlay")) e.consume();
			} else if (e.isMouseEvent() && overlay && !vanillaUi) {
				if (e.isMouseScrollEvent()) input.wheelNotches += Math.signum(e.getEventValue());
				if (e.isRMBDownEvent()) input.toggleShield = true; // vanilla's shield button
				if (!e.isMouseMoveEvent()) e.consume();
			}
		}
	}

	private void onKeyPressed(int key) {
		if (key == cfg.key("toggleOverlay")) {
			overlay = !overlay;
			engine.getViewport().setExternalControl(overlay);
			toast(overlay ? "3D view on" : "3D view off (vanilla debug view)");
			return;
		}
		if (!overlay) return;
		if (key == cfg.key("allStop")) input.allStop = true;
		else if (key == cfg.key("autoLevel")) {
			input.toggleAutoLevel = true;
			toast(sim.controls.autoLevel ? "Auto-level off" : "Auto-level on");
		} else if (key == cfg.key("tactical")) input.toggleTactical = true;
		else if (key == cfg.key("pause")) togglePause();
		else if (key == cfg.key("help")) showHelp = !showHelp;
		else if (key == cfg.key("perfHud")) showPerf = !showPerf;
		else if (cfg.isKey("toggleHud", key)) {
			showHud = !showHud;
			if (showHud) toast("HUD on");
		} else if (key == cfg.key("reloadTuning")) reloadTuning();
		else if (key == cfg.key("target")) input.selectTarget = true;
		else if (key == cfg.key("vent")) input.vent = true;
		else if (key == cfg.key("system")) {
			input.useSystem = true;
			if (sim.player != null && sim.player.system != null && !sim.player.system.supported()) toast(sim.player.system.name + " isn't in 3D yet");
		}
		else if (key == cfg.key("spectate")) {
			input.toggleSpectate = true;
			toast(sim.spectating ? "Back in control" : "Spectating: your ship flies itself");
		} else if (sim.spectating && key == cfg.key("nextHull")) input.cycleView = 1;
		else if (sim.spectating && key == cfg.key("prevHull")) input.cycleView = -1;
		else if (groupKey(key) >= 0) input.selectGroup = groupKey(key);
		else onSessionKey(key);
	}

	private void readHeldInput() {
		input.throttleUp = held("throttleUp");
		input.throttleDown = held("throttleDown");
		input.strafeLeft = held("strafeLeft");
		input.strafeRight = held("strafeRight");
		input.strafeUp = held("strafeUp");
		input.strafeDown = held("strafeDown");
		input.rollLeft = held("rollLeft");
		input.rollRight = held("rollRight");
		input.freeLook = held("freeLook") || Mouse.isButtonDown(2);
		input.fire = Mouse.isButtonDown(0) && !sim.spectating;
		// Weapon groups 1-7 are polled: vanilla's combat UI can take those key events before we see them.
		for (int i = 0; i < groupKeyDown.length; i++) {
			boolean down = Keyboard.isKeyDown(Keyboard.KEY_1 + i) || Keyboard.isKeyDown(NUMPAD[i]);
			if (down && !groupKeyDown[i]) {
				input.selectGroup = i;
				if (groupLogBudget > 0) {
					groupLogBudget--;
					log.info("S3D: weapon group key " + (i + 1) + " seen by polling");
				}
			}
			groupKeyDown[i] = down;
		}
		input.mouseX = Mouse.getX();
		input.mouseY = Mouse.getY();
		input.screenW = Math.max(1, Display.getWidth());
		input.screenH = Math.max(1, Display.getHeight());
	}

	private static final int MAX_SOUNDS_PER_FRAME = 24, MAX_PER_ID = 3;
	private final java.util.Set<String> badSounds = new java.util.HashSet<>();
	private final java.util.Map<String, Integer> perId = new java.util.HashMap<>();

	/**
	 * Plays the 3D battle's sounds through the game's sound player. Each sound is placed around
	 * vanilla's listener (the hidden 2D camera's centre) by where it is relative to the 3D camera:
	 * sideways = left/right, distance = distance, scaled by sound.distanceScale. Capped per frame and
	 * per sound id so a fleet fight doesn't turn into noise; ids the game rejects are muted.
	 */
	private void playSounds() {
		s3d.core.SoundEvents ev = sim.combat.sounds;
		if (!ev.enabled) {
			ev.clear();
			return;
		}
		try {
			com.fs.starfarer.api.SoundPlayerAPI sp = Global.getSoundPlayer();
			org.lwjgl.util.vector.Vector2f centre = engine.getViewport().getCenter();
			s3d.math.Vec3 right = sim.camera.rot.right(new s3d.math.Vec3()), fwd = sim.camera.rot.forward(new s3d.math.Vec3());
			org.lwjgl.util.vector.Vector2f zero = new org.lwjgl.util.vector.Vector2f();
			float scale = cfg.tuning.soundDistanceScale, range = cfg.tuning.soundRange, vol = cfg.tuning.soundVolume;
			perId.clear();
			int played = 0;
			for (s3d.core.SoundEvents.Shot s : ev.shots) {
				if (played >= MAX_SOUNDS_PER_FRAME) break;
				if (badSounds.contains(s.id)) continue;
				s3d.math.Vec3 rel = new s3d.math.Vec3(s.pos).sub(sim.camera.pos);
				if (rel.length() > range) continue;
				int count = perId.merge(s.id, 1, Integer::sum);
				if (count > MAX_PER_ID) continue;
				try {
					sp.playSound(s.id, s.pitch, s.volume * vol, new org.lwjgl.util.vector.Vector2f(centre.x + rel.dot(right) * scale, centre.y + rel.dot(fwd) * scale), zero);
					played++;
				} catch (Throwable t) {
					badSounds.add(s.id);
					log.warn("S3D: sound " + s.id + " can't be played (" + t + "); muted");
				}
			}
			for (s3d.core.SoundEvents.Loop l : ev.loops) {
				if (badSounds.contains(l.id)) continue;
				s3d.math.Vec3 rel = new s3d.math.Vec3(l.pos).sub(sim.camera.pos);
				if (rel.length() > range) continue;
				try {
					sp.playLoop(l.id, l.key, l.pitch, l.volume * vol, new org.lwjgl.util.vector.Vector2f(centre.x + rel.dot(right) * scale, centre.y + rel.dot(fwd) * scale), zero);
				} catch (Throwable t) {
					badSounds.add(l.id);
					log.warn("S3D: sound loop " + l.id + " can't be played (" + t + "); muted");
				}
			}
		} catch (Throwable t) {
			ev.enabled = false;
			log.error("S3D: sound switched off after an error", t);
		}
		ev.clear();
	}

	/** Neutral input: no keys, no trigger, reticle centred (stick at rest). */
	private void handsOff() {
		input.throttleUp = input.throttleDown = input.strafeLeft = input.strafeRight = false;
		input.strafeUp = input.strafeDown = input.rollLeft = input.rollRight = false;
		input.freeLook = input.fire = false;
		input.screenW = Math.max(1, Display.getWidth());
		input.screenH = Math.max(1, Display.getHeight());
		input.mouseX = input.screenW * 0.5f;
		input.mouseY = input.screenH * 0.5f;
	}

	/** 0-6 for top-row or numpad 1-7, else -1. */
	private static int groupKey(int key) {
		if (key >= Keyboard.KEY_1 && key <= Keyboard.KEY_7) return key - Keyboard.KEY_1;
		for (int i = 0; i < NUMPAD.length; i++) if (key == NUMPAD[i]) return i;
		return -1;
	}

	/** Toast + log after a weapon group key, so a switch (or why it didn't happen) is visible. */
	private void announceGroup(int requested, int before) {
		Ship3D p = sim.player;
		if (p == null) return;
		if (requested >= p.groups.size()) {
			toast("No weapon group " + (requested + 1) + " (this ship has " + p.groups.size() + ")");
		} else {
			s3d.core.WeaponGroup3D g = p.groups.get(p.selectedGroup);
			toast("Group " + (p.selectedGroup + 1) + ": " + g.label() + (g.autofire ? "  (also autofires)" : ""));
		}
		if (groupLogBudget > 0) {
			groupLogBudget--;
			log.info("S3D: weapon group " + (requested + 1) + " requested; selected " + (before + 1) + " -> " + (p.selectedGroup + 1)
					+ " of " + p.groups.size());
		}
	}

	private boolean held(String action) {
		int k = cfg.key(action);
		return k != Keyboard.KEY_NONE && Keyboard.isKeyDown(k);
	}

	protected boolean vanillaUiActive() {
		CombatUIAPI ui = engine.getCombatUI();
		boolean dialog = engine.isUIShowingDialog();
		boolean command = ui != null && ui.isShowingCommandUI();
		boolean deploy = ui != null && ui.isShowingDeploymentDialog();
		boolean v = dialog || command || deploy;
		if (v != lastVanillaUi) {
			log.info("S3D: vanilla UI " + (v ? "opened (dialog=" + dialog + " commandUI=" + command + " deployment=" + deploy
					+ "), 3D view yields" : "closed, 3D view resumes"));
			lastVanillaUi = v;
		}
		return v;
	}

	// ---- per frame -----------------------------------------------------------------------------

	void advance(float amount) {
		long now = System.nanoTime();
		float realDt = lastAdvanceNanos == 0 ? 0f : (now - lastAdvanceNanos) / 1e9f;
		lastAdvanceNanos = now;

		managePuppets(realDt);
		if (done()) return;
		statRefreshTimer -= realDt;
		if (statRefreshTimer <= 0f) {
			statRefreshTimer = 0.5f;
			refreshFlightParams();
		}
		if (!overlay || vanillaUiActive() || yieldToVanilla()) {
			CursorHider.restore();
			input.clearPressed();
			if (overlay && !yieldToVanilla() && runWhileVanillaUi()) {
				handsOff();
				float dt = engine.isPaused() ? 0f : amount;
				sim.update(input, dt, realDt);
				afterSim(dt, realDt);
				playSounds();
			}
			return;
		}
		CombatUIAPI ui = engine.getCombatUI();
		if (ui != null) ui.setDisablePlayerShipControlOneFrame(true);

		readHeldInput();
		float dt = engine.isPaused() ? 0f : amount;
		int requested = input.selectGroup, before = sim.player != null ? sim.player.selectedGroup : -1;
		sim.update(input, dt, realDt);
		input.clearPressed();
		if (requested >= 0) announceGroup(requested, before);
		afterSim(dt, realDt);
		playSounds();

		if (toastTime > 0f) toastTime -= realDt;
		simMs = (System.nanoTime() - now) / 1e6f;
	}

	void render() {
		if (done() || !overlay || vanillaUiActive() || yieldToVanilla()) {
			lastRenderNanos = 0; // don't count the time spent in vanilla menus as a slow frame
			CursorHider.restore();
			return;
		}
		if (cfg.tuning.hideGameCursor) CursorHider.hide();
		else CursorHider.restore();
		long t0 = System.nanoTime();
		if (lastRenderNanos != 0) {
			float ms = (t0 - lastRenderNanos) / 1e6f;
			frameMs += (ms - frameMs) * 0.1f;
			worstFrameMs = Math.max(worstFrameMs * 0.995f, ms);
			framesSinceLog++;
			sumSinceLog += ms;
			worstSinceLog = Math.max(worstSinceLog, ms);
		}
		lastRenderNanos = t0;

		int w = Math.max(1, Display.getWidth()), h = Math.max(1, Display.getHeight());
		if (!loggedFirstFrame) {
			loggedFirstFrame = true;
			java.nio.IntBuffer vp = org.lwjgl.BufferUtils.createIntBuffer(16);
			org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_VIEWPORT, vp);
			log.info("S3D: first 3D frame: display " + w + "x" + h + ", game viewport " + vp.get(0) + "," + vp.get(1) + " "
					+ vp.get(2) + "x" + vp.get(3) + ", screenScaleMult " + Global.getSettings().getScreenScaleMult()
					+ ", nativeCursor " + (Mouse.getNativeCursor() != null));
		}
		try (GLStateGuard g = GLStateGuard.push()) {
			RENDERER.init();
			RENDERER.renderScene(sim, w, h, g.previousFbo());
			if (showHud) {
				fillHud();
				RENDERER.renderHud(sim, input, hud, w, h);
			}
		}
		renderMs = (System.nanoTime() - t0) / 1e6f;
		logPerfPeriodically(t0);
	}

	private void fillHud() {
		hud.alerts.clear();
		hud.perf.clear();
		fillAlerts(hud.alerts);
		hud.wingsKey = k("wings");
		for (String action : cfg.tuning.keys.keySet()) hud.keyNames.put(action, k(action));
		hud.keyNames.put("freeLook", k("freeLook"));
		if (showPerf) {
			hud.perf.add(String.format("FPS %.0f  (%.1f ms, worst %.1f)", 1000f / Math.max(0.1f, frameMs), frameMs, worstFrameMs));
			hud.perf.add(String.format("sim %.2f ms  render %.2f ms", simMs, renderMs));
			hud.perf.add(String.format("%d ships  %dk tris  MSAA %dx", RENDERER.lastShips, RENDERER.lastTriangles / 1000, Math.max(0, RENDERER.msaaSamples())));
			hud.perf.add("tuning #" + cfg.hash);
		}
		hud.toast = toast;
		hud.toastAlpha = Math.max(0f, Math.min(1f, toastTime));
		hud.showHelp = showHelp;
		hud.helpLines = helpLines();
	}

	private String[] helpLines() {
		List<String> lines = new ArrayList<>(List.of(
				"FLIGHT",
				"  Mouse ............ steer (reticle; further from centre = faster turn)",
				"  Left click ....... fire the selected weapon group",
				"  1 - 7 ............ select weapon group (autofire groups shoot on their own)",
				"  Lead circle ...... put the reticle in it; the green + marks where your guns hit",
				"  " + k("target") + " ................ lock the ship nearest the reticle (else it's automatic)",
				"  " + k("throttleUp") + " / " + k("throttleDown") + " ............ throttle up / down      " + k("allStop") + " ... all stop",
				"  " + k("strafeLeft") + " / " + k("strafeRight") + " ............ strafe left / right",
				"  " + k("strafeUp") + " / " + k("strafeDown") + " ............ strafe up / down",
				"  " + k("rollLeft") + " / " + k("rollRight") + " ............ roll left / right",
				"  " + k("autoLevel") + " ................ auto-level on/off",
				"SHIELDS, FLUX, SYSTEM",
				"  " + k("system") + " ................ ship system",
				"  Right click ...... shield on/off, or phase cloak on phase ships (omni shields face incoming fire;",
				"                     hold " + k("freeLook") + " to aim yours where you look)",
				"  " + k("vent") + " ................ vent flux (no shield or weapons until it's done)",
				"SPECTATE",
				"  " + k("spectate") + " ................ spectate on/off (your ship flies itself meanwhile)",
				"  " + k("prevHull") + " / " + k("nextHull") + " ............ watch the previous / next ship on your side",
				"  " + k("tactical") + " ................ free camera / follow (mouse turns, move keys fly)",
				"CAMERA",
				"  hold " + k("freeLook") + " / middle mouse ... free look",
				"  " + k("tactical") + " ................ tactical view on/off",
				"  wheel ............ zoom"));
		lines.addAll(List.of(sessionHelpLines()));
		lines.addAll(List.of(
				"  " + k("pause") + " ................ pause",
				"  " + k("reloadTuning") + " ................ reload data/config/s3d_tuning.json",
				"  " + k("perfHud") + " ................ performance readout",
				"  " + k("toggleHud") + " ................ hide / show the HUD (reticle, panels, radar)",
				"  " + k("toggleOverlay") + " ................ hide 3D view (debug)",
				"  Esc .............. game menu"));
		return lines.toArray(new String[0]);
	}

	/** Display name(s) for an action's key(s), e.g. "F10 / \ / G". */
	protected String k(String action) {
		String names = cfg.tuning.keys.get(action);
		if (names == null) return "?";
		StringBuilder sb = new StringBuilder();
		for (String name : names.split(",")) {
			if (sb.length() > 0) sb.append(" / ");
			sb.append(keyLabel(name.trim().toUpperCase()));
		}
		return sb.toString();
	}

	private static String keyLabel(String name) {
		switch (name) {
			case "LMENU": return "L-Alt";
			case "SPACE": return "Space";
			case "LBRACKET": return "[";
			case "RBRACKET": return "]";
			case "BACKSLASH": return "\\";
			default: return name;
		}
	}

	private void logPerfPeriodically(long now) {
		if (perfLogNanos == 0) perfLogNanos = now;
		if (now - perfLogNanos < 10_000_000_000L || framesSinceLog == 0) return;
		log.info(String.format("S3D perf: %.1f fps avg, worst frame %.1f ms, sim %.2f ms, render %.2f ms, %d tris, %d ships",
				1000f * framesSinceLog / sumSinceLog, worstSinceLog, simMs, renderMs, RENDERER.lastTriangles, RENDERER.lastShips));
		perfLogNanos = now;
		framesSinceLog = 0;
		sumSinceLog = 0f;
		worstSinceLog = 0f;
	}

	/**
	 * Re-reads movement stats from the live puppets so the 3D ships follow vanilla's numbers
	 * (CR changes now; ship systems and hullmod effects later). Skipped while the deployment
	 * travel drive is running.
	 */
	private void refreshFlightParams() {
		for (Ship3D s : sim.ships) {
			if (!(s.hostRef instanceof ShipAPI) || s.destroyed) continue;
			ShipAPI ship = (ShipAPI) s.hostRef;
			if (ShipBridge.travelDriveActive(ship)) continue;
			// Lower only: CR loss is real, but zero-flux boost and ship systems on the puppet are not.
			FlightParams fresh = ShipBridge.readFlightParams(ship).cappedBy(s.baseParams);
			if (fresh.sameSource(s.params)) continue;
			log.info("S3D: flight stats for " + s.name + " updated to " + fresh);
			s.params = fresh.derive(sim.tuning);
		}
	}

	private void reloadTuning() {
		TuningLoader next = TuningLoader.load();
		boolean meshChanged = !next.tuning.meshSignature().equals(cfg.tuning.meshSignature());
		cfg = next;
		sim.setTuning(next.tuning);
		if (meshChanged) {
			ShipModel.clearCache();
			for (Ship3D s : sim.ships) {
				s.model = null;
				for (WeaponMount m : s.weapons) m.model = m.gunModel = null;
				ShipBridge.refreshGeometry(s, next.tuning);
			}
		}
		afterTuningReload(meshChanged);
		toast(next.error != null ? "Tuning error, defaults in use: " + next.error : "Tuning reloaded #" + next.hash
				+ (meshChanged ? " (meshes rebuilt)" : ""));
	}

	protected void toast(String msg) {
		toast = msg;
		toastTime = 3f;
	}

	void shutdown() {
		CursorHider.restore();
		try {
			engine.getViewport().setExternalControl(false);
		} catch (Throwable ignored) {
			// best effort
		}
	}

	Tuning tuning() {
		return cfg.tuning;
	}
}
