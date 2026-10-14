package s3d.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import s3d.core.Sim;
import s3d.math.Mat4;

/**
 * The controls card: a keyboard and a mouse drawn as an infographic in vanilla's UI look (dark navy
 * panel, cyan frame with cut corners, Orbitron title, key letters in vanilla's shortcut yellow,
 * Insignia text). Two faces: flight, and the command view (T). Keys come from the tuning's `keys`,
 * placed on a QWERTY picture by the key they're bound to; bound keys outside the picture go to the
 * strip along the bottom. Drawn last, over the HUD and the holograms.
 */
final class ControlsCard {
	/** Vanilla settings.json: widgetBorderColorDark, buttonShortcut, tooltipTitleAndLightHighlightColor, standardTextColor, textGrayColor. */
	private static final float[] EDGE = {100 / 255f, 200 / 255f, 1f};
	private static final float[] KEY = {1f, 210 / 255f, 0f};
	private static final float[] TITLE = {203 / 255f, 245 / 255f, 1f};
	private static final float[] TEXT = {220 / 255f, 220 / 255f, 220 / 255f};
	private static final float[] DIM = {175 / 255f, 175 / 255f, 175 / 255f};
	private static final float[] FILL = {0.02f, 0.05f, 0.08f};
	private static final float[] CAP_FILL = {31 / 255f, 94 / 255f, 112 / 255f}; // buttonBgDark

	private static final boolean MAC = String.valueOf(System.getProperty("os.name")).toLowerCase(Locale.ROOT).startsWith("mac");

	private final Shader fx;
	private final BitmapFont title, head, body;
	private final Mat4 ortho = new Mat4();
	private final List<T> texts = new ArrayList<>();

	private static final class T {
		final String s;
		final float x, y, a;
		final float[] c;
		final BitmapFont f;
		final int align; // -1 left, 0 centre, 1 right

		T(BitmapFont f, String s, float x, float y, float[] c, float a, int align) {
			this.f = f;
			this.s = s;
			this.x = x;
			this.y = y;
			this.c = c;
			this.a = a;
			this.align = align;
		}
	}

	/** A key cap on the picture: row, column (in key widths, staggered as a keyboard), width, LWJGL name. */
	private static final class Cap {
		final String name, label;
		final float col, row, wide;

		Cap(String name, String label, float col, float row, float wide) {
			this.name = name;
			this.label = label;
			this.col = col;
			this.row = row;
			this.wide = wide;
		}
	}

	ControlsCard(Shader fx, BitmapFont title, BitmapFont head, BitmapFont body) {
		this.fx = fx;
		this.title = title;
		this.head = head;
		this.body = body;
	}

