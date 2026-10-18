package s3d.render;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import s3d.core.HullVolume;

/**
 * A hand-made hull model (Wavefront OBJ) drawn in place of the sprite relief.
 *
 * <p>Conventions, so a model lines up with the sprite and the rest of the game data:
 * <ul>
 * <li>Ship-local units: 1 = one sprite pixel = one game unit; origin at the sprite's rotation
 *     centre; +Y up, -Z forward (nose), +X right. That is Blender's default OBJ export
 *     (Forward -Z, Up Y) of a model built nose toward Blender +Y and top toward +Z.</li>
 * <li>Texture: the ship's own sprite. Faces with UVs use them (OBJ v counts from the image
 *     bottom, as Blender writes it); faces without are projected straight down, so the top view
 *     shows the sprite exactly and skins repaint the model for free.</li>
 * <li>Unless the model has its own paint ({@link #withPaint}): an image beside the model whose
 *     UVs map into it, drawn as it is (no sprite-based top/side/underside looks). Hulls that only
 *     borrow such a model ({@link #spriteOnly}) ignore its UVs and get their sprite projected down.</li>
 * <li>Material names pick the shading: "under..."/"bottom..." underside, "wall..."/"side..." side
 *     plating, "top..."/"deck..." top; anything else blends between them by the surface normal.</li>
 * <li>Optional header comment {@code # s3d sprite <w> <h>} records the sprite size the model was
 *     made for; a sprite of another size (a skin drawn differently) falls back to the relief.</li>
 * </ul>
 *
 * <p>The hit volume is rasterised from the model (highest and lowest surface over each grid
 * column), so shots hit what you see from above and below.
 */
public final class ObjHull {

	/** Shading class for faces without a forced one: the shader blends top/side/underside by the normal. */
	static final int SIDE_AUTO = 3;
	/** Shading class for a model with its own paint: the shader draws the texture as it is. */
	static final int SIDE_PAINTED = 4;

	public final String name;
	/** Hash of the file text: the cache key changes when the file does. */
	public final int hash;
	/** Sprite size from the header, or 0 when the file has none. */
	public final int spriteW, spriteH;
	/** The model's own texture (its UVs map into it), or null to paint it with the sprite. */
	public final SpriteImage paint;
	/** True when the UVs belong to a paint this copy doesn't use: project the sprite instead. */
	private final boolean ignoreUv;

	// Parsed, triangulated: per corner indices into the pools (-1 = absent).
	private final float[] v, vt, vn;
	private final int[] cornerV, cornerT, cornerN;
	/** Per triangle: forced side class, or -1 = from the normal. */
	private final byte[] triSide;
	private final int triCount;

	private ObjHull(String name, int hash, int spriteW, int spriteH, float[] v, float[] vt, float[] vn,
					int[] cv, int[] ct, int[] cn, byte[] side, int tris) {
		this(name, hash, spriteW, spriteH, v, vt, vn, cv, ct, cn, side, tris, null, false);
	}

	private ObjHull(String name, int hash, int spriteW, int spriteH, float[] v, float[] vt, float[] vn,
					int[] cv, int[] ct, int[] cn, byte[] side, int tris, SpriteImage paint, boolean ignoreUv) {
		this.name = name;
		this.hash = hash;
		this.spriteW = spriteW;
		this.spriteH = spriteH;
		this.paint = paint;
		this.ignoreUv = ignoreUv;
		this.v = v;
		this.vt = vt;
		this.vn = vn;
		this.cornerV = cv;
		this.cornerT = ct;
		this.cornerN = cn;
		this.triSide = side;
		this.triCount = tris;
	}

	/** This model painted with its own texture; {@code paintHash} goes into {@link #hash} so edits to the image reload. */
	public ObjHull withPaint(SpriteImage paint, int paintHash) {
		return new ObjHull(name, hash * 31 + paintHash, spriteW, spriteH, v, vt, vn, cornerV, cornerT, cornerN, triSide, triCount, paint, false);
	}

	/** For a hull that borrows this model with its own sprite (a skin): drops the paint and its UVs. */
	public ObjHull spriteOnly() {
		if (paint == null) return this;
		return new ObjHull(name, hash ^ 0x5bd1e995, spriteW, spriteH, v, vt, vn, cornerV, cornerT, cornerN, triSide, triCount, null, true);
	}

	public int triangles() {
		return triCount;
	}

	/** False when the sprite was drawn at another size than the model was made for. */
	public boolean fits(SpriteImage img) {
		return spriteW <= 0 || spriteH <= 0 || Math.abs(img.w - spriteW) <= 1 && Math.abs(img.h - spriteH) <= 1;
	}

	// ---- parsing ----

