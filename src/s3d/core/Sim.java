package s3d.core;

import java.util.ArrayList;
import java.util.List;

import s3d.math.Quat;
import s3d.math.Vec3;

/** The 3D battle state and its per-frame update. Knows nothing about Starsector. */
public final class Sim {
	public Tuning tuning;
	public final List<Ship3D> ships = new ArrayList<>();
	public Ship3D player;
	public final ChaseCamera camera = new ChaseCamera();
	public final ShipControls controls = new ShipControls();
	public final Combat combat = new Combat();
	public boolean paused;
	public float time;

	/** Last stick values, for the HUD. */
	public float stickX, stickY;
	public boolean steeringActive;

	/** Sandbox: enemy missile launchers fire at the player (B). */
	public boolean incomingFire;

	/** 1 normally; below 1 while your own ship's time runs faster (phase, temporal shell). */
	public float worldTimeScale = 1f;

	/** Battle area half-size (x, z) for the minimap, 0 = unknown (the map then frames the ships around you). */
	public float arenaHalfX, arenaHalfZ;

	/** Centre of each side's live ships (owner 0 / 1), updated every frame; count 0 = none. */
	private final Vec3[] fleetCentres = {new Vec3(), new Vec3()};
	private final int[] fleetCounts = new int[2];

	/** Centre of a side's live ships, or null if it has none. */
	public Vec3 fleetCentre(int owner) {
		int i = owner == 0 ? 0 : 1;
		return fleetCounts[i] > 0 ? fleetCentres[i] : null;
	}

	/** Altitude the fight is happening at (mean of all live ships). */
	public float fightAltitude() {
		int n = fleetCounts[0] + fleetCounts[1];
		return n > 0 ? (fleetCentres[0].y * fleetCounts[0] + fleetCentres[1].y * fleetCounts[1]) / n : 0f;
	}

	private void updateFleetCentres() {
		fleetCentres[0].set(0f, 0f, 0f);
		fleetCentres[1].set(0f, 0f, 0f);
		fleetCounts[0] = fleetCounts[1] = 0;
		for (Ship3D s : ships) {
			if (s.destroyed || s.fighter) continue; // fighters swarm; the fleet is where its ships are
			int i = s.owner == 0 ? 0 : 1;
			fleetCentres[i].add(s.pos);
			fleetCounts[i]++;
		}
		for (int i = 0; i < 2; i++) if (fleetCounts[i] > 0) fleetCentres[i].scale(1f / fleetCounts[i]);
	}

	/**
	 * Spectating: your ship is flown by an AI pilot while the camera follows any ship on your side
	 * (viewShip; your own included) or flies free.
	 */
	public boolean spectating, freeCam;
	public Ship3D viewShip;
	private Ship3D spectatorPilot;

	/** Ship the camera and HUD are about: the watched ship while spectating, else yours. */
	public Ship3D viewer() {
		return spectating && viewShip != null ? viewShip : player;
	}

	/** What the watched ship is after (its AI's target, or your lock if it's yours). */
	public Ship3D viewTarget() {
		Ship3D v = viewer();
		if (v == null) return null;
		Ship3D t = v.ai != null ? v.ai.target : target;
		return t != null && !t.destroyed && ships.contains(t) ? t : null;
	}

	public void setSpectating(boolean on) {
		if (on == spectating) return;
		spectating = on;
		freeCam = false;
		if (on) {
			viewShip = player;
			target = autoTarget = aimTarget = null;
			hasLead = assistLocked = false;
			ensureSpectatorPilot();
		} else {
			if (spectatorPilot != null && spectatorPilot == player) player.ai = null;
			spectatorPilot = null;
			viewShip = null;
			if (player != null) camera.snapTo(player);
		}
	}

	/** While spectating your ship needs a pilot (also after command passes to another ship). */
	private void ensureSpectatorPilot() {
		if (player == null || player.destroyed || player.ai != null) return;
		player.ai = new ShipAI3D(97);
		player.ai.homeDir.set(0f, 0f, player.owner == 0 ? 1f : -1f);
		spectatorPilot = player;
	}

	/** Next / previous ship on your side (not fighters) for the spectator camera. */
	public void cycleView(int dir) {
		List<Ship3D> mine = new ArrayList<>();
		for (Ship3D s : ships) if (player != null && s.owner == player.owner && !s.fighter && !s.destroyed && !s.fixedPart()) mine.add(s);
		if (mine.isEmpty()) return;
		int i = mine.indexOf(viewShip);
		viewShip = mine.get(Math.floorMod((i < 0 ? 0 : i + dir), mine.size()));
		freeCam = false;
		camera.snapTo(viewShip);
	}

