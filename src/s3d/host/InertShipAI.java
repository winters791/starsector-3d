package s3d.host;

import java.lang.reflect.Field;

import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

/** Replaces vanilla AI on puppet ships so they never move, shoot or use systems in the hidden 2D battle. */
final class InertShipAI implements ShipAIPlugin {
	private final ShipwideAIFlags flags = new ShipwideAIFlags();
	private final ShipAIConfig config = makeConfig();

	/**
	 * ShipAIConfig() only exists from 0.98.5a-RC5 on; RC4 and older have just ShipAIConfig(ShipAPI).
	 * Calling either one directly ties the jar to one game build (NoSuchMethodError), so try each in turn.
	 * This AI never runs, so a config with every field at its default is all anyone needs from it.
	 */
	private static ShipAIConfig makeConfig() {
		try {
			return ShipAIConfig.class.getConstructor().newInstance();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
			// older game: fall through
		}
		try {
			return ShipAIConfig.class.getConstructor(ShipAPI.class).newInstance((ShipAPI) null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
			// that constructor may read the ship: fall through
		}
		try {
			Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
			f.setAccessible(true);
			Object unsafe = f.get(null);
			return (ShipAIConfig) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, ShipAIConfig.class);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
			return null;
		}
	}

	@Override
	public void setDoNotFireDelay(float amount) {}

	@Override
	public void forceCircumstanceEvaluation() {}

	@Override
	public void advance(float amount) {}

	@Override
	public boolean needsRefit() {
		return false;
	}

	@Override
	public ShipwideAIFlags getAIFlags() {
		return flags;
	}

	@Override
	public void cancelCurrentManeuver() {}

	@Override
	public ShipAIConfig getConfig() {
		return config;
	}
}
