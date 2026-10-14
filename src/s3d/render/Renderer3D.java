package s3d.render;

import static org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT;

import java.util.Random;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import com.fs.starfarer.api.Global;

import s3d.core.ChaseCamera;
import s3d.core.Combat;
import s3d.core.Effect;
import s3d.core.Missile3D;
import s3d.core.EngineMount;
import s3d.core.Projectile;
import s3d.core.WeaponMount;
import s3d.core.WeaponSpec3D;
import s3d.core.ShieldFlux;
import s3d.core.SystemLook;
import s3d.core.ShipSystem3D;
import s3d.core.Ship3D;
import s3d.core.Sim;
import s3d.core.Tuning;
import s3d.math.Mat4;
import s3d.math.Quat;
import s3d.math.Vec3;

/**
 * Draws one frame of the 3D scene into its own render target, then composites it full-screen
 * onto the caller's framebuffer. Pass order: sky, stars, opaque hulls, additive effects.
 */
public final class Renderer3D {
	private static final Logger log = Global.getLogger(Renderer3D.class);

	private Shader shipShader, fxShader, skyShader, starShader, shieldShader, holoShader, auraShader, retroShader;
	/** The retro filter's low-res ship pass (render.retro, scope "ships"). */
	private final RetroTarget retro = new RetroTarget();
	/** Threat fragment swarms and Shrouded shrouds round their ships. */
	private final CloudRenderer clouds = new CloudRenderer();
	/** Jitter copies get fresh random offsets every frame, as vanilla's do. */
	private final Random jitterRnd = new Random();
	private Mesh stars;
	private int starsBuiltFor = -1;
	private final SceneTarget target = new SceneTarget();
	private float[] dust;
	private int dustBuiltFor = -1;
	private GlyphFont font;
	private int whiteTex;
	private HudRenderer hud;
	private boolean initialized;

	private final Mat4 model = new Mat4(), mvp = new Mat4(), vpRot = new Mat4(), identity = new Mat4();
	private final Vec3 tmpA = new Vec3(), tmpB = new Vec3(), tmpC = new Vec3();

	// Stats for the perf HUD
	public int lastTriangles, lastShips;

	public void init() {
		if (initialized) return;
		GLCaps.detect();
		if (!GLCaps.shaders) throw new IllegalStateException("OpenGL 2.0 shaders unavailable: " + GLCaps.summary());
		if (!GLCaps.fbo) throw new IllegalStateException("EXT_framebuffer_object unavailable: " + GLCaps.summary());
		shipShader = Shader.build("ship", Shaders.SHIP_VS, Shaders.SHIP_FS, "a_pos", "a_nrm", "a_uv", "a_side");
		fxShader = Shader.build("fx", Shaders.FX_VS, Shaders.FX_FS);
		skyShader = Shader.build("sky", Shaders.SKY_VS, Shaders.SKY_FS);
		starShader = Shader.build("stars", Shaders.STAR_VS, Shaders.STAR_FS, "a_dir", "a_col");
		shieldShader = Shader.build("shield", Shaders.SHIELD_VS, Shaders.SHIELD_FS);
		holoShader = Shader.build("holo", Shaders.HOLO_VS, Shaders.HOLO_FS, "a_pos", "a_nrm");
		auraShader = Shader.build("aura", Shaders.AURA_VS, Shaders.AURA_FS, "a_pos", "a_nrm", "a_uv");
		retroShader = Shader.build("retro", Shaders.RETRO_VS, Shaders.RETRO_FS);
		try {
			font = GlyphFont.create(13);
		} catch (Throwable t) {
			log.warn("S3D: HUD font unavailable, text disabled", t);
		}
		BitmapFont victor = null; // vanilla's combat HUD font, for your ship panel and the status list
		try {
			victor = BitmapFont.load("graphics/fonts/victor14.fnt");
		} catch (Throwable t) {
			log.warn("S3D: vanilla HUD font unavailable, the ship panel uses the HUD font", t);
		}
		BitmapFont insignia = null; // vanilla's button font (the command map's "Full Assault!")
		try {
			insignia = BitmapFont.load("graphics/fonts/insignia17LTaa.fnt");
		} catch (Throwable t) {
			log.warn("S3D: vanilla button font unavailable, the fleet buttons use the HUD font", t);
		}
		hud = new HudRenderer(fxShader, font, victor, insignia);
		whiteTex = TextureUtil.upload(new int[] {0xFFFFFFFF}, 1, 1, false, 1f);
		initialized = true;
		log.info("S3D: renderer initialised");
	}

	/** HUD overlay, then the holograms the HUD asked for (they need the 3D mesh): your ship's and the target's. */
	public void renderHud(Sim sim, s3d.core.PilotInput in, HudRenderer.Info info, int w, int h) {
		hud.render(sim, in, info, w, h);
		for (HudRenderer.Holo req : new HudRenderer.Holo[] {hud.ownHolo, hud.targetHolo}) {
			if (req.ship != null) renderHologram(req.ship, sim.tuning, req, sim.time, w, h);
			req.ship = null;
		}
	}

	/** Whole screen black at this opacity, over everything drawn so far (the scene fading in at battle start). */
	public void fade(float alpha, int w, int h) {
		if (alpha <= 0f) return;
		GL11.glViewport(0, 0, w, h);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		fxShader.use().set("u_vp", identity).set("u_mode", 0);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glColor4f(0f, 0f, 0f, Math.min(1f, alpha));
		GL11.glVertex2f(-1, -1);
		GL11.glVertex2f(1, -1);
		GL11.glVertex2f(1, 1);
		GL11.glVertex2f(-1, 1);
		GL11.glEnd();
		GL11.glDisable(GL11.GL_BLEND);
		GL20.glUseProgram(0);
	}

	private final Mat4 holoProj = new Mat4(), holoModel = new Mat4(), holoMvp = new Mat4();
	private final float[] holoArmor = new float[s3d.core.ArmorShell.AZ * s3d.core.ArmorShell.EL];

	/**
	 * A ship as a hologram in a screen box: its own mesh, additive, cyan where the armor is whole and
	 * amber to red where it's gone (by direction), dimmer as hull drops. The target turns slowly; your
	 * own ship points nose up as vanilla's panel shows it, swaying a little.
	 */
	private void renderHologram(Ship3D s, Tuning t, HudRenderer.Holo req, float time, int w, int h) {
		ShipModel m = modelFor(s, t);
		if (m == null) return;
		float bw = req.w, bh = req.h;
		GL11.glViewport((int) req.x, (int) req.y, (int) bw, (int) bh);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
		float r = Math.max(5f, s.baseRadius);
		float fov = (float) Math.toRadians(30);
		float dist = r / (float) Math.tan(fov * 0.5f) * (req.noseUp ? 0.92f : 0.85f);
		holoProj.perspective(fov, bw / Math.max(1f, bh), dist * 0.2f, dist * 3f);
		Quat q = req.noseUp
				// From above and a little behind, nose to the top of the box.
				? new Quat().setAxisAngle(1, 0, 0, 1.2f).mulLocal(new Quat().setAxisAngle(0, 1, 0, 0.3f * (float) Math.sin(time * 0.35f)))
				// Seen from well above (the silhouette is what identifies a hull), turning slowly.
				: new Quat().setAxisAngle(1, 0, 0, 0.95f).mulLocal(new Quat().setAxisAngle(0, 1, 0, time * 0.5f));
		holoModel.fromRotationTranslation(q, 0f, 0f, -dist);
		holoMvp.mul(holoProj, holoModel);
		if (s.armor != null) System.arraycopy(s.armor.frac, 0, holoArmor, 0, holoArmor.length);
		else java.util.Arrays.fill(holoArmor, 1f);
		holoShader.use().set("u_mvp", holoMvp).set("u_model", holoModel).set("u_time", time)
				.set("u_hull", s.hullMax > 0f ? Math.max(0f, s.hull / s.hullMax) : 0f).set("u_col", 0.35f, 0.85f, 1f)
				.setArray("u_armor", holoArmor);
		m.mesh.draw(GL11.GL_TRIANGLES);
		GL20.glUseProgram(0);
		GL11.glViewport(0, 0, w, h);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glDisable(GL11.GL_BLEND);
	}

	public HudRenderer hud() {
		return hud;
	}

	public int msaaSamples() {
		return target.samples();
	}

	/** Renders the scene and leaves outputFbo bound with a full-screen viewport. */
	public void renderScene(Sim sim, int screenW, int screenH, int outputFbo) {
		Tuning t = sim.tuning;
		ChaseCamera cam = sim.camera;
		String retroMode = t.retroPixelSize > 1 || t.retroColorBits < 8 ? t.retroMode() : "off";
		boolean retroScene = retroMode.equals("scene");
		int px = Math.max(1, t.retroPixelSize), sceneW = retroScene ? Math.max(1, screenW / px) : screenW, sceneH = retroScene ? Math.max(1, screenH / px) : screenH;
		boolean retroShips = retroMode.equals("ships") && retro.ensure(Math.max(1, screenW / px), Math.max(1, screenH / px));
		// The whole-scene filter renders the scene target small, without MSAA (hard pixel edges are the look).
		if (!target.ensure(sceneW, sceneH, retroScene ? 0 : t.msaaSamples)) throw new IllegalStateException("scene render target unavailable");
		ensureStars(t.starCount);
		ensureDust(t.dustCount, t.dustRange);
		lastTriangles = 0;
		lastShips = 0;

		target.begin();
		GL11.glClearColor(0f, 0f, 0f, 1f);
		GL11.glClearDepth(1.0);
		GL11.glDepthMask(true);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDepthMask(false);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_BLEND);
		drawSky(cam, t, screenW / (float) screenH);

		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
		drawStars(cam, sceneW / (float) screenW);

