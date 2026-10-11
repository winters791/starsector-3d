# Starsector 3D — working notes

Campaign stays vanilla 2D; battles play out in a true 3D scene. This file tracks decisions,
known issues and next steps. Newest status first.

## Status

- **2026-10-12 — HUD toggle fixed for the vanilla keys (user: F10 and backslash did nothing in 3D); awaiting in-game test.**
  - Cause: F10 was already `handBack` (press twice to finish the battle in vanilla 2D), so in 3D it only
    showed a "press again" toast. Backslash wasn't bound at all. Only `G` hid the HUD.
  - Now: `keys.toggleHud` takes a comma-separated list, `F10,BACKSLASH,G` (G kept). `handBack` moved to
    **F9** (press twice). Help text shows every key of an action ("F10 / \ / G"). `TuningLoader.isKey`
    checks the list; `key()` still returns the first key for held/single-key actions.
  - Headless: keys resolve through the game's lwjgl.jar; `./build.sh` clean with -Xlint:all.
- **2026-10-12 — Stations and ships with modules in 3D (user: "move to the next step"); awaiting in-game test.**
  - Before: any battle with a station or a multi-module ship stayed vanilla 2D. Ship systems were checked
    first: 93 vanilla hulls have a working 3D system, the 41 without are mostly station modules, uniques and
    0.98's new enemies, so stations were the biggest gap.
  - Vanilla facts used (game data and API, read-only): a station is one hull whose core has Vast Bulk
    ("absorbs any amount of damage"), Axial Rotation and Shared Flux Sink; its sections are separate module
    hulls on STATION_MODULE slots, each attached by its own `moduleAnchor`, with their own guns, shields
    (some huge), hangars, armor and hull. The campaign tracks each module's state on the fleet member.
    Onslaught Mk.I (vambrace armour modules), the derelict mothership and the small orbital station use the
    same module system on a ship.
  - Core (no Starsector types): `Ship3D.parent` + `attachLocal`/`attachRot` (module rigidly on its parent,
    placed every frame after flight, `Sim.attach`), `anchored` + `spinRate` (station core: never moves,
    turns about the vertical), `invulnerable` (Vast Bulk: `dealDamage` returns nothing, the shot still
    stops), `root()`, `fixedPart()`. Modules take their parent's scale (new `scale.station` 2.5). A
    spinning station's modules get the spin velocity so shots can lead them.
    - Shots pass through every part of the shooter's own structure (`Combat.sameStructure`: projectiles,
      missiles, beams, flak, shields, and the AI's friendly-in-line check). Without this, a station's
      sections held fire because the core's sphere was always "in the way".
    - Collisions and knockback act on the root: a bump or hit on a vambrace shoves the whole Onslaught;
      a station doesn't move. Parts of one structure don't collide with each other. A ship's death
      destroys its modules. Destroyed sections stay on the station as wrecks.
    - AI: fixed parts run `turretStep` (target, flux/shield, fire; no flying). Nobody targets an
      invulnerable core (AI, fighters, autofire, auto lead circle; R can still lock it). Gun runs and
      flanking work against stations (a fixed part counts as a slow, bigger target).
  - Host: puppets are mirrored in three passes (ships, then modules once their parent has a twin, then
    fighters, so a hangar module's wings find their carrier). A station core (or an immobile hull with
    modules) is placed where vanilla has it (map position x mapScale), facing the same way; its puppet only
    gets its facing synced. Each module's offset and angle are read from the live puppets at mirror time;
    module puppets are never moved (vanilla keeps them on their parent). Hull, flux and armor write-back as
    for ships; a destroyed section is killed in place. If vanilla ends a puppet itself (a beaten station's
    core), its twin explodes. Fallback: if a Vast Bulk core is still alive 6 s after its last section died,
    it's removed as destroyed (logged as a warning). `battle.stations` false = those battles stay vanilla 2D;
    `battle.stationSpinDeg` 2 (vanilla's rate isn't exposed).
  - The minimap counts a station (or a ship with modules) once.
  - Bench: "station assault" (station1 from its variant: 6 modules; 4 attackers): every section destroyed in
    ~130 s, core untouched and never targeted, sections rigid (drift 0.00) while the station turned, the core
    never moved, nobody flew through it, the station fired back. "ship with modules" (Onslaught Mk.I): flew
    714 with vambraces rigid, a shove on a vambrace moved the ship, both died with it.
    Preview: `stations_top.png` (station1, station2_midline and station3_hightech assembled from their
    variants), `station_assault.png`, `station_target.png` (a section locked: hologram panel works).
  - Unverified until played: that vanilla keeps module puppets attached to a frozen core and kills a
    module puppet through applyDamage; that it ends the station once its sections are gone (else the
    fallback above); where vanilla puts the station relative to the deployment lines; how big a Star
    Fortress feels at scale 2.5; hangar modules' fighters.
  - Looks: stations are flat (sprite relief, like ships). Section names come from vanilla.
- **2026-10-12 — AI ships manoeuvre: weave, flank, gun runs (user: "yeah do that too"); awaiting in-game test.**
  - Before: near the enemy, AI ships held range almost still (bench 4v4: sight line to the nearest enemy
    swung 1.5-2 deg/s, mean speed 27, turning ~2 deg/s).
  - Plans (ShipAI3D.plan): every 4-6 s for frigates, up to ~18 s for capitals (`agility` by hull size:
    frigate 1, destroyer 0.75, cruiser 0.45, capital 0.3), a ship picks HOLD, FLANK or PASS, plus a range
    bias (+/-20%), a throttle bias and an orbit tilt (broadsides).
    - FLANK (`ai.flankChance` 0.6): slide round the target, nose kept on it, to a new bearing 50-110 deg away.
      The station is chosen by score: the side where the target's armor is already broken (strongly), away
      from its main guns, fairly level. Staying put is an option, and it wins when the armor facing us is
      already stripped (without this, flanking spread hits over fresh armor sectors and a Wolf's kinetics did
      almost nothing). The station is held in the target's own frame, so it follows the target as it turns.
    - PASS (`ai.passChance` 0.5 x agility): gun runs by frigates/destroyers at least 0.9x their target's
      speed, flux < 60%, hull > 50%, within 1.8x weapon range, and only on a target that isn't covering
      them (guns pointed elsewhere or busy with another ship), is overloaded/venting, or is a bigger hull.
      Run in (guns on the lead, then on a point beside the target at 2x the summed radii + 60), out the far
      side peeling off and up/down, come about, maybe a second run on another side. Abandoned at flux 90%,
      hull 35%, 20 s, or if the target gets far. The ship doesn't re-target during a run.
  - Weave on top of everything (`ai.weave` 0.6, scaled down for big hulls): a sideways/vertical strafe that
    swings to a new random direction every 1-4 s (eased), and jinks across the path of heavy incoming fire
    (cooldown 1.2-4 s by size). Fighting range breathes +/-10% over time; ships move in to 0.6x range on an
    overloaded/venting target and 0.8x on a flux-locked one while their own flux is low.
  - Broadsides: orbit plane tilted +/-35 deg per plan, more bob, throttle varies 0.3-1.
  - Bench, 4v4 skirmish swept over seeds: sight line 2.3-4.8 deg/s, speed 30-35, turning 3.5-6.5 deg/s.
    New "AI manoeuvres" check vs an unarmed Dominator: Wolf 5 gun runs from 4 octants. 15v15: ~7 gun runs and
    ~240 flanking moves in 90 s; battles are a little less lethal (18-19 dead in 90 s vs 25), sim cost about
    the same. Gun runs are rare in an even 4v4 brawl (everyone is engaged with someone), by design.
  - The log prints "AI manoeuvres so far: N gun runs, M flanking moves, K holds" every 20 s of a battle.
  - Bench fixes found on the way: the 4v4 layout was a mirror image, so the same mismatches decided every
    run (side 0 lost 10/10 seeds); side 1 is now side 0 turned half round. `S3D_SKIRMISH_SWEEP=n` runs n
    seeds, one line each; `S3D_AI=weave,pass,flank` overrides the AI knobs for comparisons. Dummy hulls of
    1e9 swallow hits under 32 damage (float steps of 64 there); the new check uses 1e6.
  - Timing: the 15v15 "frames over 8 ms" check was flaky during this work with another game using ~40% CPU
    (the old AI failed it 3 runs in 4 too); averages were unchanged.
