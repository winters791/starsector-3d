package s3d.render;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import s3d.core.Combat;
import s3d.core.Effect;
import s3d.core.MouseSteering;
import s3d.core.WeaponMount;
import s3d.core.WeaponSpec3D;
import s3d.core.PilotInput;
import s3d.core.Ship3D;
import s3d.core.Sim;
import s3d.math.Mat4;
import s3d.math.Vec3;

/**
 * 2D overlay in screen pixels: mouse reticle, nose (boresight) and velocity markers, throttle,
 * target brackets, battlefield minimap, attitude ball, hull/armor/flux panels
 * and status text.
 *
 * To keep fleet fights readable, full brackets only go on ships that matter right now (your
 * target, enemies within your gun range, ships that hit you in the last few seconds); others get
 * a small dot, wrecks nothing. Nothing is drawn for off-screen ships: the minimap shows them.
 */
public final class HudRenderer {

	/** Extra text supplied by the host each frame. */
	public static final class Info {
		/** Short warnings shown above the status line (retreat orders, command transfer). */
		public final List<String> alerts = new ArrayList<>();
		/** Performance readout, under the minimap. */
		public final List<String> perf = new ArrayList<>();
		public String toast;
		public float toastAlpha;
		/** Vanilla's status list for your ship (ECM, peak time, CR, skills...), bottom entry first; drawn above your hologram. */
		public final List<Status> statuses = new ArrayList<>();
		/** Your side's command points left (tactical view status line), or -1 if there are none to show. */
		public int commandPoints = -1;
	}

	/** One entry of vanilla's status list: its icon sprite, title and data lines, debuff, fade. */
	public static final class Status {
		public String icon, title, data;
		public boolean debuff;
		public float alpha = 1f;
	}

	private static final float[] CYAN = {0.55f, 0.9f, 1f};
	private static final float[] GREEN = {0.45f, 1f, 0.55f};
	private static final float[] RED = {1f, 0.4f, 0.35f};
	private static final float[] AMBER = {1f, 0.8f, 0.35f};
	private static final float[] GRAY = {0.55f, 0.58f, 0.62f};
	private static final float[] SHIELD = {0.55f, 0.8f, 1f};
	private static final float[] FLUX_SOFT = {0.62f, 0.7f, 1f};
	private static final float[] FLUX_HARD = {0.42f, 0.38f, 1f};
	/** Kill markers around the reticle: red-orange. */
	private static final float[] PIPPER = {1f, 0.42f, 0.18f};
	/** Gun "+" crosshairs: saturated yellow (with a dark rim), readable on anything. */
	private static final float[] CROSSHAIR = {1f, 0.93f, 0.05f};
	/** Your ship panel: the target hologram's cyan, bright for what's selected or up, dim for what's off. */
	private static final float[] HOLO = {0.45f, 0.85f, 1f};
	private static final float[] HOLO_HI = {0.8f, 0.97f, 1f};
	private static final float[] HOLO_DIM = {0.32f, 0.58f, 0.78f};
	/** Your ship panel's divider: pixels right per pixel up, as vanilla's slants. */
	private static final float PANEL_SLANT = 0.4f;
	/** A ship that damaged you within this many seconds counts as an attacker (bracket + arrow). */
	private static final float ATTACKER_MEMORY = 5f;

	private final Shader fx;
	private final GlyphFont font;
	/**
	 * Vanilla's combat HUD font (Victor's Pixel Font) for your ship panel and the status list, at a
	 * whole-pixel scale (2x from 1800 px tall); null if it couldn't be loaded (the HUD font instead).
	 */
	private final BitmapFont vanilla;
	/** Vanilla's button font (InsigniaLT 17), for the tactical view's fleet buttons; null = the HUD font. */
	private final BitmapFont buttonFont;
	private float vanillaScale = 1f;
	private final Mat4 ortho = new Mat4();
	private final float[] clip = new float[4];
	/** This frame's icons (weapon groups, statuses), drawn after the bars and before the text. */
	private final List<IconQuad> icons = new ArrayList<>();

	private static final class IconQuad {
		final int tex;
		final float x0, y0, x1, y1, a;
		final float[] c;

		IconQuad(int tex, float x0, float y0, float x1, float y1, float[] c, float a) {
			this.tex = tex;
			this.x0 = x0;
			this.y0 = y0;
			this.x1 = x1;
			this.y1 = y1;
			this.c = c;
			this.a = a;
		}
	}

	HudRenderer(Shader fx, GlyphFont font, BitmapFont vanilla, BitmapFont buttonFont) {
		this.fx = fx;
		this.font = font;
		this.vanilla = vanilla;
		this.buttonFont = buttonFont;
	}

	public void render(Sim sim, PilotInput in, Info info, int w, int h) {
		GL11.glViewport(0, 0, w, h);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		ortho.ortho(0, w, 0, h, -1, 1);
		fx.use().set("u_vp", ortho).set("u_mode", 0).set("u_tex", 0);

		List<Text> texts = new ArrayList<>();
		Ship3D p = sim.viewer();
		float cx = w * 0.5f, cy = h * 0.5f;
		vanillaScale = h >= 1800 ? 2f : 1f;
		icons.clear();

		List<float[]> bars = new ArrayList<>();
		float mapSize = sim.tuning.minimapSize;
		if (p != null && mapSize > 0f) {
			// Minimap backdrop goes under the line pass.
			GL11.glBegin(GL11.GL_QUADS);
			GL11.glColor4f(0.02f, 0.04f, 0.08f, 0.6f);
			GL11.glVertex2f(14f, h - 14f - mapSize);
			GL11.glVertex2f(14f + mapSize, h - 14f - mapSize);
			GL11.glVertex2f(14f + mapSize, h - 14f);
			GL11.glVertex2f(14f, h - 14f);
			GL11.glEnd();
		}
		if (p != null && !sim.spectating) {
			drawShipPanel(sim, texts, bars); // its own backdrop and lines, under the rest
			drawStatusList(info.statuses, texts);
		}
		boolean commanding = sim.camera.tactical; // tactical view: only ships, objectives and orders
		if (commanding) { // load the ring's icons now: a texture upload inside glBegin/glEnd silently fails
			for (s3d.core.CommandView.Kind k : s3d.core.CommandView.Kind.values()) {
				HudIcons.get(ringIcon(k, 1));
				if (k == s3d.core.CommandView.Kind.ESCORT) {
					HudIcons.get(ringIcon(k, 2));
					HudIcons.get(ringIcon(k, 3));
				}
			}
		}
		GL11.glBegin(GL11.GL_LINES);
		if (p != null) {
			if (!commanding) {
				drawTargets(sim, w, h, texts);
				drawFleetMarkers(sim, w, h, texts);
			}
			drawObjectives(sim, w, h, texts);
			if (!sim.spectating) {
				if (!sim.camera.tactical) {
					drawFlightMarkers(sim, in, w, h, texts);
					drawHitMarkers(sim, in, w, h);
				}
				if (!sim.camera.tactical) drawLead(sim, w, h, texts, bars);
			}
			if (!commanding) {
				drawThrottle(sim, w, h, texts);
				drawAttitude(p, w - 95f, 95f, 60f);
			}
			if (mapSize > 0f) drawMinimap(sim, 14f, h - 14f - mapSize, mapSize, w / (float) h, texts);
			if (!sim.spectating && !sim.camera.tactical) drawTargetPanel(sim, sim.aimTarget, w, h, texts, bars); // spectating / commanding: no target display
			if (!sim.spectating && !commanding) drawDamageDirection(sim, w, h);
			drawWarnings(sim, w, h, texts);
			if (!commanding) drawIncoming(sim, w, h, texts);
			else drawCommand(sim, in, w, h, texts, bars);
		}
		GL11.glEnd();
		if (!bars.isEmpty()) {
			GL11.glBegin(GL11.GL_QUADS);
			for (float[] b : bars) {
				GL11.glColor4f(b[4], b[5], b[6], b[7]);
				GL11.glVertex2f(b[0], b[1]);
				GL11.glVertex2f(b[2], b[1]);
				GL11.glVertex2f(b[2], b[3]);
				GL11.glVertex2f(b[0], b[3]);
			}
			GL11.glEnd();
		}
		if (p != null && sim.camera.tactical && sim.command.fleetButtons) drawFleetButtons(sim.command, w, h, texts);
		drawIcons();
		if (sim.tuning.damageNumbers && !commanding) drawDamageNumbers(sim, w, h, texts);

		// Status line
		StringBuilder status = new StringBuilder();
		if (sim.paused) status.append("PAUSED   ");
		if (sim.spectating) status.append("SPECTATING   ");
		if (sim.worldTimeScale < 0.99f) status.append(String.format("TIME x%.1f   ", 1f / sim.worldTimeScale));
		if (sim.controls.autoLevel != sim.tuning.autoLevelDefault) status.append(sim.controls.autoLevel ? "AUTO-LEVEL   " : "AUTO-LEVEL OFF   ");
		// Tactical view: no TACTICAL / CP words (user); only a refused order's flag (NO CP) shows briefly.
		if (sim.camera.tactical && sim.command.flag != null && sim.command.flagTime > 0f) status.append(sim.command.flag).append("   ");
		if (in.freeLook) status.append("FREE LOOK   ");
		if (status.length() > 0) texts.add(new Text(status.toString().trim(), cx, 30f, AMBER, 1f, true));

		float y = 30f + lineH() * 1.2f;
		for (int i = info.alerts.size() - 1; i >= 0; i--) {
			texts.add(new Text(info.alerts.get(i), cx, y, AMBER, 1f, true));
			y += lineH();
		}
		y = h - 12f - (p != null && mapSize > 0f ? mapSize + 12f : 0f);
		for (String s : info.perf) {
			texts.add(new Text(s, 14f, y, CYAN, 0.8f, false, 0.85f));
			y -= lineH() * 0.85f;
		}
		if (info.toast != null && info.toastAlpha > 0f) {
			texts.add(new Text(info.toast, cx, cy + h * 0.28f, AMBER, info.toastAlpha, true));
		}

		drawTexts(texts);
		GL11.glDisable(GL11.GL_BLEND);
	}

	// ---- flight markers ------------------------------------------------------------------

