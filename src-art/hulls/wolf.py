"""Wolf (Tri-Tachyon frigate): smooth spine over a ventral keel, shoulder nacelles, missile pods on thin
pylons, two rear turret blisters, three engine bells. Positions read off the sprite (src-art/README.md shows how)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import s3d_kit as kit  # noqa: E402

game, mod = kit.args()
h = kit.Hull.from_game(game, "wolf")  # 52x96, centre col 26 row 54
C = 26.0  # centre line

# Thin slab filling the outline exactly; the parts below rise out of it.
h.plate(top=1.6, bottom=-1.2, bevel=0.6)

# Spine, nose (gun housing, boxy) to stern (engine block). (col, row, z, half width, up, down[, power])
h.loft([
    (C, 0.6, 0, 6.0, 3.0, 2.0, 4.0),
    (C, 2.0, 0, 8.0, 5.0, 3.5, 4.0),
    (C, 10.0, 0, 9.0, 6.0, 4.0, 3.5),
    (C, 13.0, 0, 10.0, 8.5, 5.5),
    (C, 24.0, 0, 11.0, 11.5, 7.0),
    (C, 36.0, 0, 11.5, 13.0, 8.0),
    (C, 50.0, 0, 12.0, 13.0, 8.0),
    (C, 60.0, 0, 10.5, 11.0, 7.5),
    (C, 72.0, 0, 12.0, 10.0, 7.5, 3.0),
    (C, 80.0, 0, 12.5, 9.0, 7.0, 3.5),
    (C, 88.0, 0, 12.5, 8.0, 6.5, 3.5),
    (C, 92.0, 0, 10.0, 6.5, 5.5, 3.5),
    (C, 94.6, 0, 3.5, 3.0, 3.0, 3.0),
], power=2.4, name="spine")

# Ventral keel: a narrower hull hanging under the spine.
h.loft([
    (C, 16.0, -5.0, 2.0, 1.0, 1.0),
    (C, 24.0, -5.5, 5.0, 2.0, 4.5),
    (C, 44.0, -6.0, 6.5, 2.0, 6.0),
    (C, 66.0, -6.0, 6.0, 2.0, 5.5),
    (C, 82.0, -5.0, 5.0, 2.0, 3.5),
    (C, 88.0, -4.5, 2.0, 1.0, 1.0),
], power=2.2, name="keel")

# Bridge dome (the bright canopy at rows 24-33).
h.ellipsoid(C, 28.5, 10.0, 4.2, 5.5, 4.0, 1.0, name="bridge")

# Shoulder nacelles either side of the bridge.
nac = h.loft([
    (13.5, 11.0, 0.5, 1.5, 1.5, 1.5),
    (13.5, 13.5, 0.5, 3.4, 5.5, 4.5),
    (13.5, 20.0, 0.5, 3.6, 6.0, 4.5),
    (13.8, 23.0, 0.5, 2.6, 4.0, 3.0),
    (14.0, 25.5, 0.5, 1.0, 1.2, 1.2),
], power=2.2, name="nacelle")
h.mirrored(nac)

# Missile pods on the wing tips (missile hardpoints at col 6 / 46, row 43), sensor spike in front.
pod = h.loft([
    (8.5, 34.0, 1.0, 0.8, 1.0, 0.8),
    (8.0, 38.0, 1.0, 2.2, 2.4, 2.0),
    (7.0, 40.5, 0.5, 5.2, 5.0, 4.0),
    (7.0, 45.0, 0.5, 6.0, 6.5, 5.0),
    (7.5, 50.0, 0.5, 5.5, 6.0, 5.0),
    (8.5, 54.0, 0.5, 4.5, 4.5, 4.0),
    (10.5, 57.0, 0.5, 2.5, 2.5, 2.5),
    (11.0, 58.6, 0.5, 0.8, 0.8, 0.8),
], power=2.3, name="pod")
h.mirrored(pod)

# Rear turret blisters (turrets at col 14 / 38, row 67) with their rings.
bl = h.ellipsoid(14.0, 68.0, 0.5, 6.0, 6.5, 7.5, 5.0, name="blister")
h.mirrored(bl)
ring = h.cylinder(14.0, 67.0, 3.6, 6.8, 8.6, bevel=0.4, name="ring")
h.mirrored(ring)

# Centre turret ring on the spine (col 26, row 43).
h.cylinder(C, 43.0, 5.0, 11.8, 13.9, bevel=0.5, name="ring_c")

# Engine bells at the engine slots (rows 90 / 94), sunk into the stern so only the rims stand out.
for col, row in ((14.0, 86.5), (38.0, 86.5)):
    h.nozzle(col, row, 0.0, 3.2, 4.8, flare=1.05, name="nozzle")
h.nozzle(C, 90.0, 0.0, 3.6, 5.2, flare=1.05, name="nozzle_c")

h.export(mod)