	/** Locked target (R), or null. */
	public Ship3D target;
	/** Enemy nearest the reticle when nothing is locked; gets a lead pip automatically. */
	public Ship3D autoTarget;
	/** target if locked, else autoTarget. */
	public Ship3D aimTarget;
	/** Selected group's lead point on aimTarget. */
	public final Vec3 leadPoint = new Vec3();
	public boolean hasLead, leadInRange;
	/** Reticle is inside the lead circle: the selected group's turrets aim at the lead point. */
	public boolean assistLocked;
	public final Vec3 aimRay = new Vec3();
	private Ship3D lastLockTarget;
	/** Where the player's weapons are converging this frame (world), or null. */
	public final Vec3 aimPoint = new Vec3();
	public boolean hasAimPoint;

	public Sim(Tuning tuning) {
		this.tuning = tuning;
		controls.autoLevel = tuning.autoLevelDefault;
	}

	public void setTuning(Tuning t) {
		tuning = t;
		for (Ship3D s : ships) {
			if (s.params != null) s.params.derive(t);
			s.applyScale(scaleFor(s));
		}
	}

	/** Add a module after its parent: it takes the parent's scale so the structure fits together. */
	public void addShip(Ship3D s) {
		if (s.params != null) s.params.derive(tuning);
		s.applyScale(scaleFor(s));
		ships.add(s);
		if (s.parent != null) attach(s);
	}

	private float scaleFor(Ship3D s) {
		return s.parent != null ? s.root().scale : tuning.scaleFor(s.sizeClass);
	}

	public void setPlayer(Ship3D s) {
		player = s;
		ensureGroups(s);
		camera.snapTo(s);
		if (target == s) target = null;
	}

	/** Ships without vanilla groups get one manual group; stray mounts go into an autofire group. */
	static void ensureGroups(Ship3D s) {
		java.util.Set<WeaponMount> grouped = new java.util.HashSet<>();
		for (WeaponGroup3D g : s.groups) grouped.addAll(g.mounts);
		WeaponGroup3D extra = new WeaponGroup3D();
		extra.autofire = !s.groups.isEmpty();
		for (WeaponMount m : s.weapons) if (!grouped.contains(m)) extra.mounts.add(m);
		if (!extra.mounts.isEmpty()) s.groups.add(extra);
		if (s.selectedGroup < 0 || s.selectedGroup >= s.groups.size()) s.selectedGroup = 0;
	}

	/** Repairs every ship (you included), refills ammo and clears shots and missiles. */
	public void resetTargets() {
		for (Ship3D s : ships) {
			s.resetDamage();
			for (WeaponMount m : s.weapons) m.resetState();
		}
		combat.clear();
	}

