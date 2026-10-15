package s3d.host;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.Global;

/**
 * The player's own vanilla key bindings, for actions the 3D view mirrors (so far only Toggle UI). Vanilla keeps
 * its controls in Java preferences (node com/fs/starfarer, a JSON map split across controls1, controls2, ...);
 * each control has two slots, ACTION_1 and ACTION_2, with an LWJGL key code ("c", -1 = unbound, 2000+ = mouse).
 * The API has no way to read them. java.util.prefs is reached through java.lang.invoke handles, as the script
 * class loader may refuse it to mods. Anything unexpected: logged once, and vanilla's default key is used.
 */
final class VanillaControls {
	private static final Logger log = Global.getLogger(VanillaControls.class);
	private static boolean warned;

	private VanillaControls() {}

	/** Keyboard keys bound to vanilla's Toggle UI (default F11). */
	static int[] toggleUi() {
		int[] keys = keys("GENERAL_TOGGLE_UI");
		return keys.length > 0 ? keys : new int[] { Keyboard.KEY_F11 };
	}

	/** Both slots' keyboard keys for a vanilla control, or none if the bindings can't be read. */
	static int[] keys(String control) {
		String json = controls();
		if (json == null) return new int[0];
		int[] out = new int[2];
		int n = 0;
		for (int slot = 1; slot <= 2; slot++) {
			Matcher m = Pattern.compile("\"" + control + "_" + slot + "\"\\s*:\\s*\\{\\s*\"c\"\\s*:\\s*\"(-?\\d+)\"").matcher(json);
			if (!m.find()) continue;
			int code = Integer.parseInt(m.group(1));
			if (code > 0 && code < 256) out[n++] = code; // keyboard only
		}
		return Arrays.copyOf(out, n);
	}

	/** Read fresh each time (once per tuning load), so a rebinding in vanilla's options counts from the next battle. */
	private static String controls() {
		try {
			Class<?> prefs = Class.forName("java.util.prefs.Preferences", false, null);
			MethodHandles.Lookup l = MethodHandles.lookup();
			MethodHandle userRoot = l.findStatic(prefs, "userRoot", MethodType.methodType(prefs));
			MethodHandle node = l.findVirtual(prefs, "node", MethodType.methodType(prefs, String.class));
			MethodHandle get = l.findVirtual(prefs, "get", MethodType.methodType(String.class, String.class, String.class));
			Object starfarer = node.invoke(userRoot.invoke(), "com/fs/starfarer");
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i < 50; i++) {
				String part = (String) get.invoke(starfarer, "controls" + i, (String) null);
				if (part == null) break;
				sb.append(part);
			}
			return sb.length() > 0 ? sb.toString() : null; // none saved yet: vanilla's defaults
		} catch (Throwable t) {
			if (!warned) log.warn("S3D: could not read vanilla's key bindings, using vanilla's defaults (" + t + ")");
			warned = true;
			return null;
		}
	}
}
