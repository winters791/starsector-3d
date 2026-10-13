package s3d.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import s3d.core.ChaseCamera;
import s3d.core.Ship3D;
import s3d.core.ShipSystem3D;
import s3d.core.Sim;
import s3d.core.SwarmLook;
import s3d.core.WeaponMount;
import s3d.math.Vec3;

/**
 * The particle swarms of the Threat and the Shrouded, drawn the way vanilla's RoilingSwarmEffect and
 * DwellerShroud draw them, with their sprite sheets, straight from each ship's state (nothing kept
 * between frames; every piece's spot, size, turn and timing come from a steady hash):
 * - Threat fragment swarm: one piece of the fragment sheet per fragment, scattered round the hull's
 *   outline out to the swarm's reach (a free swarm is a ball of them). While the ship's fragment
 *   weapons are loaded, pieces now and then flash: a white copy and a faint red glow.
 * - Shroud: dark crimson blobs from the blob sheet that live 1-3 s and come back elsewhere, each
 *   flashing its glow red once; over them, dark particles (subtracted, so they darken the glows and
 *   whatever is behind) that drift and grow. A Convulsive Lunge's windup streams half the blobs toward its point; a
 *   Darkened Gaze clears the front (blobs blown back, no dark particles there).
 */
final class CloudRenderer {
	private static final float[] SHROUD_DARK = {195f / 255f, 1f, 240f / 255f}; // vanilla: the shroud colour's inverse, toward white
	private static final SwarmLook SHROUD = SwarmLook.shroud();
	private final Vec3 tA = new Vec3(), tB = new Vec3(), dir = new Vec3(), at = new Vec3(), fwd = new Vec3();
	private Vec3 camR, camU, camPos;
	private float time;

	void draw(Sim sim, Shader fx) {
		boolean any = false;
		for (Ship3D s : sim.ships) if (look(s) != null) any = true;
		if (!any) return;
		ChaseCamera cam = sim.camera;
		camR = cam.rot.right(new Vec3());
		camU = cam.rot.up(new Vec3());
		camPos = cam.pos;
		time = sim.time;
		int fragTex = FxSprites.texture("misc", "threat_swarm_pieces");
		int blobTex = FxSprites.texture("dweller", "dweller_pieces");
		int glowTex = FxSprites.texture("misc", "threat_swarm_glow");
		fx.use().set("u_vp", cam.viewProj).set("u_tex", 0);

		// 1. The pieces, blended normally.
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		pass(sim, fx, blobTex, true, Pass.PIECES);
		pass(sim, fx, fragTex, false, Pass.PIECES);

		// 2. Flashes, added on: the piece's own bright copy, then the glow round it.
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
		pass(sim, fx, blobTex, true, Pass.FLASH_COPY);
		pass(sim, fx, fragTex, false, Pass.FLASH_COPY);
		pass(sim, fx, glowTex, true, Pass.GLOW);
		pass(sim, fx, glowTex, false, Pass.GLOW);

		// 3. The shroud's dark particles over it all (vanilla's particle layer is above the swarms),
		// subtracted: they sink the glows to crimson and darken whatever is behind.
		fx.set("u_mode", 9);
		GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
		GL11.glBegin(GL11.GL_QUADS);
		for (Ship3D s : sim.ships) {
			SwarmLook l = look(s);
			if (l != null && l.shroud) dark(s, l);
		}
		GL11.glEnd();
		GL14.glBlendEquation(GL14.GL_FUNC_ADD);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
	}

	private enum Pass { PIECES, FLASH_COPY, GLOW }

	/** One kind of quad for every swarm of one kind: textured from `tex`, or plain shapes if it's missing. */
	private void pass(Sim sim, Shader fx, int tex, boolean shroud, Pass pass) {
		if (tex != 0) {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
			fx.set("u_mode", 10);
		} else {
			fx.set("u_mode", pass == Pass.PIECES && shroud ? 9 : 2);
		}
		GL11.glBegin(GL11.GL_QUADS);
		for (Ship3D s : sim.ships) {
			SwarmLook l = look(s);
			if (l == null || l.shroud != shroud) continue;
			if (shroud) shroudPieces(s, l, pass, tex != 0);
			else fragmentPieces(s, l, pass, tex != 0);
		}
		GL11.glEnd();
	}

	/** The ship's swarm look: the host's (vanilla's live parameters, once its effect exists), else vanilla's defaults; null = none. */
	private SwarmLook look(Ship3D s) {
		if (s.destroyed) return null;
		if (s.swarmLook != null) return s.swarmLook;
		if (s.shrouded) return SHROUD;
		if (s.fragments == null) return null;
		return defaults.computeIfAbsent(s, k -> k.noHull ? SwarmLook.swarm(k.fragments.size, 20f) : SwarmLook.fragments(k.baseRadius));
	}

