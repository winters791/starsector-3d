# Making your mod work with Starsector 3D

Starsector 3D plays campaign battles in a 3D scene. Most modded content works there with no extra work.
For the parts that don't, your mod can add three small CSV files. You don't need any Java, and your mod
doesn't need Starsector 3D to be installed: without it, the game never reads these files.

## What already works on its own

- **Hulls, armor, flux, shields, weapons, projectiles, beams and missiles** are read from the game's own
  data when the battle starts, hullmod and skill effects included.
- **Ship systems** that vanilla has are rebuilt in 3D and matched by id. A system with an id 3D doesn't
  know falls back on its `type` / `aiType` when they say what it does (drone launchers, reserve
  deployment, phase skimmers, teleporters, missile reloads, entropy amplifiers). Its timing, charges, flux
  costs, what it blocks, its sounds and its colours (jitter, weapon and engine glow) always come from your
  system's own data file.
- **Weapon scripts** (on-hit, on-fire, every-frame, beam effects) that are vanilla's own classes have 3D
  versions. A weapon whose script 3D doesn't know still fires and does its normal damage: only the
  script's extra effect is missing.
- **Hull models**: `data/meshes/hulls/<hullId>.obj` in your mod replaces the relief mesh made from the
  sprite (it must be made for that sprite's size; see the log if it isn't used). It is painted with the
  sprite, unless a `<hullId>.png` sits beside it: then its UVs map into that image, drawn as it is, for
  the hull and its (D) versions (skins that borrow the model still get their own sprite).

## Finding the gaps: the compatibility report

Each time the game starts, Starsector 3D writes `saves/common/s3d_compatibility.txt.data` (a plain text
file). It lists, for vanilla and every enabled mod:

- `NOT IN 3D`: a ship system that does nothing in 3D, with its type, AI type, script and the hulls
  that have it.
- `GAP`: a weapon whose script or shot behaviour isn't in 3D, or a hull whose system isn't.
- `guessed`: a system 3D matched from its type. Check that it plays right.
- `told`: what a data file said about it.

It also lists the data files it read and any problems in them, such as an unknown kind, a misspelt
parameter, a row for an id that doesn't exist, or two mods writing a row for the same id. The same
problems appear in `starsector.log` (search for `S3D: mod data`).

## The files

Put them in your mod under `data/config/s3d/`. They are ordinary Starsector CSVs: the first row holds
the column names, a row whose first cell starts with `#` is a comment, and empty rows are skipped.
`params` holds `key=value` pairs separated by spaces (commas or semicolons work too). Templates with
commented examples are in this mod's `data/config/s3d/`.

The files are read when the game starts and again when each 3D battle starts, so you can edit a file
and start a new battle to try it. If two mods write a row for the same id, the mod that loads later wins,
and the report says so. A patch mod can therefore cover a mod whose author hasn't added these files.

### systems.csv: what a ship system does in 3D

| column | |
|---|---|
| `id` | the ship system's id (as in `ship_systems.csv`) |
| `kind` | one of the kinds below, or `IGNORE` to leave it out on purpose (it then isn't reported) |
| `params` | optional: its own numbers, using keys from the `"systems"` section of `data/config/s3d_tuning.json` |

```
id,kind,params
mymod_overdrive,BURN_DRIVE,burnDriveSpeed=450 burnDriveTurnMult=0.7
mymod_aegis,FORTRESS_SHIELD,fortressShieldDamageTaken=0.25
mymod_cosmetic_glow,IGNORE,
```

A row also overrides how 3D plays one of vanilla's own systems.

Kinds that are general, and the `params` keys they read:

| kind | does | params |
|---|---|---|
| `BURN_DRIVE` | big straight-line speed boost, full throttle, steering reduced | `burnDriveSpeed`, `burnDriveAccelMult`, `burnDriveTurnMult` |
| `MANEUVERING_JETS` | more speed, acceleration and turning | `jetsSpeed`, `jetsAccelMult`, `jetsTurn` |
| `PLASMA_JETS` | like jets, with more speed | `plasmaJetsSpeed`, `jetsAccelMult`, `jetsTurn` |
| `MICROBURN` | short burst of speed while active | `microburnSpeed` |
| `COMBAT_BURN` | +100 top speed and acceleration while on | |
| `DISPLACER` | short blink along the ship's course (Phase Skimmer) | `phaseSkimmerDistance` |
| `PHASE_TELEPORTER` | jumps toward the aim point, up to the system's range | |
| `AMMO_FEED` | faster ballistic fire, less flux per shot | `ammoFeedRateOfFire`, `ammoFeedFluxPerShot` |
| `HIGH_ENERGY_FOCUS` | more energy weapon damage | `highEnergyFocusDamage` |
| `FAST_MISSILE_RACKS` | refills missile weapons | |
| `MISSILE_AUTOFORGE` | refills missile racks below a fraction (reads `minFractionToReload` and `reloadBaseAmmoAmountOnly` from your system file) | |
| `FORTRESS_SHIELD` | shield forced up, takes much less damage | `fortressShieldDamageTaken` |
| `DAMPER` | hull and armor take less damage | `damperDamageTaken` |
| `TEMPORAL_SHELL` | time runs faster for the ship | `temporalShellTimeMult` |
| `PHASE_CLOAK` | phase cloak (normally on the right-click slot) | `phaseTimeMult` |
| `EMP_EMITTER` | EMP arcs at nearby enemies (damage, EMP and range from your system file) | `empEmitterInterval`, `empEmitterTargets` |
| `FLARES` | launches flares that pull guided missiles off | `flareCount`, `flareLife` |
| `RECALL` | brings the carrier's fighters home | |
| `TARGETING_FEED` | the carrier's fighters deal more damage | |
| `LIDAR_ARRAY` | hardpoint guns get more range, rate of fire and shot speed; nothing else fires | |
| `ENTROPY_AMPLIFIER` | the target takes more damage | `entropyAmplifierRange` |
| `ACAUSAL_DISRUPTOR` | knocks a ship within range into overload | |
| `MINE_STRIKE` | lays a mine near the aim point | `mineStrikeWeapon`, `mineArmTime`, `mineWindup` |
| `CANISTER_FLAK` | throws flak canisters from the hull's system slots (the weapon is your system file's `weaponId`) | |
| `DRONE_STRIKE` | turns one of the ship's drones into a missile | `droneStrikeWeapon` |
| `DRONE_LAUNCHER` | vanilla launches the drones, and 3D flies them | |
| `RESERVE_DEPLOYMENT` | extra fighters from the carrier's bays | |

