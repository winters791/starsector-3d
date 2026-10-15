package s3d.host;

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
import s3d.render.FxSprites;
import s3d.render.GameFiles;
import s3d.render.GLStateGuard;
import s3d.render.HudRenderer;
import s3d.render.Renderer3D;
import s3d.render.ShipModel;

/**
 * What every 3D battle has in common, campaign battle, mission or simulator:
 * the 3D sim, player input, rendering over the hidden vanilla battle, the HUD, the perf readout,
 * yielding to vanilla menus, and tuning reloads. Subclasses decide which ships exist and what
 * happens to the vanilla puppets.
 */
abstract class Session3D {
	private static final Logger log = Global.getLogger(Session3D.class);
	protected static final Renderer3D RENDERER = new Renderer3D();
	static {
		// Vanilla's sprite sheets for the swarm effects (fragments, shroud blobs, glows), from the game's own files.
		FxSprites.loader = (category, key) -> ShipBridge.loadSprite(Global.getSettings().getSpriteName(category, key));
		// Vanilla's HUD font, weapon and status icons, by path (mods' files included).
		GameFiles.opener = path -> Global.getSettings().openStream(path);
	}

	protected final CombatEngineAPI engine;
	protected final Sim sim;
	protected TuningLoader cfg;
	protected final PilotInput input = new PilotInput();
	private final HudRenderer.Info hud = new HudRenderer.Info();

