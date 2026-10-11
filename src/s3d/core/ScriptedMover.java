package s3d.core;

import s3d.math.Quat;
import s3d.math.Vec3;

/** Dumb target motion for the sandbox: flies a tilted circle at constant speed, nose along its path. */
public final class ScriptedMover {
	public final Vec3 centre;
	public final float radius, speed;
	private final Quat tilt;
	private float angle;

	public ScriptedMover(Vec3 centre, float radius, float speed, float tiltDeg) {
		this.centre = centre;
		this.radius = radius;
		this.speed = speed;
		this.tilt = new Quat().setAxisAngle(1, 0, 0.3f, (float) Math.toRadians(tiltDeg));
	}

	public void step(Ship3D s, float dt) {
		if (dt <= 0f) return;
		angle += speed / Math.max(1f, radius) * dt;
		Vec3 p = tilt.rotate((float) Math.cos(angle) * radius, 0f, (float) Math.sin(angle) * radius, new Vec3()).add(centre);
		Vec3 tangent = tilt.rotate((float) -Math.sin(angle), 0f, (float) Math.cos(angle), new Vec3());
		Vec3 up = tilt.rotate(0f, 1f, 0f, new Vec3());
		s.vel.set(tangent).scale(speed);
		s.pos.set(p);
		s.rot.setLookRotation(tangent, up);
		s.thrustVisual = 0.7f;
	}
}
