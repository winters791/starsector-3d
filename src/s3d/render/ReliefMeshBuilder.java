package s3d.render;

import java.util.Arrays;

import s3d.core.HullVolume;
import s3d.core.Tuning;

/**
 * Turns a flat ship sprite into a closed 3D hull so it survives being seen from the side or below.
 *
 * <ol>
 * <li>Silhouette = sprite alpha above a threshold; a signed distance field (exact EDT, lightly
 *     blurred) gives each point its distance to the hull edge.</li>
 * <li>Marching squares on a grid (about {@code cellsAcross} cells along the long axis) turns the
 *     inside of the silhouette into triangles with a smooth outline instead of pixel stairs.</li>
 * <li>Top surface height rises from the edge with a quarter-circle profile and plateaus at the
 *     rounding radius (thin booms stay thin, wide hulls get a deck), plus a little relief from
 *     sprite brightness. The underside uses a flatter profile; a thin wall joins the two.</li>
 * </ol>
 *
 * Output is ship-local: forward -Z, right +X, up +Y, 1 unit = 1 sprite pixel = 1 game unit.
 * Vertex layout (9 floats): position(3) normal(3) uv(2) side(1: 0 top, 1 bottom, 2 wall).
 */
public final class ReliefMeshBuilder {

	public static final int STRIDE = 9;
	private static final double INF = 1e10;

	public static final class Params {
		public int cellsAcross = 110;
		public float alphaThreshold = 0.45f;
		public float roundRadiusFrac = 0.25f;
		public float topHeight = 0.75f;
		public float bottomHeight = 0.5f;
		public float keel = 0.5f;
		public float detailAmp = 0.06f;
		public float edgeThickness = 0.012f;
		public float sdfBlur = 0.8f;

		// Hard-surface profile (default): bevelled plates stepping up in tiers that follow the outline.
		public boolean hardSurface = true;
		/** Bevel width as a fraction of the rounding radius R. */
		public float bevelFrac = 0.22f;
		/** First deck height (x R). */
		public float baseHeight = 0.32f;
		/** Raised tiers inset from the outline, each tierInsetFrac x R further in and tierHeight x R taller. */
		public int tiers = 2;
		public float tierInsetFrac = 0.45f;
		public float tierHeight = 0.2f;
		/** How much the sprite's brighter/darker regions raise/lower the tiers (0 = uniform). */
		public float tierLumInfluence = 0.5f;
		/** Quantise the brightness influence into this many plate levels (0 = smooth). */
		public int tierLumSteps = 3;
		public float underHeight = 0.28f;
		public float keelHeight = 0.18f;
		/** Vertical side-plate height as a fraction of the sprite's larger dimension. */
		public float wallFrac = 0.03f;

		public static Params from(Tuning t) {
			Params p = new Params();
			p.cellsAcross = Math.max(16, t.meshCellsAcross);
			p.alphaThreshold = t.meshAlphaThreshold;
			p.roundRadiusFrac = t.meshRoundRadiusFrac;
			p.topHeight = t.meshTopHeight;
			p.bottomHeight = t.meshBottomHeight;
			p.keel = t.meshKeel;
			p.detailAmp = t.meshDetailAmp;
			p.edgeThickness = t.meshEdgeThickness;
			p.sdfBlur = t.meshSdfBlur;
			p.hardSurface = !"round".equalsIgnoreCase(t.meshProfile);
			p.bevelFrac = t.meshBevelFrac;
			p.baseHeight = t.meshBaseHeight;
			p.tiers = t.meshTiers;
			p.tierInsetFrac = t.meshTierInsetFrac;
			p.tierHeight = t.meshTierHeight;
			p.tierLumInfluence = t.meshTierLumInfluence;
			p.tierLumSteps = t.meshTierLumSteps;
			p.underHeight = t.meshUnderHeight;
			p.keelHeight = t.meshKeelHeight;
			p.wallFrac = t.meshWallFrac;
			return p;
		}
	}

	public static final class Result {
		public float[] verts;
		public int[] indices;
		public int vertexCount, topTris, bottomTris, wallTris, boundarySegments;
		public float minX, maxX, minY, maxY, minZ, maxZ;
		public float cell;
		public int gridW, gridH;
		public double buildMs;
		/** Solid shape matching this mesh, for projectile hit tests. */
		public HullVolume volume;

