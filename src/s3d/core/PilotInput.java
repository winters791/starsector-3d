package s3d.core;

/** Raw input for one frame, filled by the host from whatever input system it has. */
public final class PilotInput {
	// Held
	public boolean throttleUp, throttleDown;
	public boolean strafeLeft, strafeRight, strafeUp, strafeDown;
	public boolean rollLeft, rollRight;
	public boolean freeLook;
	public boolean fire;
	/** Mouse buttons and Shift as held (the tactical view's selection and orders). */
	public boolean mouseLeft, mouseRight, shift;

	// Pressed this frame
	public boolean allStop, toggleAutoLevel, toggleTactical, togglePause;
	public boolean selectTarget, resetTargets, toggleIncoming;
	public boolean toggleShield, vent;
	/** Spectate on/off; cycle the watched ship (-1 / +1, 0 = none). */
	public boolean toggleSpectate;
	/** Ship system key (F) pressed. */
	public boolean useSystem;
	public int cycleView;
	/** Weapon group chosen this frame (0-based), or -1. */
	public int selectGroup = -1;
	/** Weapon group whose autofire is switched this frame (0-based; Ctrl/Cmd + number), or -1. */
	public int toggleAutofire = -1;

	/** Pixels, origin bottom-left. */
	public float mouseX, mouseY, screenW = 1, screenH = 1;
	/** Positive = wheel up (zoom in). */
	public float wheelNotches;

	public void clearPressed() {
		allStop = toggleAutoLevel = toggleTactical = togglePause = false;
		selectTarget = resetTargets = toggleIncoming = false;
		toggleShield = vent = false;
		toggleSpectate = false;
		useSystem = false;
		cycleView = 0;
		selectGroup = -1;
		toggleAutofire = -1;
		wheelNotches = 0f;
	}
}
