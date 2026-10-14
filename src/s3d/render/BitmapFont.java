package s3d.render;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;

/**
 * One of vanilla's bitmap fonts (AngelCode BMFont text .fnt + page image, e.g. the combat HUD's
 * Victor's Pixel Font), read from the game's files at runtime. Glyph coverage is the page's alpha;
 * drawn with the fx shader's text mode at whole-pixel positions, nearest filtering, so pixel fonts
 * stay crisp. Single-page fonts only (all of vanilla's HUD fonts are).
 */
public final class BitmapFont {
	private static final class Glyph {
		int x, y, w, h, xoff, yoff, adv;
	}

	/** Extra pixels between letters: vanilla draws its HUD text one pixel apart (the glyphs' own advance leaves none). */
	private static final int TRACKING = 1;

	public final int texture;
	public final float lineHeight;
	private final int pageW, pageH;
	private final Map<Integer, Glyph> glyphs;
	private final Map<Long, Integer> kerning;
	private final Glyph fallback;

	private BitmapFont(int texture, float lineHeight, int pageW, int pageH, Map<Integer, Glyph> glyphs, Map<Long, Integer> kerning) {
		this.texture = texture;
		this.lineHeight = lineHeight;
		this.pageW = pageW;
		this.pageH = pageH;
		this.glyphs = glyphs;
		this.kerning = kerning;
		this.fallback = glyphs.getOrDefault((int) '?', glyphs.get((int) ' '));
	}

	/** Loads e.g. "graphics/fonts/victor14.fnt" and its page through GameFiles. Needs a GL context. */
	public static BitmapFont load(String fntPath) throws Exception {
		Map<Integer, Glyph> glyphs = new HashMap<>();
		Map<Long, Integer> kerning = new HashMap<>();
		float lineHeight = 0f;
		String page = null;
		try (InputStream in = GameFiles.open(fntPath);
			 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			for (String line; (line = r.readLine()) != null; ) {
				Map<String, String> kv = pairs(line);
				if (line.startsWith("common ")) {
					lineHeight = num(kv, "lineHeight");
				} else if (line.startsWith("page ")) {
					if (num(kv, "id") == 0) page = kv.get("file");
				} else if (line.startsWith("char ")) {
					if (num(kv, "page") != 0) continue;
					Glyph g = new Glyph();
					g.x = num(kv, "x");
					g.y = num(kv, "y");
					g.w = num(kv, "width");
					g.h = num(kv, "height");
					g.xoff = num(kv, "xoffset");
					g.yoff = num(kv, "yoffset");
					g.adv = num(kv, "xadvance");
					glyphs.put(num(kv, "id"), g);
				} else if (line.startsWith("kerning ")) {
					kerning.put(((long) num(kv, "first") << 32) | num(kv, "second"), num(kv, "amount"));
				}
			}
		}
		if (page == null || glyphs.isEmpty()) throw new IllegalStateException("not a BMFont text file: " + fntPath);
		String dir = fntPath.lastIndexOf('/') >= 0 ? fntPath.substring(0, fntPath.lastIndexOf('/') + 1) : "";
		BufferedImage img = GameFiles.image(dir + page);
		int w = img.getWidth(), h = img.getHeight();
		int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
		int tex = TextureUtil.upload(argb, w, h, false, 1f);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex); // pixel font: no smoothing between texels
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		return new BitmapFont(tex, lineHeight, w, h, glyphs, kerning);
	}

	private static Map<String, String> pairs(String line) {
		Map<String, String> kv = new HashMap<>();
		int i = 0, n = line.length();
		while (i < n) {
			int eq = line.indexOf('=', i);
			if (eq < 0) break;
			int ks = line.lastIndexOf(' ', eq) + 1;
			String key = line.substring(ks, eq);
			int vs = eq + 1, ve;
			if (vs < n && line.charAt(vs) == '"') {
				ve = line.indexOf('"', vs + 1);
				if (ve < 0) ve = n;
				kv.put(key, line.substring(vs + 1, ve));
				i = ve + 1;
			} else {
				ve = line.indexOf(' ', vs);
				if (ve < 0) ve = n;
				kv.put(key, line.substring(vs, ve));
				i = ve;
			}
		}
		return kv;
	}

	private static int num(Map<String, String> kv, String key) {
		String v = kv.get(key);
		if (v == null) return 0;
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private Glyph glyph(char c) {
		Glyph g = glyphs.get((int) c);
		return g != null ? g : fallback;
	}

	private int kern(char a, char b) {
		if (kerning.isEmpty()) return 0;
		return kerning.getOrDefault(((long) a << 32) | b, 0);
	}

	public float width(String s, float scale) {
		float pen = 0f;
		for (int k = 0; k < s.length(); k++) {
			Glyph g = glyph(s.charAt(k));
			if (g == null) continue;
			pen += g.adv + (k + 1 < s.length() ? kern(s.charAt(k), s.charAt(k + 1)) + TRACKING : 0);
		}
		return pen * scale;
	}

	/**
	 * Emits quads for text whose line top is at yTop (pixels, origin bottom-left), starting at x.
	 * Call between glBegin(GL_QUADS)/glEnd with this font's texture bound and the text shader mode.
	 */
	public void emit(String s, float x, float yTop, float scale) {
		float pen = Math.round(x), top = Math.round(yTop);
		for (int k = 0; k < s.length(); k++) {
			char ch = s.charAt(k);
			Glyph g = glyph(ch);
			if (g == null) continue;
			if (g.w > 0 && g.h > 0) {
				float x0 = pen + g.xoff * scale, x1 = x0 + g.w * scale;
				float y1 = top - g.yoff * scale, y0 = y1 - g.h * scale;
				float u0 = g.x / (float) pageW, u1 = (g.x + g.w) / (float) pageW;
				float v0 = g.y / (float) pageH, v1 = (g.y + g.h) / (float) pageH;
				GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(x0, y0);
				GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x1, y0);
				GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(x1, y1);
				GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(x0, y1);
			}
			pen += (g.adv + (k + 1 < s.length() ? kern(ch, s.charAt(k + 1)) + TRACKING : 0)) * scale;
		}
	}
}