		public int triCount() {
			return indices.length / 3;
		}

		public String summary() {
			return String.format("%d verts, %d tris (top %d, bottom %d, wall %d), grid %dx%d @ %.2fpx, "
							+ "bounds x[%.1f,%.1f] y[%.1f,%.1f] z[%.1f,%.1f], %.1f ms",
					vertexCount, triCount(), topTris, bottomTris, wallTris, gridW, gridH, cell,
					minX, maxX, minY, maxY, minZ, maxZ, buildMs);
		}
	}

	private ReliefMeshBuilder() {}

	private static final java.util.Map<String, Result> CPU_CACHE = new java.util.HashMap<>();

	/** Builds once per key (sprite + centre + mesh tuning); host (hit volumes) and renderer share it. */
	public static synchronized Result cached(String key, SpriteImage img, float cx, float cyTop, Params p) {
		Result r = CPU_CACHE.get(key);
		if (r == null) {
			r = build(img, cx, cyTop, p);
			CPU_CACHE.put(key, r);
		}
		return r;
	}

	public static synchronized void clearCache() {
		CPU_CACHE.clear();
	}

	/** Everything the height function needs about one sprite. */
	private static final class HeightCtx {
		Params p;
		float cx, rRound, halfWall, lumMean, lumStd;
		int w, W, H, pad;
		float[] lum, lumBlur, lumSmooth;
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	/** Top and underside height (local y) at image point (px, py) with distance d inside the edge. */
	private static void heights(float px, float py, float d, HeightCtx c, float[] out) {
		Params p = c.p;
		float R = c.rRound;
		float fx = px + c.pad - 0.5f, fy = py + c.pad - 0.5f;
		if (!p.hardSurface) {
			// Legacy "inflated" profile: quarter circle from the edge, plateau at R.
			float t = Math.min(1f, d / R);
			float prof = (float) Math.sqrt(t * (2f - t));
			float detail = c.lumBlur != null && t > 0f
					? (bilinear(c.lum, c.W, c.H, fx, fy) - bilinear(c.lumBlur, c.W, c.H, fx, fy)) * p.detailAmp * R * t : 0f;
			out[0] = p.topHeight * R * prof + detail + c.halfWall;
			float side = Math.min(1f, Math.abs(px - c.cx) / Math.max(1f, c.w * 0.5f));
			out[1] = -p.bottomHeight * R * prof * (1f + p.keel * (0.5f - side)) - c.halfWall;
			return;
		}
		// Hard surface: a bevelled deck, then raised tiers inset from the outline (their height follows
		// the sprite's large-scale brightness), flat plateaus in between. Thin parts stay thin plates.
		float bevel = Math.max(0.75f, p.bevelFrac * R);
		float deck = clamp01(d / bevel);
		float lumMod = 1f;
		if (c.lumSmooth != null) {
			float ls = bilinear(c.lumSmooth, c.W, c.H, fx, fy);
			float z = (ls - c.lumMean) / Math.max(0.04f, c.lumStd);
			if (p.tierLumSteps > 0) {
				// Discrete plate levels: brighter regions become distinct raised plates, not soft swells.
				float q = Math.max(1, p.tierLumSteps - 1);
				z = Math.round(Math.max(-1f, Math.min(1f, z * 0.6f)) * q * 0.5f) / (q * 0.5f);
			}
			lumMod = Math.max(0.35f, Math.min(1.65f, 1f + p.tierLumInfluence * z));
		}
		float top = p.baseHeight * R * deck;
		for (int k = 1; k <= p.tiers; k++) {
			top += p.tierHeight * R * lumMod * clamp01((d - k * p.tierInsetFrac * R) / bevel);
		}
		if (c.lumBlur != null) {
			top += (bilinear(c.lum, c.W, c.H, fx, fy) - bilinear(c.lumBlur, c.W, c.H, fx, fy)) * p.detailAmp * R * deck;
		}
		out[0] = top + c.halfWall;
		float under = p.underHeight * R * clamp01(d / (bevel * 1.4f))
				+ p.keelHeight * R * clamp01((d - p.tierInsetFrac * R * 1.2f) / bevel);
		out[1] = -under - c.halfWall;
	}

