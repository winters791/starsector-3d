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
 * target brackets, off-screen arrows, battlefield minimap, attitude ball, hull/armor/flux panels,
 * status text and help.
 *
 * To keep fleet fights readable, full brackets only go on ships that matter right now (your
 * target, enemies within your gun range, ships that hit you in the last few seconds); others get
 * a small dot, wrecks nothing. Off-screen arrows only point at your target and your attackers;
 * the minimap shows everyone else.
 */
public final class HudRenderer {

	/** Extra text supplied by the host each frame. */
	public static final class Info {
		/** Short warnings shown above the status line (retreat orders, command transfer). */
		public final List<String> alerts = new ArrayList<>();
		/** Performance readout, under the minimap. */
		public final List<String> perf = new ArrayList<>();
		/** Key that toggles your fighter wings, for the weapon panel. */
		public String wingsKey = "Z";
		/** Key names for on-screen hints (action -> display name), filled by the host. */
		public final java.util.Map<String, String> keyNames = new java.util.HashMap<>();

		String keyHint(String action) {
			return keyNames.getOrDefault(action, "?");
		}
		public String toast;
		public float toastAlpha;
		public boolean showHelp;
		public String[] helpLines = new String[0];
	}

	private static final float[] CYAN = {0.55f, 0.9f, 1f};
	private static final float[] GREEN = {0.45f, 1f, 0.55f};
	private static final float[] RED = {1f, 0.4f, 0.35f};
	private static final float[] AMBER = {1f, 0.8f, 0.35f};
	private static final float[] GRAY = {0.55f, 0.58f, 0.62f};
	private static final float[] SHIELD = {0.55f, 0.8f, 1f};
	private static final float[] FLUX_SOFT = {0.75f, 0.6f, 1f};
	private static final float[] FLUX_HARD = {0.5f, 0.3f, 0.95f};
	/** A ship that damaged you within this many seconds counts as an attacker (bracket + arrow). */
	private static final float ATTACKER_MEMORY = 5f;

	private final Shader fx;
	private final GlyphFont font;
	private final Mat4 ortho = new Mat4();
	private final float[] clip = new float[4];
	/** Screen positions of this frame's off-screen ship arrows (fleet markers stay clear of them). */
	private final List<float[]> edgeArrows = new ArrayList<>();

