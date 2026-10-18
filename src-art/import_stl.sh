#!/bin/bash
# Fits a downloaded STL/OBJ to a hull's sprite and exports it as that hull's model (see import_stl.py).
#   src-art/import_stl.sh <hullId> <model.stl|.obj|.glb> [triangles]
#   a textured GLB (or OBJ + .mtl) keeps its own paint, baked to <hullId>.png beside the .obj
#   S3D_PAINT=sprite ...       ignore the model's texture, paint it with the sprite
#   S3D_PAINT_SIZE=4096 ...    paint texture size (default 2048)
#   S3D_MODEL_DIR=build/art/hulls src-art/import_stl.sh ...   stage it where the game won't load it
#   S3D_CLAMP_OUTLINE=1 src-art/import_stl.sh ...   fold parts hanging outside the sprite onto its outline
#                                                  (for generated models whose outline drifts; ends up ~1.7x triangles)
#   S3D_PRESS=<mounts.json> src-art/import_stl.sh ...   press modelled guns flat onto the deck (cut shapes per
#                                                  mount in sprite pixels; see press_mounts in import_stl.py)
# Also writes build/art/<hullId>_fit.png (sprite alpha green, model red, overlap yellow).
set -euo pipefail
ART="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="$(dirname "$ART")"
GAME_DIR="$MOD_DIR/../../Contents/Resources/Java"
BLENDER="${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}"
[[ $# -ge 2 ]] || { echo "usage: $0 <hullId> <model.stl|.obj|.glb> [triangles]" >&2; exit 2; }
mkdir -p "$MOD_DIR/build/art"
log="$MOD_DIR/build/art/$1_import.log"
if ! "$BLENDER" -b --factory-startup --python-exit-code 1 --python "$ART/import_stl.py" -- "$GAME_DIR" "$MOD_DIR" "$1" "$2" "${3:-20000}" > "$log" 2>&1; then
	grep -E -A12 "Traceback|Error" "$log" >&2 || tail -20 "$log" >&2
	echo "$1: Blender failed (full log: $log)" >&2
	exit 1
fi
grep "S3DKIT" "$log"