- **2026-10-11 — Weight: hits shove and spin ships (user: "ships are just too static").**
  - Every hit pushes the target: impulse = `weight.impactScale` 30 x (vanilla impact + damage x
    `impactPerDamage` 0.1); velocity changes by impulse / mass (live `getMass()`), and off-centre hits spin
    the ship (torque / (0.3 x mass x radius^2) x `spinScale` 0.3). Vanilla impact is read live
    (`ProjectileSpecAPI.getImpactMass`, `MissileSpecAPI.getImpactStrength`, MIRV warheads from their params):
    kinetic guns hit hardest, energy weapons (impact 0) push by damage only. Beams push by damage per frame.
    Shield hits push too. Firing kicks the shooter back by `recoilScale` 0.15 of the shot. Missile, flak and
    ship-death explosions shove everything nearby (`blastScale` 30 x blast radius, falling off over 2x the
    radius). Flight assist then pulls ships back on course, fast for light hulls, slowly for heavy ones.
  - Camera shake (`cameraShake` 1): from hits on your ship (by velocity change), nearby blasts and your own
    recoil; scaled to your ship's size, decays in ~0.2 s.
  - Bench: a Hellbore-class hit on a Wolf's bow: 41 u/s shove and 27 deg/s spin; same hit on an Onslaught:
    2.4 u/s, no visible spin; the shoved Wolf is back at rest within 3 s.
- **2026-10-11 — Collisions and sound (next on the gap list); awaiting in-game test.**
  - Collisions (Sim.resolveCollisions): ships and wrecks are solid; fighters don't collide with ships
    (vanilla) and phased ships pass through. Sphere broad phase, then ~150 sample points per hull
    (outline at mid-height + sparse top/underside grid, `HullVolume.collisionSamples`) tested against the
    other hull's real volume both ways. Response: pushed apart by inverse mass (wrecks count 3x heavy),
    velocity impulse along the centre line with `collisions.restitution` 0.3; damage when closing faster than
    `minSpeed` 15: `damage` 8 x reduced mass x closing speed / 1000, split by the other ship's mass share, at
    the contact point through the normal armor/hull rules (one damaging bump per pair per 0.5 s). Mass is the
    live `ShipAPI.getMass()`. Bench: Wolf (200) ramming a Dominator (2250) head-on bounces off, loses 139
    hull, the Dominator ~1 armor; no overlap; a Hound can't fly through a wreck; fighters pass through ships.
  - Sound: the core queues sound events (`SoundEvents`: one-shots and per-frame loops, vanilla sound ids);
    the host plays them through `Global.getSoundPlayer()`, placed around vanilla's listener (the hidden 2D
    viewport centre) from their position relative to the 3D camera (sideways -> pan, distance x
    `sound.distanceScale` 0.3 -> attenuation), skipped beyond `sound.range` 6000, max 24 per frame and 3 per
    id; ids the game rejects are muted (logged once). Weapon fire sounds come from each weapon's data file
    (`fireSoundTwo`, else One; beams loop it) read via `SettingsAPI.loadJSON` (no API getter), systems' use /
    loop / deactivate sounds from their spec. Generic: hull and shield hits by size and gun/energy, missile and
    flak explosions, ship explosions, EMP jolts / knocked-out weapons / engines, flares, collisions, shield
    raise/lower and vent (yours), and an engine loop for the ship you're watching.
  - Unverified until played: loudness and panning (`sound.volume`, `distanceScale`), and whether every weapon's
    data file sits at data/weapons/<id>.wpn (mods may differ; those weapons are silent and logged).
- **2026-10-11 — Feedback round on HUD / spectate / phase.**
  - Free camera speed 1200 -> `camera.freeSpeed` 300 units/s (wheel 0.5x-3x).
  - Shields much more see-through: lower base and fresnel glow, softer rim and hit flares, and a
    `render.shieldBrightness` knob (0.45).
  - Target display: the flat sprite schematic is replaced by a hologram of the target's own 3D mesh
    (Renderer3D.renderHologram, holo shader): additive cyan with stronger silhouette edges and moving
    scanlines, seen from ~55 deg above and turning slowly; each fragment tinted by the remaining armor in
    its direction (12 x top/side/under, same sectors as ArmorShell): cyan whole -> amber -> red stripped;
    dimmer as hull drops. HUD draws go through `Renderer3D.renderHud` (HUD, then the hologram).
  - Spectating shows no target display.
  - Phase cloak / temporal shell on your own ship (not spectating) slow the rest of the battle to
    1/timeMult while your ship runs at normal speed, as in vanilla ("TIME x3" on the status line); AI
    ships with them still run faster than the world. Bench: with you phased another ship covers 34 per
    real second instead of 102; you keep 102.
  - Previews: `target_holo.png` (damaged Enforcer as target), `shield.png`.