	/**
	 * @param dt  simulation seconds this frame (0 when the game is paused)
	 * @param realDt wall-clock seconds, used for the camera so free-look works while paused
	 */
	public void update(PilotInput in, float dt, float realDt) {
		if (in.togglePause) paused = !paused;
		if (in.toggleAutoLevel) controls.autoLevel = !controls.autoLevel;
		if (in.toggleSpectate) setSpectating(!spectating);
		if (spectating) {
			ensureSpectatorPilot();
			if (viewShip == null || !ships.contains(viewShip)) cycleView(1);
			if (in.cycleView != 0) cycleView(in.cycleView);
		}
		if (in.toggleTactical) {
			if (spectating) {
				freeCam = !freeCam; // T: free camera <-> follow the watched ship
				if (freeCam) camera.beginFree();
				else if (viewShip != null) camera.snapTo(viewShip);
			} else {
				camera.tactical = !camera.tactical;
			}
		}
		if (in.wheelNotches != 0f) camera.zoom(in.wheelNotches, tuning);
		if (in.resetTargets) resetTargets();
		if (in.toggleIncoming) incomingFire = !incomingFire;
		combat.world = ships;
		combat.numbersFor = player;
		combat.tuning = tuning;
		combat.clock = time;
		float simDt = paused ? 0f : Math.min(dt, 0.1f);

		controls.clearAxes();
		controls.throttleAxis = (in.throttleUp ? 1f : 0f) - (in.throttleDown ? 1f : 0f);
		controls.allStop = in.allStop;
		controls.strafeX = (in.strafeRight ? 1f : 0f) - (in.strafeLeft ? 1f : 0f);
		controls.strafeY = (in.strafeUp ? 1f : 0f) - (in.strafeDown ? 1f : 0f);
		controls.roll = (in.rollLeft ? 1f : 0f) - (in.rollRight ? 1f : 0f);

		steeringActive = !in.freeLook && !camera.tactical;
		if (steeringActive) {
			float[] st = MouseSteering.stick(in.mouseX, in.mouseY, in.screenW, in.screenH, tuning);
			controls.stickX = st[0];
			controls.stickY = st[1];
		}
		stickX = controls.stickX;
		stickY = controls.stickY;

		updateFleetCentres();
		for (Ship3D s : ships) if (!s.destroyed) s.stepRepairs(simDt);
		stepSystems(in, simDt);
		// Your phase cloak / temporal shell slows the rest of the battle instead of speeding you up (as in
		// vanilla): world time runs at 1/timeMult and your ship at normal speed. AI ships with the same
		// systems simply run faster than the world.
		worldTimeScale = player != null && !player.destroyed && !spectating && player.timeMult > 1f ? 1f / player.timeMult : 1f;
		simDt *= worldTimeScale;
		stepFlux(in, simDt);
		if (player != null && !player.destroyed && !spectating) FlightModel.step(player, controls, tuning, simDt * player.timeMult);
		for (Ship3D s : ships) {
			if (s == player && !s.destroyed && !spectating) continue;
			if (s.destroyed) {
				// Wreck: keeps drifting, tumbles slowly.
				s.pos.addScaled(s.vel, simDt);
				s.rot.integrateBody(s.angVel.x, s.angVel.y, s.angVel.z, simDt);
			} else if (s.ai != null) {
				s.ai.step(this, s, simDt * s.timeMult); // temporal shell / phase: the ship's own time runs faster
			} else if (s.mover != null) {
				s.mover.step(s, simDt);
			}
		}
		placeFixedParts(simDt);
		time += simDt;
		if (tuning.collisions) resolveCollisions(simDt);
		for (Ship3D s : ships) s.trackAcceleration(simDt);

		if (spectating && viewer() != null) {
			float lookX = (in.mouseX - in.screenW * 0.5f) / (in.screenW * 0.5f);
			float lookY = (in.mouseY - in.screenH * 0.5f) / (in.screenH * 0.5f);
			if (freeCam) camera.updateFree(in, tuning, Math.min(realDt, 0.1f), in.screenW / in.screenH);
			else camera.update(viewer(), tuning, Math.min(realDt, 0.1f), in.freeLook, lookX, lookY, in.screenW / in.screenH);
		} else if (player != null) {
			float lookX = (in.mouseX - in.screenW * 0.5f) / (in.screenW * 0.5f);
			float lookY = (in.mouseY - in.screenH * 0.5f) / (in.screenH * 0.5f);
			camera.update(player, tuning, Math.min(realDt, 0.1f), in.freeLook, lookX, lookY, in.screenW / in.screenH);

			if (in.selectTarget) target = pickTarget(in);
			if (in.selectGroup >= 0 && in.selectGroup < player.groups.size()) player.selectedGroup = in.selectGroup;
			if (target != null && (target.destroyed || !ships.contains(target))) target = null;
			updateAiming(in);
			if (!player.destroyed) stepPlayerWeapons(in.fire && !camera.tactical, simDt * player.timeMult);
		}
		if (incomingFire) stepIncomingFire(simDt);
		combat.stepProjectiles(ships, simDt);
		combat.stepMissiles(ships, simDt);
		combat.stepEffects(simDt);
		camera.addShake(combat.shake * tuning.cameraShake);
		combat.shake = 0f;
		for (Ship3D s : ships) {
			if (s.hitFlash > 0f) s.hitFlash = Math.max(0f, s.hitFlash - simDt);
		}
	}

	/**
	 * Ship systems: your F (system) and right click (phase cloak, on phase ships), then every ship's
	 * systems advance and set this frame's modifiers; flux and shields pick up what they block.
	 */
	private void stepSystems(PilotInput in, float dt) {
		if (player != null && !player.destroyed && !spectating) {
			if (in.useSystem && player.system != null) player.system.activate(player);
			if (in.toggleShield && player.cloak != null) {
				player.cloak.activate(player);
				in.toggleShield = false; // phase ships have no shield; right click is the cloak
			}
		}
		for (Ship3D s : ships) {
			s.resetSystemEffects();
			if (s.destroyed) continue;
			if (s.system != null) {
				s.system.step(s, this, dt);
				systemSounds(s, s.system);
			}
			if (s.cloak != null) {
				s.cloak.step(s, this, dt);
				systemSounds(s, s.cloak);
			}
			if (s.flux != null) {
				s.flux.noDissipation = s.sysNoDissipation;
				s.flux.forceUp = s.sysForceShield;
				s.flux.blocked = s.sysNoShield || s.phased;
			}
		}
	}

	private void systemSounds(Ship3D s, ShipSystem3D sys) {
		SoundEvents snd = combat.sounds;
		if (sys.justUsed) snd.play(sys.useSound, s.pos, 1f);
		if (sys.justStopped) snd.play(sys.offSound, s.pos, 1f);
		sys.justUsed = sys.justStopped = false;
		if (sys.on()) snd.loop(sys.loopSound, sys, s.pos, sys.effect, 1f);
	}