Kinds built around one vanilla ship's script (they read that script's numbers, so they may not suit
another ship): `ORION_DEVICE`, `NOVA_BURST`, `ENERGY_LASH`, `FRAGMENT_VOLLEY`, `INCURSION_MODE`,
`EXTRACTION_PROTOCOL`, `CONSTRUCTION_SWARM`, `CONVULSIVE_LUNGE`, `DARKENED_GAZE`, `TENEBROUS_EXPULSION`,
`MOTE_CONTROL`.

Numbers you leave out are the ones 3D uses for vanilla's system (the player's `s3d_tuning.json`).

### weapon_effects.csv: what a weapon script does in 3D

| column | |
|---|---|
| `script` | the script's class name. A full name (`com.mymod.combat.FooOnHit`) matches only that class. A simple name (`FooOnHit`) matches a class of that name in any package. |
| `effect` | one of the effects below, or the simple class name of a vanilla script, which gives yours that script's 3D version with vanilla's numbers |
| `params` | the effect's numbers |

```
script,effect,params
com.mymod.combat.ShredderOnHit,ARMOR_STRIP,amount=150
com.mymod.combat.ArcRifleOnHit,IonCannonOnHitEffect,
com.mymod.combat.MuzzleLightsEffect,VISUAL,
```

| effect | does | params |
|---|---|---|
| `EMP_ARCS` | EMP arcs that can go through shields, more likely the more hard flux the shield holds (Ion Cannon). Uses the weapon's EMP. | |
| `ARMOR_STRIP` | strips armor around a hull hit, on top of the damage (Breach) | `amount` (required) |
| `ARMOR_BURN` | burns armor away where it hit, over ticks about a second apart; never the hull (Disintegrator) | `amount` (required), `ticks` (default 10) |
| `SHIELD_BONUS` | extra damage when the missile hits a shield (Squall). Missiles only. | `amount` (required) |
| `EXTRA_ARC` | a chance of an extra EMP arc on a hull hit; `pierce=1` lets it go through shields at high hard flux (Sabot, Pilum). Uses the weapon's EMP. | `chance` 0-1 (required), `pierce` 0 or 1 |
| `ARC_GUN` | no shell: each shot is an instant arc to the nearest enemy in a cone (Shock Repeater) | `cone`, the full angle in degrees (required) |
| `VISUAL` | looks only: nothing to do in 3D, and it isn't reported | |
| `IGNORE` | leave it out on purpose: not modelled, and not reported | |

The vanilla scripts 3D can copy: `IonCannonOnHitEffect`, `IonBeamEffect`, `TachyonLanceEffect`,
`BreachOnHitEffect`, `MiningBlasterOnHitEffect`, `DisintegratorEffect`, `GravitonBeamEffect`,
`PaladinEffect`, `PilumOnHitEffect`, `SabotOnHitEffect`, `SquallOnFireEffect`,
`ShockRepeaterOnFireEffect`. These Omega, Threat and Dweller scripts can be copied too, but they are
built around their own weapons: `RiftBeamEffect`, `RiftCascadeEffect`, `RiftLanceEffect`,
`RiftTorpedoEffect`, `RealityDisruptorEffect`, `VoltaicDischargeOnFireEffect`, `VoltaicCannonEffect`,
`VoidblasterEffect`, `NeutronTorpedoOnFireEffect`, `KineticFragmentMissileEffect`,
`SeekerFragmentMissileEffect`, `UnstableFragmentMissileEffect`, `DevouringSwarmMissileEffect`,
`SwarmLauncherEffect`, `VortexLauncherEffect`, `AbyssalGlareEffect`, `AssayingRiftEffect`,
`InimicalEmanationOnFireEffect`, `RiftLightningEffect`, `PseudoparticleJetEffect`.

### hulls.csv: keeping a hull's battles in 2D

For a ship that can't work in 3D, for example one that depends on custom combat scripts: any battle
with this hull on the field (or a skin or (D) version of it) plays in vanilla 2D.

| column | |
|---|---|
| `id` | the hull id |
| `battle` | `2d`, or `3d` to undo another mod's row |

```
id,battle
mymod_weird_carrier,2d
```

## What isn't possible yet

Behaviour that none of the kinds or effects above covers, such as a brand-new system mechanic or a
custom on-hit effect, can't be added from data alone. Map it to the closest kind or effect, set it to
`IGNORE`, or keep the hull's battles 2D. A small Java API for these cases may come later, once it's
clear what modders need. Requests are welcome.
