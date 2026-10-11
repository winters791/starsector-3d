package s3d.host;

import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

/** Replaces vanilla AI on puppet ships so they never move, shoot or use systems in the hidden 2D battle. */
final class InertShipAI implements ShipAIPlugin {
	private final ShipwideAIFlags flags = new ShipwideAIFlags();
	private final ShipAIConfig config = new ShipAIConfig();

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
