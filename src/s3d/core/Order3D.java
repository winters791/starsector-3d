package s3d.core;

import s3d.math.Vec3;

/**
 * A standing order for an AI ship (from vanilla's command map; the host translates them). Null on
 * a ship = fight freely. Retreat is separate ({@link Ship3D#retreatOrdered}).
 */
public final class Order3D {
	public enum Kind {
		/** Stay with a friendly ship; fight only what comes near it. */
		ESCORT,
		/** Go to a point and hold there; fight what comes near it (vanilla defend and rally orders). */
		HOLD,
		/** Go to an objective and sit on it until it's ours; fight what comes near it. */
		CAPTURE,
		/** Go after one enemy ship. */
		ATTACK,
		/** Keep out of an area (round a point, or round a ship). */
		AVOID
	}

	public final Kind kind;
	/** HOLD, CAPTURE, AVOID: where (world). AVOID round a ship follows the ship. */
	public final Vec3 point = new Vec3();
	/** ESCORT: who to stay with; ATTACK: who to go after; AVOID: what to keep away from (or null). */
	public Ship3D ship;
	/** CAPTURE: the objective. */
	public Objective3D objective;
	/** HOLD and CAPTURE: how close to the point counts as there; AVOID: how far to keep. */
	public float radius;
	/** Vanilla's name for it (log and HUD). */
	public String label;

	public Order3D(Kind kind) {
		this.kind = kind;
	}

	/** Where this order wants the ship to be (ESCORT: the escorted ship; else the point). */
	public Vec3 anchor() {
		if ((kind == Kind.ESCORT || kind == Kind.AVOID) && ship != null) return ship.pos;
		if (kind == Kind.CAPTURE && objective != null) return objective.pos;
		return point;
	}

	/** Same order (so the AI keeps its plan when the host re-reads it). */
	public boolean sameAs(Order3D o) {
		return o != null && o.kind == kind && o.ship == ship && o.objective == objective && o.point.distance(point) < 50f;
	}

	@Override
	public String toString() {
		String what = ship != null ? ship.name : objective != null ? objective.name : String.format("(%.0f, %.0f)", point.x, point.z);
		return (label != null ? label : kind.name()) + " " + what;
	}
}