	protected boolean overlay = true;
	private boolean showPerf, showHud = true;
	private final boolean[] groupKeyDown = new boolean[7];
	private boolean hudKeyDown;
	/** Top-row and numpad 1-7 (both select weapon groups). */
	private static final int[] NUMPAD = {Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3, Keyboard.KEY_NUMPAD4,
			Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6, Keyboard.KEY_NUMPAD7};
	private int groupLogBudget = 30;
	/** Pause = a tap of Cmd (Mac) or Ctrl (Windows/Linux) on its own: down, nothing else pressed, up. */
	private boolean pauseTapDown, pauseTapSpoiled;
	private long pauseTapAt;
	/** Keys already down when Cmd/Ctrl went down (macOS can leave a key "held" after a Cmd shortcut); those don't spoil the tap. */
	private final boolean[] heldAtTap = new boolean[Keyboard.KEYBOARD_SIZE], buttonsAtTap = new boolean[3];
	private static final boolean MAC = String.valueOf(System.getProperty("os.name")).toLowerCase().startsWith("mac");
	private boolean lastVanillaUi, loggedFirstFrame, shipInfoHidden, shipInfoFailed;
	private float statRefreshTimer;
	/** Seconds the 3D scene has stayed up since vanilla called the battle over (see OUTRO_SECONDS). */
	private float outroTime;
	/** After the battle is decided the 3D scene stays up this long (no HUD, no write-back), until vanilla's own screens come up. */
	private static final float OUTRO_SECONDS = 6f;
	/** The 3D view fades in from black over this long when it first comes up. */
	private static final float FADE_IN_SECONDS = 0.6f;
	private long firstRenderNanos;
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
		SkillStats.readRules(sim.combat.skills);
		sim.shuttleModelSource = ShipBridge.hullSprite("graphics/ships/shuttle.png"); // vanilla's Shuttle Pod, the command shuttle
		engine.getViewport().setExternalControl(true);
	}

	// ---- hooks -------------------------------------------------------------------------------

	/** Every frame, even while vanilla UI is showing: keep the puppets in line. */
	protected abstract void managePuppets(float realDt);

	/** After the sim step (only while the 3D view is up). */
	protected void afterSim(float dt, float realDt) {}

	/** Session-specific keys; return true if handled. */
	protected abstract boolean onSessionKey(int key);

	/**
	 * The command shuttle reached this ship: it's yours now. Default: your old ship gets an AI pilot,
	 * the new one loses its own.
	 */
	protected void transferArrived(Ship3D s) {
		Ship3D old = sim.player;
		if (old != null && old != s && !old.destroyed && old.ai == null) old.ai = new s3d.core.ShipAI3D(98);
		s.ai = null;
		sim.setPlayer(s);
	}

	/** Your side's command points left, or -1 if this session has none (the tactical view shows them). */
	protected int commandPoints() {
		return -1;
	}

	/** Orders given in the tactical view (sim.command.issued); the default (no fleet) drops them. */
	protected void carryOutCommands() {
		sim.command.issued.clear();
	}

	/** Keys vanilla must still receive while the 3D view is up (Esc always does). */
	protected boolean passThrough(int key) {
		return false;
	}

	/** Short warnings for the player (shown above the status line); usually none. */
	protected abstract void fillAlerts(List<String> alerts);

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

	/** Where the battle stood, for the bug-report file when the 3D layer hits an error. */
	String state() {
		int yours = 0, enemy = 0, fighters = 0, gone = 0;
		for (Ship3D s : sim.ships) {
			if (s.destroyed) gone++;
			else if (s.fighter) fighters++;
			else if (s.owner == 0) yours++;
			else enemy++;
		}
		Ship3D p = sim.player;
		return String.format("battle time %.1f s; ships: %d yours, %d enemy, %d fighters, %d gone; %d projectiles, %d missiles; flying %s%s",
				sim.time, yours, enemy, fighters, gone, sim.combat.projectiles.size(), sim.combat.missiles.size(),
				p != null ? p.name + " (" + p.hullName + ")" : "nothing", p != null && p.system != null ? ", system " + p.system.id + " " + p.system.state : "");
	}

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
			if (e.isKeyboardEvent() && e.isKeyDownEvent() && !e.isRepeat() && !pauseModifier(e.getEventValue())) pauseTapSpoiled = true; // a shortcut, not a tap
			if (e.isKeyboardEvent()) {
				int key = e.getEventValue();
				if (key == Keyboard.KEY_ESCAPE || vanillaUi || passThrough(key)) continue;
				if (e.isKeyDownEvent() && !e.isRepeat()) onKeyPressed(key);
				if (overlay || key == cfg.key("toggleOverlay")) e.consume();
			} else if (e.isMouseEvent() && overlay && !vanillaUi) {
				if (e.isMouseScrollEvent()) input.wheelNotches += Math.signum(e.getEventValue());
				if (e.isRMBDownEvent() && !sim.camera.tactical) input.toggleShield = true; // vanilla's shield button (tactical view: orders)
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
		else if (key == cfg.key("autoLevel")) input.toggleAutoLevel = true; // the status line shows AUTO-LEVEL
		else if (key == cfg.key("tactical")) input.toggleTactical = true;
		else if (key != Keyboard.KEY_NONE && key == cfg.key("pause")) togglePause(); // an optional extra key; the Cmd/Ctrl tap always works
		else if (key == cfg.key("perfHud")) showPerf = !showPerf;
		else if (key == cfg.key("reloadTuning")) reloadTuning();
		else if (key == cfg.key("target")) input.selectTarget = true;
		else if (key == cfg.key("vent")) {
			input.vent = true;
			Ship3D p = sim.player;
			if (p != null && p.flux != null && !p.flux.canVent()) {
				boolean so = p.hostRef instanceof ShipAPI && ((ShipAPI) p.hostRef).getVariant() != null
						&& ((ShipAPI) p.hostRef).getVariant().hasHullMod("safetyoverrides");
				toast(so ? "Can't vent: Safety Overrides disables venting" : "This ship can't vent flux");
			}
		}
		else if (key == cfg.key("system")) {
			input.useSystem = true;
			if (sim.player != null && sim.player.system != null && !sim.player.system.supported()) toast(sim.player.system.name + " isn't in 3D yet");
		}
		else if (key == cfg.key("spectate")) input.toggleSpectate = true;
		else if (sim.spectating && key == cfg.key("nextHull")) input.cycleView = 1;
		else if (sim.spectating && key == cfg.key("prevHull")) input.cycleView = -1;
		else if (groupKey(key) >= 0 && !autofireModifier()) input.selectGroup = groupKey(key); // with Ctrl/Cmd: polled below
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
		input.mouseLeft = Mouse.isButtonDown(0);
		input.mouseRight = Mouse.isButtonDown(1);
		input.shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
		input.fire = input.mouseLeft && !sim.spectating && !sim.camera.tactical;
		pollPauseTap();
		// Weapon groups 1-7 are polled: vanilla's combat UI can take those key events before we see them.
		for (int i = 0; i < groupKeyDown.length; i++) {
			boolean down = Keyboard.isKeyDown(Keyboard.KEY_1 + i) || Keyboard.isKeyDown(NUMPAD[i]);
			if (down && !groupKeyDown[i]) {
				if (autofireModifier()) input.toggleAutofire = i; // vanilla's Ctrl + number (Cmd on a Mac too)
				else input.selectGroup = i;
				if (groupLogBudget > 0) {
					groupLogBudget--;
					log.info("S3D: weapon group key " + (i + 1) + " seen by polling");
				}
			}
			groupKeyDown[i] = down;
		}
		// Hide UI is polled too: its keys are vanilla's Toggle UI, whose key events vanilla may take first.
		boolean hudDown = false;
		for (int k : cfg.keys("toggleHud")) hudDown |= Keyboard.isKeyDown(k);
		if (hudDown && !hudKeyDown) {
			showHud = !showHud;
			log.info("S3D: 3D UI " + (showHud ? "shown" : "hidden"));
		}
		hudKeyDown = hudDown;
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

	/** Cmd on a Mac, Ctrl elsewhere: the pause key, tapped on its own. */
	private static boolean pauseModifier(int key) {
		return MAC ? key == Keyboard.KEY_LMETA || key == Keyboard.KEY_RMETA : key == Keyboard.KEY_LCONTROL || key == Keyboard.KEY_RCONTROL;
	}

	/**
	 * Pause on a tap of Cmd (Mac) / Ctrl: pressed and let go within half a second with nothing else
	 * pressed meanwhile, so Cmd/Ctrl + 1-7 (autofire), Cmd+Tab and the like never pause. Polled, as
	 * vanilla may take the modifier's own events.
	 */
	private void pollPauseTap() {
		boolean down = MAC ? Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA)
				: Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
		long now = System.nanoTime();
		if (down && !pauseTapDown) {
			pauseTapDown = true;
			pauseTapSpoiled = false;
			pauseTapAt = now;
			for (int k = 1; k < heldAtTap.length; k++) heldAtTap[k] = Keyboard.isKeyDown(k);
			for (int b = 0; b < 3; b++) buttonsAtTap[b] = Mouse.isButtonDown(b); // pausing mid-burst still works
		}
		if (down && (otherInputDown(heldAtTap, buttonsAtTap) || !Display.isActive())) pauseTapSpoiled = true;
		if (!down && pauseTapDown) {
			pauseTapDown = false;
			if (!pauseTapSpoiled && now - pauseTapAt < 500_000_000L && Display.isActive()) togglePause();
		}
	}

	/** A mouse button or a key besides Cmd/Ctrl that wasn't already down at the press, held right now. */
	private static boolean otherInputDown(boolean[] already, boolean[] buttons) {
		for (int b = 0; b < 3; b++) if (Mouse.isButtonDown(b) && !buttons[b]) return true;
		for (int k = 1; k < Keyboard.KEYBOARD_SIZE; k++) {
			if (k == Keyboard.KEY_LMETA || k == Keyboard.KEY_RMETA || k == Keyboard.KEY_LCONTROL || k == Keyboard.KEY_RCONTROL) continue;
			if (Keyboard.isKeyDown(k) && !already[k]) return true;
		}
		return false;
	}

	/** Ctrl (vanilla's autofire modifier) or Cmd held. */
	private static boolean autofireModifier() {
		return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
				|| Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA);
	}

	/** 0-6 for top-row or numpad 1-7, else -1. */
	private static int groupKey(int key) {
		if (key >= Keyboard.KEY_1 && key <= Keyboard.KEY_7) return key - Keyboard.KEY_1;
		for (int i = 0; i < NUMPAD.length; i++) if (key == NUMPAD[i]) return i;
		return -1;
	}

	/** Log after Ctrl/Cmd + number (the weapon panel's AUTOFIRE box shows the result). */
	private void logAutofire(int group) {
		Ship3D p = sim.player;
		if (p == null || group >= p.groups.size()) return;
		log.info("S3D: group " + (group + 1) + " autofire " + (p.groups.get(group).autofire ? "on" : "off"));
	}

	/** Log after a weapon group key, so a switch (or why it didn't happen) can be traced. */
	private void logGroup(int requested, int before) {
		Ship3D p = sim.player;
		if (p == null) return;
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
		boolean slowRefresh = statRefreshTimer <= 0f;
		if (slowRefresh) {
			statRefreshTimer = 0.5f;
			refreshFlightParams();
		}
		refreshSkillStats(slowRefresh);
		if (!overlay || vanillaUiActive() || yieldToVanilla()) {
			boolean outro = inOutro();
			if (outro) outroTime += realDt;
			CursorHider.restore();
			showVanillaShipInfo(!outro);
			input.clearPressed();
			pauseTapDown = false; // a tap needs the 3D view up from press to release
			if (outro || overlay && !yieldToVanilla() && runWhileVanillaUi()) {
				handsOff();
				float dt = engine.isPaused() ? 0f : amount;
				sim.update(input, dt, realDt);
				if (!outro) afterSim(dt, realDt); // the result is in: nothing more is written to vanilla
				playSounds();
			}
			return;
		}
		CombatUIAPI ui = engine.getCombatUI();
		if (ui != null) ui.setDisablePlayerShipControlOneFrame(true);
		showVanillaShipInfo(false);

		readHeldInput();
		float dt = engine.isPaused() ? 0f : amount;
		int requested = input.selectGroup, before = sim.player != null ? sim.player.selectedGroup : -1;
		int autofireToggled = input.toggleAutofire;
		sim.update(input, dt, realDt);
		input.clearPressed();
		if (sim.transferArrived != null) {
			Ship3D to = sim.transferArrived;
			sim.transferArrived = null;
			if (sim.command.follow == sim.player) sim.command.follow = to; // the tactical view keeps following you
			log.info("S3D: command shuttle reached " + to.name);
			transferArrived(to);
		}
		carryOutCommands();
		if (requested >= 0) logGroup(requested, before);
		if (autofireToggled >= 0) logAutofire(autofireToggled);
		afterSim(dt, realDt);
		playSounds();

		if (toastTime > 0f) toastTime -= realDt;
		simMs = (System.nanoTime() - now) / 1e6f;
	}

	/**
	 * The battle is decided but vanilla's own screens aren't up yet: the 3D scene plays on for a few
	 * seconds (ships drifting, wrecks burning) instead of cutting to the empty 2D map.
	 */
	private boolean inOutro() {
		return overlay && !done() && yieldToVanilla() && !vanillaUiActive() && outroTime < OUTRO_SECONDS;
	}

	void render() {
		boolean outro = inOutro();
		if (done() || !overlay || vanillaUiActive() || yieldToVanilla() && !outro) {
			lastRenderNanos = 0; // don't count the time spent in vanilla menus as a slow frame
			CursorHider.restore();
			return;
		}
		if (outro || sim.camera.tactical) CursorHider.restore(); // vanilla's end-of-battle prompts / pointing at ships
		else if (cfg.tuning.hideGameCursor) CursorHider.hide();
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
			if (showHud && !outro) {
				fillHud();
				RENDERER.renderHud(sim, input, hud, w, h);
			}
			if (firstRenderNanos == 0) firstRenderNanos = t0;
			RENDERER.fade(1f - (t0 - firstRenderNanos) / 1e9f / FADE_IN_SECONDS, w, h);
		}
		renderMs = (System.nanoTime() - t0) / 1e6f;
		logPerfPeriodically(t0);
	}

	private void fillHud() {
		hud.alerts.clear();
		hud.perf.clear();
		fillAlerts(hud.alerts);
		if (showPerf) {
			hud.perf.add(String.format("FPS %.0f  (%.1f ms, worst %.1f)", 1000f / Math.max(0.1f, frameMs), frameMs, worstFrameMs));
			hud.perf.add(String.format("sim %.2f ms  render %.2f ms", simMs, renderMs));
			hud.perf.add(String.format("%d ships  %dk tris  MSAA %dx", RENDERER.lastShips, RENDERER.lastTriangles / 1000, Math.max(0, RENDERER.msaaSamples())));
			hud.perf.add("tuning #" + cfg.hash);
		}
		hud.toast = toast;
		hud.toastAlpha = Math.max(0f, Math.min(1f, toastTime));
		VanillaStatus.read(engine, hud.statuses);
		hud.commandPoints = sim.camera.tactical ? commandPoints() : -1;
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
			case "LSHIFT": return "Shift";
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
	 * travel drive is running. Top speed may rise above what the ship deployed with only by a
	 * battle objective's bonus (nav buoys).
	 */
	private void refreshFlightParams() {
		for (Ship3D s : sim.ships) {
			if (!(s.hostRef instanceof ShipAPI) || s.destroyed) continue;
			ShipAPI ship = (ShipAPI) s.hostRef;
			if (ShipBridge.travelDriveActive(ship)) continue;
			// Lower only: CR loss is real, but zero-flux boost and ship systems on the puppet are not.
			// Nav buoys are real: their share may lift top speed past the deployment figure.
			FlightParams cap = s.baseParams;
			float nav = cap != null ? ShipBridge.navSpeedMult(ship) : 1f;
			if (nav > 1.001f) cap = new FlightParams(cap.maxSpeed * nav, cap.accel, cap.decel, cap.maxTurnRateDeg, cap.turnAccelDeg);
			FlightParams fresh = ShipBridge.readFlightParams(ship).cappedBy(cap);
			if (fresh.sameSource(s.params)) continue;
			log.info("S3D: flight stats for " + s.name + " updated to " + fresh);
			s.params = fresh.derive(sim.tuning);
		}
	}

	/**
	 * Skill and hullmod combat stats from the live puppets (SkillStats): every frame, since some follow
	 * flux (Polarized Armor); fighters, and the search for listener skills, twice a second.
	 */
	private void refreshSkillStats(boolean slow) {
		for (Ship3D s : sim.ships) {
			if (!(s.hostRef instanceof ShipAPI) || s.destroyed || s.fighter && !slow) continue;
			SkillStats.refresh((ShipAPI) s.hostRef, s, slow);
		}
	}

	private void reloadTuning() {
		TuningLoader next = TuningLoader.load();
		boolean meshChanged = !next.tuning.meshSignature().equals(cfg.tuning.meshSignature());
		meshChanged |= HullMeshes.reload(); // edited hull models show up on K too
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

	/**
	 * Vanilla's ship panel (bottom left: hull, flux, weapon groups, system) describes the frozen 2D
	 * puppet, never the 3D ship, and sits over the 3D view; the 3D HUD shows all of it. Hidden while
	 * the 3D view is up, back for vanilla's menus and when the battle is handed back.
	 */
	private void showVanillaShipInfo(boolean show) {
		CombatUIAPI ui = engine.getCombatUI();
		if (ui == null || shipInfoFailed) return;
		try {
			if (!show) ui.hideShipInfo(); // every frame: vanilla may fan it out again (new target, ship change)
			else if (shipInfoHidden) ui.reFanOutShipInfo();
			if (show == shipInfoHidden) log.info("S3D: vanilla ship panel " + (show ? "shown again" : "hidden while the 3D view is up"));
			shipInfoHidden = !show;
		} catch (Throwable t) {
			shipInfoFailed = true;
			log.warn("S3D: could not hide/show vanilla's ship panel", t);
		}
	}

	void shutdown() {
		CursorHider.restore();
		showVanillaShipInfo(true);
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