	/**
	 * Flux, shields and venting for every ship. Omni shields turn toward incoming fire, else toward
	 * the ship's target (for you: the aim target; while free-looking, wherever the camera looks).
	 */
	private void stepFlux(PilotInput in, float dt) {
		if (player != null && player.flux != null && !player.destroyed) {
			if (in.toggleShield && player.flux.hasShield() && !player.flux.disabled()) {
				combat.sounds.play(player.flux.shieldOn ? "shield_lower" : "shield_raise", player.pos, 0.8f);
			}
			if (in.toggleShield) player.flux.toggleShield();
			if (in.vent && !player.flux.venting && !player.flux.disabled() && player.flux.flux > 0.5f) combat.sounds.play("vent_flux", player.pos, 1f);
			if (in.vent) player.flux.vent();
		}
		Ship3D heard = viewer();
		if (heard != null && !heard.destroyed) {
			combat.sounds.loop("engine_loop", heard, heard.pos, 0.25f + 0.5f * Math.min(1f, heard.thrustVisual), 0.8f + 0.4f * Math.min(1f, heard.thrustVisual));
		}
		Vec3 want = new Vec3();
		for (Ship3D s : ships) {
			ShieldFlux f = s.flux;
			if (f == null || s.destroyed) {
				s.speedBonus = 0f;
				continue;
			}
			Vec3 desired = null;
			if (f.type == ShieldFlux.Type.OMNI) {
				if (s == player && in.freeLook) {
					camera.rot.forward(want);
				} else if (threat(s, want) <= 0f) {
					Ship3D t = s == player ? aimTarget : s.ai != null ? s.ai.target : null;
					if (t != null) want.set(t.pos).sub(s.pos);
					else s.rot.forward(want);
				}
				desired = s.rot.inverseRotate(want, new Vec3());
			}
			f.step(dt, desired, tuning);
			s.speedBonus = f.zeroFluxBoostActive() ? f.zeroFluxBoost : 0f;
		}
	}

	/**
	 * Incoming fire about to reach this ship: enemy shots and missiles on a course passing within
	 * its shield/hull radius soon, and enemy beams on it. Returns the damage-weighted amount and
	 * writes the direction it's coming from (world).
	 */
	public float threat(Ship3D s, Vec3 dirOut) {
		dirOut.set(0f, 0f, 0f);
		float total = 0f;
		float reach = Math.max(s.radius, s.flux != null ? s.flux.radius * s.scale : 0f) * 1.2f;
		Vec3 rel = new Vec3(), rv = new Vec3();
		for (Projectile p : combat.projectiles) {
			if (p.source == null || p.source.owner == s.owner) continue;
			total += incoming(s, p.pos, p.vel, p.spec.damage * p.spec.shieldMult, 1.2f, reach, rel, rv, dirOut);
		}
		for (Missile3D m : combat.missiles) {
			if (m.owner == s.owner || m.flamedOut()) continue;
			total += incoming(s, m.pos, m.vel, m.spec.damage * m.spec.shieldMult, 2.5f, reach, rel, rv, dirOut);
		}
		for (Ship3D o : ships) {
			if (o.owner == s.owner || o.destroyed) continue;
			for (WeaponMount m : o.weapons) {
				if (!m.beamHitting || m.beamEnd.distance(s.pos) > reach * 1.3f) continue;
				float w = m.spec.dps * m.spec.shieldMult * m.beamLevel;
				m.worldPos(o, rel).sub(s.pos);
				float len = rel.length();
				if (len > 1e-3f) dirOut.addScaled(rel, w / len);
				total += w;
			}
		}
		return total;
	}

	private static float incoming(Ship3D s, Vec3 pos, Vec3 vel, float weight, float window, float reach, Vec3 rel, Vec3 rv, Vec3 dirOut) {
		rel.set(pos).sub(s.pos);
		rv.set(vel).sub(s.vel);
		float v2 = rv.lengthSq();
		if (v2 < 1f) return 0f;
		float tca = -rel.dot(rv) / v2;
		if (tca < 0f || tca > window) return 0f;
		float cx = rel.x + rv.x * tca, cy = rel.y + rv.y * tca, cz = rel.z + rv.z * tca;
		if (cx * cx + cy * cy + cz * cz > reach * reach) return 0f;
		float w = weight * (1f - 0.5f * tca / window);
		float len = rel.length();
		if (len > 1e-3f) dirOut.addScaled(rel, w / len);
		return w;
	}

	// ---- stations and modules ----

	/** Station cores stay put and turn slowly about the vertical; modules ride on their parent. */
	private void placeFixedParts(float dt) {
		Quat spin = new Quat();
		for (Ship3D s : ships) {
			if (!s.anchored) continue;
			s.vel.set(0f, 0f, 0f);
			s.angVel.set(0f, 0f, 0f);
			if (s.spinRate != 0f && dt > 0f) s.rot.mul(spin.setAxisAngle(0f, 1f, 0f, s.spinRate * dt), s.rot).normalize();
		}
		for (Ship3D s : ships) if (s.parent != null) attach(s);
	}