	private final java.util.Map<Ship3D, SwarmLook> defaults = new java.util.WeakHashMap<>();

	// ---- Threat --------------------------------------------------------------------------------

	private void fragmentPieces(Ship3D s, SwarmLook l, Pass pass, boolean textured) {
		int count = Math.min(s.fragments.active(), l.members);
		if (count <= 0) return;
		float k = Math.max(0.5f, s.scale);
		if (pass == Pass.PIECES) {
			for (int i = 0; i < count; i++) {
				fragmentSpot(s, l, i, k, at);
				float size = l.spriteSize * (0.75f + 0.25f * hash(s, i, 5)) * k;
				GL11.glColor4f(l.color[0], l.color[1], l.color[2], 1f);
				piece(at, size, angle(s, i, 60f), textured ? cell(s, i) : -1);
			}
			return;
		}
		// Vanilla: while its fragment weapons hold charges, a piece or two a second flashes (1 s up, 4 s down).
		float rate = flashRate(s, l, count);
		if (rate <= 0f) return;
		int first = (int) Math.floor((time - 5f) * rate), last = (int) Math.floor(time * rate);
		for (int n = Math.max(0, first); n <= last; n++) {
			float glow = flashGlow(time - n / rate);
			if (glow <= 0f) continue;
			int i = (int) (hash(s, n, 7) * count);
			fragmentSpot(s, l, i, k, at);
			float size = l.spriteSize * (0.75f + 0.25f * hash(s, i, 5)) * k;
			flash(l, pass, glow, 1f, size, angle(s, i, 60f), textured ? cell(s, i) : -1, k);
		}
	}