	/**
	 * @param cx    rotation centre, pixels from the sprite's left edge
	 * @param cyTop rotation centre, pixels from the sprite's top edge
	 */
	public static Result build(SpriteImage img, float cx, float cyTop, Params p) {
		long t0 = System.nanoTime();
		final int w = img.w, h = img.h, pad = 3;
		final int W = w + 2 * pad, H = h + 2 * pad;

		// ---- signed distance field (positive inside), in padded pixel space ----
		boolean[] in = new boolean[W * H];
		float[] lum = new float[W * H];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = (y + pad) * W + (x + pad);
				in[i] = img.alpha(x, y) >= p.alphaThreshold;
				if (in[i]) lum[i] = img.luminance(x, y);
			}
		}
		double[] dToOutside = edt(in, W, H, false);
		double[] dToInside = edt(in, W, H, true);
		float[] sdf = new float[W * H];
		for (int i = 0; i < W * H; i++) {
			sdf[i] = in[i] ? (float) Math.sqrt(dToOutside[i]) - 0.5f : -((float) Math.sqrt(dToInside[i]) - 0.5f);
		}
		if (p.sdfBlur > 0.05f) sdf = gaussian(sdf, W, H, p.sdfBlur);
		float[] lumBlur = p.detailAmp > 0f ? gaussian(lum, W, H, 2.0f) : null;

		// ---- grid ----
		float cell = Math.max(1f, Math.max(w, h) / (float) p.cellsAcross);
		int nx = (int) Math.ceil(W / cell), ny = (int) Math.ceil(H / cell);
		int gw = nx + 1;
		float[] gv = new float[gw * (ny + 1)];
		for (int j = 0; j <= ny; j++) {
			for (int i = 0; i <= nx; i++) {
				gv[j * gw + i] = bilinear(sdf, W, H, i * cell - 0.5f, j * cell - 0.5f);
			}
		}

		// ---- marching squares: 2D vertices (image coords), triangles and outline segments ----
		Geo g = new Geo();
		int[] cornerId = new int[gw * (ny + 1)];
		int[] hEdge = new int[nx * (ny + 1)];
		int[] vEdge = new int[gw * ny];
		Arrays.fill(cornerId, -1);
		Arrays.fill(hEdge, -1);
		Arrays.fill(vEdge, -1);

		int[] ci = new int[4], cj = new int[4];
		float[] cv = new float[4];
		int[] poly = new int[8];
		boolean[] polyEdge = new boolean[8];
		for (int j = 0; j < ny; j++) {
			for (int i = 0; i < nx; i++) {
				ci[0] = i; cj[0] = j;
				ci[1] = i + 1; cj[1] = j;
				ci[2] = i + 1; cj[2] = j + 1;
				ci[3] = i; cj[3] = j + 1;
				int mask = 0;
				for (int k = 0; k < 4; k++) {
					cv[k] = gv[cj[k] * gw + ci[k]];
					if (cv[k] > 0f) mask |= 1 << k;
				}
				if (mask == 0) continue;

				int n = 0;
				for (int k = 0; k < 4; k++) {
					int k2 = (k + 1) & 3;
					boolean inA = cv[k] > 0f, inB = cv[k2] > 0f;
					if (inA) {
						int idx = cj[k] * gw + ci[k];
						if (cornerId[idx] < 0) cornerId[idx] = g.add(ci[k] * cell - pad, cj[k] * cell - pad, cv[k], false);
						poly[n] = cornerId[idx];
						polyEdge[n++] = false;
					}
					if (inA != inB) {
						int slot;
						int[] edgeArr;
						if (k == 0) { edgeArr = hEdge; slot = j * nx + i; }
						else if (k == 1) { edgeArr = vEdge; slot = j * gw + (i + 1); }
						else if (k == 2) { edgeArr = hEdge; slot = (j + 1) * nx + i; }
						else { edgeArr = vEdge; slot = j * gw + i; }
						if (edgeArr[slot] < 0) {
							float tt = cv[k] / (cv[k] - cv[k2]);
							float ax = ci[k] * cell - pad, ay = cj[k] * cell - pad;
							float bx = ci[k2] * cell - pad, by = cj[k2] * cell - pad;
							edgeArr[slot] = g.add(ax + (bx - ax) * tt, ay + (by - ay) * tt, 0f, true);
						}
						poly[n] = edgeArr[slot];
						polyEdge[n++] = true;
					}
				}

				boolean saddle = mask == 5 || mask == 10;
				float centre = (cv[0] + cv[1] + cv[2] + cv[3]) * 0.25f;
				if (saddle && centre <= 0f) {
					// Two separate corner triangles instead of one bridged hexagon. The walk gives
					// mask 10 as [e0 c1 e1 | e2 c3 e3] but mask 5 as [c0 e0 e1 c2 e2 e3], so rotate
					// mask 5 by one to [e3 c0 e0 | e1 c2 e2].
					if (mask == 5) rotateRight(poly, polyEdge, 6);
					emitPolygon(g, poly, polyEdge, 0, 3);
					emitPolygon(g, poly, polyEdge, 3, 3);
				} else {
					emitPolygon(g, poly, polyEdge, 0, n);
				}
			}
		}