	/**
	 * Puts a module where it belongs on its parent, moving with it (a spinning station's modules get
	 * the speed of the spin, so shots can lead them).
	 */
	public static void attach(Ship3D m) {
		Ship3D p = m.parent;
		Vec3 r = p.rot.rotate(m.attachLocal.x * p.scale, m.attachLocal.y * p.scale, m.attachLocal.z * p.scale, new Vec3());
		m.pos.set(p.pos).add(r);
		m.rot.mul(p.rot, m.attachRot).normalize();
		m.vel.set(p.vel);
		if (p.anchored && p.spinRate != 0f) m.vel.add(new Vec3(0f, p.spinRate, 0f).cross(r));
		m.angVel.set(0f, 0f, 0f);
	}

	// ---- collisions ----

	private final java.util.Map<Long, Float> lastBump = new java.util.HashMap<>();

	/**
	 * Ships (and wrecks) can't pass through each other. Pairs whose spheres overlap are checked
	 * properly: sample points of each hull against the other's real volume. Touching ships are pushed
	 * apart (lighter one moves more), bounce off each other by mass, and take damage from the closing
	 * speed. Fighters don't collide with ships (as in vanilla); phased ships pass through.
	 */
	private void resolveCollisions(float dt) {
		if (dt <= 0f) return;
		Vec3 n = new Vec3(), contact = new Vec3(), p = new Vec3();
		for (int i = 0; i < ships.size(); i++) {
			Ship3D a = ships.get(i);
			if (a.fighter || a.phased || a.volume == null) continue;
			for (int j = i + 1; j < ships.size(); j++) {
				Ship3D b = ships.get(j);
				if (b.fighter || b.phased || b.volume == null || a.destroyed && b.destroyed) continue;
				// Parts of one structure don't collide with each other; a bump moves the whole
				// structure (a station not at all).
				Ship3D ra = a.root(), rb = b.root();
				if (ra == rb || ra.anchored && rb.anchored) continue;
				float d = a.pos.distance(b.pos);
				if (d > a.radius + b.radius) continue;
				contact.set(0f, 0f, 0f);
				int hits = penetration(a, b, contact, p) + penetration(b, a, contact, p);
				if (hits == 0) continue;
				contact.scale(1f / hits);
				n.set(b.pos).sub(a.pos);
				if (n.lengthSq() < 1e-6f) n.set(0f, 1f, 0f);
				n.normalize();
				float ia = inverseMass(ra), ib = inverseMass(rb), sum = ia + ib;
				float push = Math.min(12f, 1.5f + hits * 0.4f);
				ra.pos.addScaled(n, -push * ia / sum);
				rb.pos.addScaled(n, push * ib / sum);
				float vn = new Vec3(b.vel).sub(a.vel).dot(n); // < 0: closing
				if (vn >= 0f) continue;
				float jImp = -(1f + tuning.collisionRestitution) * vn / sum;
				ra.vel.addScaled(n, -jImp * ia);
				rb.vel.addScaled(n, jImp * ib);
				float closing = -vn;
				if (closing < tuning.collisionMinSpeed) continue;
				long key = ((long) System.identityHashCode(a) << 32) ^ System.identityHashCode(b);
				Float last = lastBump.get(key);
				if (last != null && time - last < 0.5f) continue; // one damaging bump per pair per half second
				lastBump.put(key, time);
				float mu = 1f / sum; // reduced mass
				float dmg = tuning.collisionDamage * mu * closing / 1000f;
				// The lighter side takes more of it.
				if (!a.destroyed) combat.collisionDamage(b, a, contact, dmg * ia / sum * 2f);
				if (!b.destroyed) combat.collisionDamage(a, b, contact, dmg * ib / sum * 2f);
			}
		}
	}

	/** 1 / mass for collisions: wrecks are heavy and limp, station cores immovable. */
	private static float inverseMass(Ship3D r) {
		if (r.anchored) return 0f;
		return 1f / Math.max(1f, r.destroyed ? r.mass * 3f : r.mass);
	}

	/** How many of `a`'s sample points are inside `b`'s hull; adds their world positions to sum. */
	private static int penetration(Ship3D a, Ship3D b, Vec3 sum, Vec3 tmp) {
		float[] pts = a.volume.collisionSamples();
		int hits = 0;
		float inv = 1f / Math.max(1e-3f, b.scale);
		for (int k = 0; k < pts.length; k += 3) {
			a.rot.rotate(pts[k] * a.scale, pts[k + 1] * a.scale, pts[k + 2] * a.scale, tmp).add(a.pos);
			if (tmp.distance(b.pos) > b.radius * 1.2f) continue;
			Vec3 world = new Vec3(tmp);
			tmp.sub(b.pos);
			b.rot.inverseRotate(tmp, tmp).scale(inv);
			if (b.volume.contains(tmp.x, tmp.y, tmp.z)) {
				sum.add(world);
				hits++;
			}
		}
		return hits;
	}

