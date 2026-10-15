"""Fits a ready-made STL (or OBJ) model to a hull's sprite and exports it as the mod's hull model.

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
The game paints the model with the sprite projected from above (the OBJ has no UVs), so a good
silhouette match is what makes the paint line up.
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

    if model_path.lower().endswith(".stl"):
        bpy.ops.wm.stl_import(filepath=model_path)
    else:
        bpy.ops.wm.obj_import(filepath=model_path)
    ob = bpy.context.selected_objects[0]
    for o in bpy.context.selected_objects[1:]:
        o.select_set(True)
    if len(bpy.context.selected_objects) > 1:
        bpy.context.view_layer.objects.active = ob
        bpy.ops.object.join()
        ob = bpy.context.view_layer.objects.active
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
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
    me.vertices.foreach_set("co", NV.ravel().astype(np.float32))
    me.update()  # a pure rotation + scale: face winding stays outward

    tris = len(T)
    if tris > target:
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

    hull.parts = [ob]
    hull.export(mod_dir, sharp_angle_deg=40.0, uv="fold")


def perm_sign(p):
    """+1 for an even permutation of (0, 1, 2), -1 for an odd one."""
    inv = sum(1 for i in range(3) for j in range(i + 1, 3) if p[i] > p[j])
    return -1 if inv % 2 else 1


if __name__ == "__main__":
    main()
