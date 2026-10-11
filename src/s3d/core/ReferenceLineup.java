package s3d.core;

/**
 * Parks reference ships side by side, broadside to the player, all at the same distance, so their
 * relative size reads directly (perspective makes ships at different distances impossible to compare).
 */
public final class ReferenceLineup {
	private final float depth, gap;
	private float cursorX;

	/**
	 * @param startX left edge of the lineup
	 * @param depth  distance in front of the origin (along -Z)
	 * @param gap    space between hulls
	 */
	public ReferenceLineup(float startX, float depth, float gap) {
		this.cursorX = startX;
		this.depth = depth;
		this.gap = gap;
	}

	/**
	 * Places the ship in the next slot: nose pointing +X, a little below eye level and rolled so its
	 * top faces the viewer (seen edge-on, every hull looks like a thin disc).
	 */
	public void place(Ship3D s) {
		float halfLength = Math.max(s.radius, 30f);
		s.pos.set(cursorX + halfLength, -depth * 0.15f, -depth);
		s.rot.setAxisAngle(0, 1, 0, (float) Math.toRadians(-90))
				.mulLocal(new s3d.math.Quat().setAxisAngle(0, 0, 1, (float) Math.toRadians(-30)));
		cursorX += 2f * halfLength + gap;
	}
}