		// ---- heights ----
		float rRound = Math.max(2f, p.roundRadiusFrac * Math.min(w, h));
		float halfWall = (p.hardSurface ? p.wallFrac : p.edgeThickness) * Math.max(w, h) * 0.5f;
		HeightCtx hc = new HeightCtx();
		hc.p = p;
		hc.cx = cx;
		hc.w = w;
		hc.rRound = rRound;
		hc.halfWall = halfWall;
		hc.lum = lum;
		hc.lumBlur = lumBlur;
		hc.W = W;
		hc.H = H;
		hc.pad = pad;
		if (p.hardSurface && p.tiers > 0 && p.tierLumInfluence > 0f) {
			hc.lumSmooth = gaussian(lum, W, H, Math.max(2f, rRound * 0.3f));
			double sum = 0, sum2 = 0;
			int n = 0;
			for (int i = 0; i < W * H; i++) {
				if (!in[i]) continue;
				sum += hc.lumSmooth[i];
				sum2 += hc.lumSmooth[i] * hc.lumSmooth[i];
				n++;
			}
			if (n > 0) {
				hc.lumMean = (float) (sum / n);
				hc.lumStd = (float) Math.sqrt(Math.max(0, sum2 / n - hc.lumMean * hc.lumMean));
			}
		}
		int n2 = g.count;
		float[] hTop = new float[n2], hBot = new float[n2];
		float[] th = new float[2];
		for (int k = 0; k < n2; k++) {
			float d = g.boundary[k] ? 0f : Math.max(0f, g.s[k]);
			heights(g.x[k], g.y[k], d, hc, th);
			hTop[k] = th[0];
			hBot[k] = th[1];
		}

		// ---- solid volume for hit tests, on the marching-squares grid ----
		int gh = ny + 1;
		float[] vTop = new float[gw * gh], vBot = new float[gw * gh];
		for (int j = 0; j < gh; j++) {
			for (int i = 0; i < gw; i++) {
				float d = Math.max(0f, gv[j * gw + i]);
				heights(i * cell - pad, j * cell - pad, d, hc, th);
				vTop[j * gw + i] = th[0];
				vBot[j * gw + i] = th[1];
			}
		}
		HullVolume volume = new HullVolume(-pad - cx, -pad - cyTop, cell, gw, gh, gv, vTop, vBot);

		// ---- assemble 3D vertices ----
		boolean wall = halfWall > 0.01f;
		int[] wallTop = new int[n2];
		Arrays.fill(wallTop, -1);
		int wallVerts = 0;
		if (wall) {
			for (int s = 0; s < g.segCount; s++) {
				for (int e = 0; e < 2; e++) {
					int v = g.seg[s * 2 + e];
					if (wallTop[v] < 0) {
						wallTop[v] = 2 * n2 + wallVerts;
						wallVerts += 2; // top + bottom copy
					}
				}
			}
		}
		int vCount = 2 * n2 + wallVerts;
		float[] pos = new float[vCount * 3];
		float[] nrm = new float[vCount * 3];
		float[] uv = new float[vCount * 2];
		float[] side = new float[vCount];
		for (int k = 0; k < n2; k++) {
			float lx = g.x[k] - cx, lz = g.y[k] - cyTop;
			float u = g.x[k] / w, v = g.y[k] / h;
			put(pos, k, lx, hTop[k], lz);
			put(pos, n2 + k, lx, hBot[k], lz);
			uv[k * 2] = uv[(n2 + k) * 2] = u;
			uv[k * 2 + 1] = uv[(n2 + k) * 2 + 1] = v;
			side[k] = 0f;
			side[n2 + k] = 1f;
			if (wall && wallTop[k] >= 0) {
				int a = wallTop[k], b = a + 1;
				put(pos, a, lx, halfWall, lz);
				put(pos, b, lx, -halfWall, lz);
				uv[a * 2] = uv[b * 2] = u;
				uv[a * 2 + 1] = uv[b * 2 + 1] = v;
				side[a] = side[b] = 2f;
			}
		}

