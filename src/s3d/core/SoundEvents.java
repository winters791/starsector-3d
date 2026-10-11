package s3d.core;

import java.util.ArrayList;
import java.util.List;

import s3d.math.Vec3;

/**
 * Sounds the 3D battle wants played this frame, by vanilla sound id. The core can't play audio;
 * the host drains these after each update and plays them through the game's sound player,
 * positioned relative to the camera. One-shots are cleared by the host; loops must be re-requested
 * every frame they should keep playing (keyed by the object that owns them).
 */
public final class SoundEvents {
	public static final class Shot {
		public final String id;
		public final Vec3 pos = new Vec3();
		public final float volume, pitch;

		Shot(String id, Vec3 pos, float volume, float pitch) {
			this.id = id;
			this.pos.set(pos);
			this.volume = volume;
			this.pitch = pitch;
		}
	}

	public static final class Loop {
		public final String id;
		public final Object key;
		public final Vec3 pos = new Vec3();
		public final float volume, pitch;

		Loop(String id, Object key, Vec3 pos, float volume, float pitch) {
			this.id = id;
			this.key = key;
			this.pos.set(pos);
			this.volume = volume;
			this.pitch = pitch;
		}
	}

	public final List<Shot> shots = new ArrayList<>();
	public final List<Loop> loops = new ArrayList<>();
	/** Off in headless tests unless a test turns it on. */
	public boolean enabled;

	public void play(String id, Vec3 pos, float volume) {
		play(id, pos, volume, 1f);
	}

	public void play(String id, Vec3 pos, float volume, float pitch) {
		if (!enabled || id == null || id.isEmpty() || volume <= 0f) return;
		shots.add(new Shot(id, pos, volume, pitch));
	}

	public void loop(String id, Object key, Vec3 pos, float volume, float pitch) {
		if (!enabled || id == null || id.isEmpty() || volume <= 0f) return;
		loops.add(new Loop(id, key, pos, volume, pitch));
	}

	public void clear() {
		shots.clear();
		loops.clear();
	}
}