	/** Reticle position in normalised screen coords (-1..1). */
	public float[] reticleNdc(PilotInput in) {
		float[] r = steeringActive
				? MouseSteering.clampedReticle(in.mouseX, in.mouseY, in.screenW, in.screenH, tuning)
				: new float[] {in.mouseX, in.mouseY};
		return new float[] {r[0] / in.screenW * 2f - 1f, r[1] / in.screenH * 2f - 1f};
	}

	/** Enemy whose on-screen position is closest to the reticle (within reach), else the one nearest the reticle ray. */
	private Ship3D pickTarget(PilotInput in) {
		float[] ndc = reticleNdc(in);
		Vec3 ray = camera.rayDir(ndc[0], ndc[1], new Vec3());
		Ship3D best = null;
		float bestScore = Float.MAX_VALUE;
		Vec3 to = new Vec3();
		for (Ship3D s : ships) {
			if (s == player || s.destroyed || s.owner == player.owner) continue;
			to.set(s.pos).sub(camera.pos);
			float dist = to.length();
			if (dist < 1f) continue;
			float cos = to.dot(ray) / dist;
			if (cos <= 0f) continue;
			// Angular distance from the ray, minus the ship's own angular size, so big ships are easy to click.
			float ang = (float) Math.acos(Math.min(1f, cos)) - (float) Math.atan(s.radius / dist);
			if (ang < bestScore) {
				bestScore = ang;
				best = s;
			}
		}
		return best;
	}

	/**
	 * Works out what the player is aiming at this frame: the aim target (locked with R, else the enemy
	 * nearest the reticle), the selected group's lead point on it, whether the reticle is inside the
	 * lead circle (aim assist), and the point weapons converge on otherwise (the real hull under the
	 * reticle, not its bounding sphere).
	 */
	private void updateAiming(PilotInput in) {
		float[] ndc = reticleNdc(in);
		camera.rayDir(ndc[0], ndc[1], aimRay);
		autoTarget = target != null ? null : pickAutoTarget(aimRay);
		aimTarget = target != null ? target : autoTarget;

		WeaponGroup3D sel = selectedGroup();
		boolean wasLocked = assistLocked && lastLockTarget == aimTarget;
		hasLead = false;
		leadInRange = false;
		assistLocked = false;
		float speed = sel != null ? sel.leadSpeed() : 0f;
		if (aimTarget != null && speed > 0f) {
			Vec3 origin = groupOrigin(sel, new Vec3());
			hasLead = Combat.interceptCurved(origin, player.vel, aimTarget.pos, aimTarget.vel, aimTarget.accel, speed, leadPoint);
			if (hasLead) {
				Vec3 mw = new Vec3();
				for (WeaponMount m : sel.mounts) {
					if (m.spec.supported && m.worldPos(player, mw).distance(leadPoint) <= m.spec.range * tuning.weaponRangeMult) leadInRange = true;
				}
				// Lock when the reticle is inside the lead circle as drawn (screen pixels); a little
				// stickiness once locked so it doesn't flicker at the edge while you track.
				float[] clip = new float[4];
				camera.viewProj.transform(leadPoint.x, leadPoint.y, leadPoint.z, clip);
				if (clip[3] > 0f) {
					float lx = (clip[0] / clip[3] * 0.5f + 0.5f) * in.screenW, ly = (clip[1] / clip[3] * 0.5f + 0.5f) * in.screenH;
					float rx = (ndc[0] * 0.5f + 0.5f) * in.screenW, ry = (ndc[1] * 0.5f + 0.5f) * in.screenH;
					float dist = (float) Math.hypot(lx - rx, ly - ry);
					float r = leadCircleRadiusPx();
					assistLocked = dist <= r || (wasLocked && dist <= r * 1.6f);
				}
			}
		}

		lastLockTarget = aimTarget;
		if (assistLocked) {
			aimPoint.set(leadPoint);
		} else {
			float d = combat.raycast(ships, camera.pos, aimRay, 30000f, player);
			if (d < 0f) d = hasLead ? leadPoint.distance(camera.pos) : tuning.weaponConvergence;
			aimPoint.set(camera.pos).addScaled(aimRay, d);
		}
		hasAimPoint = true;
	}