	/**
	 * Where fragment i sits: a ship's swarm round its hull's outline (vanilla: from three quarters of the
	 * hull's reach out to its reach + the swarm's), more toward the outside; a free swarm a ball. Each
	 * piece roils a little round its spot.
	 */
	private void fragmentSpot(Ship3D s, SwarmLook l, int i, float k, Vec3 out) {
		float h1 = hash(s, i, 1), h2 = hash(s, i, 2), h3 = hash(s, i, 3);
		double yaw = h1 * Math.PI * 2;
		float pitch = (float) Math.asin(2f * h2 - 1f) * (l.aroundHull ? 0.6f : 1f);
		dir.set((float) (Math.cos(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.sin(yaw) * Math.cos(pitch)));
		s.rot.rotate(dir, tA);
		float reach = l.maxOffset * k;
		if (l.aroundHull) {
			float edge = s.reachToward(tA), max = edge + reach;
			float f = Math.max(0.1f, edge / Math.max(1f, max) * 0.75f);
			out.set(s.pos).addScaled(tA, max * (f + (1f - f) * (float) Math.sqrt(h3)));
		} else {
			out.set(s.pos).addScaled(tA, reach * (float) Math.cbrt(h3));
		}
		float wob = reach * 0.15f, w = 0.6f + hash(s, i, 4);
		out.add(tB.set((float) Math.sin(time * w + i), (float) Math.sin(time * w * 1.3f + i * 2), (float) Math.cos(time * w * 0.8f + i)).scale(wob));
	}

	/** Vanilla's flash rate: (1 + the fragment weapons' charge) x 2 a second, more for big swarms; none when they're empty. */
	private static float flashRate(Ship3D s, SwarmLook l, int count) {
		if (l.flashPerSecond > 0f) return l.flashPerSecond;
		float charge = 0f, n = 0f;
		for (WeaponMount m : s.weapons) {
			if (m.spec.fragmentsNeeded <= 0 && m.spec.fragmentsSpent <= 0 || m.spec.maxAmmo <= 0) continue;
			charge += m.ammo / m.spec.maxAmmo;
			n++;
		}
		if (n <= 0f || charge <= 0f) return 0f;
		return (1f + charge / n) * 2f * Math.max(1f, Math.min(2f, count / 50f));
	}

	// ---- Shrouded ------------------------------------------------------------------------------

	/**
	 * The shroud's blobs: each lives 1-3 s (fading in over up to a second, out over one) and comes back
	 * elsewhere within the shroud's reach of the centre; each flashes once, early in its life.
	 */
	private void shroudPieces(Ship3D s, SwarmLook l, Pass pass, boolean textured) {
		float k = Math.max(0.5f, s.scale);
		ShipSystem3D sys = s.system;
		boolean lunge = sys != null && sys.ex != null && sys.kind == ShipSystem3D.Kind.CONVULSIVE_LUNGE && sys.state == ShipSystem3D.State.IN && sys.ex.lungeDest != null;
		boolean gaze = gazing(s);
		s.rot.forward(fwd);
		Vec3 pull = lunge ? new Vec3(sys.ex.lungeDest).sub(s.pos).normalize() : null;
		float pullMax = lunge ? sys.effect * l.maxOffset * k * (sys.ex.maw ? 2.2f : 0.9f) : 0f;
		for (int i = 0; i < l.members; i++) {
			float life = l.lifeMin + l.lifeRange * hash(s, i, 4), cycle = life + 1f;
			float t = time + hash(s, i, 5) * cycle;
			int gen = (int) Math.floor(t / cycle);
			float age = t - gen * cycle;
			int id = i * 131 + gen;
			float fadeIn = 0.5f + 0.5f * hash(s, id, 6);
			float b = age < life ? Math.min(1f, age / fadeIn) : Math.max(0f, 1f - (age - life));
			if (b <= 0f) continue;
			float h1 = hash(s, id, 1), h2 = hash(s, id, 2), h3 = hash(s, id, 3);
			double yaw = h1 * Math.PI * 2;
			float pitch = (float) Math.asin(2f * h2 - 1f) * 0.5f;
			dir.set((float) (Math.cos(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.sin(yaw) * Math.cos(pitch)));
			s.rot.rotate(dir, tA);
			at.set(s.pos).addScaled(tA, l.maxOffset * k * (float) Math.sqrt(h3));
			// Drifts a little over its life.
			at.addScaled(tB.set(hash(s, id, 8) - 0.5f, (hash(s, id, 9) - 0.5f) * 0.5f, hash(s, id, 10) - 0.5f), 40f * k * age);
			if (pull != null && (i & 1) == 0) at.addScaled(pull, pullMax * Math.min(1f, age)); // the lunge's windup
			if (gaze && tA.dot(fwd) > COS_HALF_GAZE) at.addScaled(fwd, -l.maxOffset * k * sys.effect * Math.min(1f, age)); // blown back off the front
			float size = l.spriteSize * (0.75f + 0.25f * hash(s, id, 11)) * k * b;
			float ang = (hash(s, id, 12) * 2f - 1f) * 120f * age + hash(s, id, 13) * 360f;
			int c = textured ? (int) (hash(s, id, 14) * 16f) : -1;
			b *= nearFade(size);
			if (b <= 0f) continue;
			if (pass == Pass.PIECES) {
				GL11.glColor4f(l.color[0], l.color[1], l.color[2], b);
				piece(at, size, ang, c);
			} else {
				float glow = flashGlow(age - hash(s, id, 15) * 0.6f);
				if (glow > 0f) flash(l, pass, glow, b, size, ang, c, k);
			}
		}
	}

	/**
	 * The shroud's dark particles (vanilla: a batch every ~0.1 s, each 2-4 s, growing to twice its size,
	 * fading in over half a second and out over the rest, drifting), within 1.5x the shroud's reach,
	 * outside its clear centre; none in the front arc while a Darkened Gaze is up.
	 */
	private void dark(Ship3D s, SwarmLook l) {
		float k = Math.max(0.5f, s.scale);
		boolean gaze = gazing(s);
		float clear = (gaze ? 150f : l.darkClearCentre) * k;
		int n = l.darkBatch * (gaze ? 15 : 30);
		float size = l.maxOffset * 2f * 0.33f * l.darkSize * k, area = l.maxOffset * 1.5f * l.darkArea * k;
		float alpha = (l.darkHighContrast ? 150f : 100f) / 255f;
		s.rot.forward(fwd);
		for (int i = 0; i < n; i++) {
			float dur = 2f + 2f * hash(s, i, 21);
			float t = time / dur + hash(s, i, 22);
			int gen = (int) Math.floor(t);
			float f = t - gen, age = f * dur;
			int id = i * 977 + gen;
			float h1 = hash(s, id, 23), h2 = hash(s, id, 24), h3 = hash(s, id, 25);
			double yaw = h1 * Math.PI * 2;
			float pitch = (float) Math.asin(2f * h2 - 1f) * 0.5f;
			dir.set((float) (Math.cos(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.sin(yaw) * Math.cos(pitch)));
			s.rot.rotate(dir, tA);
			if (gaze && tA.dot(fwd) > COS_HALF_GAZE) continue;
			float r = clear + (area - clear) * (float) Math.sqrt(h3);
			at.set(s.pos).addScaled(tA, r);
			at.addScaled(tB.set(hash(s, id, 26) - 0.5f, (hash(s, id, 27) - 0.5f) * 0.5f, hash(s, id, 28) - 0.5f), size * 0.5f * age);
			float ramp = Math.min(0.5f, dur * 0.25f);
			float a = age < ramp ? age / ramp : 1f - (age - ramp) / (dur - ramp);
			float across = size * (1f + f);
			a *= nearFade(across);
			if (a <= 0f) continue;
			GL11.glColor4f(SHROUD_DARK[0], SHROUD_DARK[1], SHROUD_DARK[2], alpha * a);
			Renderer3D.quad(at, camR, camU, across * 0.5f);
		}
	}

	private static final float COS_HALF_GAZE = (float) Math.cos(Math.toRadians(45));

	private static boolean gazing(Ship3D s) {
		ShipSystem3D sys = s.system;
		return sys != null && sys.kind == ShipSystem3D.Kind.DARKENED_GAZE && (sys.state == ShipSystem3D.State.IN || sys.state == ShipSystem3D.State.ACTIVE);
	}

	// ---- shared --------------------------------------------------------------------------------

	/**
	 * Fades a big soft sprite out as the camera gets into it (3D only: from above, vanilla never flies
	 * through a shroud), so being inside one isn't a black screen.
	 */
	private float nearFade(float size) {
		float d = at.distance(camPos);
		return Math.max(0f, Math.min(1f, (d - size * 0.5f) / size));
	}

	/** A flash's glow 0..1 at `t` seconds since it started: vanilla's 1 s up, 4 s down, squared. */
	private static float flashGlow(float t) {
		if (t <= 0f || t >= 5f) return 0f;
		float b = t < 1f ? t : 1f - (t - 1f) / 4f;
		return b * b;
	}

	/** A flashing piece: its bright copy, or the glow round it (vanilla: up to twice the flash radius across, half strength). */
	private void flash(SwarmLook l, Pass pass, float glow, float b, float size, float ang, int cell, float k) {
		if (pass == Pass.FLASH_COPY) {
			GL11.glColor4f(l.flashCore[0], l.flashCore[1], l.flashCore[2], l.flashCore[3] * b * glow);
			piece(at, size, ang, cell);
		} else {
			GL11.glColor4f(l.flashFringe[0], l.flashFringe[1], l.flashFringe[2], l.flashFringe[3] * b * glow * 0.5f);
			piece(at, l.flashRadius * (0.5f + 0.5f * glow) * 2f * k * b, 0f, -1);
		}
	}

	/** A camera-facing sprite `size` across, turned `angleDeg`, showing sheet cell `cell` of 4x4 (-1 = the whole texture). */
	private void piece(Vec3 c, float size, float angleDeg, int cell) {
		float half = size * 0.5f, a = (float) Math.toRadians(angleDeg), cs = (float) Math.cos(a) * half, sn = (float) Math.sin(a) * half;
		float rx = camR.x * cs + camU.x * sn, ry = camR.y * cs + camU.y * sn, rz = camR.z * cs + camU.z * sn;
		float ux = camU.x * cs - camR.x * sn, uy = camU.y * cs - camR.y * sn, uz = camU.z * cs - camR.z * sn;
		float u0 = 0f, v0 = 0f, du = 1f;
		if (cell >= 0) {
			u0 = (cell % 4) * 0.25f;
			v0 = (cell / 4) * 0.25f;
			du = 0.25f;
		}
		GL11.glTexCoord2f(u0, v0 + du); GL11.glVertex3f(c.x - rx - ux, c.y - ry - uy, c.z - rz - uz);
		GL11.glTexCoord2f(u0 + du, v0 + du); GL11.glVertex3f(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz);
		GL11.glTexCoord2f(u0 + du, v0); GL11.glVertex3f(c.x + rx + ux, c.y + ry + uy, c.z + rz + uz);
		GL11.glTexCoord2f(u0, v0); GL11.glVertex3f(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz);
	}

	private float angle(Ship3D s, int i, float maxTurn) {
		return hash(s, i, 12) * 360f + (hash(s, i, 13) * 2f - 1f) * maxTurn * time;
	}

	private static int cell(Ship3D s, int i) {
		return (int) (hash(s, i, 14) * 16f);
	}

	/** A steady random number 0..1 for (ship, index, salt). */
	private static float hash(Ship3D s, int i, int salt) {
		int h = s.id.hashCode() * 73856093 ^ i * 19349663 ^ salt * 83492791;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		h ^= h >>> 15;
		return (h & 0xffffff) / (float) 0x1000000;
	}
}