	/** Labels per action for each face; actions not listed aren't shown (debug and sandbox keys). */
	private static Map<String, String> flightLabels() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("throttleUp", "THROTTLE +");
		m.put("throttleDown", "THROTTLE -");
		m.put("strafeLeft", "STRAFE");
		m.put("strafeRight", "STRAFE");
		m.put("strafeUp", "UP");
		m.put("strafeDown", "DOWN");
		m.put("rollLeft", "ROLL");
		m.put("rollRight", "ROLL");
		m.put("allStop", "ALL STOP");
		m.put("system", "SYSTEM");
		m.put("vent", "VENT");
		m.put("target", "TARGET");
		m.put("wings", "WINGS");
		m.put("tactical", "COMMAND");
		m.put("freeLook", "LOOK");
		m.put("autoLevel", "AUTO-LEVEL");
		m.put("spectate", "SPECTATE");
		m.put("toggleHud", "HIDE UI");
		m.put("handBack", "x2  FINISH IN 2D");
		return m;
	}

	private static Map<String, String> commandLabels() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("throttleUp", "PAN");
		m.put("throttleDown", "PAN");
		m.put("strafeLeft", "PAN");
		m.put("strafeRight", "PAN");
		m.put("rollLeft", "TURN");
		m.put("rollRight", "TURN");
		m.put("freeLook", "ORBIT");
		m.put("tactical", "FLIGHT VIEW");
		m.put("toggleHud", "HIDE UI");
		return m;
	}

	/** QWERTY positions for the keys the picture shows (column in key widths from Q, row 0 = Q row). */
	private static final Object[][] LAYOUT = {
			{"1", 0f, -1f}, {"2", 1f, -1f}, {"3", 2f, -1f}, {"4", 3f, -1f}, {"5", 4f, -1f}, {"6", 5f, -1f}, {"7", 6f, -1f},
			{"Q", 0.5f, 0f}, {"W", 1.5f, 0f}, {"E", 2.5f, 0f}, {"R", 3.5f, 0f}, {"T", 4.5f, 0f},
			{"A", 0.75f, 1f}, {"S", 1.75f, 1f}, {"D", 2.75f, 1f}, {"F", 3.75f, 1f}, {"G", 4.75f, 1f},
			{"Z", 1.25f, 2f}, {"X", 2.25f, 2f}, {"C", 2.25f + 1f, 2f}, {"V", 4.25f, 2f},
	};

	/**
	 * Draws the card (alpha 0..1) centred on the screen: the command view's face while the tactical
	 * view is up, else flight.
	 */
	void render(Sim sim, float alpha, int w, int h) {
		if (alpha <= 0.01f || title == null || head == null || body == null) return;
		boolean command = sim.camera.tactical;
		float s = h >= 1800 ? 2f : 1f; // whole-pixel scale, as the ship panel
		float cw = 960f * s, ch = 470f * s;
		float x0 = Math.round((w - cw) * 0.5f), y0 = Math.round((h - ch) * 0.5f), x1 = x0 + cw, y1 = y0 + ch;
		float a = Math.min(1f, alpha);
		texts.clear();
		textScale = s;

		GL11.glViewport(0, 0, w, h);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		ortho.ortho(0, w, 0, h, -1, 1);
		fx.use().set("u_vp", ortho).set("u_mode", 0).set("u_tex", 0);
		GL11.glDisable(GL11.GL_TEXTURE_2D);

		// Panel: vanilla's cut corners (top left, bottom right), a title rule.
		float cut = 16f * s;
		float[] px = {x0 + cut, x1, x1, x1 - cut, x0, x0}, py = {y1, y1, y0 + cut, y0, y0, y1 - cut};
		GL11.glColor4f(FILL[0], FILL[1], FILL[2], a);
		GL11.glBegin(GL11.GL_POLYGON);
		for (int k = 0; k < px.length; k++) GL11.glVertex2f(px[k], py[k]);
		GL11.glEnd();
		GL11.glBegin(GL11.GL_LINES);
		color(EDGE, 0.85f * a);
		for (int k = 0; k < px.length; k++) line(px[k], py[k], px[(k + 1) % px.length], py[(k + 1) % py.length]);
		color(EDGE, 0.35f * a);
		float in = 4f * s; // inner hairline, as vanilla's double frames
		line(x0 + cut + in * 0.4f, y1 - in, x1 - in, y1 - in);
		line(x1 - in, y1 - in, x1 - in, y0 + cut + in * 0.4f);
		float ruleY = y1 - 44f * s;
		color(EDGE, 0.5f * a);
		line(x0 + 20f * s, ruleY, x1 - 20f * s, ruleY);
		GL11.glEnd();

		String name = command ? "COMMAND VIEW" : "COMBAT CONTROLS";
		texts.add(new T(title, name, x0 + 24f * s, y1 - 14f * s, TITLE, a, -1));

		// Which picture key does what on this face.
		Map<String, String> labels = command ? commandLabels() : flightLabels();
		Map<String, String> onKey = new LinkedHashMap<>();
		Map<String, String> strip = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : labels.entrySet()) {
			String bound = sim.tuning.keys.get(e.getKey());
			if (bound == null || bound.isEmpty()) continue;
			String key = bound.split(",")[0].trim().toUpperCase(Locale.ROOT);
			if (inPicture(key)) onKey.putIfAbsent(key, e.getValue());
			else strip.putIfAbsent(key, e.getValue());
		}
		String mod = MAC ? "CMD" : "CTRL";
		if (!command) for (int g = 1; g <= 7; g++) onKey.put(String.valueOf(g), g == 1 ? "GROUPS" : "");

		// Keyboard, left half.
		float kx = x0 + 84f * s, ky = ruleY - (command ? 4f : 34f) * s; // top of the number row (none in the command view)
		float pitch = 84f * s, capW = 46f * s, capH = 38f * s, rowH = 70f * s;
		List<Cap> caps = new ArrayList<>();
		for (Object[] l : LAYOUT) {
			String n = (String) l[0];
			if (command && onKey.get(n) == null) continue; // the command view shows only its own keys
			caps.add(new Cap(n, onKey.get(n), (Float) l[1], (Float) l[2], 1f));
		}
		caps.add(new Cap("LSHIFT", command ? "ADD TO SELECTION" : onKey.get("LSHIFT"), -0.6f, 2f, 1.6f));
		caps.add(new Cap("CMDTAP", "PAUSE (TAP)", -0.6f, 3f, 1.6f));
		caps.add(new Cap("LMENU", onKey.get("LMENU"), 1f, 3f, 1.5f));
		if (!command || onKey.get("SPACE") != null) caps.add(new Cap("SPACE", onKey.get("SPACE"), 2.4f, 3f, 2.9f));
		float numPitch = 40f * s;
		GL11.glBegin(GL11.GL_QUADS);
		for (Cap c : caps) {
			float[] r = capRect(c, kx, ky, pitch, numPitch, capW, capH, rowH, s);
			boolean used = c.label != null;
			GL11.glColor4f(CAP_FILL[0], CAP_FILL[1], CAP_FILL[2], (used ? 0.55f : 0.18f) * a);
			GL11.glVertex2f(r[0], r[1]);
			GL11.glVertex2f(r[2], r[1]);
			GL11.glVertex2f(r[2], r[3]);
			GL11.glVertex2f(r[0], r[3]);
		}
		GL11.glEnd();
		GL11.glBegin(GL11.GL_LINES);
		for (Cap c : caps) {
			float[] r = capRect(c, kx, ky, pitch, numPitch, capW, capH, rowH, s);
			boolean used = c.label != null;
			color(EDGE, (used ? 0.9f : 0.25f) * a);
			rect(r);
			if (used) { // a key's lit bottom edge, as a pressed-in cap
				color(EDGE, 0.45f * a);
				line(r[0] + 3f * s, r[1] + 3f * s, r[2] - 3f * s, r[1] + 3f * s);
			}
		}
		GL11.glEnd();
		for (Cap c : caps) {
			float[] r = capRect(c, kx, ky, pitch, numPitch, capW, capH, rowH, s);
			boolean used = c.label != null;
			String cap = capName(c.name, mod);
			float cxm = (r[0] + r[2]) * 0.5f;
			texts.add(new T(head, cap, cxm, (r[1] + r[3]) * 0.5f + head.lineHeight * s * 0.42f, used ? KEY : DIM, (used ? 1f : 0.45f) * a, 0));
			if (used && !c.label.isEmpty()) {
				boolean numbers = Character.isDigit(c.name.charAt(0));
				if (numbers) {
					float rx = kx + 7f * numPitch + 10f * s;
					texts.add(new T(body, "WEAPON GROUPS", rx, (r[1] + r[3]) * 0.5f + body.lineHeight * s * 0.85f, TEXT, a, -1));
					texts.add(new T(body, mod + "+#  AUTOFIRE", rx, (r[1] + r[3]) * 0.5f - body.lineHeight * s * 0.15f, DIM, a, -1));
				} else {
					texts.add(new T(body, c.label, cxm, r[1] - 5f * s, TEXT, 0.95f * a, 0));
				}
			}
		}

		// Mouse, right half.
		float mx = x0 + 720f * s, my = ruleY - 205f * s;
		drawMouse(mx, my, s, a, command);

		// Strip: bound keys the picture doesn't show, then vanilla's own.
		strip.put("TAB", "COMMAND MAP");
		float sx = x0 + 34f * s, sy = y0 + 46f * s;
		GL11.glBegin(GL11.GL_LINES);
		color(EDGE, 0.35f * a);
		line(x0 + 20f * s, sy + 26f * s, x1 - 20f * s, sy + 26f * s);
		GL11.glEnd();
		for (Map.Entry<String, String> e : strip.entrySet()) {
			String cap = capName(e.getKey(), mod);
			float tw = head.width(cap, s) + 14f * s;
			float[] r = {sx, sy - 13f * s, sx + tw, sy + 13f * s};
			GL11.glBegin(GL11.GL_QUADS);
			GL11.glColor4f(CAP_FILL[0], CAP_FILL[1], CAP_FILL[2], 0.55f * a);
			GL11.glVertex2f(r[0], r[1]);
			GL11.glVertex2f(r[2], r[1]);
			GL11.glVertex2f(r[2], r[3]);
			GL11.glVertex2f(r[0], r[3]);
			GL11.glEnd();
			GL11.glBegin(GL11.GL_LINES);
			color(EDGE, 0.9f * a);
			rect(r);
			GL11.glEnd();
			texts.add(new T(head, cap, (r[0] + r[2]) * 0.5f, sy + head.lineHeight * s * 0.42f, KEY, a, 0));
			float lx = r[2] + 8f * s;
			texts.add(new T(body, e.getValue(), lx, sy + body.lineHeight * s * 0.45f, TEXT, a, -1));
			sx = lx + body.width(e.getValue(), s) + 26f * s;
		}

		drawTexts();
		GL11.glDisable(GL11.GL_BLEND);
	}

	private static boolean inPicture(String key) {
		if (key.equals("LSHIFT") || key.equals("LMENU") || key.equals("SPACE")) return true;
		for (Object[] l : LAYOUT) if (l[0].equals(key)) return true;
		return false;
	}

	private static float[] capRect(Cap c, float kx, float ky, float pitch, float numPitch, float capW, float capH, float rowH, float s) {
		if (c.row < 0f) { // number row: tight, its own pitch
			float x = kx + c.col * numPitch;
			return new float[] {x, ky - 30f * s, x + 32f * s, ky};
		}
		float top = ky - 52f * s - c.row * rowH;
		float x = kx + c.col * pitch;
		float wdt = c.wide <= 1f ? capW : capW + (c.wide - 1f) * pitch;
		return new float[] {x, top - capH, x + wdt, top};
	}

	private static String capName(String n, String mod) {
		switch (n) {
			case "LSHIFT": return "SHIFT";
			case "LMENU": return MAC ? "OPTION" : "ALT";
			case "SPACE": return "SPACE";
			case "CMDTAP": return mod;
			case "LBRACKET": return "[";
			case "RBRACKET": return "]";
			case "BACKSLASH": return "\\";
			default: return n;
		}
	}

	/** A mouse in outline with callouts: buttons, wheel, and moving it. */
	private void drawMouse(float cx, float cy, float s, float a, boolean command) {
		float mw = 86f * s, mh = 132f * s, r = 40f * s;
		float left = cx - mw * 0.5f, right = cx + mw * 0.5f, top = cy + mh * 0.5f, bot = cy - mh * 0.5f;
		float split = top - 56f * s; // bottom of the buttons
		List<float[]> outline = new ArrayList<>();
		arc(outline, right - r, top - r, r, 0f, 90f);
		arc(outline, left + r, top - r, r, 90f, 180f);
		arc(outline, left + r, bot + r, r, 180f, 270f);
		arc(outline, right - r, bot + r, r, 270f, 360f);
		// button fills
		GL11.glBegin(GL11.GL_TRIANGLE_FAN);
		GL11.glColor4f(CAP_FILL[0], CAP_FILL[1], CAP_FILL[2], 0.35f * a);
		GL11.glVertex2f(cx, cy);
		for (float[] p : outline) GL11.glVertex2f(p[0], p[1]);
		GL11.glVertex2f(outline.get(0)[0], outline.get(0)[1]);
		GL11.glEnd();
		GL11.glBegin(GL11.GL_LINES);
		color(EDGE, 0.9f * a);
		for (int i = 0; i < outline.size(); i++) {
			float[] p = outline.get(i), q = outline.get((i + 1) % outline.size());
			line(p[0], p[1], q[0], q[1]);
		}
		line(left + 2f * s, split, right - 2f * s, split);
		line(cx, split, cx, top);
		// wheel
		float ww = 12f * s, wh = 26f * s, wy = top - 30f * s;
		color(KEY, 0.9f * a);
		rect(new float[] {cx - ww * 0.5f, wy - wh * 0.5f, cx + ww * 0.5f, wy + wh * 0.5f});
		line(cx - ww * 0.5f + 2f * s, wy, cx + ww * 0.5f - 2f * s, wy);
		// callout leaders
		color(EDGE, 0.6f * a);
		float lbY = split + 26f * s, rbY = split + 26f * s;
		float lEnd = left - 50f * s, rEnd = right + 50f * s;
		line(left + 18f * s, lbY, lEnd, lbY);
		dot(left + 18f * s, lbY, 2.5f * s);
		line(right - 18f * s, rbY, rEnd, rbY);
		dot(right - 18f * s, rbY, 2.5f * s);
		line(cx, top + 4f * s, cx, top + 30f * s);
		dot(cx, wy + wh * 0.5f + 1f * s, 2f * s);
		// movement arrows round the body
		float ar = mh * 0.5f + 24f * s;
		color(EDGE, 0.45f * a);
		for (int k = 0; k < 4; k++) {
			double ang = Math.PI * 0.5 * k + Math.PI * 0.25;
			float ex = cx + (float) Math.cos(ang) * (mw * 0.5f + 26f * s), ey = cy - 18f * s + (float) Math.sin(ang) * (ar - 30f * s);
			float dx = (float) Math.cos(ang) * 7f * s, dy = (float) Math.sin(ang) * 7f * s;
			line(ex - dx, ey - dy, ex + dx, ey + dy);
			line(ex + dx, ey + dy, ex + dx - dx * 0.9f + dy * 0.6f, ey + dy - dy * 0.9f - dx * 0.6f);
			line(ex + dx, ey + dy, ex + dx - dx * 0.9f - dy * 0.6f, ey + dy - dy * 0.9f + dx * 0.6f);
		}
		GL11.glEnd();

		String[] lb = command ? new String[] {"LEFT", "SELECT", "DRAG: BOX"} : new String[] {"LEFT", "FIRE GROUP", null};
		String[] rb = command ? new String[] {"RIGHT", "ORDER", "DRAG: HEIGHT"} : new String[] {"RIGHT", "SHIELD / PHASE", null};
		callout(lb, lEnd - 6f * s, lbY, 1, s, a);
		callout(rb, rEnd + 6f * s, rbY, -1, s, a);
		float wTop = top + 30f * s + 4f * s + head.lineHeight * s + body.lineHeight * s;
		texts.add(new T(head, "WHEEL", cx, wTop, KEY, a, 0));
		texts.add(new T(body, command ? "ZOOM  (PRESS: ORBIT)" : "ZOOM  (PRESS: LOOK)", cx, wTop - head.lineHeight * s - 2f * s, TEXT, a, 0));
		if (command) {
			texts.add(new T(body, "HOLD RIGHT, RELEASE", cx, bot - 32f * s, KEY, a, 0));
			texts.add(new T(body, "MORE ORDERS  (RING)", cx, bot - 32f * s - body.lineHeight * s, TEXT, a, 0));
			texts.add(new T(body, "DOUBLE CLICK A SHIP: FOLLOW", cx, bot - 32f * s - body.lineHeight * s * 2.2f, DIM, a, 0));
		} else {
			texts.add(new T(body, "MOVE", cx, bot - 32f * s, KEY, a, 0));
			texts.add(new T(body, "STEER / AIM", cx, bot - 32f * s - body.lineHeight * s, TEXT, a, 0));
		}
	}

	/** Part name in key yellow, the action below it, an optional extra line; anchored at x by align. */
	private void callout(String[] lines, float x, float y, int align, float s, float a) {
		float top = y + head.lineHeight * s * 0.5f;
		texts.add(new T(head, lines[0], x, top, KEY, a, align));
		texts.add(new T(body, lines[1], x, top - head.lineHeight * s - 2f * s, TEXT, a, align));
		if (lines[2] != null) texts.add(new T(body, lines[2], x, top - head.lineHeight * s - body.lineHeight * s - 4f * s, DIM, a, align));
	}

	private static void arc(List<float[]> out, float cx, float cy, float r, float from, float to) {
		int seg = 8;
		for (int i = 0; i <= seg; i++) {
			double t = Math.toRadians(from + (to - from) * i / seg);
			out.add(new float[] {cx + (float) Math.cos(t) * r, cy + (float) Math.sin(t) * r});
		}
	}

	private static void dot(float x, float y, float r) {
		line(x - r, y, x + r, y);
		line(x, y - r, x, y + r);
	}

	private static void rect(float[] r) {
		line(r[0], r[1], r[2], r[1]);
		line(r[2], r[1], r[2], r[3]);
		line(r[2], r[3], r[0], r[3]);
		line(r[0], r[3], r[0], r[1]);
	}

	private static void color(float[] c, float a) {
		GL11.glColor4f(c[0], c[1], c[2], a);
	}

	private static void line(float x0, float y0, float x1, float y1) {
		GL11.glVertex2f(x0, y0);
		GL11.glVertex2f(x1, y1);
	}

	private void drawTexts() {
		fx.set("u_mode", 3);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		for (BitmapFont bf : new BitmapFont[] {title, head, body}) {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, bf.texture);
			GL11.glBegin(GL11.GL_QUADS);
			for (T t : texts) {
				if (t.f != bf) continue;
				float sc = textScale;
				float wdt = bf.width(t.s, sc);
				float x = t.align == 0 ? t.x - wdt * 0.5f : t.align > 0 ? t.x - wdt : t.x;
				GL11.glColor4f(0f, 0f, 0f, t.a * 0.6f);
				bf.emit(t.s, x + sc, t.y - sc, sc);
				GL11.glColor4f(t.c[0], t.c[1], t.c[2], t.a);
				bf.emit(t.s, x, t.y, sc);
			}
			GL11.glEnd();
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		fx.set("u_mode", 0);
	}

	/** The card's whole-pixel scale this frame (2 on tall screens), for its text. */
	private float textScale = 1f;
}