	public static ObjHull parse(String name, String text) {
		FloatList pv = new FloatList(), pt = new FloatList(), pn = new FloatList();
		IntList cv = new IntList(), ct = new IntList(), cn = new IntList();
		IntList sides = new IntList(); // not java.io: Starsector's script sandbox refuses the whole package
		int sw = 0, sh = 0;
		byte material = -1;
		int[] fv = new int[64], ft = new int[64], fn = new int[64];
		int len = text.length(), pos = 0;
		while (pos < len) {
			int end = text.indexOf('\n', pos);
			if (end < 0) end = len;
			String line = text.substring(pos, end).trim();
			pos = end + 1;
			if (line.isEmpty()) continue;
			String[] tok = line.split("\\s+");
			switch (tok[0]) {
				case "v" -> {
					pv.add(f(tok[1]));
					pv.add(f(tok[2]));
					pv.add(f(tok[3]));
				}
				case "vt" -> {
					pt.add(f(tok[1]));
					pt.add(tok.length > 2 ? f(tok[2]) : 0f);
				}
				case "vn" -> {
					pn.add(f(tok[1]));
					pn.add(f(tok[2]));
					pn.add(f(tok[3]));
				}
				case "usemtl" -> material = tok.length > 1 ? sideOf(tok[1]) : -1;
				case "f" -> {
					int n = Math.min(tok.length - 1, fv.length);
					for (int k = 0; k < n; k++) {
						String[] parts = tok[k + 1].split("/", -1);
						fv[k] = index(parts[0], pv.size / 3);
						ft[k] = parts.length > 1 && !parts[1].isEmpty() ? index(parts[1], pt.size / 2) : -1;
						fn[k] = parts.length > 2 && !parts[2].isEmpty() ? index(parts[2], pn.size / 3) : -1;
					}
					for (int k = 1; k < n - 1; k++) { // fan; Blender writes convex quads/ngons for hard-surface work
						for (int c : new int[] {0, k, k + 1}) {
							cv.add(fv[c]);
							ct.add(ft[c]);
							cn.add(fn[c]);
						}
						sides.add(material);
					}
				}
				case "#" -> {
					if (tok.length >= 5 && "s3d".equals(tok[1]) && "sprite".equals(tok[2])) {
						sw = Integer.parseInt(tok[3]);
						sh = Integer.parseInt(tok[4]);
					}
				}
				default -> { /* o, g, s, mtllib, l: not needed */ }
			}
		}
		byte[] side = new byte[sides.size];
		for (int i = 0; i < side.length; i++) side[i] = (byte) sides.a[i];
		return new ObjHull(name, text.hashCode(), sw, sh, pv.toArray(), pt.toArray(), pn.toArray(),
				cv.toArray(), ct.toArray(), cn.toArray(), side, side.length);
	}

	private static float f(String s) {
		return Float.parseFloat(s);
	}

	/** OBJ indices are 1-based; negative ones count back from the latest element. */
	private static int index(String s, int count) {
		int i = Integer.parseInt(s);
		return i < 0 ? count + i : i - 1;
	}

	private static byte sideOf(String material) {
		String m = material.toLowerCase(java.util.Locale.ROOT);
		if (m.startsWith("under") || m.startsWith("bottom")) return 1;
		if (m.startsWith("wall") || m.startsWith("side")) return 2;
		if (m.startsWith("top") || m.startsWith("deck")) return 0;
		return -1;
	}

	// ---- mesh + hit volume ----

	private static final Map<String, ReliefMeshBuilder.Result> CPU_CACHE = new HashMap<>();

	public static synchronized ReliefMeshBuilder.Result cached(String key, ObjHull obj, SpriteImage img, float cx, float cyTop, int cellsAcross) {
		return CPU_CACHE.computeIfAbsent(key, k -> obj.build(img, cx, cyTop, cellsAcross));
	}

	public static synchronized void clearCache() {
		CPU_CACHE.clear();
	}

