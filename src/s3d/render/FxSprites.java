package s3d.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla sprite sheets some effects draw with (the Threat's fragments, the shroud's blobs, the hit
 * glow), by their settings.json category and key. The host supplies the loader (the game's own
 * files); the preview tools read them from the install. Missing ones are 0 and the effect falls
 * back to plain shapes.
 */
public final class FxSprites {
	public interface Loader {
		SpriteImage load(String category, String key) throws Exception;
	}

	public static Loader loader;
	private static final Map<String, Integer> TEXTURES = new HashMap<>();

	private FxSprites() {}

	/** The GL texture for a vanilla sprite, loaded once (0 if it can't be had). */
	static int texture(String category, String key) {
		String id = category + "/" + key;
		Integer tex = TEXTURES.get(id);
		if (tex != null) return tex;
		int t = 0;
		try {
			SpriteImage img = loader != null ? loader.load(category, key) : null;
			if (img != null) t = TextureUtil.upload(img.argb, img.w, img.h, true, 1f);
		} catch (Exception e) {
			t = 0;
		}
		TEXTURES.put(id, t);
		return t;
	}
}
