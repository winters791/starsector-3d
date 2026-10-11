package s3d.math;

import java.nio.FloatBuffer;

/** Column-major 4x4 float matrix, laid out the way glUniformMatrix4 expects. */
public final class Mat4 {
	public final float[] m = new float[16];

	public Mat4() {
		identity();
	}

	public Mat4 identity() {
		for (int i = 0; i < 16; i++) m[i] = 0f;
		m[0] = m[5] = m[10] = m[15] = 1f;
		return this;
	}

	public Mat4 set(Mat4 o) {
		System.arraycopy(o.m, 0, m, 0, 16);
		return this;
	}

	public Mat4 perspective(float fovYRadians, float aspect, float near, float far) {
		float f = 1f / (float) Math.tan(fovYRadians * 0.5);
		identity();
		m[0] = f / aspect;
		m[5] = f;
		m[10] = (far + near) / (near - far);
		m[11] = -1f;
		m[14] = 2f * far * near / (near - far);
		m[15] = 0f;
		return this;
	}

	public Mat4 ortho(float left, float right, float bottom, float top, float near, float far) {
		identity();
		m[0] = 2f / (right - left);
		m[5] = 2f / (top - bottom);
		m[10] = -2f / (far - near);
		m[12] = -(right + left) / (right - left);
		m[13] = -(top + bottom) / (top - bottom);
		m[14] = -(far + near) / (far - near);
		return this;
	}

	/** Rotation + translation: this = T(pos) * R(q). */
	public Mat4 fromRotationTranslation(Quat q, float px, float py, float pz) {
		float x = q.x, y = q.y, z = q.z, w = q.w;
		float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
		m[0] = 1 - 2 * (yy + zz); m[1] = 2 * (xy + wz);     m[2] = 2 * (xz - wy);     m[3] = 0;
		m[4] = 2 * (xy - wz);     m[5] = 1 - 2 * (xx + zz); m[6] = 2 * (yz + wx);     m[7] = 0;
		m[8] = 2 * (xz + wy);     m[9] = 2 * (yz - wx);     m[10] = 1 - 2 * (xx + yy); m[11] = 0;
		m[12] = px;               m[13] = py;               m[14] = pz;               m[15] = 1;
		return this;
	}

	/** Scales the rotation columns uniformly: this = this * S(s). */
	public Mat4 scaleLocal(float s) {
		for (int i = 0; i < 3; i++) {
			m[i] *= s;
			m[4 + i] *= s;
			m[8 + i] *= s;
		}
		return this;
	}

	/** View matrix for a camera at pos with orientation q (camera looks down its local -Z). */
	public Mat4 view(Quat q, Vec3 pos) {
		Quat inv = new Quat(q).conjugate();
		fromRotationTranslation(inv, 0, 0, 0);
		Vec3 t = inv.rotate(-pos.x, -pos.y, -pos.z, new Vec3());
		m[12] = t.x; m[13] = t.y; m[14] = t.z;
		return this;
	}

	/** this = a * b. Safe when this aliases a or b. */
	public Mat4 mul(Mat4 a, Mat4 b) {
		float[] r = new float[16];
		for (int c = 0; c < 4; c++) {
			for (int row = 0; row < 4; row++) {
				r[c * 4 + row] = a.m[row] * b.m[c * 4] + a.m[4 + row] * b.m[c * 4 + 1]
						+ a.m[8 + row] * b.m[c * 4 + 2] + a.m[12 + row] * b.m[c * 4 + 3];
			}
		}
		System.arraycopy(r, 0, m, 0, 16);
		return this;
	}

	/** Transforms (x,y,z,1); writes clip-space xyzw into out[0..3]. */
	public void transform(float x, float y, float z, float[] out) {
		out[0] = m[0] * x + m[4] * y + m[8] * z + m[12];
		out[1] = m[1] * x + m[5] * y + m[9] * z + m[13];
		out[2] = m[2] * x + m[6] * y + m[10] * z + m[14];
		out[3] = m[3] * x + m[7] * y + m[11] * z + m[15];
	}

	public FloatBuffer store(FloatBuffer fb) {
		fb.clear();
		fb.put(m);
		fb.flip();
		return fb;
	}
}
