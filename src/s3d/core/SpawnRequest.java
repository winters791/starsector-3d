package s3d.core;

import s3d.math.Vec3;

/**
 * Something a 3D system or weapon puts into the battle that vanilla must also know about: a fighter
 * wing (Threat attack and construction swarms, Shrouded ejecta and vortices) or a ship (one a
 * construction swarm has finished). The host spawns it through vanilla's fleet manager and gives each
 * new puppet a 3D twin here, moving like this, with this behaviour. In the bench and the sandbox
 * nobody drains the queue, so nothing appears.
 */
public final class SpawnRequest {
	/** Wing id (wing = true) or ship variant id. */
	public final String id;
	public final boolean wing;
	public final Ship3D source;
	public final Vec3 pos = new Vec3(), dir = new Vec3();
	/** It leaves along dir at this fraction of its own top speed (vanilla's takeoff velocity). */
	public float speedFraction;
	/** What the twin does once it's in (null = an ordinary ship / fighter AI). */
	public final Ship3D.Behaviour behaviour;
	/** Construction swarm: let the host pick what it builds, as vanilla's Fabricator does. */
	public boolean pickBuild;
	/** A weapon that launched it (Swarm Launcher): counts its swarms. */
	public WeaponMount launcher;
	/** Construction swarm (filled by the host's pick): what it builds, how long it builds and travels first, how much bigger the swarm is. */
	public String buildVariant;
	public float buildSeconds, travelSeconds, buildSizeMult = 1f;

	public SpawnRequest(String id, boolean wing, Ship3D source, Vec3 pos, Vec3 dir, float speedFraction, Ship3D.Behaviour behaviour) {
		this.id = id;
		this.wing = wing;
		this.source = source;
		this.pos.set(pos);
		if (dir.lengthSq() > 1e-6f) this.dir.set(dir).normalize();
		else if (source != null) source.rot.forward(this.dir);
		else this.dir.set(0f, 0f, -1f);
		this.speedFraction = speedFraction;
		this.behaviour = behaviour;
	}
}
