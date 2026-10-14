# Starsector 3D — working notes

Campaign stays vanilla 2D; battles play out in a true 3D scene. This file tracks decisions,
known issues and next steps. Newest status first.

## Status

- **2026-10-14 — Officer skills and hullmods in 3D (user asked "do officer skills work in 3D yet?", then "start on
  this" for the gaps: damage vs targets, per-ship armor rules, the skills vanilla runs as combat listeners, Gunnery
  Implants' recoil and autofire leading). New bench group `S3D_ONLY=skillStats` (11 checks). Not committed, jar not
  rebuilt. Awaiting in-game test.**
  - `core/CombatStats` on every ship (`Ship3D.stats`, defaults change nothing): damage dealt vs fighters / each hull size
    (a module counts as its station) / missiles, to shields, hull, weapons, engines; hit strength bonus; the target's
    effective armor bonus, least armor fraction, most armor reduction, kinetic armor damage taken; hull/armor and shield
    damage taken by delivery (beam / missile / projectile) and damage type; EMP to weapons / engines taken; recoil
    multipliers; autofire aim accuracy and IPDAI's perfect PD leading.
  - `host/SkillStats` copies these from the puppet's MutableShipStats every frame (fighters twice a second; Session3D
    `refreshSkillStats`), and now also re-reads armor / hull / EMP damage taken and shield flux per damage live (they
    were read once at deployment). Polarized Armor's flux-based part is vanilla's own listener on the puppet: it sets the
    puppet's effective armor bonus and EMP taken from the hard flux we write back; the log line "effective armor now
    +X%" confirms it works in game. Each ship logs one "skills/hullmods in 3D for ..." line listing what isn't default.
  - Listener skills rebuilt in core, spotted with `ShipAPI.hasListenerOfClass` (numbers in `core/SkillRules`, copied
    from vanilla's public statics when a battle starts): Energy Weapon Mastery (bonus = magnitude x flux level above
    the minimum level, the same linear rule as vanilla's ship method; full at 600 and closer, none from 1000), Ranged
    Specialization (none to 800, +30% from 1600; crit mode if a mod switches CRITS on), Damage Control elite (at most
    once per 2 s, hull damage past 500 from one hit cut by 60%; any hull/armor hit spends it, as in vanilla), Combat
    Endurance regen (0.5% of max hull per second, at most max(2000, half the hull) per battle). Distances are compared
    in vanilla units (3D distance / weapons.rangeMult).
  - Where it applies (`Combat`): `dealDamage` (vs-size and taken multipliers on the damage; hit strength x armor
    damage taken x kinetic armor taken, then the attacker's bonus, in vanilla's order; the target's effective armor,
    least armor and most reduction; damage to hull after armor; then Damage Control), `shieldDamage` (vs size x to
    shields x shield taken), `empHit` (now takes the attacker: weapon / engine taken x damage to weapons / engines),
    every missile-HP hit (damage to missiles), projectile and beam hits (`rangeBonus`). Collisions keep the plain rules.
  - Gunnery Implants: spread floor and cap x max recoil mult, buildup x per-shot mult (small weapons also their own
    mult), recovery x decay mult (vanilla's spread tracker). Autofire leading: vanilla's autofire aims at the true lead
    divided by (2 - accuracy): no skill = half the lead, Gunnery Implants = all of it; a weapon's autofireAccBonus and
    IPDAI PD take it to all of it too (`Sim.underLead`, `CombatStats.leadFactor`, `weapons.unskilledLeadFactor`).
  - Measured (AiArena, 30 mixed battles, 6 seeds, `S3D_ARENA_TUNE=unskilledLeadFactor=1|1.5|2`; nobody has the
    skill there): perfect lead 347 ships destroyed, decided battles 151 s on average; vanilla's half lead 337, 168 s;
    1.5 in between (349, 160 s). Kiting at long range suffers most: the bench's cautious Wolf standing off an Enforcer
    deals 764 instead of 3394. **User chose the vanilla rule** (`weapons.unskilledLeadFactor` 2; 1 = everyone leads
    perfectly as before). That caution check now gives both its ships full leading (it tests positioning, not aim).
  - How vanilla does it was read from the API sources (skills) and javap of the obfuscated damage, spread-tracker and
    autofire classes (understanding only, nothing copied).
  - Not done: Polarized Armor's jitter (vanilla tints the ship above 75% hard flux); officer personality (separate step;
    the AI session's hook is ShipAI3D's per-pilot aggression / caution).
- **2026-10-14 — Enemy AI rebuilt after AI Tweaks, plus +15% manoeuvrability (user: "the AI is still not there yet,
  it should be more aggressive, some ships just stand idle; they should either pursue, attack or retreat. A flat 15%
  boost to manoeuvrability of all ships. Grind the gears on this one", then "the AI Tweaks folder is the gold standard
  for me; it's fine if they are idle somewhat"). Full bench 140/140 (4 new checks, `S3D_ONLY=aiTweaks`). Jar NOT
  rebuilt yet (peers active). Awaiting in-game test.**
  - New dev tool `src-tools/s3d/tools/AiArena.java` (main class, run like the bench with `MAIN=s3d.tools.AiArena`):
    five battle types (12v12 line battle, the same with the enemy admiral's escort and capture orders, 10 v boss + 4
    escorts, fast frigates v slow capitals, 8v8 brawl), measuring **idle time** (no shot at an enemy for 3 s, not
    closing, not backing off) and its cause (`ShipAI3D.activity`). `S3D_ARENA_AB="caution=0.5"` fights mirrored
    battles with per-pilot settings on one side (each seed twice, sides swapped); `S3D_ARENA_TRACE`, `S3D_ARENA_WATCH=<id>`.
  - What made ships idle before (14.7% of ship-time, one capture ship 139 s): admiral escort/capture orders (sitting
    by the flagship / on a taken objective), the fleet-cohesion pull dragging ships off chases, broadside ships circling
    just out of reach, slow ships chasing faster targets forever, ships stuck at 98% flux backing off in slow reverse
    for 25 s+ without venting, capitals flying away at full throttle while their 4 deg/s turn came round.
  - **AI Tweaks** (`~/Downloads/starsector-ai-tweaks-master`, Halke's mod, public domain; read for ideas, nothing
    copied, rebuilt for 3D; summary in the ShipAI3D class comment): target scoring (`attackScore`: turning time, guns'
    reach and dps share at range, target flux, Damper bricks, big-v-big for slow capitals, friends already on it, helpless,
    stickiness, never behind a friendly hull), fleet segmentation (big slow ships go for the enemy's main group, not lone
    frigates), range = shortest primary gun (here x0.92 and to the target's centre: our guns fire once the centre is in
    range, vanilla's at the hull, so "range + target radius" parked frigates outside their own guns), threat-vector attack
    spot + attack coordination (friends on one target spread round it) + orbit/strafe one on one, flown on thrusters
    (`orbitCourse`/`drive`), broadsides held on the target the same way, the vent module (back off on hard flux > 75% or
    flux heading past 93%, away from the threats until safe, then park out of its own reach and vent; vent when safe at
    50% / 30% idle; safety = fire inbound + guns reaching during the vent v hull x level^4, frigates only with nothing
    near; finish a badly hurt target first), fire to 93% flux.
  - Own additions: pursue flat out (throttle by alignment, thrusters toward the target while turning), stall breaker
    (in reach but no shot for 3 s: range pulled in, then a flank), hopeless chase given up after 6 s, `hunt` with nothing
    to fight, a back-off that can't shake its chaser fights on after 10 s (in 3D reverse/strafe are slow, so running from
    an equal is hopeless), station sections vent only when needed, `Tuning.aiAggression` (ai.aggression, default 1).
  - Orders: `Order3D.admiral` (enemy's or an ally's, set in BattleSession): escort/hold = where to fight first (any
    target, pulled back past 2.5x the leash), capture ends once the point is ours. **Your own orders are unchanged.**
  - A/B, 24 mirrored battles each (6 seeds x brawl/lines x sides): new v previous AI ahead in 18-20, about 1.3M v 0.8M
    damage, half the losses; every AI Tweaks part won or tied on its own (back-off movement 23/1, targeting 19/5, vent
    rules 17/7, range 16/8, orbit 14/10, broadside 12/12). Caution still pays (caution 0 loses 9/15), opportunism too
    (16/8); aggression 0/1/2 are within noise (2 trades more ships). Arena: idle 14.7% -> 4.6%, longest 139 s -> 23 s,
    firing 41% -> 48%, first kill 52 s -> 39 s. Pure-AI sim cost unchanged (15v15 check passes).
  - **Manoeuvrability**: `flight.maneuverMult` 1.15 (`FlightParams.derive`): turn rate, turn acceleration (spin-up time
    kept), acceleration, deceleration, strafe acceleration; not top speed. Onslaught yaw 3.9 -> 4.5 deg/s.
  - Bench changes: AI omni shield fixture uses a real hull (a 1e9-hull ship vents through fire under the new safety
    rule); canister flak Manticore holds its spot; station assault no longer requires damage to attackers (they now pick
    modules off from angles the others can't cover: 12 station shots, none hit); caution kite counts a careless Wolf that
    dies early as in reach; opportunism check runs with aggression 0. Bench edits applied as idempotent hunks
    (scratchpad `sync_bench.py`) because CombatBench is shared.
  - To watch in game: enemy fleets now push in and focus fire; ships at high hard flux break off and come back after
    venting (that's the AI Tweaks back-off, not idling); slow capitals still can't catch frigates; whether admiral escorts
    leave their flagship too readily (pulled back past 2.5x the leash).

- **2026-10-14 — Retro filter for ships, optional (user, with two PS1-era reference shots: "the current voxel look
  seems janky... make the filters optional"). Compiles; offline look check `S3D_PREVIEW_ONLY=retro` ->
  build/preview/retro_sheet.png, retro_zoom.png, retro_<view>_<variant>.png. Awaiting in-game test.**
  - Tuning `retro` section: `filter` "ships" (default) / "scene" / "off"; `pixelSize` 3 (screen pixels per retro
    pixel); `colorBits` 5 (PS1 15-bit colour; 8 = no quantising); `dither` 1.0 (4x4 Bayer, in retro pixels);
    `wobble` 0 (PS1 vertex snap to a grid of N retro pixels; 1 = authentic). K reloads it mid-battle.
  - "ships": hulls + turrets (incl. phased ghosts) render into `render/RetroTarget` (screen/pixelSize colour + depth
    textures, no MSAA), then a full-screen composite (`Shaders.RETRO_FS`) writes them into the scene target, nearest-
    sampled and dithered, with `gl_FragDepth` from the low-res depth so missiles, shields, beams and other effects
    still sort against hulls. Premultiplied blend keeps phased ships see-through. Effects, sky, stars, HUD stay sharp.
  - "scene": the scene target itself is screen/pixelSize without MSAA, composited nearest + dithered; stars keep their
    on-screen size (point size scaled, sub-pixel ones dim instead). HUD stays sharp.
  - Missile bodies are not filtered (too small at low res). System looks (jitter copies, glows) draw at full res over
    the low-res hull; looked fine in the systemFx preview.
- **2026-10-14 — Your ship panel in vanilla's HUD font, with weapon icons and vanilla's status list (user, with a
  vanilla screenshot: "replace the font with the vanilla ones, have the little weapon icons beside the weapon name, and
  the other info in vertical (ECM rating, peak time etc. with their icons); keep the same blue colorway"). Compiles;
  offline look check `S3D_PREVIEW_ONLY=shipPanel` -> build/preview/ship_panel.png. Awaiting in-game test.**
  - Font: vanilla's combat HUD uses Victor's Pixel Font (`graphics/fonts/victor14.fnt`, found in its combat UI
    classes). New `render/BitmapFont` reads AngelCode .fnt + page through new `render/GameFiles` (host: the game's
    `openStream`, so mods' files too; preview: the install), nearest-filtered at whole pixels, +1 px between letters as
    vanilla draws it (measured off the user's screenshot: 7 px letters on an 8 px step). 2x from 1800 px tall. Only your
    ship panel and the status list use it; the rest of the HUD keeps its font. Falls back to the HUD font if it fails.
  - Weapon icons: `WeaponSpec3D.iconSprites` = the weapon's turret sprite layers (under, base, barrel; hardpoint ones if
    it has no turret sprite), from ShipBridge. `render/HudIcons` stacks them into a white-by-brightness silhouette with
    the sprite's alpha, tinted in the row's cyan (fx mode 10). Slot shape as before if a weapon has no sprite. Gotcha:
    the icon textures are created before the panel's glBegin (GL drops texture uploads inside glBegin/glEnd: first
    attempt drew some other texture as blue blocks).
  - Status list: `host/VanillaStatus` reads vanilla's own list back every frame (CombatState.getShipInfo()
    .getStatusList(): its first List field, each entry's icon `getSpriteName()` and two `LabelAPI` lines, red data line
    = debuff, `getFader().getBrightness()` for its fade). So it shows exactly what vanilla would: Command Network, ECM,
    Coordinated Maneuvers, peak time, CR, terrain (ion storms), skills, hullmods, other mods' statuses. The game's
    script class loader refuses `java.lang.reflect` to mods, so fields/methods are held as Objects and used through
    `java.lang.invoke` handles; only public method names and public interfaces are relied on. Any failure: logged once
    ("can't read vanilla's status list"), no list. First success logs "reading vanilla's status list: N entries...".
    Drawn above your hologram, bottom entry first, at most 9 (the throttle scale is above), titles bright cyan, data
    cyan, debuff data red (the panel's own warning red).
  - Unverified until played: that vanilla keeps its (faded-out) ship panel's status list updating while the 3D view
    is up (hideShipInfo only fades it, per the bytecode); label order (title first) on the live widget; the offline
    preview's GL prints a one-off "texture unloadable" warning that the game may or may not show.

- **2026-10-13 23:55 — Bug-report files for playtesters (user: "how will a player report bugs?", then "implement 3D
  specific error logging"; testers will post to a Discord channel or a separate public issues repo). Compiles; a
  throwaway harness with a fake SettingsAPI passed 12/12 (it isn't part of the bench: BattleLog needs Global). Jar NOT
  rebuilt (the HUD session was mid-edit). Awaiting in-game test.**
  - `host/BattleLog`, a log4j appender on the root logger installed first thing in `onApplicationLoad`. It keeps every
    `s3d.*` line of the current 3D battle, plus WARN+ from any other logger while the battle runs, and writes
    `saves/common/s3d_last_battle.txt.data` every minute, when the battle ends (handed back, stayed 2D, combat over, or
    closed by the game: the next battle's init closes it) and on an error. Also `s3d_previous_battle` (the battle before,
    kept when the next 3D battle starts; also carried over from the last game session) and `s3d_last_error` (written on
    any ERROR whose trace has an `s3d.` frame, ours or logged by the game, so later battles don't overwrite it).
  - Header: what to send, the battle and its outcome, mod/game version, OS, Java, heap, GL renderer (`GLCaps`), screen
    and UI scale, enabled mods with versions, ships by side (refreshed every 10 s on the game thread). Then warnings
    and errors (up to 60, with traces up to 40 lines), our lines from outside 3D battles (game start: version,
    compatibility report, data-file problems), and the battle: the first 250 KB plus a rolling last 400 KB, to stay under
    writeTextFileToCommon's 1 MB.
  - `CombatHostPlugin.fail` puts `Session3D.state()` in the outcome (battle time, ships per side, shots, player ship
    and system state) before it logs the error. The in-game message now names `saves/common/s3d_last_error.txt.data`.
  - Locking: `doAppend` holds the appender's monitor (and log4j holds the root logger's), so nothing inside
    `synchronized (this)` may log; files are written outside it, and lines logged while writing are skipped (`writer`).
    The harness checked this with 300 battles opened and closed while two threads log.
  - Cost to watch: in a big battle each write builds and saves up to ~650 KB once a minute on the game thread (a few
    ms). If the minute's "S3D perf" worst frame shows it, make the periodic write smaller or less frequent.
  - Still to do for testers: a short tester README (controls, known issues, which files to attach) and the report form.

- **2026-10-13 23:40 — Modder data layer and compatibility report (user: "is there a way future modders can support this
  framework?", then "build the data layer and compatibility report"). Bench 5 new checks (`S3D_ONLY=modData`), full
  bench all pass. Awaiting in-game test.** Reference for modders: `MODDING.md`.
  - Other mods add `data/config/s3d/systems.csv` (system id -> 3D kind or IGNORE, plus per-system numbers using keys of
    the tuning file's "systems" section), `weapon_effects.csv` (script class -> EMP_ARCS / ARMOR_STRIP / ARMOR_BURN /
    SHIELD_BONUS / EXTRA_ARC / ARC_GUN / VISUAL / IGNORE, or a vanilla script's simple name to get its 3D version) and
    `hulls.csv` (hull -> battles stay 2D). They don't need Java or a dependency on this mod. Templates with commented
    examples are in our `data/config/s3d/`.
  - `core/ModData` parses and validates rows (pure, bench-tested). `host/ModDataLoader` reads each enabled mod's files
    one at a time with `loadCSV(path, modId)` in load order: the game's merged-spreadsheet loader picks between mods
    "in an undefined manner", and we want to say whose row won. Later mods win and the conflict is logged. The files
    are read at game start and again at each 3D battle start, so edits show without a restart.
  - A system row wins over the built-in mapping (it can also rebalance vanilla's own systems). Its numbers become a
    per-system `Tuning` copy (`Tuning.withSystems`, `ShipSystem3D.tuning`); the battle's tuning is untouched.
    Misspelt keys are caught by `Tuning.systemKeys()`, which tracks the keys `readSystems` reads, so there is no
    second list to keep in step. Weapon effect rows are looked up by full class name first, then by simple name, before
    the built-in scripts (`ShipBridge.effect` -> `builtInEffect`). Hull rows follow skins and (D) hulls to their base.
  - `host/CompatReport`: at game start, writes `saves/common/s3d_compatibility.txt.data`. For vanilla and each mod it
    lists systems that are NOT IN 3D (with type, AI type, script and the hulls that have them), systems guessed from
    their type, weapons with a GAP (unknown on-hit, on-fire, every-frame or beam script, or an unhandled shot
    behaviour), hulls with a gap, what each data file said, and problems in the data files (unknown kind, effect or
    key, missing number, an id nothing has, a script no weapon uses, two mods writing the same row). Every-frame
    scripts come from `data/weapons/<id>.wpn`; 8 vanilla weapons have no file under their id. The log line
    `S3D: compatibility report: ... (N ms)` gives the time it takes, so watch the load time.
  - The report's script lists are `ShipBridge.MODELLED_EFFECTS` / `VISUAL_EFFECTS` and `ExoticBridge.EFFECTS`. Keep them
    in step with the switches; a diff of the switch case labels against the sets matched when this was written.
  - Offline preview against vanilla's files: 0 unknown vanilla weapon scripts; 10 systems matched by type (drone
    launchers, energy_conversion / skimmer_drone as DISPLACER); **7 vanilla systems not in 3D**: flarelauncher_single
    (type WEAPON, AI FLARE_JAMMER), dynamic_stabilizer, inferniuminjector, interdictor, cryoflux, chiral_figment,
    traveldrive (campaign Full Burn, probably irrelevant in battle).
  - Not done: a Java API for behaviour no kind or effect covers (MODDING.md says it may come later); the report doesn't
    check hullmods.

- **2026-10-13 23:35 — MIRV spread is 3D (user: "make the MIRV spread 3D instead of flat"). `S3D_ONLY=missileGuidance`
  (1 new check). Awaiting in-game test.**
  - `Combat.split`: warheads used to fan out in one plane (the lead direction x a horizontal side vector). Now each turns
    away from the spread's centre by the angle vanilla's fan gives it, toward any side of it. Even spread (Hurricane,
    Hydra, Sabot): vanilla's mirrored pairs keep their angles, each pair on its own spoke, spokes evenly round the
    half-turn, the whole pattern rolled at random per split: Hurricane = one straight back + pairs at 30/60/90 deg on
    spokes 60 deg apart (a three-armed flower); Sabot = a + of shells at 5/10 deg. Random spread: angle uniform up to
    half the arc (as vanilla's), any direction round the centre. Arc offset (backward spill) and spreadInaccuracy as
    before. Bench: Hurricane angles 0/30/30/60/60/90/90 exactly, flattest plane through two pushes leaves one 49 deg
    out, 7 of 7 still hit.

- **2026-10-13 23:15 — Phase cloak flux and speed as vanilla, yellow gun crosshairs (user: "the Ziggurat boss (TTS
  Xenophornica) has unlimited flux and never unphases; phasing uses way less flux than vanilla; add the phase-ship speed
  loss; make the + crosshair a contrasty yellow"). Full bench 129/129 (4 new, `S3D_ONLY=phaseRules`). Awaiting in-game
  test.**
  - **Why phasing was free**: vanilla's ship_systems.csv marks the phase cloak `hardFlux` and `noHardDissipation`. We read
    the first but not the second, so the hard-flux upkeep dissipated as it came in (Ziggurat: 320/s upkeep vs 1200/s
    dissipation = phased forever). Now `ShipSystem3D.hardDissipationAllowed` (from `isHardDissipationAllowed()`) ->
    `ShieldFlux.noHardDissipation`: soft flux still dissipates while phased, hard flux stays until you unphase.
  - **Ship clocks**: vanilla advances each ship (systems, flux) by battle time x its own time mult. Ours stepped every
    ship's systems and flux on the battle clock, so a phased AI paid a third of its upkeep per battle second, and while
    you were phased everyone else's system timers ran 3x too fast for the slowed world. Now `Ship3D.clock` (last frame's
    world scale x time mult) drives each ship's systems, and flux steps at battle dt x time mult. Bench: AI phased 2 s ->
    2144 hard flux (3x clock: 2240, old: 960 minus dissipation).
  - **Phase coil stress** (vanilla `PhaseCloakStats`): while cloaked, top speed = 33% + 67% x (1 - min(1, hard flux
    level / 50%)) — so it falls from the moment you phase (the upkeep is hard flux) and recovers as hard flux dissipates
    once you're out. Constants read from PhaseCloakStats' public fields; the 50% threshold from the live ship's
    `PHASE_CLOAK_FLUX_LEVEL_FOR_MIN_SPEED_MOD` (Adaptive Phase Coils +50%); phased speed / accel bonuses from
    `PHASE_CLOAK_SPEED_MOD` / `ACCEL_MOD` (Phase Mastery +100% top speed, Phase Corps). New `Ship3D.sysTopSpeedMult`
    scales the whole envelope (forward, reverse, strafe, vertical, cap). Panel state: `PHASED  SPEED 60%` (red) under
    stress. Not copied: vanilla also multiplies top speed and accel by the cloak's fade level during the 0.5 s in/out.
  - **AI boss now has to come out**: under constant fire a Ziggurat AI was phased 64% of 40 s and came out 4 times,
    hard flux peaking at 85% (its unphase threshold). Before: phased the whole time.
  - **Right-click systems that aren't cloaks**: the Invictus (Canister Flak) and Vanguard (Damper) have their defence
    system in the phase-cloak slot; 3D turned it into a phase cloak (log showed `phase cloak Canister Flak -> PHASE_CLOAK`).
    Now only `isPhaseCloak()` systems become cloaks; others keep their own kind, right click uses them, the AI uses them
    by their kind.
  - **Gun crosshairs**: each selected gun's "+" is now solid saturated yellow (2 px arms) with a 1 px dark rim, drawn
    over the line pass; not-ready guns at 55%. Kill markers keep the red-orange. Offline:
    `S3D_PREVIEW_ONLY=shipPanel` -> build/preview/ship_panel*.png (new `ship_panel_phase.png`: phased Ziggurat).

- **2026-10-13 — Missile guidance and fuses audited against vanilla's missile AIs (user: "Hurricane MIRV launcher not
  working as intended; check each weapon against its 2D counterpart"). Bench 125/125 (7 new, `S3D_ONLY=missileGuidance`),
  jar rebuilt 22:54. First half got into commit 58e94d4; the rest is uncommitted. Awaiting in-game test.**
  - Read vanilla's behaviour from its data files and (for the order of events) its missile AI classes by type; rebuilt in
    our own code, nothing copied. Each missile now flies by `WeaponSpec3D.guidance`, from vanilla's missile type:
  - **Hurricane was broken**: its warheads are vanilla type MISSILE_TWO_STAGE_SECOND_UNGUIDED, which 3D read as "no
    target", so all seven fanned out backwards (arcOffset 180) and burned away from the target. Now as vanilla: they
    spawn facing the target, drift out along the backward fan with no thrust while they line up on the lead (flight
    clock held), lock on (nose within 1 deg after 0.5 s, or after 3 s, or the target phases) and burn straight in,
    unguided. Bench: 7 of 7 hit, fanned ~220 wide when they lock.
  - **Two-stage** (TWO_STAGE: Squall, Hurricane warheads, Threat unstable fragments): the same line-up / lock / straight
    burn. Squall used to home all the way (undodgeable); now a ship that moves after the lock is missed, as in vanilla.
  - **MIRV split rule** (Sabot, Pilum, Hydra, Hurricane): splits within split range of the hull after its minimum time,
    or earlier if `canSplitEarly` and it would get there before then (Hurricane, Sabot); only with its nose on the
    target (within the fan arc less 1.5 deg, or anywhere on the hull for arc-0 Pilum: vanilla allows a tenth of the
    target's apparent size, our noses lead so the whole hull counts). Near the split it leads for its warheads' speed.
    `evenSpread` (random within the arc otherwise), `spreadInaccuracy`, and shell warheads' `projectileRange` (Sabot 600)
    are read. The fan's centre stays the warheads' lead (vanilla: the MIRV's nose, which the lead steering keeps there).
  - **Heatseeker** (Salamander, Salamander pod): vanilla circles round to within 45 deg of the target's tail before it
    goes in; it used to home straight at the nose. Now it orbits 225-325 beyond the hull (swinging wide inside that,
    in when outside, random 15-30 deg weave rerolled every 1-3 s) toward the tail, then commits. Fighters and station
    sections it goes straight for. Bench: strikes 58 behind the centre (homing: 152 in front).
  - **Proximity Charge Launcher** (PHASE_CHARGE, also the bomber version): flies to the point it was aimed at, brakes to a
    stop there and waits for its fuse; it used to home on the ship.
  - **Fuses** (vanilla ProximityFuseAI): fused shots now also go off by enemy ship hulls (flak bursts just short of a
    hull instead of needing a direct hit), fused shells burst where their range runs out (flak airbursts), with
    `shotRangeVariance` (Devastator up to 75% short, Heavy Adjudicator 20%), and fused missiles burst when their flight
    ends instead of fizzling: **Canister Flak's random fuses now actually go off**, as do proximity charges, Stinger
    mines and unstable fragments.
  - Blasts (areaBlast, all of them: flak, mines, rifts...) and the fuse test trace toward the hull's nearest point when
    the line to its centre misses, so a burst beside a wing or a long nose hurts it (vanilla: anything within radius).
  - Checked and already matching vanilla: gun cycle (charge, burst, cooldown), beam cycle (charge up, sustain or burst,
    charge down, cooldown), rockets and bombs straight, homing MISSILE type, DEM missiles, flares, mines.
  - Not modelled (minor): vanilla homing missiles' small random course weave. (The flat MIRV fan became a 3D spread, 23:35.)
  - Log line per weapon now names the guidance ("two-stage (aims, locks, burns straight)", "heatseeker (goes for the
    tail)", "to the aim point, then waits"), split details and fused-shell variance.
  - Unverified until played: Hurricane / Squall look and feel at 3D scale, Salamander orbit vs fast frigates, more flak
    explosions on screen (perf), proximity charges from fighters.

- **2026-10-13 — Ship systems batch 4: Mote Attractor, the Threat's and the Shrouded's systems, Swarm Launcher and
  Churning Locus, and vanilla's swarm / shroud looks (user: "make the next batch"). Built from each vanilla script's
  behaviour and look. Full bench 118/118 (with the station / range session's changes); jar rebuilt with both; awaiting
  in-game test.**
  - Threat (`core/ExoticSystems`): Fragment Volley, Incursion Mode and Extraction Protocol hold no charge of their own;
    only an Overseer's Energy Lash fires them (vanilla's lash-activated systems). The Overseer lashes the unit whose
    system would help most right now (vanilla's usefulness rules) or an enemy ship (1500 EMP from each system slot; a
    phased target is knocked into a 1 s overload). Lash cooldown 2 + 0.33 x the unit's DP (max 10), 2-5 s on enemies
    (x2 if phased). Volley: fragment weapons x2 range, cooldowns cut to 1 s, reloaded, fragments regrow x10. Incursion:
    +100 speed, ammo regen x5, dissipation x3, reckless AI. Extraction: +100 speed backing off, then vents and reloads.
  - Construction Swarm (Fabricator, needs vanilla's MIN_FRAGMENTS): a swarm (a real vanilla wing) drifts out at a third
    speed for 3-6 s, settles and builds what vanilla's own `ConstructionSwarmSystemScript.pickVariant` picks (build
    time from DP, Overseer extra, first two frigates fast, `host/BuildPicker`), then the ship appears and the swarm is
    gone. One build at a time per Fabricator.
  - Shrouded: Convulsive Lunge (Tendril, Maw, vortices) pulls 1000 toward the aim (no aim: straight back) with
    vanilla's spring and friction, decelerating, shield off at the peak. Darkened Gaze (Eye): its all-round shield
    always faces back; on, it opens 90 deg at the front and the gaze beams fire through; turn x0.15, hull damage taken
    x2. Tenebrous Expulsion (Maelstrom): ejecta every 0.15 s within 90 deg of the aim for a second, then pushed back.
  - Spawned units (`core/SpawnRequest`, `core/SpawnBehaviours`, host `spawnRequested`): the host spawns real vanilla
    wings / ships through the fleet manager and mirrors them where the 3D side asked. Swarm Launcher fires attack swarms
    (holds once its wing count is out, until one is left). Churning Locus fires shrouded vortices (half speed if fired
    more than 150 deg off the nose). Vortex: chases the nearest enemy ship it can reach before it decays (5%/s), rams
    (knocked to 10% speed, decays faster per ram), lunges when close, bursts when gone (vanilla damage, enemies only).
    Ejecta: slows to 10% speed, decays 6.67%/s, bursts.
  - Mote Attractor (Ziggurat, `core/MoteControl`): motes stream out (every 0.15-0.25 s, up to 30) and flock by
    vanilla's rules (kept off ships, apart from and in step with each other, pushed out from close in and drawn back
    from far out, carried along with the ship; they change heading by swinging their velocity, not turning). They go
    for enemy missiles (a touch destroys one) and fighters (extra damage) within 2000. Using the system puts an
    attractor at the aim for 10 s, or locks onto the enemy ship under it for 20 s: an arc shoots out and the motes go.
  - Looks, `render/CloudRenderer`, with vanilla's own sprite sheets loaded from the game at runtime
    (`render/FxSprites`): Threat fragments are one shard per fragment round the hull (vanilla's counts: 20/40/60/100
    by size), flashing while fragment weapons are loaded. Shrouds are crimson blobs that live 1-3 s with red glows,
    under subtracted dark particles (the murk). A Lunge's windup streams the blobs toward the point; the Gaze clears
    the front. Each ship's numbers come from its live vanilla effect (`ExoticBridge.swarmLook`, retried each frame
    until vanilla has made it); construction swarms are sized as vanilla's script sizes them. Shroud sprites fade near
    the camera so being inside one isn't a black screen (3D only). New fx shader modes 9 (soft puff) and 10 (sprite).
  - Fixed on the way: the lunge held its forward speed afterwards (it now decelerates, as vanilla's DECELERATE); the
    lash missed phased targets (their cloak hadn't stepped yet that frame: `Ship3D.phasedNow()`); motes ram missiles
    with a swept test. "PD kills fighters" fixture re-tuned for range 1.2: its raider stays on the gunboat (no
    carrier, a harmless gun) instead of flying home out of PD reach.
  - Bench `S3D_ONLY=systemsBatch4` (13 checks); preview `S3D_PREVIEW_ONLY=exoticFx ./build.sh preview` (exotic_fx.png).
  - Unverified until in game: fleet-manager spawns of attack swarms / construction swarms / vortices / ejecta
    appearing where asked and getting twins (log "spawned", first 3 of each kind), the Fabricator's picks (log
    "construction swarm to build"), the live swarm parameters (log "swarm look" / "shroud look"), the looks in motion.

- **2026-10-13 — Ships ignore station spurs; stations turn 4x faster; weapon range +20% (user). Separate session from
  batch 4; compiled + benched in a scratchpad; in the jar rebuilt with batch 4; awaiting in-game test.**
  - Unarmed sections are never picked as targets: `Ship3D.unarmedPart()` = a module with no weapons and no launch
    bays (`hasBays`, set in ShipBridge). That covers Station Structural Spurs (5000 hull / 1500 armour, no guns),
    Station Armour, extensions and struts, and the Onslaught Mk.I's vambraces. Skipped by: ship AI `pickTarget`,
    fighters (unless their carrier's target is one), turret autofire (unless it's the ship's locked target), the
    player's automatic lead target (R still locks one), Quantum Disruptor's fallback pick. Shots that hit a spur still
    damage it; it just stops soaking whole fleets' attention.
  - Safe because vanilla ends a station when its armed sections are gone: the user's Xantu Ravach battle log shows
    the last Heavy Combat Module die and vanilla end the remaining spur and the station in the same frame. The 6 s
    fallback (`checkStationBeaten`) now counts armed sections only and removes leftover spur puppets with the core.
  - `battle.stationSpinDeg` 1 -> 4 (a full turn in 90 s). Bench side effect: the station1 stand-in's armed sections
    die in ~74 s instead of ~125 s and it lands far fewer hits (sections sweep past attackers); watch station danger
    in game.
  - `weapons.rangeMult` 1 -> 1.2. Everything that scales with it grows 20% too, as when it was halved: system ranges
    (Entropy, Teleporter, Mine Strike, Disruptor...), missile flight time, DEM/rift/orb distances, HUD range rings.
  - Bench: "station assault" now wants every ARMED section destroyed, unarmed sections never targeted, and the
    station to hit back at all (was "> 500 damage to attackers"; most of that came while the station fired nothing).
    Full bench 116/118: the 15v15 timing check (load) and "PD kills fighters" — range only (passes at 1.0): its
    unarmed test fighter flies home, and at the 1.2 fixture scale it leaves PD reach after 52 shots with 1 hull left.
    Fixture needs re-tuning, not the PD logic. (Done in the batch 4 entry above: 118/118.)

- **2026-10-13 — Ship systems batch 3 (user: "start working on the weapons and ship systems"), and rockets fly straight.
  Built from each vanilla script's behaviour and look. Bench 106/106; jar rebuilt; awaiting in-game test.**
  - Rockets and bombs (vanilla missile types ROCKET, BOMB, BOMB_WITH_SLOW: Hammer, Reaper, Annihilator, Squall, Sabot
    warheads, the Reality Disruptor shot, canisters, cluster bombs) fly straight whatever turn rate their engine has,
    as in vanilla (user's call). Guided missiles unchanged.
  - Orion Device (Retribution, Venture Pather) and Nova Burst (Nova), `core/OrionDrive`: a charge drops out of the
    rear system slot and goes off behind the push plate (Orion 0.25 s later, Nova at once) in a cone of cloud away
    from the ship (vanilla's particle counts, sizes, speeds and arc; Nova adds vanilla's big blue-white flash); the
    jolt pushes the ship forward hard for a moment (Orion 5000 for 0.25 s, Nova 10000 for 1 s, tapering) with top
    speed +500, then it brakes back to normal within 3 s. One press fires the whole train (user's call): every charge
    left, one per cooldown (Orion 4 a second apart, Nova 2), no braking between jolts so they stack into one sprint.
    Orange (Nova blue) jitter on the hull after each jolt, detonation sound, camera jolt when it's yours. Bench: Orion
    108 -> peak 890 -> back to 108; Nova peaks near 3100 for a moment: that is vanilla's formula (twice the push for
    four times as long), say if it feels too much.
  - Combat Burn (Onslaught Mk.I): +100 top speed and acceleration while on, steerable, its engine glow settings.
  - Quantum Disruptor (Harbinger): pink jitter while charging (vanilla's ranges, growing to +50), then the target (or the
    nearest enemy ship within 500 + system range bonus) is knocked into a 1 s overload with pink arcs; it drops its shield.
    The AI uses it on a shielded target its guns can reach.
  - Reserve Deployment (Gemini): the host runs vanilla's own public ReserveWingStats on the carrier puppet's bays, so
    the extra fighters come out exactly as in 2D and get 3D twins like any others.
  - Drone launchers (stations' Merlon / Ravelin / Gargoyle drones, Point Defense Drones): the puppet keeps that one system
    on so vanilla launches real drones; each gets a 3D twin that launches out of the mothership to its orbit from the
    launcher's own behaviour table (radius beyond the hull, speed, direction, facing, by slot index) and fights from it.
    Unverified until a station battle: that vanilla launches with the puppet's AI frozen (log: "drone ... from").
  - Bench `S3D_ONLY=systemsBatch3`; previews: system_fx.png has the new tiles, orion_side.png shows Orion and Nova
    from the side.
  - Next batch: Mote Attractor (Ziggurat), the Threat units' and Shrouded ships' systems, Swarm Launcher and Churning
    Locus (vortex launcher).

- **2026-10-13 — Ship system, vent and overload looks rebuilt to match vanilla's 2D ones (user: "don't resemble their
  original 2D visuals"). Looks only. Bench 100/100; jar rebuilt; awaiting in-game test.**
  - Went through each vanilla system's data file and stats script and rebuilt its look from vanilla's own building
    blocks, in `core/SystemLook` (defaults per kind; the host overrides them from the system's own data file at
    runtime, so other mods' systems keep their colours):
    - Jitter (tinted copies of the ship at fresh random offsets every frame, vanilla's ranges in sprite pixels):
      "under" copies sit just behind the hull so only a fuzzy halo shows; "over" copies just in front tint it. At
      most 10 copies drawn per layer, brightness of the rest kept. Damper orange, Temporal Shell blue (it was red),
      Displacer / Phase Teleporter blue, Mine Strike pink, Entropy Amplifier red on the ship (charge-up only) and
      on its target, Targeting Feed orange-red / Recall blue / Termination Sequence orange on the carrier's
      fighters.
    - Weapon glow (the weapons drawn again, tinted, plus a soft halo so it reads at chase distance): High Energy
      Focus magenta on energy weapons (it was a blue hull glow), Ammo Feed yellow on ballistics (was orange), Lidar
      Array red on its non-beam ballistic/energy hardpoints, Targeting Feed on fighters' guns.
    - Engines from vanilla's engine glow settings: Burn Drive 2.5x long, 3x wide, 2x glow in the ship's own colour
      (no white-hot); Microburn and Plasma Jets green (Plasma Jets were violet); Temporal Shell blue and 25% shorter;
      Displacer blue; phase cloak and Phase Teleporter put the flames out. The hull's system-only thrusters
      (vanilla's system-activated engines, now read from the hull; looks only, no thrust, not EMP targets) light
      while a movement system runs.
    - Fortress Shield takes the shield to its own colours (white ring, magenta inside; was gold).
    - Phase: a quarter visible at full phase as vanilla, with the cloak's pink highlight along the silhouette.
    - EMP Emitter arcs in the system's effect colours (pink); Lidar dishes (vanilla decorative beam weapons, now
      read from the hull) fire their ranging beams at the aim while it winds up, in the dish's own beam colours.
    - Venting: clouds in the hull style's vent colours (white core, violet fringe on most hulls) over the whole
      hull, and a soft glow in the fringe colour; overload arcs in the hull style's overload colour. Both read from
      the ship at battle start.
  - Removed, as vanilla has nothing like them: the entropy tether and targeting-feed lines, temporal afterimages,
    activation rings and flashes, burn-drive sparks, jet puffs, mote particles, reload flashes, canister muzzle
    flashes, the EMP charge crackle, the blink trails and the gold/blue/orange hull auras.
  - Preview: `S3D_PREVIEW_ONLY=systemFx ./build.sh preview` -> system_fx.png. Not visible offline (needs the real
    hull): system-only thrusters, lidar dishes, fighter jitter.
  - Flaky check: "DEM stands off, paints, fires" failed once in four full runs earlier (payload hull loss 747 of
    ~840; the closest approach varies 400-425 between JVM runs). Not related to these changes.

- **2026-10-13 — Polish pass 1 (user: "polish tf out of the game"): deaths, retreats, battle start/end, camera,
  hit feedback. No gameplay numbers changed. Bench 100/100; jar rebuilt 15:22; awaiting in-game test.**
  - Ship deaths: the big blast now comes with a white flash, an expanding shockwave ring (new effect kind RING,
    fx shader mode 8) and hot debris flung off the hull; then (not fighters) 2-6 secondary explosions pop across
    the hull over ~2 s, each with its own sound (effects can now be delayed and carry a sound), and the wreck
    burns for 6-14 s (flames off its weapon mounts, the odd small pop), dying down at the end.
  - Retreats: a ship leaving the battle (past its edge, or taken early by vanilla: the "left the vanilla battle by
    itself" case) jumps out with a blue flash stretched ahead and a ring plus the burn drive sound, instead of
    blinking out.
  - Battle start: the 3D view fades in from black (0.6 s) while the camera swoops in from behind and above your
    ship (2 s, ease-out). Battle end: when vanilla calls the battle, the 3D scene plays on for up to 6 s with the
    HUD off (no write-back to vanilla any more) until vanilla's own screens come up, instead of cutting to the
    empty 2D map.
  - Camera: switching ships glides instead of cutting (taking over your next ship 0.9 s; spectate [ / ] and leaving
    spectate 0.6 s). `ChaseCamera.glideTo` / `introTo`.
  - Hit feedback, no text: diagonal ticks round the reticle when your shots land (white hull/armor, blue shields),
    bigger red-orange ticks spreading out on a kill (ships 0.8 s, fighters 0.5 s): this replaces the kill-feed
    toasts. Hull damage you take shows as a red arc round your ship on the side the attacker is (faint full ring
    when it's straight ahead/behind), stronger for heavier hits.
  - Fixes: the automatic target stuck on a wreck when no other enemy was near the reticle (target panel showed a
    dead ship at "HULL 100%" indefinitely); AUTO-LEVEL only shows on the status line when it differs from
    `flight.autoLevelDefault` (it was permanently on screen).
  - Preview: `S3D_PREVIEW_ONLY=deathFx ./build.sh preview` -> death_fx.png (kill sequence), jump_out.png,
    intro_hurt.png (start swoop + damage arc).

- **2026-10-13 — UI/UX cleanup (user: "too many hotkeys"); fighters no longer hold a battle open; Burn Drive steers.
  Headless checks pass; awaiting in-game test.**
  - Keys: `H` (help overlay) removed, along with the overlay itself; the controls are listed under How to test.
    Free camera removed (user's choice over moving it to J): `T` is always tactical view, also while spectating
    (tactical view of the watched ship). `camera.freeSpeed` is gone from the tuning file.
  - Text removed from the HUD: the spectate banner and its key hints (a one-word SPECTATING joins the bottom status
    line instead), the pitch/roll readout under the attitude ball, the `[F]` / `[Z]` key labels in the ship panel,
    "LOCKED" on the locked ship's bracket (the double bracket marks it; the target panel still says LOCKED),
    INCOMING MISSILES (the red markers on the missiles stay), the battle-start hint, and toasts for weapon group
    switches, autofire on/off, auto-level, HUD on, spectate on/off, wings engage/regroup. Kill-feed toasts are gone
    too: ships destroyed/lost, sections, stations, retreats, reinforcements, objectives taken. Kept: failure
    feedback (can't vent, system not in 3D yet, no wings), the F9 confirm, command transfer, tuning reload, alerts.
  - Gun pippers ("+" per weapon at the lead point) are red-orange instead of green.
  - AUTOFIRE box: shows the group's setting even for the selected group (it used to stay empty while selected,
    so Ctrl/Cmd+number on the selected group looked like it did nothing). The selected group is still fired by hand.
  - Fighters: a wing whose carrier retreats (or leaves the battle any other way) leaves with it (twin removed,
    puppet removed from the engine); wings whose carrier died fight on only while their side still has a ship in
    3D; parked fighter puppets whose carrier or side is gone are removed too. Their puppets are pinned to the 3D
    twins and never reach an edge, which is what kept vanilla from ending the battle. AI carriers ordered to
    retreat call their wings back (regroup + vanilla pull-back). Log: `fighter ... left the battle: <why>`, and the
    10 s fighter line counts "left with their side".
  - Burn Drive: vanilla's spec says no turning, which zeroed steering on top of the burn's own turn penalty. The
    3D Burn Drive now ignores that flag and keeps `systems.burnDriveTurnMult` of the turn rate (0.2 -> 0.5): bench
    Wolf turns 49 deg in 3 s at full stick while burning (about 90 normally). Bench check updated to use vanilla's
    no-turning flag.

- **2026-10-12 — M5 (hull-by-hull model pass) cut from the roadmap** (user). Relief meshes stay; the OBJ loader and
  Blender kit below stay in, unused. See Next steps.

- **2026-10-12 (late) — Hull models (M5 groundwork): hand-made OBJ hulls load in place of the relief; first model (Wolf)
  previewed offline, staged, not in game yet.**
  - Game side: `data/meshes/hulls/<hullId>.obj` replaces the sprite relief for that hull (skins and (D) hulls fall back
    to their base hull's file and repaint it with their own sprite). Looked up once per hull through the game's loader
    (`host/HullMeshes`), so other mods can ship models for their hulls. `render/ObjHull` parses the OBJ (ship-local
    pixels, +Y up, -Z forward = Blender's default export of a nose-to-+Y model), projects the sprite from above as the
    texture, and rasterises the hit volume from the model (lowest to highest surface per grid column). `K` re-reads
    model files. A sprite of another size than the header's `# s3d sprite w h` keeps the relief.
  - Shading: model faces use a new "auto" class in the ship shader that blends the relief's top / side-plating /
    underside looks by the surface normal (relief meshes render exactly as before).
  - Authoring: `src-art/` (see its README). One Blender Python script per hull lays parts over the sprite with
    `s3d_kit.py` (sprite-outline plate, lofted bodies, domes, rings, engine bells, mirroring) and exports the OBJ;
    `src-art/build.sh <hullId>` runs Blender headless (under a second), `--grid <hullId>` draws the sprite with a pixel
    grid to read part positions off. No game art is copied: the sprite is read at build time, only geometry is written.
  - Wolf (`src-art/hulls/wolf.py`, 6.8k triangles vs the relief's 13.5k): spine over a ventral keel, bridge canopy,
    shoulder nacelles, missile pods on thin pylons, rear turret blisters with rings, three engine bells sunk into the
    stern. Hit volume checked against the relief: same solid volume (x0.99), same outline at the mid-plane, 26 px tall
    instead of 19; turrets now sit on the modelled rings. Preview: `build/preview/model_wolf.png` (relief vs model,
    6 angles) and `model_wolf_close.png`.
  - Staged in `build/art/hulls/wolf.obj` (the game doesn't read it) until you've looked; to try it in game, build it
    without `S3D_MODEL_DIR` (writes `data/meshes/hulls/wolf.obj`); the 23:27 jar already has the loader.
  - Preview tool: `S3D_PREVIEW_ONLY=hullModels`, `S3D_MESH=relief` (ignore models), `S3D_MODEL_DIR` (staging folder).

- **2026-10-12 (late) — AI: more opportunistic shooting, more caution; ranges halved, speed +50%, armor -30%. Headless
  bench 100/100 pass; awaiting in-game test.**
  - Balance (tuning file + code defaults): `weapons.rangeMult` 2 -> 1 (vanilla ranges; missile flight time and system
    ranges scale with it as before); `flight.speedMult` 0.575 -> 0.8625 (+50% top speed; acceleration, turning,
    strafe share unchanged); new `weapons.armorReductionMult` 0.7: armor's damage reduction is vanilla's x0.7 (the 85%
    cap becomes 59.5%; e.g. 50 kinetic hit strength on 1500 armor: 20.25 of 100 gets through instead of 7.5), one
    formula `Combat.armorDamageMult` for every hit. `weapons.convergence` 1200 -> 600 (unaimed fire converges at
    about the new ranges).
  - Caution (`ai.caution`, 0 = off): AI ships work out how much enemy gun dps reaches a point (each ship's guns by
    range, missiles left out; overloaded/venting guns count a quarter, guns facing away a bit over half). Against a
    target they outrange they fight from just outside its guns (backing off in full reverse; a faster ship that can't
    back off fast enough turns and runs out, then comes about); enemies other than the target that have them under
    their guns push them out sideways (and further back when those guns are round the target); at 75% flux they back
    out of the target's reach until under 40%; they vent only once no guns reach them; flank spots, targets and gun
    runs inside the enemy's crowd count against; no gun runs at a target they outrange (or a bigger hull whose guns
    are on them); they don't dive onto a venting target they already outrange.
  - Opportunism (`ai.opportunism`, 0 = off): a mount whose shot is blocked (friendly in the line, missile not worth it)
    turns to another enemy it can reach, also while still swinging on (no flip-flopping between the two); among
    enemies in reach a mount takes the better shot (helpless > unshielded from where it is / nearly dead > nearest);
    zero-flux weapons never hold for flux; shots that land on armor fire up to 95% flux (others 85%); ships prefer
    targets that are hurt, flux-locked, already in front of their guns, or outranged; fighters dogfight enemy
    fighters at about their speed instead of flashing past.
  - Fixes the halved ranges needed: big hulls' "fight from 2.5 radii" floor is now capped at 85% of their guns'
    reach (counting how far out the mounts sit), or capitals sat out of range for good (bench Conquest: 0% -> 95% in
    range); avoiding station sections now counts the hull beside the line to the section's centre, where it is
    within a second, and pushes harder inside the margin (attackers fight close beside sections now and were
    scraping the neighbouring one: 8 of 10 seeds clean, the rest graze for a tenth of a second).
  - Bench: new-vs-old AI, mirrored 6v6 (Wolf, Lasher, Hammerhead, Enforcer, Eagle, Dominator), 6 seeds x both sides:
    new wins all 12 on damage (360k vs 75k dealt, 16 vs 71 ships lost); mounts with an enemy in reach fire 82% of the
    time (old 50%; flux holds 8% vs 31%), time inside enemy guns' reach 25% (old 34%). Set pieces: a Wolf with
    800-range guns vs an Enforcer with 600: in its reach 10% of the time (careless 82%), no damage taken, 3.8x the
    damage dealt; a Hammerhead fighting beside a second enemy: in the second's reach 5% (careless 33%); a gun
    platform with a friendly in its line of fire: 71 shots at another enemy (careless 0); Talons vs fighters 3/3
    (careless 1/3). New-AI 4v4 battle pace: contact at 30-37 s (old setup 23-31 s), 4-7 of 8 dead in 4 min, ships
    move and turn about twice as fast relative to each other.
  - Bench fixtures laid out for 2x ranges now scale with `rangeMult` (`CombatBench.fk`); `S3D_ONLY=<method>` runs any
    one check group, `S3D_AB=<seeds>` the new-vs-old fleet battles. Two thresholds relaxed at rangeMult 1, both
    from fighting at half the distance: hardpoint lances land 70%+ (85% measured; 100% at 2x), station attackers may
    graze a section up to 15 frames.
  - Watch in game: capitals at 2.5x scale now fight nearly hull to hull (vanilla ranges barely exceed their length);
    whether cautious ships feel too passive against equal-range fleets (they don't stand off unless they outrange
    the target); whether fast frigates kiting slow brawlers looks good or silly; station fights at close quarters.
    Knobs: `ai.caution` / `ai.opportunism` (0..1, K reloads), `weapons.armorReductionMult`.

- **2026-10-12 — Fixes from your play session: venting stuck forever, Ctrl/Cmd+number autofire; lag measured.**
  - Venting: your Eradicator (Chantico) has Safety Overrides, whose script multiplies the vent rate by 0 (vanilla:
    "prevents the use of active venting"). The 3D side let the vent start and then drained at 0/s, so the ship sat in
    VENTING (no shields, no guns) for good; AI ships with Safety Overrides could lock themselves the same way. Now
    `ShieldFlux.canVent()` (vent rate and dissipation above 0) gates every vent: the key does nothing but a toast ("Can't
    vent: Safety Overrides disables venting"), no vent sound, and the mirror log says "can't vent". Headless: SO ship
    never enters VENTING and keeps dissipating; normal ship vents 5000 flux in under 12 s.
  - Autofire: Ctrl + 1-7 (vanilla's binding) and Cmd + 1-7 toggle that group's autofire (`PilotInput.toggleAutofire`,
    edge-polled once per press; the number-key event no longer selects the group while Ctrl/Cmd is held). Toast "Group 2
    autofire ON/OFF"; turning it on for the selected group says it starts once you switch away. In the H help.
  - Lag (17-30 fps, 200-700 ms hitches, 43-46 ships): this battle's sim and render ran 5-20x slower than the same code
    paths in yesterday's 44-69 ship battles (58 fps, sim 0.2-0.5 ms, render 2 ms). During it the Mac was at load average
    16-30 (Chrome ~390% CPU, a Minecraft launcher JVM ~85%) with swap in use; the game has a fixed 6 GB heap on a 16 GB
    Mac, so GCs that touch swapped heap stall for hundreds of ms. Offline A/B under the same load, committed baseline vs
    now: render benchmark (60 ships) 9.4-14.6 ms vs 8.8-12.8 ms; 15v15 + 40 fighters sim 1.32-1.33 ms vs 1.06-1.36 ms.
    No regression in the mod's code; jar rebuilt 19:10. If it lags again, check Activity Monitor for Chrome / other JVMs
    and swap before blaming the mod (the in-game "S3D perf" log lines give sim/render ms per 10 s).

- **2026-10-12 — Your ship panel (bottom left) redone in vanilla's layout, hologram style; previewed offline, jar rebuilt
  18:42, awaiting in-game test.**
  - Replaces the plain text weapon list (`HudRenderer.drawShipPanel`). Same arrangement as vanilla's green panel, in
    the target display's cyan: your hull as a hologram (same shader as the target's: armor damage by direction, dimmer
    as hull drops), 160x148 px against the target's 192x162, nose up, swaying +/-17 deg, with corner brackets. Under it
    the ship's name, class and variant ("ISS ZENITH / EAGLE-CLASS / ASSAULT HEAVY CRUISER"; new `Ship3D.variantName`
    from the variant's display name + designation). A slanted divider runs from the bottom edge up to a rule.
  - Above the rule: FLUX bar (hard part darker) + flux value, "0-FLUX BOOST" when it's on; HULL bar (cyan, amber under
    50%, red under 25%) + hull points, then the ship state where vanilla says HOLDING FIRE (SHIELDS UP/DOWN, OVERLOADED
    n s, VENTING FLUX, PHASED, DESTROYED); the system: name, charges, a bar (time left while running, cooldown progress,
    next charge), status + key; phase cloak the same with [RMB]. Wings / fragments / reality-disrupted lines on top.
  - Under the rule: WEAPON GROUPS, each row stepping left along the divider: number, mount icon (vanilla's slot shapes:
    square ballistic, circle energy, diamond missile, sized by mount), "2X HEAVY NEEDLER" (+n for other weapon kinds),
    LINKED/ALTERNATING, readiness bar (amber while cycling), "DMG: KINETIC", AUTOFIRE box (filled = autofiring; the
    selected group is fired by hand), ammo, "n OFF" / "OFF n s" when EMP'd. Selected group: brighter, on a band.
  - Flux bar colours on both panels are a little bluer (soft 0.62/0.7/1, hard 0.42/0.38/1).
  - Preview: `S3D_PREVIEW_ONLY=shipPanel ./build.sh preview` writes `build/preview/ship_panel.png` (Eagle: jets cooling
    down, shields up, a gun knocked out, torpedoes part spent, locked on a damaged Enforcer) and `ship_panel_b.png`
    (Hammerhead: zero flux, ammo feeder on), plus `_crop` close-ups. The preview's weapons now carry their real names,
    sizes and damage types (weapon_data.csv). The full preview suite runs clean.
  - Not done: vanilla's separate selected-weapon box (bottom centre); readiness and ammo sit in each group row instead.
    Unverified in game: text size at your resolution (0.8 of the 13 px HUD font, like the target panel), and whether
    vanilla's CR / peak-performance readout (above where its panel was) still shows and crowds the hologram.

- **2026-10-12 — Playtest feedback round (your list of 8); headless-tested, awaiting in-game test.**
  - Speed +15%: `flight.speedMult` 0.5 -> 0.575 (acceleration and turning unchanged).
  - Up/down: **Shift** down (was C), Space up. Vertical strafe speed, acceleration and braking x1.3 (`flight.verticalMult`).
    The AI's weave keeps its old vertical pace (jink goals / verticalMult): at the faster pace the weave carried AI ships
    out of their own Canister Flak screen (bench, 11 seeds: 0-5 missiles killed of 8 instead of 3-5).
  - Runway: `battle.deployFrac` (0.4 of the half-map, 1800 on an 18000 map) replaced by `battle.deployDistance` 2700
    from the centre line (at most 85% of the way to the edge), so 5400 apart. Bench fleets, frigates to capitals: first
    shots after 22-37 s (was 10-20 s), first hits 33-46 s.
  - Vanilla's green ship panel (bottom left: the frozen puppet's hull/flux/weapon groups/system, which is why weapon
    changes, ammo and system recharge never showed there) is hidden while the 3D view is up (`CombatUIAPI.hideShipInfo`
    every frame; `reFanOutShipInfo` for vanilla's menus and when the battle is handed back). The 3D HUD's own panel shows
    all of it. Unverified: whether that hides everything you meant; the API has no way to hide the rest of vanilla's HUD.
  - AI shields: vanilla's unfold times are 2.2-3.7 s (log: Eagle 3.65, Omen 3.33), but the AI raised its shield only for
    fire at most 1.2 s out and dropped it after 0.6 s of quiet, so the shield was never fully up. Now it stays up while an
    enemy has the ship within its weapons' reach (plus closing speed x unfold time) and flux is under `ai.shieldFlux` 0.6;
    above that the old rule (up for inbound fire, down when quiet so hard flux drains). New check "AI keeps its shield up
    under the guns": fully up 100% of the time and 0 hull/armor lost, against 0% and 53 with the old rule. 4v4 skirmish:
    shields up 25% of ship-time (was 12%).
  - AI weapons, Ziggurat's tachyon lances: its lances sit in 5 deg (+/-2.5) hardpoints, and at 2.5x scale the mounts are
    80 units above and 52 beside the centreline. With the nose on the target's centre, the target was 4-6 deg off the
    lance's axis at 1000-1300, so it almost never fired (probe: 6 bursts in four 90 s fights). Two fixes, both for every
    weapon: a ship counts as in a weapon's arc when part of it is (centre within the arc widened by asin(half its radius /
    distance), as vanilla does), and nose ships point their forward hardpoints, firepower-weighted, at the target rather
    than their centre (when the hardpoints carry 30%+ of the nose's firepower). Probe: 112 bursts per four fights,
    landing 100% against an Eagle, a Dominator and a Hammerhead. New check "AI fires its hardpoint lances" (28 bursts in
    90 s; the old code fails it). Limited-ammo missiles are still held for openings on purpose (`missileWorthFiring`).
  - Blackout after clicking outside the game: you saw it in the campaign too, so it's vanilla's fullscreen, not the mod.
    The game runs as an undecorated 1920x1200 window on a 2560x1664 screen; clicking the border deactivates it. Try the
    native resolution in the launcher, or windowed mode.
  - Side note: the SpeedUp mod's "hold middle mouse = 2x speed" also fires when you hold middle mouse for free look.
  - Bench: every check passes except "AI 15v15 sim cost", which failed only while the Mac was loaded (Chrome ~390% CPU,
    load average 8-10). Interleaved before/after runs under the same load showed no regression (1.75-2.26 ms before,
    1.61-2.14 ms after). Arc tests no longer call cos/asin per candidate (cos/sin of each mount's arc are cached), which
    cancels the extra work. "AI omni shield faces the fire" now allows under 1 point lost when the shield was already up
    before the first shot (its old ratio test needed early losses).
  - Unverified until played: `hideShipInfo`; whether vanilla also reacts to Shift (your controls bind it to ship strafe
    and fast-forward); how the always-up shields' upkeep changes AI firing in real fights; hardpoint aiming on odd layouts.

- **2026-10-12 — 15v15 load check spikes: JIT warm-up under Rosetta, plus a NaN aim bug and GC. Fixed what the code can
  fix; the check now judges a warm JVM; the full bench passes (93 checks).**
  - Cause, from a per-frame breakdown the check now prints: slow frames come the first time something happens in the JVM
    (first missile shot down ~9 s, first death 11.4 s, first shot 28 s, first overloads...). HotSpot throws away its
    optimised code for the hot per-ship methods (`Sim.update/threat/autofireAim`, `ShipAI3D.step/manageFlux`,
    `Combat.stepMount`, `ShieldFlux.*`; ~100 deopts per battle), and every ship runs slowly until they're recompiled.
    The cost lands in every phase at once. Under Rosetta (the bench and the game both run the x86_64 JRE) each recompile
    is also retranslated: 10-50 ms frames, some of them stalls (40 ms wall, 2 ms thread CPU). Same battle on the native
    arm64 JDK: avg 0.7 ms, worst under 3 ms even cold. Same battle 3x in one JVM: 19 -> 4 -> 0 frames over 8 ms. No
    algorithmic burst (projectile/missile phases stay under ~3 ms).
  - Bug: `Combat.interceptCurved` let a NaN lead through when the target nearly outruns the shot (t^2 overflows: px Inf,
    py NaN, t NaN, and `t > 30` is false for NaN). The turret's aim stayed NaN for the rest of the battle (bench: a
    Dagger's torpedoes flew as NaN from 11.4 s), and the NaN reaching other ships' threat/shield code set off the biggest
    deopt burst. Now any non-finite answer keeps the linear lead. **Changes combat where the bug hit:** the bench 15v15
    ends 37 destroyed instead of 25 (the bombers' torpedoes land).
  - GC: 77% of the sim's garbage was two temporary vectors in `Combat.canReach`, run for every mount x candidate target by
    `Sim.autofireAim`; more in `ShieldFlux.segmentHit` / `Combat.shieldOnSegment`. Now scratch vectors (new `canReach`
    overload taking the mount's world position), with the same arithmetic in the same order: the battle's end state is
    bit-identical. 30-280 KB/frame (depending on the JIT's escape analysis) -> 4-12 KB; no GC during the battle (was a
    2-4 ms young GC every ~0.6 s).
  - Bench `aiLoad`: battle run twice; the first (other seeds) is reported as JIT warm-up, not judged. Frames over 8 ms are
    broken down (phases via the opt-in `Sim.Profile`, slowest ship, thread CPU vs wall, GC, JIT ms, allocation, shots /
    missiles / effects, deaths). New check "AI 15v15 state stays finite". `Combat.seed(long)` repeats the battle.
  - Numbers, this Mac, `S3D_ONLY=aiLoad`, 3 runs each (the game was running during the "after" runs):

    | | before | after |
    |---|---|---|
    | first battle in the JVM | 12-18 frames over 8 ms, worst 25-51 ms | 7-14 (all while the JIT compiled), worst 18-24 ms |
    | judged battle | 1 (a GC frame), worst 9.5-11 ms, avg 1.23-1.25 | 0, worst 4.2-5.4 ms, avg 1.06-1.24 |
    | NaN | Dagger turret aim from 11.47 s | none |

    The old single cold battle was 8-19 frames over 8 ms, worst 15-31 ms. Full `./build.sh combat` (identical steps into a
    scratch dir, since the game had the jar open): before, 1 failure (this check); after, all pass, judged battle worst
    3.4 ms.
  - In game: the first 3D battle of a session will still hitch at first contact (a few 15-25 ms frames); later battles
    run warm. If that shows, the options are a JIT warm-up (a short headless throwaway battle on a background thread at
    game load) or running the game on a native arm64 JVM (needs arm64 LWJGL 2 natives; outside the mod). Not built:
    your call.

- **2026-10-12 — Weapon scripts, batch 2: Omega, Threat and Dweller weapons, flare launchers, missile blasts (user:
  "start working on weapon batch 2"); headless-tested, awaiting in-game test.**
  - Same approach as batch 1: rebuilt natively, vanilla's scripts never run. Numbers are read from the scripts' public
    constants (`RiftCascadeEffect.MAX_RIFTS`, `KineticFragmentMissileEffect.OTHER_FRAGMENT_DAMAGE`, ...), the minelayer
    weapons vanilla spawns rifts with (damage, EMP, blast radius/core, friendly fire, fuse delay from the mine's .proj),
    and the weapon's own `FragmentWeapon.getNumFragmentsToFire()` (so mods' fragment weapons count too). Numbers that are
    inline in vanilla's code are in the tuning file under `weaponFx2`. Core: `ExoticWeapons` (+ `FragmentSwarm`), host:
    `ExoticBridge`; Combat has small hooks where shots fire, beams play, hits and missile impacts land.
  - Omega:
    - Rift Beam: every ~0.5 s at full power a small rift (200 energy, radius 50) opens by the beam, on an enemy missile or
      fighter within 100 of it if there is one, else somewhere along it.
    - Rift Cascade: once a burst is on a target, 1-5 rifts (more the closer the target: one per 200 of unused range) open
      0.1 s apart, walking round its hull from the hit in the direction the beam travels, each 0.5 s after it appears;
      1000 energy x0.75 rising to x1.25, radius 100, friendly fire (vanilla's PROJECTILE_FF). A beam reaching full range
      opens one where it ends.
    - Rift Lance: vanilla's mine chance is 0, so visual only (if a mod raises it, the rift is modelled).
    - Rift Torpedo: area blast (175 / full within 75), flies through fighters and missiles, flares don't fool it, a rift
      tear where it hits.
    - Reality Disruptor: a slow orb that passes through everything (nothing can shoot it) and, after 0.5 s, arcs ~10x a
      second to the nearest enemy ship or missile within 600 (x rangeMult): 25 energy + 1500 EMP (a raised shield stops
      it); ships it reaches repair weapons/engines at half speed for 5 s ("REALITY DISRUPTED" on your HUD). Fizzles at
      its range.
  - Threat:
    - Fragment swarm: ships with the Fragment Swarm hullmod get a pool (vanilla sizes 20/40/60/100 by hull size with the
      ship's swarm stat mods, regrowing 1/2/3/5 per second; a swarm fighter's 50, no regrowth, and it pays hull for
      fragments it throws). Fragment weapons need that many to fire (Voltaic Discharge 10, spends none); fragment missiles
      spend theirs (Kinetic 5, Seeker 10, Unstable 1, Defabrication 6). Your HUD shows "FRAGMENTS n / size" (red when too
      few). The swarm itself isn't drawn yet.
    - Voltaic Discharge: an instant arc from a point in the swarm to the nearest enemy all round (360 deg), shot damage
      + EMP, PD too.
    - Voltaic Cannon: a hit throws 10-14 EMP arcs (500 each) into the ship's weapons and engines; through a raised shield
      each pierces as often as an ion arc. Flies through fighters and missiles.
    - Voidblaster: shield hits are soft flux. Neutron Torpedo: launched at x0.33 speed.
    - Kinetic Fragments: the 4 escort fragments strike with the missile, 100 kinetic each (fewer as it's shot up).
    - Defabrication Swarm: a hull hit does nothing up front; its damage eats in over 11 ticks (~10 s, armor then hull),
      each damaging tick 75% likely to give the firer a fragment back.
    - Swarm Launcher: not fired in 3D (it launches fighter wings; logged as "launches fighter wings (not in 3D yet)").
  - Dweller:
    - Abyssal Glare: rifts where the beam lands (always when it first lands, then 20% a quarter-second, at least every
      0.5 s): 100 energy (vanilla's half-damage outside Darkened Gaze), radius 75, friendly fire; lightning along it.
    - Rift Lightning: no shell; lightning from the shroud to the first missile / shield / hull along the barrel within
      range (missing: 50-100% of the range out), and a rift there (500 energy + 1000 EMP, radius 80).
    - Inimical Emanation: arc from the shroud to the nearest enemy within 30 deg, reaches phased ships, slows repairs 5 s.
    - Hungering Rift: a hull hit heals a damaged friendly shrouded (Dweller) ship 1000 x its heal stat; non-Dweller
      ships can have at most 10 out at once (battle-wide, like vanilla); blows up on contact (80 / 30, friendly fire).
    - Pseudoparticle Jet: visual only. Vortex Launcher: not fired (launches fighter wings).
  - Flare launchers (missiles of type FLARE, e.g. UAF's Flare Launcher Pod): fire decoy flares that lure enemy guided
    missiles like the flare system's (same tuning); autofire/AI fire them only while an enemy guided missile is coming
    for the ship.
  - Missile area blasts (any missile with a vanilla `explosionSpec`, not just batch 2): on a hit the missile's damage also
    hits everything else in the blast (core full, edge half), friendly fire if vanilla's collision class says so. Affects
    Reaper, Hammer, Atropos, Terminator drone, cluster bombs, Seeker Fragments, Rift Torpedo. Also new for every
    missile: `fizzleOnReachingWeaponRange` (flames out at its range) and pass-through flags; projectiles' pass-through
    flags too.
  - Rifts are drawn as dark tears (reverse-subtract blending) with a coloured halo; orbs the same, pulsing.
  - Bench (`S3D_ONLY=weaponScripts2 ./build.sh combat`): 17 checks, all pass: cascade 4 rifts round an Eagle at 500, all
    within 68 of the hull, 584 -> 4303 damage; rift beam kills 4 of 4 missiles 60 off the beam (0 without); glare 7 rifts
    in 3 s; rift lightning 500, no shell; orb flies through a ship, knocks out 4/4 and 3/4 guns, repairs x0.5, gone at
    range; Voltaic Cannon 8/8 components from one hull hit, 0 through a zero-hard-flux shield; Voidblaster 100 soft / 0
    hard; fragment launcher fires 4 from 20 then waits, regrows, none without a swarm; Kinetic +400; Defabrication 0 at
    impact, 220 over 11 ticks, firer 10 -> 21 fragments; Hungering Rift heals a Dweller 5000 -> 6000 (not a non-Dweller),
    cap 2 holds; flares cut 6 missiles' damage 1800 -> 1200; blasts hit the neighbour (873), a friend only with FF;
    pass-through misses the fighter, hits the ship behind; Voltaic Discharge hits behind, spends nothing, silent with 5
    fragments; Inimical hits a phased ship (plain arc gun can't); fizzle at 1009 for range 1000.
  - Full bench: everything passes except "AI 15v15 + 40 fighters sim cost" (pre-existing frame spikes, avg 1.28 ms;
    the frame-spike session is on it).
  - Unverified until played: everything host-side (this was written without the game): the minelayer specs' behaviour
    JSON keys, `MissileSpecAPI.getTypeString()` = "FLARE", `getExplosionSpec()` on missiles, the Fragment Swarm hullmod
    check and `FragmentWeapon` on puppets, `DwellerShroud.getShroudFor` / `DwellerHullmod` detection, heal stat default.
    Look for the weapon lines at battle start ("rifts", "uses N fragments", "decoy flares", "blast ...") and the
    "fragment swarm" / "shrouded" lines under each ship. Also: rift sizes at 3D scale, cascade placement on stations,
    the darkening blend on your GPU, whether area blasts change torpedo balance.
  - Not modelled: Swarm Launcher / Vortex Launcher (spawning fighter wings from a weapon needs a host bridge to vanilla's
    fleet manager); the Threat/Dweller ships' own systems (energy lash, construction swarm, Darkened Gaze...); fragment
    swarm visuals and swarms trading fragments between ships; EMP on missiles (orb arcs only damage them).
  - Noticed, not changed (out of scope, ask): missiles of vanilla type ROCKET / BOMB with a turn rate home in 3D (Hammer
    30, several bombs incl. Canister Flak's 10, UAF's AWP / Lion Roar / Semibreve rockets); vanilla flies rockets straight.

- **2026-10-12 — Ship systems, batch 2 (user: "start with the next step"); headless-tested, awaiting in-game test.**
  - Picked by what the logs showed in the user's battles as "NOT IN 3D YET": Heavy Missile Autoforge (station modules,
    12x), UAF's "Targeting Laser System" (an entropy amplifier, 7x), Mine Strike (3x), Lidar Array, Termination Sequence,
    Canister Flak (2x each), Phase Teleporter, Degraded Phase Skimmer; plus Targeting Feed (Heron) as a cheap common one.
    Same approach as batch 1: rebuilt natively (`ShipSystem3D` kinds), numbers from the vanilla scripts' public
    constants (`EntropyAmplifierStats.DAM_MULT`, `MineStrikeStats.getRange/LIVE_TIME`, `MineStrikeStationStats.
    MINE_RANGE_STATION`, `TargetingFeedStats.DAMAGE_INCREASE_PERCENT`, `LidarArrayStats.*_BONUS`), the system's JSON
    (`minFractionToReload`, `reloadBaseAmmoAmountOnly`, range, weapon id) and its weapon specs; the few that are inline
    in vanilla are in the tuning file's "systems" block.
  - Mapping: by id, and for other mods' systems by their vanilla system type / AI type where that pins down what they do
    (AMMO_RELOAD -> autoforge, DISPLACER -> skimmer, TELEPORTER -> teleporter, AI type ENTROPY_AMPLIFIER -> entropy
    amplifier; this is what picks up UAF's). The log line says what each was set up as.
  - What each does in 3D:
    - Missile Autoforge / Heavy Missile Autoforge: usable once a missile rack is below `minFractionToReload` (0.6 / 0.5);
      refills every missile rack by its base ammo (Autoforge) or to full (Heavy). AI: uses it as soon as it can.
    - Degraded / Threat Phase Skimmer: the existing skimmer.
    - Entropy Amplifier (and UAF's): needs an enemy within 1500 x system range bonus x rangeMult; for its duration the
      target takes x1.5 (DAM_MULT) hull, armor and shield damage (red flashes on it). Yours: your locked target, else the
      one nearest the reticle. AI: its target, once in weapon range.
    - Phase Teleporter: at the end of its 0.5 s charge you jump to the aim point (up to range x rangeMult); an AI jumps
      to its fighting distance off the side of its target, or straight away from incoming fire when badly hurt. Never
      ends up inside a hull.
    - Mine Strike (Doom; station version range 3500): a heavy mine (`minelayer2`: 2000 HE, blast 250 / full within 200)
      appears at the aim point (AI: where its target will be), just outside any hull it would be inside. Arms in 0.5 s;
      an enemy hull within its fuse range (175) starts a 1 s windup (ping sound, fast red pulse); it blows then, or at
      the end of its 5 s life. PD can shoot it (500 hp). AI: one live mine at a time (it laid all 5 charges into one
      target in 4 s otherwise: 10000 damage).
    - Canister Flak (Sarissa, Manticore): each hidden system slot throws its burst (5) one canister at a time, 0.25-1 s
      apart, at random speed (x0.25-1) and fuse (x0.25-1), as vanilla's plugin does; the canister's proximity fuse (40)
      bursts on enemy missiles and fighters. 3D change: canisters spread through a cone round the slot direction (at
      least 45 deg half-angle) instead of a flat line, so the cloud also screens from above and below. AI: throws it when
      a missile is ~1.5 s out or fighters are within 500. **Caveat:** the slots throw sideways-forward (vanilla's
      angles), so missiles coming straight at the nose mostly slip between canisters (bench: 0 of 8 nose-on, 4 of 8 along
      the slots' diagonals). Worth watching in game.
    - Targeting Feed (Heron): the carrier's fighters deal x1.5 damage (shots, beams, missiles) while it runs. AI: when
      its wings are engaged. Needed a sim change: every ship's system modifiers are now reset before any system runs
      (before, a system acting on another ship could be wiped if that ship was stepped later).
    - Lidar Array (Invictus): 3.25 s wind-up in which nothing but missiles fires, then only hardpoint ballistic/energy
      guns (not beams) fire, with +100% range, x3 rate of fire and +50% shot speed; missiles always may. AI: lined up on a
      target within the boosted range, flux under 60%. (Recoil reduction and the passive +35% range after first use are
      not modelled.)
    - Termination Sequence (Tempest, Apex): the drone nearest the target becomes a `terminator_missile` (1200 energy +
      600 EMP, homing); the drone rides the missile (drawn as itself) and dies when it hits or is shot down; vanilla then
      rebuilds it like any lost fighter. Needs a target in missile range and a drone.
  - Bench (`S3D_ONLY=systemsBatch2`): autoforge 2/10 -> 8 (base ammo 6), not usable when full; entropy x1.50 hull and
    shield while on, x1.00 after, not usable at 8000; teleporter: you land on the aim point, the AI makes a 3000 jump at
    a target 9000 away; mine strike: the AI lays mines one at a time, 4000 hull lost in 4 s; canister flak as above;
    targeting feed: fighter and its shot x1.50, carrier x1.00; lidar: wind-up 0 gun shots (missiles 6), active 14
    hardpoint shots in 4.5 s vs 3 in 3 s normally, turret 0, reaches 1.8x normal range only while active; termination
    sequence: drone rides, dies, target takes exactly 1200.
    Later fix (weapon batch 2 session): the canister flak check was flaky (unseeded `ShipSystem3D.RND`: 2-5 kills of 8,
    failing ~1 run in 4). Now seeded (`ShipSystem3D.seed`, `S3D_FLAK_SEED`, default 31 -> 4 kills); over seeds 1-10 it
    kills 2-5 and seed 6 fails the >= 3 bar, so the bar sits at the low tail of a real spread.
  - Unverified until played: everything above in the real game; UAF's amplifier (mapped by AI type, uses vanilla's
    x1.5, its own script may differ); `MineStrikeStats` getRange on a station; how system slots read on real hulls.

- **2026-10-12 — Weapon scripts, batch 1: vanilla on-hit / on-fire / beam effects rebuilt in 3D (user: "move to the next
  step"); headless-tested, awaiting in-game test.**
  - Before: only the ion/tachyon family had a 3D version. Breach, Disintegrator, Graviton, Paladin, Pilum, Sabot, Squall,
    Shock Repeater and every DEM missile fired as plain damage; on-fire scripts weren't even read, so Shock Repeater,
    Squall and the DEM launchers never showed up as "not modelled".
  - Same approach as ship systems: rebuilt natively, vanilla scripts never run. Numbers are read at runtime from the
    scripts' public constants (`BreachOnHitEffect.DAMAGE`, `GravitonBeamEffect.DAMAGE_PERCENT_*`, ...), so a mod that
    rebalances them carries over; the two that are inline in vanilla (Paladin's blast, Sabot's arc chance) are in the
    tuning file under `weaponFx`.
  - What each does in 3D:
    - Breach SRM (+250), Mining Blaster (+100): armor stripped round a hull hit on top of the damage; armor doesn't
      reduce it; never hull. Floating armor number.
    - Disintegrator: a burn stays on the hull where it hit, 11 ticks about a second apart, 1000 armor in all; armor
      only (a burn on bare hull does nothing); blue sparks and vanilla's loop sound while it burns; ends if the ship dies.
    - Graviton Beam: a shield it plays on (at full power) takes +5/8/10% damage from everything with 1/2/3+ such beams
      on it, lasting 1 s after the beam leaves.
    - Paladin PD: once per burst, at full power, a 100 frag blast (radius 75, full damage within 50) where the beam
      lands, sparing what the beam itself is burning; kills missile clusters and fighters round the target.
    - Pilum (second stage): an EMP-only arc to a component anywhere on the ship on every hit; through a raised shield
      with the ion chance (hard flux - 10%). Sabot warheads: 25% chance of the same on a hull hit (not through shields).
    - Squall: +150 damage (x the ship's missile damage bonus) when it hits a shield.
    - Shock Repeater: no shell any more (vanilla's shell has no collision). Each shot is an instant arc to the nearest
      enemy ship or missile within range and a 30 deg cone of the barrel, with the shot's damage and EMP; a raised
      shield in the way takes it. Nothing there: the arc crackles out ahead.
    - DEM missiles (Gorgon, Gazer, Dragonfire, Hydra's warheads), driven by the missile's own behaviour JSON: cruise in
      like any guided missile; within the trigger distance of the hull (after the minimum delay, or sooner once it has
      swung `triggerAngle` round the target, or if it's already inside its stand-off) it holds a stand-off band (backs
      off / closes in / bleeds speed to a drift, Gazer sidesteps), turns its nose on (turn rate boosted), paints the
      target with its targeting laser (colour from the laser's spec) for the targeting time, then fires the payload
      beam (read from `getWeaponSpec(payloadWeaponId)`: dps x the ship's missile damage bonus, burst, charge, range,
      type, colours) along its nose. Bomb-pumped (Dragonfire): blows apart and the beam fires from where it was.
      Then it explodes or flames out per its JSON. Shot down mid-burst = the beam stops. All distances x rangeMult.
      AI: a DEM lining up on / painting a ship counts as incoming fire from its direction (omni shields turn to it),
      and payload beams count like any enemy beam.
    - MIRV warheads that are missiles of their own now stay missiles (read from the warhead's .proj through the game's
      loader): Pilum's second stage homes (before: a straight shell at the first stage's speed, which a sidestep
      dodged), Hydra spills six DEM warheads backwards (`arcOffset`) that swing round and attack, Hurricane's warheads
      are unguided missiles. Warhead on-hit scripts are read too (Sabot's arc). `spreadSpeedRange` is used.
    - Visual-only scripts are recognised and named in the log as "visual only" (Cryoblaster, Cryoflamer, Heavy
      Adjudicator muzzle flash, lights/blinkers, sensor dish, VPDriver (unused in vanilla)). Anything else, now including
      on-fire and every-frame scripts, is logged as "not modelled".
  - **Also fixed (existing bug, affects every missile): missile flight time now scales with `weapons.rangeMult`.** Ranges
    are x2 in 3D but flight time wasn't, so a missile launched from near its (3D) range flamed out about halfway: the
    AI launches from the full range. Missiles now fly flightTime x rangeMult (one line in `Combat.stepMissiles`), so
    they reach as far as their range says; they also stay in the air longer for PD to work on.
  - Bench (`./build.sh combat`, real hull shapes; `S3D_ONLY=weaponScripts` runs just these): breach strips +150 armor
    reading at the hit (45 plain vs 195), hull untouched; disintegrator 300 -> 2 armor over ~12 s, hull unchanged;
    graviton x1.050 / x1.100 hard flux from a gun under 1 / 3 beams; Paladin leaves 0 of 4 packed missiles (3
    without the blast); Pilum arc knocks out a tail turret from a nose hit (plain hit can't); Squall 500 hard flux
    from 100 kinetic; shock repeater hits the ship ahead, never the one beside, no shells; Gorgon-like DEM holds 900+
    from the hull, paints, its burst does 840 (expected 840) and it's gone; Pilum second stage hits a target that
    sidesteps after the split (straight shell: 0 hits); Hydra-like: 6 warheads, 6 payloads, ~3140 damage.
  - Full bench: everything passes except "AI 15v15 + 40 fighters sim cost", which **already fails on the merged code
    before this step** (3 runs each on this Mac: baseline avg 1.30-1.36 ms, 8-17 frames over 8 ms, worst 15-24 ms;
    with this step avg 1.23-1.26 ms, 13-16 frames over 8 ms, worst 21-30 ms). Averages are fine; the single-frame
    spikes need their own look (GC? a volley of PD work?). In game the S3D perf line showed 58 fps with 28 ships.
  - Unverified until played: the warhead .proj load path for other mods' MIRVs (falls back to a shell, logged);
    whether the payload/laser look right at 3D scale; DEM hover band feel; Shock Repeater as PD in a real missile
    storm; the longer missile flight (balance: watch PD vs missile-heavy fleets).
  - Not in this batch: Omega/Threat/Dweller weapons (Rift Lance, Rift Cascade, Rift Beam, Rift Torpedo, Voltaic,
    Neutron torpedo, Dweller fragments/swarms...), other mods' scripts (UAF's Racun, Kanshee, Selemene pods were seen
    in the log), flare-launcher weapons (CUSTOM behaviour), canister flak / mines (ship systems).

- **2026-10-12 — Orders and objectives in 3D (user: "proceed to the next step"); awaiting in-game test.**
  - Before: 3D pilots ignored every command-map order except retreat, objectives weren't shown, and their
    bonuses never reached the 3D ships.
  - Orders (core `Order3D` on `Ship3D.order`, followed by ShipAI3D; null = fight freely). Twice a second the
    host reads each AI ship's vanilla assignment (`CombatTaskManagerAPI.getAssignmentFor`, ally or not) and
    maps it by type name:
    - LIGHT/MEDIUM/HEAVY_ESCORT, DEFEND, RALLY_* on a friendly ship -> ESCORT: a slot off its side
      (port or starboard by seed, a little aft), matching its speed; fights only enemies within
      max(1500, 1.2x weapon range) + its radius of the escorted ship.
    - DEFEND / RALLY_* on a point, and anything else aimed at a point -> HOLD: go there (each ship to its own
      spot on a ring), wait facing the enemy fleet; fights only within max(2000, 1.5x weapon range) + radius
      of the point (`battle.holdRadius` 1000 map units).
    - CAPTURE / CONTROL / ASSAULT on an objective -> CAPTURE: as HOLD, sitting within 0.35 of the capture
      ring.
    - ENGAGE / INTERCEPT / HARASS / STRIKE on an enemy ship -> ATTACK: that ship is the target.
    - AVOID -> keep out of `battle.avoidRadius` 2000 map units round the point (or ship), never chase into it.
    - Aimed at a station or module -> HOLD round the whole structure. SEARCH_AND_DESTROY, RECON,
      REPAIR_AND_REFIT, no assignment -> fight freely. A fight that drags a ship 1.4x its leash from its
      escort/hold/capture ground is dropped. Ordered ships skip the fleet-cohesion pull.
    - Changes are logged ("S3D: X ordered: ESCORT Y", "has no orders: fights freely").
  - Objectives (core `Objective3D`, `Sim.objectives`): mirrored from `engine.getObjectives()` at their map
    position x mapScale; owner followed from vanilla twice a second (toast and log on change). HUD: a square
    on each (green yours, red enemy, grey nobody), name and distance; a smaller inner square while the other
    side is alone on it, an amber cross while contested; small squares on the minimap.
    - Taking one: ships sit on it in 3D, so their puppets sit on it in 2D, for vanilla's own capture.
      `battle.captureRadius` 600 map units is our guess at vanilla's ring. Fallback: if one side has been
      alone on it for `battle.captureFallback` 30 s and vanilla hasn't flipped it, `setOwner` (logged WARN).
    - Bonuses: weapon ranges are re-read from the puppets twice a second (`WeaponAPI.getRange()`, so sensor
      arrays' ECM penalty and other mid-battle range changes reach 3D; the AI re-profiles). Top speed may now
      rise past the deployment figure by modifiers whose source mentions "nav" (nav buoys); every top-speed
      modifier source is logged once ("top speed modifier source seen"), so the real key shows if it's
      different. Comm relays (command points) are vanilla's own.
  - Bench (stand-in data, see the next entry): new "AI orders" check: escort mean 566 from its ship, ignores an enemy far from it, engages one
    that comes close; HOLD reaches a point 4200 away and stays, ignoring an enemy far from it; CAPTURE sits
    inside the ring; ATTACK picks the ordered (farther) ship 100% of the time; AVOID stays 3000+ from an area
    of radius 1500 and leaves the enemy inside alone.
  - Unverified until played: every API call above that's new (`getObjectives`, `BattleObjectiveAPI`
    getType/getDisplayName/getLocation/getOwner/setOwner, `AssignmentInfo.getTarget`, `AssignmentTargetAPI`,
    `DeployedFleetMemberAPI.getShip`) -- this was written without the game (see the next entry); whether
    vanilla captures objectives for puppets with no collision; vanilla's capture ring; the nav key.
- **2026-10-12 — Stations at twice the scale (user: "the stations need to be 2 times the scale of the
  original"); awaiting in-game test.**
  - `scale.station` 2.5 -> 5.0 (modules follow their station). Read as "twice what they are now"; it's one
    number in the tuning file if 2x vanilla (2.0) was meant.
  - At 5x a station's sections sit thousands of units out and vanilla's collision circle for the core can be
    far bigger than the hull, so the AI (1.6x bounding-sphere avoidance, gun range to the target's centre)
    could hover out of reach. Now, for station sections and modules only (ship v ship unchanged):
    - Hulls know how far their solid shape reaches in each direction (`HullVolume.reachToward` / `extent`,
      32 bins; `Ship3D.reachToward` / `surfaceDistance` / `extent`).
    - Shots count as in range when the near side is (`Sim.nearSide`; autofire, AI, HUD in-range). The AI
      steers clear of the real shape + its own size + 80, fights at its range from the near side, doesn't
      flank from inside the structure, and makes no gun runs at an anchored station (the run "past the
      target" was into the station).
    - Fleet cohesion uses the centre of ships that fly (`Sim.mobileCentre`): a station's parts put the old
      centre inside the station and pulled attackers into it. Working on a station within 3x weapon range
      counts as busy.
    - `battle.stationSpinDeg` 2 -> 1: the outer sections' speed grows with scale; at 2 deg/s they outran
      destroyers trying to hold on them.
    - Host: a station that would poke out of 80% of the battle area is moved in (logged; the command map
      still shows it where vanilla has it); a ship that arrives inside a station is moved out beside it.
  - Built in a cloud session without the game: everything type-checks against stand-in API stubs (not in
    the repo) with -Xlint:all clean, and the benches ran on stand-in hull data (rough shapes and stats,
    including a made-up 6-section station), so their numbers are indicative only. Not yet run:
    `./build.sh` / `./build.sh combat` on the real install.
  - Stand-in results, station assault from 6 directions (old code at 2.5 / new code at 5.0): all stripped in
    ~150 / ~173 s; attackers lost 12 / 2 of 24; damage to attackers ~9700 / ~2300; no hull contact either way.
    A 700 px stand-in station at 5x stalled with the old AI (a section left after 300 s) and is stripped in
    ~200 s now. The attackers stand off instead of running into the station, so stations are much less
    lethal in the bench; watch for that in game (real station weapons out-range the bench's generic guns).
    The bench's own single "station assault" layout fails its "> 500 damage to attackers" check on the
    stand-in data (266); all six batch layouts pass it (757-4812).

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
src-art/         Blender scripts that build hand-made hull models (s3d_kit.py + hulls/<hullId>.py)
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
Hand-made OBJ override per hull (option B): `data/meshes/hulls/<hullId>.obj`, built from Blender scripts in
`src-art/` (see its README); hulls without one keep the relief.

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
3. Controls (no in-game help since 2026-10-13; keys live in `keys` in the tuning file): mouse steers, left click
   fires the selected group, right click shield / phase, 1-7 select group, Ctrl/Cmd+1-7 toggle autofire, W/S
   throttle, X all stop, A/D strafe, Space/Shift up/down, Q/E roll, L auto-level, F system, V vent, R lock target,
   Z wings engage/regroup, hold L-Alt or middle mouse to look around, wheel zoom, T tactical view, J spectate
   ([ / ] other ships), P pause, Tab command map, F9 twice = finish in vanilla 2D, G / F10 / \ hide HUD,
   I perf readout, O hide 3D view (debug), K reload tuning. Sandbox: [ / ] hull, N repair, B incoming fire.
   Tune feel in `data/config/s3d_tuning.json`, press `K` in game to reload (the perf readout shows the file hash,
   so you can tell whether the reload picked up your edit). Retro filter: `retro.filter` "ships" / "scene" / "off".
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
- No weapons drawn on hulls yet (turret/hardpoint sprites are separate in Starsector) → M3.
- No sound yet. No collisions between 3D ships yet.
- No mesh LOD; fine at sandbox scale, revisit for big battles.
- GL resources live for the session; a display-mode change that recreates the GL context would need
  `ShipModel.clearCache()` + renderer re-init (not handled yet).
- Ships from other mods are untested (should work: same API path).

## Known gaps after M3

(Historical: most of this is done since; see the status entries and Next steps.)

- Targets don't manoeuvre or fight back (no AI yet), apart from the B missile test.
- No shields, flux or overload yet; EMP (ion cannon/ion beam) does nothing extra; no fighters.
- Weapon on-hit/every-frame scripts (special effects) aren't run; missile special behaviours (MIRV
  splitting, flares, proximity fuses) fall back to plain homing/straight missiles.
- Aiming is pure Freelancer (weapons converge on the reticle). If holding the reticle on the lead
  diamond while it also steers feels awkward, options: aim assist near the diamond, or a separate
  aim mode.

## Known gaps after M4 (unverified until the first campaign test)

(Historical: fighters, ship systems, phase, collisions, stations, modules and orders are in now.)

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

(Updated 2026-10-13: ship systems batches 3 and 4 done. Earlier (2026-10-12) done since the list before: stations at 5x with AI that copes, orders and objectives (both built and
run on the real install: a station battle played at 58 fps with 28 ships, enemy DEFEND / HEAVY_ESCORT orders followed),
weapon scripts batch 1, ship systems batch 2, weapon scripts batch 2.)

1. In-game test of weapon scripts batch 1: a fight with Breach / Pilum / Sabot / Squall / Gorgon / Hydra / Dragonfire
   boats and a Graviton / Paladin / Disintegrator / Shock Repeater ship (simulator is fine). Watch the weapon lines in
   the log at battle start ("strips 250 armor", "DEM: ...", "visual only", "not modelled") and how DEM missiles look
   holding off and painting.
2. In-game test of ship systems batch 2: fly (or fight) an Afflictor (Entropy Amplifier), Hyperion (Phase Teleporter),
   Doom (Mine Strike), Manticore / Sarissa (Canister Flak), Heron (Targeting Feed), Invictus (Lidar Array), Tempest
   (Termination Sequence), and a station with Heavy Missile Autoforge. The system's log line at battle start says what
   it was set up as.
3. Still to test from earlier: your own orders on the Tab map (escort / capture / defend / engage / avoid) in a battle
   with objectives; only enemy orders have shown up in the log so far.
4. In-game test of weapon scripts batch 2: the simulator with Omega / Threat / Dweller ships if unlocked, or Console
   Commands (installed) to put Rift Cascade / Rift Beam / Reality Disruptor / Voltaic / fragment weapons on your ship
   and spawn a Threat or Dweller fleet. Watch the weapon and "fragment swarm" lines in the log at battle start.
5. In-game test of ship systems batches 3 and 4: Retribution (Orion), Nova, Onslaught Mk.I, Harbinger, Gemini, a
   station with drones; a Threat fleet (Overseer lashing, Fabricator building, Hive's Swarm Launcher) and a Shrouded
   one (Tendril / Maw lunges, Eye's gaze, Maelstrom's ejecta, Churning Locus vortices); a Ziggurat. Console Commands
   can spawn them. Watch the log's "spawned", "construction swarm to build", "swarm look" and "shroud look" lines.
6. Gaps by impact: mesh LOD for big battles; other mods' ships and scripts (UAF is installed). Decided: no JIT
   warm-up at load (user: not an issue).

Cut from the roadmap (2026-10-12, user): M5, the hull-by-hull model pass. Ships keep the sprite relief meshes. The OBJ
loader and the Blender kit stay in the mod, unused (the Wolf is still only staged in `build/art/hulls/`).
