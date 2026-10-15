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

		// "crisp" profile: the hard-surface layout with sharp creases, a straightened outline and near-vertical steps.
		public boolean crisp;
		public float crispSdfBlur = 0.35f;
		/** Outline straightening tolerance in grid cells (0 = off). */
		public float outlineTol = 0.6f;
		public float creaseDeg = 35f;
		/** Deck chamfer width / deck height. */
		public float chamfer = 1f;
		/** Tier riser width / tier height (at least 1.5 cells). */
		public float riser = 0.4f;
		/** Smoothing of the inner tier outlines, as a fraction of the tier inset. */
		public float tierSmooth = 0.5f;

		// "plated": crisp, but the raised levels follow panels found in the sprite instead of the outline.
		public boolean plated;
		public int patches = 120;
		public float compact = 0.12f, merge = 0.07f, minPanel = 0.004f, lumStep = 0.7f, plateSmooth = 0.6f;
		/** Octagon radius for cleaning each plate level, as a fraction of R (0 = off). */
		public float plateOpen = 0.06f;

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
			p.crisp = t.crispMesh();
			p.crispSdfBlur = t.meshCrispSdfBlur;
			p.outlineTol = t.meshCrispOutlineTol;
			p.creaseDeg = t.meshCrispCreaseDeg;
			p.chamfer = t.meshCrispChamfer;
			p.riser = t.meshCrispRiser;
			p.tierSmooth = t.meshCrispTierSmooth;
			p.plated = t.platedMesh();
			p.patches = t.meshPlatedPatches;
			p.compact = t.meshPlatedCompact;
			p.merge = t.meshPlatedMerge;
			p.minPanel = t.meshPlatedMinPanel;
			p.lumStep = t.meshPlatedLumStep;
			p.plateSmooth = t.meshPlatedSmooth;
			p.plateOpen = t.meshPlatedOpen;
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

	/** Dev tools only: the plate height field of the last plated build (padded sprite space). */
	public static float[] lastPlateField;
	public static int lastPlateW, lastPlateH;
	/** Dev tools only: last plated build's panel count, kept panels and share of the hull marked as line. */
	public static String lastPlateStats = "";

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
		float cx, rRound, halfWall, lumMean, lumStd, cell;
		int w, W, H, pad;
		float[] lum, lumBlur, lumSmooth;
		/** Crisp: a smoothed distance field for the inner tiers, so their steps don't trace every notch of the outline. */
		float[] tierSdf;
		/** Plated: the panel plate height over each pixel (above the deck), or null. */
		float[] plateTop;
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
		float lumMod = lumMod(fx, fy, c);
		if (p.crisp) {
			// Crisp: a 45-degree chamfer up to a flat deck, then near-vertical risers to flat tiers.
			float base = p.baseHeight * R;
			float minRun = 1.5f * c.cell; // narrower ramps fall between grid vertices and turn jagged
			float chamfer = Math.max(minRun, base * p.chamfer);
			// plated: a short, steep rim bevel, or narrow prongs become ridges where both sides' bevels meet
			if (c.plateTop != null) chamfer = Math.max(minRun, Math.min(chamfer, 0.1f * R));
			float top = base * clamp01(d / chamfer);
			if (c.plateTop != null) {
				// Plated: each panel is a flat plate at its own level; levels fade in past the chamfer so
				// the rim keeps its bevel and thin booms stay low.
				top += bilinear(c.plateTop, c.W, c.H, fx, fy) * clamp01((d - chamfer * 0.5f) / Math.max(minRun, chamfer));
			} else {
			float dt = c.tierSdf != null ? bilinear(c.tierSdf, c.W, c.H, fx, fy) : d;
			for (int k = 1; k <= p.tiers; k++) {
				float rise = p.tierHeight * R * lumMod;
				float run = Math.max(minRun, rise * p.riser);
				top += rise * clamp01((dt - k * p.tierInsetFrac * R) / run);
			}
			}
			out[0] = top + c.halfWall;
			float ub = p.underHeight * R, kh = p.keelHeight * R;
			float under = ub * clamp01(d / Math.max(minRun, ub * p.chamfer * 1.4f))
					+ kh * clamp01((d - p.tierInsetFrac * R * 1.2f) / Math.max(minRun, kh * p.riser));
			out[1] = -under - c.halfWall;
			return;
		}
		// Hard surface: a bevelled deck, then raised tiers inset from the outline (their height follows
		// the sprite's large-scale brightness), flat plateaus in between. Thin parts stay thin plates.
		float bevel = Math.max(0.75f, p.bevelFrac * R);
		float deck = clamp01(d / bevel);
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

	/** Tier height multiplier from the sprite's large-scale brightness (1 when tierLumInfluence is 0). */
	private static float lumMod(float fx, float fy, HeightCtx c) {
		Params p = c.p;
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
		return lumMod;
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
		float blur = p.crisp ? p.crispSdfBlur : p.sdfBlur;
		if (blur > 0.05f) sdf = gaussian(sdf, W, H, blur);
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

		if (p.crisp && p.outlineTol > 0f) straightenOutline(g, p.outlineTol * cell);

		// ---- heights ----
		float rRound = Math.max(2f, p.roundRadiusFrac * Math.min(w, h));
		float halfWall = (p.hardSurface ? p.wallFrac : p.edgeThickness) * Math.max(w, h) * 0.5f;
		HeightCtx hc = new HeightCtx();
		hc.p = p;
		hc.cx = cx;
		hc.w = w;
		hc.rRound = rRound;
		hc.cell = cell;
		if (p.plated) {
			float[][] rgb = new float[3][W * H];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = (y + pad) * W + (x + pad), c = img.argb[y * w + x];
					rgb[0][i] = ((c >> 16) & 255) / 255f;
					rgb[1][i] = ((c >> 8) & 255) / 255f;
					rgb[2][i] = (c & 255) / 255f;
				}
			}
			// mirror axis: the rotation centre's column, in padded pixel space (pixel centres at x + 0.5)
			hc.plateTop = plateField(in, rgb, lum, sdf, W, H, rRound, cell, p, cx + pad - 0.5f);
			lastPlateField = hc.plateTop;
			lastPlateW = W;
			lastPlateH = H;
		}
		if (p.crisp && p.tierSmooth > 0f) hc.tierSdf = gaussian(sdf, W, H, Math.max(0.5f, p.tierSmooth * p.tierInsetFrac * rRound));
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
		if (p.crisp) {
			creaseShading(g, hTop, hBot, side, p.tierHeight * rRound);
			if (wall) {
				// Walls: the sprite's rim folds down over them (the bottom edge samples one wall height
				// further inside the outline) instead of one edge texel smeared straight down, and they
				// shade from a lit top edge to a darker foot.
				float wallH = 2f * halfWall;
				for (int k = 0; k < n2; k++) {
					int a = wallTop[k];
					if (a < 0) continue;
					float ox = nrm[a * 3], oy = nrm[a * 3 + 2];
					float l = (float) Math.sqrt(ox * ox + oy * oy);
					if (l > 1e-6f) {
						uv[(a + 1) * 2] = (g.x[k] - ox / l * wallH) / w;
						uv[(a + 1) * 2 + 1] = (g.y[k] - oy / l * wallH) / h;
					}
					side[a] += 0.25f + 0.2f * 0.6f;
					side[a + 1] += 0.25f - 0.2f * 0.6f;
				}
			}
			float[] stepRise = p.plated ? stepRise(g, hTop, 3f * p.tierHeight * rRound) : null;
			Creased cr = crease(pos, uv, side, idx, (float) Math.cos(Math.toRadians(p.creaseDeg)));
			if (stepRise != null) {
				// Step faces (plate edges, rim bevel) would show the art projected straight down, stretched into
				// streaks. Their vertex copies (steep normal) sample it uphill instead, against the face's own
				// direction (smooth along a step), by as much as they sit below the plate above; the flat
				// plating around the step keeps its straight-down projection.
				for (int v = 0; v < cr.count; v++) {
					int o2 = cr.from[v];
					if (o2 >= n2 || stepRise[o2] <= 0f || cr.nrm[v * 3 + 1] > 0.8f) continue;
					float ox = cr.nrm[v * 3], oz = cr.nrm[v * 3 + 2];
					float l = (float) Math.sqrt(ox * ox + oz * oz);
					if (l < 1e-4f) continue;
					cr.uv[v * 2] = Math.max(0f, Math.min(1f, (g.x[o2] - ox / l * stepRise[o2]) / w));
					cr.uv[v * 2 + 1] = Math.max(0f, Math.min(1f, (g.y[o2] - oz / l * stepRise[o2]) / h));
				}
			}
			pos = cr.pos;
			nrm = cr.nrm;
			uv = cr.uv;
			side = cr.side;
			idx = cr.idx;
			vCount = cr.count;
		}

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
	// plated profile

	/**
	 * Height above the deck for every pixel, plate by plate. Plates come from the sprite's colours: the
	 * hull is split into about {@code patches} compact patches of similar colour (SLIC-style
	 * clustering on smoothed RGB + position), then neighbouring patches with close mean colours merge
	 * and specks merge into their neighbour. A plate's level (0..tiers) comes from how deep inside the
	 * hull it lies on average, one up if it is clearly brighter than the hull, one down if clearly
	 * darker; each level is tierHeight x R. The field is smoothed a little so step outlines don't
	 * follow the grid's stairs.
	 */
	static float[] plateField(boolean[] in, float[][] rgb, float[] lum, float[] sdf, int W, int H, float R, float cell, Params p,
							  float mirrorX) {
		int n = W * H;
		float[][] col = new float[3][];
		for (int k = 0; k < 3; k++) col[k] = gaussian(rgb[k], W, H, 1.0f);
		int hullArea = 0;
		for (int i = 0; i < n; i++) if (in[i]) hullArea++;
		if (hullArea == 0) return new float[n];

		// ---- SLIC-style patches ----
		float S = (float) Math.max(3.0, Math.sqrt(hullArea / (double) Math.max(4, p.patches)));
		java.util.ArrayList<float[]> centres = new java.util.ArrayList<>(); // x, y, r, g, b
		for (float y = S * 0.5f; y < H; y += S) {
			for (float x = S * 0.5f; x < W; x += S) {
				int i = (int) y * W + (int) x;
				if (in[i]) centres.add(new float[] {x, y, col[0][i], col[1][i], col[2][i]});
			}
		}
		int K = centres.size();
		int[] lab = new int[n];
		float[] best = new float[n];
		float m2 = p.compact * p.compact;
		int win = (int) Math.ceil(S * 1.5f);
		for (int iter = 0; iter < 6; iter++) {
			Arrays.fill(lab, -1);
			Arrays.fill(best, Float.MAX_VALUE);
			for (int k = 0; k < K; k++) {
				float[] c = centres.get(k);
				int x0 = Math.max(0, (int) c[0] - win), x1 = Math.min(W - 1, (int) c[0] + win);
				int y0 = Math.max(0, (int) c[1] - win), y1 = Math.min(H - 1, (int) c[1] + win);
				for (int y = y0; y <= y1; y++) {
					for (int x = x0; x <= x1; x++) {
						int i = y * W + x;
						if (!in[i]) continue;
						float dr = col[0][i] - c[2], dg = col[1][i] - c[3], db = col[2][i] - c[4];
						float dx = (x - c[0]) / S, dy = (y - c[1]) / S;
						float dist = dr * dr + dg * dg + db * db + (dx * dx + dy * dy) * m2;
						if (dist < best[i]) { best[i] = dist; lab[i] = k; }
					}
				}
			}
			double[][] acc = new double[K][6];
			for (int i = 0; i < n; i++) {
				int k = lab[i];
				if (k < 0) continue;
				double[] a = acc[k];
				a[0] += i % W; a[1] += i / W; a[2] += col[0][i]; a[3] += col[1][i]; a[4] += col[2][i]; a[5]++;
			}
			for (int k = 0; k < K; k++) {
				double[] a = acc[k];
				if (a[5] == 0) continue;
				float[] c = centres.get(k);
				for (int q = 0; q < 5; q++) c[q] = (float) (a[q] / a[5]);
			}
		}
		for (int i = 0; i < n; i++) if (in[i] && lab[i] < 0) lab[i] = 0;

		// ---- connected regions of each patch label, then merging ----
		int[] reg = new int[n];
		Arrays.fill(reg, -1);
		int[] queue = new int[n];
		int regions = 0;
		for (int s = 0; s < n; s++) {
			if (!in[s] || reg[s] >= 0) continue;
			int head = 0, tail = 0;
			queue[tail++] = s;
			reg[s] = regions;
			while (head < tail) {
				int i = queue[head++], x = i % W;
				for (int j : new int[] {x > 0 ? i - 1 : -1, x < W - 1 ? i + 1 : -1, i - W, i + W}) {
					if (j < 0 || j >= n || !in[j] || reg[j] >= 0 || lab[j] != lab[i]) continue;
					reg[j] = regions;
					queue[tail++] = j;
				}
			}
			regions++;
		}
		int[] parent = new int[regions];
		for (int r = 0; r < regions; r++) parent[r] = r;
		double[][] stat = new double[regions][4]; // r, g, b sums, count
		for (int i = 0; i < n; i++) {
			if (reg[i] < 0) continue;
			double[] st = stat[reg[i]];
			st[0] += col[0][i]; st[1] += col[1][i]; st[2] += col[2][i]; st[3]++;
		}
		int minArea = Math.max(10, Math.round(p.minPanel * hullArea));
		// merge passes: similar colour first, then specks into their closest-coloured neighbour
		for (int pass = 0; pass < 2; pass++) {
			boolean changed = true;
			while (changed) {
				changed = false;
				for (int i = 0; i < n; i++) {
					if (reg[i] < 0) continue;
					int x = i % W;
					for (int j : new int[] {x < W - 1 ? i + 1 : -1, i + W}) {
						if (j < 0 || j >= n || reg[j] < 0) continue;
						int a = find(parent, reg[i]), b = find(parent, reg[j]);
						if (a == b) continue;
						double[] sa = stat[a], sb = stat[b];
						double dr = sa[0] / sa[3] - sb[0] / sb[3], dg = sa[1] / sa[3] - sb[1] / sb[3], db = sa[2] / sa[3] - sb[2] / sb[3];
						double diff = Math.sqrt(dr * dr + dg * dg + db * db);
						boolean join = pass == 0 ? diff < p.merge : (sa[3] < minArea || sb[3] < minArea) && diff < 0.6;
						if (!join) continue;
						parent[b] = a;
						for (int q = 0; q < 4; q++) sa[q] += sb[q];
						changed = true;
					}
				}
			}
		}
		// anything still tiny joins whichever neighbour it touches
		for (int sweep = 0; sweep < 4; sweep++) {
			for (int i = 0; i < n; i++) {
				if (reg[i] < 0) continue;
				int a = find(parent, reg[i]);
				if (stat[a][3] >= minArea) continue;
				int x = i % W;
				for (int j : new int[] {x > 0 ? i - 1 : -1, x < W - 1 ? i + 1 : -1, i - W, i + W}) {
					if (j < 0 || j >= n || reg[j] < 0) continue;
					int b = find(parent, reg[j]);
					if (b == a) continue;
					parent[a] = b;
					for (int q = 0; q < 4; q++) stat[b][q] += stat[a][q];
					break;
				}
			}
		}

		// ---- plate levels ----
		double[] depth = new double[regions], bright = new double[regions];
		int[] cnt = new int[regions];
		double sum = 0, sum2 = 0;
		for (int i = 0; i < n; i++) {
			if (reg[i] < 0) continue;
			int r = find(parent, reg[i]);
			reg[i] = r;
			depth[r] += Math.max(0f, sdf[i]);
			bright[r] += lum[i];
			cnt[r]++;
			sum += lum[i];
			sum2 += lum[i] * lum[i];
		}
		double mean = sum / hullArea, std = Math.sqrt(Math.max(1e-6, sum2 / hullArea - mean * mean));
		float[] level = new float[regions];
		float inset = Math.max(1f, p.tierInsetFrac * R);
		int plates = 0;
		for (int r = 0; r < regions; r++) {
			if (cnt[r] == 0) continue;
			plates++;
			double d = depth[r] / cnt[r], z = (bright[r] / cnt[r] - mean) / std;
			int k = (int) Math.round(d / inset - 0.3);
			if (z > p.lumStep) k++;
			else if (z < -p.lumStep * 1.5) k--;
			level[r] = Math.max(0, Math.min(p.tiers, k)) * p.tierHeight * R;
		}
		lastPlateStats = String.format("%d patches -> %d regions -> %d plates (min %d px)", K, regions, plates, minArea);
		float[] hf = new float[n];
		for (int i = 0; i < n; i++) if (reg[i] >= 0) hf[i] = level[reg[i]];
		// Most hulls are mirror-symmetric; patches aren't. If the silhouette mirrors onto itself about the
		// rotation centre, each plate level becomes the higher of a point and its mirror image.
		if (mirrorX >= 0f) {
			int match = 0, total = 0;
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					int mx = Math.round(2f * mirrorX - x);
					if (mx < 0 || mx >= W) continue;
					boolean a = in[y * W + x], b = in[y * W + mx];
					if (a || b) { total++; if (a == b) match++; }
				}
			}
			if (total > 0 && match > 0.95f * total) {
				float[] sym = hf.clone();
				for (int y = 0; y < H; y++) {
					for (int x = 0; x < W; x++) {
						int mx = Math.round(2f * mirrorX - x);
						if (mx >= 0 && mx < W && in[y * W + x] && in[y * W + mx]) sym[y * W + x] = Math.max(hf[y * W + x], hf[y * W + mx]);
					}
				}
				hf = sym;
				lastPlateStats += ", mirrored";
			}
		}
		// Armour, not rock: each level is opened with an octagon (erode, then dilate), which drops anything
		// too thin to be a plate (no spikes) and leaves straight edges with 45-degree chamfered corners.
		// Upper levels use a bigger octagon, so they are chunkier and sit inside the ones below.
		float step = p.tierHeight * R;
		if (step > 1e-4f && p.plateOpen > 0f) {
			boolean[] below = in.clone();
			float[] out = new float[n];
			for (int k = 1; k <= p.tiers; k++) {
				boolean[] m = new boolean[n];
				for (int i = 0; i < n; i++) m[i] = below[i] && hf[i] >= (k - 0.5f) * step;
				int r = Math.max(1, Math.round(Math.max(1.5f * cell, p.plateOpen * R) * (1f + 0.4f * (k - 1))));
				m = octDilate(octErode(m, W, H, r), W, H, r);
				for (int i = 0; i < n; i++) {
					m[i] &= below[i];
					if (m[i]) out[i] += step;
				}
				below = m;
			}
			hf = out;
		}
		return p.plateSmooth > 0f ? gaussian(hf, W, H, Math.max(0.4f, p.plateSmooth * cell)) : hf;
	}

	/** Erosion by an octagon of radius r: a square of half side ~0.6r, then a diamond of radius ~0.6r. */
	static boolean[] octErode(boolean[] m, int W, int H, int r) {
		return morph(m, W, H, r, true);
	}

	static boolean[] octDilate(boolean[] m, int W, int H, int r) {
		return morph(m, W, H, r, false);
	}

	private static boolean[] morph(boolean[] m, int W, int H, int r, boolean erode) {
		int sq = Math.max(1, Math.round(r * 0.6f)), di = Math.max(1, r - sq);
		boolean[] a = m.clone(), b = new boolean[m.length];
		// square: separable min/max over rows then columns
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				boolean v = erode; // erode: all within reach set; dilate: any (stops at the first that decides it)
				for (int d = -sq; d <= sq && v == erode; d++) {
					int xx = x + d;
					v = xx >= 0 && xx < W && a[y * W + xx];
				}
				b[y * W + x] = v;
			}
		}
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				boolean v = erode;
				for (int d = -sq; d <= sq && v == erode; d++) {
					int yy = y + d;
					v = yy >= 0 && yy < H && b[yy * W + x];
				}
				a[y * W + x] = v;
			}
		}
		// diamond: repeated 4-neighbour steps
		for (int it = 0; it < di; it++) {
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					int i = y * W + x;
					boolean c = a[i];
					boolean l = x > 0 && a[i - 1], rr = x < W - 1 && a[i + 1], u = y > 0 && a[i - W], dn = y < H - 1 && a[i + W];
					b[i] = erode ? (c && l && rr && u && dn) : (c || l || rr || u || dn);
				}
			}
			boolean[] t = a; a = b; b = t;
		}
		return a;
	}

	private static int find(int[] parent, int a) {
		while (parent[a] != a) {
			parent[a] = parent[parent[a]];
			a = parent[a];
		}
		return a;
	}

	// ------------------------------------------------------------------------------------------
	// crisp profile helpers

	/**
	 * Straightens the traced outline: each outline loop is simplified (Douglas-Peucker, tolerance tol
	 * pixels) and the vertices between kept points slide onto the straight segment. A vertex whose
	 * move would flip or collapse a triangle stays put.
	 */
	private static void straightenOutline(Geo g, float tol) {
		int n = g.count;
		int[] next = new int[n];
		Arrays.fill(next, -1);
		boolean[] fixed = new boolean[n];
		for (int s = 0; s < g.segCount; s++) {
			int a = g.seg[s * 2], b = g.seg[s * 2 + 1];
			if (next[a] >= 0) fixed[a] = true; // branching outline: leave it alone
			else next[a] = b;
		}
		// vertex -> triangles (CSR)
		int[] start = new int[n + 1];
		for (int t = 0; t < g.triCount * 3; t++) start[g.tri[t] + 1]++;
		for (int v = 0; v < n; v++) start[v + 1] += start[v];
		int[] fill = Arrays.copyOf(start, n);
		int[] vt = new int[g.triCount * 3];
		for (int t = 0; t < g.triCount; t++) for (int k = 0; k < 3; k++) vt[fill[g.tri[t * 3 + k]]++] = t;

		boolean[] seen = new boolean[n];
		int[] loop = new int[n + 1];
		boolean[] keep = new boolean[n + 1];
		int[] stack = new int[2 * (n + 2)];
		for (int s0 = 0; s0 < n; s0++) {
			if (next[s0] < 0 || seen[s0]) continue;
			int len = 0, v = s0;
			while (v >= 0 && !seen[v]) {
				seen[v] = true;
				loop[len++] = v;
				v = next[v];
			}
			if (v != s0 || len < 6) continue; // open chain or tiny speck
			loop[len] = s0;
			// split at the point farthest from the start, simplify both halves
			int far = 0;
			float best = -1f;
			for (int i = 1; i < len; i++) {
				float dx = g.x[loop[i]] - g.x[s0], dy = g.y[loop[i]] - g.y[s0];
				if (dx * dx + dy * dy > best) { best = dx * dx + dy * dy; far = i; }
			}
			Arrays.fill(keep, 0, len + 1, false);
			keep[0] = keep[far] = keep[len] = true;
			for (int i = 0; i < len; i++) if (fixed[loop[i]]) keep[i] = true;
			int sp = 0;
			stack[sp++] = 0; stack[sp++] = far;
			stack[sp++] = far; stack[sp++] = len;
			while (sp > 0) {
				int b = stack[--sp], a = stack[--sp];
				if (b - a < 2) continue;
				float ax = g.x[loop[a]], ay = g.y[loop[a]], ex = g.x[loop[b]] - ax, ey = g.y[loop[b]] - ay;
				float el = (float) Math.sqrt(ex * ex + ey * ey);
				int worst = -1;
				float wd = tol;
				for (int i = a + 1; i < b; i++) {
					float px = g.x[loop[i]] - ax, py = g.y[loop[i]] - ay;
					float dist = el < 1e-5f ? (float) Math.sqrt(px * px + py * py) : Math.abs(px * ey - py * ex) / el;
					if (keep[i]) dist = Float.MAX_VALUE;
					if (dist > wd) { wd = dist; worst = i; }
				}
				if (worst < 0) continue;
				keep[worst] = true;
				stack[sp++] = a; stack[sp++] = worst;
				stack[sp++] = worst; stack[sp++] = b;
			}
			// slide the dropped vertices onto their segment
			int a = 0;
			for (int b = 1; b <= len; b++) {
				if (!keep[b]) continue;
				float ax = g.x[loop[a]], ay = g.y[loop[a]], ex = g.x[loop[b]] - ax, ey = g.y[loop[b]] - ay;
				float el2 = ex * ex + ey * ey;
				for (int i = a + 1; i < b && el2 > 1e-8f; i++) {
					int q = loop[i];
					float t = clamp01(((g.x[q] - ax) * ex + (g.y[q] - ay) * ey) / el2);
					moveIfSafe(g, q, ax + ex * t, ay + ey * t, start, vt);
				}
				a = b;
			}
		}
	}

	private static void moveIfSafe(Geo g, int v, float nx, float ny, int[] start, int[] vt) {
		float ox = g.x[v], oy = g.y[v];
		float[] before = new float[start[v + 1] - start[v]];
		for (int k = start[v]; k < start[v + 1]; k++) before[k - start[v]] = area2(g, vt[k]);
		g.x[v] = nx;
		g.y[v] = ny;
		for (int k = start[v]; k < start[v + 1]; k++) {
			float b = before[k - start[v]], a = area2(g, vt[k]);
			if (Math.abs(b) < 1e-6f) continue; // already a sliver; dropped later anyway
			if (Math.signum(a) != Math.signum(b) || Math.abs(a) < Math.abs(b) * 0.1f) {
				g.x[v] = ox;
				g.y[v] = oy;
				return;
			}
		}
	}

	private static float area2(Geo g, int t) {
		int a = g.tri[t * 3], b = g.tri[t * 3 + 1], c = g.tri[t * 3 + 2];
		return (g.x[b] - g.x[a]) * (g.y[c] - g.y[a]) - (g.y[b] - g.y[a]) * (g.x[c] - g.x[a]);
	}

	/**
	 * Plated: for each top vertex, how far it sits below the highest vertex within two edges (0 if less
	 * than 0.3, capped at reach): the fold distance for step faces.
	 */
	private static float[] stepRise(Geo g, float[] hTop, float reach) {
		int n = g.count;
		int[] start = new int[n + 1];
		for (int t = 0; t < g.triCount * 3; t++) start[g.tri[t] + 1] += 2;
		for (int v = 0; v < n; v++) start[v + 1] += start[v];
		int[] fill = Arrays.copyOf(start, n);
		int[] nb = new int[start[n]];
		for (int t = 0; t < g.triCount; t++) {
			for (int k = 0; k < 3; k++) {
				int a = g.tri[t * 3 + k];
				nb[fill[a]++] = g.tri[t * 3 + (k + 1) % 3];
				nb[fill[a]++] = g.tri[t * 3 + (k + 2) % 3];
			}
		}
		float[] max1 = new float[n];
		for (int v = 0; v < n; v++) {
			float m = hTop[v];
			for (int q = start[v]; q < start[v + 1]; q++) m = Math.max(m, hTop[nb[q]]);
			max1[v] = m;
		}
		float[] rise = new float[n];
		for (int v = 0; v < n; v++) {
			float m = max1[v];
			for (int q = start[v]; q < start[v + 1]; q++) m = Math.max(m, max1[nb[q]]);
			float r = m - hTop[v];
			rise[v] = r < 0.3f ? 0f : Math.min(r, reach);
		}
		return rise;
	}

	/**
	 * Crease shading baked into the side attribute: side = class + 0.25 + 0.2 x e, with e in [-1, 1]
	 * (negative = inner corner, darkened; positive = outer edge, lit). From how far each vertex sits
	 * above or below its neighbours; the outline rim counts as an outer edge.
	 */
	private static void creaseShading(Geo g, float[] hTop, float[] hBot, float[] side, float stepH) {
		int n = g.count;
		float[] sumT = new float[n], sumB = new float[n];
		int[] cnt = new int[n];
		for (int t = 0; t < g.triCount; t++) {
			for (int k = 0; k < 3; k++) {
				int a = g.tri[t * 3 + k], b = g.tri[t * 3 + (k + 1) % 3];
				sumT[a] += hTop[b]; sumB[a] += hBot[b]; cnt[a]++;
				sumT[b] += hTop[a]; sumB[b] += hBot[a]; cnt[b]++;
			}
		}
		float s = Math.max(0.2f, stepH * 0.3f);
		for (int v = 0; v < n; v++) {
			float eT, eB;
			if (g.boundary[v]) {
				eT = 1f;
				eB = 0.6f;
			} else if (cnt[v] > 0) {
				eT = Math.max(-1f, Math.min(1f, (hTop[v] - sumT[v] / cnt[v]) / s));
				eB = Math.max(-1f, Math.min(1f, (sumB[v] / cnt[v] - hBot[v]) / s));
			} else {
				eT = eB = 0f;
			}
			side[v] += 0.25f + 0.2f * eT;
			side[n + v] += 0.25f + 0.2f * eB;
		}
	}

	private static final class Creased {
		float[] pos, nrm, uv, side;
		int[] idx;
		int count;
		/** The input vertex each output vertex was copied from. */
		int[] from;
	}

	/**
	 * Per-corner normals that only smooth across faces within the crease angle, so plates stay flat
	 * and their edges break the light. Corners with different normals get their own vertex.
	 */
	private static Creased crease(float[] pos, float[] uv, float[] side, int[] idx, float cosA) {
		int nv = pos.length / 3, nt = idx.length / 3;
		float[] fn = new float[nt * 3];
		float[] fu = new float[nt * 3];
		for (int t = 0; t < nt; t++) {
			float[] f = triNormal(pos, idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2]);
			float l = (float) Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
			System.arraycopy(f, 0, fn, t * 3, 3);
			if (l > 1e-12f) { fu[t * 3] = f[0] / l; fu[t * 3 + 1] = f[1] / l; fu[t * 3 + 2] = f[2] / l; }
		}
		int[] start = new int[nv + 1];
		for (int v : idx) start[v + 1]++;
		for (int v = 0; v < nv; v++) start[v + 1] += start[v];
		int[] fill = Arrays.copyOf(start, nv);
		int[] vt = new int[idx.length];
		for (int t = 0; t < nt; t++) for (int k = 0; k < 3; k++) vt[fill[idx[t * 3 + k]]++] = t;

		Creased c = new Creased();
		int cap = nv * 2;
		float[] P = new float[cap * 3], N = new float[cap * 3], U = new float[cap * 2], S = new float[cap];
		int[] I = new int[idx.length];
		int count = 0;
		int[] F = new int[cap];
		int[] firstCopy = new int[nv];
		int[] nextCopy = new int[cap];
		Arrays.fill(firstCopy, -1);
		for (int t = 0; t < nt; t++) {
			for (int k = 0; k < 3; k++) {
				int v = idx[t * 3 + k];
				float nx = 0f, ny = 0f, nz = 0f;
				for (int q = start[v]; q < start[v + 1]; q++) {
					int u = vt[q];
					if (fu[t * 3] * fu[u * 3] + fu[t * 3 + 1] * fu[u * 3 + 1] + fu[t * 3 + 2] * fu[u * 3 + 2] < cosA) continue;
					nx += fn[u * 3]; ny += fn[u * 3 + 1]; nz += fn[u * 3 + 2];
				}
				float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (l < 1e-12f) { nx = fu[t * 3]; ny = fu[t * 3 + 1]; nz = fu[t * 3 + 2]; l = 1f; }
				nx /= l; ny /= l; nz /= l;
				int id = -1;
				for (int e = firstCopy[v]; e >= 0; e = nextCopy[e]) {
					if (N[e * 3] * nx + N[e * 3 + 1] * ny + N[e * 3 + 2] * nz > 0.999f) { id = e; break; }
				}
				if (id < 0) {
					if (count == cap) {
						cap *= 2;
						P = Arrays.copyOf(P, cap * 3); N = Arrays.copyOf(N, cap * 3);
						U = Arrays.copyOf(U, cap * 2); S = Arrays.copyOf(S, cap); nextCopy = Arrays.copyOf(nextCopy, cap);
						F = Arrays.copyOf(F, cap);
					}
					id = count++;
					System.arraycopy(pos, v * 3, P, id * 3, 3);
					N[id * 3] = nx; N[id * 3 + 1] = ny; N[id * 3 + 2] = nz;
					System.arraycopy(uv, v * 2, U, id * 2, 2);
					S[id] = side[v];
					F[id] = v;
					nextCopy[id] = firstCopy[v];
					firstCopy[v] = id;
				}
				I[t * 3 + k] = id;
			}
		}
		c.pos = Arrays.copyOf(P, count * 3);
		c.nrm = Arrays.copyOf(N, count * 3);
		c.uv = Arrays.copyOf(U, count * 2);
		c.side = Arrays.copyOf(S, count);
		c.idx = I;
		c.count = count;
		c.from = Arrays.copyOf(F, count);
		return c;
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