	/** Selected group fires on the trigger; autofire groups engage on their own; others just track. */
	private void stepPlayerWeapons(boolean trigger, float dt) {
		Vec3 lead = new Vec3();
		Ship3D[] tgt = new Ship3D[1];
		for (int gi = 0; gi < player.groups.size(); gi++) {
			WeaponGroup3D g = player.groups.get(gi);
			if (gi == player.selectedGroup) {
				stepGroup(g, aimPoint, trigger, dt);
			} else if (g.autofire) {
				for (WeaponMount m : g.mounts) {
					boolean engage = autofireAim(player, m, aimTarget, lead, tgt);
					boolean fire = engage && (tgt[0] == null || missileWorthFiring(player, m, tgt[0]));
					combat.stepMount(player, m, engage ? lead : null, fire, tgt[0], dt, tuning);
				}
			} else {
				for (WeaponMount m : g.mounts) combat.stepMount(player, m, aimPoint, false, dt, tuning);
			}
		}
	}

	/**
	 * Selected-group aim: the lead point when the reticle is in the lead circle; hardpoints also take
	 * the lead whenever it falls inside their small cone (they can't reach the reticle anyway).
	 */
	private Vec3 manualAim(WeaponMount m, Vec3 aim) {
		if (hasLead && (assistLocked || (m.hardpoint && Combat.canReach(player, m, leadPoint, tuning)))) return leadPoint;
		return aim;
	}

	/** LINKED: all fire together. ALTERNATING: one at a time, spaced evenly across the group. */
	private void stepGroup(WeaponGroup3D g, Vec3 aim, boolean trigger, float dt) {
		int n = g.mounts.size();
		if (!g.alternating || n < 2) {
			for (WeaponMount m : g.mounts) combat.stepMount(player, m, manualAim(m, aim), trigger, aimTarget, dt, tuning);
			return;
		}
		g.altWait -= dt;
		for (int k = 0; k < n && !g.mounts.get(g.altIndex % n).spec.supported; k++) g.altIndex = (g.altIndex + 1) % n;
		int turn = g.altIndex % n;
		for (int k = 0; k < n; k++) {
			WeaponMount m = g.mounts.get(k);
			combat.stepMount(player, m, manualAim(m, aim), trigger && k == turn && g.altWait <= 0f, aimTarget, dt, tuning);
			if (k == turn && m.firedThisFrame) {
				g.altIndex = (turn + 1) % n;
				g.altWait = Math.max(0.05f, m.spec.cooldown / n);
			}
		}
	}

	/**
	 * Autofire: point-defence weapons take the nearest enemy missile they can reach first (pdOnly
	 * weapons never shoot ships); otherwise the aim target if this mount can reach its lead, else the
	 * nearest enemy it can reach. Writes the aim point and (for missiles) the ship to home on.
	 */
	public boolean autofireAim(Ship3D self, WeaponMount m, Ship3D preferred, Vec3 aimOut, Ship3D[] targetOut) {
		targetOut[0] = null;
		if (!m.spec.supported) return false;
		float speed = shotSpeed(m.spec);
		if (speed <= 0f) return false;
		Vec3 mw = m.worldPos(self, new Vec3());
		Vec3 lead = new Vec3();
		if (m.spec.pd) {
			float best = Float.MAX_VALUE;
			for (Missile3D ms : combat.missiles) {
				if (ms.owner == self.owner || ms.flamedOut()) continue;
				if (!Combat.intercept(mw, self.vel, ms.pos, ms.vel, speed, lead)) continue;
				if (!Combat.canReach(self, m, lead, tuning)) continue;
				float d = ms.pos.distance(mw);
				if (d < best) {
					best = d;
					aimOut.set(lead);
				}
			}
			if (best < Float.MAX_VALUE) return true;
		}
		// PD goes for fighters next (and only fighters if PD-only); ships' other guns prefer ships and
		// only turn on fighters when no ship is in reach. Fighters' own guns shoot whatever is closest.
		boolean anti = m.spec.pd;
		for (int pass = 0; pass < 2 && targetOut[0] == null; pass++) {
			float bestDist = Float.MAX_VALUE;
			for (Ship3D s : ships) {
				if (s == self || s.destroyed || s.owner == self.owner) continue;
				if (m.spec.pdOnly && !s.fighter) continue;
				if (s.phased || s.invulnerable) continue; // shots would pass straight through / do nothing
				if (pass == 0 && !self.fighter && s.fighter != anti) continue;
				if (!Combat.interceptCurved(mw, self.vel, s.pos, s.vel, s.accel, speed, lead)) continue;
				if (!Combat.canReach(self, m, lead, tuning)) continue;
				float d = s == preferred ? -1f : s.pos.distance(mw);
				if (d < bestDist) {
					bestDist = d;
					targetOut[0] = s;
					aimOut.set(lead);
				}
			}
		}
		return targetOut[0] != null;
	}

