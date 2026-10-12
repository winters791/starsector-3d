# Hull models (src-art)

Hand-made 3D hulls that replace the sprite relief for chosen ships. The game looks for
`data/meshes/hulls/<hullId>.obj`; a hull without one (or a skin/(D) hull whose base hull has none) keeps the relief.
Other mods can ship files at the same path for their own hulls.

## How a model is made

Each hull is a short Blender Python script in `hulls/<hullId>.py` that lays parts over the sprite with
`s3d_kit.py`. Scripts are the source; the `.obj` is generated.

```
src-art/build.sh --grid wolf      # build/art/wolf_grid.png: sprite x9, grid every 4 px (yellow every 8), centre in red
src-art/build.sh wolf             # -> data/meshes/hulls/wolf.obj (+ build/art/wolf.blend to look at in Blender)
S3D_MODEL_DIR=build/art/hulls src-art/build.sh wolf   # stage it where the game won't load it
S3D_MODEL_DIR=build/art/hulls S3D_PREVIEW_ONLY=hullModels ./build.sh preview wolf
                                  # build/preview/model_wolf.png (relief vs model) and model_wolf_close.png
```

(`./build.sh preview` rebuilds the jar; don't run it while the game is open.) In game, `K` reloads edited
model files along with the tuning file.

## Coordinates

Parts are placed in sprite pixels: `col` from the sprite's left edge, `row` down from its top edge,
`z` up from the ship's mid-plane. Engines and weapons sit near z = 0 (turrets are seated on whatever
surface is highest under them). Read positions off the grid image and the hull's `.ship` file
(weapon/engine slot `locations` are `[forward, left]` from the centre: col = cx - left, row = cyTop - forward).

## Parts (s3d_kit.Hull)

| part | what |
|---|---|
| `plate(top, bottom, bevel)` | the sprite outline as a slab: the base every model starts with, so the top view always matches the sprite |
| `loft(stations, power)` | smooth body through cross-sections `(col, row, z, half_width, up, down[, power])`; power 2 = ellipse, 3-4 = boxy |
| `ellipsoid(col, row, z, rx, ry, up, down)` | domes, blisters, canopies |
| `cylinder(col, row, r, z0, z1, bevel)` | turret rings, pedestals |
| `nozzle(col, row, z, r, length)` | engine bell pointing back (+row), open with a recessed throat |
| `box(col0, col1, row0, row1, z0, z1, bevel)` | blocks |
| `mirrored(part)` | copy mirrored across the centre column |

## Rules the game relies on

- The texture is the hull's own sprite projected from above (no UVs needed; skins repaint the model).
  Keep parts inside the sprite outline, or they pick up smeared edge colours.
- Steep and downward faces are shaded like the relief's side plating and underside automatically.
  To force one, name the material `top...`, `wall...`/`side...` or `under...` (needs UVs/materials export).
- Shots hit the model: the hit volume is everything between its lowest and highest surface over each
  point, so overhangs count as solid.
- The header line `# s3d sprite <w> <h>` (written by the kit) records the sprite size; a sprite of a
  different size falls back to the relief.
- Units: 1 = one sprite pixel = one game unit, origin at the rotation centre, +Y up, -Z forward (Blender: nose
  toward +Y, top +Z, default OBJ export axes).
- No game art goes into the repo: scripts read the sprite at build time and only geometry is written.
