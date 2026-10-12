"""Starsector 3D hull modelling kit for Blender (runs headless from src-art/build.sh, or in the UI).

A hull script describes a ship as a handful of parts laid over its sprite; the kit builds them, joins
them and exports data/meshes/hulls/<hullId>.obj for the mod (see s3d.render.ObjHull).

Coordinates are sprite pixels, so parts can be read straight off the sprite:
  col = x from the sprite's left edge, row = y down from its top edge (pixel edges: pixel c spans
  c..c+1), z = height above the ship's mid-plane (engines and weapons sit near z = 0).
The kit turns them into Blender space X = col - cx, Y = cyTop - row, Z = z (nose toward +Y, top +Z,
1 unit = 1 pixel), which Blender's default OBJ export (Forward -Z, Up Y) writes in the mod's frame.

The game paints the model with the hull's own sprite, projected from above; so parts should stay
inside the sprite's outline (the plate() part fills it exactly) and the 3D shape does the rest.
Only geometry is written: no game art ends up in the OBJ or in the saved .blend.
"""

import math
import os

import bmesh
import bpy
import numpy as np


def args():
    """(game data dir, mod dir) from the command line after "--" (see src-art/build.sh)."""
    import sys
    a = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if len(a) < 2:
        raise SystemExit("usage: blender -b --python <hull>.py -- <game data dir> <mod dir>")
    return a[0], a[1]


def ship_file(game_dir, hull_id):
    """The hull's sprite path and rotation centre (from the top-left) from its .ship file."""
    import json
    import re
    with open(os.path.join(game_dir, "data", "hulls", hull_id + ".ship"), encoding="utf-8") as f:
        d = json.loads(re.sub(r"#.*", "", f.read()))
    return os.path.join(game_dir, d["spriteName"]), d["center"][0], d["height"] - d["center"][1]


