package s3d.tools;

import java.io.File;
import java.util.Map;

import javax.imageio.ImageIO;

import s3d.core.FlightModel;
import s3d.core.FlightParams;
import s3d.core.Ship3D;
import s3d.core.ShipControls;
import s3d.core.Tuning;
import s3d.math.Vec3;
import s3d.render.ReliefMeshBuilder;
import s3d.render.SpriteImage;

/** Headless sanity numbers for the flight model and the mesh builder, using the current tuning file. */
public final class FlightBench {
	private static final float DT = 1f / 60f;

	public static void main(String[] args) throws Exception {
		File game = new File(args[0]);
		File build = new File(args[1]);
		Tuning t = GameData.modTuning(build);
		Map<String, GameData.Hull> hulls = GameData.hulls(game);
		String[] ids = {"wolf", "tempest", "lasher", "hammerhead", "sunder", "falcon", "eagle", "dominator", "conquest", "onslaught"};

		System.out.println("Flight model with data/config/s3d_tuning.json");
		System.out.printf("%-11s %5s %5s | %6s %6s %6s | %7s %7s %7s %7s %7s %7s%n",
				"hull", "turn", "spd", "yaw3D", "roll3D", "spinUp", "yaw90s", "to90%s", "stopDst", "drift1s", "level60", "overshoot");
		for (String id : ids) {
			GameData.Hull h = hulls.get(id);
			if (h == null) continue;
			FlightParams p = new FlightParams(h.speed, h.accel, h.decel, h.turn, h.turnAccel).derive(t);
			System.out.printf("%-11s %5.0f %5.0f | %6.1f %6.1f %6.2f | %7.2f %7.2f %7.0f %6.1fd %7.2f %6.1fd%n",
					id, h.turn, h.speed, Math.toDegrees(p.yawRate), Math.toDegrees(p.rollRate), p.yawRate / p.yawAccel,
					yaw90(p, t), to90(p, t), stopDistance(p, t), drift(p, t), level(p, t), stickReleaseOvershoot(p, t));
		}

		System.out.println();
		System.out.println("Relief meshes (CPU only)");
		for (String id : new String[] {"wolf", "lasher", "hammerhead", "eagle", "dominator", "onslaught", "paragon"}) {
			GameData.Hull h = hulls.get(id);
			if (h == null) continue;
			GameData.readShipFile(game, h);
			SpriteImage img = new SpriteImage(ImageIO.read(new File(game, h.spritePath)));
			ReliefMeshBuilder.Result r = ReliefMeshBuilder.build(img, h.centerX, img.h - h.centerYFromBottom, ReliefMeshBuilder.Params.from(t));
			System.out.printf("%-11s %4dx%-4d %s | %s%n", id, img.w, img.h, r.summary(), validate(r));
		}
	}

	private static Ship3D ship(FlightParams p) {
		Ship3D s = new Ship3D("bench");
		s.params = p;
		return s;
	}

	/** Seconds to yaw 90 degrees from rest with the stick fully deflected. */
	static float yaw90(FlightParams p, Tuning t) {
		Ship3D s = ship(p);
		ShipControls c = new ShipControls();
		c.stickX = 1f;
		float time = 0f;
		while (time < 120f) {
			FlightModel.step(s, c, t, DT);
			time += DT;
			Vec3 f = s.rot.forward(new Vec3());
			if (Math.atan2(f.x, -f.z) >= Math.PI / 2) return time;
		}
		return Float.NaN;
	}

	/** Seconds from standstill to 90% of max speed with throttle at 100%. */
	static float to90(FlightParams p, Tuning t) {
		Ship3D s = ship(p);
		s.throttle = 1f;
		ShipControls c = new ShipControls();
		float time = 0f;
		while (time < 120f) {
			FlightModel.step(s, c, t, DT);
			time += DT;
			if (s.speed() >= 0.9f * p.fwdSpeed) return time;
		}
		return Float.NaN;
	}