	/**
	 * Missile discipline for autofire and AI: limited-ammo missiles wait for an opening instead of
	 * emptying the racks. Kinetic (anti-shield) missiles go at shields that are already loaded;
	 * others wait until the target's shield can't stop them (down, missing, or facing away).
	 * Anyone overloaded, venting or nearly dead gets everything; the last quarter of the ammo is
	 * kept for those moments. Reloading or unlimited launchers and PD missiles fire freely.
	 */
	public static boolean missileWorthFiring(Ship3D shooter, WeaponMount m, Ship3D target) {
		WeaponSpec3D w = m.spec;
		if (w.kind != WeaponSpec3D.Kind.MISSILE || w.pd || shooter.fighter) return true; // fighters rearm on landing
		if (w.maxAmmo <= 0 || w.ammoPerSecond * 30f >= w.maxAmmo) return true;
		if (target == null) return false;
		ShieldFlux f = target.flux;
		if (f != null && f.disabled() || target.hull < target.hullMax * 0.35f) return true;
		if (m.ammo < w.maxAmmo * 0.25f) return false;
		boolean shielded = f != null && f.shieldUp();
		if (w.shieldMult >= 1.5f) return shielded && f.level() > 0.4f;
		if (!shielded) return true;
		Vec3 toShooter = target.rot.inverseRotate(new Vec3(shooter.pos).sub(target.pos), new Vec3());
		float len = toShooter.length();
		return len > 1e-3f && toShooter.dot(f.facing) / len < (float) Math.cos(f.halfAngle()); // shield faces away
	}

	/** Speed used for leading: projectile speed, beam extension speed, or missile top speed. */
	public static float shotSpeed(WeaponSpec3D w) {
		switch (w.kind) {
			case BEAM: return Math.max(1000f, w.beamSpeed);
			case MISSILE: return Math.max(1f, w.missileMaxSpeed);
			default: return w.projectileSpeed;
		}
	}

	/** Sandbox test: every enemy missile launcher fires at you when it can (point defence practice). */
	private void stepIncomingFire(float dt) {
		if (player == null || player.destroyed) return;
		Vec3 lead = new Vec3();
		for (Ship3D s : ships) {
			if (s == player || s.destroyed || s.owner == player.owner) continue;
			for (WeaponMount m : s.weapons) {
				if (m.spec.kind != WeaponSpec3D.Kind.MISSILE || !m.spec.supported) continue;
				Vec3 mw = m.worldPos(s, new Vec3());
				boolean ok = Combat.interceptCurved(mw, s.vel, player.pos, player.vel, player.accel, shotSpeed(m.spec), lead)
						&& Combat.canReach(s, m, lead, tuning);
				combat.stepMount(s, m, ok ? lead : null, ok, player, dt, tuning);
			}
		}
	}

	/** Enemy nearest the reticle (by angle, allowing for its size) within autoTargetDeg; sticky to avoid flicker. */
	private Ship3D pickAutoTarget(Vec3 ray) {
		Ship3D best = null;
		float bestScore = Float.MAX_VALUE, currentScore = Float.MAX_VALUE;
		Vec3 to = new Vec3();
		for (Ship3D s : ships) {
			if (s == player || s.destroyed || s.owner == player.owner || s.invulnerable) continue;
			to.set(s.pos).sub(camera.pos);
			float dist = to.length();
			if (dist < 1f) continue;
			float ang = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, to.dot(ray) / dist))))
					- (float) Math.toDegrees(Math.atan(s.radius / dist));
			if (ang > tuning.autoTargetDeg) continue;
			if (s.fighter) ang += 8f; // the automatic lead circle prefers ships; R still locks a fighter
			if (s == autoTarget) currentScore = ang;
			if (ang < bestScore) {
				bestScore = ang;
				best = s;
			}
		}
		if (autoTarget != null && currentScore <= bestScore + 2f) return autoTarget;
		return best;
	}

	/** Lead circle radius on screen: the reticle's radius scaled by leadCircleScale. */
	public float leadCircleRadiusPx() {
		return tuning.reticleRadiusPx * tuning.leadCircleScale;
	}

	public WeaponGroup3D selectedGroup() {
		if (player == null || player.groups.isEmpty()) return null;
		return player.groups.get(Math.max(0, Math.min(player.groups.size() - 1, player.selectedGroup)));
	}

	/** Average world position of the group's working mounts (lead pips are computed from here). */
	public Vec3 groupOrigin(WeaponGroup3D g, Vec3 out) {
		out.set(0f, 0f, 0f);
		int n = 0;
		Vec3 mw = new Vec3();
		for (WeaponMount m : g.mounts) {
			if (!m.spec.supported) continue;
			out.add(m.worldPos(player, mw));
			n++;
		}
		return n > 0 ? out.scale(1f / n) : out.set(player.pos);
	}

	/** Speed of the first working projectile weapon (for the lead solution). */
	public static float primaryProjectileSpeed(Ship3D s) {
		if (s == null) return 0f;
		for (WeaponMount m : s.weapons) {
			if (m.spec.supported && m.spec.projectileSpeed > 0f) return m.spec.projectileSpeed;
		}
		return 0f;
	}
}
