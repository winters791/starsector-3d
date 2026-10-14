package s3d.core;

/**
 * Hull movement stats as Starsector defines them (2D, after hullmods/skills when read from a live ship),
 * plus the 3D values derived from them by {@link #derive(Tuning)}.
 */
public final class FlightParams {
	// Source stats: units/s, units/s^2, deg/s, deg/s^2
	public float maxSpeed, accel, decel, maxTurnRateDeg, turnAccelDeg;

	// Derived 3D values (rad/s, rad/s^2, units/s, units/s^2)
	public float pitchRate, yawRate, rollRate;
	public float pitchAccel, yawAccel, rollAccel;
	public float fwdSpeed, revSpeed, strafeSpeed, verticalSpeed;
	public float fwdAccel, fwdDecel, strafeAccel, lateralDecel, verticalAccel, verticalDecel;

	public FlightParams(float maxSpeed, float accel, float decel, float maxTurnRateDeg, float turnAccelDeg) {
		this.maxSpeed = maxSpeed;
		this.accel = accel;
		this.decel = decel;
		this.maxTurnRateDeg = maxTurnRateDeg;
		this.turnAccelDeg = turnAccelDeg;
	}

	/** True if the source stats differ (ignoring float noise), i.e. a refresh changed something. */
	/** Field-wise minimum of the source stats with a ceiling (temporary vanilla boosts can't exceed it). */
	public FlightParams cappedBy(FlightParams cap) {
		if (cap == null) return this;
		return new FlightParams(Math.min(maxSpeed, cap.maxSpeed), Math.min(accel, cap.accel), Math.min(decel, cap.decel),
				Math.min(maxTurnRateDeg, cap.maxTurnRateDeg), Math.min(turnAccelDeg, cap.turnAccelDeg));
	}

	public boolean sameSource(FlightParams o) {
		return o != null && Math.abs(maxSpeed - o.maxSpeed) < 0.01f && Math.abs(accel - o.accel) < 0.01f
				&& Math.abs(decel - o.decel) < 0.01f && Math.abs(maxTurnRateDeg - o.maxTurnRateDeg) < 0.01f
				&& Math.abs(turnAccelDeg - o.turnAccelDeg) < 0.01f;
	}

	public FlightParams derive(Tuning t) {
		float base = Math.max(0.5f, maxTurnRateDeg);
		float ref = Math.max(1f, t.turnCurveRef);
		float man = t.maneuverMult; // flat manoeuvrability bonus: turning and acceleration, not top speed
		float turnDeg = ref * (float) Math.pow(base / ref, t.turnCurveExp) * t.turnRateMult * man;
		// Keep the hull's spin-up time (rate / accel), then let tuning make it snappier.
		float spinUp = base / Math.max(0.5f, turnAccelDeg);
		float turnAccDeg = turnDeg / spinUp * t.turnAccelMult; // turnDeg carries the bonus already: spin-up time stays the hull's

		pitchRate = rad(turnDeg * t.pitchRateMult);
		yawRate = rad(turnDeg * t.yawRateMult);
		rollRate = rad(Math.max(turnDeg * t.rollRateMult, t.rollRateMinDeg * man));
		pitchAccel = rad(turnAccDeg * t.pitchRateMult);
		yawAccel = rad(turnAccDeg * t.yawRateMult);
		rollAccel = rollRate / spinUp * t.turnAccelMult;

		fwdSpeed = maxSpeed * t.speedMult;
		revSpeed = fwdSpeed * t.reverseSpeedMult;
		strafeSpeed = fwdSpeed * t.strafeSpeedMult;
		fwdAccel = Math.max(1f, accel * t.accelMult * man);
		fwdDecel = Math.max(1f, decel * t.decelMult * man);
		strafeAccel = Math.max(1f, accel * t.accelMult * t.strafeAccelMult * man);
		lateralDecel = Math.max(1f, decel * t.decelMult * t.lateralDampMult * man);
		verticalSpeed = strafeSpeed * t.verticalMult;
		verticalAccel = strafeAccel * t.verticalMult;
		verticalDecel = lateralDecel * t.verticalMult;
		return this;
	}

	private static float rad(float deg) {
		return deg * (float) Math.PI / 180f;
	}

	@Override
	public String toString() {
		String s = String.format("speed %.0f accel %.0f decel %.0f turn %.0f deg/s turnAccel %.0f",
				maxSpeed, accel, decel, maxTurnRateDeg, turnAccelDeg);
		if (yawRate > 0f) {
			s += String.format(" (3D: yaw %.1f pitch %.1f roll %.1f deg/s)",
					Math.toDegrees(yawRate), Math.toDegrees(pitchRate), Math.toDegrees(rollRate));
		}
		return s;
	}
}
