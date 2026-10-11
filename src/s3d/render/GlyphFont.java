package s3d.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.lwjgl.opengl.GL11;

/**
 * Monospace HUD font rasterised once with Java2D into a texture atlas (ASCII 32..126).
 * Avoids depending on LazyLib/MagicLib just to print numbers.
 */
public final class GlyphFont {
	private static final int FIRST = 32, LAST = 126, COLS = 16;

	public final int texture;
	public final float advance, lineHeight;
	private final int cellW, cellH, atlasW, atlasH;

	private GlyphFont(int texture, int cellW, int cellH, int atlasW, int atlasH, float advance) {
		this.texture = texture;
		this.cellW = cellW;
		this.cellH = cellH;
		this.atlasW = atlasW;
		this.atlasH = atlasH;
		this.advance = advance;
		this.lineHeight = cellH;
	}

	public static GlyphFont create(int sizePx) {
		Font font = new Font(Font.MONOSPACED, Font.BOLD, sizePx);
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		pg.setFont(font);
		FontMetrics fm = pg.getFontMetrics();
		int adv = fm.charWidth('M');
		int cellW = adv + 2, cellH = fm.getHeight() + 2, ascent = fm.getAscent();
		pg.dispose();

		int rows = (LAST - FIRST + COLS) / COLS;
		int aw = COLS * cellW, ah = rows * cellH;
		BufferedImage img = new BufferedImage(aw, ah, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(font);
		g.setColor(Color.WHITE);
		for (int c = FIRST; c <= LAST; c++) {
			int i = c - FIRST;
			g.drawString(String.valueOf((char) c), (i % COLS) * cellW + 1, (i / COLS) * cellH + 1 + ascent);
		}
		g.dispose();
		int[] argb = img.getRGB(0, 0, aw, ah, null, 0, aw);
		int tex = TextureUtil.upload(argb, aw, ah, false, 1f);
		return new GlyphFont(tex, cellW, cellH, aw, ah, adv);
	}

	public float width(String s) {
		return s.length() * advance;
	}

	/**
	 * Emits quads for text whose top-left corner is at (x, yTop) in pixels (origin bottom-left).
	 * Must be called between glBegin(GL_QUADS)/glEnd with the text shader bound.
	 */
	public void emit(String s, float x, float yTop, float scale) {
		float pen = x;
		for (int k = 0; k < s.length(); k++) {
			char ch = s.charAt(k);
			if (ch < FIRST || ch > LAST) ch = '?';
			int i = ch - FIRST;
			float u0 = (i % COLS) * cellW / (float) atlasW, v0 = (i / COLS) * cellH / (float) atlasH;
			float u1 = u0 + cellW / (float) atlasW, v1 = v0 + cellH / (float) atlasH;
			float x0 = pen - scale, x1 = pen + (cellW - 1) * scale;
			float y0 = yTop - cellH * scale, y1 = yTop;
			GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(x0, y0);
			GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x1, y0);
			GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(x1, y1);
			GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(x0, y1);
			pen += advance * scale;
		}
	}
}
