package s3d.render;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * HUD icons from vanilla sprites (weapon turrets, status icons), as tintable hologram silhouettes:
 * the layers are stacked centred (a turret's under / base / barrel), then each pixel becomes white
 * at its brightness (stretched so the brightest part is full) with the sprite's own alpha. Drawn
 * with the fx shader's tinted-sprite mode, so the HUD colours them. Cached by layer list; anything
 * that can't be loaded is null and the caller falls back.
 */
final class HudIcons {
	static final class Icon {
		final int tex, w, h;

		Icon(int tex, int w, int h) {
			this.tex = tex;
			this.w = w;
			this.h = h;
		}
	}

	private static final Map<String, Icon> CACHE = new HashMap<>();
	private static final Icon NONE = new Icon(0, 0, 0);

	private HudIcons() {}

	static Icon get(String... layers) {
		if (layers == null || layers.length == 0) return null;
		String key = String.join("|", layers);
		Icon icon = CACHE.get(key);
		if (icon == null) {
			icon = build(layers);
			CACHE.put(key, icon);
		}
		return icon == NONE ? null : icon;
	}

	private static Icon build(String[] layers) {
		try {
			java.util.List<BufferedImage> imgs = new java.util.ArrayList<>();
			int w = 0, h = 0;
			for (String path : layers) {
				if (path == null || path.isEmpty()) continue;
				BufferedImage img = GameFiles.image(path);
				imgs.add(img);
				w = Math.max(w, img.getWidth());
				h = Math.max(h, img.getHeight());
			}
			if (imgs.isEmpty() || w == 0 || h == 0) return NONE;
			// Stack the layers centred, alpha-over, keeping each pixel's luminance.
			float[] lum = new float[w * h], alpha = new float[w * h];
			for (BufferedImage img : imgs) {
				int ox = (w - img.getWidth()) / 2, oy = (h - img.getHeight()) / 2;
				for (int y = 0; y < img.getHeight(); y++) {
					for (int x = 0; x < img.getWidth(); x++) {
						int p = img.getRGB(x, y);
						float a = (p >>> 24) / 255f;
						if (a <= 0f) continue;
						float l = (0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255)) / 255f;
						int i = (y + oy) * w + x + ox;
						lum[i] = l * a + lum[i] * (1f - a);
						alpha[i] = a + alpha[i] * (1f - a);
					}
				}
			}
			float top = 0f;
			for (int i = 0; i < w * h; i++) if (alpha[i] > 0.3f) top = Math.max(top, lum[i]);
			float gain = top > 0.05f ? 1f / top : 1f;
			int[] argb = new int[w * h];
			for (int i = 0; i < w * h; i++) {
				// A floor keeps dark metal visible as a silhouette; the brightest parts read as full colour.
				int v = Math.round(255f * Math.min(1f, 0.3f + 0.7f * lum[i] * gain));
				int a = Math.round(255f * Math.min(1f, alpha[i]));
				argb[i] = a << 24 | v << 16 | v << 8 | v;
			}
			return new Icon(TextureUtil.upload(argb, w, h, true, 1f), w, h);
		} catch (Throwable t) {
			return NONE;
		}
	}
}