	HudRenderer(Shader fx, GlyphFont font) {
		this.fx = fx;
		this.font = font;
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
		GL11.glBegin(GL11.GL_LINES);
		if (p != null) {
			drawTargets(sim, w, h, texts);
			drawFleetMarkers(sim, w, h, texts);
			if (!sim.spectating) {
				if (!sim.camera.tactical) drawFlightMarkers(sim, in, w, h, texts);
				drawLead(sim, w, h, texts);
			}
			drawThrottle(sim, w, h, texts);
			drawAttitude(p, w - 95f, 95f, 60f, texts);
			if (mapSize > 0f) drawMinimap(sim, 14f, h - 14f - mapSize, mapSize, w / (float) h, texts);
			if (!sim.spectating) drawTargetPanel(sim, sim.aimTarget, w, h, texts, bars); // spectating: no target display
			if (!sim.spectating) drawWeaponPanel(sim, info, texts, bars);
			else drawSpectateBanner(sim, info, w, texts);
			drawWarnings(sim, w, h, texts);
			drawIncoming(sim, w, h, texts);
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
		if (sim.tuning.damageNumbers) drawDamageNumbers(sim, w, h, texts);

		// Status line
		StringBuilder status = new StringBuilder();
		if (sim.paused) status.append("PAUSED   ");
		if (sim.worldTimeScale < 0.99f) status.append(String.format("TIME x%.1f   ", 1f / sim.worldTimeScale));
		if (sim.controls.autoLevel) status.append("AUTO-LEVEL   ");
		if (sim.camera.tactical) status.append("TACTICAL   ");
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
		if (info.showHelp) drawHelp(info.helpLines, w, h, texts);

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
	private void drawAttitude(Ship3D p, float cx, float cy, float r, List<Text> texts) {
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
		texts.add(new Text(String.format("P%+4.0f R%+4.0f", Math.toDegrees(pitch), Math.toDegrees(roll)), cx, cy - r - 8f, CYAN, 0.8f, true));
	}

	// ---- targets ---------------------------------------------------------------------------

	private void drawTargets(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		float cx = w * 0.5f, cy = h * 0.5f;
		float focal = (h * 0.5f) / (float) Math.tan(sim.camera.fovYRad * 0.5);
		// Only the nearest few get names; the rest just get brackets/arrows, so crowds stay readable.
		edgeArrows.clear();
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
			String label = isTarget ? "LOCKED  " + s.name + "  " + formatDist(dist)
					: isAim ? s.name + "  " + formatDist(dist) : null; // names only on what you're aiming at
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
			if (!visible && !(isTarget || isAim || attacker)) continue; // the minimap shows the rest
			if (visible && !relevant) {
				color(col, 0.5f);
				line(c[0] - 2f, c[1], c[0] + 2f, c[1]);
				line(c[0], c[1] - 2f, c[0], c[1] + 2f);
				continue;
			}
			if (visible) {
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
			} else {
				// Off-screen arrow on an ellipse around the centre.
				float dx = c[0] - cx, dy = c[1] - cy;
				if (c[2] <= 0f) { dx = -dx; dy = -dy; }
				float len = (float) Math.hypot(dx, dy);
				if (len < 1e-3f) { dx = 0f; dy = -1f; len = 1f; }
				dx /= len;
				dy /= len;
				float ex = w * 0.44f, ey = h * 0.42f;
				float t = 1f / (float) Math.sqrt((dx * dx) / (ex * ex) + (dy * dy) / (ey * ey));
				float ax = clearOfTargetPanel(sim, cx + dx * t, cy + dy * t, w, h), ay = Math.min(cy + dy * t, clearOfMinimap(sim, cx + dx * t, cy + dy * t, h));
				edgeArrows.add(new float[] {ax, ay});
				float px = -dy, py = dx;
				color(col, 0.9f);
				line(ax + dx * 12f, ay + dy * 12f, ax + px * 8f, ay + py * 8f);
				line(ax + dx * 12f, ay + dy * 12f, ax - px * 8f, ay - py * 8f);
				line(ax + px * 8f, ay + py * 8f, ax - px * 8f, ay - py * 8f);
				texts.add(new Text(formatDist(dist), ax - dx * 18f, ay - dy * 18f + 6f, col, 0.75f, true));
			}
		}
	}

	/**
	 * Where each fleet is: a faint diamond on each fleet's centre, or an edge chevron with its
	 * distance when it's off screen ("FLEET" green = yours, "ENEMY" red).
	 */
	private void drawFleetMarkers(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		float cx = w * 0.5f, cy = h * 0.5f;
		for (int owner = 0; owner < 2; owner++) {
			Vec3 c = sim.fleetCentre(owner);
			if (c == null) continue;
			boolean mine = owner == p.owner;
			if (mine && c.distance(p.pos) < 900f) continue; // you're with your fleet; no marker needed
			float[] col = mine ? GREEN : RED;
			float[] sc = projectRaw(sim, c.x, c.y, c.z, w, h);
			float dist = c.distance(p.pos);
			if (sc[2] > 0f && onScreen(sc, w, h, 30f)) {
				color(col, 0.35f);
				float k = 7f;
				line(sc[0], sc[1] + k, sc[0] + k, sc[1]);
				line(sc[0] + k, sc[1], sc[0], sc[1] - k);
				line(sc[0], sc[1] - k, sc[0] - k, sc[1]);
				line(sc[0] - k, sc[1], sc[0], sc[1] + k);
				continue;
			}
			float dx = sc[0] - cx, dy = sc[1] - cy;
			if (sc[2] <= 0f) { dx = -dx; dy = -dy; }
			float len = (float) Math.hypot(dx, dy);
			if (len < 1e-3f) { dx = 0f; dy = -1f; len = 1f; }
			dx /= len;
			dy /= len;
			float ex = w * 0.47f, ey = h * 0.45f;
			float t = 1f / (float) Math.sqrt((dx * dx) / (ex * ex) + (dy * dy) / (ey * ey));
			float ax = clearOfTargetPanel(sim, cx + dx * t, cy + dy * t, w, h), ay = Math.min(cy + dy * t, clearOfMinimap(sim, cx + dx * t, cy + dy * t, h)), px = -dy, py = dx;
			boolean crowded = false;
			for (float[] a : edgeArrows) if (Math.hypot(a[0] - ax, a[1] - ay) < 60f) crowded = true;
			if (crowded) continue; // a ship arrow already points that way
			color(col, 0.75f);
			line(ax + dx * 10f, ay + dy * 10f, ax + px * 10f - dx * 2f, ay + py * 10f - dy * 2f);
			line(ax + dx * 10f, ay + dy * 10f, ax - px * 10f - dx * 2f, ay - py * 10f - dy * 2f);
			texts.add(new Text((mine ? "FLEET " : "ENEMY ") + formatDist(dist), ax - dx * 22f, ay - dy * 22f + 5f, col, 0.75f, true, 0.75f));
		}
	}

	/** x for an edge marker at (x, y) that keeps it off the target display (right side), if one is up. */
	private float clearOfTargetPanel(Sim sim, float x, float y, int w, int h) {
		Ship3D t = sim.spectating ? null : sim.aimTarget;
		if (t == null) return x;
		float px0 = w - 218f - 24f, top = h * 0.5f + 175f, bottom = h * 0.5f + 160f - lineH() * 0.8f * 3.2f - 170f - 70f;
		return x > px0 && y < top && y > bottom ? px0 : x;
	}

	/** Highest y an edge marker at (x, y) may use without landing on the minimap (top left). */
	private float clearOfMinimap(Sim sim, float x, float y, int h) {
		float size = sim.tuning.minimapSize;
		if (size <= 0f || x > 14f + size + 30f) return y;
		return Math.min(y, h - 14f - size - 30f);
	}

	// ---- weapons HUD ---------------------------------------------------------------------

	/**
	 * War Thunder-style aiming: a lead circle (10% bigger than the reticle) where the selected group's
	 * shots meet the aim target along its predicted path (reticle inside = shots go there), plus one small "+" per
	 * selected weapon showing where its barrel points at that distance. Put the + in the circle.
	 */
	private void drawLead(Sim sim, int w, int h, List<Text> texts) {
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
		for (WeaponMount m : g.mounts) {
			if (!m.spec.supported) continue;
			m.worldPos(p, mw);
			float dist = mw.distance(sim.leadPoint);
			p.rot.rotate(m.aimDir, dir);
			pt.set(mw).addScaled(dir, dist);
			float[] q = project(sim, pt.x, pt.y, pt.z, w, h);
			if (q == null) continue;
			boolean ready = m.readiness() >= 1f;
			color(GREEN, ready ? 1f : 0.45f);
			line(q[0] - 5f, q[1], q[0] - 1.5f, q[1]);
			line(q[0] + 1.5f, q[1], q[0] + 5f, q[1]);
			line(q[0], q[1] - 5f, q[0], q[1] - 1.5f);
			line(q[0], q[1] + 1.5f, q[0], q[1] + 5f);
		}
	}

	/**
	 * Top-down map of the battle area, enemy side at the top (like vanilla's command map): every
	 * ship as a heading arrow, your view cone, the map edges (yours green: that's where retreats
	 * leave). A tick above or below an icon means the ship is well above or below you.
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
		holoShip = t;
		holoX = x0 + 4f;
		holoY = boxTop - boxH + 4f;
		holoW = pw - 8f;
		holoH = boxH - 8f;

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

	/** Hologram request for the renderer (the target and its box in screen pixels), set by drawTargetPanel. */
	Ship3D holoShip;
	float holoX, holoY, holoW, holoH;

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

	private void drawWeaponPanel(Sim sim, Info info, List<Text> texts, List<float[]> bars) {
		Ship3D p = sim.player;
		float k = 0.75f, lh = lineH() * k;
		float x = 14f, y = 26f + p.groups.size() * lh;
		int wingsUp = 0;
		for (Ship3D s : sim.ships) if (s.fighter && s.carrier == p && !s.destroyed) wingsUp++;
		float extra = 2.2f;
		if (wingsUp > 0 || p.wingsRegroup) {
			texts.add(new Text(String.format("WINGS  %s  (%d out)  [%s]", p.wingsRegroup ? "REGROUP" : "ENGAGE", wingsUp, info.wingsKey),
					x, y + lh * extra, p.wingsRegroup ? AMBER : GREEN, 0.8f, false, k));
			extra += 1f;
		}
		for (s3d.core.ShipSystem3D sys : new s3d.core.ShipSystem3D[] {p.system, p.cloak}) {
			if (sys == null) continue;
			String key = sys == p.cloak ? "RMB" : info.keyHint("system");
			String line;
			float[] col;
			if (!sys.supported()) {
				line = String.format("SYSTEM  %s  (not in 3D yet)", sys.name);
				col = GRAY;
			} else {
				String charges = sys.maxAmmo > 0 ? String.format("  %d/%d", (int) sys.ammo, sys.maxAmmo) : "";
				line = String.format("%s  %s  %s%s  [%s]", sys == p.cloak ? "PHASE" : "SYSTEM", sys.name, sys.status(), charges, key);
				col = sys.on() ? AMBER : sys.ready(p) ? GREEN : CYAN;
			}
			texts.add(new Text(line, x, y + lh * extra, col, 0.85f, false, k));
			extra += 1f;
		}
		texts.add(new Text("WEAPONS", x, y + lh, CYAN, 0.7f, false, k));
		for (int i = 0; i < p.groups.size(); i++) {
			s3d.core.WeaponGroup3D g = p.groups.get(i);
			boolean selected = i == p.selectedGroup;
			String label = g.label();
			if (label.length() > 20) label = label.substring(0, 20);
			String mode = g.autofire && !selected ? "A" : selected ? ">" : " ";
			if (!g.hasWorkingWeapon()) {
				texts.add(new Text(String.format("%d%s %-20s", i + 1, mode, label), x, y, GRAY, 0.7f, false, k));
			} else {
				float r = 1f;
				int off = 0, working = 0;
				float back = 0f;
				for (WeaponMount m : g.mounts) {
					if (!m.spec.supported) continue;
					working++;
					if (m.disabledFor > 0f) {
						off++;
						back = Math.max(back, m.disabledFor);
					} else {
						r = Math.min(r, m.readiness());
					}
				}
				float[] col = off == working ? RED : selected ? (r >= 1f ? GREEN : AMBER) : CYAN;
				texts.add(new Text(String.format("%d%s %-20s", i + 1, mode, label), x, y, col, selected ? 1f : 0.75f, false, k));
				if (off > 0) {
					String why = off == working ? String.format("OFF %.0fs", back) : off + " OFF";
					texts.add(new Text(why, x + textW("x") * k * 24f + 92f, y, RED, 0.9f, false, k));
				}
				float bx = x + textW("x") * k * 24f;
				bar(bars, bx, y - lh * 0.75f, bx + 40f, 4f, r, r >= 1f ? GREEN : AMBER);
				int ammo = 0, maxAmmo = 0;
				for (WeaponMount m : g.mounts) {
					if (!m.spec.supported || m.spec.maxAmmo <= 0) continue;
					ammo += (int) m.ammo;
					maxAmmo += m.spec.maxAmmo;
				}
				if (maxAmmo > 0) texts.add(new Text(ammo + "/" + maxAmmo, bx + 46f, y, ammo > 0 ? CYAN : RED, 0.8f, false, k));
			}
			y -= lh;
		}
	}

	/** What you're watching and how to change it. */
	private void drawSpectateBanner(Sim sim, Info info, int w, List<Text> texts) {
		Ship3D v = sim.viewer();
		String what = sim.freeCam ? "FREE CAMERA" : "SPECTATING  " + (v != null ? v.name + "  (" + v.hullName + ")" + (v == sim.player ? "  - your ship, AI-flown" : "") : "");
		texts.add(new Text(what, w * 0.5f, 74f, CYAN, 0.95f, true));
		String keys = sim.freeCam
				? "mouse turns   " + info.keyHint("throttleUp") + "/" + info.keyHint("throttleDown") + " forward/back   " + info.keyHint("strafeLeft") + "/" + info.keyHint("strafeRight")
						+ " sideways   " + info.keyHint("strafeUp") + "/" + info.keyHint("strafeDown") + " up/down   wheel speed   " + info.keyHint("tactical") + " follow a ship   " + info.keyHint("spectate") + " back to your ship"
				: info.keyHint("prevHull") + " / " + info.keyHint("nextHull") + " other ships   " + info.keyHint("tactical") + " free camera   hold " + info.keyHint("freeLook") + " look around   " + info.keyHint("spectate") + " back to your ship";
		texts.add(new Text(keys, w * 0.5f, 56f, CYAN, 0.7f, true, 0.8f));
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
		if (p.destroyed) {
			boolean sandbox = sim.arenaHalfX <= 0f;
			texts.add(new Text(sandbox ? "SHIP DESTROYED  -  press N to repair" : "SHIP DESTROYED", w * 0.5f, h * 0.5f + 60f, RED, 1f, true));
		}
	}

	/** Enemy missiles nearby: a warning count plus a small red marker on each one. */
	private void drawIncoming(Sim sim, int w, int h, List<Text> texts) {
		Ship3D p = sim.viewer();
		int near = 0;
		for (s3d.core.Missile3D ms : sim.combat.missiles) {
			if (ms.owner == p.owner || ms.flamedOut()) continue;
			float d = ms.pos.distance(p.pos);
			if (d > 3000f) continue;
			if (ms.target == p && d < 2000f) near++;
			float[] s = project(sim, ms.pos.x, ms.pos.y, ms.pos.z, w, h);
			if (s == null) continue;
			color(RED, 0.9f);
			float k = 5f;
			line(s[0], s[1] + k, s[0] + k, s[1]);
			line(s[0] + k, s[1], s[0], s[1] - k);
			line(s[0], s[1] - k, s[0] - k, s[1]);
			line(s[0] - k, s[1], s[0], s[1] + k);
		}
		if (near > 0) texts.add(new Text("INCOMING MISSILES: " + near, w * 0.5f, h - 14f - lineH() * 4.5f, RED, 1f, true));
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

	// ---- help ------------------------------------------------------------------------------

	private void drawHelp(String[] lines, int w, int h, List<Text> texts) {
		float lh = lineH();
		float boxW = 0f;
		for (String s : lines) boxW = Math.max(boxW, textW(s));
		boxW += 40f;
		float boxH = lines.length * lh + 30f;
		float x0 = (w - boxW) * 0.5f, y1 = (h + boxH) * 0.5f;
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glColor4f(0.02f, 0.04f, 0.07f, 0.82f);
		GL11.glVertex2f(x0, y1 - boxH);
		GL11.glVertex2f(x0 + boxW, y1 - boxH);
		GL11.glVertex2f(x0 + boxW, y1);
		GL11.glVertex2f(x0, y1);
		GL11.glEnd();
		float y = y1 - 15f;
		for (String s : lines) {
			texts.add(new Text(s, x0 + 20f, y, CYAN, 1f, false));
			y -= lh;
		}
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

	private static final class Text {
		final String s;
		final float x, y, a, scale;
		final float[] c;
		final boolean centred;

		Text(String s, float x, float y, float[] c, float a, boolean centred) {
			this(s, x, y, c, a, centred, 1f);
		}

		Text(String s, float x, float y, float[] c, float a, boolean centred, float scale) {
			this.s = s;
			this.x = x;
			this.y = y;
			this.c = c;
			this.a = a;
			this.centred = centred;
			this.scale = scale;
		}
	}

	private void drawTexts(List<Text> texts) {
		if (font == null || texts.isEmpty()) return;
		fx.set("u_mode", 3);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, font.texture);
		GL11.glBegin(GL11.GL_QUADS);
		for (Text t : texts) {
			float x = t.centred ? t.x - font.width(t.s) * t.scale * 0.5f : t.x;
			// Drop shadow keeps text readable over bright nebula and hulls.
			GL11.glColor4f(0f, 0f, 0f, t.a * 0.7f);
			font.emit(t.s, Math.round(x) + 1f, Math.round(t.y) - 1f, t.scale);
			GL11.glColor4f(t.c[0], t.c[1], t.c[2], t.a);
			font.emit(t.s, Math.round(x), Math.round(t.y), t.scale);
		}
		GL11.glEnd();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		fx.set("u_mode", 0);
	}
}