	private void drawFlightMarkers(Sim sim, PilotInput in, int w, int h, List<Text> texts) {
		Ship3D p = sim.player;
		float cx = w * 0.5f, cy = h * 0.5f;

		// Centre dot (the full-deflection ring is gone: it read as a halo around the ship).
		color(CYAN, 0.35f);
		circle(cx, cy, 2.5f, 8);

		// Mouse reticle, tethered to centre (stronger when steering harder).
		if (sim.steeringActive) {
			float[] r = MouseSteering.clampedReticle(in.mouseX, in.mouseY, w, h, sim.tuning);
			float mag = (float) Math.min(1.0, Math.hypot(sim.stickX, sim.stickY));
			color(CYAN, 0.1f + 0.35f * mag);
			line(cx, cy, r[0], r[1]);
			float rr = sim.tuning.reticleRadiusPx;
			color(CYAN, 0.9f);
			circle(r[0], r[1], rr, 20);
			for (int k = 0; k < 4; k++) {
				double a = k * Math.PI / 2;
				float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
				line(r[0] + dx * (rr - 4f), r[1] + dy * (rr - 4f), r[0] + dx * (rr + 4f), r[1] + dy * (rr + 4f));
			}
		}

		// Boresight: where the nose points (far ahead).
		Vec3 f = p.rot.forward(new Vec3());
		float far = Math.max(2000f, p.radius * 40f);
		float[] nose = project(sim, p.pos.x + f.x * far, p.pos.y + f.y * far, p.pos.z + f.z * far, w, h);
		if (nose != null) {
			color(GREEN, 0.9f);
			line(nose[0] - 14f, nose[1], nose[0] - 5f, nose[1]);
			line(nose[0] + 5f, nose[1], nose[0] + 14f, nose[1]);
			line(nose[0], nose[1] + 5f, nose[0], nose[1] + 12f);
			circle(nose[0], nose[1], 2f, 6);
		}

		// Velocity vector (prograde) — the gap between this and the nose is your drift.
		float speed = p.speed();
		if (speed > 3f) {
			Vec3 v = new Vec3(p.vel).normalize();
			float[] pro = project(sim, p.pos.x + v.x * far, p.pos.y + v.y * far, p.pos.z + v.z * far, w, h);
			if (pro != null && onScreen(pro, w, h, 0)) {
				color(AMBER, 0.85f);
				circle(pro[0], pro[1], 7f, 14);
				line(pro[0] - 13f, pro[1], pro[0] - 7f, pro[1]);
				line(pro[0] + 7f, pro[1], pro[0] + 13f, pro[1]);
				line(pro[0], pro[1] + 7f, pro[0], pro[1] + 13f);
			}
			float[] retro = project(sim, p.pos.x - v.x * far, p.pos.y - v.y * far, p.pos.z - v.z * far, w, h);
			if (retro != null && onScreen(retro, w, h, 0)) {
				color(AMBER, 0.6f);
				circle(retro[0], retro[1], 7f, 14);
				line(retro[0] - 5f, retro[1] - 5f, retro[0] + 5f, retro[1] + 5f);
				line(retro[0] - 5f, retro[1] + 5f, retro[0] + 5f, retro[1] - 5f);
			}
		}
	}

	/**
	 * Hit confirmation around the reticle, no text: short diagonal ticks when your shots land (white
	 * on hull or armor, blue on shields), bigger red-orange ones that spread out when you kill something.
	 */
	private void drawHitMarkers(Sim sim, PilotInput in, int w, int h) {
		float[] r = MouseSteering.clampedReticle(in.mouseX, in.mouseY, w, h, sim.tuning);
		float rr = sim.tuning.reticleRadiusPx;
		float kill = sim.time - sim.combat.lastKillAt, hull = sim.time - sim.combat.lastHullHitAt, shield = sim.time - sim.combat.lastShieldHitAt;
		float killLife = sim.combat.lastKillShip ? 0.8f : 0.5f;
		if (kill >= 0f && kill < killLife) {
			float t = kill / killLife, spread = (sim.combat.lastKillShip ? 14f : 8f) * (float) Math.sqrt(t);
			color(PIPPER, 1f - t * t);
			diagonalTicks(r[0], r[1], rr + 6f + spread, rr + (sim.combat.lastKillShip ? 22f : 16f) + spread);
		} else if (hull >= 0f && hull < 0.22f) {
			color(new float[] {1f, 0.95f, 0.85f}, 1f - hull / 0.22f);
			diagonalTicks(r[0], r[1], rr + 5f, rr + 12f);
		} else if (shield >= 0f && shield < 0.22f) {
			color(SHIELD, 0.9f * (1f - shield / 0.22f));
			diagonalTicks(r[0], r[1], rr + 5f, rr + 10f);
		}
	}

	/**
	 * Hull damage you take: a red arc around your ship on the side it came from (the attacker's
	 * direction on screen), stronger for heavier hits; a faint full ring when it came from straight
	 * ahead or behind.
	 */
	private void drawDamageDirection(Sim sim, int w, int h) {
		Ship3D p = sim.player;
		float age = sim.time - sim.combat.hurtAt;
		if (p == null || p.destroyed || age < 0f || age > 0.7f) return;
		float[] c = project(sim, p.pos.x, p.pos.y, p.pos.z, w, h);
		if (c == null) return;
		float focal = (h * 0.5f) / (float) Math.tan(sim.camera.fovYRad * 0.5);
		float rr = Math.max(70f, p.radius * focal / Math.max(1f, p.pos.distance(sim.camera.pos)) * 1.25f + 24f);
		Vec3 rel = new Vec3(sim.combat.hurtFrom).sub(p.pos);
		float dx = rel.dot(sim.camera.rot.right(new Vec3())), dy = rel.dot(sim.camera.rot.up(new Vec3()));
		float len = (float) Math.hypot(dx, dy), relLen = Math.max(1f, rel.length());
		float strength = Math.max(0.4f, Math.min(1f, sim.combat.hurtAmount / Math.max(1f, p.hullMax * 0.03f)));
		float a = (1f - age / 0.7f) * strength;
		if (len < relLen * 0.25f) { // along the view axis: no side to point at
			color(RED, a * 0.35f);
			circle(c[0], c[1], rr, 40);
			return;
		}
		double mid = Math.atan2(dy, dx), half = Math.toRadians(22);
		for (int k = 0; k < 3; k++) {
			color(RED, a * (k == 1 ? 0.9f : 0.45f));
			float r = rr + k * 2.5f;
			int seg = 10;
			for (int i = 0; i < seg; i++) {
				double a0 = mid - half + 2 * half * i / seg, a1 = mid - half + 2 * half * (i + 1) / seg;
				line(c[0] + (float) Math.cos(a0) * r, c[1] + (float) Math.sin(a0) * r, c[0] + (float) Math.cos(a1) * r, c[1] + (float) Math.sin(a1) * r);
			}
		}
	}

