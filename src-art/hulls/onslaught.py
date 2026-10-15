"""Onslaught (Hegemony battleship): three thick armoured prongs over a deep aft body, stepped armour on top,
a bulbous core between the aft turrets, raked armour claws beside the engine block, pipework round the
engines, raised barbettes under every turret and engine bells at the six engine slots.
Look after a user-supplied fan render (three prongs of heavy plating, busy pipework aft); positions from
the sprite (288x384, centre col 144 row 244) and onslaught.ship's slots.

Volumes come from sprite_body (each prong / the aft body fills the art's width row by row, boxy sections
with chamfered flanks); the stepped plating is region_plate slabs sitting on top of them."""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import s3d_kit as kit  # noqa: E402

game, mod = kit.args()
h = kit.Hull.from_game(game, "onslaught")
C = 144.0


def both(ob):
    """The part and its mirror image (the hull is symmetric about col 144)."""
    if ob is not None:
        h.mirrored(ob)


def profile(*pts):
    """Height as a function of row, linear between (row, value) points."""
    rows, vals = zip(*pts)
    return lambda r: float(np.interp(r, rows, vals))


def rows(r0, r1, step=6):
    return list(np.arange(r0, r1, step)) + [r1]


# ---- base: a slab filling the outline exactly, so the top view is always the whole sprite ----
h.plate(top=6.0, bottom=-6.0, bevel=1.5)

# ---- hull volumes ----
# centre prong: a ram bow rising to a tall armoured spine
h.sprite_body(101, 187, rows(7, 160), up=profile((7, 5), (30, 16), (70, 30), (120, 36), (160, 36)),
              down=profile((7, 4), (30, 12), (80, 22), (160, 26)), power=3.5, name="body_c")
# side prongs (prong + outer wing), deepest under the large turrets
both(h.sprite_body(0, 100, rows(14, 246), up=profile((14, 5), (40, 18), (100, 26), (170, 28), (246, 18)),
                   down=profile((14, 4), (40, 12), (120, 20), (200, 20), (246, 12)), power=3.5, name="body_s"))
# aft body: wide and deep, tapering to the engine block
h.sprite_body(40, 248, rows(150, 352), up=profile((150, 26), (230, 26), (300, 22), (352, 14)),
              down=profile((150, 26), (260, 28), (320, 22), (352, 12)), power=4.0, shrink=0.9, name="body_aft")

# ---- stepped armour on top of the volumes ----
centre = [(C, 6), (196, 150), (C, 168), (92, 150)]
h.region_plate(centre, inset=9, top=42, bottom=10, bevel=1.6, name="prong_c2")
h.region_plate([(C, 30), (170, 140), (C, 150), (118, 140)], inset=14, top=48, bottom=10, bevel=1.4, name="prong_c3")
h.loft([(C, 20, 22, 2.0, 5.0, 2.0, 4.0), (C, 44, 38, 4.0, 8.0, 3.0, 4.0), (C, 92, 50, 5.0, 6.0, 3.0, 4.0),
        (C, 104, 49, 3.0, 2.0, 2.0, 4.0)], power=4.0, name="ridge")
side = [(58, 12), (102, 14), (108, 150), (104, 246), (44, 246), (38, 92)]
both(h.region_plate(side, inset=8, top=33, bottom=8, bevel=1.6, name="prong_s2"))
both(h.region_plate([(66, 26), (94, 30), (98, 140), (60, 140), (56, 60)], inset=10, top=40, bottom=8, bevel=1.4,
                    name="prong_s3"))
wing = [(8, 88), (58, 84), (60, 246), (8, 246)]
both(h.region_plate(wing, inset=6, top=30, bottom=8, bevel=1.4, name="wing2"))
# gun housings for the built-in large hardpoints at the prong tips (col 74 / 214, row 46)
both(h.box(66, 84, 26, 60, 10, 30, bevel=1.6, name="tip_housing"))
h.region_plate([(96, 160), (192, 160), (192, 300), (96, 300)], inset=10, top=32, bottom=10, bevel=1.4, name="aft2")

# ---- bulbous core between the aft turrets (the pale dome on the sprite, cols 123-166, rows 205-285) ----
h.ellipsoid(C, 226, 28, 17, 21, 16, 6, name="core_front")
h.ellipsoid(C, 262, 26, 14, 17, 13, 6, name="core_back")
h.ellipsoid(C, 196, 31, 9, 9, 9, 4, name="core_neck")
# raked armour claws either side of the engine block
both(h.loft([(110, 252, 14, 7.0, 16.0, 12.0, 4.0), (98, 300, 12, 7.5, 16.0, 14.0, 4.0), (86, 344, 8, 5.0, 10.0, 10.0, 4.0),
             (84, 352, 6, 2.5, 4.0, 4.0, 4.0)], power=4.0, name="claw"))

# ---- pipework round the engine block (the dark machinery on the sprite, rows 280-345) ----
for c0, c1, z in ((116, 118, 26.0), (124, 126, 29.0), (164, 162, 29.0), (172, 170, 26.0)):
    h.pipe([(c0, 276, z), (c0, 300, z + 1.0), (c1, 330, z - 3.0), (c1, 342, z - 8.0)], 2.4, name="pipe")
for c in (108, 180):
    h.pipe([(c, 300, 20.0), (c, 336, 14.0)], 3.4, name="pipe_big")
for row in (292.0, 312.0):
    h.pipe([(112, row, 25.0), (C, row - 4, 30.0), (176, row, 25.0)], 2.0, name="pipe_cross")

# ---- engine bells at the engine slots ----
for col, row, r, ln in ((133, 348, 13.0, 14.0), (155, 348, 13.0, 14.0), (104, 336, 9.0, 11.0), (184, 336, 9.0, 11.0),
                        (74, 314, 9.0, 11.0), (214, 314, 9.0, 11.0)):
    h.nozzle(col, row, 2.0, r, ln, flare=1.08, name="nozzle")

# ---- overall height: everything so far x1.2 (the rings below keep their own thickness) ----
for ob in h.parts:
    for v in ob.data.vertices:
        v.co.z *= 1.2
    ob.data.update()

# ---- barbettes: a raised ring under every turret, seated on whatever is below ----
SIZE = {"SMALL": 5.0, "MEDIUM": 8.0, "LARGE": 12.0}
for col, row, size in ((73, 86, "MEDIUM"), (70, 110, "SMALL"), (215, 86, "MEDIUM"), (218, 110, "SMALL"),
                       (66, 130, "SMALL"), (222, 130, "SMALL"), (144, 57, "MEDIUM"), (156, 94, "SMALL"),
                       (132, 94, "SMALL"), (256, 247, "MEDIUM"), (32, 247, "MEDIUM"), (126, 195, "MEDIUM"),
                       (162, 195, "MEDIUM"), (122, 253, "MEDIUM"), (166, 253, "MEDIUM"), (56, 167, "LARGE"),
                       (144, 118, "LARGE"), (231, 167, "LARGE"), (79, 253, "MEDIUM"), (104, 236, "MEDIUM"),
                       (184, 236, "MEDIUM"), (209, 253, "MEDIUM")):
    r = SIZE[size]
    z = h.top_at(col, row, default=6.0)
    h.cylinder(col, row, r + 2.5, z - 1.5, z + 1.8, segs=28, bevel=0.6, name="barbette")
    h.cylinder(col, row, r, z + 1.8, z + 3.6, segs=28, bevel=0.5, name="ring")

h.export(mod, uv="fold")