def clear_scene():
    for o in list(bpy.data.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    for m in list(bpy.data.meshes):
        bpy.data.meshes.remove(m)


class Hull:
    @staticmethod
    def from_game(game_dir, hull_id):
        sprite, cx, cy_top = ship_file(game_dir, hull_id)
        return Hull(hull_id, sprite, cx, cy_top)

    def __init__(self, hull_id, sprite_path, cx, cy_top):
        """cx, cy_top: the sprite's rotation centre in pixels from its left and top edges
        (the .ship file's "center" is measured from the bottom-left: cy_top = height - center[1])."""
        self.id = hull_id
        self.cx = float(cx)
        self.cy_top = float(cy_top)
        img = bpy.data.images.load(sprite_path, check_existing=True)
        self.w, self.h = img.size
        px = np.array(img.pixels[:], dtype=np.float32).reshape(self.h, self.w, 4)
        self.alpha = px[::-1, :, 3].copy()  # Blender stores rows bottom-up; row 0 = top here
        bpy.data.images.remove(img)
        self.parts = []
        clear_scene()

    # ---- coordinates ----

    def P(self, col, row, z=0.0):
        return (col - self.cx, self.cy_top - row, z)

    def mirror_col(self, col, axis_col=None):
        a = self.cx if axis_col is None else axis_col
        return 2.0 * a - col

    def _new(self, name, bm, smooth=True):
        me = bpy.data.meshes.new(name)
        bm.normal_update()
        bm.to_mesh(me)
        bm.free()
        if smooth:
            me.shade_smooth()
        ob = bpy.data.objects.new(name, me)
        bpy.context.scene.collection.objects.link(ob)
        self.parts.append(ob)
        return ob

    # ---- parts ----

    def plate(self, top=2.0, bottom=-1.5, bevel=0.8, threshold=0.45, blur=0.7, tolerance=0.2, name="plate"):
        """The sprite's outline as a solid slab from z=bottom to z=top, top edge bevelled. It fills
        the silhouette exactly so the top view always shows the whole sprite."""
        a = _blur(self.alpha, blur) if blur > 0 else self.alpha
        loops = [_simplify(l, tolerance) for l in _contours(a, threshold)]
        loops = [l for l in loops if len(l) >= 3 and abs(_area(l)) > 2.0]
        bm = bmesh.new()
        edges = []
        for loop in loops:
            vs = [bm.verts.new(self.P(c, r, bottom)) for c, r in loop]
            for i in range(len(vs)):
                edges.append(bm.edges.new((vs[i], vs[(i + 1) % len(vs)])))
        bmesh.ops.triangle_fill(bm, use_beauty=True, use_dissolve=False, edges=edges)
        faces = list(bm.faces)
        for f in faces:
            if f.normal.z > 0:
                f.normal_flip()
        ext = bmesh.ops.extrude_face_region(bm, geom=faces)
        moved = [g for g in ext["geom"] if isinstance(g, bmesh.types.BMVert)]
        bmesh.ops.translate(bm, vec=(0, 0, top - bottom), verts=moved)
        if not any(f.calc_center_median().z < bottom + 1e-4 for f in bm.faces):
            # extrusion took the original faces away: close the underside again
            rim0 = [e for e in bm.edges if e.is_boundary]
            bmesh.ops.triangle_fill(bm, use_beauty=True, use_dissolve=False, edges=rim0)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        if bevel > 0:
            # the crease between the top cap and the side wall
            rim = [e for e in bm.edges if all(v.co.z > top - 1e-4 for v in e.verts)
                   and len(e.link_faces) == 2 and abs(e.link_faces[0].normal.z - e.link_faces[1].normal.z) > 0.5]
            bmesh.ops.bevel(bm, geom=rim, offset=bevel, segments=2, profile=0.5, affect='EDGES', clamp_overlap=True)
        return self._new(name, bm, smooth=False)

    def loft(self, stations, power=2.5, segs=32, name="loft", cap=True):
        """A smooth body through cross-sections. Each station is (col, row, z, half_width, up, down[, power]):
        centre point, half width across, height above and depth below the centre. The cross-section
        is a superellipse (power 2 = ellipse, higher = boxier; per station or the default). Stations
        are joined in order; the section at each one is perpendicular to the path."""
        pts = [np.array(self.P(s[0], s[1], s[2]), dtype=np.float64) for s in stations]
        bm = bmesh.new()
        rings = []
        for i, s in enumerate(stations):
            hw, up, down = s[3], s[4], s[5]
            pw = s[6] if len(s) > 6 else power
            t = pts[min(i + 1, len(pts) - 1)] - pts[max(i - 1, 0)]
            t /= max(1e-9, np.linalg.norm(t))
            side, upv = _frame(t)
            ring = []
            for k in range(segs):
                th = 2 * math.pi * k / segs
                c, sn = math.cos(th), math.sin(th)
                x = hw * math.copysign(abs(c) ** (2.0 / pw), c)
                y = (up if sn >= 0 else down) * math.copysign(abs(sn) ** (2.0 / pw), sn)
                ring.append(bm.verts.new(tuple(pts[i] + side * x + upv * y)))
            rings.append(ring)
        for i in range(len(rings) - 1):
            a, b = rings[i], rings[i + 1]
            for k in range(segs):
                bm.faces.new((a[k], a[(k + 1) % segs], b[(k + 1) % segs], b[k]))
        if cap:
            for ring, p in ((rings[0], pts[0]), (rings[-1], pts[-1])):
                c = bm.verts.new(tuple(p))
                for k in range(segs):
                    bm.faces.new((ring[k], ring[(k + 1) % segs], c))
        bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-4)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        return self._new(name, bm)

    def ellipsoid(self, col, row, z, rx, ry, up, down, segs=24, name="dome"):
        """Ellipsoid with radii rx (across), ry (along), up (above centre), down (below)."""
        bm = bmesh.new()
        bmesh.ops.create_uvsphere(bm, u_segments=segs, v_segments=max(6, segs // 2), radius=1.0)
        cx, cy, cz = self.P(col, row, z)
        for v in bm.verts:
            x, y, zz = v.co
            v.co = (cx + x * rx, cy + y * ry, cz + zz * (up if zz >= 0 else down))
        return self._new(name, bm)

    def cylinder(self, col, row, r, z0, z1, segs=24, bevel=0.0, name="ring"):
        """Vertical cylinder (turret ring, pedestal) from z0 to z1."""
        bm = bmesh.new()
        bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=segs, radius1=r, radius2=r, depth=z1 - z0)
        cx, cy, _ = self.P(col, row)
        bmesh.ops.translate(bm, vec=(cx, cy, (z0 + z1) * 0.5), verts=bm.verts)
        if bevel > 0:
            rim = [e for e in bm.edges if len(e.link_faces) == 2 and abs(e.link_faces[0].normal.z - e.link_faces[1].normal.z) > 0.5]
            bmesh.ops.bevel(bm, geom=rim, offset=bevel, segments=2, profile=0.5, affect='EDGES', clamp_overlap=True)
        return self._new(name, bm, smooth=False)

    def nozzle(self, col, row, z, r_out, length, r_in=None, depth=None, flare=1.1, segs=24, name="nozzle"):
        """Engine bell pointing backwards (+row): starts at row, ends at row + length. Open at the back
        with a recessed throat, so it reads as a nozzle from behind."""
        r_in = r_out * 0.72 if r_in is None else r_in
        depth = length * 0.8 if depth is None else depth
        bm = bmesh.new()
        p0 = np.array(self.P(col, row, z))
        back = np.array((0.0, -1.0, 0.0))
        side, upv = np.array((1.0, 0.0, 0.0)), np.array((0.0, 0.0, 1.0))

        def ring(centre, r):
            return [bm.verts.new(tuple(centre + (side * math.cos(2 * math.pi * k / segs) + upv * math.sin(2 * math.pi * k / segs)) * r))
                    for k in range(segs)]

        front = ring(p0, r_out * 0.9)
        rear = ring(p0 + back * length, r_out * flare)
        lip = ring(p0 + back * length, r_in * flare)
        throat = ring(p0 + back * (length - depth), r_in * 0.8)
        fc = bm.verts.new(tuple(p0))
        tc = bm.verts.new(tuple(p0 + back * (length - depth)))
        for k in range(segs):
            k2 = (k + 1) % segs
            bm.faces.new((front[k], front[k2], rear[k2], rear[k]))
            bm.faces.new((rear[k], rear[k2], lip[k2], lip[k]))
            bm.faces.new((lip[k], lip[k2], throat[k2], throat[k]))
            bm.faces.new((throat[k], throat[k2], tc))
            bm.faces.new((front[k2], front[k], fc))
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        return self._new(name, bm, smooth=True)

    def box(self, col0, col1, row0, row1, z0, z1, bevel=0.5, name="box"):
        bm = bmesh.new()
        bmesh.ops.create_cube(bm, size=1.0)
        a, b = self.P(col0, row0, z0), self.P(col1, row1, z1)
        lo = [min(a[i], b[i]) for i in range(3)]
        hi = [max(a[i], b[i]) for i in range(3)]
        for v in bm.verts:
            v.co = tuple(lo[i] + (v.co[i] + 0.5) * (hi[i] - lo[i]) for i in range(3))
        if bevel > 0:
            bmesh.ops.bevel(bm, geom=list(bm.edges), offset=bevel, segments=2, profile=0.5, affect='EDGES', clamp_overlap=True)
        return self._new(name, bm, smooth=False)

    def mirrored(self, ob, axis_col=None):
        """Copy of a part mirrored across a column (default: the rotation centre)."""
        a = (self.cx if axis_col is None else axis_col) - self.cx
        bm = bmesh.new()
        bm.from_mesh(ob.data)
        for v in bm.verts:
            v.co.x = 2 * a - v.co.x
        bmesh.ops.reverse_faces(bm, faces=bm.faces)
        flat = not any(p.use_smooth for p in ob.data.polygons)
        return self._new(ob.name + "_m", bm, smooth=not flat)

    # ---- output ----

    def export(self, mod_dir, sharp_angle_deg=40.0):
        """Writes <out>/<id>.obj, where <out> is $S3D_MODEL_DIR if set (a staging folder the game
        doesn't read, for work in progress) or the mod's data/meshes/hulls; and build/art/<id>.blend."""
        out = os.path.join(mod_dir, os.environ.get("S3D_MODEL_DIR") or os.path.join("data", "meshes", "hulls"))
        return self.write(os.path.join(out, self.id + ".obj"), os.path.join(mod_dir, "build", "art", self.id + ".blend"), sharp_angle_deg)

    def write(self, obj_path, blend_path=None, sharp_angle_deg=40.0):
        for o in bpy.data.objects:
            o.select_set(o in self.parts)
        bpy.context.view_layer.objects.active = self.parts[0]
        bpy.ops.object.join()
        ob = bpy.context.view_layer.objects.active
        ob.name = self.id
        me = ob.data
        # Smooth inside parts, hard where faces meet at a crease.
        smooth = [p.use_smooth for p in me.polygons]
        me.set_sharp_from_angle(angle=math.radians(sharp_angle_deg))
        for p, s in zip(me.polygons, smooth):
            p.use_smooth = s or p.use_smooth
        os.makedirs(os.path.dirname(obj_path), exist_ok=True)
        bpy.ops.wm.obj_export(filepath=obj_path, export_selected_objects=True, forward_axis='NEGATIVE_Z', up_axis='Y',
                              export_uv=False, export_normals=True, export_materials=False,
                              export_triangulated_mesh=True, apply_modifiers=True)
        with open(obj_path, "r", encoding="utf-8") as f:
            body = [l for l in f.read().splitlines() if not l.startswith("#")]
        tris = sum(1 for l in body if l.startswith("f "))
        head = ["# Starsector 3D hull model: %s (made with src-art/s3d_kit.py)" % self.id,
                "# s3d sprite %d %d" % (self.w, self.h)]
        with open(obj_path, "w", encoding="utf-8") as f:
            f.write("\n".join(head + body) + "\n")
        if blend_path:
            os.makedirs(os.path.dirname(blend_path), exist_ok=True)
            bpy.ops.wm.save_as_mainfile(filepath=blend_path)
        print("S3DKIT wrote %s: %d triangles" % (obj_path, tris))
        return tris


def grid_image(game_dir, hull_id, out_path, scale=9):
    """The sprite blown up with a pixel grid (every 4 px, labelled every 8) and the rotation centre
    in red: the sheet to read part positions from. Run: src-art/build.sh --grid <hullId>."""
    sprite, cx, cy_top = ship_file(game_dir, hull_id)
    img = bpy.data.images.load(sprite)
    w, h = img.size
    px = np.array(img.pixels[:], dtype=np.float32).reshape(h, w, 4)[::-1]  # row 0 = top
    bpy.data.images.remove(img)
    bg = np.array((0.16, 0.0, 0.2), dtype=np.float32)
    rgb = px[:, :, :3] * px[:, :, 3:4] + bg * (1 - px[:, :, 3:4])
    big = np.repeat(np.repeat(rgb, scale, axis=0), scale, axis=1)
    for c in range(0, w + 1, 4):
        x = min(c * scale, w * scale - 1)
        big[:, x] = big[:, x] * 0.4 + (np.array((1, 1, 0)) if c % 8 == 0 else np.array((1, 1, 1))) * (0.6 if c % 8 == 0 else 0.25)
    for r in range(0, h + 1, 4):
        y = min(r * scale, h * scale - 1)
        big[y, :] = big[y, :] * 0.4 + (np.array((1, 1, 0)) if r % 8 == 0 else np.array((1, 1, 1))) * (0.6 if r % 8 == 0 else 0.25)
    big[:, min(int(cx * scale), w * scale - 1)] = (1, 0, 0)
    big[min(int(cy_top * scale), h * scale - 1), :] = (1, 0, 0)
    H, W = big.shape[:2]
    out = bpy.data.images.new("grid", W, H)
    rgba = np.concatenate([big, np.ones((H, W, 1), dtype=np.float32)], axis=2)[::-1]
    out.pixels.foreach_set(rgba.astype(np.float32).ravel())
    out.filepath_raw = out_path
    out.file_format = "PNG"
    out.save()
    print("S3DKIT wrote %s (%dx%d sprite, centre col %.1f row %.1f; grid lines every 4 px, yellow every 8)" % (out_path, w, h, cx, cy_top))


if __name__ == "__main__":
    # blender -b --python s3d_kit.py -- grid <game data dir> <mod dir> <hullId>
    import sys
    a = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if len(a) == 4 and a[0] == "grid":
        os.makedirs(os.path.join(a[2], "build", "art"), exist_ok=True)
        grid_image(a[1], a[3], os.path.join(a[2], "build", "art", a[3] + "_grid.png"))


# ---- geometry helpers ----

def _frame(t):
    """Side and up vectors for a cross-section perpendicular to direction t (side stays horizontal)."""
    z = np.array((0.0, 0.0, 1.0))
    side = np.cross(t, z)
    if np.linalg.norm(side) < 1e-6:
        side = np.array((1.0, 0.0, 0.0))
    side /= np.linalg.norm(side)
    if side[0] < 0:  # keep "side" pointing to +X for paths along the ship
        side = -side
    up = np.cross(side, t)
    up /= np.linalg.norm(up)
    if up[2] < 0:
        up = -up
    return side, up


def _blur(a, sigma):
    r = max(1, int(math.ceil(sigma * 2.5)))
    k = np.exp(-(np.arange(-r, r + 1) ** 2) / (2 * sigma * sigma))
    k /= k.sum()
    p = np.pad(a, r, mode="constant")
    p = np.apply_along_axis(lambda m: np.convolve(m, k, mode="same"), 0, p)
    p = np.apply_along_axis(lambda m: np.convolve(m, k, mode="same"), 1, p)
    return p[r:-r, r:-r]


def _contours(a, thr):
    """Marching squares over pixel centres; returns closed loops of (col, row) points."""
    H, W = a.shape
    g = np.zeros((H + 2, W + 2), dtype=np.float32)
    g[1:-1, 1:-1] = a - thr
    g[0, :] = g[-1, :] = g[:, 0] = g[:, -1] = -thr

    # grid point (i, j) sits at col = j - 0.5, row = i - 0.5
    def pt_h(i, j):  # on the edge (i, j)-(i, j+1)
        t = g[i, j] / (g[i, j] - g[i, j + 1])
        return (j - 0.5 + t, i - 0.5)

    def pt_v(i, j):  # on the edge (i, j)-(i+1, j)
        t = g[i, j] / (g[i, j] - g[i + 1, j])
        return (j - 0.5, i - 0.5 + t)

    nxt = {}
    for i in range(H + 1):
        for j in range(W + 1):
            a0, a1, a2, a3 = g[i, j] > 0, g[i, j + 1] > 0, g[i + 1, j + 1] > 0, g[i + 1, j] > 0
            case = a0 | a1 << 1 | a2 << 2 | a3 << 3
            if case in (0, 15):
                continue
            top, right, bot, left = ("h", i, j), ("v", i, j + 1), ("h", i + 1, j), ("v", i, j)
            # segments oriented with the inside on the left
            segs = {
                1: [(left, top)], 2: [(top, right)], 3: [(left, right)], 4: [(right, bot)],
                6: [(top, bot)], 7: [(left, bot)], 8: [(bot, left)], 9: [(bot, top)],
                11: [(bot, right)], 12: [(right, left)], 13: [(right, top)], 14: [(top, left)],
            }.get(case)
            if segs is None:  # saddles 5, 10: split or join by the centre value
                centre = (g[i, j] + g[i, j + 1] + g[i + 1, j + 1] + g[i + 1, j]) > 0
                if case == 5:
                    segs = [(left, bot), (right, top)] if centre else [(left, top), (right, bot)]
                else:
                    segs = [(top, left), (bot, right)] if centre else [(top, right), (bot, left)]
            for s, e in segs:
                nxt[s] = e
    loops = []
    seen = set()
    for start in list(nxt.keys()):
        if start in seen:
            continue
        loop, k = [], start
        while k not in seen and k in nxt:
            seen.add(k)
            loop.append(pt_h(k[1], k[2]) if k[0] == "h" else pt_v(k[1], k[2]))
            k = nxt[k]
        if len(loop) >= 3:
            loops.append(loop)
    return loops


def _area(loop):
    return 0.5 * sum(loop[i][0] * loop[(i + 1) % len(loop)][1] - loop[(i + 1) % len(loop)][0] * loop[i][1] for i in range(len(loop)))


def _simplify(loop, tol):
    """Douglas-Peucker on a closed loop."""
    if len(loop) < 8 or tol <= 0:
        return loop
    pts = np.array(loop)
    far = int(np.argmax(np.linalg.norm(pts - pts[0], axis=1)))
    a = _dp(pts[: far + 1], tol)
    b = _dp(np.vstack([pts[far:], pts[:1]]), tol)
    return [tuple(p) for p in np.vstack([a[:-1], b[:-1]])]


def _dp(pts, tol):
    if len(pts) < 3:
        return pts
    a, b = pts[0], pts[-1]
    ab = b - a
    n = np.linalg.norm(ab)
    if n < 1e-9:
        d = np.linalg.norm(pts - a, axis=1)
    else:
        d = np.abs(ab[0] * (pts[:, 1] - a[1]) - ab[1] * (pts[:, 0] - a[0])) / n
    i = int(np.argmax(d))
    if d[i] <= tol:
        return np.vstack([a, b])
    return np.vstack([_dp(pts[: i + 1], tol)[:-1], _dp(pts[i:], tol)])