		// ---- triangles with consistent outward winding ----
		int triTop = g.triCount, wallQuads = wall ? g.segCount : 0;
		int[] idx = new int[(triTop * 2 + wallQuads * 2) * 3];
		int o = 0, emitted = 0;
		for (int t = 0; t < triTop; t++) {
			int a = g.tri[t * 3], b = g.tri[t * 3 + 1], c = g.tri[t * 3 + 2];
			float area2 = faceNormalY(pos, a, b, c);
			if (Math.abs(area2) < 1e-6f) continue; // zero-area sliver (edge crossing landed on a corner)
			emitted++;
			boolean ccwFromAbove = area2 > 0f;
			// top: normal must point +Y
			idx[o++] = a;
			idx[o++] = ccwFromAbove ? b : c;
			idx[o++] = ccwFromAbove ? c : b;
			// bottom: normal must point -Y
			idx[o++] = n2 + a;
			idx[o++] = n2 + (ccwFromAbove ? c : b);
			idx[o++] = n2 + (ccwFromAbove ? b : c);
		}
		for (int t = 0; t < emitted * 2; t++) accumulateFaceNormal(pos, nrm, idx, t);

		if (wall) {
			for (int s = 0; s < g.segCount; s++) {
				int va = g.seg[s * 2], vb = g.seg[s * 2 + 1];
				float ax = g.x[va], ay = g.y[va], bx = g.x[vb], by = g.y[vb];
				float ex = bx - ax, ey = by - ay;
				float len = (float) Math.sqrt(ex * ex + ey * ey);
				if (len < 1e-5f) continue;
				float ox = -ey / len, oy = ex / len; // candidate outward normal (image space)
				float mx = (ax + bx) * 0.5f, my = (ay + by) * 0.5f;
				float probe = Math.max(0.6f, cell * 0.75f);
				if (bilinear(sdf, W, H, mx + ox * probe + pad - 0.5f, my + oy * probe + pad - 0.5f) > 0f) {
					ox = -ox;
					oy = -oy;
				}
				int ta = wallTop[va], ba = ta + 1, tb = wallTop[vb], bb = tb + 1;
				// Outward in 3D is (ox, 0, oy) since image y maps to +Z.
				float[] fn = triNormal(pos, ta, tb, bb);
				boolean flip = fn[0] * ox + fn[2] * oy < 0f;
				idx[o++] = ta; idx[o++] = flip ? bb : tb; idx[o++] = flip ? tb : bb;
				idx[o++] = ta; idx[o++] = flip ? ba : bb; idx[o++] = flip ? bb : ba;
				for (int v : new int[] {ta, ba}) { nrm[v * 3] += ox * len; nrm[v * 3 + 2] += oy * len; }
				for (int v : new int[] {tb, bb}) { nrm[v * 3] += ox * len; nrm[v * 3 + 2] += oy * len; }
			}
		}
		if (o < idx.length) idx = Arrays.copyOf(idx, o);