	/**
	 * Interleaved vertices in the relief mesh's layout (position, normal, uv, side) plus the
	 * hit volume, on a grid as fine as the relief's ({@code cellsAcross} along the long axis).
	 */
	public ReliefMeshBuilder.Result build(SpriteImage img, float cx, float cyTop, int cellsAcross) {
		long t0 = System.nanoTime();
		Map<Long, Integer> unique = new HashMap<>();
		FloatList out = new FloatList();
		int[] idx = new int[triCount * 3];
		int o = 0, top = 0, bottom = 0, wall = 0;
		float[] fnrm = new float[3];
		for (int t = 0; t < triCount; t++) {
			int c0 = t * 3;
			faceNormal(cornerV[c0], cornerV[c0 + 1], cornerV[c0 + 2], fnrm);
			if (fnrm[0] == 0f && fnrm[1] == 0f && fnrm[2] == 0f) continue; // degenerate
			int facing = triSide[t] >= 0 ? triSide[t] : fnrm[1] > 0.3f ? 0 : fnrm[1] < -0.3f ? 1 : 2;
			if (facing == 0) top++;
			else if (facing == 1) bottom++;
			else wall++;
			int side = paint != null ? SIDE_PAINTED : triSide[t] >= 0 ? triSide[t] : SIDE_AUTO;
			for (int k = 0; k < 3; k++) {
				int c = c0 + k;
				int vi = cornerV[c], ti = ignoreUv ? -1 : cornerT[c], ni = cornerN[c];
				// Corners are shared when position, uv, normal and shading all match. Without a
				// normal each face keeps its own corners (flat shading).
				boolean share = ni >= 0 && vi < (1 << 22) && ti + 1 < (1 << 21) && ni + 1 < (1 << 19);
				long key = share ? vi | (long) (ti + 1) << 22 | (long) (ni + 1) << 43 | (long) (side & 3) << 62 : -1L;
				Integer id = share ? unique.get(key) : null;
				if (id == null) {
					id = out.size / ReliefMeshBuilder.STRIDE;
					float px = v[vi * 3], py = v[vi * 3 + 1], pz = v[vi * 3 + 2];
					out.add(px);
					out.add(py);
					out.add(pz);
					if (ni >= 0) {
						out.add(vn[ni * 3]);
						out.add(vn[ni * 3 + 1]);
						out.add(vn[ni * 3 + 2]);
					} else {
						out.add(fnrm[0]);
						out.add(fnrm[1]);
						out.add(fnrm[2]);
					}
					if (ti >= 0) {
						out.add(vt[ti * 2]);
						out.add(1f - vt[ti * 2 + 1]);
					} else {
						out.add((px + cx) / img.w);
						out.add((pz + cyTop) / img.h);
					}
					out.add(side);
					if (share) unique.put(key, id);
				}
				idx[o++] = id;
			}
		}
		ReliefMeshBuilder.Result r = new ReliefMeshBuilder.Result();
		r.verts = out.toArray();
		r.indices = o < idx.length ? Arrays.copyOf(idx, o) : idx;
		r.vertexCount = r.verts.length / ReliefMeshBuilder.STRIDE;
		r.topTris = top;
		r.bottomTris = bottom;
		r.wallTris = wall;
		r.minX = r.minY = r.minZ = Float.MAX_VALUE;
		r.maxX = r.maxY = r.maxZ = -Float.MAX_VALUE;
		for (int k = 0; k < r.vertexCount; k++) {
			int b = k * ReliefMeshBuilder.STRIDE;
			r.minX = Math.min(r.minX, r.verts[b]);
			r.maxX = Math.max(r.maxX, r.verts[b]);
			r.minY = Math.min(r.minY, r.verts[b + 1]);
			r.maxY = Math.max(r.maxY, r.verts[b + 1]);
			r.minZ = Math.min(r.minZ, r.verts[b + 2]);
			r.maxZ = Math.max(r.maxZ, r.verts[b + 2]);
		}
		r.cell = Math.max(1f, Math.max(img.w, img.h) / (float) Math.max(16, cellsAcross));
		r.volume = volume(r, r.cell);
		r.gridW = r.volume.nx;
		r.gridH = r.volume.nz;
		r.buildMs = (System.nanoTime() - t0) / 1e6;
		return r;
	}

	private void faceNormal(int a, int b, int c, float[] out) {
		float abx = v[b * 3] - v[a * 3], aby = v[b * 3 + 1] - v[a * 3 + 1], abz = v[b * 3 + 2] - v[a * 3 + 2];
		float acx = v[c * 3] - v[a * 3], acy = v[c * 3 + 1] - v[a * 3 + 1], acz = v[c * 3 + 2] - v[a * 3 + 2];
		float nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
		float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (l < 1e-9f) {
			out[0] = out[1] = out[2] = 0f;
			return;
		}
		out[0] = nx / l;
		out[1] = ny / l;
		out[2] = nz / l;
	}