	private static void diagonalTicks(float cx, float cy, float r0, float r1) {
		float k = 0.7071f;
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sy = -1; sy <= 1; sy += 2) line(cx + sx * r0 * k, cy + sy * r0 * k, cx + sx * r1 * k, cy + sy * r1 * k);
		}
	}

	private void drawThrottle(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		float x = 30f;
		float y0 = h * 0.5f - 65f, y1 = h * 0.5f + 65f;
		float zero = y0 + (y1 - y0) / 3f;
		float rev = Math.max(0.01f, sim.tuning.reverseSpeedMult);
		// Frame
		color(CYAN, 0.35f);
		line(x - 3f, y0, x - 3f, y1);
		line(x + 3f, y0, x + 3f, y1);
		line(x - 6f, zero, x + 6f, zero);
		// Throttle fill
		float th = p.throttle;
		float ty = th >= 0f ? zero + th * (y1 - zero) : zero + th * (zero - y0);
		color(th >= 0f ? CYAN : AMBER, 0.85f);
		for (float yy = Math.min(zero, ty); yy <= Math.max(zero, ty); yy += 2f) line(x - 2f, yy, x + 2f, yy);
		// Actual forward speed marker
		float fs = p.params != null && p.params.fwdSpeed > 0 ? p.forwardSpeed() / p.params.fwdSpeed : 0f;
		float sy = fs >= 0f ? zero + Math.min(1.2f, fs) * (y1 - zero) : zero + Math.max(-1f, fs / rev) * (zero - y0);
		color(GREEN, 0.95f);
		line(x - 9f, sy, x - 4f, sy);
		line(x + 4f, sy, x + 9f, sy);
		texts.add(new Text(String.format("%.0f", p.speed()), x, y0 - 6f, GREEN, 0.9f, true, 0.75f));
		texts.add(new Text(String.format("%.0f%%", th * 100f), x, y1 + 16f, CYAN, 0.85f, true, 0.75f));
	}

	/** Attitude ball: horizon line tilts with roll and slides with pitch. */
	private void drawAttitude(Ship3D p, float cx, float cy, float r) {
		Vec3 f = p.rot.forward(new Vec3()), right = p.rot.right(new Vec3()), up = p.rot.up(new Vec3());
		float pitch = (float) Math.asin(Math.max(-1f, Math.min(1f, f.y)));
		float roll = (float) Math.atan2(right.y, up.y);
		color(CYAN, 0.35f);
		circle(cx, cy, r, 40);
		float off = -pitch / (float) (Math.PI / 2) * r;
		float ang = -roll;
		float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
		// Horizon: chord perpendicular to the rotated vertical, offset by pitch.
		float hx = -sa * off, hy = ca * off;
		float half = (float) Math.sqrt(Math.max(0f, r * r - off * off));
		color(AMBER, 0.8f);
		if (half > 1f) line(cx + hx - ca * half, cy + hy - sa * half, cx + hx + ca * half, cy + hy + sa * half);
		// Fixed ship symbol
		color(GREEN, 0.9f);
		line(cx - 22f, cy, cx - 8f, cy);
		line(cx + 8f, cy, cx + 22f, cy);
		circle(cx, cy, 3f, 8);
	}

	// ---- targets ---------------------------------------------------------------------------

	private void drawTargets(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		float focal = (h * 0.5f) / (float) Math.tan(sim.camera.fovYRad * 0.5);
		List<Ship3D> others = new ArrayList<>(sim.ships);
		others.remove(p);
		others.removeIf(o -> o.destroyed); // wrecks: no markers
		// Gun/beam reach; missiles only count if that's all the ship has (their range covers the whole fight).
		float reach = 0f, missileReach = 0f;
		for (WeaponMount m : p.weapons) {
			if (!m.spec.supported) continue;
			float r = m.spec.range * sim.tuning.weaponRangeMult;
			if (m.spec.kind == WeaponSpec3D.Kind.MISSILE) missileReach = Math.max(missileReach, r);
			else reach = Math.max(reach, r);
		}
		if (reach <= 0f) reach = missileReach;
		for (Ship3D s : others) {
			float[] c = projectRaw(sim, s.pos.x, s.pos.y, s.pos.z, w, h);
			float dist = s.pos.distance(p.pos);
			float camDist = s.pos.distance(sim.camera.pos);
			boolean isTarget = s == sim.target;
			boolean isAim = s == sim.aimTarget;
			float[] col = s.destroyed ? GRAY : isAim ? AMBER : s.owner == p.owner ? GREEN : RED;
			String label = isTarget || isAim ? s.name + "  " + formatDist(dist) : null; // names only on what you're aiming at
			boolean attacker = s.owner != p.owner && sim.time - s.lastHitPlayerAt < ATTACKER_MEMORY;
			boolean relevant = isTarget || isAim || attacker || s.owner != p.owner && dist <= reach;
			if (attacker && !isAim) col = RED;
			boolean visible = c[2] > 0f && onScreen(c, w, h, 20f);
			if (s.fighter && !isTarget && !isAim) {
				// Fighters swarm: a small diamond on enemy fighters near you or shooting at you, nothing else.
				if (visible && s.owner != p.owner && (attacker || dist <= reach)) {
					color(col, 0.7f);
					float k = 3.5f;
					line(c[0], c[1] + k, c[0] + k, c[1]);
					line(c[0] + k, c[1], c[0], c[1] - k);
					line(c[0], c[1] - k, c[0] - k, c[1]);
					line(c[0] - k, c[1], c[0], c[1] + k);
				}
				continue;
			}
			if (!visible) continue; // the minimap shows it
			if (!relevant) {
				color(col, 0.5f);
				line(c[0] - 2f, c[1], c[0] + 2f, c[1]);
				line(c[0], c[1] - 2f, c[0], c[1] + 2f);
				continue;
			}
			float rr = Math.max(10f, s.radius * focal / Math.max(1f, camDist));
			float k = Math.min(rr * 0.35f, 14f);
			color(col, 0.85f);
			corner(c[0] - rr, c[1] + rr, k, -k);
			corner(c[0] + rr, c[1] + rr, -k, -k);
			corner(c[0] - rr, c[1] - rr, k, k);
			corner(c[0] + rr, c[1] - rr, -k, k);
			if (isTarget) {
				float rr2 = rr + 4f;
				corner(c[0] - rr2, c[1] + rr2, k, -k);
				corner(c[0] + rr2, c[1] + rr2, -k, -k);
				corner(c[0] - rr2, c[1] - rr2, k, k);
				corner(c[0] + rr2, c[1] - rr2, -k, k);
			}
			if (label != null) texts.add(new Text(label, c[0], c[1] - rr - 6f, col, 0.8f, true));
		}
	}

	/** A faint diamond on each fleet's centre when it's on screen (green yours, red the enemy's); off screen the minimap shows it. */
	private void drawFleetMarkers(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		for (int owner = 0; owner < 2; owner++) {
			Vec3 c = sim.fleetCentre(owner);
			if (c == null) continue;
			boolean mine = owner == p.owner;
			if (mine && c.distance(p.pos) < 900f) continue; // you're with your fleet; no marker needed
			float[] sc = projectRaw(sim, c.x, c.y, c.z, w, h);
			if (sc[2] <= 0f || !onScreen(sc, w, h, 30f)) continue;
			color(mine ? GREEN : RED, 0.35f);
			float k = 7f;
			line(sc[0], sc[1] + k, sc[0] + k, sc[1]);
			line(sc[0] + k, sc[1], sc[0], sc[1] - k);
			line(sc[0], sc[1] - k, sc[0] - k, sc[1]);
			line(sc[0] - k, sc[1], sc[0], sc[1] + k);
		}
	}

	/**
	 * Battle objectives: a square on each (green yours, red the enemy's, grey nobody's) with its
	 * name and distance. A smaller square inside in the other side's colour: they're alone on it
	 * and taking it; an amber cross: both sides are on it.
	 */
	private void drawObjectives(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		for (s3d.core.Objective3D o : sim.objectives) {
			float[] sc = project(sim, o.pos.x, o.pos.y, o.pos.z, w, h);
			if (sc == null || !onScreen(sc, w, h, 20f)) continue; // the minimap shows it
			float[] col = o.owner < 0 ? GRAY : o.owner == p.owner ? GREEN : RED;
			square(col, 0.8f, sc[0], sc[1], 9f);
			int taker = o.holder();
			if (taker >= 0 && taker != o.owner) square(taker == p.owner ? GREEN : RED, 0.9f, sc[0], sc[1], 4f);
			else if (o.present[0] > 0 && o.present[1] > 0) {
				color(AMBER, 0.9f);
				line(sc[0] - 4f, sc[1] - 4f, sc[0] + 4f, sc[1] + 4f);
				line(sc[0] - 4f, sc[1] + 4f, sc[0] + 4f, sc[1] - 4f);
			}
			String label = sim.camera.tactical ? o.name.toUpperCase() : o.name.toUpperCase() + "  " + formatDist(o.flatDistance(p.pos)); // tactical: no distance from your ship
			texts.add(new Text(label, sc[0], sc[1] - 24f, col, sim.camera.tactical ? 0.55f : 0.75f, true, 0.75f));
		}
	}

	// ---- tactical view: selection and orders ------------------------------------------------

	/** Order colours: attack red, escort green, hold / capture cyan, avoid amber, cancel grey, retreat amber. */
	private static float[] orderColour(s3d.core.CommandView.Kind k) {
		switch (k) {
			case ENGAGE: return RED;
			case ESCORT: return GREEN;
			case AVOID:
			case RETREAT: return AMBER;
			case CLEAR: return GRAY;
			default: return CYAN;
		}
	}

	private static float[] orderColour(s3d.core.Order3D.Kind k) {
		switch (k) {
			case ATTACK: return RED;
			case ESCORT: return GREEN;
			case AVOID: return AMBER;
			default: return CYAN;
		}
	}

	/**
	 * The tactical view's own marks: a line from each of your ships with an order to where the order
	 * sends it (bright for the selected ones), selection rings, the hover ring, the selection box,
	 * the point and height being dragged, the ring menu, and a fading ring where an order just went.
	 */
	private void drawCommand(Sim sim, PilotInput in, int w, int h, List<Text> texts, List<float[]> bars) {
		s3d.core.CommandView cv = sim.command;
		Ship3D p = sim.player;
		float focal = (h * 0.5f) / (float) Math.tan(sim.camera.fovYRad * 0.5);
		for (Ship3D s : sim.ships) {
			if (p == null || !s.warship() || s.owner != p.owner) continue;
			boolean sel = cv.selected.contains(s);
			float[] c = project(sim, s.pos.x, s.pos.y, s.pos.z, w, h);
			if (s.order != null) {
				Vec3 a = s.order.anchor();
				float[] t = project(sim, a.x, a.y, a.z, w, h);
				if (c != null && t != null) {
					float[] col = orderColour(s.order.kind);
					color(col, sel ? 0.75f : 0.3f);
					dashed(c[0], c[1], t[0], t[1], 10f);
					if (s.order.kind == s3d.core.Order3D.Kind.HOLD || s.order.kind == s3d.core.Order3D.Kind.AVOID && s.order.ship == null) {
						diamond(t[0], t[1], 6f);
						stalk(sim, a, cv.planeY, w, h, col, sel ? 0.5f : 0.2f);
					}
				}
			}
			if (s.retreatOrdered && c != null) {
				color(AMBER, 0.7f);
				line(c[0] - 5f, c[1] - 9f, c[0], c[1] - 14f);
				line(c[0] + 5f, c[1] - 9f, c[0], c[1] - 14f);
			}
			if (sel && c != null) {
				float rr = Math.max(12f, s.radius * focal / Math.max(1f, s.pos.distance(sim.camera.pos))) + 6f;
				color(GREEN, 0.9f);
				circle(c[0], c[1], rr, 28);
				circle(c[0], c[1], rr + 3f, 28);
			}
		}
		if (cv.hover != null) {
			float[] c = project(sim, cv.hover.pos.x, cv.hover.pos.y, cv.hover.pos.z, w, h);
			if (c != null) {
				float rr = Math.max(12f, cv.hover.radius * focal / Math.max(1f, cv.hover.pos.distance(sim.camera.pos))) + 10f;
				color(p != null && cv.hover.owner == p.owner ? (cv.hover.ally ? GRAY : GREEN) : RED, 0.55f);
				circle(c[0], c[1], rr, 28);
				texts.add(new Text(cv.hover.name.toUpperCase(), c[0], c[1] - rr - 8f, cv.hover.owner == (p != null ? p.owner : 0) ? GREEN : RED, 0.8f, true, 0.75f));
			}
		} else if (cv.hoverObjective != null) {
			float[] c = project(sim, cv.hoverObjective.pos.x, cv.hoverObjective.pos.y, cv.hoverObjective.pos.z, w, h);
			if (c != null) {
				color(CYAN, 0.7f);
				circle(c[0], c[1], 16f, 20);
			}
		}
		if (cv.boxing) {
			color(GREEN, 0.7f);
			line(cv.boxX0, cv.boxY0, cv.boxX1, cv.boxY0);
			line(cv.boxX1, cv.boxY0, cv.boxX1, cv.boxY1);
			line(cv.boxX1, cv.boxY1, cv.boxX0, cv.boxY1);
			line(cv.boxX0, cv.boxY1, cv.boxX0, cv.boxY0);
		}
		if (cv.gesture == s3d.core.CommandView.Gesture.PRESSED && cv.groundValid) {
			// Right button down on empty space: the spot, a short up/down tick (drag to set its height),
			// and an arc filling round the cursor while held still (let go when full: the ring opens).
			float[] g = project(sim, cv.ground.x, cv.ground.y, cv.ground.z, w, h);
			if (g != null) {
				color(CYAN, 0.7f);
				ellipse(g[0], g[1], 14f, 6f, 20);
				line(g[0], g[1] + 8f, g[0], g[1] + 22f);
				line(g[0] - 4f, g[1] + 18f, g[0], g[1] + 22f);
				line(g[0] + 4f, g[1] + 18f, g[0], g[1] + 22f);
			}
		}
		float hold = cv.ringHoldFraction();
		if (hold > 0.05f) {
			color(CYAN, 0.25f + 0.5f * hold);
			int seg = Math.max(1, (int) (24 * hold));
			for (int i = 0; i < seg; i++) {
				double a0 = Math.PI / 2 - i * 2 * Math.PI / 24, a1 = Math.PI / 2 - (i + 1) * 2 * Math.PI / 24;
				line(cv.pressX + (float) Math.cos(a0) * 16f, cv.pressY + (float) Math.sin(a0) * 16f,
						cv.pressX + (float) Math.cos(a1) * 16f, cv.pressY + (float) Math.sin(a1) * 16f);
			}
		}
		if (cv.gesture == s3d.core.CommandView.Gesture.HEIGHT && cv.groundValid) {
			Vec3 top = new Vec3(cv.ground.x, cv.ground.y + cv.lift, cv.ground.z);
			float[] g = project(sim, cv.ground.x, cv.ground.y, cv.ground.z, w, h);
			float[] t = project(sim, top.x, top.y, top.z, w, h);
			if (g != null) {
				color(CYAN, 0.5f);
				ellipse(g[0], g[1], 14f, 6f, 20);
			}
			if (g != null && t != null) {
				color(CYAN, 0.9f);
				line(g[0], g[1], t[0], t[1]);
				diamond(t[0], t[1], 8f);
				texts.add(new Text((cv.lift >= 0f ? "+" : "") + formatDist(cv.lift), t[0] + 14f, t[1] - 4f, CYAN, 0.9f, false, 0.8f));
			}
		}
		if (cv.gesture == s3d.core.CommandView.Gesture.RING) {
			int n = cv.ring.size();
			color(CYAN, 0.35f);
			circle(cv.pressX, cv.pressY, 6f, 12);
			for (int i = 0; i < n; i++) {
				double a = Math.PI / 2 - i * 2 * Math.PI / n; // first option on top, then clockwise
				float x = cv.pressX + (float) Math.cos(a) * s3d.core.CommandView.RING_RADIUS_PX;
				float y = cv.pressY + (float) Math.sin(a) * s3d.core.CommandView.RING_RADIUS_PX;
				boolean on = i == cv.ringChoice;
				s3d.core.CommandView.Kind k = cv.ring.get(i);
				float[] col = orderColour(k);
				color(col, on ? 1f : 0.45f);
				circle(x, y, on ? 26f : 22f, 24);
				// Vanilla's order icon in the circle; the word only on the one under the mouse.
				HudIcons.Icon ic = HudIcons.get(ringIcon(k, cv.selected.size()));
				float r = on ? 19f : 16f;
				if (ic != null) icon(ic, x - r, y - r, x + r, y + r, col, on ? 1f : 0.75f);
				else texts.add(new Text(k.name(), x, y - 5f, col, on ? 1f : 0.7f, true, 0.7f));
				if (on && ic != null) texts.add(new Text(k.name(), x, y - 32f, col, 1f, true, 0.7f));
			}
		}
		if (sim.shuttle != null) { // your command shuttle: a diamond on it, a dashed line to where it's going
			float[] c = project(sim, sim.shuttle.pos.x, sim.shuttle.pos.y, sim.shuttle.pos.z, w, h);
			Ship3D to = sim.shuttle.to;
			float[] t = to != null ? project(sim, to.pos.x, to.pos.y, to.pos.z, w, h) : null;
			color(CYAN, 0.9f);
			if (c != null) diamond(c[0], c[1], 6f);
			if (c != null && t != null) {
				color(CYAN, 0.5f);
				dashed(c[0], c[1], t[0], t[1], 6f);
			}
		}
		for (s3d.core.CommandView.Ping ping : cv.pings) {
			float[] c = project(sim, ping.pos.x, ping.pos.y, ping.pos.z, w, h);
			if (c == null) continue;
			float k = ping.age / 1.5f;
			color(orderColour(ping.kind), 0.9f * (1f - k));
			circle(c[0], c[1], 10f + 30f * k, 24);
		}
	}

	/** Vanilla's command-map button colours: dark navy panel, muted cyan frame, pale blue text. */
	private static final float[] BTN_FILL = {0.03f, 0.07f, 0.1f};
	private static final float[] BTN_EDGE = {0.33f, 0.62f, 0.78f};
	private static final float[] BTN_TEXT = {0.72f, 0.86f, 0.98f};
	private static final float[] BTN_CONFIRM = {1f, 0.85f, 0.35f};

	/**
	 * Full assault and full retreat, styled like vanilla's command-map buttons: a dark panel with a
	 * cyan frame (corners cut on the left), the label in vanilla's button font, and vanilla's own icon
	 * (icon_search+destroy, icon_full_retreat) in a framed square on the right. Brighter on hover,
	 * filled while on (full assault) or ordered (full retreat); the first retreat click asks to confirm.
	 * Own pass (fills under frames), after the line pass.
	 */
	private void drawFleetButtons(s3d.core.CommandView cv, int w, int h, List<Text> texts) {
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		for (int i = 0; i < 2; i++) {
			float[] r = s3d.core.CommandView.buttonRect(i, w, h);
			boolean assault = i == s3d.core.CommandView.BUTTON_ASSAULT;
			boolean on = assault ? cv.fullAssault : cv.fullRetreat;
			boolean confirm = !assault && cv.retreatConfirm > 0f && !cv.fullRetreat;
			boolean hot = cv.buttonHover == i;
			float x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3], bh = y1 - y0;
			float side = bh + 6f, gap = 5f;
			float ix1 = x1, ix0 = ix1 - side, iy0 = y0 - 3f, iy1 = y1 + 3f; // icon square, a little taller than the bar
			float lx1 = ix0 - gap, cut = bh * 0.3f;
			float[] lx = {x0 + cut, lx1, lx1, x0 + cut, x0, x0};
			float[] ly = {y1, y1, y0, y0, y0 + cut, y1 - cut};
			float fillA = on ? 0.95f : hot || confirm ? 0.9f : 0.82f;
			float lift = on ? 0.12f : hot || confirm ? 0.05f : 0f;
			// fills
			GL11.glColor4f(BTN_FILL[0] + lift * 0.6f, BTN_FILL[1] + lift * 1.4f, BTN_FILL[2] + lift * 1.8f, fillA);
			GL11.glBegin(GL11.GL_POLYGON);
			for (int k = 0; k < lx.length; k++) GL11.glVertex2f(lx[k], ly[k]);
			GL11.glEnd();
			GL11.glBegin(GL11.GL_QUADS);
			GL11.glVertex2f(ix0, iy0);
			GL11.glVertex2f(ix1, iy0);
			GL11.glVertex2f(ix1, iy1);
			GL11.glVertex2f(ix0, iy1);
			GL11.glEnd();
			// frames
			float edgeA = on || hot || confirm ? 1f : 0.7f;
			float[] edge = confirm ? BTN_CONFIRM : BTN_EDGE;
			GL11.glBegin(GL11.GL_LINES);
			color(edge, edgeA);
			for (int k = 0; k < lx.length; k++) line(lx[k], ly[k], lx[(k + 1) % lx.length], ly[(k + 1) % ly.length]);
			line(ix0, iy0, ix1, iy0);
			line(ix1, iy0, ix1, iy1);
			line(ix1, iy1, ix0, iy1);
			line(ix0, iy1, ix0, iy0);
			color(edge, edgeA * 0.55f);
			float in = 3f;
			line(ix0 + in, iy0 + in, ix1 - in, iy0 + in);
			line(ix1 - in, iy0 + in, ix1 - in, iy1 - in);
			line(ix1 - in, iy1 - in, ix0 + in, iy1 - in);
			line(ix0 + in, iy1 - in, ix0 + in, iy0 + in);
			GL11.glEnd();
			HudIcons.Icon ic = HudIcons.get(assault ? "graphics/warroom/icon_search+destroy.png" : "graphics/warroom/icon_full_retreat.png");
			if (ic != null) icon(ic, ix0 + 5f, iy0 + 5f, ix1 - 5f, iy1 - 5f, confirm ? BTN_CONFIRM : BTN_TEXT, on || hot ? 1f : 0.85f);
			String label = assault ? "Full Assault!" : confirm ? "Confirm Retreat?" : "Full Retreat!";
			float[] tc = confirm ? BTN_CONFIRM : BTN_TEXT;
			float ta = on || hot || confirm ? 1f : 0.85f, cx = (x0 + cut * 0.5f + lx1) * 0.5f;
			if (buttonFont != null) texts.add(new Text(label, cx, (y0 + y1) * 0.5f + buttonFont.lineHeight * 0.5f, tc, ta, true, 1f, buttonFont));
			else texts.add(new Text(label.toUpperCase(), cx, (y0 + y1) * 0.5f + 6f, tc, ta, true, 0.8f));
		}
	}

	/** Vanilla's command-map icon for a ring option (graphics/warroom/taskicons). */
	private static String ringIcon(s3d.core.CommandView.Kind k, int selected) {
		String dir = "graphics/warroom/taskicons/";
		switch (k) {
			case ENGAGE: return dir + "icon_engage.png";
			case ESCORT: return dir + (selected >= 3 ? "icon_escort_heavy.png" : selected == 2 ? "icon_escort_medium.png" : "icon_escort_light.png");
			case CAPTURE: return dir + "icon_capture.png";
			case MOVE: return dir + "icon_rally_task_force.png";
			case AVOID: return dir + "icon_avoid.png";
			case CLEAR: return dir + "icon_search_and_destroy.png";
			case RETREAT: return dir + "icon_retreat.png";
			case TRANSFER: return dir + "icon_transfer_command.png";
			default: return null;
		}
	}

	/** A faint vertical line from a point down (or up) to the tactical view's ground plane, with a small foot. */
	private void stalk(Sim sim, Vec3 a, float planeY, int w, int h, float[] col, float alpha) {
		if (Math.abs(a.y - planeY) < 20f) return;
		float[] t = project(sim, a.x, a.y, a.z, w, h);
		float[] g = project(sim, a.x, planeY, a.z, w, h);
		if (t == null || g == null) return;
		color(col, alpha);
		line(t[0], t[1], g[0], g[1]);
		ellipse(g[0], g[1], 6f, 2.5f, 12);
	}

	private static void diamond(float x, float y, float k) {
		line(x, y + k, x + k, y);
		line(x + k, y, x, y - k);
		line(x, y - k, x - k, y);
		line(x - k, y, x, y + k);
	}

	private static void dashed(float x0, float y0, float x1, float y1, float dash) {
		float len = (float) Math.hypot(x1 - x0, y1 - y0);
		if (len < 1f) return;
		int n = (int) Math.min(200, len / dash);
		for (int i = 0; i < n; i += 2) {
			float a = i / (float) n, b = Math.min(1f, (i + 1) / (float) n);
			line(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a, x0 + (x1 - x0) * b, y0 + (y1 - y0) * b);
		}
	}

	private static void square(float[] col, float a, float x, float y, float k) {
		color(col, a);
		line(x - k, y - k, x + k, y - k);
		line(x + k, y - k, x + k, y + k);
		line(x + k, y + k, x - k, y + k);
		line(x - k, y + k, x - k, y - k);
	}

	// ---- weapons HUD ---------------------------------------------------------------------

	/**
	 * War Thunder-style aiming: a lead circle (10% bigger than the reticle) where the selected group's
	 * shots meet the aim target along its predicted path (reticle inside = shots go there), plus one small "+" per
	 * selected weapon showing where its barrel points at that distance. Put the + in the circle.
	 * The + are solid yellow bars with a dark rim (drawn as quads, over the line pass) so they show
	 * against bright shields, explosions and dark space alike.
	 */
	private void drawLead(Sim sim, int w, int h, List<Text> texts, List<float[]> bars) {
		Ship3D p = sim.player, t = sim.aimTarget;
		if (p == null || t == null || t.destroyed || !sim.hasLead) return;
		float[] s = project(sim, sim.leadPoint.x, sim.leadPoint.y, sim.leadPoint.z, w, h);
		if (s == null) return;
		float r = sim.leadCircleRadiusPx();
		float[] col = sim.assistLocked ? GREEN : sim.leadInRange ? AMBER : GRAY;
		float[] c = project(sim, t.pos.x, t.pos.y, t.pos.z, w, h);
		if (c != null) {
			color(col, 0.3f);
			line(c[0], c[1], s[0], s[1]);
		}
		color(col, 0.95f);
		circle(s[0], s[1], r, 24);
		circle(s[0], s[1], 1.5f, 6);
		if (!sim.leadInRange) texts.add(new Text("OUT OF RANGE", s[0], s[1] + r + 18f, GRAY, 0.9f, true, 0.8f)); // above: the name label sits below

		// Gun pippers for the selected group.
		s3d.core.WeaponGroup3D g = sim.selectedGroup();
		if (g == null) return;
		Vec3 mw = new Vec3(), dir = new Vec3(), pt = new Vec3();
		List<float[]> fills = new ArrayList<>();
		for (WeaponMount m : g.mounts) {
			if (!m.spec.supported) continue;
			m.worldPos(p, mw);
			float dist = mw.distance(sim.leadPoint);
			p.rot.rotate(m.aimDir, dir);
			pt.set(mw).addScaled(dir, dist);
			float[] q = project(sim, pt.x, pt.y, pt.z, w, h);
			if (q == null) continue;
			boolean ready = m.readiness() >= 1f;
			plus(bars, fills, q[0], q[1], ready ? 1f : 0.55f);
		}
		bars.addAll(fills); // every rim under every fill: converging guns' + overlap
	}

	/** One gun pipper: four 2 px yellow arms around a small gap, each with a 1 px dark rim (rims to bars, fills to fills). */
	private static void plus(List<float[]> rims, List<float[]> fills, float x, float y, float a) {
		final float arm = 7f, gap = 2f, half = 1f, rim = 1f;
		float[][] arms = {
				{x - arm, y - half, x - gap, y + half}, {x + gap, y - half, x + arm, y + half},
				{x - half, y - arm, x + half, y - gap}, {x - half, y + gap, x + half, y + arm}};
		for (float[] b : arms) {
			rims.add(new float[] {b[0] - rim, b[1] - rim, b[2] + rim, b[3] + rim, 0.04f, 0.03f, 0f, 0.85f * a});
			fills.add(new float[] {b[0], b[1], b[2], b[3], CROSSHAIR[0], CROSSHAIR[1], CROSSHAIR[2], a});
		}
	}

	/**
	 * Top-down map of the battle area, enemy side at the top (like vanilla's command map): every
	 * ship as a heading arrow, objectives as small squares in their holder's colour, your view
	 * cone, the map edges (yours green: that's where retreats leave). A tick above or below an
	 * icon means the ship is well above or below you.
	 */
	private void drawMinimap(Sim sim, float x0, float y0, float size, float aspect, List<Text> texts) {
		Ship3D p = sim.viewer();
		float cxm = x0 + size * 0.5f, cym = y0 + size * 0.5f;
		boolean arena = sim.arenaHalfX > 0f && sim.arenaHalfZ > 0f;
		float hx = arena ? sim.arenaHalfX : Math.max(500f, sim.tuning.radarRange);
		float hz = arena ? sim.arenaHalfZ : hx;
		float ox = arena ? 0f : p.pos.x, oz = arena ? 0f : p.pos.z;
		float k = (size - 16f) / (2f * Math.max(hx, hz));
		color(CYAN, 0.35f);
		line(x0, y0, x0 + size, y0);
		line(x0 + size, y0, x0 + size, y0 + size);
		line(x0 + size, y0 + size, x0, y0 + size);
		line(x0, y0 + size, x0, y0);
		if (arena) {
			float ax0 = cxm - hx * k, ax1 = cxm + hx * k, ay0 = cym - hz * k, ay1 = cym + hz * k;
			color(CYAN, 0.2f);
			line(ax0, ay0, ax0, ay1);
			line(ax1, ay0, ax1, ay1);
			color(RED, 0.45f);
			line(ax0, ay1, ax1, ay1);
			color(GREEN, 0.6f);
			line(ax0, ay0, ax1, ay0);
		}
		float lim = size * 0.5f - 4f, tickScale = Math.max(hx, hz) * 0.08f;
		Vec3 f = new Vec3();
		for (s3d.core.Objective3D o : sim.objectives) {
			float mx = Math.max(-lim, Math.min(lim, (o.pos.x - ox) * k)), my = Math.max(-lim, Math.min(lim, -(o.pos.z - oz) * k));
			square(o.owner < 0 ? GRAY : o.owner == p.owner ? GREEN : RED, 0.8f, cxm + mx, cym + my, 3.5f);
		}
		for (Ship3D s : sim.ships) {
			if (s.destroyed) continue;
			float mx = (s.pos.x - ox) * k, my = -(s.pos.z - oz) * k;
			mx = Math.max(-lim, Math.min(lim, mx));
			my = Math.max(-lim, Math.min(lim, my));
			float px = cxm + mx, py = cym + my;
			if (s.fighter) {
				color(s.owner == p.owner ? GREEN : RED, 0.55f);
				line(px - 1f, py, px + 1f, py);
				continue;
			}
			s.rot.forward(f);
			float dx = f.x, dy = -f.z, len = (float) Math.hypot(dx, dy);
			if (len < 1e-3f) { dx = 0f; dy = 1f; } else { dx /= len; dy /= len; }
			float sz = "capital".equals(s.sizeClass) ? 7f : "cruiser".equals(s.sizeClass) ? 6f : "destroyer".equals(s.sizeClass) ? 5f : 4f;
			boolean attacker = s.owner != p.owner && sim.time - s.lastHitPlayerAt < ATTACKER_MEMORY;
			float[] col = s == p ? CYAN : s == sim.aimTarget ? AMBER : s.owner == p.owner ? GREEN : RED;
			color(col, s == p || s == sim.aimTarget || attacker ? 1f : 0.8f);
			float tx = px + dx * sz, ty = py + dy * sz;
			float lx = px - dx * sz * 0.7f - dy * sz * 0.6f, ly = py - dy * sz * 0.7f + dx * sz * 0.6f;
			float rx = px - dx * sz * 0.7f + dy * sz * 0.6f, ry = py - dy * sz * 0.7f - dx * sz * 0.6f;
			line(tx, ty, lx, ly);
			line(lx, ly, rx, ry);
			line(rx, ry, tx, ty);
			float dyAlt = s == p ? 0f : s.pos.y - p.pos.y;
			if (Math.abs(dyAlt) > tickScale * 0.5f) {
				float tick = Math.max(-9f, Math.min(9f, dyAlt / tickScale * 3f));
				color(col, 0.6f);
				line(px, py, px, py + tick);
			}
			if (s == sim.aimTarget) circle(px, py, sz + 3f, 12);
			if (s == p) {
				color(CYAN, 0.9f);
				circle(px, py, sz + 5f, 16);
			}
		}
		// Your view cone
		sim.camera.rot.forward(f);
		float vx = f.x, vy = -f.z, vl = (float) Math.hypot(vx, vy);
		if (vl > 0.2f) {
			vx /= vl;
			vy /= vl;
			float half = (float) Math.atan(Math.tan(sim.camera.fovYRad * 0.5) * aspect);
			float px = cxm + Math.max(-lim, Math.min(lim, (p.pos.x - ox) * k)), py = cym + Math.max(-lim, Math.min(lim, -(p.pos.z - oz) * k));
			color(CYAN, 0.3f);
			for (int sgn = -1; sgn <= 1; sgn += 2) {
				float c = (float) Math.cos(half * sgn), sn = (float) Math.sin(half * sgn);
				float ex = vx * c - vy * sn, ey = vx * sn + vy * c;
				line(px, py, px + ex * 45f, py + ey * 45f);
			}
		}
		texts.add(new Text(formatDist(2f * Math.max(hx, hz)), x0 + 4f, y0 + 4f + lineH() * 0.75f, CYAN, 0.5f, false, 0.75f));
		int friends = 0, enemies = 0;
		for (Ship3D s : sim.ships) {
			if (s.destroyed || s.fighter || s.parent != null) continue; // a station or ship with modules counts once
			if (s.owner == p.owner) friends++;
			else enemies++;
		}
		String count = friends + " v " + enemies;
		texts.add(new Text(count, x0 + size - 4f - textW(count) * 0.75f, y0 + 4f + lineH() * 0.75f, CYAN, 0.6f, false, 0.75f));
	}

	/**
	 * Target readout on the right: name and range, a slowly turning hologram of the hull (drawn by
	 * the renderer from the ship's own mesh, armor damage by direction), then hull and flux bars and
	 * the shield state.
	 */
	private void drawTargetPanel(Sim sim, Ship3D t, int w, int h, List<Text> texts, List<float[]> bars) {
		Ship3D p = sim.viewer();
		if (t == null || p == null) return;
		float k = 0.8f, lh = lineH() * k;
		float pw = 200f, x1 = w - 18f, x0 = x1 - pw;
		float top = h * 0.5f + 160f;
		Vec3 rel = new Vec3(t.pos).sub(p.pos);
		float dist = rel.length();
		Vec3 relVel = new Vec3(p.vel).sub(t.vel);
		float closing = dist > 1f ? relVel.dot(rel) / dist : 0f;
		float[] col = t.owner == p.owner ? GREEN : AMBER;
		texts.add(new Text((t == sim.target ? "LOCKED  " : "TARGET  ") + t.name, x0, top, col, 1f, false, k));
		texts.add(new Text(t.hullName, x0, top - lh, col, 0.75f, false, k));
		texts.add(new Text(String.format("%s   closing %+.0f%s", formatDist(dist), closing, t.destroyed ? "   DESTROYED" : ""), x0, top - lh * 2f, col, 0.8f, false, k));

		float boxTop = top - lh * 3.2f, boxH = 170f;
		color(CYAN, 0.18f);
		line(x0, boxTop, x1, boxTop);
		line(x0, boxTop - boxH, x1, boxTop - boxH);
		targetHolo.set(t, x0 + 4f, boxTop - boxH + 4f, pw - 8f, boxH - 8f);

		float y = boxTop - boxH - 8f;
		float hullFrac = t.hullMax > 0f ? t.hull / t.hullMax : 0f;
		texts.add(new Text(String.format("HULL %3.0f%%", hullFrac * 100f), x0, y, CYAN, 0.9f, false, k));
		bar(bars, x0 + 70f, y - lh * 0.7f, x1, 6f, hullFrac, new float[] {1f - hullFrac, 0.3f + 0.6f * hullFrac, 0.3f});
		y -= lh * 1.15f;
		if (t.flux != null && t.flux.maxFlux > 0f) {
			texts.add(new Text(String.format("FLUX %3.0f%%", t.flux.level() * 100f), x0, y, CYAN, 0.9f, false, k));
			fluxBar(bars, x0 + 70f, y - lh * 0.7f, x1, 6f, t.flux);
			y -= lh * 1.15f;
			String st = shieldState(t.flux);
			if (!st.isEmpty()) texts.add(new Text(st, x0, y, t.flux.disabled() ? RED : SHIELD, 0.9f, false, k));
		}
	}

	/** A hologram for the renderer to draw after the HUD: the ship and its box in screen pixels. */
	static final class Holo {
		Ship3D ship;
		float x, y, w, h;
		/** Nose to the top of the box, swaying (your ship) instead of turning (the target). */
		final boolean noseUp;

		Holo(boolean noseUp) {
			this.noseUp = noseUp;
		}

		void set(Ship3D ship, float x, float y, float w, float h) {
			this.ship = ship;
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}
	}

	/** Set by the panels each frame; Renderer3D.renderHud draws and clears them. */
	final Holo ownHolo = new Holo(true), targetHolo = new Holo(false);

	/** Flux bar: soft flux in light violet with the hard part in deep violet at the start. */
	private static void fluxBar(List<float[]> bars, float x0, float y, float x1, float hgt, s3d.core.ShieldFlux f) {
		float total = Math.max(0f, Math.min(1f, f.level()));
		float hard = f.maxFlux > 0f ? Math.max(0f, Math.min(total, f.hardFlux / f.maxFlux)) : 0f;
		bars.add(new float[] {x0, y, x1, y + hgt, 0.1f, 0.14f, 0.2f, 0.75f});
		float span = x1 - x0 - 2f;
		if (total > 0f) bars.add(new float[] {x0 + 1f, y + 1f, x0 + 1f + span * total, y + hgt - 1f, FLUX_SOFT[0], FLUX_SOFT[1], FLUX_SOFT[2], 0.9f});
		if (hard > 0f) bars.add(new float[] {x0 + 1f, y + 1f, x0 + 1f + span * hard, y + hgt - 1f, FLUX_HARD[0], FLUX_HARD[1], FLUX_HARD[2], 0.95f});
	}

	private static String shieldState(s3d.core.ShieldFlux f) {
		if (f.overloaded()) return String.format("OVERLOADED %.1f s", f.overloadLeft);
		if (f.venting) return "VENTING";
		if (!f.hasShield()) return "";
		return f.shieldOn ? "SHIELD UP" : "SHIELD DOWN";
	}

	// ---- your ship panel -------------------------------------------------------------------

	/**
	 * Your ship, bottom left, laid out like vanilla's ship panel in the target display's hologram
	 * style: your hull as a hologram (nose up, a little smaller than the target's) over its name,
	 * class and variant; a slanted divider; flux and hull bars, ship state and system above a rule;
	 * the weapon groups under it, stepping left along the divider (number, weapon icon, weapons,
	 * LINKED / ALTERNATING, readiness, damage type, AUTOFIRE box, ammo). Text in vanilla's HUD
	 * font. Draws its own backdrop and lines before the main line pass, so the rest of the HUD sits
	 * on top.
	 */
	private void drawShipPanel(Sim sim, List<Text> texts, List<float[]> bars) {
		Ship3D p = sim.player;
		float lh = panelLineH(), adv = panelAdvance();
		float m = 14f;

		// Left column: the hologram over name, class and variant.
		float holoW = 160f, holoH = 148f, holoY = m + lh * 3f + 8f;
		ownHolo.set(p, m, holoY, holoW, holoH);
		texts.add(pt(caps(p.name, 24), m + 2f, m + lh * 3f, HOLO_HI, 0.95f));
		texts.add(pt(caps(p.hullName, 24), m + 2f, m + lh * 2f, HOLO, 0.8f));
		if (p.variantName != null) texts.add(pt(caps(p.variantName, 24), m + 2f, m + lh, HOLO, 0.6f));

		// Divider from the bottom edge up to the rule; groups below the rule, ship status above it.
		int rows = Math.max(1, p.groups.size());
		float rowH = lh * 2f + 4f, headH = lh * 1.4f;
		float ruleY = m + rows * rowH + headH;
		float slant0 = m + holoW + 12f; // divider x at the bottom edge
		float ruleX = slant0 + PANEL_SLANT * ruleY, right = ruleX + Math.max(400f, adv * 56f); // room for vanilla's wider letters

		// Status lines, top to bottom: extras, FLUX, HULL, SYSTEM, PHASE.
		int wingsUp = 0;
		for (Ship3D s : sim.ships) if (s.fighter && s.carrier == p && !s.destroyed) wingsUp++;
		int fragNeed = 0;
		if (p.fragments != null) for (WeaponMount wm : p.weapons) fragNeed = Math.max(fragNeed, wm.spec.fragmentsNeeded);
		float slowed = sim.combat.exotic.slowedRepairsLeft(p);
		boolean wings = wingsUp > 0 || p.wingsRegroup;
		int lines = 1 + (p.flux != null && p.flux.maxFlux > 0f ? 1 : 0) + (p.system != null ? 1 : 0) + (p.cloak != null ? 1 : 0)
				+ (wings ? 1 : 0) + (p.fragments != null ? 1 : 0) + (slowed > 0f ? 1 : 0);
		float step = lh * 1.15f, top = ruleY + 5f + lines * step;

		// Weapon icons upload a texture on first use, which GL ignores between glBegin and glEnd: load them now.
		for (s3d.core.WeaponGroup3D g : p.groups) for (WeaponMount wm : g.mounts) HudIcons.get(wm.spec.iconSprites);

		// Backdrop: darker at the bottom edge, fading upward (the hologram is additive and needs it).
		GL11.glBegin(GL11.GL_QUADS);
		shade(m - 6f, 0f, m + holoW + 4f, 0f, m + holoW + 4f, holoY + holoH + 4f, m - 6f, holoY + holoH + 4f, top);
		shade(slant0, 0f, right, 0f, right, ruleY, ruleX, ruleY, top);
		shade(ruleX, ruleY, right, ruleY, right, top, ruleX, top, top);
		if (p.selectedGroup >= 0 && p.selectedGroup < p.groups.size()) { // selected group: a band along the divider
			float yt = ruleY - headH - p.selectedGroup * rowH + 1f, yb = yt - rowH + 2f;
			GL11.glColor4f(HOLO[0], HOLO[1], HOLO[2], 0.1f);
			GL11.glVertex2f(slant0 + PANEL_SLANT * yb + 3f, yb);
			GL11.glVertex2f(right - 6f, yb);
			GL11.glVertex2f(right - 6f, yt);
			GL11.glVertex2f(slant0 + PANEL_SLANT * yt + 3f, yt);
		}
		GL11.glEnd();

		GL11.glBegin(GL11.GL_LINES);
		color(HOLO, 0.55f);
		line(slant0, 0f, ruleX, ruleY);
		line(ruleX, ruleY, right, ruleY);
		line(right, ruleY - 4f, right, ruleY + 4f);
		color(HOLO, 0.45f);
		float c = 10f, hx1 = m + holoW, hy1 = holoY + holoH;
		corner(m, holoY, c, c);
		corner(hx1, holoY, -c, c);
		corner(m, hy1, c, -c);
		corner(hx1, hy1, -c, -c);

		float sx = ruleX + 4f;
		float barX0 = sx + adv * 6f, barX1 = barX0 + 130f, valX = barX1 + 8f, stateX = valX + adv * 7f + 4f;
		float y = top;
		if (wings) {
			texts.add(pt(String.format("WINGS  %s  (%d OUT)", p.wingsRegroup ? "REGROUP" : "ENGAGE", wingsUp),
					sx, y, p.wingsRegroup ? AMBER : HOLO, 0.85f));
			y -= step;
		}
		if (p.fragments != null) { // Threat fragment swarm: what fragment weapons draw on
			int have = p.fragments.active();
			texts.add(pt(String.format("FRAGMENTS  %d / %d", have, p.fragments.size), sx, y, have < fragNeed ? RED : HOLO, 0.85f));
			y -= step;
		}
		if (slowed > 0f) {
			texts.add(pt(String.format("REALITY DISRUPTED  slower repairs %.0f s", Math.max(1f, slowed)).toUpperCase(java.util.Locale.ROOT),
					sx, y, AMBER, 0.85f));
			y -= step;
		}
		float barY = lh * 0.72f;
		if (p.flux != null && p.flux.maxFlux > 0f) {
			texts.add(pt("FLUX", sx, y, HOLO, 0.9f));
			barTicks(barX0, barX1, y - barY, 7f);
			fluxBar(bars, barX0, y - barY, barX1, 7f, p.flux);
			texts.add(pt(String.format("%.0f", p.flux.flux), valX, y, HOLO, 0.9f));
			if (p.flux.zeroFluxBoostActive() && !p.destroyed) texts.add(pt("0-FLUX BOOST", stateX, y, HOLO_HI, 0.85f));
			y -= step;
		}
		float hullFrac = p.hullMax > 0f ? Math.max(0f, Math.min(1f, p.hull / p.hullMax)) : 0f;
		texts.add(pt("HULL", sx, y, HOLO, 0.9f));
		barTicks(barX0, barX1, y - barY, 7f);
		bar(bars, barX0, y - barY, barX1, 7f, hullFrac, hullFrac > 0.5f ? HOLO : hullFrac > 0.25f ? AMBER : RED);
		texts.add(pt(String.format("%.0f", Math.max(0f, p.hull)), valX, y, HOLO, 0.9f));
		String state = null;
		float[] stateCol = HOLO;
		if (p.destroyed) {
			state = "DESTROYED";
			stateCol = RED;
		} else if (p.flux != null && p.flux.overloaded()) {
			state = String.format("OVERLOADED %.1fs", p.flux.overloadLeft);
			stateCol = RED;
		} else if (p.flux != null && p.flux.venting) {
			state = "VENTING FLUX";
			stateCol = SHIELD;
		} else if (p.phased) {
			// Phase coil stress (hard flux) cuts top speed; vanilla shows it as a debuff.
			boolean stressed = p.sysTopSpeedMult < 0.995f;
			state = stressed ? String.format("PHASED  SPEED %.0f%%", p.sysTopSpeedMult * 100f) : "PHASED";
			stateCol = stressed ? RED : AMBER;
		} else if (p.flux != null && p.flux.hasShield()) {
			state = p.flux.shieldOn ? "SHIELDS UP" : "SHIELDS DOWN";
			stateCol = p.flux.shieldOn ? HOLO_HI : HOLO_DIM;
		}
		if (state != null) texts.add(pt(state, stateX, y, stateCol, 0.9f));
		y -= step;
		for (s3d.core.ShipSystem3D sys : new s3d.core.ShipSystem3D[] {p.system, p.cloak}) {
			if (sys == null) continue;
			if (!sys.supported()) {
				texts.add(pt(caps(sys.name, 26), sx, y, GRAY, 0.8f));
				texts.add(pt("NOT IN 3D YET", stateX, y, GRAY, 0.8f));
			} else {
				boolean ready = sys.ready(p);
				float[] col = sys.on() ? AMBER : ready ? HOLO_HI : HOLO;
				texts.add(pt(caps(sys.name, 26), sx, y, col, 0.95f));
				if (sys.maxAmmo > 0) texts.add(pt(String.valueOf((int) sys.ammo), valX, y, sys.ammo >= 1f ? HOLO : RED, 0.9f));
				color(HOLO, 0.6f);
				line(stateX, y - lh * 0.9f, stateX, y - lh * 0.1f);
				bar(bars, stateX + 4f, y - barY, stateX + 40f, 5f, systemFrac(sys), sys.on() ? AMBER : ready ? HOLO_HI : HOLO_DIM);
				texts.add(pt(sys.status(), stateX + 46f, y, col, 0.9f));
			}
			y -= step;
		}

		// Weapon groups.
		texts.add(pt("WEAPON GROUPS", slant0 + PANEL_SLANT * (ruleY - 3f) + 6f, ruleY - 3f, HOLO, 0.8f));
		if (p.groups.isEmpty()) {
			float rt = ruleY - headH;
			texts.add(pt("NO WEAPONS", slant0 + PANEL_SLANT * rt + 6f, rt, HOLO_DIM, 0.7f));
		}
		for (int i = 0; i < p.groups.size(); i++) {
			s3d.core.WeaponGroup3D g = p.groups.get(i);
			float rt = ruleY - headH - i * rowH; // top of the row's first line
			float rx = slant0 + PANEL_SLANT * rt + 6f; // clear of the divider along the first line
			float ix0 = rx + adv * 2f + 3f, ix1 = ix0 + 22f; // weapon icon box, both lines tall
			float nx = ix1 + 6f, cx = nx + adv * 24f, bx = cx + adv * 12.5f;
			boolean selected = i == p.selectedGroup;
			WeaponMount first = null;
			int off = 0, working = 0, ammo = 0, maxAmmo = 0;
			float r = 1f, back = 0f;
			String dmg = null;
			for (WeaponMount wm : g.mounts) {
				if (!wm.spec.supported) continue;
				if (first == null) first = wm;
				working++;
				String d = wm.spec.damageType != null ? wm.spec.damageType : "OTHER";
				dmg = dmg == null || dmg.equals(d) ? d : "MIXED";
				if (wm.disabledFor > 0f) {
					off++;
					back = Math.max(back, wm.disabledFor);
				} else {
					r = Math.min(r, wm.readiness());
				}
				if (wm.spec.maxAmmo > 0) {
					ammo += (int) wm.ammo;
					maxAmmo += wm.spec.maxAmmo;
				}
			}
			float a = selected ? 1f : 0.75f;
			float[] col = first == null ? GRAY : off == working ? RED : selected ? HOLO_HI : HOLO;
			texts.add(pt((i + 1) + ".", rx, rt, col, a));
			WeaponMount iconOf = first != null ? first : g.mounts.isEmpty() ? null : g.mounts.get(0);
			if (iconOf != null) {
				// Vanilla's weapon icon (its turret sprite) in the panel's colour; the slot shape if it has none.
				HudIcons.Icon wi = HudIcons.get(iconOf.spec.iconSprites);
				if (wi != null) {
					icon(wi, ix0, rt - lh * 2f - 1f, ix1, rt - 1f, col, 0.95f * a);
				} else {
					color(col, 0.9f * a);
					mountIcon(iconOf.spec.type, iconOf.spec.size, (ix0 + ix1) * 0.5f, rt - lh);
				}
			}
			texts.add(pt(caps(groupLabel(g), 22), nx, rt, col, a));
			if (first == null) {
				texts.add(pt("NOT IN 3D YET", cx, rt, GRAY, 0.7f));
				continue;
			}
			texts.add(pt(g.alternating ? "ALTERNATING" : "LINKED", cx, rt, col, 0.85f * a));
			texts.add(pt("DMG: " + dmg.replace('_', ' '), nx + adv * 2f, rt - lh, col, 0.8f * a));
			if (off == working) {
				texts.add(pt(String.format("OFF %.0fs", back), bx, rt, RED, 0.9f));
			} else {
				bar(bars, bx, rt - barY + 1f, bx + 40f, 5f, r, r >= 1f ? (selected ? HOLO_HI : HOLO) : AMBER);
				if (off > 0) texts.add(pt(off + " OFF", bx + 46f, rt, RED, 0.9f));
			}
			boolean auto = g.autofire; // the group's setting; while selected it's still fired by hand
			texts.add(pt("AUTOFIRE", cx, rt - lh, auto ? col : HOLO_DIM, (auto ? 0.85f : 0.6f) * a));
			float boxX = cx + adv * 9f + 2f, boxY = rt - lh * 1.5f - 3.5f;
			if (auto) {
				bars.add(new float[] {boxX, boxY, boxX + 7f, boxY + 7f, col[0], col[1], col[2], 0.9f});
			} else {
				color(HOLO_DIM, 0.6f);
				line(boxX, boxY, boxX + 7f, boxY);
				line(boxX + 7f, boxY, boxX + 7f, boxY + 7f);
				line(boxX + 7f, boxY + 7f, boxX, boxY + 7f);
				line(boxX, boxY + 7f, boxX, boxY);
			}
			if (maxAmmo > 0) texts.add(pt(ammo + "/" + maxAmmo, bx, rt - lh, ammo > 0 ? col : RED, 0.85f * a));
		}
		GL11.glEnd();
	}

	/** Backdrop quad (counter-clockwise from bottom left), alpha fading from the bottom edge to topY. */
	private static void shade(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float topY) {
		float[][] v = {{x0, y0}, {x1, y1}, {x2, y2}, {x3, y3}};
		for (float[] q : v) {
			float f = Math.max(0f, Math.min(1f, q[1] / Math.max(1f, topY)));
			GL11.glColor4f(0.01f, 0.03f, 0.06f, 0.5f - 0.32f * f);
			GL11.glVertex2f(q[0], q[1]);
		}
	}

	/** Vanilla's bar ends: a bracket at the start and a tick at the end. */
	private static void barTicks(float x0, float x1, float y, float hgt) {
		line(x0 - 2f, y - 1f, x0 - 2f, y + hgt + 1f);
		line(x0 - 2f, y - 1f, x0 + 1f, y - 1f);
		line(x0 - 2f, y + hgt + 1f, x0 + 1f, y + hgt + 1f);
		line(x1 + 2f, y - 2f, x1 + 2f, y + hgt + 2f);
	}

	/** Vanilla's slot shapes, sized by mount: ballistic square, energy circle, missile diamond, else a triangle. */
	private static void mountIcon(String type, String size, float x, float y) {
		float r = "LARGE".equals(size) ? 5.5f : "MEDIUM".equals(size) ? 4.5f : 3.5f;
		switch (type != null ? type : "") {
			case "BALLISTIC":
				line(x - r, y - r, x + r, y - r);
				line(x + r, y - r, x + r, y + r);
				line(x + r, y + r, x - r, y + r);
				line(x - r, y + r, x - r, y - r);
				break;
			case "ENERGY":
				circle(x, y, r, 12);
				break;
			case "MISSILE":
				line(x, y + r * 1.25f, x + r * 1.25f, y);
				line(x + r * 1.25f, y, x, y - r * 1.25f);
				line(x, y - r * 1.25f, x - r * 1.25f, y);
				line(x - r * 1.25f, y, x, y + r * 1.25f);
				break;
			default:
				line(x, y + r, x + r, y - r);
				line(x + r, y - r, x - r, y - r);
				line(x - r, y - r, x, y + r);
		}
	}

	/** Bar for the system: time left while it runs, progress through cooldown or the next charge, full when ready. */
	private static float systemFrac(s3d.core.ShipSystem3D sys) {
		switch (sys.state) {
			case IN:
			case OUT:
				return sys.effect;
			case ACTIVE:
				return sys.toggle || sys.active <= 0f ? 1f : 1f - sys.stateTime / sys.active;
			case COOLDOWN:
				return sys.cooldown > 0f ? sys.stateTime / sys.cooldown : 1f;
			default:
				return sys.maxAmmo > 0 && sys.ammo < 1f ? sys.ammo : 1f;
		}
	}

	/** "2X HEAVY MAULER" for the group's first weapon, "+1" for each other kind in it. */
	private static String groupLabel(s3d.core.WeaponGroup3D g) {
		String name = null;
		int count = 0;
		java.util.Set<String> others = new java.util.HashSet<>();
		for (WeaponMount wm : g.mounts) {
			if (name == null) name = wm.spec.name;
			if (wm.spec.name.equals(name)) count++;
			else others.add(wm.spec.name);
		}
		if (name == null) return "";
		return count + "x " + name + (others.isEmpty() ? "" : " +" + others.size());
	}

	private static String caps(String s, int max) {
		if (s == null) return "";
		s = s.toUpperCase(java.util.Locale.ROOT);
		return s.length() > max ? s.substring(0, max) : s;
	}

	/** Mid-screen warnings about your own ship: overloaded, venting, destroyed. */
	private void drawWarnings(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		if (p.flux != null && !p.destroyed) {
			if (p.flux.overloaded()) {
				texts.add(new Text(String.format("OVERLOADED  %.1f s", p.flux.overloadLeft), w * 0.5f, h * 0.5f - 70f, RED, 1f, true));
			} else if (p.flux.venting) {
				texts.add(new Text("VENTING", w * 0.5f, h * 0.5f - 70f, SHIELD, 0.9f, true));
			}
		}
		float thrust = p.engineThrust();
		if (!p.destroyed && thrust < 1f) {
			int n = p.engineDown != null ? p.engineDown.length : 0, down = Math.round((1f - thrust) * n);
			texts.add(new Text(thrust <= 0f ? "FLAMED OUT  -  engines down" : String.format("ENGINES  %d of %d down", down, n),
					w * 0.5f, h * 0.5f - 90f, thrust <= 0f ? RED : AMBER, 0.95f, true));
		}
	}

	/** Enemy missiles nearby: a small red marker on each one. */
	private void drawIncoming(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		for (s3d.core.Missile3D ms : sim.combat.missiles) {
			if (ms.owner == p.owner || ms.flamedOut()) continue;
			if (ms.pos.distance(p.pos) > 3000f) continue;
			float[] s = project(sim, ms.pos.x, ms.pos.y, ms.pos.z, w, h);
			if (s == null) continue;
			color(RED, 0.9f);
			float k = 5f;
			line(s[0], s[1] + k, s[0] + k, s[1]);
			line(s[0] + k, s[1], s[0], s[1] - k);
			line(s[0], s[1] - k, s[0] - k, s[1]);
			line(s[0] - k, s[1], s[0], s[1] + k);
		}
		if (sim.incomingFire) texts.add(new Text("INCOMING FIRE TEST ON (B)", w * 0.5f, 48f, RED, 0.85f, true));
	}

	/** Frame + fill; queued as quads drawn after the line pass. */
	private static void bar(List<float[]> bars, float x0, float y, float x1, float hgt, float frac, float[] col) {
		frac = Math.max(0f, Math.min(1f, frac));
		bars.add(new float[] {x0, y, x1, y + hgt, 0.1f, 0.14f, 0.2f, 0.75f});
		if (frac > 0f) bars.add(new float[] {x0 + 1f, y + 1f, x0 + 1f + (x1 - x0 - 2f) * frac, y + hgt - 1f, col[0], col[1], col[2], 0.9f});
	}

	private void drawDamageNumbers(Sim sim, int w, int h, List<Text> texts) {
		for (Effect e : sim.combat.effects) {
			if (e.kind != Effect.Kind.DAMAGE_TEXT || e.text == null) continue;
			float[] s = project(sim, e.pos.x, e.pos.y, e.pos.z, w, h);
			if (s == null) continue;
			float age = e.age01();
			// Start well above the hit and fan out sideways so numbers never cover the lead circle.
			float side = ((System.identityHashCode(e) % 7) - 3) * 9f;
			texts.add(new Text(e.text, s[0] + side, s[1] + 34f + age * 22f, new float[] {e.r, e.g, e.b}, 1f - age * age, true));
		}
	}

	private static void ellipse(float cx, float cy, float rx, float ry, int seg) {
		for (int i = 0; i < seg; i++) {
			double a0 = i * 2 * Math.PI / seg, a1 = (i + 1) * 2 * Math.PI / seg;
			line(cx + (float) Math.cos(a0) * rx, cy + (float) Math.sin(a0) * ry, cx + (float) Math.cos(a1) * rx, cy + (float) Math.sin(a1) * ry);
		}
	}

	private static String formatDist(float d) {
		return d >= 1000f ? String.format("%.1fk", d / 1000f) : String.format("%.0f", d);
	}

	// ---- primitives ------------------------------------------------------------------------

	private float[] project(Sim sim, float x, float y, float z, int w, int h) {
		float[] r = projectRaw(sim, x, y, z, w, h);
		return r[2] > 0f ? r : null;
	}

	/** {screenX, screenY, w}; w <= 0 means behind the camera (x/y are then mirrored). */
	private float[] projectRaw(Sim sim, float x, float y, float z, int w, int h) {
		sim.camera.viewProj.transform(x, y, z, clip);
		float cw = clip[3];
		float inv = 1f / (Math.abs(cw) < 1e-6f ? 1e-6f : cw);
		return new float[] {(clip[0] * inv * 0.5f + 0.5f) * w, (clip[1] * inv * 0.5f + 0.5f) * h, cw};
	}

	private static boolean onScreen(float[] s, int w, int h, float margin) {
		return s[0] >= margin && s[0] <= w - margin && s[1] >= margin && s[1] <= h - margin;
	}

	private static void color(float[] c, float a) {
		GL11.glColor4f(c[0], c[1], c[2], a);
	}

	private static void line(float x0, float y0, float x1, float y1) {
		GL11.glVertex2f(x0, y0);
		GL11.glVertex2f(x1, y1);
	}

	private static void circle(float cx, float cy, float r, int seg) {
		for (int i = 0; i < seg; i++) {
			double a0 = i * 2 * Math.PI / seg, a1 = (i + 1) * 2 * Math.PI / seg;
			line(cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r, cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r);
		}
	}

	private static void corner(float x, float y, float dx, float dy) {
		line(x, y, x + dx, y);
		line(x, y, x, y + dy);
	}

	private float lineH() {
		return font != null ? font.lineHeight : 16f;
	}

	private float textW(String s) {
		return font != null ? font.width(s) : s.length() * 8f;
	}

	/** Line height and a capital's advance of your ship panel's text (vanilla's font, else the HUD font at 0.8). */
	private float panelLineH() {
		return vanilla != null ? vanilla.lineHeight * vanillaScale : lineH() * 0.8f;
	}

	private float panelAdvance() {
		return vanilla != null ? vanilla.width("XX", vanillaScale) - vanilla.width("X", vanillaScale) : textW("x") * 0.8f; // one letter's step, spacing included
	}

	/** Text in your ship panel or the status list: vanilla's HUD font if it loaded. */
	private Text pt(String s, float x, float y, float[] c, float a) {
		return vanilla != null ? new Text(s, x, y, c, a, false, vanillaScale, vanilla) : new Text(s, x, y, c, a, false, 0.8f);
	}

	/** Queues an icon fitted (aspect kept) and centred in the box x0..x1, y0..y1, in colour c. */
	private void icon(HudIcons.Icon ic, float x0, float y0, float x1, float y1, float[] c, float a) {
		float k = Math.min((x1 - x0) / ic.w, (y1 - y0) / ic.h);
		float w = ic.w * k, h = ic.h * k, cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
		icons.add(new IconQuad(ic.tex, cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, c, a));
	}

	private void drawIcons() {
		if (icons.isEmpty()) return;
		fx.set("u_mode", 10); // tinted sprite with its own alpha
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		for (IconQuad q : icons) {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, q.tex);
			GL11.glBegin(GL11.GL_QUADS);
			GL11.glColor4f(q.c[0], q.c[1], q.c[2], q.a);
			GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(q.x0, q.y0);
			GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(q.x1, q.y0);
			GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(q.x1, q.y1);
			GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(q.x0, q.y1);
			GL11.glEnd();
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		fx.set("u_mode", 0);
	}

	/**
	 * Vanilla's status list (ECM rating, Coordinated Maneuvers, peak time, CR, ion storms, skills...)
	 * as vanilla stacks it: above your ship's hologram, bottom entry first, each its icon beside a
	 * title and a data line, fading out as vanilla fades it; in the panel's cyan, debuff lines red.
	 */
	private void drawStatusList(List<Status> statuses, List<Text> texts) {
		if (statuses.isEmpty()) return;
		float lh = panelLineH(), m = 14f;
		float iconBox = lh * 2f, entryH = lh * 2f + 6f;
		float y = ownHolo.y + ownHolo.h + 12f; // just above your hologram's frame (drawShipPanel set it this frame)
		int shown = Math.min(statuses.size(), 9); // the throttle scale sits above that
		for (int i = 0; i < shown; i++) {
			Status s = statuses.get(i);
			float a = Math.max(0f, Math.min(1f, s.alpha));
			if (a <= 0.02f) continue;
			HudIcons.Icon ic = s.icon != null ? HudIcons.get(s.icon) : null;
			if (ic != null) icon(ic, m, y, m + iconBox, y + iconBox, HOLO, 0.9f * a);
			float tx = m + iconBox + 6f, top = y + lh * 2f;
			if (s.title != null) texts.add(pt(s.title.toUpperCase(java.util.Locale.ROOT), tx, top, HOLO_HI, 0.95f * a));
			if (s.data != null) texts.add(pt(s.data.toUpperCase(java.util.Locale.ROOT), tx, top - lh, s.debuff ? RED : HOLO, 0.85f * a));
			y += entryH;
		}
	}

	private static final class Text {
		final String s;
		final float x, y, a, scale;
		final float[] c;
		final boolean centred;
		/** Vanilla's bitmap font, or null for the HUD's own font. */
		final BitmapFont bmp;

		Text(String s, float x, float y, float[] c, float a, boolean centred) {
			this(s, x, y, c, a, centred, 1f);
		}

		Text(String s, float x, float y, float[] c, float a, boolean centred, float scale) {
			this(s, x, y, c, a, centred, scale, null);
		}

		Text(String s, float x, float y, float[] c, float a, boolean centred, float scale, BitmapFont bmp) {
			this.s = s;
			this.x = x;
			this.y = y;
			this.c = c;
			this.a = a;
			this.centred = centred;
			this.scale = scale;
			this.bmp = bmp;
		}
	}

	private void drawTexts(List<Text> texts) {
		if (texts.isEmpty()) return;
		fx.set("u_mode", 3);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		if (font != null) {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, font.texture);
			GL11.glBegin(GL11.GL_QUADS);
			for (Text t : texts) {
				if (t.bmp != null) continue;
				float x = t.centred ? t.x - font.width(t.s) * t.scale * 0.5f : t.x;
				// Drop shadow keeps text readable over bright nebula and hulls.
				GL11.glColor4f(0f, 0f, 0f, t.a * 0.7f);
				font.emit(t.s, Math.round(x) + 1f, Math.round(t.y) - 1f, t.scale);
				GL11.glColor4f(t.c[0], t.c[1], t.c[2], t.a);
				font.emit(t.s, Math.round(x), Math.round(t.y), t.scale);
			}
			GL11.glEnd();
		}
		for (BitmapFont bf : new BitmapFont[] {vanilla, buttonFont}) {
			if (bf == null) continue;
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, bf.texture);
			GL11.glBegin(GL11.GL_QUADS);
			for (Text t : texts) {
				if (t.bmp != bf) continue;
				float x = t.centred ? t.x - bf.width(t.s, t.scale) * 0.5f : t.x;
				GL11.glColor4f(0f, 0f, 0f, t.a * 0.7f);
				bf.emit(t.s, x + t.scale, t.y - t.scale, t.scale);
				GL11.glColor4f(t.c[0], t.c[1], t.c[2], t.a);
				bf.emit(t.s, x, t.y, t.scale);
			}
			GL11.glEnd();
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		fx.set("u_mode", 0);
	}
}
