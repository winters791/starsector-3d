package s3d.core;

import s3d.math.Mat4;
import s3d.math.Quat;
import s3d.math.Vec3;

/**
 * Third-person camera. The chase pose is anchored to the ship's actual position every frame
 * (so there's no positional jitter) and only the orientation lags, which is what makes turns
 * readable. Free-look orbits around the ship; tactical view pulls far back and looks down at
 * the command view's focus (see {@link CommandView}), from its heading.
 */
public final class ChaseCamera {

	public final Vec3 pos = new Vec3();
	public final Quat rot = new Quat();
	public final Mat4 view = new Mat4();
	public final Mat4 proj = new Mat4();
	public final Mat4 viewProj = new Mat4();
	public float fovYRad;
	public float near = 2f, far = 120000f;
	public float aspect = 1.6f;

	public boolean tactical;
	public float zoomChase = 1f, zoomTactical = 1f;
	/** The command view's rig (focus, heading, orbit pitch), set by Sim; null = look at the ship from its heading. */
	CommandView rig;
	private float lastTacDist = 2600f;

	private final Quat chaseRot = new Quat();
	private final Quat tacRot = new Quat();
	private final Vec3 tacHeading = new Vec3(0, 0, -1);
	private float orbitYaw, orbitPitch;
	private float blend;
	private float speedFrac;
	private boolean initialized;
	/** Shake 0..1 (hits, blasts, recoil); decays by itself. */
	private float shake;
	private final java.util.Random shakeRnd = new java.util.Random();

	public void addShake(float amount) {
		shake = Math.min(1f, shake + Math.max(0f, amount));
	}

	/** Jitters position and aim by the current shake, scaled to the ship's size; then decays it. */
	private void applyShake(float size, float dt) {
		if (shake < 0.002f) return;
		float a = shake * shake;
		pos.add(new Vec3(shakeRnd.nextFloat() - 0.5f, shakeRnd.nextFloat() - 0.5f, shakeRnd.nextFloat() - 0.5f).scale(a * size * 0.12f));
		rot.mulLocal(new Quat().setAxisAngle(shakeRnd.nextFloat() - 0.5f, shakeRnd.nextFloat() - 0.5f, 0f, a * 0.02f));
		shake *= (float) Math.exp(-dt / 0.22f);
	}

	/** Glide from the last pose to the new ship's chase view: 1 at the switch, 0 when done. */
	private float glide;
	private float glideSeconds = 0.8f;
	/** Ease out (fast start, gentle settle) instead of ease in-out: the battle-start swoop. */
	private boolean glideEaseOut;
	private final Vec3 glideFromPos = new Vec3();
	private final Quat glideFromRot = new Quat();

	/**
	 * Like snapTo, but the camera flies from where it is now to the new ship's view over a moment
	 * (switching ships while spectating, taking over another ship) instead of cutting.
	 */
	public void glideTo(Ship3D s, float seconds) {
		boolean had = initialized;
		glideFromPos.set(pos);
		glideFromRot.set(rot);
		snapTo(s);
		if (!had || seconds <= 0f) return;
		glide = 1f;
		glideSeconds = seconds;
		glideEaseOut = false;
	}

	/**
	 * Battle start: the camera begins far behind and above the ship, looking at it, and swoops into
	 * the chase view over the given seconds.
	 */
	public void introTo(Ship3D s, float seconds) {
		snapTo(s);
		Vec3 f = s.rot.forward(new Vec3()), up = new Vec3(0f, 1f, 0f);
		float d = Math.max(300f, s.radius * 12f);
		glideFromPos.set(s.pos).addScaled(f, -d).addScaled(up, d * 0.5f);
		glideFromRot.setLookRotation(new Vec3(s.pos).sub(glideFromPos).normalize(), up);
		glide = 1f;
		glideSeconds = seconds;
		glideEaseOut = true;
	}

	public void snapTo(Ship3D s) {
		chaseRot.set(s.rot);
		s.rot.forward(tacHeading);
		tacHeading.y = 0f;
		if (tacHeading.lengthSq() < 1e-6f) tacHeading.set(0, 0, -1);
		tacHeading.normalize();
		orbitYaw = orbitPitch = 0f;
		blend = tactical ? 1f : 0f;
		initialized = true;
	}