	static float stopDistance(FlightParams p, Tuning t) {
		Ship3D s = ship(p);
		s.vel.set(0, 0, -p.fwdSpeed);
		ShipControls c = new ShipControls();
		c.allStop = true;
		float dist = 0f;
		for (int i = 0; i < 60 * 120 && s.speed() > 0.5f; i++) {
			FlightModel.step(s, c, t, DT);
			dist += s.speed() * DT;
		}
		return dist;
	}

	/** Angle between nose and velocity after one second of full yaw at max speed. */
	static float drift(FlightParams p, Tuning t) {
		Ship3D s = ship(p);
		s.throttle = 1f;
		s.vel.set(0, 0, -p.fwdSpeed);
		ShipControls c = new ShipControls();
		c.stickX = 1f;
		for (int i = 0; i < 60; i++) FlightModel.step(s, c, t, DT);
		Vec3 f = s.rot.forward(new Vec3());
		Vec3 v = new Vec3(s.vel).normalize();
		return (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, f.dot(v)))));
	}

	/** Seconds for auto-level to bring a 60 degree roll within 2 degrees. */
	static float level(FlightParams p, Tuning t) {
		Ship3D s = ship(p);
		s.rot.setAxisAngle(0, 0, 1, (float) Math.toRadians(60));
		ShipControls c = new ShipControls();
		c.autoLevel = true;
		float time = 0f;
		while (time < 60f) {
			FlightModel.step(s, c, t, DT);
			time += DT;
			Vec3 r = s.rot.right(new Vec3()), u = s.rot.up(new Vec3());
			if (Math.abs(Math.toDegrees(Math.atan2(r.y, u.y))) < 2.0 && Math.abs(s.angVel.z) < 0.02f) return time;
		}
		return Float.NaN;
	}

	/** Degrees the nose keeps turning after the stick is released at full turn rate. */
	static float stickReleaseOvershoot(FlightParams p, Tuning t) {
		Ship3D s = ship(p);
		s.angVel.y = -p.yawRate;
		ShipControls c = new ShipControls();
		Vec3 f0 = s.rot.forward(new Vec3());
		for (int i = 0; i < 60 * 30 && Math.abs(s.angVel.y) > 1e-4f; i++) FlightModel.step(s, c, t, DT);
		Vec3 f1 = s.rot.forward(new Vec3());
		return (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, f0.dot(f1)))));
	}

	/** Checks for NaNs, unit normals and that every outline edge is used by exactly two wall triangles. */
	static String validate(ReliefMeshBuilder.Result r) {
		int bad = 0;
		for (float v : r.verts) if (Float.isNaN(v) || Float.isInfinite(v)) bad++;
		int nonUnit = 0;
		for (int i = 0; i < r.vertexCount; i++) {
			int b = i * ReliefMeshBuilder.STRIDE;
			float l = (float) Math.sqrt(r.verts[b + 3] * r.verts[b + 3] + r.verts[b + 4] * r.verts[b + 4] + r.verts[b + 5] * r.verts[b + 5]);
			if (Math.abs(l - 1f) > 1e-3f) nonUnit++;
		}
		int topUp = 0, topTotal = 0;
		for (int tIdx = 0; tIdx < r.topTris * 2; tIdx += 2) {
			int a = r.indices[tIdx * 3], b = r.indices[tIdx * 3 + 1], c = r.indices[tIdx * 3 + 2];
			float ny = normalY(r.verts, a, b, c);
			topTotal++;
			if (ny > 0) topUp++;
		}
		return (bad == 0 && nonUnit == 0 && topUp == topTotal ? "OK" : "PROBLEM")
				+ String.format(" (nan=%d nonUnitNormals=%d topFacingUp=%d/%d outlineSegs=%d)", bad, nonUnit, topUp, topTotal, r.boundarySegments);
	}

	private static float normalY(float[] v, int a, int b, int c) {
		int s = ReliefMeshBuilder.STRIDE;
		float abx = v[b * s] - v[a * s], abz = v[b * s + 2] - v[a * s + 2];
		float acx = v[c * s] - v[a * s], acz = v[c * s + 2] - v[a * s + 2];
		return abz * acx - abx * acz;
	}
}
