package s3d.host;

import java.util.IdentityHashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.combat.threat.ConstructionSwarmSystemScript;
import com.fs.starfarer.api.impl.combat.threat.ConstructionSwarmSystemScript.SwarmConstructableVariant;

/**
 * What a Fabricator's construction swarm builds and how long it takes, decided by vanilla's own
 * ConstructionSwarmSystemScript (its public pick, by what the Threat side already has out), one
 * script per Fabricator so its first two frigates come out fast as in vanilla. Build time, travel
 * time and the swarm's size follow vanilla's numbers for the pick's hull size.
 */
final class BuildPicker {
	static final class Pick {
		String variantId;
		int fragments;
		float cr, buildSeconds, travelSeconds, radiusMult = 1f;
	}

	private static final Map<ShipAPI, ConstructionSwarmSystemScript> SCRIPTS = new IdentityHashMap<>();
	private static final Map<ShipAPI, Integer> FAST_LEFT = new IdentityHashMap<>();

	private BuildPicker() {}

	static Pick pick(ShipAPI fabricator) {
		ConstructionSwarmSystemScript.init();
		ConstructionSwarmSystemScript script = SCRIPTS.computeIfAbsent(fabricator, k -> new ConstructionSwarmSystemScript());
		SwarmConstructableVariant v = script.pickVariant(fabricator);
		if (v == null) return null;
		ShipVariantAPI variant = Global.getSettings().getVariant(v.variantId);
		if (variant == null) return null;
		Pick p = new Pick();
		p.variantId = v.variantId;
		p.fragments = v.fragments;
		p.cr = v.cr;
		p.buildSeconds = ConstructionSwarmSystemScript.BASE_CONSTRUCTION_TIME + v.dp * ConstructionSwarmSystemScript.CONSTRUCTION_TIME_DP_MULT;
		if (variant.getHullSpec().hasTag(Tags.THREAT_OVERSEER)) p.buildSeconds += ConstructionSwarmSystemScript.CONSTRUCTION_TIME_OVERSEER_EXTRA;
		int fast = FAST_LEFT.getOrDefault(fabricator, ConstructionSwarmSystemScript.FAST_CONSTRUCTION_FRIGATES_MAX);
		if (fast > 0) {
			if (v.size == HullSize.FRIGATE) {
				FAST_LEFT.put(fabricator, fast - 1);
				p.buildSeconds = 2f;
			} else {
				FAST_LEFT.put(fabricator, 0);
			}
		}
		switch (v.size) {
			case DESTROYER: p.radiusMult = 2f; p.travelSeconds = 4f; break;
			case CRUISER: p.radiusMult = 3.5f; p.travelSeconds = 5f; break;
			case CAPITAL_SHIP: p.radiusMult = 4f; p.travelSeconds = 6f; break;
			default: p.radiusMult = 1f; p.travelSeconds = 3f;
		}
		return p;
	}

	/** A new battle: forget the old Fabricators. */
	static void clear() {
		SCRIPTS.clear();
		FAST_LEFT.clear();
	}
}
