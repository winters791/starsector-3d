package s3d.math;

/**
 * Mutable unit quaternion. Conventions used across the mod: right-handed, +Y up,
 * a ship's (and the camera's) forward is local -Z, right is +X, up is +Y.
 * Body rates: pitch about +X (positive = nose up), yaw about +Y (positive = nose left),
 * roll about +Z (positive = right wing up, i.e. roll left).
 */
public final class Quat {
	public float x, y, z, w = 1f;

	public Quat() {}

	public Quat(Quat o) {
		set(o);
	}

	public Quat set(float x, float y, float z, float w) {
		this.x = x; this.y = y; this.z = z; this.w = w;
		return this;
	}

	public Quat set(Quat o) {
		return set(o.x, o.y, o.z, o.w);
	}

	public Quat identity() {
		return set(0, 0, 0, 1);
	}

	public Quat setAxisAngle(float ax, float ay, float az, float radians) {
		float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		if (len < 1e-12f) return identity();
		float s = (float) Math.sin(radians * 0.5) / len;
		return set(ax * s, ay * s, az * s, (float) Math.cos(radians * 0.5));
	}

	/** this = a * b (apply b first, then a). Safe when this aliases a or b. */
	public Quat mul(Quat a, Quat b) {
		float nx = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y;
		float ny = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x;
		float nz = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w;
		float nw = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z;
		return set(nx, ny, nz, nw);
	}

	/** this = this * b, i.e. b is a rotation expressed in this rotation's local frame. */
	public Quat mulLocal(Quat b) {
		return mul(this, b);
	}

	public Quat conjugate() {
		x = -x; y = -y; z = -z;
		return this;
	}

	public Quat normalize() {
		float l = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		if (l < 1e-12f) return identity();
		float inv = 1f / l;
		x *= inv; y *= inv; z *= inv; w *= inv;
		return this;
	}

	/** Rotates v by this quaternion into out (out may alias v). */
	public Vec3 rotate(Vec3 v, Vec3 out) {
		return rotate(v.x, v.y, v.z, out);
	}

	public Vec3 rotate(float vx, float vy, float vz, Vec3 out) {
		// t = 2 * cross(q.xyz, v); v' = v + w*t + cross(q.xyz, t)
		float tx = 2f * (y * vz - z * vy);
		float ty = 2f * (z * vx - x * vz);
		float tz = 2f * (x * vy - y * vx);
		return out.set(
				vx + w * tx + (y * tz - z * ty),
				vy + w * ty + (z * tx - x * tz),
				vz + w * tz + (x * ty - y * tx));
	}

	/** Rotates v by the inverse of this quaternion (world -> local). */
	public Vec3 inverseRotate(Vec3 v, Vec3 out) {
		float vx = v.x, vy = v.y, vz = v.z;
		float qx = -x, qy = -y, qz = -z;
		float tx = 2f * (qy * vz - qz * vy);
		float ty = 2f * (qz * vx - qx * vz);
		float tz = 2f * (qx * vy - qy * vx);
		return out.set(
				vx + w * tx + (qy * tz - qz * ty),
				vy + w * ty + (qz * tx - qx * tz),
				vz + w * tz + (qx * ty - qy * tx));
	}

	public Vec3 forward(Vec3 out) {
		return rotate(0, 0, -1, out);
	}

	public Vec3 up(Vec3 out) {
		return rotate(0, 1, 0, out);
	}

	public Vec3 right(Vec3 out) {
		return rotate(1, 0, 0, out);
	}

	/** Applies a body-frame angular velocity (rad/s about local x,y,z) for dt seconds. */
	public Quat integrateBody(float wx, float wy, float wz, float dt) {
		float mag = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
		if (mag * dt < 1e-9f) return this;
		Quat d = new Quat().setAxisAngle(wx, wy, wz, mag * dt);
		return mulLocal(d).normalize();
	}

	/** Shortest-path spherical interpolation from this toward 'to' by t (0..1). */
	public Quat slerpTo(Quat to, float t) {
		float bx = to.x, by = to.y, bz = to.z, bw = to.w;
		float cos = x * bx + y * by + z * bz + w * bw;
		if (cos < 0f) {
			cos = -cos; bx = -bx; by = -by; bz = -bz; bw = -bw;
		}
		float s0, s1;
		if (cos > 0.9995f) {
			s0 = 1f - t; s1 = t;
		} else {
			float ang = (float) Math.acos(cos);
			float sin = (float) Math.sin(ang);
			s0 = (float) Math.sin((1f - t) * ang) / sin;
			s1 = (float) Math.sin(t * ang) / sin;
		}
		return set(s0 * x + s1 * bx, s0 * y + s1 * by, s0 * z + s1 * bz, s0 * w + s1 * bw).normalize();
	}

	/** Rotation that looks along 'fwd' with 'upHint' as up (fwd maps to local -Z). */
	public Quat setLookRotation(Vec3 fwd, Vec3 upHint) {
		Vec3 f = new Vec3(fwd).normalize();
		Vec3 r = new Vec3(f).cross(upHint);
		if (r.lengthSq() < 1e-8f) r.set(1, 0, 0);
		r.normalize();
		Vec3 u = new Vec3(r).cross(f).normalize();
		// Basis columns: right=r, up=u, back=-f
		float m00 = r.x, m01 = u.x, m02 = -f.x;
		float m10 = r.y, m11 = u.y, m12 = -f.y;
		float m20 = r.z, m21 = u.z, m22 = -f.z;
		float tr = m00 + m11 + m22;
		if (tr > 0f) {
			float s = (float) Math.sqrt(tr + 1f) * 2f;
			return set((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s).normalize();
		} else if (m00 > m11 && m00 > m22) {
			float s = (float) Math.sqrt(1f + m00 - m11 - m22) * 2f;
			return set(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s).normalize();
		} else if (m11 > m22) {
			float s = (float) Math.sqrt(1f + m11 - m00 - m22) * 2f;
			return set((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s).normalize();
		} else {
			float s = (float) Math.sqrt(1f + m22 - m00 - m11) * 2f;
			return set((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s).normalize();
		}
	}
}
