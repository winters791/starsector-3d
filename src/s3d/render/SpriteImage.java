package s3d.render;

import java.awt.image.BufferedImage;

/** CPU copy of a sprite: ARGB pixels, row 0 = top of the image. */
public final class SpriteImage {
	public final int w, h;
	public final int[] argb;

	public SpriteImage(BufferedImage img) {
		w = img.getWidth();
		h = img.getHeight();
		argb = img.getRGB(0, 0, w, h, null, 0, w);
	}

	public float alpha(int x, int y) {
		if (x < 0 || y < 0 || x >= w || y >= h) return 0f;
		return (argb[y * w + x] >>> 24) / 255f;
	}

	public float luminance(int x, int y) {
		if (x < 0 || y < 0 || x >= w || y >= h) return 0f;
		int p = argb[y * w + x];
		return (0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255)) / 255f;
	}

	/**
	 * Copy whose transparent pixels take the colour of their nearest opaque neighbours, so mipmaps
	 * and edge texels don't bleed black onto the hull. Alpha is forced opaque: the mesh outline,
	 * not the texture, defines the silhouette.
	 */
	public int[] dilatedOpaque(int passes) {
		int[] cur = argb.clone();
		boolean[] known = new boolean[w * h];
		for (int i = 0; i < w * h; i++) known[i] = (cur[i] >>> 24) >= 128;
		for (int pass = 0; pass < passes; pass++) {
			int[] next = cur.clone();
			boolean[] nextKnown = known.clone();
			boolean changed = false;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = y * w + x;
					if (known[i]) continue;
					int r = 0, g = 0, b = 0, n = 0;
					for (int dy = -1; dy <= 1; dy++) {
						for (int dx = -1; dx <= 1; dx++) {
							int xx = x + dx, yy = y + dy;
							if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
							int j = yy * w + xx;
							if (!known[j]) continue;
							int p = cur[j];
							r += (p >> 16) & 255;
							g += (p >> 8) & 255;
							b += p & 255;
							n++;
						}
					}
					if (n > 0) {
						next[i] = (r / n) << 16 | (g / n) << 8 | (b / n);
						nextKnown[i] = true;
						changed = true;
					}
				}
			}
			cur = next;
			known = nextKnown;
			if (!changed) break;
		}
		int[] tint = averageTintRgb();
		int fill = tint[0] << 16 | tint[1] << 8 | tint[2];
		for (int i = 0; i < w * h; i++) cur[i] = 0xFF000000 | (known[i] ? cur[i] & 0xFFFFFF : fill);
		return cur;
	}

	/**
	 * Tangent-space surface detail from the sprite's own shading: brighter = raised. Encoded as
	 * R = slope across (+x), G = slope down the image (+z), 0.5 = flat. Lightly smoothed so pixel
	 * noise doesn't sparkle; transparent pixels are flat.
	 */
	public int[] normalMap(float strength) {
		float[] hgt = new float[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = argb[y * w + x];
				float a = (p >>> 24) / 255f;
				hgt[y * w + x] = a * (0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255)) / 255f;
			}
		}
		// High-pass: only fine detail (panel lines, greebles) becomes bumps. The sprite's broad baked
		// lighting gradients would otherwise tilt whole panels and look like crumpled foil.
		float[] broad = ReliefMeshBuilder.gaussian(hgt, w, h, 2.5f);
		float[] fine = new float[w * h];
		for (int i = 0; i < w * h; i++) fine[i] = hgt[i] - broad[i];
		float[] sm = ReliefMeshBuilder.gaussian(fine, w, h, 0.6f);
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float hl = sm[y * w + Math.max(0, x - 1)], hr = sm[y * w + Math.min(w - 1, x + 1)];
				float hu = sm[Math.max(0, y - 1) * w + x], hd = sm[Math.min(h - 1, y + 1) * w + x];
				float gx = -(hr - hl) * 0.5f * strength, gz = -(hd - hu) * 0.5f * strength;
				gx = Math.max(-1f, Math.min(1f, gx));
				gz = Math.max(-1f, Math.min(1f, gz));
				int r = Math.round((gx * 0.5f + 0.5f) * 255f), g = Math.round((gz * 0.5f + 0.5f) * 255f);
				out[y * w + x] = 0xFF000000 | r << 16 | g << 8 | 255;
			}
		}
		return out;
	}

	/** Average colour of solid pixels (0..255). */
	public int[] averageTintRgb() {
		long r = 0, g = 0, b = 0, n = 0;
		for (int p : argb) {
			if ((p >>> 24) < 200) continue;
			r += (p >> 16) & 255;
			g += (p >> 8) & 255;
			b += p & 255;
			n++;
		}
		if (n == 0) return new int[] {128, 128, 128};
		return new int[] {(int) (r / n), (int) (g / n), (int) (b / n)};
	}
}
