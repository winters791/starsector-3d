#!/bin/bash
# Switch hand-made / imported hull models on and off.
#   ./models.sh                list every model and whether the game uses it
#   ./models.sh off <id|all>   stop using a model (moved to models_off/, which the game never reads)
#   ./models.sh on  <id|all>   use it again (moved back to data/meshes/hulls/)
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
	for f in "$ON"/*.obj; do [[ -e "$f" ]] && { printf "  on   %s\n" "$(basename "$f" .obj)"; any=1; }; done
	for f in "$OFF"/*.obj; do [[ -e "$f" ]] && { printf "  off  %s\n" "$(basename "$f" .obj)"; any=1; }; done
	[[ $any == 1 ]] || echo "  (no hull models)"
}

move() { # move <from dir> <to dir> <id|all> <verb>
	local from="$1" to="$2" id="$3" verb="$4" n=0
	if [[ "$id" == "all" ]]; then
		for f in "$from"/*.obj; do [[ -e "$f" ]] && { mv "$f" "$to/"; echo "$verb $(basename "$f" .obj)"; n=$((n + 1)); }; done
		[[ $n -gt 0 ]] || echo "nothing to $verb"
	elif [[ -e "$from/$id.obj" ]]; then
		if [[ -e "$to/$id.obj" ]]; then
			echo "error: $id.obj is in both folders; remove one first ($to/$id.obj)" >&2
			exit 1
		fi
		mv "$from/$id.obj" "$to/"
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
