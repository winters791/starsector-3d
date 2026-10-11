package s3d.core;

/**
 * Solid shape of a relief-mesh hull for hit tests, in the ship's unscaled local frame.
 * A point is inside when it lies within the silhouette and between the underside and top
 * surfaces at that (x, z). Grids are sampled bilinearly; built by the mesh builder from the
 * same signed distance field and heights as the visible mesh, so hits match what you see.
 */
public final class HullVolume {
	/** Grid origin (local x, z of grid point 0,0) and spacing. */
	public final float originX, originZ, cell;
	public final int nx, nz;
	/** Signed distance to the silhouette edge (positive inside), top and underside heights (local y). */
	public final float[] sdf, top, bottom;
	public final float minY, maxY;

	public HullVolume(float originX, float originZ, float cell, int nx, int nz, float[] sdf, float[] top, float[] bottom) {
		this.originX = originX;
		this.originZ = originZ;
		this.cell = cell;
		this.nx = nx;
		this.nz = nz;
		this.sdf = sdf;
		this.top = top;
		this.bottom = bottom;
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (int i = 0; i < top.length; i++) {
			if (sdf[i] <= 0f) continue;
			lo = Math.min(lo, bottom[i]);
			hi = Math.max(hi, top[i]);
		}
		minY = lo == Float.MAX_VALUE ? 0f : lo;
		maxY = hi == -Float.MAX_VALUE ? 0f : hi;
	}

	public boolean contains(float x, float y, float z) {
		if (y < minY || y > maxY) return false;
		float gx = (x - originX) / cell, gz = (z - originZ) / cell;
		if (gx < 0f || gz < 0f || gx > nx - 1.001f || gz > nz - 1.001f) return false;
		if (sample(sdf, gx, gz) <= 0f) return false;
		return y <= sample(top, gx, gz) && y >= sample(bottom, gx, gz);
	}

	/**
	 * Copy whose solid extends at least halfHeight above and below the mid-plane inside the
	 * silhouette. Fighters use it: as thin relief plates they'd be nearly unhittable edge-on, while
	 * vanilla hits anything crossing their outline.
	 */
	public HullVolume thickened(float halfHeight) {
		float[] t = top.clone(), b = bottom.clone();
		for (int i = 0; i < t.length; i++) {
			t[i] = Math.max(t[i], halfHeight);
			b[i] = Math.min(b[i], -halfHeight);
		}
		return new HullVolume(originX, originZ, cell, nx, nz, sdf, t, b);
	}

	private float[] samples;

	/**
	 * Points spread over the hull for collision tests (unscaled local, x/y/z triples): the outline
	 * at mid-height plus a sparse grid of the top and underside. Built once, about 150 points.
	 */
	public float[] collisionSamples() {
		if (samples != null) return samples;
		java.util.List<float[]> pts = new java.util.ArrayList<>();
		int inside = 0;
		for (float v : sdf) if (v > 0f) inside++;
		int stride = Math.max(1, (int) Math.sqrt(inside / 60.0));
		for (int gz = 0; gz < nz; gz++) {
			for (int gx = 0; gx < nx; gx++) {
				int i = gz * nx + gx;
				if (sdf[i] <= 0f) continue;
				float x = originX + gx * cell, z = originZ + gz * cell;
				boolean edge = sdf[i] < cell * 1.5f;
				if (edge && (gx + gz) % stride == 0) pts.add(new float[] {x, (top[i] + bottom[i]) * 0.5f, z});
				if (gx % stride == 0 && gz % stride == 0) {
					pts.add(new float[] {x, top[i] - 0.25f, z});
					pts.add(new float[] {x, bottom[i] + 0.25f, z});
				}
			}
		}
		samples = new float[pts.size() * 3];
		for (int i = 0; i < pts.size(); i++) System.arraycopy(pts.get(i), 0, samples, i * 3, 3);
		return samples;
	}

	private static final int REACH_BINS = 32;
	private float[] reach;
	private float extent;

	/**
	 * How far the solid shape reaches from the centre toward local direction (x, z) (unscaled; the
	 * direction needn't be unit): the furthest solid cell in that 1/32 of a turn, or its neighbours.
	 */
	public float reachToward(float x, float z) {
		if (reach == null) buildReach();
		double a = Math.atan2(x, z);
		int bin = (int) Math.floor((a + Math.PI) / (2 * Math.PI) * REACH_BINS);
		return reach[Math.max(0, Math.min(REACH_BINS - 1, bin))];
	}

	/** Furthest solid point from the centre in the horizontal plane (unscaled). */
	public float extent() {
		if (reach == null) buildReach();
		return extent;
	}

	private void buildReach() {
		float[] raw = new float[REACH_BINS];
		float ext = 0f;
		for (int gz = 0; gz < nz; gz++) {
			for (int gx = 0; gx < nx; gx++) {
				if (sdf[gz * nx + gx] <= 0f) continue;
				float x = originX + gx * cell, z = originZ + gz * cell;
				float r = (float) Math.hypot(x, z) + cell * 0.5f;
				int bin = (int) Math.floor((Math.atan2(x, z) + Math.PI) / (2 * Math.PI) * REACH_BINS);
				bin = Math.max(0, Math.min(REACH_BINS - 1, bin));
				raw[bin] = Math.max(raw[bin], r);
				ext = Math.max(ext, r);
			}
		}
		float[] out = new float[REACH_BINS];
		for (int i = 0; i < REACH_BINS; i++) {
			out[i] = Math.max(raw[i], Math.max(raw[(i + 1) % REACH_BINS], raw[(i + REACH_BINS - 1) % REACH_BINS]));
		}
		extent = ext;
		reach = out;
	}

	/** Top-surface height at (x, z), or 0 outside the silhouette. Used to seat turrets on the hull. */
	public float topAt(float x, float z) {
		float gx = (x - originX) / cell, gz = (z - originZ) / cell;
		if (gx < 0f || gz < 0f || gx > nx - 1.001f || gz > nz - 1.001f) return 0f;
		return sample(top, gx, gz);
	}

	/** 0 = hit came through the top surface, 1 = the side wall, 2 = the underside. */
	public int surfaceClass(float outsideX, float outsideY, float outsideZ) {
		float gx = (outsideX - originX) / cell, gz = (outsideZ - originZ) / cell;
		if (gx >= 0f && gz >= 0f && gx <= nx - 1.001f && gz <= nz - 1.001f && sample(sdf, gx, gz) > 0f) {
			if (outsideY > sample(top, gx, gz)) return 0;
			if (outsideY < sample(bottom, gx, gz)) return 2;
		}
		return 1;
	}

	private float sample(float[] a, float gx, float gz) {
		int x0 = (int) gx, z0 = (int) gz;
		float tx = gx - x0, tz = gz - z0;
		int i = z0 * nx + x0;
		float r0 = a[i] + (a[i + 1] - a[i]) * tx;
		float r1 = a[i + nx] + (a[i + nx + 1] - a[i + nx]) * tx;
		return r0 + (r1 - r0) * tz;
	}
}
