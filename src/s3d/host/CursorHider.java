package s3d.host;

import java.nio.IntBuffer;

import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Mouse;

import com.fs.starfarer.api.Global;

/**
 * Swaps the game's hardware mouse cursor for a transparent one while the 3D view is up (our HUD
 * draws its own reticle) and puts the game's cursor back whenever vanilla UI is showing. State is
 * global so a new battle can always restore a cursor a previous one left hidden.
 */
final class CursorHider {
	private static final Logger log = Global.getLogger(CursorHider.class);

	private static Cursor blank;
	private static boolean unsupported;
	private static Cursor saved;
	private static boolean hidden;
	/**
	 * Hide again only this long after the last restore. Leaving the refit simulator closes its exit
	 * dialog and the battle ends a frame later, so hiding at once would leave the refit screen with
	 * no cursor and no battle frame left to bring it back.
	 */
	private static final long HIDE_DELAY_NANOS = 500_000_000L;
	private static long restoredAt;

	private CursorHider() {}

	/** Call every frame the 3D view is visible. Re-applies if the game swapped its cursor back in. */
	static void hide() {
		if (unsupported || System.nanoTime() - restoredAt < HIDE_DELAY_NANOS) return;
		try {
			Cursor b = blank();
			if (b == null) return;
			Cursor current = Mouse.getNativeCursor();
			if (hidden && current == b) return;
			if (current != b) saved = current;
			Mouse.setNativeCursor(b);
			if (!hidden) log.info("S3D: game cursor hidden while the 3D view is active");
			hidden = true;
		} catch (Throwable t) {
			unsupported = true;
			log.warn("S3D: could not hide the game cursor; it will stay visible over the HUD", t);
		}
	}

	static void restore() {
		restoredAt = System.nanoTime();
		if (!hidden) return;
		hidden = false;
		try {
			Mouse.setNativeCursor(saved);
		} catch (Throwable t) {
			log.warn("S3D: could not restore the game cursor", t);
		}
	}

	private static Cursor blank() throws Exception {
		if (blank != null) return blank;
		if ((Cursor.getCapabilities() & Cursor.CURSOR_ONE_BIT_TRANSPARENCY) == 0) {
			unsupported = true;
			log.warn("S3D: transparent cursors unsupported; the game cursor will stay visible");
			return null;
		}
		int size = Math.max(1, Cursor.getMinCursorSize());
		IntBuffer pixels = BufferUtils.createIntBuffer(size * size); // all zero = fully transparent
		blank = new Cursor(size, size, 0, 0, 1, pixels, null);
		return blank;
	}
}