	/**
	 * Solid shape for hit tests: over each grid column, from the lowest to the highest surface
	 * the model has there. Overhangs fill in (seen from the side a gap under a wing is solid),
	 * which matches how the relief volume behaves.
	 */
	private static HullVolume volume(ReliefMeshBuilder.Result r, float cell) {
		final int pad = 2;
		float ox = (float) Math.floor(r.minX / cell) * cell - pad * cell;
		float oz = (float) Math.floor(r.minZ / cell) * cell - pad * cell;
		int nx = (int) Math.ceil((r.maxX - ox) / cell) + pad + 1;
		int nz = (int) Math.ceil((r.maxZ - oz) / cell) + pad + 1;
		float[] hi = new float[nx * nz], lo = new float[nx * nz];
		Arrays.fill(hi, -Float.MAX_VALUE);
		Arrays.fill(lo, Float.MAX_VALUE);
		float[] p = r.verts;
		int[] ix = r.indices;
		final int S = ReliefMeshBuilder.STRIDE;
		final float eps = 1e-4f;
		for (int t = 0; t < ix.length; t += 3) {
			int a = ix[t] * S, b = ix[t + 1] * S, c = ix[t + 2] * S;
			float ax = p[a], ay = p[a + 1], az = p[a + 2];
			float bx = p[b], by = p[b + 1], bz = p[b + 2];
			float cxv = p[c], cy = p[c + 1], cz = p[c + 2];
			float den = (bz - cz) * (ax - cxv) + (cxv - bx) * (az - cz);
			if (Math.abs(den) < 1e-9f) continue; // vertical face: covers no column
			int gx0 = Math.max(0, (int) Math.ceil((Math.min(ax, Math.min(bx, cxv)) - ox) / cell));
			int gx1 = Math.min(nx - 1, (int) Math.floor((Math.max(ax, Math.max(bx, cxv)) - ox) / cell));
			int gz0 = Math.max(0, (int) Math.ceil((Math.min(az, Math.min(bz, cz)) - oz) / cell));
			int gz1 = Math.min(nz - 1, (int) Math.floor((Math.max(az, Math.max(bz, cz)) - oz) / cell));
			for (int gz = gz0; gz <= gz1; gz++) {
				float z = oz + gz * cell;
				for (int gx = gx0; gx <= gx1; gx++) {
					float x = ox + gx * cell;
					float w0 = ((bz - cz) * (x - cxv) + (cxv - bx) * (z - cz)) / den;
					float w1 = ((cz - az) * (x - cxv) + (ax - cxv) * (z - cz)) / den;
					float w2 = 1f - w0 - w1;
					if (w0 < -eps || w1 < -eps || w2 < -eps) continue;
					float y = w0 * ay + w1 * by + w2 * cy;
					int i = gz * nx + gx;
					if (y > hi[i]) hi[i] = y;
					if (y < lo[i]) lo[i] = y;
				}
			}
		}
		boolean[] in = new boolean[nx * nz];
		for (int i = 0; i < in.length; i++) in[i] = hi[i] > lo[i];
		double[] dOut = ReliefMeshBuilder.edt(in, nx, nz, false);
		double[] dIn = ReliefMeshBuilder.edt(in, nx, nz, true);
		float[] sdf = new float[nx * nz];
		for (int i = 0; i < sdf.length; i++) {
			sdf[i] = in[i] ? ((float) Math.sqrt(dOut[i]) - 0.5f) * cell : -((float) Math.sqrt(dIn[i]) - 0.5f) * cell;
		}
		// Columns just outside take their neighbours' heights, so bilinear samples at the edge
		// don't sag toward zero.
		for (int i = 0; i < in.length; i++) {
			if (!in[i]) {
				hi[i] = 0f;
				lo[i] = 0f;
			}
		}
		for (int pass = 0; pass < 2; pass++) {
			float[] h2 = hi.clone(), l2 = lo.clone();
			boolean[] in2 = in.clone();
			for (int gz = 0; gz < nz; gz++) {
				for (int gx = 0; gx < nx; gx++) {
					int i = gz * nx + gx;
					if (in[i]) continue;
					float sh = 0f, sl = 0f;
					int n = 0;
					for (int dz = -1; dz <= 1; dz++) {
						for (int dx = -1; dx <= 1; dx++) {
							int x = gx + dx, z = gz + dz;
							if (x < 0 || z < 0 || x >= nx || z >= nz || !in[z * nx + x]) continue;
							sh += hi[z * nx + x];
							sl += lo[z * nx + x];
							n++;
						}
					}
					if (n > 0) {
						h2[i] = sh / n;
						l2[i] = sl / n;
						in2[i] = true;
					}
				}
			}
			hi = h2;
			lo = l2;
			in = in2;
		}
		return new HullVolume(ox, oz, cell, nx, nz, sdf, hi, lo);
	}

	// ---- small growable arrays ----

	private static final class FloatList {
		float[] a = new float[1024];
		int size;

		void add(float f) {
			if (size == a.length) a = Arrays.copyOf(a, size * 2);
			a[size++] = f;
		}

		float[] toArray() {
			return Arrays.copyOf(a, size);
		}
	}

	private static final class IntList {
		int[] a = new int[1024];
		int size;

		void add(int i) {
			if (size == a.length) a = Arrays.copyOf(a, size * 2);
			a[size++] = i;
		}

		int[] toArray() {
			return Arrays.copyOf(a, size);
		}
	}
}