		// ---- pack ----
		Result r = new Result();
		r.verts = new float[vCount * STRIDE];
		r.minX = r.minY = r.minZ = Float.MAX_VALUE;
		r.maxX = r.maxY = r.maxZ = -Float.MAX_VALUE;
		for (int k = 0; k < vCount; k++) {
			float nxv = nrm[k * 3], nyv = nrm[k * 3 + 1], nzv = nrm[k * 3 + 2];
			float l = (float) Math.sqrt(nxv * nxv + nyv * nyv + nzv * nzv);
			if (l < 1e-12f) { nxv = 0f; nyv = side[k] == 1f ? -1f : 1f; nzv = 0f; l = 1f; }
			int b = k * STRIDE;
			float px = pos[k * 3], py = pos[k * 3 + 1], pz = pos[k * 3 + 2];
			r.verts[b] = px; r.verts[b + 1] = py; r.verts[b + 2] = pz;
			r.verts[b + 3] = nxv / l; r.verts[b + 4] = nyv / l; r.verts[b + 5] = nzv / l;
			r.verts[b + 6] = uv[k * 2]; r.verts[b + 7] = uv[k * 2 + 1];
			r.verts[b + 8] = side[k];
			r.minX = Math.min(r.minX, px); r.maxX = Math.max(r.maxX, px);
			r.minY = Math.min(r.minY, py); r.maxY = Math.max(r.maxY, py);
			r.minZ = Math.min(r.minZ, pz); r.maxZ = Math.max(r.maxZ, pz);
		}
		r.indices = idx;
		r.vertexCount = vCount;
		r.topTris = r.bottomTris = emitted;
		r.wallTris = (idx.length / 3) - emitted * 2;
		r.boundarySegments = g.segCount;
		r.cell = cell;
		r.gridW = nx;
		r.gridH = ny;
		r.volume = volume;
		r.buildMs = (System.nanoTime() - t0) / 1e6;
		return r;
	}

	// ------------------------------------------------------------------------------------------

	/** Growable 2D vertex / triangle / outline-segment store. */
	private static final class Geo {
		float[] x = new float[1024], y = new float[1024], s = new float[1024];
		boolean[] boundary = new boolean[1024];
		int count;
		int[] tri = new int[3072];
		int triCount;
		int[] seg = new int[1024];
		int segCount;

		int add(float px, float py, float sdf, boolean onBoundary) {
			if (count == x.length) {
				int n = count * 2;
				x = Arrays.copyOf(x, n); y = Arrays.copyOf(y, n); s = Arrays.copyOf(s, n);
				boundary = Arrays.copyOf(boundary, n);
			}
			x[count] = px; y[count] = py; s[count] = sdf; boundary[count] = onBoundary;
			return count++;
		}

		void tri(int a, int b, int c) {
			if (a == b || b == c || a == c) return;
			if (triCount * 3 + 3 > tri.length) tri = Arrays.copyOf(tri, tri.length * 2);
			tri[triCount * 3] = a; tri[triCount * 3 + 1] = b; tri[triCount * 3 + 2] = c;
			triCount++;
		}

		void seg(int a, int b) {
			if (a == b) return;
			if (segCount * 2 + 2 > seg.length) seg = Arrays.copyOf(seg, seg.length * 2);
			seg[segCount * 2] = a; seg[segCount * 2 + 1] = b;
			segCount++;
		}
	}

	/** Fans poly[start..start+n) into triangles; consecutive outline vertices become segments. */
	private static void emitPolygon(Geo g, int[] poly, boolean[] isEdge, int start, int n) {
		for (int m = 1; m < n - 1; m++) g.tri(poly[start], poly[start + m], poly[start + m + 1]);
		for (int m = 0; m < n; m++) {
			int a = start + m, b = start + (m + 1) % n;
			if (isEdge[a] && isEdge[b]) g.seg(poly[a], poly[b]);
		}
	}

	private static void rotateRight(int[] poly, boolean[] isEdge, int n) {
		int lastV = poly[n - 1];
		boolean lastE = isEdge[n - 1];
		for (int m = n - 1; m > 0; m--) {
			poly[m] = poly[m - 1];
			isEdge[m] = isEdge[m - 1];
		}
		poly[0] = lastV;
		isEdge[0] = lastE;
	}

	private static void put(float[] arr, int v, float x, float y, float z) {
		arr[v * 3] = x; arr[v * 3 + 1] = y; arr[v * 3 + 2] = z;
	}

	private static float faceNormalY(float[] p, int a, int b, int c) {
		float abx = p[b * 3] - p[a * 3], abz = p[b * 3 + 2] - p[a * 3 + 2];
		float acx = p[c * 3] - p[a * 3], acz = p[c * 3 + 2] - p[a * 3 + 2];
		return abz * acx - abx * acz;
	}

	private static float[] triNormal(float[] p, int a, int b, int c) {
		float abx = p[b * 3] - p[a * 3], aby = p[b * 3 + 1] - p[a * 3 + 1], abz = p[b * 3 + 2] - p[a * 3 + 2];
		float acx = p[c * 3] - p[a * 3], acy = p[c * 3 + 1] - p[a * 3 + 1], acz = p[c * 3 + 2] - p[a * 3 + 2];
		return new float[] {aby * acz - abz * acy, abz * acx - abx * acz, abx * acy - aby * acx};
	}

	private static void accumulateFaceNormal(float[] p, float[] n, int[] idx, int t) {
		int a = idx[t * 3], b = idx[t * 3 + 1], c = idx[t * 3 + 2];
		float[] fn = triNormal(p, a, b, c);
		for (int v : new int[] {a, b, c}) {
			n[v * 3] += fn[0];
			n[v * 3 + 1] += fn[1];
			n[v * 3 + 2] += fn[2];
		}
	}

	/** Bilinear sample; (fx, fy) in array index space, clamped to the edges. */
	static float bilinear(float[] a, int W, int H, float fx, float fy) {
		fx = Math.max(0f, Math.min(W - 1.001f, fx));
		fy = Math.max(0f, Math.min(H - 1.001f, fy));
		int x0 = (int) fx, y0 = (int) fy;
		float tx = fx - x0, ty = fy - y0;
		int i = y0 * W + x0;
		float top = a[i] + (a[i + 1] - a[i]) * tx;
		float bot = a[i + W] + (a[i + W + 1] - a[i + W]) * tx;
		return top + (bot - top) * ty;
	}

	/** Exact squared Euclidean distance to the nearest pixel where mask == featureValue. */
	static double[] edt(boolean[] mask, int W, int H, boolean featureValue) {
		double[] f = new double[W * H];
		for (int i = 0; i < W * H; i++) f[i] = mask[i] == featureValue ? 0 : INF;
		int n = Math.max(W, H);
		double[] line = new double[n], out = new double[n], z = new double[n + 1];
		int[] v = new int[n];
		for (int x = 0; x < W; x++) {
			for (int y = 0; y < H; y++) line[y] = f[y * W + x];
			dt1d(line, H, out, v, z);
			for (int y = 0; y < H; y++) f[y * W + x] = out[y];
		}
		for (int y = 0; y < H; y++) {
			System.arraycopy(f, y * W, line, 0, W);
			dt1d(line, W, out, v, z);
			System.arraycopy(out, 0, f, y * W, W);
		}
		return f;
	}

	/** Felzenszwalb-Huttenlocher 1D squared distance transform (lower envelope of parabolas). */
	private static void dt1d(double[] f, int n, double[] d, int[] v, double[] z) {
		int k = 0;
		v[0] = 0;
		z[0] = -INF;
		z[1] = INF;
		for (int q = 1; q < n; q++) {
			double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
			while (s <= z[k]) {
				k--;
				s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
			}
			k++;
			v[k] = q;
			z[k] = s;
			z[k + 1] = INF;
		}
		k = 0;
		for (int q = 0; q < n; q++) {
			while (z[k + 1] < q) k++;
			double dq = q - v[k];
			d[q] = dq * dq + f[v[k]];
		}
	}

	static float[] gaussian(float[] src, int W, int H, float sigma) {
		int r = Math.max(1, (int) Math.ceil(sigma * 2.5f));
		float[] k = new float[2 * r + 1];
		float sum = 0f;
		for (int i = -r; i <= r; i++) {
			k[i + r] = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
			sum += k[i + r];
		}
		for (int i = 0; i < k.length; i++) k[i] /= sum;
		float[] tmp = new float[W * H], dst = new float[W * H];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				float acc = 0f;
				for (int i = -r; i <= r; i++) acc += src[y * W + Math.max(0, Math.min(W - 1, x + i))] * k[i + r];
				tmp[y * W + x] = acc;
			}
		}
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				float acc = 0f;
				for (int i = -r; i <= r; i++) acc += tmp[Math.max(0, Math.min(H - 1, y + i)) * W + x] * k[i + r];
				dst[y * W + x] = acc;
			}
		}
		return dst;
	}
}
