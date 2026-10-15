#!/bin/bash
# Fits a downloaded STL/OBJ to a hull's sprite and exports it as that hull's model (see import_stl.py).
#   src-art/import_stl.sh <hullId> <model.stl> [triangles]
#   S3D_MODEL_DIR=build/art/hulls src-art/import_stl.sh ...   stage it where the game won't load it
# Also writes build/art/<hullId>_fit.png (sprite alpha green, model red, overlap yellow).
set -euo pipefail
ART="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="$(dirname "$ART")"
GAME_DIR="$MOD_DIR/../../Contents/Resources/Java"
BLENDER="${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}"
[[ $# -ge 2 ]] || { echo "usage: $0 <hullId> <model.stl> [triangles]" >&2; exit 2; }
mkdir -p "$MOD_DIR/build/art"
log="$MOD_DIR/build/art/$1_import.log"
if ! "$BLENDER" -b --factory-startup --python-exit-code 1 --python "$ART/import_stl.py" -- "$GAME_DIR" "$MOD_DIR" "$1" "$2" "${3:-20000}" > "$log" 2>&1; then
	grep -E -A12 "Traceback|Error" "$log" >&2 || tail -20 "$log" >&2
	echo "$1: Blender failed (full log: $log)" >&2
	exit 1
fi
grep "S3DKIT" "$log"