		GL11.glDisable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		GL11.glDepthFunc(GL11.GL_LEQUAL);
		GL11.glDepthMask(true);
		GL11.glEnable(GL11.GL_CULL_FACE);
		GL11.glCullFace(GL11.GL_BACK);
		GL11.glFrontFace(GL11.GL_CCW);
		// PS1 wobble: vertices snap to a grid of retroWobble retro pixels (NDC spans 2 units).
		float wobble = retroShips || retroScene ? t.retroWobble : 0f;
		float snapX = wobble > 0f ? screenW / (float) px / (2f * wobble) : 0f, snapY = wobble > 0f ? screenH / (float) px / (2f * wobble) : 0f;
		if (retroShips) {
			retro.begin();
			drawShips(sim, snapX, snapY);
			target.begin();
			compositeRetroShips(t);
		} else {
			drawShips(sim, snapX, snapY);
		}
		drawMissileBodies(sim);

		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDepthMask(false);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTex); // keeps the fx sampler bound to something valid
		drawSystemLooks(sim);
		clouds.draw(sim, fxShader);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTex);
		fxShader.use().set("u_vp", cam.viewProj).set("u_tex", 0);
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
		drawShields(sim);
		fxShader.use().set("u_vp", cam.viewProj).set("u_tex", 0);
		drawEngines(sim);
		drawProjectiles(sim);
		drawBeams(sim);
		drawLidarBeams(sim);
		drawArcs(sim);
		drawMissileFx(sim);
		drawEffects(sim);
		drawRifts(sim);
		drawDust(sim);
		if (cam.tacticalBlend() > 0.01f) drawTacticalAids(sim, cam.tacticalBlend());
		GL11.glDepthMask(true);

		target.resolve();

		// Composite onto the caller's framebuffer.
		glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, outputFbo);
		GL11.glViewport(0, 0, screenW, screenH);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.texture());
		int filter = retroScene ? GL11.GL_NEAREST : GL11.GL_LINEAR;
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
		if (retroScene) {
			retroUniforms(t, target.width(), target.height()).set("u_depth", 0).set("u_useDepth", 0f);
		} else {
			fxShader.use().set("u_vp", identity).set("u_mode", 4).set("u_tex", 0);
		}
		fullScreenQuad();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTex);
		GL20.glUseProgram(0);
	}

	/**
	 * Retro filter, ships scope: the low-res ship pass onto the scene target, pixel-blocky and
	 * dithered, writing the ships' depth so effects drawn next still go behind/in front of hulls.
	 * Premultiplied blend, so phased (see-through) ships stay see-through.
	 */
	private void compositeRetroShips(Tuning t) {
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glDepthFunc(GL11.GL_ALWAYS);
		GL11.glDepthMask(true);
		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, retro.depthTexture());
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, retro.colorTexture());
		retroUniforms(t, retro.width(), retro.height()).set("u_depth", 1).set("u_useDepth", 1f);
		fullScreenQuad();
		GL20.glUseProgram(0);
		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		GL11.glDepthFunc(GL11.GL_LEQUAL);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_CULL_FACE);
	}

	/** The retro composite shader, bound, for a source of w x h retro pixels. */
	private Shader retroUniforms(Tuning t, int w, int h) {
		int bits = Math.min(8, Math.max(1, t.retroColorBits));
		return retroShader.use().set("u_color", 0).set("u_size", (float) w, (float) h)
				.set("u_levels", bits >= 8 ? 0f : (float) ((1 << bits) - 1)).set("u_dither", t.retroDither);
	}

	private static void fullScreenQuad() {
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
		GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
		GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
		GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
		GL11.glEnd();
	}

	// ------------------------------------------------------------------------------------------

	private void drawSky(ChaseCamera cam, Tuning t, float aspect) {
		Vec3 r = cam.rot.right(tmpA), u = cam.rot.up(tmpB), f = cam.rot.forward(tmpC);
		float tanY = (float) Math.tan(cam.fovYRad * 0.5);
		Vec3 sun = new Vec3(t.sunDir[0], t.sunDir[1], t.sunDir[2]).normalize();
		skyShader.use()
				.set("u_right", r.x, r.y, r.z).set("u_up", u.x, u.y, u.z).set("u_fwd", f.x, f.y, f.z)
				.set("u_tanX", tanY * aspect).set("u_tanY", tanY)
				.set("u_sunDir", sun.x, sun.y, sun.z).set("u_sunColor", t.sunColor)
				.set("u_nebA", t.nebulaColorA).set("u_nebB", t.nebulaColorB).set("u_nebStrength", t.nebulaStrength);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex2f(-1, -1);
		GL11.glVertex2f(1, -1);
		GL11.glVertex2f(1, 1);
		GL11.glVertex2f(-1, 1);
		GL11.glEnd();
	}

	private void ensureStars(int count) {
		if (stars != null && starsBuiltFor == count) return;
		if (stars != null) stars.delete();
		Random rnd = new Random(1337);
		float[] v = new float[count * 7];
		int[] idx = new int[count];
		for (int i = 0; i < count; i++) {
			double zz = rnd.nextDouble() * 2 - 1, phi = rnd.nextDouble() * Math.PI * 2, rr = Math.sqrt(1 - zz * zz);
			float bright = (float) Math.pow(rnd.nextDouble(), 6.0);
			float size = 1.0f + bright * 2.6f;
			float temp = (float) rnd.nextDouble();
			float lum = 0.18f + 0.82f * bright;
			v[i * 7] = (float) (rr * Math.cos(phi));
			v[i * 7 + 1] = (float) zz;
			v[i * 7 + 2] = (float) (rr * Math.sin(phi));
			v[i * 7 + 3] = lum * (0.8f + 0.25f * temp);
			v[i * 7 + 4] = lum * (0.85f + 0.1f * temp);
			v[i * 7 + 5] = lum * (1.05f - 0.25f * temp);
			v[i * 7 + 6] = size;
			idx[i] = i;
		}
		stars = Mesh.upload(v, idx, 7, new int[][] {{0, 3, 0}, {1, 4, 3}});
		starsBuiltFor = count;
	}

	/** pointScale: target pixels per screen pixel, so stars keep their on-screen size in a low-res scene. */
	private void drawStars(ChaseCamera cam, float pointScale) {
		vpRot.view(cam.rot, new Vec3());
		vpRot.mul(cam.proj, vpRot);
		starShader.use().set("u_vpRot", vpRot).set("u_pointScale", pointScale);
		GL11.glEnable(GL20.GL_VERTEX_PROGRAM_POINT_SIZE);
		GL11.glEnable(GL20.GL_POINT_SPRITE);
		stars.draw(GL11.GL_POINTS);
		GL11.glDisable(GL20.GL_POINT_SPRITE);
		GL11.glDisable(GL20.GL_VERTEX_PROGRAM_POINT_SIZE);
	}

	/** snapX/snapY: the retro wobble grid (half its size in cells, per NDC unit), 0 = no wobble. */
	private void drawShips(Sim sim, float snapX, float snapY) {
		Tuning t = sim.tuning;
		ChaseCamera cam = sim.camera;
		Vec3 sun = new Vec3(t.sunDir[0], t.sunDir[1], t.sunDir[2]).normalize();
		Vec3 fill = new Vec3(t.fillDir[0], t.fillDir[1], t.fillDir[2]).normalize();
		shipShader.use()
				.set("u_snap", snapX, snapY)
				.set("u_tex", 0)
				.set("u_nrm", 1)
				.set("u_bump", t.bumpStrength)
				.set("u_lightDir", sun.x, sun.y, sun.z)
				.set("u_lightColor", t.sunColor)
				.set("u_ambient", t.ambient)
				.set("u_fillDir", fill.x, fill.y, fill.z)
				.set("u_fillColor", t.fillColor)
				.set("u_camPos", cam.pos.x, cam.pos.y, cam.pos.z);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		Quat q = new Quat(), bank = new Quat();
		java.util.List<Ship3D> ghosts = null;
		boolean commanding = cam.tacticalBlend() > 0.5f;
		for (Ship3D s : sim.ships) {
			if (s.noHull) continue; // attack / construction swarms: only their swarm is drawn
			if (s.destroyed && commanding) continue; // tactical view: wrecks are clutter
			if (s.phaseLevel > 0.01f) {
				// Phased ships are drawn afterwards, see-through.
				if (ghosts == null) ghosts = new java.util.ArrayList<>();
				ghosts.add(s);
				continue;
			}
			ShipModel m = modelFor(s, t);
			if (m == null) continue;
			bank.setAxisAngle(0, 0, 1, s.visualBank);
			q.mul(s.rot, bank);
			model.fromRotationTranslation(q, s.pos.x, s.pos.y, s.pos.z).scaleLocal(s.scale);
			mvp.mul(cam.viewProj, model);
			shipShader.set("u_model", model).set("u_mvp", mvp).set("u_tint", m.tint)
					.set("u_hitFlash", Math.min(1f, s.hitFlash * 3f)).set("u_dead", s.destroyed ? 1f : 0f);
			bindModel(m);
			m.mesh.draw(GL11.GL_TRIANGLES);
			lastTriangles += m.mesh.indexCount() / 3;
			lastShips++;
			drawWeapons(s, t, cam);
		}
		if (sim.shuttle != null && sim.shuttleModelSource instanceof ShipModel.Source) { // your command shuttle (vanilla's Shuttle Pod)
			if (sim.shuttleModel == null) sim.shuttleModel = ShipModel.get((ShipModel.Source) sim.shuttleModelSource, t);
			ShipModel m = (ShipModel) sim.shuttleModel;
			Vec3 d = sim.shuttle.dir;
			Vec3 up = new Vec3(Math.abs(d.y) > 0.95f ? 1f : 0f, Math.abs(d.y) > 0.95f ? 0f : 1f, 0f);
			q.setLookRotation(d, up);
			model.fromRotationTranslation(q, sim.shuttle.pos.x, sim.shuttle.pos.y, sim.shuttle.pos.z).scaleLocal(t.scaleFor("frigate"));
			mvp.mul(cam.viewProj, model);
			shipShader.set("u_model", model).set("u_mvp", mvp).set("u_tint", m.tint).set("u_hitFlash", 0f).set("u_dead", 0f);
			bindModel(m);
			m.mesh.draw(GL11.GL_TRIANGLES);
			lastTriangles += m.mesh.indexCount() / 3;
		}
		if (ghosts != null) {
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(0x8003, 0x8004); // GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA (constant set per ship below)
			GL11.glDepthMask(false);
			for (Ship3D s : ghosts) {
				ShipModel m = modelFor(s, t);
				if (m == null) continue;
				GL14.glBlendColor(0f, 0f, 0f, 1f - 0.75f * s.phaseLevel); // vanilla: a quarter visible when fully phased
				bank.setAxisAngle(0, 0, 1, s.visualBank);
				q.mul(s.rot, bank);
				model.fromRotationTranslation(q, s.pos.x, s.pos.y, s.pos.z).scaleLocal(s.scale);
				mvp.mul(cam.viewProj, model);
				shipShader.set("u_model", model).set("u_mvp", mvp).set("u_tint", m.tint)
						.set("u_hitFlash", 0f).set("u_dead", 0f);
				bindModel(m);
				m.mesh.draw(GL11.GL_TRIANGLES);
				lastTriangles += m.mesh.indexCount() / 3;
				lastShips++;
			}
			GL11.glDepthMask(true);
			GL11.glDisable(GL11.GL_BLEND);
		}
		unbindDetail();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
	}

	/** Turret/hardpoint sprites as small relief meshes, pointing where the barrel is aimed. */
	private void drawWeapons(Ship3D s, Tuning t, ChaseCamera cam) {
		Quat wr = new Quat();
		Vec3 wp = new Vec3();
		for (WeaponMount mt : s.weapons) {
			if (mt.model == null && mt.modelSource instanceof ShipModel.Source) mt.model = ShipModel.get((ShipModel.Source) mt.modelSource, t);
			if (mt.gunModel == null && mt.gunModelSource instanceof ShipModel.Source) mt.gunModel = ShipModel.get((ShipModel.Source) mt.gunModelSource, t);
			if (!(mt.model instanceof ShipModel)) continue;
			mt.worldPos(s, wp);
			mt.worldRot(s, wr);
			drawPart((ShipModel) mt.model, wr, wp, s, cam);
			if (mt.gunModel instanceof ShipModel) {
				Vec3 up = wr.up(new Vec3()).scale(0.6f * s.scale);
				drawPart((ShipModel) mt.gunModel, wr, up.add(wp), s, cam);
			}
		}
	}

	private void drawPart(ShipModel m, Quat rot, Vec3 pos, Ship3D s, ChaseCamera cam) {
		model.fromRotationTranslation(rot, pos.x, pos.y, pos.z).scaleLocal(s.scale);
		mvp.mul(cam.viewProj, model);
		shipShader.set("u_model", model).set("u_mvp", mvp).set("u_tint", m.tint)
				.set("u_hitFlash", Math.min(1f, s.hitFlash * 3f)).set("u_dead", s.destroyed ? 1f : 0f);
		bindModel(m);
		m.mesh.draw(GL11.GL_TRIANGLES);
		lastTriangles += m.mesh.indexCount() / 3;
	}

	/** Colour texture on unit 0, surface-detail normal map on unit 1. */
	private static void bindModel(ShipModel m) {
		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, m.normalTexture);
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, m.texture);
	}

	private static void unbindDetail() {
		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
	}

	/** Builds (or fetches) the GPU model for a ship the first time it's drawn. */
	public static ShipModel modelFor(Ship3D s, Tuning t) {
		if (s.model instanceof ShipModel) return (ShipModel) s.model;
		if (!(s.modelSource instanceof ShipModel.Source)) return null;
		ShipModel m = ShipModel.get((ShipModel.Source) s.modelSource, t);
		s.model = m;
		return m;
	}

	private void drawEngines(Sim sim) {
		Tuning t = sim.tuning;
		ChaseCamera cam = sim.camera;
		Vec3 camR = cam.rot.right(new Vec3()), camU = cam.rot.up(new Vec3());
		float[] look = new float[8];

		fxShader.set("u_mode", 1);
		GL11.glBegin(GL11.GL_QUADS);
		for (Ship3D s : sim.ships) {
			if (s.noHull) continue;
			engineLook(s, look);
			for (int ei = 0; ei < s.engines.size(); ei++) {
				if (s.engineDown != null && ei < s.engineDown.length && s.engineDown[ei] > 0f) continue; // knocked out by EMP
				flame(cam, t, s, s.engines.get(ei), s.thrustVisual, 1f, look);
			}
			if (look[7] > 0f && !s.destroyed) for (EngineMount e : s.systemEngines) flame(cam, t, s, e, 1f, look[7], look);
		}
		GL11.glEnd();

		fxShader.set("u_mode", 2);
		GL11.glBegin(GL11.GL_QUADS);
		Vec3 p = new Vec3();
		for (Ship3D s : sim.ships) {
			if (s.noHull) continue;
			engineLook(s, look);
			float thrust = Math.min(1f, s.thrustVisual);
			float glowA = (float) Math.pow(look[2], 0.6);
			for (int ei = 0; ei < s.engines.size(); ei++) {
				EngineMount e = s.engines.get(ei);
				if (s.engineDown != null && ei < s.engineDown.length && s.engineDown[ei] > 0f) continue;
				if (look[0] <= 0.01f || look[1] <= 0.01f) continue;
				s.rot.rotate(e.pos.x * s.scale, e.pos.y * s.scale, e.pos.z * s.scale, p).add(s.pos);
				float r = e.width * 0.75f * (0.5f + 0.6f * thrust) * t.engineFlameScale * s.scale * (float) Math.sqrt(look[1]);
				GL11.glColor4f(mixc(e.r, look, 3), mixc(e.g, look, 4), mixc(e.b, look, 5), (0.22f + 0.33f * thrust) * glowA);
				quad(p, camR, camU, r);
			}
			if (look[7] > 0f && !s.destroyed) {
				for (EngineMount e : s.systemEngines) {
					s.rot.rotate(e.pos.x * s.scale, e.pos.y * s.scale, e.pos.z * s.scale, p).add(s.pos);
					GL11.glColor4f(mixc(e.r, look, 3), mixc(e.g, look, 4), mixc(e.b, look, 5), 0.55f * look[7] * glowA);
					quad(p, camR, camU, e.width * 0.85f * t.engineFlameScale * s.scale);
				}
			}
		}
		GL11.glEnd();
	}

	/** One engine flame (a camera-facing tapered quad) at the given throttle and strength, shaped by the system look. */
	private final Vec3 fp = new Vec3(), fd = new Vec3(), fToCam = new Vec3(), fSide = new Vec3();

	private void flame(ChaseCamera cam, Tuning t, Ship3D s, EngineMount e, float thrust, float level, float[] look) {
		float len = e.length * (0.25f + 0.85f * thrust) * t.engineFlameScale * s.scale * look[0] * level;
		float wid = e.width * 0.55f * t.engineFlameScale * s.scale * look[1];
		if (len < 0.5f || wid < 0.2f) return;
		s.rot.rotate(e.pos.x * s.scale, e.pos.y * s.scale, e.pos.z * s.scale, fp).add(s.pos);
		s.rot.rotate(e.dir, fd);
		fToCam.set(cam.pos).sub(fp).normalize();
		fSide.set(fd).cross(fToCam);
		if (fSide.lengthSq() < 1e-6f) return;
		fSide.normalize().scale(wid * 0.5f);
		float a = (0.45f + 0.6f * Math.min(1f, thrust)) * level * (float) Math.pow(look[2], 0.6);
		Vec3 p = fp, d = fd, side = fSide;
		GL11.glColor4f(mixc(e.r, look, 3), mixc(e.g, look, 4), mixc(e.b, look, 5), a);
		GL11.glTexCoord2f(0, 0); GL11.glVertex3f(p.x - side.x, p.y - side.y, p.z - side.z);
		GL11.glTexCoord2f(0, 1); GL11.glVertex3f(p.x + side.x, p.y + side.y, p.z + side.z);
		GL11.glTexCoord2f(1, 1); GL11.glVertex3f(p.x + d.x * len + side.x * 0.45f, p.y + d.y * len + side.y * 0.45f, p.z + d.z * len + side.z * 0.45f);
		GL11.glTexCoord2f(1, 0); GL11.glVertex3f(p.x + d.x * len - side.x * 0.45f, p.y + d.y * len - side.y * 0.45f, p.z + d.z * len - side.z * 0.45f);
	}

	/**
	 * What the running system (and phase cloak) does to the engines, as vanilla's engine glow
	 * settings: {length, width, glow, r, g, b, how far toward that colour, system-only thrusters 0..1}.
	 */
	private static void engineLook(Ship3D s, float[] out) {
		out[0] = out[1] = out[2] = 1f;
		out[3] = out[4] = out[5] = out[6] = out[7] = 0f;
		applyEngineLook(s.system, out);
		applyEngineLook(s.cloak, out);
	}

	private static void applyEngineLook(ShipSystem3D sys, float[] out) {
		if (sys == null || sys.effect <= 0f || !sys.supported()) return;
		SystemLook l = sys.look;
		float e = sys.effect;
		out[0] *= 1f + (l.engineLength - 1f) * e;
		out[1] *= 1f + (l.engineWidth - 1f) * e;
		out[2] *= 1f + (l.engineGlow - 1f) * e;
		if (l.engineColor != null && l.engineColor[3] > 0f) {
			out[3] = l.engineColor[0];
			out[4] = l.engineColor[1];
			out[5] = l.engineColor[2];
			out[6] = Math.max(out[6], e * l.engineColor[3]);
		}
		if (l.systemEngines) out[7] = Math.max(out[7], e);
	}

	private static float mixc(float c, float[] look, int i) {
		return c + (look[i] - c) * look[6];
	}

	/**
	 * Ship system looks as vanilla draws them, plus the vent glow and phase highlight:
	 * - jitter: copies of the hull, tinted, at fresh random offsets each frame across the screen
	 *   (vanilla's ranges in sprite pixels). "Under" copies sit just behind the hull so only a fuzzy
	 *   halo shows round the silhouette; "over" copies sit just in front and tint it.
	 * - weapon glow: the weapons of the system's type (Lidar: its hardpoints) drawn again, tinted.
	 * - the system's target (Entropy Amplifier) and the carrier's fighters (Targeting Feed, Recall
	 *   Device, Termination Sequence) get their jitter / weapon glow too.
	 */
	private void drawSystemLooks(Sim sim) {
		ChaseCamera cam = sim.camera;
		java.util.Map<Ship3D, float[]> targetJitter = null; // target -> {r, g, b, a, level, copies, range}
		for (Ship3D s : sim.ships) {
			ShipSystem3D sys = s.system;
			if (s.destroyed || sys == null || sys.effect <= 0f || sys.look.targetJitter == null || sys.sysTarget == null || sys.sysTarget.destroyed) continue;
			if (targetJitter == null) targetJitter = new java.util.IdentityHashMap<>();
			float[] c = sys.look.targetJitter;
			targetJitter.put(sys.sysTarget, new float[] {c[0], c[1], c[2], c[3], sys.effect, sys.look.targetJitterCopies, sys.look.targetJitterRange});
		}
		boolean begun = false;
		Quat q = new Quat(), bank = new Quat();
		Vec3 camR = cam.rot.right(new Vec3()), camU = cam.rot.up(new Vec3()), camF = cam.rot.forward(new Vec3());
		for (Ship3D s : sim.ships) {
			if (s.destroyed) continue;
			ShipSystem3D sys = s.system;
			boolean afterglow = sys != null && sys.orion != null && sys.look.jitterAfterUse > 0f && sys.orion.sinceDetonation < sys.look.jitterAfterUse;
			boolean own = sys != null && sys.supported() && (sys.effect > 0f || afterglow);
			ShipSystem3D carrierSys = s.fighter && s.carrier != null && !s.carrier.destroyed ? s.carrier.system : null;
			boolean fromCarrier = carrierSys != null && carrierSys.effect > 0f && (carrierSys.look.fighterJitter != null || carrierSys.look.fighterJitterUnder != null || carrierSys.look.fighterWeaponGlow != null);
			float[] tj = targetJitter != null ? targetJitter.get(s) : null;
			boolean vent = s.flux != null && s.flux.venting;
			if (!own && !fromCarrier && tj == null && !vent && s.phaseLevel <= 0.01f) continue;
			ShipModel m = modelFor(s, sim.tuning);
			if (m == null) continue;
			if (!begun) {
				auraShader.use().set("u_cam", cam.pos.x, cam.pos.y, cam.pos.z).set("u_time", sim.time).set("u_tex", 0);
				begun = true;
			}
			bank.setAxisAngle(0, 0, 1, s.visualBank);
			q.mul(s.rot, bank);
			float px = Math.max(1f, s.radius / Math.max(0.01f, s.scale)); // collision radius in sprite pixels
			if (own) {
				SystemLook l = sys.look;
				float lev = l.jitterLevel(sys), grow = l.jitterGrow * sys.effect;
				if (l.jitterShrinkIn > 0f && sys.state == ShipSystem3D.State.IN) grow += (1f - sys.effect * sys.effect) * l.jitterShrinkIn; // Energy Lash gathering in
				if (l.jitterUnder != null && l.jitterUnderCopies > 0) {
					jitter(sim, m, s, q, l.jitterUnder, lev, l.jitterUnderCopies, l.jitterUnderMin, l.jitterUnderRange + l.jitterUnderRadiusFraction * px + grow, true, camR, camU, camF);
				}
				if (l.jitter != null && l.jitterCopies > 0) {
					jitter(sim, m, s, q, l.jitter, lev, l.jitterCopies, l.jitterMin, l.jitterRange + l.jitterRadiusFraction * px + grow, false, camR, camU, camF);
				}
				if (l.weaponGlow != null && l.weaponGlow[3] > 0f) weaponGlow(sim, s, l, l.weaponGlow, sys.effect, camF);
			}
			if (fromCarrier) {
				SystemLook l = carrierSys.look;
				float e = carrierSys.effect, range = l.fighterJitterRange + (l.fighterJitterGrows ? e * px : 0f);
				if (l.fighterJitterUnder != null) jitter(sim, m, s, q, l.fighterJitterUnder, e, l.fighterJitterUnderCopies, 0f, range, true, camR, camU, camF);
				if (l.fighterJitter != null) jitter(sim, m, s, q, l.fighterJitter, e, l.fighterJitterCopies, 0f, range, false, camR, camU, camF);
				if (l.fighterWeaponGlow != null) weaponGlow(sim, s, null, l.fighterWeaponGlow, e, camF);
			}
			if (tj != null) jitter(sim, m, s, q, tj, tj[4], (int) tj[5], 0f, tj[6], false, camR, camU, camF);
			if (vent) { // vanilla's radial vent glow, in the fringe colour
				float pulse = 0.25f + 0.1f * (float) Math.sin(sim.time * 9f + s.radius);
				glow(sim, m, s, q, s.ventFringe, pulse * (0.4f + 0.6f * s.flux.level()), 0f);
			}
			if (s.phaseLevel > 0.01f) { // phase highlight: the cloak's effect colour along the silhouette
				ShipSystem3D cloak = s.cloak != null ? s.cloak : sys;
				float[] c = cloak != null && cloak.look.effect1 != null ? cloak.look.effect1 : new float[] {1f, 0.69f, 1f};
				glow(sim, m, s, q, c, 0.45f * s.phaseLevel, 0.4f);
			}
		}
		if (begun) GL20.glUseProgram(0);
		fxShader.use().set("u_vp", sim.camera.viewProj).set("u_tex", 0);
		if (!glowSpots.isEmpty()) { // the weapon glows' soft halos, so they read at a distance too
			fxShader.set("u_mode", 2);
			GL11.glBegin(GL11.GL_QUADS);
			Vec3 at = new Vec3();
			for (float[] g : glowSpots) {
				GL11.glColor4f(g[4], g[5], g[6], g[7]);
				quad(at.set(g[0], g[1], g[2]), camR, camU, g[3]);
			}
			GL11.glEnd();
			glowSpots.clear();
		}
	}

	/** This frame's weapon glow halos: {x, y, z, size, r, g, b, a}. */
	private final java.util.List<float[]> glowSpots = new java.util.ArrayList<>();

	/** Most copies drawn per jitter layer (vanilla's damper has 25); the brightness of the rest is kept. */
	private static final int MAX_JITTER_COPIES = 10;

	/**
	 * Vanilla's jitter in 3D: `copies` tinted copies of the hull, each shifted by a fresh random
	 * amount between minPx and maxPx sprite pixels across the screen. Under: pushed just behind
	 * the hull (only the halo round the silhouette shows). Over: just in front (tints the hull).
	 */
	private void jitter(Sim sim, ShipModel m, Ship3D s, Quat rot, float[] col, float level, int copies, float minPx, float maxPx,
			boolean under, Vec3 camR, Vec3 camU, Vec3 camF) {
		if (level <= 0.005f || copies <= 0) return;
		int n = Math.min(copies, MAX_JITTER_COPIES);
		float alpha = col[3] * level * (copies / (float) n) * (under ? 0.2f : 0.45f);
		float push = s.radius * 0.04f * (under ? 1f : -1f); // along the view: behind or in front of the hull
		Vec3 at = new Vec3();
		auraShader.set("u_col", col[0], col[1], col[2]).set("u_alpha", alpha).set("u_bands", 0f).set("u_textured", 1f).set("u_inflate", 0f);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, m.texture);
		for (int i = 0; i < n; i++) {
			double ang = jitterRnd.nextDouble() * Math.PI * 2;
			float r = (minPx + (maxPx - minPx) * (float) Math.sqrt(jitterRnd.nextFloat())) * s.scale;
			at.set(s.pos).addScaled(camR, (float) Math.cos(ang) * r).addScaled(camU, (float) Math.sin(ang) * r).addScaled(camF, push);
			model.fromRotationTranslation(rot, at.x, at.y, at.z).scaleLocal(s.scale);
			mvp.mul(sim.camera.viewProj, model);
			auraShader.set("u_mvp", mvp).set("u_model", model);
			m.mesh.draw(GL11.GL_TRIANGLES);
			lastTriangles += m.mesh.indexCount() / 3;
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTex);
	}

	/** A glow along the silhouette (vent, phase highlight): the hull pushed out a little, bright at grazing angles. */
	private void glow(Sim sim, ShipModel m, Ship3D s, Quat rot, float[] col, float alpha, float bands) {
		if (alpha <= 0.005f) return;
		model.fromRotationTranslation(rot, s.pos.x, s.pos.y, s.pos.z).scaleLocal(s.scale);
		mvp.mul(sim.camera.viewProj, model);
		auraShader.set("u_mvp", mvp).set("u_model", model).set("u_col", col[0], col[1], col[2]).set("u_alpha", alpha)
				.set("u_bands", bands).set("u_textured", 0f).set("u_inflate", 1.2f);
		m.mesh.draw(GL11.GL_TRIANGLES);
		lastTriangles += m.mesh.indexCount() / 3;
	}

	/**
	 * Vanilla's weapon glow: the weapons of the system's types (Lidar Array: non-beam ballistic and
	 * energy hardpoints; a carrier's feed: all of a fighter's) drawn again in the glow colour.
	 */
	private void weaponGlow(Sim sim, Ship3D s, SystemLook l, float[] col, float level, Vec3 camF) {
		Quat wr = new Quat();
		Vec3 wp = new Vec3();
		auraShader.set("u_col", col[0], col[1], col[2]).set("u_alpha", col[3] * level).set("u_bands", 0f).set("u_textured", 1f).set("u_inflate", 0f);
		for (WeaponMount mt : s.weapons) {
			if (l != null && !glows(l, mt)) continue;
			mt.worldPos(s, wp);
			float px = "LARGE".equals(mt.spec.size) ? 18f : "MEDIUM".equals(mt.spec.size) ? 12f : 8f;
			glowSpots.add(new float[] {wp.x, wp.y, wp.z, px * 2f * s.scale, col[0], col[1], col[2], 0.8f * Math.min(1f, col[3] * 1.4f) * level});
			if (!(mt.model instanceof ShipModel)) continue;
			wp.addScaled(camF, -s.scale); // a hair in front so it reads over the gun
			mt.worldRot(s, wr);
			weaponPart(sim, (ShipModel) mt.model, wr, wp, s);
			if (mt.gunModel instanceof ShipModel) {
				Vec3 up = wr.up(new Vec3()).scale(0.6f * s.scale);
				weaponPart(sim, (ShipModel) mt.gunModel, wr, up.add(wp), s);
			}
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTex);
	}

	private static boolean glows(SystemLook l, WeaponMount mt) {
		if (l.glowFragmentWeapons) return s3d.core.Combat.fragmentWeapon(mt.spec);
		if (l.glowLidarHardpoints) {
			return mt.hardpoint && mt.spec.kind != s3d.core.WeaponSpec3D.Kind.BEAM && ("BALLISTIC".equals(mt.spec.type) || "ENERGY".equals(mt.spec.type));
		}
		return mt.spec.type != null && l.weaponTypes.contains(mt.spec.type);
	}

	private void weaponPart(Sim sim, ShipModel m, Quat rot, Vec3 pos, Ship3D s) {
		model.fromRotationTranslation(rot, pos.x, pos.y, pos.z).scaleLocal(s.scale);
		mvp.mul(sim.camera.viewProj, model);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, m.texture);
		auraShader.set("u_mvp", mvp).set("u_model", model);
		m.mesh.draw(GL11.GL_TRIANGLES);
		lastTriangles += m.mesh.indexCount() / 3;
	}

	/**
	 * Lidar Array winding up: its lidar dishes (vanilla decorative beam weapons) fire their ranging
	 * beams at what the ship is aiming at, in the dish's own beam colours.
	 */
	private void drawLidarBeams(Sim sim) {
		ChaseCamera cam = sim.camera;
		boolean begun = false;
		Vec3 a = new Vec3(), b = new Vec3();
		for (Ship3D s : sim.ships) {
			ShipSystem3D sys = s.system;
			if (s.destroyed || sys == null || sys.kind != ShipSystem3D.Kind.LIDAR_ARRAY || sys.state != ShipSystem3D.State.IN || s.lidarDishes.isEmpty()) continue;
			Vec3 aim = s == sim.player && sim.hasAimPoint ? sim.aimPoint
					: s.ai != null && s.ai.target != null && !s.ai.target.destroyed ? s.ai.target.pos : null;
			for (WeaponMount d : s.lidarDishes) {
				d.worldPos(s, a);
				float reach = d.spec.range * sim.tuning.weaponRangeMult;
				if (aim != null) b.set(aim).sub(a);
				else s.rot.forward(b);
				float len = b.length();
				if (len < 1f) continue;
				b.scale(Math.min(reach, aim != null ? len : reach) / len).add(a);
				if (!begun) {
					fxShader.set("u_mode", 6);
					GL11.glBegin(GL11.GL_QUADS);
					begun = true;
				}
				float flick = 0.8f + 0.2f * (float) Math.sin(sim.time * 30f + d.localPos.x);
				beamRibbon(cam, a, b, Math.max(2f, d.spec.beamWidth * 0.5f), d.spec.fringeColor, d.spec.coreColor, sys.effect * flick);
			}
		}
		if (begun) {
			GL11.glEnd();
			fxShader.set("u_mode", 2);
		}
	}

	/** Camera-facing streaks: fringe colour outside, core colour along the centre, fading toward the tail. */
	private void drawProjectiles(Sim sim) {
		ChaseCamera cam = sim.camera;
		Vec3 toCam = new Vec3(), side = new Vec3(), tail = new Vec3();
		fxShader.set("u_mode", 5);
		GL11.glBegin(GL11.GL_QUADS);
		for (Projectile p : sim.combat.projectiles) {
			WeaponSpec3D w = p.spec;
			float len = Math.max(8f, w.projLength), wid = Math.max(2f, w.projWidth) * 1.6f;
			float dist = p.distanceFromOrigin();
			float a = Math.min(1f, Math.max(0f, (p.maxRange - dist) / (0.15f * p.maxRange)));
			len = Math.min(len, dist + 1f); // don't draw the tail behind the muzzle
			tail.set(p.pos).addScaled(p.dir, -len);
			toCam.set(cam.pos).sub(p.pos).normalize();
			side.set(p.dir).cross(toCam);
			if (side.lengthSq() < 1e-6f) continue;
			side.normalize().scale(wid * 0.5f);
			GL11.glColor4f(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2], a);
			GL14.glSecondaryColor3f(w.coreColor[0], w.coreColor[1], w.coreColor[2]);
			GL11.glTexCoord2f(0, 0); GL11.glVertex3f(p.pos.x - side.x, p.pos.y - side.y, p.pos.z - side.z);
			GL11.glTexCoord2f(0, 1); GL11.glVertex3f(p.pos.x + side.x, p.pos.y + side.y, p.pos.z + side.z);
			GL11.glTexCoord2f(1, 1); GL11.glVertex3f(tail.x + side.x, tail.y + side.y, tail.z + side.z);
			GL11.glTexCoord2f(1, 0); GL11.glVertex3f(tail.x - side.x, tail.y - side.y, tail.z - side.z);
		}
		GL11.glEnd();
		// Glowing head so shots stay readable from the chase camera (Starsector sizes them for a zoomed-out 2D view).
		Vec3 camR = cam.rot.right(new Vec3()), camU = cam.rot.up(new Vec3());
		fxShader.set("u_mode", 2);
		GL11.glBegin(GL11.GL_QUADS);
		for (Projectile p : sim.combat.projectiles) {
			WeaponSpec3D w = p.spec;
			float dist = p.distanceFromOrigin();
			float a = Math.min(1f, Math.max(0f, (p.maxRange - dist) / (0.15f * p.maxRange)));
			float size = Math.max(4f, w.projWidth * 1.8f) + p.pos.distance(cam.pos) * 0.004f;
			GL11.glColor4f(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2], 0.9f * a);
			quad(p.pos, camR, camU, size);
		}
		GL11.glEnd();
	}

	/** Missile bodies: the missile sprite as a small flat relief mesh, nose along its heading. */
	private void drawMissileBodies(Sim sim) {
		Tuning t = sim.tuning;
		ChaseCamera cam = sim.camera;
		if (sim.combat.missiles.isEmpty()) return;
		Vec3 sun = new Vec3(t.sunDir[0], t.sunDir[1], t.sunDir[2]).normalize();
		Vec3 fill = new Vec3(t.fillDir[0], t.fillDir[1], t.fillDir[2]).normalize();
		shipShader.use().set("u_snap", 0f, 0f).set("u_tex", 0).set("u_nrm", 1).set("u_bump", t.bumpStrength)
				.set("u_lightDir", sun.x, sun.y, sun.z).set("u_lightColor", t.sunColor)
				.set("u_ambient", t.ambient).set("u_fillDir", fill.x, fill.y, fill.z).set("u_fillColor", t.fillColor)
				.set("u_camPos", cam.pos.x, cam.pos.y, cam.pos.z).set("u_hitFlash", 0f).set("u_dead", 0f);
		Quat q = new Quat();
		Vec3 up = new Vec3();
		for (Missile3D ms : sim.combat.missiles) {
			WeaponSpec3D w = ms.spec;
			if (w.missileModel == null && w.missileModelSource instanceof ShipModel.Source) {
				w.missileModel = ShipModel.get((ShipModel.Source) w.missileModelSource, t);
			}
			if (!(w.missileModel instanceof ShipModel)) continue;
			ShipModel m = (ShipModel) w.missileModel;
			up.set(Math.abs(ms.dir.y) > 0.95f ? 1f : 0f, Math.abs(ms.dir.y) > 0.95f ? 0f : 1f, 0f);
			q.setLookRotation(ms.dir, up);
			model.fromRotationTranslation(q, ms.pos.x, ms.pos.y, ms.pos.z);
			mvp.mul(cam.viewProj, model);
			shipShader.set("u_model", model).set("u_mvp", mvp).set("u_tint", m.tint);
			bindModel(m);
			m.mesh.draw(GL11.GL_TRIANGLES);
			lastTriangles += m.mesh.indexCount() / 3;
		}
		unbindDetail();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
	}

	/** Missile engine flames, glows and fading trails. */
	private void drawMissileFx(Sim sim) {
		ChaseCamera cam = sim.camera;
		if (sim.combat.missiles.isEmpty()) return;
		Vec3 camR = cam.rot.right(new Vec3()), camU = cam.rot.up(new Vec3());
		Vec3 a = new Vec3(), b = new Vec3(), side = new Vec3(), toCam = new Vec3(), seg = new Vec3();
		// Trails
		fxShader.set("u_mode", 0);
		GL11.glBegin(GL11.GL_QUADS);
		for (Missile3D ms : sim.combat.missiles) {
			float[] c = ms.spec.missileGlow;
			float fadeAll = ms.flamedOut() ? Math.max(0f, 1f - ms.fade) : 1f;
			float wid = Math.max(2f, ms.spec.missileRadius * 0.45f);
			int n = ms.trailCount;
			for (int i = 0; i < n - 1; i++) {
				int i0 = (ms.trailHead - n + i + Missile3D.TRAIL) % Missile3D.TRAIL;
				int i1 = (i0 + 1) % Missile3D.TRAIL;
				a.set(ms.trail[i0 * 3], ms.trail[i0 * 3 + 1], ms.trail[i0 * 3 + 2]);
				b.set(ms.trail[i1 * 3], ms.trail[i1 * 3 + 1], ms.trail[i1 * 3 + 2]);
				seg.set(b).sub(a);
				toCam.set(cam.pos).sub(b);
				side.set(seg).cross(toCam);
				if (side.lengthSq() < 1e-6f) continue;
				side.normalize().scale(wid * 0.5f);
				float al0 = 0.35f * fadeAll * i / n, al1 = 0.35f * fadeAll * (i + 1) / n;
				GL11.glColor4f(c[0] * al0, c[1] * al0, c[2] * al0, 1f);
				GL11.glVertex3f(a.x - side.x, a.y - side.y, a.z - side.z);
				GL11.glVertex3f(a.x + side.x, a.y + side.y, a.z + side.z);
				GL11.glColor4f(c[0] * al1, c[1] * al1, c[2] * al1, 1f);
				GL11.glVertex3f(b.x + side.x, b.y + side.y, b.z + side.z);
				GL11.glVertex3f(b.x - side.x, b.y - side.y, b.z - side.z);
			}
		}
		GL11.glEnd();
		// Engine glow at the tail
		fxShader.set("u_mode", 2);
		GL11.glBegin(GL11.GL_QUADS);
		for (Missile3D ms : sim.combat.missiles) {
			if (ms.flamedOut()) continue;
			if (ms.spec.mine) {
				// A mine: a slow red pulse, quick and bright once it's winding up to blow.
				float rate = ms.windup >= 0f ? 14f : 3f;
				float pulse = 0.5f + 0.5f * (float) Math.sin(sim.time * rate + ms.spawnPos.x);
				float size = ms.spec.missileRadius * (ms.windup >= 0f ? 2.2f : 1.4f);
				GL11.glColor4f(1f, 0.25f, 0.2f, (ms.windup >= 0f ? 0.6f : 0.3f) + 0.4f * pulse);
				quad(ms.pos, camR, camU, size);
				continue;
			}
			float[] c = ms.spec.missileGlow;
			float len = ms.spec.missileRadius;
			a.set(ms.pos).addScaled(ms.dir, -len);
			GL11.glColor4f(c[0], c[1], c[2], 0.9f);
			quad(a, camR, camU, Math.max(6f, ms.spec.missileRadius * 1.2f));
			GL11.glColor4f(1f, 0.95f, 0.85f, 0.7f);
			quad(a, camR, camU, Math.max(3f, ms.spec.missileRadius * 0.45f));
		}
		GL11.glEnd();
	}

	/** Beams: camera-facing ribbons from the mount to whatever they hit, glow at both ends. */
	private static final int SHIELD_RINGS = 14, SHIELD_SEGS = 40;

	/**
	 * Raised shields as glowing sphere caps (additive): faint in the middle, brighter toward the
	 * silhouette, a bright rim at the cap edge, and a flare where it was just hit.
	 */
	private void drawShields(Sim sim) {
		ChaseCamera cam = sim.camera;
		boolean any = false;
		Vec3 c = new Vec3(), f = new Vec3(), u = new Vec3(), v = new Vec3(), d = new Vec3(), hd = new Vec3();
		for (Ship3D s : sim.ships) {
			ShieldFlux sf = s.flux;
			if (s.destroyed || sf == null || !sf.shieldUp()) continue;
			if (!any) {
				shieldShader.use().set("u_vp", cam.viewProj).set("u_cam", cam.pos.x, cam.pos.y, cam.pos.z);
				any = true;
			}
			float r = sf.radius * s.scale;
			s.rot.rotate(sf.centre.x * s.scale, sf.centre.y * s.scale, sf.centre.z * s.scale, c).add(s.pos);
			s.rot.rotate(sf.facing, f).normalize();
			u.set(Math.abs(f.y) < 0.9f ? 0f : 1f, Math.abs(f.y) < 0.9f ? 1f : 0f, 0f);
			v.set(f).cross(u).normalize();
			u.set(v).cross(f).normalize();
			float flicker = sf.level() > 0.8f ? 0.85f + 0.15f * (float) Math.sin(sim.time * 40f + s.radius) : 1f;
			// A shield system (Fortress Shield) takes the shield to its own colours as it comes up, as vanilla's does.
			ShipSystem3D ss = s.system;
			float fe = ss != null && ss.supported() && (ss.look.shieldRing != null || ss.look.shieldInner != null) ? ss.effect : 0f;
			float[] inner = sf.innerColor, ring = sf.ringColor;
			if (fe > 0f) {
				if (ss.look.shieldInner != null) inner = mixColor(inner, ss.look.shieldInner, fe);
				if (ss.look.shieldRing != null) ring = mixColor(ring, ss.look.shieldRing, fe);
			}
			shieldShader.set("u_inner", inner).set("u_ring", ring).set("u_alpha", flicker).set("u_bright", sim.tuning.shieldBrightness);
			for (int i = 0; i < ShieldFlux.HITS; i++) {
				float k = sf.hitSize[i] * Math.max(0f, 1f - sf.hitAge[i] / 0.45f);
				s.rot.rotate(sf.hitDir[i], hd);
				shieldShader.set("u_hit" + i, hd.x, hd.y, hd.z, k);
			}
			float half = sf.halfAngle();
			GL11.glBegin(GL11.GL_QUADS);
			for (int i = 0; i < SHIELD_RINGS; i++) {
				float t0 = half * i / SHIELD_RINGS, t1 = half * (i + 1) / SHIELD_RINGS;
				for (int j = 0; j < SHIELD_SEGS; j++) {
					float p0 = (float) (2 * Math.PI * j / SHIELD_SEGS), p1 = (float) (2 * Math.PI * (j + 1) / SHIELD_SEGS);
					shieldVertex(c, r, f, u, v, t0, p0, i / (float) SHIELD_RINGS, d);
					shieldVertex(c, r, f, u, v, t0, p1, i / (float) SHIELD_RINGS, d);
					shieldVertex(c, r, f, u, v, t1, p1, (i + 1) / (float) SHIELD_RINGS, d);
					shieldVertex(c, r, f, u, v, t1, p0, (i + 1) / (float) SHIELD_RINGS, d);
				}
			}
			GL11.glEnd();
		}
	}

	private static float[] mixColor(float[] a, float[] b, float k) {
		float[] out = a.clone();
		for (int i = 0; i < Math.min(3, out.length); i++) out[i] = a[i] + (b[i] - a[i]) * k;
		return out;
	}

	private static void shieldVertex(Vec3 c, float r, Vec3 f, Vec3 u, Vec3 v, float theta, float phi, float edge, Vec3 d) {
		float st = (float) Math.sin(theta), ct = (float) Math.cos(theta);
		float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
		d.set(f.x * ct + (u.x * cp + v.x * sp) * st, f.y * ct + (u.y * cp + v.y * sp) * st, f.z * ct + (u.z * cp + v.z * sp) * st);
		GL11.glNormal3f(d.x, d.y, d.z);
		GL11.glTexCoord2f(edge, 0f);
		GL11.glVertex3f(c.x + d.x * r, c.y + d.y * r, c.z + d.z * r);
	}

	private void drawBeams(Sim sim) {
		ChaseCamera cam = sim.camera;
		Vec3 o = new Vec3();
		Vec3 camR = cam.rot.right(new Vec3()), camU = cam.rot.up(new Vec3());
		java.util.List<Combat.PayloadBeam> payloads = sim.combat.payloads;
		fxShader.set("u_mode", 6);
		GL11.glBegin(GL11.GL_QUADS);
		for (Ship3D s : sim.ships) {
			for (WeaponMount m : s.weapons) {
				if (m.beamLevel <= 0f) continue;
				WeaponSpec3D w = m.spec;
				beamRibbon(cam, m.worldPos(s, o), m.beamEnd, w.beamWidth * (0.35f + 0.65f * m.beamLevel), w.fringeColor, w.coreColor, m.beamLevel);
			}
			for (WeaponMount m : s.gazeBeams) { // Darkened Gaze
				if (m.beamLevel <= 0f) continue;
				WeaponSpec3D w = m.spec;
				beamRibbon(cam, m.worldPos(s, o), m.beamEnd, w.beamWidth * (0.35f + 0.65f * m.beamLevel), w.fringeColor, w.coreColor, m.beamLevel);
			}
		}
		for (Combat.PayloadBeam pb : payloads) {
			WeaponMount m = pb.mount;
			if (m.beamLevel <= 0f) continue;
			beamRibbon(cam, pb.origin, m.beamEnd, m.spec.beamWidth * (0.35f + 0.65f * m.beamLevel), m.spec.fringeColor, m.spec.coreColor, m.beamLevel);
		}
		for (Missile3D ms : sim.combat.missiles) {
			if (ms.laserLevel <= 0f || ms.spec.dem == null) continue;
			float[] c = ms.spec.dem.laserColor;
			float flick = 0.75f + 0.25f * (float) Math.sin(sim.time * 30f + ms.age * 7f);
			beamRibbon(cam, ms.pos, ms.laserEnd, 3f, c, c, ms.laserLevel * flick);
		}
		GL11.glEnd();
		fxShader.set("u_mode", 2);
		GL11.glBegin(GL11.GL_QUADS);
		for (Ship3D s : sim.ships) {
			for (WeaponMount m : s.weapons) {
				if (m.beamLevel <= 0f) continue;
				beamGlows(m.worldPos(s, o), m, camR, camU);
			}
		}
		for (Combat.PayloadBeam pb : payloads) {
			if (pb.mount.beamLevel > 0f) beamGlows(pb.origin, pb.mount, camR, camU);
		}
		GL11.glEnd();
	}

	/** One camera-facing beam ribbon from o to end (inside a QUADS batch, fx mode 6). */
	private static void beamRibbon(ChaseCamera cam, Vec3 o, Vec3 end, float width, float[] fringe, float[] core, float alpha) {
		float dx = end.x - o.x, dy = end.y - o.y, dz = end.z - o.z;
		if (dx * dx + dy * dy + dz * dz < 1f) return;
		Vec3 side = new Vec3(dx, dy, dz).cross(new Vec3(cam.pos).sub(o));
		if (side.lengthSq() < 1e-6f) return;
		side.normalize().scale(width * 0.5f);
		GL11.glColor4f(fringe[0], fringe[1], fringe[2], alpha);
		GL14.glSecondaryColor3f(core[0], core[1], core[2]);
		GL11.glTexCoord2f(0, 0); GL11.glVertex3f(o.x - side.x, o.y - side.y, o.z - side.z);
		GL11.glTexCoord2f(0, 1); GL11.glVertex3f(o.x + side.x, o.y + side.y, o.z + side.z);
		GL11.glTexCoord2f(1, 1); GL11.glVertex3f(end.x + side.x, end.y + side.y, end.z + side.z);
		GL11.glTexCoord2f(1, 0); GL11.glVertex3f(end.x - side.x, end.y - side.y, end.z - side.z);
	}

	/** Glow at a beam's muzzle, and at its far end while it's hitting something (fx mode 2). */
	private static void beamGlows(Vec3 o, WeaponMount m, Vec3 camR, Vec3 camU) {
		WeaponSpec3D w = m.spec;
		GL11.glColor4f(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2], 0.6f * m.beamLevel);
		quad(o, camR, camU, w.beamWidth * 1.2f);
		if (m.beamHitting) {
			GL11.glColor4f(w.fringeColor[0], w.fringeColor[1], w.fringeColor[2], 0.9f * m.beamLevel);
			quad(m.beamEnd, camR, camU, w.beamWidth * 2.2f);
			GL11.glColor4f(1f, 0.95f, 0.9f, 0.8f * m.beamLevel);
			quad(m.beamEnd, camR, camU, w.beamWidth * 0.8f);
		}
	}

	private final java.util.Random arcRnd = new java.util.Random();

	/** EMP arcs: jagged lightning between two points, re-rolled every frame so it crackles. */
	private void drawArcs(Sim sim) {
		ChaseCamera cam = sim.camera;
		Vec3 a = new Vec3(), b = new Vec3(), d = new Vec3(), toCam = new Vec3(), side = new Vec3(), perp1 = new Vec3(), perp2 = new Vec3();
		boolean begun = false;
		for (Effect e : sim.combat.effects) {
			if (e.kind != Effect.Kind.ARC) continue;
			if (!begun) {
				fxShader.set("u_mode", 6);
				GL11.glBegin(GL11.GL_QUADS);
				begun = true;
			}
			float alpha = 1f - e.age01();
			d.set(e.end).sub(e.pos);
			float len = d.length();
			if (len < 1f) continue;
			d.scale(1f / len);
			perp1.set(Math.abs(d.y) < 0.9f ? 0f : 1f, Math.abs(d.y) < 0.9f ? 1f : 0f, 0f).cross(d).normalize();
			perp2.set(d).cross(perp1);
			int n = Math.max(6, Math.min(16, (int) (len / 8f)));
			a.set(e.pos);
			for (int i = 1; i <= n; i++) {
				float t = i / (float) n;
				b.set(e.pos).addScaled(d, len * t);
				if (i < n) {
					float j = len * 0.3f * (float) Math.sin(Math.PI * t);
					b.addScaled(perp1, (arcRnd.nextFloat() - 0.5f) * j).addScaled(perp2, (arcRnd.nextFloat() - 0.5f) * j);
				}
				toCam.set(cam.pos).sub(a);
				side.set(b).sub(a).cross(toCam);
				if (side.lengthSq() > 1e-6f) {
					side.normalize().scale(e.size);
					GL11.glColor4f(e.r, e.g, e.b, alpha);
					GL14.glSecondaryColor3f(0.9f, 0.95f, 1f);
					GL11.glTexCoord2f(0.3f, 0); GL11.glVertex3f(a.x - side.x, a.y - side.y, a.z - side.z);
					GL11.glTexCoord2f(0.3f, 1); GL11.glVertex3f(a.x + side.x, a.y + side.y, a.z + side.z);
					GL11.glTexCoord2f(0.3f, 1); GL11.glVertex3f(b.x + side.x, b.y + side.y, b.z + side.z);
					GL11.glTexCoord2f(0.3f, 0); GL11.glVertex3f(b.x - side.x, b.y - side.y, b.z - side.z);
				}
				a.set(b);
			}
		}
		if (begun) GL11.glEnd();
	}

	/** Muzzle flashes, impact sparks and explosions as glowing billboards. */
	private void drawEffects(Sim sim) {
		ChaseCamera cam = sim.camera;
		Vec3 camR = cam.rot.right(new Vec3()), camU = cam.rot.up(new Vec3());
		fxShader.set("u_mode", 2);
		GL11.glBegin(GL11.GL_QUADS);
		boolean rings = false, commanding = cam.tacticalBlend() > 0.5f;
		for (Effect e : sim.combat.effects) {
			if (!e.visible() || e.wreck && commanding) continue;
			rings |= e.kind == Effect.Kind.RING;
			float age = e.age01();
			float size, alpha;
			switch (e.kind) {
				case FLASH: size = e.size * (0.6f + 0.4f * age); alpha = (float) Math.pow(1f - age, 1.5); break;
				case SPARK: size = e.size * (1f + age); alpha = (1f - age) * (1f - age); break;
				case EXPLOSION: size = e.size * (0.3f + (float) Math.sqrt(age)); alpha = (float) Math.pow(1f - age, 1.2); break;
				case NEBULA: // vanilla's nebula particle: fades in, holds, fades out, growing all the while
					size = e.size * (1f + (e.grow - 1f) * age);
					alpha = age < 0.1f ? age / 0.1f : age < 0.5f ? 1f : 1f - (age - 0.5f) / 0.5f;
					break;
				default: continue;
			}
			GL11.glColor4f(e.r, e.g, e.b, alpha);
			quad(e.pos, camR, camU, size);
			if (e.kind != Effect.Kind.FLASH && e.kind != Effect.Kind.NEBULA) {
				GL11.glColor4f(1f, 0.95f, 0.85f, alpha * 0.8f);
				quad(e.pos, camR, camU, size * 0.35f);
			}
		}
		GL11.glEnd();
		if (!rings) return;
		// Shockwaves: a thin shell racing out fast, then slowing as it fades.
		fxShader.set("u_mode", 8);
		GL11.glBegin(GL11.GL_QUADS);
		for (Effect e : sim.combat.effects) {
			if (e.kind != Effect.Kind.RING || !e.visible()) continue;
			float age = e.age01();
			GL11.glColor4f(e.r, e.g, e.b, (float) Math.pow(1f - age, 1.6));
			quad(e.pos, camR, camU, e.size * (0.15f + 0.85f * (1f - (1f - age) * (1f - age))));
		}
		GL11.glEnd();
		fxShader.set("u_mode", 2);
	}

	/**
	 * Rifts (Omega / Dweller) and Reality Disruptor orbs: a coloured halo, then a dark core taken away
	 * from whatever is behind it (reverse-subtract blending), so they read as tears in space.
	 */
	private void drawRifts(Sim sim) {
		java.util.List<Missile3D> orbs = sim.combat.exotic.orbs;
		boolean any = !orbs.isEmpty();
		for (int i = 0; !any && i < sim.combat.effects.size(); i++) any = sim.combat.effects.get(i).kind == Effect.Kind.RIFT;
		if (!any) return;
		ChaseCamera cam = sim.camera;
		Vec3 camR = cam.rot.right(new Vec3()), camU = cam.rot.up(new Vec3());
		fxShader.set("u_mode", 2);
		for (int pass = 0; pass < 2; pass++) {
			if (pass == 1) GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
			GL11.glBegin(GL11.GL_QUADS);
			for (Effect e : sim.combat.effects) {
				if (e.kind != Effect.Kind.RIFT) continue;
				float age = e.age01();
				float alpha = Math.min(1f, age * 10f) * (float) Math.pow(1f - age, 0.8);
				float size = e.size * (0.75f + 0.35f * (float) Math.sqrt(age));
				if (pass == 0) {
					GL11.glColor4f(e.r, e.g, e.b, alpha * 0.8f);
					quad(e.pos, camR, camU, size * 1.5f);
				} else {
					GL11.glColor4f(0.85f, 0.85f, 0.85f, alpha);
					quad(e.pos, camR, camU, size);
				}
			}
			for (Missile3D o : orbs) {
				float f = o.flamedOut() ? Math.max(0f, 1f - o.fade * 2f) : Math.min(1f, o.age * 4f);
				float pulse = 0.85f + 0.15f * (float) Math.sin(sim.time * 9f + o.spawnPos.x);
				float r = Math.max(18f, o.spec.missileRadius) * pulse;
				if (pass == 0) {
					float[] c = o.spec.fringeColor;
					GL11.glColor4f(c[0], c[1], c[2], f);
					quad(o.pos, camR, camU, r * 2.4f);
				} else {
					GL11.glColor4f(0.8f, 0.8f, 0.8f, f);
					quad(o.pos, camR, camU, r);
				}
			}
			GL11.glEnd();
		}
		GL14.glBlendEquation(GL14.GL_FUNC_ADD);
	}

	static void quad(Vec3 c, Vec3 r, Vec3 u, float s) {
		GL11.glTexCoord2f(0, 0); GL11.glVertex3f(c.x - r.x * s - u.x * s, c.y - r.y * s - u.y * s, c.z - r.z * s - u.z * s);
		GL11.glTexCoord2f(1, 0); GL11.glVertex3f(c.x + r.x * s - u.x * s, c.y + r.y * s - u.y * s, c.z + r.z * s - u.z * s);
		GL11.glTexCoord2f(1, 1); GL11.glVertex3f(c.x + r.x * s + u.x * s, c.y + r.y * s + u.y * s, c.z + r.z * s + u.z * s);
		GL11.glTexCoord2f(0, 1); GL11.glVertex3f(c.x - r.x * s + u.x * s, c.y - r.y * s + u.y * s, c.z - r.z * s + u.z * s);
	}

	private void ensureDust(int count, float range) {
		if (dust != null && dustBuiltFor == count) return;
		Random rnd = new Random(42);
		dust = new float[count * 3];
		for (int i = 0; i < dust.length; i++) dust[i] = rnd.nextFloat();
		dustBuiltFor = count;
	}

	/** Space dust around the camera: the main cue for your own speed and drift. */
	private void drawDust(Sim sim) {
		Tuning t = sim.tuning;
		Ship3D player = sim.viewer();
		if (player == null) return;
		Vec3 c = sim.camera.pos;
		float range = Math.max(200f, t.dustRange), half = range * 0.5f;
		Vec3 v = player.vel;
		float streak = t.dustStreak;
		fxShader.set("u_mode", 0);
		GL11.glBegin(GL11.GL_LINES);
		for (int i = 0; i < dustBuiltFor; i++) {
			float rx = wrap(dust[i * 3] * range - c.x, range) - half;
			float ry = wrap(dust[i * 3 + 1] * range - c.y, range) - half;
			float rz = wrap(dust[i * 3 + 2] * range - c.z, range) - half;
			float dist = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
			float fade = 1f - dist / half;
			if (fade <= 0f) continue;
			fade *= fade * Math.min(1f, dist / 40f);
			float x = c.x + rx, y = c.y + ry, z = c.z + rz;
			float a = t.dustBrightness * fade;
			GL11.glColor4f(0.55f * a, 0.62f * a, 0.8f * a, 1f);
			GL11.glVertex3f(x, y, z);
			GL11.glColor4f(0f, 0f, 0f, 1f);
			GL11.glVertex3f(x + v.x * streak + 0.6f, y + v.y * streak, z + v.z * streak);
		}
		GL11.glEnd();
	}

	private static float wrap(float v, float range) {
		float r = v % range;
		return r < 0f ? r + range : r;
	}

	/** Reference grid on the player's plane plus altitude stalks, so depth reads in the overview. */
	/**
	 * Tactical view: a grid on the ground plane (the command view's focus height, where right clicks
	 * land), sized to the zoom, and a drop line from every ship to it with a ring where it meets it,
	 * so height reads at a glance.
	 */
	private void drawTacticalAids(Sim sim, float blend) {
		Ship3D player = sim.viewer();
		if (player == null) return;
		float extent = Math.max(6000f, sim.camera.tacticalDistance() * 1.6f);
		float step = 500f;
		while (extent / step > 16f) step *= 2f;
		float y0 = sim.command.planeY;
		Vec3 focus = sim.command.focus;
		float cx = (float) Math.floor(focus.x / step) * step, cz = (float) Math.floor(focus.z / step) * step;
		fxShader.set("u_mode", 0);
		GL11.glBegin(GL11.GL_LINES);
		for (float k = -extent; k <= extent; k += step) {
			float a = 0.10f * blend * (1f - Math.abs(k) / extent);
			GL11.glColor4f(0.3f * a, 0.6f * a, 0.8f * a, 1f);
			GL11.glVertex3f(cx + k, y0, cz - extent);
			GL11.glVertex3f(cx + k, y0, cz + extent);
			GL11.glVertex3f(cx - extent, y0, cz + k);
			GL11.glVertex3f(cx + extent, y0, cz + k);
		}
		for (Ship3D s : sim.ships) {
			if (!s.warship()) continue; // ships only: no wrecks, fighters, drones, swarms, station parts
			boolean friend = s.owner == player.owner;
			float a = 0.35f * blend;
			GL11.glColor4f((friend ? 0.3f : 0.9f) * a, (friend ? 0.9f : 0.3f) * a, (friend ? 0.6f : 0.3f) * a, 1f);
			if (Math.abs(s.pos.y - y0) > 40f) {
				GL11.glVertex3f(s.pos.x, s.pos.y, s.pos.z);
				GL11.glVertex3f(s.pos.x, y0, s.pos.z);
			}
			a = 0.5f * blend;
			GL11.glColor4f((friend ? 0.3f : 0.9f) * a, (friend ? 0.9f : 0.3f) * a, (friend ? 0.6f : 0.3f) * a, 1f);
			float r = Math.max(25f, s.radius * 0.35f);
			for (int i = 0; i < 16; i++) {
				double a0 = i * Math.PI / 8, a1 = (i + 1) * Math.PI / 8;
				GL11.glVertex3f(s.pos.x + (float) Math.cos(a0) * r, y0, s.pos.z + (float) Math.sin(a0) * r);
				GL11.glVertex3f(s.pos.x + (float) Math.cos(a1) * r, y0, s.pos.z + (float) Math.sin(a1) * r);
			}
		}
		GL11.glEnd();
	}
}
