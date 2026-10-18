"""Fits a ready-made STL, OBJ or GLB model to a hull's sprite and exports it as the mod's hull model.

  blender -b --factory-startup --python src-art/import_stl.py -- <game data dir> <mod dir> <hullId> <model file> [triangles]
  (or src-art/import_stl.sh <hullId> <model file> [triangles])

Downloaded models come in any size, axis order and facing. This script:
  1. takes the model's thinnest axis as up and tries every way of laying the other two over the sprite
     (nose up or down, mirrored or not), keeping the one whose top-down silhouette covers the sprite's
     alpha best; a mirror is only allowed together with flipping up/down, so the model is never inverted;
  2. scales and shifts it (uniform scale) to the best silhouette match;
  3. cuts it down to about [triangles] (default 20000) with Blender's collapse decimation;
  4. writes <id>.obj with the kit's header (to $S3D_MODEL_DIR or data/meshes/hulls) and
     build/art/<id>_fit.png: sprite alpha (green), model silhouette (red), overlap (yellow).
Without a texture the game paints the model with the sprite (projected from above, folded down the
walls), so a good silhouette match is what makes the paint line up.

A model that brings its own texture (a GLB, or an OBJ with an .mtl and image) keeps its paint instead:
the reduced mesh gets a fresh UV map and the original's colours are baked onto it, written as <id>.png
beside <id>.obj (texture size $S3D_PAINT_SIZE, default 2048). The game draws that instead of the sprite.
S3D_PAINT=sprite ignores the model's texture and paints it with the sprite as before.
"""

import math
import os
import sys

import bpy
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import s3d_kit  # noqa: E402


def silhouette(pts_xy, w, h):
    """Occupancy mask (h, w) of 2D points in sprite pixel space (col, row), closed by one pixel."""
    m = np.zeros((h, w), dtype=bool)
    c = np.floor(pts_xy[:, 0]).astype(int)
    r = np.floor(pts_xy[:, 1]).astype(int)
    ok = (c >= 0) & (c < w) & (r >= 0) & (r < h)
    m[r[ok], c[ok]] = True
    # close small gaps between samples: dilate then erode once
    d = m.copy()
    d[1:, :] |= m[:-1, :]; d[:-1, :] |= m[1:, :]; d[:, 1:] |= m[:, :-1]; d[:, :-1] |= m[:, 1:]
    e = d.copy()
    e[1:, :] &= d[:-1, :]; e[:-1, :] &= d[1:, :]; e[:, 1:] &= d[:, :-1]; e[:, :-1] &= d[:, 1:]
    return e | m


def iou(a, b):
    return (a & b).sum() / max(1, (a | b).sum())