- **2026-10-11 — Ship systems, first batch (user chose "rebuild each in 3D" over running vanilla's scripts).**
  - `ShipSystem3D` (core): vanilla life cycle (idle / charge-up / active / charge-down / cooldown,
    toggles, charges with regen), flux per use / per second (soft or hard, never overloads; can't pay =
    shuts off), shut down by overload/vent. Numbers read from the live vanilla system on the puppet
    (charge-up/active/down durations, cooldown, max ammo, ammo/s, flux per use/second) and its spec
    (toggle, hard flux, firing/turning/shield/dissipation allowed, always-accelerate, range, damage, EMP).
    Effects are ours, magnitudes in the `systems` section of the tuning file (vanilla's numbers live in
    its scripts, which we don't copy or run). Puppet systems stay disabled.
  - Kinds: Burn Drive (+300 top speed x speedMult, accel x4, turning down to 20%, forced full thrust),
    Maneuvering Jets (+50% speed, accel x2, +50% turn), Plasma Jets (+100% speed), Microburn (+600 burst),
    Phase Skimmer (500-unit jump along the velocity), Ammo Feeder (ballistic RoF x2, flux/shot x0.67), High
    Energy Focus (energy damage x1.5), Fast Missile Racks (refill), Fortress Shield (shield damage x0.1,
    shield forced up), Damper Field (hull/armor damage x0.33), Temporal Shell (ship time x3), EMP Emitter
    (arcs to the 3 nearest enemies/missiles every 0.5 s: shields absorb, else hull damage + EMP), flare
    launchers (3 decoys; enemy guided missiles within 800 may take the bait every 0.5 s at 60% and chase
    the flare; both go up harmlessly), Recall Device (wings land at once), phase cloak (right click on
    phase ships: intangible to everything, can't fire, ship time x3, flux upkeep, drawn see-through).
    Anything else is logged as "NOT IN 3D YET" and shown grey on the HUD (e.g. forge vats, drones, canister
    flak, entropy amplifier, lidar, targeting feed, orion device).
  - Hooks: per-ship modifiers reset each frame and set by running systems (speed/accel/turn mults, flat
    speed, full throttle, ballistic RoF/flux, energy damage, damage-taken and shield-damage-taken mults, no
    fire / shield / dissipation, forced shield, time mult, phase). Time mult scales the ship's own flight
    and weapon stepping (projectiles keep world time).
  - Controls: F (`keys.system`); right click = phase cloak on phase ships. HUD: "SYSTEM name STATE charges
    [F]" (and "PHASE ... [RMB]") in the weapon panel.
  - AI: Burn Drive/Microburn/Plasma Jets to close long gaps when lined up (or to retreat); Jets in or
    near range; Skimmer to dodge heavy fire; Ammo Feeder/HEF/Temporal Shell when the target is in range and
    flux < 70%; Fortress Shield under fire above 50% flux; Damper when hit with no shield up; EMP Emitter
    with enemies inside 80% of its range; flares at inbound guided missiles; racks below 40% missile
    ammo; phase out of heavy fire and back in when quiet or above 85% flux. Recall: player only.
  - Headless (all pass): burn drive 100 -> 250 speed and 26 deg of turn in 3 s at full stick; skimmer
    3 jumps from 4 presses on 3 charges; ammo feeder 9 -> 16 shots; damper 44 -> 12 damage; phased ship
    0 hits taken / 0 shots fired; flares: 2300 damage without, 0 with the AI flaring; AI burns in on a
    6000-unit gap. Preview: `build/preview/phase.png`.
- **2026-10-11 — Target display and spectate mode (user: fighters "fine for now").**
  - Target display (right side, replaces the three top-centre bars): name, hull class, range and closing
    speed; the hull's own sprite as a nose-up schematic (separate alpha-preserving texture per sprite,
    fx shader mode 7), dimmed/reddened as hull drops, with an 18-cell-wide armor grid shaded amber -> red
    by the remaining armor of the 3D sector in that direction (top/side/under averaged); knocked-out
    weapons as red x, lost engines as red dots; hull and flux bars; shield/overload/vent state. Edge
    markers keep clear of it; "OUT OF RANGE" moved above the lead circle.
  - Spectate (J, `keys.spectate`): your ship gets an AI pilot (removed again when you take back control;
    re-added if command passes to another ship meanwhile). Camera follows any ship on your side ([ / ]
    cycle, your own included; Alt free-look works), or T for a free camera (mouse turns like steering,
    W/S/A/D/Space/C fly, wheel = speed). HUD follows the watched ship (brackets, minimap ring, target
    display shows what it's after); reticle, lead circle and weapon panel are hidden; banner with keys.
  - Previews: `battle.png` (target display), `spectate.png` (watching the Heron, Enforcer with bow armor stripped).
- **2026-10-11 — Fighters built (user chose fighters before ship systems); awaiting in-game test.**
  - Hybrid, like the rest of the battle host: vanilla's carrier puppets still launch wings, replace
    losses at the replacement rate and refit landed fighters. Each launched fighter puppet gets a 3D
    twin (relief mesh, weapons, flux from the live fighter) leaving from its launch bay on the carrier's
    3D hull with the carrier's velocity plus a push forward and up. Ships are mirrored before fighters
    each frame; a fighter whose carrier isn't in 3D yet waits a frame.
  - A 3D kill kills the puppet like a ship (vanilla counts the loss); a 3D landing calls the bay's
    `land()` (vanilla refits and relaunches, mirrored afresh). Fighter hull is copied to the puppet;
    no armor/retreat handling for fighters. Log: a `fighters:` summary every 10 s (out / launched /
    landed / lost); fighter hulls are described in full once.
  - Fighter AI (ShipAI3D fighter branch): launch climb, then attack runs (close on the lead, fire, break
    away sideways and up, come round). FIGHTER/INTERCEPTOR/SUPPORT prefer enemy fighters; BOMBER/ASSAULT
    prefer ships and take the carrier's target first (yours: locked, else aimed); targets only within
    the wing's range of the carrier. SUPPORT wings, and all wings while regrouped, orbit the carrier.
    Out of non-reloading ammo or under 40% hull they fly home (easing off near the bay) and land.
    Orphaned fighters fight on. Fighters skip missile discipline (they rearm).
  - Wings key (Z, `keys.wings`): your carrier's wings engage / regroup; vanilla's pull-back flag is set
    too. AI carriers always engage.
  - Ships vs fighters: ship AI never picks a fighter as its target; ships don't swerve for fighters;
    non-PD guns shoot fighters only when no ship is in reach; PD takes missiles, then fighters (PD-only
    weapons now shoot fighters too); flak proximity fuses trigger on fighters. Your automatic lead circle
    prefers ships (R still locks a fighter). Fighters don't count toward fleet centres or the "v" count.
  - Fighters are hit like vanilla's outline: their hit volume is thickened to at least 35% of their
    radius above and below the mid-plane (as relief plates they'd be a few units thick edge-on).
  - HUD: enemy fighters near you or shooting at you get a small diamond (no brackets, no labels, no
    off-screen arrows); minimap shows fighters as dots; the weapon panel shows "WINGS ENGAGE/REGROUP
    (n out) [Z]" when your ship has wings.
  - Headless (all pass): parked fighter takes 7/8 hits; Heron strike: bombers' torpedoes hit, all 3
    bombers land to rearm, nobody beyond the 4000 leash; Talons kill 3/3 Broadswords and leave their
    carrier alone; regrouped wings orbit at ~670 from the carrier; PD kills a raider while the AI's main
    gun stays on the enemy ship; 15v15 + 40 fighters: 1.3 ms/frame, worst 7.5 ms.
  - Note: the test guns first used frag/kinetic and "missed" — fighters' 50 armor soaked them (vanilla's
    armor rule). Energy weapons kill them as expected. Frag PD vs armored fighters is weak, as in vanilla.
  - Not in 3D yet: sandbox has no carriers (fighters only appear in campaign battles); fighter formations;
    vanilla fighter-specific stats (e.g. fighter damage bonuses via DamageToFighters).
- **2026-10-11 — Armor bug fixed (user: "ships just seem invincible, even the smaller ones").**
  `ArmorShell.strip` divided each sector's share by the kernel weight sum twice, so stripping D armor
  only lowered the local reading by ~0.12 D (vanilla's cell kernel: ~0.8 D) and every spot held ~8x its
  rating. Now ~0.6 D. Same spot hit repeatedly, hits until first hull damage / dead (before -> after,
  vanilla-style grid in brackets): 400 armor / 2000 hull vs 100 dmg 104/137 -> 22/48 (15/42); 1750/14000
  vs 400 dmg 122/174 -> 26/70 (17/63). Bench: 15v15 kills in 90 s 7 -> 13; no new failures (the two
  fighter checks, interceptors and PD vs fighters, fail with and without the fix). Still slightly
  tougher than vanilla; closer would mean weighting toward the hit sector.
- **2026-10-11 — Ships halved in speed and agility (user: "just too fast").** `flight.speedMult`,
  `accelMult`, `decelMult` and `turnRateMult` 1.0 -> 0.5 (code defaults match), `rollRateMinDeg` 25 -> 12.5.
  Reverse/strafe speed and turn acceleration follow, so spin-up times are unchanged; the zero-flux boost is
  halved too. Missiles and shots keep vanilla speeds. Bench: Wolf 75 top speed, 30 deg/s yaw, 90 deg turn
  3.1 s (was 1.6). 4v4 AI first contact now ~40 s (lower `battle.deployFrac` if the approach drags).
  User otherwise: "played it and seems fine" after the EMP/missile build.
  - Bug fixed on the way: wrecks kept their weapons' last firing state (a beam firing at death stayed
    drawn on the wreck; the bench counted ghost shots). Destruction now shuts all weapons down.
- **2026-10-11 — EMP and missile behaviour built; awaiting in-game test.**
  - EMP (live `getEmpPerShot` / `getEmpPerSecond`): hull/armor hits carry EMP in proportion to the
    damage dealt; it jumps to the nearest working weapon or engine within `emp.arcFrac` of the hull
    radius (min `arcMin`); component health is the live weapon / engine max HP. At 0 the weapon is
    disabled (no fire, no turning) or the engine is out, for `emp.weaponRepairTime` 10 / `engineRepairTime`
    8 s x the hull's combat repair-time stats, then back at full health. Lost engines cut thrust
    (35-100%); all out = flameout: no thrust or flight assist (the ship drifts), turning at 20%.
    EMP isn't reduced by armor (`emp.armorReduces` to change; unsure what vanilla does). EmpDamageTakenMult applies.
  - Shield-piercing arcs: weapons whose vanilla script is IonCannonOnHitEffect / IonBeamEffect /
    TachyonLanceEffect (ion cannon, ion pulser, ion beam, tachyon lance; matched by class name at
    runtime) arc through a raised shield with chance = hard-flux level - 10% (max 90%): the arc hits the
    hull under the impact with the shot's damage and EMP and strikes the nearest component anywhere on
    the ship. Beams roll every 0.5 s while on a shield. Other weapon scripts are named in the log as not modelled.
  - Missile discipline (AI and your autofire groups; your selected group fires when you say):
    limited-ammo missiles (not PD, not reloading within ~30 s) hold until there's an opening. Kinetic
    (shield mult >= 1.5, e.g. Sabot) fire at shields above 40% flux; others when the target's shield is
    down, missing or facing away; anything at an overloaded/venting target or one under 35% hull; the
    last quarter of the rack is kept for those.
  - Proximity fuse (`PROXIMITY_FUSE` behaviour, e.g. flak): detonates within its range of an enemy
    missile; blast hits missiles and ship hulls/shields within the radius (full damage in the core,
    half at the edge).
  - Splitting missiles (`MIRV` behaviour, e.g. Sabot): split within split range (+ random extra) of the
    target after the minimum time, into N unguided warheads fanned over the arc toward the lead, with
    the behaviour's damage, EMP and damage type (plus the ship's missile damage bonus).
  - Flares: vanilla launches them from ship systems, so they come with ship systems.
  - HUD: disabled groups show OFF (red), engines-down / FLAMED OUT warning. Arcs drawn as crackling bolts.
  - Headless (all pass): EMP takes out the nearest turret only and it repairs; disabled weapon holds
    fire; ion arcs pierce 0 times at 0% hard flux vs 15/24 at 80%; flameout drifts at constant speed;
    missile discipline cases; flak proximity kills a missile 25 units off its path; Sabot-style split
    puts all 5 warheads on the target's shield. Preview: `build/preview/emp.png`.
- **2026-10-11 — "Everything is everywhere" pass (user: scattered, dashing, cluttered, lost; "all of these").**
  - Scattered: fleets enter closer (`battle.deployFrac` 0.4 -> ~3.6k apart) and tighter sideways
    (`battle.deploySpread` 0.5 of vanilla's spread). AI targets score distance from itself plus half
    the distance from its own fleet's centre (fleets engage together); with nothing in range a ship
    drifts back once `ai.cohesion` (2000) from its fleet centre; nobody leaves 85% of the battle area
    or `ai.altitudeBand` (700) around the fight's mean altitude.
  - Dashing: range is held by matching the target's speed along the line of sight, closing/opening
    at a rate that can still brake (no overshoot); gentler strafing (`ai.strafe` 0.35); overloaded or
    venting ships face the fight and back off slowly instead of running.
  - Headless 4v4: distance flown per ship roughly halved (Wolf 28k -> 14k in 4 min), mean distance to
    own fleet centre 1035, mean height off the fight 148, 6 overloads (was 25), 4 destroyed.
  - Clutter: damage numbers only for hits you deal, merged per target every 0.5 s (red = hull,
    amber = armor, blue = shield); names only on your target / aim target; shield glow and hit flares
    toned down.
  - Orientation: fleet-centre markers (faint diamond on screen, edge chevron "FLEET"/"ENEMY" + distance
    off screen; yours hidden when you're within 900 of it, skipped where a ship arrow already points);
    edge markers never land on the minimap; you get a ring and a longer view cone on the minimap.
- **2026-10-11 — Fixes from the first campaign test (Onslaught vs pirates).** User: number keys don't
  switch groups, weapons seem to do no damage, hull/armor feel off the charts, "everything is everywhere".
  From the log: (1) `getFluxCostToFire` is per *firing* (Devastator 12 x 75 = 900) but was charged per
  shell, so one autofire Devastator burst cost ~10.8k flux and pinned the Onslaught at max flux, blocking
  every weapon. Now divided by burst size. (2) 1-7 are now polled from the keyboard (vanilla's combat UI
  seems to take those events first). (3) Puppet stats leaked into 3D: vanilla's zero-flux boost on the
  puppets (+50 speed, because we copy flux onto them) and ship systems (the Onslaught's Burn Drive took
  it 29 -> 229). Puppet systems are now disabled, re-read flight stats can only go below the values read
  at deployment, and puppet flux is kept under 99% so vanilla never overloads one. (4) The "AI fights
  NOSE at 800" log line printed defaults; the AI now profiles at entry. Hull/armor values themselves
  match vanilla data plus hullmods/skills (Hound 2000/400, Onslaught armor 1750 -> 2250).
- **2026-10-11 — HUD layout pass (user feedback after an in-game look).** Vanilla's command-point /
  clean-disengage widget draws over the top-right corner (it can't be moved), so the minimap moved
  to the top left (with a "friends v enemies" count) and the blue top-left text is gone; only real
  alerts remain (retreat progress, command transfer), shown above the status line. Removed the
  steering-range ring around the screen centre and your hull/armor/flux/shield panel (bottom
  right; vanilla's own ship readout covers it). Weapon panel and throttle bar are smaller; the
  throttle sits at the far left. FPS readout (I) sits under the minimap.
- **2026-10-11 — Shields, flux and a decluttered HUD with a battlefield minimap; awaiting in-game test.**
  User decisions: shields keep vanilla's *coverage* (2D arc share of the circle = 3D cone share of the
  sphere: 90 -> 60 deg half-angle, 180 -> half sphere, 360 -> bubble); omni shields auto-face
  incoming fire (hold L-Alt to aim yours where you look); whole-battlefield minimap top right;
  drop most off-screen arrows, brackets only when relevant, remove the bottom radar. Labels unchanged.
  - Flux (`ShieldFlux`, core): capacity, dissipation, vent rate, overload time, zero-flux boost,
    hard-flux-while-shielded fraction, all read live from the ship's stats. Weapons cost soft flux
    per shot (beams per second, `getFluxCostToFire`) and can't fire past capacity; shield upkeep is
    soft flux (data stores it as a fraction of base dissipation; converted, raw values logged per hull);
    shield hits are hard flux = damage x type mult x efficiency (x hullmod/skill absorption mults);
    beam hits on shields are soft flux. Dissipation clears soft first, hard only with the shield
    down. A shield hit past capacity overloads: shield off, no weapons, 4-10 s scaled by the
    overflow (`shields.overloadBaseTime/MaxTime`; vanilla's exact formula isn't public). Vent (V):
    2x dissipation, no shield/weapons until empty. Zero-flux boost: +ZeroFluxSpeedBoost (50) top speed.
  - Shields: sphere cap of the vanilla radius x class scale at the vanilla shield centre; blocks
    shots, missiles and beams entering from outside within the cap; unfolds over the hull's unfold
    time; front shields face the nose, omni shields turn at `shields.omniTurnRateDeg` (120, not in
    vanilla data) toward incoming fire (shots/missiles about to pass within reach, beams on the ship),
    else the target. Right click toggles yours (vanilla's button). Drawn as an additive glowing cap
    with a bright rim, silhouette glow, a flare where hit, flicker above 80% flux.
  - Puppets get the 3D flux copied each frame (command map flux bars match).
  - AI: shield up while fire is inbound and briefly after, down when quiet so hard flux drains;
    near full flux it drops the shield and takes hits on armor rather than overload (while hull > 50%);
    holds ship-killing fire above `aiFluxFireLimit` (0.85); stands further off as flux rises; vents when
    flux-locked and not under fire (sooner if no enemy is close); overloaded or venting it runs from its
    target. It pounces on enemies that are overloaded or venting (preferred target, fires up to 98% flux).
  - HUD: minimap (`hud.minimapSize` 230 px, 0 = off): battle area with your edge green and the enemy's
    red, every live ship as a heading arrow (cyan you, green friends, red enemies, amber target with a
    ring), altitude ticks, your view cone; sandbox frames 2 x `weapons.radarRange` around you. FPS readout
    moved under it and starts hidden (`hud.perfDefault`, I toggles). Brackets: full for your target, enemies
    within your gun range, and anyone who hit you in the last 5 s; others a small dot; wrecks nothing.
    Off-screen arrows only for your target and attackers. Bottom radar removed. Flux bars (hard part
    darker) and shield/overload/vent state on your panel and the target panel; big OVERLOADED / VENTING
    warning mid-screen.
  - Headless (all pass): coverage mapping; front shield blocks from ahead only; kinetic 2x / HE 0.5x
    flux with efficiency; hard flux holds while shielded and drains when down; overload past capacity
    (7.2 s for that overflow) and recovery; vent 2000 flux in 6.67 s with weapons blocked; no shots past
    capacity; beam on shield = soft flux only; AI Enforcer's omni shield turns to fire from behind and
    takes no hull damage; 4v4 skirmish with shields: 4 destroyed (2 per side) in 4 min, 25 overloads,
    52 vents, ships above the fire limit 20% of the time; 15v15 sim 0.39 ms/frame.
  - Preview: `build/preview/battle.png` (HUD, minimap, shields in a fleet fight), `shield.png` (close-up).
- **2026-10-11 — Milestone 4 (campaign battles in 3D) built; awaiting in-game test.**
  - Every real campaign battle now plays in 3D (`battle.enabled` false = all vanilla). Not taken
    over: missions other than the sandbox, the refit simulator, and battles with stations or
    multi-module ships (logged as "stays vanilla 2D").
  - Vanilla runs the battle as before: deployment dialog, reinforcements and orders via Tab (the
    3D view yields while vanilla UI is up; the 3D fight keeps running behind it unless paused),
    command points, enemy admiral, CR timers, end of battle and the campaign result.
  - Each deployed ship gets a 3D twin. You fly yours; every other ship (enemies and your side)
    is flown by `ShipAI3D`. Twins enter on their own side's line (`battle.deployFrac` of the scaled
    half map height, staggered altitudes), x taken from vanilla's deployment position.
  - Write-back every frame: puppets move to their twin's position mapped onto the 2D map
    (`battle.mapScale` 0.5 3D units per map unit, clamped inside it) so the command map and the
    enemy admiral see the real fight; hull is copied every frame; armor per cell from the 3D
    sector in that direction twice a second (only ever lowered); a 3D kill moves the puppet to an
    empty spot and kills it with `applyDamage` (credited to the killer's puppet); twins start with
    the puppet's current hull.
  - Retreat: a vanilla retreat order (per ship or full retreat, read every 0.5 s) makes the twin
    fly for its own edge; past it, the puppet is set retreating and placed past the vanilla map
    edge so vanilla counts it as retreated. If vanilla hasn't counted a kill or retreat after
    1.5 s, the puppet is removed directly via the fleet manager (logged as a warning).
  - Your ship lost (or retreated): after `battle.transferDelay` s you take over your nearest
    remaining non-allied ship (and vanilla's player ship follows). Vanilla's own transfer command
    is followed too.
  - Safety valve: F10 twice (`keys.handBack`) or any exception hands the battle back to vanilla
    2D: puppets get their collision class and default AI back where they stand (which is their
    3D position on the map).
  - P pauses the whole battle (vanilla included). Damage numbers only show for hits you deal or take.
  - AI (`ShipAI3D`, core, no Starsector types): works out once which side carries most firepower
    (nose / port / starboard; missiles and PD don't decide it) and that side's range and shot
    speed. Nose ships point at the lead, hold ~`ai.rangeFrac` of range, circle-strafe and bob in
    range; broadside ships orbit with the gun side in, leaning in or out to hold range. Re-targets
    every `ai.retargetTime` s (nearest, preferring current target and whoever hit it). Every mount
    autofires (same logic as player autofire groups), holding ship-killing shots until the barrel
    is within `ai.fireConeDeg` of the lead and no friendly is in the line of fire. Pushes away from
    nearby ships (no 3D collisions yet).
  - Host refactor: `Session3D` (shared input, render, HUD, perf, tuning reload) with
    `SandboxSession` (unchanged behaviour) and `BattleSession` on top.
  - Headless (`./build.sh combat`, all pass): 4v4 AI skirmish (contact at 13 s, both sides take
    ~15k damage, 7/8 destroyed in 4 min, 0 friendly-fire hits, nobody strays past 2.6k from the
    centre); Conquest picks its port broadside and keeps the target on that side 100% of the time;
    retreating ship turns home at full speed; 15v15 AI sim cost 0.16 ms/frame average.
- **2026-10-11 — Hull meshes reworked: "balloons" → hard surface (user feedback).** The old profile
  inflated every outline with a quarter-circle, so everything read as pillows. New default
  (`mesh.profile` = "hard"): a bevelled deck (`bevelFrac` 0.14 × R), then 3 raised tiers inset from
  the outline (`tierInsetFrac` 0.32, `tierHeight` 0.15) so wide hulls get a stepped superstructure
  while booms/wings stay plates; taller vertical side plating (`wallFrac` 0.03); flatter underside
  with one keel step. No brightness-driven displacement (it made surfaces look crumpled); instead a
  high-pass normal map from the sprite (`bumpStrength` 0.4) so panel lines catch the light; less rim
  light. Weapons use a one-tier version. `"profile":"round"` restores the old look. Picked from
  close-up comparisons of 4 plate styles × 4 detail levels (`build/preview/profile_compare.png`).
- **2026-10-11 — HUD toggle added (G, `keys.toggleHud`); built, not yet tested in game.** Hides the
  whole HUD layer (reticle, lead circle, panels, radar, damage numbers, status line, help). The 3D
  scene stays on. H still toggles help but it is invisible while the HUD is hidden.
- **2026-10-11 — Beams and missiles built (user: weapons before campaign); awaiting test.**
  - Beams: vanilla cycle from the live spec (charge-up → sustain while held / burst duration →
    charge-down → cooldown), damage per second scaled by beam level, armor hit strength =
    dps × `dpsToHitStrengthMult` (read from settings), traced each frame from the mount along the
    barrel to the first hull (relief volume) or enemy missile within range. Drawn as core/fringe
    ribbons with muzzle and impact glows; damage numbers batched every 0.3 s.
  - Missiles: launch speed, top speed, acceleration, turn rate, turn accel, flight time, hitpoints,
    radius and sprite from the missile's own hull/engine spec, with the firing ship's missile bonuses
    applied. Guided (turn rate > 1 deg/s) missiles steer toward the curved-path intercept of the aim
    target; unguided fly straight. After flight time they flame out and fade without detonating.
    Impact: damage with vanilla armor rules, explosion sized from the spec. Drawn as small relief
    meshes from the missile sprite with engine glow and fading trails.
  - Point defence: projectiles and beams hit enemy missiles; PD/PD_ALSO/PD_ONLY weapons on autofire
    target the nearest reachable enemy missile first (PD_ONLY never shoots ships).
  - Guided missile launchers only need range (they launch along the barrel, then steer).
  - Sandbox: B = incoming-fire test (enemy missile launchers fire at you); your hull/armor panel;
    incoming-missile count + red markers; missiles on the radar; N repairs/rearms everything.
  - Headless: beam 395 dmg in 2 s at 200 dps; burst beam 3 cycles in 7 s; guided missiles 3/3 on a
    circling target; rocket straight + burns out; PD: 6 hits on you without PD vs 0 with 8 shot down.
- **2026-10-11 — Milestone 3 signed off** (user: "the aim circle feels right now"). Next: Milestone 4,
  real campaign battles in 3D with results fed back through the vanilla puppets.
- **2026-10-11 — M3 feedback round 2 fixed; awaiting re-test.** Lead circle is now 10% bigger than
  the reticle (`weapons.reticleRadiusPx` 11, `leadCircleScale` 1.1) and the lock test is done in
  screen pixels against exactly what's drawn (sticky to 1.6x once locked, so it doesn't flicker).
  Prediction now follows curved paths: each ship's acceleration is tracked (smoothed) and the
  intercept is solved along constant-acceleration motion (`Combat.interceptCurved`), used by the lead
  circle and by autofire. Headless: on a tight turn at long range, straight-line lead 0 hits vs
  curved 24 of ~32; a simulated player tracking the small circle held lock 99% of frames.
  Damage numbers now spawn above/beside the hit so they don't cover the circle.
- **2026-10-11 — M3 feedback round 1 fixed; awaiting re-test.**
  - "Heavy blaster fires 2 kinds of shots": left click fired every weapon, and the Ion Cannon sits right
    behind the blaster. Now vanilla weapon groups (read live from `ShipAPI.getWeaponGroupsCopy()`):
    left click fires only the selected group (Wolf: group 1 = Heavy Blaster), 1–7 select, autofire
    groups (Wolf: Ion Cannon, PD lasers) engage on their own: aim target first, else the nearest enemy
    whose lead point is inside that mount's cone and range. LINKED/ALTERNATING honoured.
  - Ranges: flat **2x** (`weapons.rangeMult` default 2.0).
  - "Very hard to hit": causes were (1) hardpoints only fire along the nose but nothing showed that,
    (2) turrets converged on the reticle ray's hit with the target's *bounding sphere* (well in front
    of the hull; from the turret's offset position that line missed), (3) no lead pip without R.
    War Thunder-style aiming now: the enemy nearest the reticle (within 25°) gets a lead circle
    automatically (R still locks); circle radius = aim-assist angle (6°); reticle inside → circle turns
    green and the selected group's turrets aim at the lead; hardpoints aim at the lead whenever it's
    inside their small cone; green "+" gun pippers show where each selected barrel points at the lead's
    distance (put the + in the circle). Without a target, weapons converge on the real hull under the
    reticle (ray marched through hull volumes).
  - Headless: trigger fires only the selected group; autofire engages without trigger; a simulated
    player keeping the reticle on a crossing frigate's lead circle: assist locked 99% of frames, 10 hits.
- **2026-10-11 — Milestone 3 (weapons + target) built; awaiting in-game test.**
  - Rules from the user: weapons sit on their slots (positions scale with the hull); range is a
    sphere of radius = weapon range centred on the **mount**; a ship's own shots pass through its own
    hull (a top turret can fire down through the ship). Ranges stay vanilla for now
    (`weapons.rangeMult` = 1); a class-based range modifier may come later after checking originals.
  - All projectile weapons work generically from the live `WeaponAPI` (range, projectile speed,
    damage, cooldown, burst, charge-up, spread, turn rate, ammo; hullmod/skill bonuses included).
    Beams and missiles are listed but don't fire yet (marked in the weapon panel).
  - Arcs: 3D cone of half-angle arc/2 around the slot direction; turrets turn toward the point under
    the reticle at the weapon's turn rate, clamped to the cone. Left click fires all working weapons.
  - Damage: vanilla damage-type multipliers, hit strength vs armor, `maxArmorDamageReduction` and
    `minArmorFraction` read from the game's settings. Armor is a 3D shell: 12 azimuth sectors × top /
    side / underside; a hit reads and strips its sector plus neighbours at half weight (vanilla's
    kernel idea). Overflow goes to hull. Hits test against the relief hull's actual volume (same SDF and
    heights as the mesh), so shots pass through gaps between prongs.
  - Projectiles inherit the firing ship's velocity (vanilla). Lead diamond = linear intercept per
    projectile speed; green when a mount can reach it.
  - HUD: target panel (hull/armor bars, distance, closing speed), weapon panel, Elite-style radar
    (ellipse = your plane, stalks = height), floating damage numbers.
  - Sandbox targets: the lineup (lasher → onslaught) plus a Hound circling behind it. N repairs all.
  - **Key changes** to keep Starsector's own bindings free: R = target (vanilla), strafe up/down moved
    from R/F to Space/C (F = ship system and V = vent later).
  - Headless checks `./build.sh combat`: armor formula, volume hits, range sphere from the mount,
    own-hull pass-through, lead vs no-lead on a circling target (19 vs 0 hits), destruction. All pass.
- **2026-10-11 — Milestone 2 signed off on feel and scale.** User tuned per-class 3D size live in
  the sandbox and settled on fighter 0.8, frigate 0.8, destroyer 1.0, cruiser 1.4, capital 2.5
  (now also the code defaults). Tuning hot-reload (K) confirmed working: six file versions loaded in
  one session. An Onslaught is now ~880 units long vs a ~75-unit Wolf.
  - **Open for M3:** vanilla weapon ranges (500–1000) are now short relative to cruiser/capital hulls
    (an Onslaught is ~880 long). Proposal: weapon mounts scale with the hull (they must, to sit on
    it); ranges start vanilla with a global `weapons.rangeMult` knob, judged in the M3 sandbox.
  - Capitals still use vanilla speeds (Onslaught 25 u/s), so at 2.5x size they crawl relative to
    their length; fine for now, revisit with weapons.
- 2026-10-11 — Milestone 2, first in-game test done; fixes in.
  - Works on the real game: GL 2.1 Metal on the M3, GLSL 1.20, FBO + 4x MSAA, 1920x1200, ~59 fps
    (vsync cap), render ~1–1.5 ms CPU, 4 ships / 79k tris. User: "the Wolf feels great".
  - Vanilla's HUD does **not** draw over the 3D pass (main M2 risk resolved). Only the hardware
    mouse cursor did → now swapped for a transparent cursor while the 3D view is up
    (`mouse.hideGameCursor`), restored whenever vanilla UI shows or the battle changes.
  - Bug found in the log: flight stats were read 0.2 s after deploy, while vanilla's deployment
    *travel drive* (+600 flat speed and acceleration) was still on → the Wolf flew at 750 / 730
    instead of 150 / 130. Fixed: stats now skip travel-drive modifiers and are re-read from the live
    puppet every 0.5 s once the drive is off. **The feel the user liked was ~5x speed/accel**; the
    real numbers will feel much slower. `flight.speedMult` / `flight.accelMult` exist if a faster
    3D pace is wanted (≈5.0 / 5.6 reproduces the first test), but weapon ranges (500–1000 units)
    will feel short at high speed — decide after the re-test.
  - Shields/weapons don't work yet by design (M3+).
  - User: "ships not drawn to scale" (Onslaught should be 3–4x, Hammerhead 1.5–2x). Geometry was
    already 1 sprite px = 1 unit (Onslaught 351 long vs Wolf 93 = 3.75x). Two presentation causes,
    both fixed: (1) reference ships were scattered 1.1k–2.6k away while your own ship sits ~250 from
    the camera, so perspective shrank them → now spawned deterministically (`sandbox.references`)
    and parked side by side at one distance (`sandbox.lineupDepth`, 900), smallest to largest;
    (2) chase distance was proportional to hull size, so every flown hull filled the screen equally →
    now sub-linear (`camera.sizeExp` 0.35: 1 = old behaviour, 0 = fixed distance / true screen scale).
    Flown Onslaught now ~2.3x the Wolf's screen width (previews: `build/preview/scale_*.png`).
  - Re-test: real-speed Wolf "much more balanced"; cursor hiding works (no flicker reported).
  - **Decision (user):** per-class 3D size on top of sprite scale — frigate 1.0, destroyer 0.8,
    cruiser 1.2, capital 1.0 (`scale` block in the tuning file). It's a true 3D size: mesh, engines
    and the ship's radius (camera, brackets, later collisions and weapon mounts) all scale together.
    Lineup now Lasher, Hammerhead, Sunder, Eagle, Dominator, Onslaught at 1200 units.
- 2026-10-11 — Milestone 2 (flight sandbox) built. Missions → *3D Flight Sandbox*. Only that mission
  is taken over; every other battle is vanilla.
- 2026-10-11 — Milestone 1 feasibility report accepted (combat host, relief meshes, Java 17).

## Architecture

```
src/s3d/math     Vec3, Quat, Mat4 (right-handed, +Y up, forward = -Z)
src/s3d/core     Sim, Ship3D, FlightModel, MouseSteering, ChaseCamera, Tuning — no Starsector types
src/s3d/render   Renderer3D, ReliefMeshBuilder, HudRenderer, shaders, GL helpers — LWJGL only
src/s3d/core     ... plus ShipAI3D (AI pilot)
src/s3d/host     Starsector glue: CombatHostPlugin, Session3D (shared) -> SandboxSession / BattleSession,
                 ShipBridge, TuningLoader
src-tools/       Dev-only tools (not in the mod jar): FlightBench, MeshPreview
```

**Hosting (decision, M1; built M4):** the vanilla battle starts normally. `CombatHostPlugin` (registered for
every battle via `data/config/settings.json` → `plugins`) decides whether to take over. Vanilla ships
become *puppets*: parked far apart, `CollisionClass.NONE`, inert AI, hold fire, zero velocity every
frame. The 3D sim runs alongside and is drawn full-screen from `renderInUICoords`. In campaign
battles (M4) 3D outcomes are written back onto the puppets (position, hull, armor grid by azimuth,
kills, retreats; CR is vanilla's own since the puppets really are deployed) and vanilla produces
the real `EngagementResult`. Sandbox puppets are parked instead and nothing is written back. Fallback if this proves unworkable: dialog-hosted scene
(Option 2 in the feasibility report); core/render don't depend on the host, so only `host/` changes.

**Rendering:** legacy OpenGL 2.1 / GLSL 1.20 (what LWJGL 2 gets on macOS). The scene renders into our
own FBO (4x MSAA via EXT_framebuffer_multisample + blit), then is composited onto whatever framebuffer
the game had bound. `GLStateGuard` saves/restores program, FBO, buffers, attribs and matrices (by
value) so vanilla rendering is untouched. HUD text uses a Java2D-rasterised glyph atlas (no
LazyLib/MagicLib dependency).

**Ship visuals (decision, M1): option A, relief meshes from sprites.** `ReliefMeshBuilder`:
alpha silhouette → exact EDT signed distance field (lightly blurred) → marching squares on a grid
(~110 cells on the long axis) → top surface height = quarter-circle profile from the edge with a
plateau at the rounding radius, plus small brightness relief → flatter keel-shaped underside → thin
edge wall. Sprite texture is edge-dilated so mipmaps don't bleed black. Underside shading: same
panels, desaturated, darker, pulled toward the hull tint; cool fill light from below keeps it readable.
Hand-made OBJ override per hull (option B) is planned, not implemented.

**Stats:** read from the live puppet `ShipAPI.getMutableStats()` (so hullmods, skills, D-/S-mods and
CR penalties are already applied); sprite via `Global.getSettings().openStream(hullSpec.getSpriteName())`;
rotation centre from `ShipAPI.getSpriteAPI()` (measured from the sprite's bottom-left — verified
against the Wolf's engine slots); engines from `EngineSlotAPI.computePosition(origin, 0)`.

## Flight model (M2)

Arcade 6DOF with flight assist (`FlightModel`):
- Throttle is a set level (W/S ramp it, X zeroes it); velocity is steered toward
  throttle×maxSpeed along the nose plus strafe, per body axis, limited by hull accel (speeding up) and
  decel (slowing / killing drift). Turning leaves momentum behind (drift); total speed is capped at max
  speed like vanilla.
- Angular rates approach the stick target limited by turn acceleration. 3D turn rate =
  `60 · (hullTurn/60)^0.75` so frigates keep their numbers while capitals are less glacial
  (Onslaught 4 → 7.9 deg/s). Spin-up time keeps the hull's own rate/accel ratio, then
  `turnAccelMult` (default 2) makes it snappier.
- Mouse = Freelancer-style virtual stick: reticle offset from centre → turn rate, with deadzone and expo.
- Auto-level (L) rolls wings level against world up, fading out near vertical.

Bench (`./build.sh bench`, default tuning):

| hull | 2D turn | 3D yaw | yaw 90° | 0→90% speed | stop dist | drift after 1s turn | level from 60° roll |
|---|---|---|---|---|---|---|---|
| wolf | 60 | 60 | 1.6 s | 1.1 s | 111 | 13.5° | 1.6 s |
| hammerhead | 30 | 36 | 2.7 s | 1.4 s | 101 | 7° | 2.3 s |
| eagle | 15 | 21 | 4.4 s | 1.4 s | 60 | 0° | 3.6 s |
| onslaught | 4 | 8 | 11.7 s | 2.3 s | 31 | 0° | 4.6 s |

## Measurements

- Relief mesh build: 8–40 ms per hull (once, cached). 13k–30k triangles per hull.
- Offscreen benchmark on this M3 (Rosetta, same GL as the game, no game running):
  60 ships / 1.3M triangles at 1280×800 with 4x MSAA + HUD: **7.3–9 ms per frame** including glFinish.
- In game: FPS / sim ms / render ms on the HUD (I), and a `S3D perf:` line in the log every 10 s.

## How to test

1. Mod is pre-enabled in `mods/enabled_mods.json` (check the launcher's Mods list).
2. Missions → *3D Flight Sandbox* → deploy the flagship. **Campaign battles:** engage any fleet
   without a station (a small pirate or patrol fleet first; save before). Deploy as usual; the 3D
   battle starts when the deployment dialog closes. Check afterwards: losses, hull damage and CR on
   the fleet screen, salvage. Log lines: `S3D: campaign battle in 3D`, `entered the 3D battle`,
   `destroyed in 3D`, `vanilla registered the loss`, `retreated`, and any `WARN` from S3D.
3. `H` shows controls. Tune feel in `data/config/s3d_tuning.json`, press `K` in game to reload (the HUD
   shows the file hash, so you can tell whether the reload picked up your edit).
4. Log: `Starsector.app/logs/starsector.log`, lines starting with `S3D`.
5. Dev tools: `./build.sh` (jar), `./build.sh bench`, `./build.sh preview [hullIds…]`
   (writes `build/preview/*.png`, flight strip, benchmark, mission icon).

## Known issues / unverified until the first in-game run

- Cursor hiding needs a re-test: if the game re-sets its cursor every frame, it could flicker.
- **Input leakage:** we consume key/mouse events in `processInputPreCoreControls`, but vanilla may poll
  some keys directly (e.g. Tab command map). If the command map opens, the 3D view yields until it closes.
- `engine.isUIShowingDialog()` semantics are a guess; transitions are logged ("vanilla UI opened/closed").
- Tuning reload uses `SettingsAPI.loadText(path, modId)`; if the game caches it, edits won't show
  until restart (watch the hash).
- No weapons drawn on hulls yet (turret/hardpoint sprites are separate in Starsector) → M3/M5.
- No sound yet. No collisions between 3D ships yet.
- No mesh LOD; fine at sandbox scale, revisit for big battles.
- GL resources live for the session; a display-mode change that recreates the GL context would need
  `ShipModel.clearCache()` + renderer re-init (not handled yet).
- Ships from other mods are untested (should work: same API path).

## Known gaps after M3

- Targets don't manoeuvre or fight back (no AI yet), apart from the B missile test.
- No shields, flux or overload yet; EMP (ion cannon/ion beam) does nothing extra; no fighters.
- Weapon on-hit/every-frame scripts (special effects) aren't run; missile special behaviours (MIRV
  splitting, flares, proximity fuses) fall back to plain homing/straight missiles.
- Aiming is pure Freelancer (weapons converge on the reticle). If holding the reticle on the lead
  diamond while it also steers feels awkward, options: aim assist near the diamond, or a separate
  aim mode.

## Known gaps after M4 (unverified until the first campaign test)

- Untested in game: whether `applyDamage` kills a `CollisionClass.NONE` puppet (fallback: remove
  as destroyed, which may change disabled-vs-destroyed for recovery), whether vanilla counts a
  puppet placed past the map edge as retreated (fallback: `removeDeployed(ship, true)`), and how
  pursuit/escape battles behave (the escaping side may need different retreat handling).
- Orders other than retreat (escort, capture, rally) are ignored by 3D pilots; objectives do nothing.
- Fighters stay parked puppets (carriers have no fighter firepower); stations and modules keep
  the whole battle vanilla.
- No ship systems (so no flares), phase cloaks (phase ships fight unshielded), CR malfunctions, or
  3D ship collisions.
- Shield/flux unknowns to verify in game (log line `shield raw values`): whether `ShieldAPI.getUpkeep`
  returns the fraction (assumed when <= 2) or flux/s; whether `getUnfoldTime` is seconds.
- AI dumps missiles as soon as they can reach (no ammo conservation).

## Next steps

(Updated 2026-10-12. Done since the last list: ship systems batch 1 + phase, EMP, fighters, collisions,
missile behaviours, sound, weight/knockback, AI manoeuvring, stations and module ships.)

1. In-game test of stations (attack a pirate or Pather base, or any station).
2. Gaps by impact: orders and objectives (escort, capture, rally, objective bonuses do nothing in 3D);
   weapon on-hit / every-frame scripts; remaining ship systems (drone strike, canister flak, entropy
   amplifier, phase teleporter, reserve wings, mine strike, targeting feed, lidar array, ...); mesh LOD for
   big battles; other mods' ships.
3. M5: hull-by-hull pass (OBJ override for hulls the sprite relief doesn't suit; stations would benefit).
