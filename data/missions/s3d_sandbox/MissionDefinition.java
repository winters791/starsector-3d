package data.missions.s3d_sandbox;

import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

// Loose script compiled by the game's Janino at load time: keep the syntax plain.
// The 3D takeover is done by s3d.host.CombatHostPlugin, which recognises this mission id.
public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {
		api.initFleet(FleetSide.PLAYER, "ISS", FleetGoal.ATTACK, false);
		api.initFleet(FleetSide.ENEMY, "TTS", FleetGoal.ATTACK, true);

		api.setFleetTagline(FleetSide.PLAYER, "Test pilot");
		api.setFleetTagline(FleetSide.ENEMY, "Inert targets (take damage, don't fight back)");

		api.addBriefingItem("Deploy the flagship; the 3D view takes over once it is in space");
		api.addBriefingItem("Mouse steers, left click fires, R targets, W/S throttle, A/D Space/C strafe, Q/E roll");
		api.addBriefingItem("H shows all controls; Esc opens the menu to leave");

		api.addToFleet(FleetSide.PLAYER, "wolf_Assault", FleetMemberType.SHIP, "Testbed", true);

		api.addToFleet(FleetSide.ENEMY, "onslaught_Standard", FleetMemberType.SHIP, "Reference Capital", false);
		api.addToFleet(FleetSide.ENEMY, "eagle_Assault", FleetMemberType.SHIP, "Reference Cruiser", false);
		api.addToFleet(FleetSide.ENEMY, "hammerhead_Balanced", FleetMemberType.SHIP, "Reference Destroyer", false);
		api.addToFleet(FleetSide.ENEMY, "lasher_Standard", FleetMemberType.SHIP, "Reference Frigate", false);

		float size = 16000f;
		api.initMap(-size / 2f, size / 2f, -size / 2f, size / 2f);
	}
}