def main():
    argv = sys.argv[sys.argv.index("--") + 1:]
    game_dir, mod_dir, hull_id, model_path = argv[:4]
    target = int(argv[4]) if len(argv) > 4 else 20000

    hull = s3d_kit.Hull.from_game(game_dir, hull_id)
    w, h = hull.w, hull.h
    alpha = hull.alpha > 0.45

    ext_in = os.path.splitext(model_path)[1].lower()
    if ext_in == ".stl":
        bpy.ops.wm.stl_import(filepath=model_path)
    elif ext_in in (".glb", ".gltf"):
        bpy.ops.import_scene.gltf(filepath=model_path)
    else:
        bpy.ops.wm.obj_import(filepath=model_path)
    meshes = [o for o in bpy.context.selected_objects if o.type == "MESH"]
    for o in bpy.context.selected_objects:
        o.select_set(o in meshes)
    bpy.context.view_layer.objects.active = meshes[0]
    bpy.ops.object.parent_clear(type="CLEAR_KEEP_TRANSFORM")  # glTF hangs meshes under empties
    ob = meshes[0]
    if len(meshes) > 1:
        bpy.ops.object.join()
        ob = bpy.context.view_layer.objects.active
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    # glTF (and OBJ with UVs) split vertices along every UV seam; weld them back, or decimation and
    # unwrapping see loose triangles. UVs live per face corner, so the seams survive.
    import bmesh
    bm = bmesh.new()
    bm.from_mesh(ob.data)
    size = max(ob.dimensions)
    welded = len(bm.verts)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=size * 1e-6)
    welded -= len(bm.verts)
    bm.to_mesh(ob.data)
    bm.free()
    if welded:
        print("S3DKIT welded %d split vertices" % welded)
    painted =os.environ.get("S3D_PAINT") != "sprite" and model_texture(ob) is not None
    print("S3DKIT paint: %s" % ("the model's own texture (baked)" if painted else "the sprite"))
    me = ob.data
    n = len(me.vertices)
    V = np.empty(n * 3, dtype=np.float64)
    me.vertices.foreach_get("co", V)
    V = V.reshape(n, 3)
    me.calc_loop_triangles()
    T = np.empty(len(me.loop_triangles) * 3, dtype=np.int64)
    me.loop_triangles.foreach_get("vertices", T)
    T = T.reshape(-1, 3)
    print("S3DKIT model: %d verts, %d triangles, size %s" % (n, len(T), np.round(V.max(0) - V.min(0), 3)))

    ext = V.max(0) - V.min(0)
    up = int(np.argmin(ext))
    plan = [i for i in range(3) if i != up]
    S = s3d_kit.surface_samples(V, T, per_tri=2)

    # sprite alpha bounding box, in pixel-edge coordinates
    rows = np.where(alpha.any(1))[0]
    cols = np.where(alpha.any(0))[0]
    sb0 = np.array([cols[0], rows[0]], float)
    sb1 = np.array([cols[-1] + 1, rows[-1] + 1], float)

    best = None
    for swap in (False, True):
        ax = plan[::-1] if swap else plan
        for sc in (1, -1):
            for sr in (1, -1):
                # col = sc * model[ax0], row = sr * model[ax1]; in the hull frame X = col, Y = -row, so
                # the model axes ax0, ax1, up go to sc X, -sr Y, zsign Z. A rotation (det +1, never a
                # mirror) needs zsign = -sc * sr * sign of the permutation (ax0, ax1, up).
                zsign = -sc * sr * perm_sign((ax[0], ax[1], up))
                p = np.stack([sc * S[:, ax[0]], sr * S[:, ax[1]]], 1)
                b0, b1 = p.min(0), p.max(0)
                k = float(np.mean((sb1 - sb0) / (b1 - b0)))
                off = (sb0 + sb1) / 2 - (b0 + b1) / 2 * k
                cand = (0.0, None)
                for ks in np.linspace(0.94, 1.06, 13):  # refine scale and shift on the silhouette
                    for dx in np.linspace(-3, 3, 7):
                        for dy in np.linspace(-3, 3, 7):
                            kk = k * ks
                            o2 = (sb0 + sb1) / 2 - (b0 + b1) / 2 * kk + np.array([dx, dy])
                            score = iou(silhouette(p * kk + o2, w, h), alpha)
                            if score > cand[0]:
                                cand = (score, (kk, o2))
                label = "swap=%s col=%+d row=%+d up=%+d" % (swap, sc, sr, zsign)
                print("S3DKIT   %-34s IoU %.3f" % (label, cand[0]))
                # prefer keeping the model's own up when a symmetric ship fits both ways
                score = cand[0] + (0.01 if zsign > 0 else 0.0)
                if best is None or score > best[0]:
                    best = (score, cand[0], swap, sc, sr, zsign, ax, cand[1])
    _, fit, swap, sc, sr, zsign, ax, (k, off) = best
    print("S3DKIT best: swap=%s col=%+d row=%+d up=%+d, scale %.4f, IoU %.3f" % (swap, sc, sr, zsign, k, fit))

    # model -> Blender hull frame: X = col - cx, Y = cyTop - row, Z = height (1 unit = 1 pixel)
    def to_hull(P):
        col = sc * P[:, ax[0]] * k + off[0]
        row = sr * P[:, ax[1]] * k + off[1]
        z = zsign * P[:, up] * k
        return np.stack([col - hull.cx, hull.cy_top - row, z], 1)

    NV = to_hull(V)
    zmid = (NV[:, 2].min() + NV[:, 2].max()) / 2
    NV[:, 2] -= zmid  # mid-plane at z = 0, where engines and weapons sit
    if os.environ.get("S3D_CLAMP_OUTLINE"):
        clamp_to_outline(NV, alpha, hull.cx, hull.cy_top)
    if os.environ.get("S3D_PRESS"):
        press_mounts(NV, T, os.environ["S3D_PRESS"], w, h, hull.cx, hull.cy_top)
    me.vertices.foreach_set("co", NV.ravel().astype(np.float32))
    me.update()  # a pure rotation + scale: face winding stays outward
    if os.environ.get("S3D_CLAMP_OUTLINE"):
        # vertices pulled onto the same outline pixel pile up; merge them or decimation stalls on the slivers
        import bmesh
        bm = bmesh.new()
        bm.from_mesh(me)
        bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=0.05)
        bmesh.ops.dissolve_degenerate(bm, edges=bm.edges, dist=0.05)
        bm.to_mesh(me)
        bm.free()
        me.update()
        me.calc_loop_triangles()
        T = np.empty((len(me.loop_triangles), 3))  # only its length is used below

    hi = None
    if painted:  # keep the full model to bake its colours from
        hi = ob.copy()
        hi.data = ob.data.copy()
        bpy.context.collection.objects.link(hi)

    tris = len(T)
    if tris > target:  # stops short (about 1.7x) on outline-clamped meshes: the folds won't collapse further
        mod = ob.modifiers.new("decimate", "DECIMATE")
        mod.decimate_type = "COLLAPSE"
        mod.ratio = target / tris
        mod.use_collapse_triangulate = True
        bpy.ops.object.modifier_apply(modifier=mod.name)
    for p in ob.data.polygons:
        p.use_smooth = True

    # fit check image: sprite alpha green, model red, both yellow
    me = ob.data
    n = len(me.vertices)
    V2 = np.empty(n * 3); me.vertices.foreach_get("co", V2); V2 = V2.reshape(n, 3)
    me.calc_loop_triangles()
    T2 = np.empty(len(me.loop_triangles) * 3, dtype=np.int64); me.loop_triangles.foreach_get("vertices", T2)
    S2 = s3d_kit.surface_samples(V2, T2.reshape(-1, 3))
    sil = silhouette(np.stack([S2[:, 0] + hull.cx, hull.cy_top - S2[:, 1]], 1), w, h)
    print("S3DKIT after decimation: %d triangles, silhouette IoU %.3f, height %.1f (z %.1f..%.1f)"
          % (len(T2) // 3, iou(sil, alpha), V2[:, 2].max() - V2[:, 2].min(), V2[:, 2].min(), V2[:, 2].max()))
    rgb = np.zeros((h, w, 4), np.float32)
    rgb[..., 0] = sil
    rgb[..., 1] = alpha
    rgb[..., 3] = 1
    scale = 4
    big = np.repeat(np.repeat(rgb, scale, 0), scale, 1)[::-1]
    img = bpy.data.images.new("fit", w * scale, h * scale)
    img.pixels.foreach_set(big.ravel())
    os.makedirs(os.path.join(mod_dir, "build", "art"), exist_ok=True)
    img.filepath_raw = os.path.join(mod_dir, "build", "art", hull_id + "_fit.png")
    img.file_format = "PNG"
    img.save()

    out_dir = os.path.join(mod_dir, os.environ.get("S3D_MODEL_DIR") or os.path.join("data", "meshes", "hulls"))
    png = os.path.join(out_dir, hull_id + ".png")
    if painted:
        os.makedirs(out_dir, exist_ok=True)
        bake_paint(hi, ob, int(os.environ.get("S3D_PAINT_SIZE") or 2048), png)
    elif os.path.isfile(png):
        os.remove(png)  # a paint from an earlier textured import; its UVs are gone now
        print("S3DKIT removed the old paint %s" % png)

    hull.parts = [ob]
    hull.export(mod_dir, sharp_angle_deg=40.0, uv=True if painted else "fold")


def model_texture(ob):
    """The image node that colours the model (feeding a shader's base colour, or else the first colour
    image), or None when it has no textured material or no UVs."""
    if not ob.data.uv_layers:
        return None
    first = None
    for slot in ob.material_slots:
        m = slot.material
        if not m or not m.use_nodes:
            continue
        for node in m.node_tree.nodes:
            if node.type != "TEX_IMAGE" or not node.image:
                continue
            if node.image.colorspace_settings.is_data:
                continue  # normal / roughness / metal maps
            for link in node.outputs["Color"].links:
                if link.to_socket.name in ("Base Color", "Color", "Emission Color"):
                    return node
            first = first or node
    return first


def bake_paint(hi, low, size, png_path):
    """Gives [low] a fresh UV map and bakes [hi]'s texture colours onto it (from the surface just
    outside or inside each texel, so decimation drift doesn't matter), saved as [png_path]."""
    # every material of the original shows only its colour texture, unlit, so an emission bake copies it
    for slot in hi.material_slots:
        m = slot.material
        if not m or not m.use_nodes:
            continue
        nt = m.node_tree
        tex = next((n for n in nt.nodes if n.type == "TEX_IMAGE" and n.image
                    and not n.image.colorspace_settings.is_data), None)
        out = next((n for n in nt.nodes if n.type == "OUTPUT_MATERIAL"), None) or nt.nodes.new("ShaderNodeOutputMaterial")
        em = nt.nodes.new("ShaderNodeEmission")
        if tex:
            nt.links.new(tex.outputs["Color"], em.inputs["Color"])
        nt.links.new(em.outputs["Emission"], out.inputs["Surface"])
        for n in nt.nodes:
            if n.type == "OUTPUT_MATERIAL":
                n.is_active_output = n == out

    me = low.data
    while me.uv_layers:
        me.uv_layers.remove(me.uv_layers[0])
    me.uv_layers.new(name="paint")
    me.materials.clear()
    for o in bpy.context.view_layer.objects:
        o.select_set(o == low)
    bpy.context.view_layer.objects.active = low
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    # generated hulls are lumpy, so this makes thousands of small islands; packing them separately (concave
    # shapes, rotated, 2 texels apart) fills ~35% of the image, against ~3% with smart_project's own margin
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=0.0)
    bpy.ops.uv.pack_islands(rotate=True, shape_method="CONCAVE", margin_method="FRACTION", margin=2.0 / size)
    bpy.ops.object.mode_set(mode="OBJECT")

    img = bpy.data.images.new(os.path.basename(png_path), size, size, alpha=False)
    mat = bpy.data.materials.new("paint")
    mat.use_nodes = True
    node = mat.node_tree.nodes.new("ShaderNodeTexImage")
    node.image = img
    mat.node_tree.nodes.active = node
    me.materials.append(mat)

    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.device = "CPU"
    sc.cycles.samples = 4
    hi.select_set(True)
    low.select_set(True)
    bpy.context.view_layer.objects.active = low
    bpy.ops.object.bake(type="EMIT", use_selected_to_active=True, cage_extrusion=1.0, max_ray_distance=6.0,
                        margin=16, use_clear=True, target="IMAGE_TEXTURES")
    img.filepath_raw = png_path
    img.file_format = "PNG"
    img.save()
    me.materials.clear()  # the OBJ goes out without materials; the game finds <id>.png by name
    bpy.data.objects.remove(hi)
    print("S3DKIT baked the model's texture onto %d faces: %s (%dx%d)" % (len(me.polygons), png_path, size, size))


def clamp_to_outline(NV, alpha, cx, cy_top, slack=1.0):
    """Pulls vertices that hang outside the sprite (by more than [slack] px, seen from above) onto the nearest
    sprite pixel, in place. The mesh stays closed: overhangs fold onto the outline instead of being cut off,
    so they no longer pick up smeared edge paint. Height is kept."""
    h, w = alpha.shape
    col = NV[:, 0] + cx
    row = cy_top - NV[:, 1]
    # sprite pixels within reach of the outside: the inside pixels with an outside neighbour
    pad = np.pad(alpha, 1)
    edge = alpha & ~(pad[:-2, 1:-1] & pad[2:, 1:-1] & pad[1:-1, :-2] & pad[1:-1, 2:])
    er, ec = np.nonzero(edge)
    ep = np.stack([ec + 0.5, er + 0.5], 1)
    c = np.floor(col).astype(int)
    r = np.floor(row).astype(int)
    inside = (c >= 0) & (c < w) & (r >= 0) & (r < h)
    inside[inside] = alpha[r[inside], c[inside]]
    out = np.nonzero(~inside)[0]
    moved = 0
    for i in range(0, len(out), 4096):
        idx = out[i:i + 4096]
        p = np.stack([col[idx], row[idx]], 1)
        d2 = ((p[:, None, :] - ep[None, :, :]) ** 2).sum(-1)
        j = d2.argmin(1)
        far = d2[np.arange(len(idx)), j] > slack * slack
        tgt = ep[j[far]]
        NV[idx[far], 0] = tgt[:, 0] - cx
        NV[idx[far], 1] = cy_top - tgt[:, 1]
        moved += int(far.sum())
    print("S3DKIT outline clamp: %d of %d vertices pulled onto the sprite outline" % (moved, len(NV)))


def press_mounts(NV, T, spec_path, w, h, cx, cy_top, s=3):
    """Presses modelled guns flat, in place: for each mount in the JSON spec, every vertex over its cut
    shapes that stands above the mount's deck is lowered onto it. Shapes are in sprite pixels (col, row):
    ["disc", c, r, radius], ["cap", c0, r0, c1, r1, radius] (a stroke with round ends) or
    ["box", c0, r0, c1, r1]. The deck is the spec's "pct" percentile (default 35) of the top surface in a
    ring just outside the shapes, or "level"[mount] in pixels above the model's bottom. Runs before the
    paint bake, so a textured model keeps its colours on the flattened pads."""
    import json
    spec = json.load(open(spec_path))
    pct = spec.get("pct", 35)
    col = NV[:, 0] + cx
    row = cy_top - NV[:, 1]
    zb = top_heights(np.stack([col, row, NV[:, 2]], 1)[T], w, h, s)
    yy, xx = np.mgrid[0:h * s, 0:w * s]
    xx = (xx + 0.5) / s
    yy = (yy + 0.5) / s
    deck = np.full(zb.shape, np.inf)
    bottom = NV[:, 2].min()
    for name, parts in spec["shapes"].items():
        m = np.zeros(zb.shape, bool)
        for p in parts:
            if p[0] == "disc":
                m |= np.hypot(xx - p[1], yy - p[2]) < p[3]
            elif p[0] == "cap":
                v = np.array([p[3] - p[1], p[4] - p[2]])
                t = np.clip(((xx - p[1]) * v[0] + (yy - p[2]) * v[1]) / max(1e-9, v @ v), 0, 1)
                m |= np.hypot(xx - (p[1] + t * v[0]), yy - (p[2] + t * v[1])) < p[5]
            else:
                m |= (xx >= p[1]) & (xx <= p[3]) & (yy >= p[2]) & (yy <= p[4])
        m = grow(m, 1)
        if name in spec.get("level", {}):
            level = bottom + spec["level"][name]
        else:
            ring = grow(m, 2 * s) & ~m & ~np.isnan(zb)
            level = float(np.percentile(zb[ring], pct))
        deck[m] = np.minimum(deck[m], level)
    c = np.clip((col * s).astype(int), 0, w * s - 1)
    r = np.clip((row * s).astype(int), 0, h * s - 1)
    tgt = deck[r, c]
    drop = NV[:, 2] > tgt
    NV[drop, 2] = tgt[drop]
    print("S3DKIT pressed %d mounts flat (%d vertices lowered)" % (len(spec["shapes"]), drop.sum()))


def grow(m, n):
    """mask [m] dilated by [n] cells (4-neighbour)"""
    for _ in range(n):
        g = m.copy()
        g[1:] |= m[:-1]; g[:-1] |= m[1:]; g[:, 1:] |= m[:, :-1]; g[:, :-1] |= m[:, 1:]
        m = g
    return m


def top_heights(tri, w, h, s):
    """Highest surface z seen from above, per (h*s, w*s) cell (nan where there is no model), from
    triangles given as (n, 3 corners, [col, row, z])."""
    zb = np.full((h * s, w * s), np.nan)
    t = tri.copy()
    t[..., :2] *= s
    lo = np.floor(t[..., :2].min(1)).astype(int)
    span = (np.ceil(t[..., :2].max(1)).astype(int) - lo).max(1)
    e1, e2 = t[:, 1] - t[:, 0], t[:, 2] - t[:, 0]
    det = e1[:, 0] * e2[:, 1] - e1[:, 1] * e2[:, 0]
    ok = np.abs(det) > 1e-12
    for sz in sorted(set(span[ok].tolist())):
        sel = np.where(ok & (span == sz))[0]
        for dy in range(sz + 1):
            for dx in range(sz + 1):
                cx, cy = lo[sel, 0] + dx, lo[sel, 1] + dy
                px, py = cx + 0.5 - t[sel, 0, 0], cy + 0.5 - t[sel, 0, 1]
                a = (px * e2[sel, 1] - py * e2[sel, 0]) / det[sel]
                b = (py * e1[sel, 0] - px * e1[sel, 1]) / det[sel]
                inside = ((a >= -1e-6) & (b >= -1e-6) & (a + b <= 1 + 1e-6)
                          & (cx >= 0) & (cy >= 0) & (cx < w * s) & (cy < h * s))
                if inside.any():
                    i = sel[inside]
                    z = t[i, 0, 2] + a[inside] * e1[i, 2] + b[inside] * e2[i, 2]
                    np.fmax.at(zb, (cy[inside], cx[inside]), z)
    return zb


def perm_sign(p):
    """+1 for an even permutation of (0, 1, 2), -1 for an odd one."""
    inv = sum(1 for i in range(3) for j in range(i + 1, 3) if p[i] > p[j])
    return -1 if inv % 2 else 1


if __name__ == "__main__":
    main()
