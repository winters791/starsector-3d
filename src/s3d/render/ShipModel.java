package s3d.render;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;

import s3d.core.Tuning;

/**
 * GPU-side hull: relief mesh (or a hand-made {@link ObjHull} model) + sprite texture. Built once per
 * sprite and cached for the session.
 */
public final class ShipModel {
	private static final Logger log = Global.getLogger(ShipModel.class);
	private static final Map<String, ShipModel> CACHE = new HashMap<>();

	/** What the host hands the renderer; the GL work happens lazily on the render pass. */
	public static final class Source {
		public final String spritePath;
		public final SpriteImage image;
		/** Rotation centre: pixels from the left edge, and from the top edge. */
		public final float cx, cyTop;
		/** Weapon sprites get a flatter, crisper profile than hulls. */
		public final boolean weapon;
		/** Hand-made model drawn instead of the relief, or null. */
		public final ObjHull obj;

		public Source(String spritePath, SpriteImage image, float cx, float cyTop) {
			this(spritePath, image, cx, cyTop, false, null);
		}

		public Source(String spritePath, SpriteImage image, float cx, float cyTop, boolean weapon) {
			this(spritePath, image, cx, cyTop, weapon, null);
		}

		public Source(String spritePath, SpriteImage image, float cx, float cyTop, ObjHull obj) {
			this(spritePath, image, cx, cyTop, false, obj);
		}

		private Source(String spritePath, SpriteImage image, float cx, float cyTop, boolean weapon, ObjHull obj) {
			this.spritePath = spritePath;
			this.image = image;
			this.cx = cx;
			this.cyTop = cyTop;
			this.weapon = weapon;
			this.obj = obj;
		}

		ReliefMeshBuilder.Params params(Tuning t) {
			ReliefMeshBuilder.Params p = ReliefMeshBuilder.Params.from(t);
			if (weapon) {
				// Small machinery: one raised housing on a bevelled base plate, thin underside.
				p.topHeight *= 0.55f;
				p.bottomHeight *= 0.25f;
				p.roundRadiusFrac = 0.16f;
				p.keel = 0f;
				p.cellsAcross = 48;
				p.tiers = 1;
				p.baseHeight = 0.3f;
				p.tierHeight = 0.16f;
				p.tierInsetFrac = 0.5f;
				p.underHeight = 0.1f;
				p.keelHeight = 0f;
				p.bevelFrac = 0.3f;
				p.wallFrac = 0.04f;
			}
			return p;
		}
	}

	public final String key;
	public final Mesh mesh;
	public final int texture;
	/** Surface-detail normal map generated from the sprite's shading. */
	public final int normalTexture;
	public final float[] tint;
	public final ReliefMeshBuilder.Result info;

	private ShipModel(String key, Mesh mesh, int texture, int normalTexture, float[] tint, ReliefMeshBuilder.Result info) {
		this.key = key;
		this.mesh = mesh;
		this.texture = texture;
		this.normalTexture = normalTexture;
		this.tint = tint;
		this.info = info;
	}

	/** Cache key shared by the CPU mesh/volume and the GPU model. */
	public static String key(Source src, Tuning t) {
		String at = src.spritePath + "@" + src.cx + "," + src.cyTop;
		if (src.obj != null) return "obj:" + src.obj.name + "#" + Integer.toHexString(src.obj.hash) + "|" + at + "|c" + t.meshCellsAcross;
		return at + (src.weapon ? "|w" : "") + "|" + t.meshSignature();
	}

	/** CPU-only build (mesh + hit volume); safe to call outside rendering. */
	public static ReliefMeshBuilder.Result geometry(Source src, Tuning t) {
		return build(key(src, t), src, t);
	}

	private static ReliefMeshBuilder.Result build(String key, Source src, Tuning t) {
		if (src.obj != null) return ObjHull.cached(key, src.obj, src.image, src.cx, src.cyTop, t.meshCellsAcross);
		return ReliefMeshBuilder.cached(key, src.image, src.cx, src.cyTop, src.params(t));
	}

	public static ShipModel get(Source src, Tuning t) {
		String key = key(src, t);
		ShipModel m = CACHE.get(key);
		if (m != null) return m;
		ReliefMeshBuilder.Result r = build(key, src, t);
		Mesh mesh = Mesh.upload(r.verts, r.indices, ReliefMeshBuilder.STRIDE,
				new int[][] {{0, 3, 0}, {1, 3, 3}, {2, 2, 6}, {3, 1, 8}});
		int tex = TextureUtil.upload(src.image.dilatedOpaque(12), src.image.w, src.image.h, true, t.anisotropy);
		int nrm = TextureUtil.upload(src.image.normalMap(4f), src.image.w, src.image.h, true, t.anisotropy);
		int[] rgb = src.image.averageTintRgb();
		m = new ShipModel(key, mesh, tex, nrm, new float[] {rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f}, r);
		CACHE.put(key, m);
		log.info("S3D: built " + (src.obj != null ? "model " + src.obj.name + " textured with " : "relief mesh for ") + src.spritePath + ": " + r.summary());
		return m;
	}

	/** Drops GPU resources (e.g. after mesh tuning changed or the GL context was lost). */
	public static void clearCache() {
		for (ShipModel m : CACHE.values()) {
			try {
				m.mesh.delete();
				org.lwjgl.opengl.GL11.glDeleteTextures(m.texture);
				org.lwjgl.opengl.GL11.glDeleteTextures(m.normalTexture);
			} catch (Throwable ignored) {
				// context may already be gone
			}
		}
		CACHE.clear();
		ReliefMeshBuilder.clearCache();
		ObjHull.clearCache();
	}
}
