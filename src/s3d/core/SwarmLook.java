package s3d.core;

/**
 * How a ship's particle swarm looks: a Threat fragment swarm or a Shrouded shroud, after vanilla's
 * RoilingSwarmEffect / DwellerShroud parameters (the host fills these from the ship's live effect;
 * the defaults are vanilla's baseline). Distances and sizes are vanilla units, scaled with the hull.
 */
public final class SwarmLook {
	/** A shroud (dark crimson blobs that live a few seconds, dark particles round them) rather than fragments. */
	public boolean shroud;
	/** Pieces drawn: a shroud keeps this many; a fragment swarm draws one per fragment it has, up to this. */
	public int members = 50;
	/** Pieces sit up to this far out: round the hull's outline (a ship's fragment swarm) or from its centre. */
	public float maxOffset = 20f;
	public boolean aroundHull;
	/** A piece's size at full scale (each is 50-100% of it). */
	public float spriteSize = 20f;
	/** Each shroud piece lives lifeMin + up to lifeRange seconds, then comes back somewhere else. */
	public float lifeMin = 1f, lifeRange = 2f;
	public float[] color = {1f, 1f, 1f};
	/** Flashes: how many pieces a second, how many at once, the red glow's radius; colours (r, g, b, a). */
	public float flashPerSecond, flashRadius = 120f;
	public int flashCount = 1;
	public float[] flashFringe = {1f, 0f, 0f, 40f / 255f}, flashCore = {1f, 1f, 1f, 0.5f};
	/** The shroud's dark particles: a batch every ~0.1 s; area, size, a clear centre (vanilla units). */
	public int darkBatch = 11;
	public float darkArea = 1f, darkSize = 1f, darkClearCentre;
	public boolean darkHighContrast;

	/** Vanilla's Threat fragment swarm on a ship: pieces over the hull, out to half its radius (at most 100). */
	public static SwarmLook fragments(float collisionRadius) {
		SwarmLook l = new SwarmLook();
		l.members = 400;
		l.maxOffset = Math.min(100f, collisionRadius * 0.5f);
		l.aroundHull = true;
		return l;
	}

	/** An attack / construction swarm: the swarm is the whole thing, a ball of pieces. */
	public static SwarmLook swarm(int fragments, float radius) {
		SwarmLook l = new SwarmLook();
		l.members = fragments;
		l.maxOffset = radius;
		l.flashPerSecond = 2f;
		return l;
	}

	/** Vanilla's baseline shroud (DwellerShroud): 100 crimson blobs within 150, flashing their glow red. */
	public static SwarmLook shroud() {
		SwarmLook l = new SwarmLook();
		l.shroud = true;
		l.members = 100;
		l.maxOffset = 150f;
		l.spriteSize = 128f * 1.5f * 0.67f;
		l.color = new float[] {100f / 255f, 0f, 25f / 255f};
		l.flashPerSecond = 17f * 0.25f;
		l.flashCount = 2;
		l.flashRadius = 150f;
		l.flashFringe = new float[] {150f / 255f, 0f, 30f / 255f, 1f};
		l.flashCore = new float[] {1f, 0f, 0.25f, 1f};
		return l;
	}
}
