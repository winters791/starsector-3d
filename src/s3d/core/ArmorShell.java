package s3d.core;

import java.util.Arrays;

/**
 * 3D take on Starsector's armor grid: the hull is wrapped in sectors by direction around the ship
 * (azimuth, 0 = nose) and by surface (top, side, underside). Like vanilla's cell kernel, a hit
 * reads armor from its sector plus neighbours (half weight) and strips armor from all of them.
 */
public final class ArmorShell {
	public static final int AZ = 12, EL = 3;
	private static final float NEIGHBOUR_WEIGHT = 0.5f;

	/** Armor rating of the hull (armor in each sector when undamaged). */
	public final float rating;
	/** Remaining fraction 0..1 per sector, index = el * AZ + az. */
	public final float[] frac = new float[AZ * EL];

	public ArmorShell(float rating) {
		this.rating = rating;
		repair();
	}

	public void repair() {
		Arrays.fill(frac, 1f);
	}

	/** Sector for a hit at local (x, z) (unscaled; forward is -Z) through surface class 0/1/2. */
	public static int sector(float x, float z, int surface) {
		double az = Math.atan2(x, -z); // 0 = nose, positive = toward starboard (+X)
		int a = (int) Math.floor((az + Math.PI) / (2 * Math.PI) * AZ) % AZ;
		if (a < 0) a += AZ;
		return Math.max(0, Math.min(EL - 1, surface)) * AZ + a;
	}

	/** Armor value (armor units) protecting this sector: weighted average of it and its neighbours. */
	public float armorAt(int sector) {
		int a0 = sector % AZ, e0 = sector / AZ;
		float sum = 0f, wsum = 0f;
		for (int de = -1; de <= 1; de++) {
			int e = e0 + de;
			if (e < 0 || e >= EL) continue;
			for (int da = -1; da <= 1; da++) {
				int a = Math.floorMod(a0 + da, AZ);
				float w = (de == 0 && da == 0) ? 1f : NEIGHBOUR_WEIGHT;
				sum += frac[e * AZ + a] * w;
				wsum += w;
			}
		}
		return rating * sum / wsum;
	}

	/**
	 * Removes armorDamage (armor units) around the sector with the same weights armorAt reads.
	 * armorAt treats each sector as holding rating / wsum, so the sector at weight w loses
	 * armorDamage * w / wsum of that, i.e. armorDamage * w / rating of its fraction. The reading
	 * there then drops by about 0.6x the damage (vanilla's cell kernel: about 0.8x).
	 */
	public void strip(int sector, float armorDamage) {
		if (rating <= 0f) return;
		int a0 = sector % AZ, e0 = sector / AZ;
		for (int de = -1; de <= 1; de++) {
			int e = e0 + de;
			if (e < 0 || e >= EL) continue;
			for (int da = -1; da <= 1; da++) {
				int i = e * AZ + Math.floorMod(a0 + da, AZ);
				float w = (de == 0 && da == 0) ? 1f : NEIGHBOUR_WEIGHT;
				frac[i] = Math.max(0f, frac[i] - armorDamage * w / rating);
			}
		}
	}

	public float averageFraction() {
		float s = 0f;
		for (float f : frac) s += f;
		return s / frac.length;
	}
}
