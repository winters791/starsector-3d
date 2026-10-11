package s3d.core;

/**
 * Freelancer-style virtual stick: the further the reticle is from screen centre, the faster
 * the ship turns toward it (up to the hull's rate). Deadzone + expo give fine aim near centre.
 */
public final class MouseSteering {

	private MouseSteering() {}

	/** Returns {stickX, stickY} in -1..1. Mouse coords are pixels with origin bottom-left. */
	public static float[] stick(float mouseX, float mouseY, float screenW, float screenH, Tuning t) {
		float radius = Math.max(1f, t.mouseFullDeflection * screenH);
		float dx = (mouseX - screenW * 0.5f) / radius;
		float dy = (mouseY - screenH * 0.5f) / radius;
		float mag = (float) Math.sqrt(dx * dx + dy * dy);
		if (mag < 1e-6f) return new float[] {0f, 0f};
		float clamped = Math.min(1f, mag);
		float dz = FlightModel.clamp(t.mouseDeadzone, 0f, 0.9f);
		float m = Math.max(0f, (clamped - dz) / (1f - dz));
		float e = FlightModel.clamp(t.mouseExpo, 0f, 1f);
		m = m * (1f - e) + m * m * m * e;
		float sx = dx / mag * m;
		float sy = dy / mag * m;
		if (t.invertPitch) sy = -sy;
		return new float[] {sx, sy};
	}

	/** Reticle position clamped to the full-deflection circle, for drawing. */
	public static float[] clampedReticle(float mouseX, float mouseY, float screenW, float screenH, Tuning t) {
		float radius = Math.max(1f, t.mouseFullDeflection * screenH);
		float cx = screenW * 0.5f, cy = screenH * 0.5f;
		float dx = mouseX - cx, dy = mouseY - cy;
		float mag = (float) Math.sqrt(dx * dx + dy * dy);
		if (mag > radius) {
			dx *= radius / mag;
			dy *= radius / mag;
		}
		return new float[] {cx + dx, cy + dy};
	}
}
