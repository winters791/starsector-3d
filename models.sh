#!/bin/bash
# Switch hand-made / imported hull models on and off.
#   ./models.sh                list every model and whether the game uses it
#   ./models.sh off <id|all>   stop using a model (moved to models_off/, which the game never reads)
#   ./models.sh on  <id|all>   use it again (moved back to data/meshes/hulls/)
# A model's own paint (<id>.png beside <id>.obj) moves with it.
# A hull without an enabled model uses the sprite relief. Restart the battle (or press K in one) to see a change.
# models_off/ is kept out of git and out of release zips; third-party models stay out of git either way
# (see .gitignore).
set -euo pipefail
MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ON="$MOD_DIR/data/meshes/hulls"
OFF="$MOD_DIR/models_off"
mkdir -p "$ON" "$OFF"

list() {
	local any=0
	for f in "$ON"/*.obj; do [[ -e "$f" ]] && { printf "  on   %s%s\n" "$(basename "$f" .obj)" "$(painted "$f")"; any=1; }; done
	for f in "$OFF"/*.obj; do [[ -e "$f" ]] && { printf "  off  %s%s\n" "$(basename "$f" .obj)" "$(painted "$f")"; any=1; }; done
	[[ $any == 1 ]] || echo "  (no hull models)"
}

painted() { [[ -e "${1%.obj}.png" ]] && echo "  (own paint)" || true; }

mv_model() { # mv_model <obj file> <to dir>: the model and its paint, if any
	mv "$1" "$2/"
	if [[ -e "${1%.obj}.png" ]]; then mv "${1%.obj}.png" "$2/"; fi
}

move() { # move <from dir> <to dir> <id|all> <verb>
	local from="$1" to="$2" id="$3" verb="$4" n=0
	if [[ "$id" == "all" ]]; then
		for f in "$from"/*.obj; do [[ -e "$f" ]] && { mv_model "$f" "$to"; echo "$verb $(basename "$f" .obj)"; n=$((n + 1)); }; done
		[[ $n -gt 0 ]] || echo "nothing to $verb"
	elif [[ -e "$from/$id.obj" ]]; then
		if [[ -e "$to/$id.obj" ]]; then
			echo "error: $id.obj is in both folders; remove one first ($to/$id.obj)" >&2
			exit 1
		fi
		mv_model "$from/$id.obj" "$to"
		echo "$verb $id"
	elif [[ -e "$to/$id.obj" ]]; then
		echo "$id is already $verb"
	else
		echo "error: no model named $id" >&2
		list >&2
		exit 1
	fi
}

case "${1:-list}" in
	list) list ;;
	on) [[ $# -eq 2 ]] || { echo "usage: $0 on <id|all>" >&2; exit 2; }; move "$OFF" "$ON" "$2" "enabled" ;;
	off) [[ $# -eq 2 ]] || { echo "usage: $0 off <id|all>" >&2; exit 2; }; move "$ON" "$OFF" "$2" "disabled" ;;
	*) echo "usage: $0 [list | on <id|all> | off <id|all>]" >&2; exit 2 ;;
esac
