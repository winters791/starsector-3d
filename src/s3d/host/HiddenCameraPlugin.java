package s3d.host;

import java.util.List;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.Display;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/**
 * Keeps vanilla's off-screen ship markers off the 3D view.
 *
 * While the 3D view is up, BattleSession parks the hidden 2D camera off the map, at
 * (mapWidth, mapHeight) with a 1000 x 1000 view. That puts every puppet ship off-screen, and
 * vanilla's HUD then draws an off-screen marker (ship icon + distance) for each one, piled up along
 * the screen edge on top of the 3D view.
 *
 * At render time, after the 2D world is drawn and before vanilla's HUD draws, this plugin re-frames
 * the camera over every ship, so none of them is off-screen and no markers are drawn. The 2D world is
 * also faded out (viewport alpha 0); the opaque 3D view covers it anyway. The next frame's advance
 * parks the camera again before anything else reads it (the 3D sounds use its centre).
 */
public class HiddenCameraPlugin extends BaseEveryFrameCombatPlugin {
	private static final Logger log = Global.getLogger(HiddenCameraPlugin.class);

	private CombatEngineAPI engine;
	private boolean alphaZeroed, failed, logged;

	@Override
	public void init(CombatEngineAPI engine) {
		this.engine = engine;
		alphaZeroed = failed = logged = false;
	}

	@Override
	public void advance(float amount, List<InputEventAPI> events) {
		if (engine == null || failed || !alphaZeroed) return;
		try {
			// The 3D view was switched off or the battle handed back: show the 2D world again.
			if (!parkedBy3D(engine.getViewport())) restoreAlpha();
		} catch (Throwable t) {
			fail(t);
		}
	}

	@Override
	public void renderInUICoords(ViewportAPI viewport) {
		if (engine == null || failed) return;
		try {
			ViewportAPI vp = engine.getViewport();
			if (!parkedBy3D(vp)) {
				if (alphaZeroed) restoreAlpha();
				return;
			}
			frameAllShips(vp);
			vp.setAlphaMult(0f);
			alphaZeroed = true;
			if (!logged) {
				logged = true;
				log.info("S3D: hidden 2D camera framed over every ship so vanilla draws no off-screen markers");
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** The 3D view is up: the camera is under external control and parked where BattleSession puts it. */
	private boolean parkedBy3D(ViewportAPI vp) {
		if (!vp.isExternalControl()) return false;
		return Math.abs(vp.getLLX() - engine.getMapWidth()) < 1f && Math.abs(vp.getLLY() - engine.getMapHeight()) < 1f
				&& Math.abs(vp.getVisibleWidth() - 1000f) < 1f;
	}

	private void frameAllShips(ViewportAPI vp) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (ShipAPI ship : engine.getShips()) {
			Vector2f p = ship.getLocation();
			if (p == null) continue;
			minX = Math.min(minX, p.x);
			minY = Math.min(minY, p.y);
			maxX = Math.max(maxX, p.x);
			maxY = Math.max(maxY, p.y);
		}
		if (minX > maxX) minX = maxX = minY = maxY = 0f;
		float margin = 1500f; // a capital's radius plus slack, so no hull pokes past the frame
		float w = maxX - minX + 2f * margin, h = maxY - minY + 2f * margin;
		float aspect = Math.max(1, Display.getWidth()) / (float) Math.max(1, Display.getHeight());
		if (w / h < aspect) w = h * aspect;
		else h = w / aspect;
		float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f;
		vp.set(cx - w * 0.5f, cy - h * 0.5f, w, h);
	}

	private void restoreAlpha() {
		engine.getViewport().setAlphaMult(1f);
		alphaZeroed = false;
	}

	private void fail(Throwable t) {
		failed = true;
		log.warn("S3D: hidden-camera framing failed; vanilla's off-screen markers may show over the 3D view", t);
		try {
			if (alphaZeroed) restoreAlpha();
		} catch (Throwable ignored) {
			// best effort
		}
	}
}
