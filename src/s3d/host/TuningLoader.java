package s3d.host;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.Global;

import s3d.core.Tuning;

/** Loads data/config/s3d_tuning.json from this mod and resolves key names to LWJGL key codes. */
final class TuningLoader {
	private static final Logger log = Global.getLogger(TuningLoader.class);
	static final String PATH = "data/config/s3d_tuning.json";

	final Tuning tuning;
	final String hash;
	final Map<String, Integer> keyCodes = new HashMap<>();
	final Map<String, int[]> keyLists = new HashMap<>();
	final String error;

	private TuningLoader(Tuning tuning, String hash, String error) {
		this.tuning = tuning;
		this.hash = hash;
		this.error = error;
		for (Map.Entry<String, String> e : tuning.keys.entrySet()) {
			// An action can list several keys, comma-separated (e.g. "F10,BACKSLASH,G").
			String[] names = e.getValue().split(",");
			int[] codes = new int[names.length];
			int n = 0;
			for (String name : names) {
				int code = Keyboard.getKeyIndex(name.trim().toUpperCase());
				if (code == Keyboard.KEY_NONE) log.warn("S3D: unknown key name '" + name.trim() + "' for " + e.getKey());
				else codes[n++] = code;
			}
			if (e.getKey().equals("toggleHud")) { // also whatever the player binds to vanilla's Toggle UI
				int[] vanilla = VanillaControls.toggleUi();
				codes = Arrays.copyOf(codes, n + vanilla.length);
				for (int v : vanilla) if (!contains(codes, n, v)) codes[n++] = v;
				log.info("S3D: hide UI keys " + names(codes, n));
			}
			keyLists.put(e.getKey(), Arrays.copyOf(codes, n));
			keyCodes.put(e.getKey(), n > 0 ? codes[0] : Keyboard.KEY_NONE);
		}
	}

	private static boolean contains(int[] codes, int n, int code) {
		for (int i = 0; i < n; i++) if (codes[i] == code) return true;
		return false;
	}

	private static String names(int[] codes, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) sb.append(i > 0 ? ", " : "").append(Keyboard.getKeyName(codes[i]));
		return sb.toString();
	}

	/** Every key of the action. */
	int[] keys(String action) {
		return keyLists.getOrDefault(action, new int[0]);
	}

	static TuningLoader load() {
		try {
			String text = Global.getSettings().loadText(PATH, S3DModPlugin.MOD_ID);
			Tuning t = Tuning.fromJson(new JSONObject(text));
			String hash = Integer.toHexString(text.hashCode());
			log.info("S3D: loaded " + PATH + " (#" + hash + ")");
			return new TuningLoader(t, hash, null);
		} catch (Throwable t) {
			log.error("S3D: could not read " + PATH + ", using built-in defaults", t);
			return new TuningLoader(new Tuning(), "defaults", t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}

	/** The action's first key (for held keys and single-key actions). */
	int key(String action) {
		Integer c = keyCodes.get(action);
		return c == null ? Keyboard.KEY_NONE : c;
	}

	/** True if the pressed key is one of the action's keys. */
	boolean isKey(String action, int key) {
		for (int c : keyLists.getOrDefault(action, new int[0])) if (c == key) return true;
		return false;
	}
}
