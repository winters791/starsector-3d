package s3d.core;

/** Per-frame pilot input for one ship (player or, later, AI). All axes -1..1. */
public final class ShipControls {
	/** +1 while throttle-up held, -1 while throttle-down held. */
	public float throttleAxis;
	public boolean allStop;
	public float strafeX, strafeY;
	/** +1 = roll left (Q), -1 = roll right (E). */
	public float roll;
	/** Virtual stick: +x = turn right, +y = nose up. */
	public float stickX, stickY;
	public boolean autoLevel;

	public void clearAxes() {
		throttleAxis = 0f;
		allStop = false;
		strafeX = strafeY = 0f;
		roll = 0f;
		stickX = stickY = 0f;
	}
}
