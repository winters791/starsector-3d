package s3d.core;

/**
 * A Threat ship's fragment swarm as its weapons see it: how many fragments are about the ship. Fragment
 * weapons need enough of them to fire and fragment missiles are made of them; the swarm regrows to its
 * size over time (vanilla's FragmentSwarmHullmod numbers, set by the host). Not drawn yet.
 */
public final class FragmentSwarm {
	/** Fragments about the ship now (fractional while regrowing). */
	public float count;
	/** The size it regrows to; it can hold up to twice that (fragments a Defabrication Swarm brings back). */
	public final int size;
	/** Fragments regrown per second (none for fighters: their swarm only shrinks). */
	public final float regen;

	public FragmentSwarm(int size, float regen) {
		this.size = Math.max(0, size);
		this.regen = Math.max(0f, regen);
		this.count = this.size;
	}

	public int active() {
		return (int) count;
	}

	void spend(int n) {
		count = Math.max(0f, count - n);
	}

	void add(float n) {
		count = Math.min(size * 2f, count + n);
	}

	void step(float dt) {
		if (count < size) count = Math.min(size, count + regen * dt);
	}
}
