package s3d.core;

import s3d.math.Quat;
import s3d.math.Vec3;

/**
 * A weapon on a slot. Position and rest direction are in the ship's unscaled local frame; the
 * firing arc becomes a cone of half-angle arc/2 around the rest direction (a 360 degree turret
 * covers the whole sphere, which is fine because a ship's own shots pass through its hull).
 */
public final class WeaponMount {
	public final String slotId;
	public final WeaponSpec3D spec;
	public final Vec3 localPos;
	public final Vec3 restDir;
	public final float halfArcRad;
	public final boolean hardpoint;

	/** Current barrel direction, ship-local unit vector. */
	public final Vec3 aimDir;

	// Component health (EMP): disabled weapons can't fire or turn until repaired.
	public float hp = 100f, maxHp = 100f, disabledFor;
	// Ion beam / tachyon lance arc timer
	float arcTimer;

	// Firing state
	public float cooldownLeft, chargeLeft = -1f, burstLeftTime;
	public int burstShotsLeft;
	public float spread;
	public float ammo;
	public float flash;
	public boolean firedThisFrame;

	// Beam state: level ramps 0..1 over charge-up, holds while firing, ramps down after.
	public float beamLevel, beamSustainLeft;
	public boolean beamFiring;
	public final Vec3 beamEnd = new Vec3();
	public boolean beamHitting;
	float beamDamageAccum, beamTextTimer;

	/** Render-side models (ShipModel.Source / ShipModel), owned by the renderer. */
	public Object modelSource, model;
	/** Back-reference for the host (the vanilla weapon). Never read by core code. */
	public Object hostRef;
	public Object gunModelSource, gunModel;

	public WeaponMount(String slotId, WeaponSpec3D spec, Vec3 localPos, Vec3 restDir, float arcDeg, boolean hardpoint) {
		this.slotId = slotId;
		this.spec = spec;
		this.localPos = localPos;
		this.restDir = new Vec3(restDir).normalize();
		this.halfArcRad = (float) Math.toRadians(Math.min(360f, Math.max(0f, arcDeg)) * 0.5f);
		this.hardpoint = hardpoint;
		this.aimDir = new Vec3(this.restDir);
		this.spread = spec.minSpread;
		this.ammo = spec.maxAmmo;
	}

	/** World position of the mount. */
	public Vec3 worldPos(Ship3D s, Vec3 out) {
		return s.rot.rotate(localPos.x * s.scale, localPos.y * s.scale, localPos.z * s.scale, out).add(s.pos);
	}

	/** Orientation of the barrel in world space (forward = barrel). */
	public Quat worldRot(Ship3D s, Quat out) {
		Quat local = new Quat().setLookRotation(aimDir, Math.abs(aimDir.y) > 0.99f ? new Vec3(0, 0, -1) : new Vec3(0, 1, 0));
		return out.mul(s.rot, local);
	}

	public boolean ready() {
		return spec.supported && cooldownLeft <= 0f && burstShotsLeft == 0 && !beamFiring && beamLevel <= 0f
				&& (spec.maxAmmo <= 0 || ammo >= 1f);
	}

	public void resetState() {
		hp = maxHp;
		disabledFor = 0f;
		cooldownLeft = 0f;
		chargeLeft = -1f;
		burstShotsLeft = 0;
		beamLevel = 0f;
		beamFiring = false;
		beamHitting = false;
		ammo = spec.maxAmmo;
		spread = spec.minSpread;
	}

	/** 0..1 readiness for the HUD (1 = ready). */
	public float readiness() {
		if (!spec.supported) return 0f;
		if (burstShotsLeft > 0) return 0f;
		if (spec.cooldown <= 0f) return 1f;
		return 1f - Math.max(0f, Math.min(1f, cooldownLeft / spec.cooldown));
	}
}
