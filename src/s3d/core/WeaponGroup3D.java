package s3d.core;

import java.util.ArrayList;
import java.util.List;

/** A vanilla weapon group: fired manually while selected, or by itself if autofire is on. */
public final class WeaponGroup3D {
	public final List<WeaponMount> mounts = new ArrayList<>();
	public boolean autofire;
	/** ALTERNATING groups fire one weapon at a time, evenly spaced; LINKED fire together. */
	public boolean alternating;

	int altIndex;
	float altWait;

	public boolean hasWorkingWeapon() {
		for (WeaponMount m : mounts) if (m.spec.supported) return true;
		return false;
	}

	/** Projectile speed of the group's first working weapon (for its lead pip). */
	public float leadSpeed() {
		for (WeaponMount m : mounts) if (m.spec.supported && Sim.shotSpeed(m.spec) > 0f) return Sim.shotSpeed(m.spec);
		return 0f;
	}

	public String label() {
		StringBuilder sb = new StringBuilder();
		for (WeaponMount m : mounts) {
			if (sb.indexOf(m.spec.name) >= 0) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(m.spec.name);
		}
		int n = mounts.size();
		return (n > 1 ? n + "x " : "") + sb;
	}
}
