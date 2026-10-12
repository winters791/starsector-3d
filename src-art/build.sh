#!/bin/bash
# Builds hull models with Blender, headless.
#   src-art/build.sh             every hull in src-art/hulls/
#   src-art/build.sh wolf eagle  just these
#   src-art/build.sh --grid wolf build/art/wolf_grid.png: the sprite with a pixel grid to read positions off
# Writes data/meshes/hulls/<id>.obj (what the mod loads) and build/art/<id>.blend (to open in Blender).
# S3D_MODEL_DIR=build/art/hulls src-art/build.sh ...  stages models where the game won't see them.
set -euo pipefail
ART="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="$(dirname "$ART")"
GAME_DIR="$MOD_DIR/../../Contents/Resources/Java"
BLENDER="${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}"

if [[ "${1:-}" == "--grid" ]]; then
	# src-art/build.sh --grid <hullId>: the sprite with a pixel grid, to read part positions from
	"$BLENDER" -b --factory-startup --python-exit-code 1 --python "$ART/s3d_kit.py" -- grid "$GAME_DIR" "$MOD_DIR" "$2" 2>&1 | grep -E "S3DKIT|Error"
	exit 0
fi

ids=("$@")
if [[ ${#ids[@]} -eq 0 ]]; then
	for f in "$ART"/hulls/*.py; do ids+=("$(basename "$f" .py)"); done
fi
mkdir -p "$MOD_DIR/build/art"
for id in "${ids[@]}"; do
	log="$MOD_DIR/build/art/$id.log"
	if ! "$BLENDER" -b --factory-startup --python-exit-code 1 --python "$ART/hulls/$id.py" -- "$GAME_DIR" "$MOD_DIR" > "$log" 2>&1; then
		grep -E -A12 "Traceback|Error" "$log" >&2 || tail -20 "$log" >&2
		echo "$id: Blender failed (full log: $log)" >&2
		exit 1
	fi
	grep "S3DKIT" "$log"
done
