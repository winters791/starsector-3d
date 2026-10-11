package s3d.math;

/** Mutable 3D vector. Methods mutate {@code this} and return it, so hot loops don't allocate. */
public final class Vec3 {
	public float x, y, z;

	public Vec3() {}

	public Vec3(float x, float y, float z) {
		this.x = x; this.y = y; this.z = z;
	}

	public Vec3(Vec3 o) {
		this(o.x, o.y, o.z);
	}

	public Vec3 set(float x, float y, float z) {
		this.x = x; this.y = y; this.z = z;
		return this;
	}

	public Vec3 set(Vec3 o) {
		return set(o.x, o.y, o.z);
	}

	public Vec3 add(Vec3 o) {
		x += o.x; y += o.y; z += o.z;
		return this;
	}

	public Vec3 addScaled(Vec3 o, float s) {
		x += o.x * s; y += o.y * s; z += o.z * s;
		return this;
	}

	public Vec3 sub(Vec3 o) {
		x -= o.x; y -= o.y; z -= o.z;
		return this;
	}

	public Vec3 scale(float s) {
		x *= s; y *= s; z *= s;
		return this;
	}

	public float dot(Vec3 o) {
		return x * o.x + y * o.y + z * o.z;
	}

	/** this = this x o */
	public Vec3 cross(Vec3 o) {
		return set(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
	}

	public float length() {
		return (float) Math.sqrt(x * x + y * y + z * z);
	}

	public float lengthSq() {
		return x * x + y * y + z * z;
	}

	public Vec3 normalize() {
		float l = length();
		if (l > 1e-12f) scale(1f / l);
		return this;
	}

	public float distance(Vec3 o) {
		float dx = x - o.x, dy = y - o.y, dz = z - o.z;
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public Vec3 lerp(Vec3 to, float t) {
		x += (to.x - x) * t; y += (to.y - y) * t; z += (to.z - z) * t;
		return this;
	}

	@Override
	public String toString() {
		return String.format("(%.2f, %.2f, %.2f)", x, y, z);
	}
}