	/**
	 * @param lookX,lookY mouse offset from screen centre, -1..1 (used only while free-looking)
	 */
	public void update(Ship3D s, Tuning t, float dt, boolean freeLook, float lookX, float lookY, float aspect) {
		if (!initialized) snapTo(s);
		dt = Math.max(dt, 0f);

		// Orientation lag (ship rotation shows on screen as the ship turning inside the frame).
		chaseRot.slerpTo(s.rot, alpha(dt, t.camRotLag));

		float yawTarget = freeLook ? -lookX * (float) Math.PI : 0f;
		float pitchTarget = freeLook ? lookY * 1.3f : 0f;
		float orbitTau = freeLook ? 0.07f : t.camFreeLookReturn;
		orbitYaw = FlightModel.smooth(orbitYaw, yawTarget, orbitTau, dt);
		orbitPitch = FlightModel.smooth(orbitPitch, pitchTarget, orbitTau, dt);

		float fwdFrac = s.params != null && s.params.fwdSpeed > 0f ? s.forwardSpeed() / s.params.fwdSpeed : 0f;
		speedFrac = FlightModel.smooth(speedFrac, FlightModel.clamp(fwdFrac, 0f, 1.2f), 0.6f, dt);

		// ---- chase pose ----
		Quat orbit = new Quat().setAxisAngle(0, 1, 0, orbitYaw)
				.mulLocal(new Quat().setAxisAngle(1, 0, 0, orbitPitch));
		Quat chaseView = new Quat().mul(chaseRot, orbit);
		// Sub-linear in hull size so bigger ships look bigger on screen (see Tuning.camSizeExp).
		float ref = Math.max(1f, t.camSizeRefRadius);
		float camR = ref * (float) Math.pow(Math.max(1f, s.radius) / ref, t.camSizeExp);
		float back = Math.max(t.camBackMin, camR * t.camBackRadii) * zoomChase
				+ speedFrac * camR * t.camSpeedPullbackRadii;
		back = Math.max(back, s.radius * 1.2f); // never inside the hull's bounding sphere
		float up = camR * t.camUpRadii * zoomChase;
		Vec3 chasePos = chaseView.rotate(0f, up, back, new Vec3()).add(s.pos);

		// ---- tactical pose: looking down at the command view's focus from its heading ----
		// (without a rig: heading-only, ignoring roll/pitch, looking down at the ship)
		Vec3 f = s.rot.forward(new Vec3());
		f.y = 0f;
		if (f.lengthSq() > 1e-4f) {
			f.normalize();
			tacHeading.lerp(f, alpha(dt, 0.6f)).normalize();
		}
		Vec3 heading = rig != null ? rig.heading(new Vec3()) : tacHeading;
		float pitch = rig != null ? rig.pitchOffset : orbitPitch * 0.5f;
		Quat tacTarget = new Quat().setLookRotation(heading, new Vec3(0, 1, 0))
				.mulLocal(new Quat().setAxisAngle(0, 1, 0, rig != null ? 0f : orbitYaw))
				.mulLocal(new Quat().setAxisAngle(1, 0, 0, -(float) Math.toRadians(t.camTacticalPitchDeg) + pitch));
		tacRot.set(tacTarget);
		float tacDist = Math.max(t.camTacticalDist, s.radius * 25f) * zoomTactical;
		lastTacDist = tacDist;
		if (rig != null && rig.follow != null) rig.focus.set(rig.follow.pos); // where it is this frame (it moved after the rig's update)
		Vec3 tacPos = tacRot.rotate(0f, 0f, tacDist, new Vec3()).add(rig != null ? rig.focus : s.pos);

		// ---- blend ----
		blend = FlightModel.smooth(blend, tactical ? 1f : 0f, t.camTransition, dt);
		float b = blend * blend * (3f - 2f * blend);
		pos.set(chasePos).lerp(tacPos, b);
		rot.set(chaseView).slerpTo(tacRot, b);
		if (glide > 0f) {
			glide = Math.max(0f, glide - dt / glideSeconds);
			float k = 1f - glide;
			k = glideEaseOut ? 1f - glide * glide * glide : k * k * (3f - 2f * k);
			Vec3 toPos = new Vec3(pos);
			Quat toRot = new Quat().set(rot);
			pos.set(glideFromPos).lerp(toPos, k);
			rot.set(glideFromRot).slerpTo(toRot, k);
		}
		applyShake(Math.max(20f, camR), dt);

		this.aspect = aspect;
		fovYRad = (float) Math.toRadians(t.camFovDeg);
		near = Math.max(1f, s.radius * 0.05f * (1f - b) + b * 10f);
		proj.perspective(fovYRad, aspect, near, far);
		view.view(rot, pos);
		viewProj.mul(proj, view);
	}

	/** World-space direction of the ray through normalised screen coords (-1..1). */
	public Vec3 rayDir(float ndcX, float ndcY, Vec3 out) {
		float tanY = (float) Math.tan(fovYRad * 0.5), tanX = tanY * aspect;
		Vec3 f = rot.forward(new Vec3()), r = rot.right(new Vec3()), u = rot.up(new Vec3());
		return out.set(f).addScaled(r, ndcX * tanX).addScaled(u, ndcY * tanY).normalize();
	}

	public void zoom(float wheelNotches, Tuning t) {
		float k = (float) Math.pow(0.9, wheelNotches);
		if (tactical) {
			zoomTactical = FlightModel.clamp(zoomTactical * k, 0.2f, 8f);
		} else {
			zoomChase = FlightModel.clamp(zoomChase * k, t.camZoomMin, t.camZoomMax);
		}
	}

	/** Camera distance of the tactical view (world units), as of the last update. */
	public float tacticalDistance() {
		return lastTacDist;
	}

	/** Screen pixels (origin bottom-left) of a world point; false if it's behind the camera. out = {x, y, depth}. */
	public boolean toScreen(Vec3 p, float w, float h, float[] out) {
		float[] clip = new float[4];
		viewProj.transform(p.x, p.y, p.z, clip);
		if (clip[3] <= 1e-4f) return false;
		out[0] = (clip[0] / clip[3] * 0.5f + 0.5f) * w;
		out[1] = (clip[1] / clip[3] * 0.5f + 0.5f) * h;
		out[2] = clip[3];
		return true;
	}

	/** How big a ship's bounding sphere looks on screen, in pixels. */
	public float screenRadius(Ship3D s, float screenH) {
		return s.radius * focalPx(screenH) / Math.max(1f, s.pos.distance(pos));
	}

	/** World units per screen pixel at the distance of a point (for dragging heights). */
	public float worldPerPixel(Vec3 at, float screenH) {
		return Math.max(1f, at.distance(pos)) / focalPx(screenH);
	}

	private float focalPx(float screenH) {
		return (screenH * 0.5f) / (float) Math.tan(fovYRad * 0.5);
	}

	/** 0 = chase view, 1 = tactical view (smoothed). */
	public float tacticalBlend() {
		return blend;
	}

	private static float alpha(float dt, float tau) {
		if (tau <= 0f) return 1f;
		return 1f - (float) Math.exp(-dt / tau);
	}
}
