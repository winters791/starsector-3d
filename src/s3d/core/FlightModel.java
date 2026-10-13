package s3d.core;

import s3d.math.Quat;
import s3d.math.Vec3;

/**
 * Arcade 6DOF flight with flight assist: the ship's velocity is steered toward the velocity the
 * pilot is asking for (throttle along the nose, strafe sideways), limited by the hull's
 * acceleration/deceleration. Turning leaves some momentum behind (drift), which the
 * lateral damping bleeds off. Angular rates are rate-limited by the hull's turn stats.
 */
public final class FlightModel {

	private FlightModel() {}

	public static void step(Ship3D s, ShipControls c, Tuning t, float dt) {
		if (dt <= 0f) return;
		FlightParams p = s.params;
		// EMP: each lost engine costs thrust; with all of them out the ship drifts and barely turns.
		float eng = s.engineThrust();
		float thrust = eng >= 1f ? 1f : eng <= 0f ? 0f : 0.35f + 0.65f * eng;
		float turnK = (0.2f + 0.8f * thrust) * s.sysTurnMult; // ship systems change turning too
		float turnAccK = turnK * s.sysTurnAccelMult;
		float accK = thrust * s.sysAccelMult;

		// ---- throttle ----
		if (c.allStop) {
			s.throttle = 0f;
		} else if (c.throttleAxis != 0f) {
			s.throttle = clamp(s.throttle + c.throttleAxis * t.throttleRate * dt, -1f, 1f);
		}

		// ---- rotation ----
		float pitchDes = c.stickY * p.pitchRate;
		float yawDes = -c.stickX * p.yawRate;
		float rollDes = c.roll * p.rollRate;
		if (c.autoLevel && Math.abs(c.roll) < 0.01f) {
			rollDes = autoLevelRate(s.rot, p, t);
		}
		s.angVel.x = approach(s.angVel.x, pitchDes * turnK, p.pitchAccel * Math.max(0.2f, turnAccK) * dt);
		s.angVel.y = approach(s.angVel.y, yawDes * turnK, p.yawAccel * Math.max(0.2f, turnAccK) * dt);
		s.angVel.z = approach(s.angVel.z, rollDes * turnK, p.rollAccel * Math.max(0.2f, turnAccK) * dt);
		s.rot.integrateBody(s.angVel.x, s.angVel.y, s.angVel.z, dt);

		// ---- translation (in the body frame) ----
		Vec3 vb = s.rot.inverseRotate(s.vel, new Vec3());
		float fwdSpeed = (p.fwdSpeed * s.sysSpeedMult + (s.speedBonus + s.sysSpeedFlat) * t.speedMult) * Math.max(thrust, 0.0001f);
		float desZ = s.sysFullThrottle ? -fwdSpeed : s.throttle >= 0f ? -s.throttle * fwdSpeed : -s.throttle * p.revSpeed;
		if (s.sysBrake) desZ = 0f; // Convulsive Lunge: no thrust of its own, it decelerates (vanilla) while the lunge pulls
		boolean speedingUp = desZ * vb.z >= 0f && Math.abs(desZ) > Math.abs(vb.z);
		if (thrust > 0f) {
			vb.z = approachAxis(vb.z, desZ, p.fwdAccel * accK, p.fwdDecel * accK, dt);
			vb.x = approachAxis(vb.x, c.strafeX * p.strafeSpeed * thrust, p.strafeAccel * accK, p.lateralDecel * accK, dt);
			vb.y = approachAxis(vb.y, c.strafeY * p.verticalSpeed * thrust, p.verticalAccel * accK, p.verticalDecel * accK, dt);
		}
		s.rot.rotate(vb, s.vel);

		// Total speed never exceeds the hull's max speed (as in vanilla); bleed off excess smoothly.
		float speed = s.vel.length();
		float cap = Math.max(fwdSpeed, p.revSpeed);
		if (speed > cap && thrust > 0f) { // flamed out: no flight assist, the ship just drifts
			float target = Math.max(cap, speed - p.fwdDecel * dt);
			s.vel.scale(target / speed);
		}
		s.pos.addScaled(s.vel, dt);

		// ---- cosmetics ----
		float thrustTarget = (s.throttle > 0f ? 0.25f + 0.55f * s.throttle : 0.08f) + (speedingUp && desZ < 0f ? 0.4f : 0f);
		s.thrustVisual = smooth(s.thrustVisual, Math.min(1.2f, thrustTarget) * thrust, 0.15f, dt);
		float bankMax = (float) Math.toRadians(t.visualBankDeg);
		float yawFrac = p.yawRate > 0f ? s.angVel.y / p.yawRate : 0f;
		s.visualBank = smooth(s.visualBank, clamp(yawFrac, -1f, 1f) * bankMax, 0.2f, dt);
		s.strafeVisualX = smooth(s.strafeVisualX, c.strafeX, 0.1f, dt);
		s.strafeVisualY = smooth(s.strafeVisualY, c.strafeY, 0.1f, dt);
	}

	/**
	 * Roll rate that levels the wings against world up. Fades out when the nose points near
	 * straight up/down, where "level" is undefined.
	 */
	static float autoLevelRate(Quat rot, FlightParams p, Tuning t) {
		Vec3 f = rot.forward(new Vec3());
		Vec3 r = rot.right(new Vec3());
		Vec3 u = rot.up(new Vec3());
		float err = (float) Math.atan2(r.y, u.y); // >0 when the right wing is high
		float fade = clamp((0.92f - Math.abs(f.y)) / 0.3f, 0f, 1f);
		float mag = Math.abs(err);
		float rate = Math.min(p.rollRate, Math.min(t.autoLevelGain * mag * p.rollRate,
				(float) Math.sqrt(2f * p.rollAccel * mag) * 0.9f));
		return -Math.signum(err) * rate * fade;
	}

	/** Moves cur toward target using upRate when speeding up in the same direction, downRate otherwise. */
	static float approachAxis(float cur, float target, float upRate, float downRate, float dt) {
		boolean up = target * cur >= 0f && Math.abs(target) > Math.abs(cur);
		if (up) return approach(cur, target, upRate * dt);
		if (target * cur < 0f) {
			// Reversing: brake to zero first, then accelerate with whatever time is left.
			float stopTime = Math.abs(cur) / downRate;
			if (stopTime >= dt) return approach(cur, 0f, downRate * dt);
			return approach(0f, target, upRate * (dt - stopTime));
		}
		return approach(cur, target, downRate * dt);
	}

	public static float approach(float cur, float target, float maxDelta) {
		if (cur < target) return Math.min(target, cur + maxDelta);
		return Math.max(target, cur - maxDelta);
	}

	/** Frame-rate independent exponential smoothing with time constant tau (seconds). */
	public static float smooth(float cur, float target, float tau, float dt) {
		if (tau <= 0f) return target;
		return cur + (target - cur) * (1f - (float) Math.exp(-dt / tau));
	}

	public static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}
}
