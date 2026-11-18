#!/bin/bash
# Starsector 3D build script.
#   ./build.sh            compile src/ against the game's jars -> jars/Starsector3D.jar
#   ./build.sh tools      also compile the offline dev tools (src-tools/) into build/tools
#   ./build.sh preview [variantSprite...]   render relief-mesh previews to build/preview/*.png
#   ./build.sh bench      headless flight-model numbers for a few hulls
#   ./build.sh combat     headless weapon/damage rule checks on real hull shapes
#   ./build.sh shots      cinematic battle stills (no HUD) to build/forum_shots/battles
#   ./build.sh release    build, then zip the player-facing files to dist/Starsector3D-<version>[-no-models].zip
set -euo pipefail

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The game install: the nearest folder above this one with Contents/Resources/Java (works from
# mods/<this>/ or from a deeper copy). Override with GAME_DIR=/path/to/Starsector.app
find_game_dir() {
	local d="$MOD_DIR"
	while [[ "$d" != "/" ]]; do
		d="$(dirname "$d")"
		if [[ -f "$d/Contents/Resources/Java/starfarer.api.jar" ]]; then
			echo "$d"
			return
		fi
	done
}
GAME_DIR="${GAME_DIR:-$(find_game_dir)}"
if [[ -z "$GAME_DIR" || ! -f "$GAME_DIR/Contents/Resources/Java/starfarer.api.jar" ]]; then
	echo "error: can't find the game (set GAME_DIR=/path/to/Starsector.app)" >&2
	exit 1
fi
GAME_JAVA_DIR="$GAME_DIR/Contents/Resources/Java"
GAME_JRE="$GAME_DIR/Contents/Home"   # x86_64 Zulu 17 that ships with this install (runs under Rosetta)

# Compile with a JDK 17 (the game runs Java 17). Override with JDK_HOME=...
JDK_HOME="${JDK_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
if [[ -z "$JDK_HOME" || ! -x "$JDK_HOME/bin/javac" ]]; then
	echo "error: need a JDK 17 (set JDK_HOME)" >&2
	exit 1
fi
JAVAC="$JDK_HOME/bin/javac"
JAR="$JDK_HOME/bin/jar"

CP="$GAME_JAVA_DIR/starfarer.api.jar:$GAME_JAVA_DIR/lwjgl.jar:$GAME_JAVA_DIR/lwjgl_util.jar:$GAME_JAVA_DIR/log4j-1.2.9.jar:$GAME_JAVA_DIR/json.jar"
BUILD="$MOD_DIR/build"

build_mod() {
	rm -rf "$BUILD/classes"
	mkdir -p "$BUILD/classes" "$MOD_DIR/jars"
	# Quoted, one per line: javac splits @argfile entries on spaces ("mods/Starsector3D 3/...").
	find "$MOD_DIR/src" -name '*.java' | sed 's/.*/"&"/' > "$BUILD/sources.txt"
	"$JAVAC" --release 17 -encoding UTF-8 -Xlint:all,-serial,-processing -cp "$CP" -d "$BUILD/classes" @"$BUILD/sources.txt"
	"$JAR" cf "$MOD_DIR/jars/Starsector3D.jar" -C "$BUILD/classes" .
	echo "built jars/Starsector3D.jar ($(find "$BUILD/classes" -name '*.class' | wc -l | tr -d ' ') classes)"
}

build_tools() {
	rm -rf "$BUILD/tools"
	mkdir -p "$BUILD/tools"
	find "$MOD_DIR/src-tools" -name '*.java' | sed 's/.*/"&"/' > "$BUILD/tool-sources.txt"
	"$JAVAC" --release 17 -encoding UTF-8 -cp "$CP:$BUILD/classes" -d "$BUILD/tools" @"$BUILD/tool-sources.txt"
}

# The tools need LWJGL's x86_64 natives, so they run on the game's own (x86_64) JRE.
run_tool() {
	local main="$1"; shift
	arch -x86_64 "$GAME_JRE/bin/java" -Djava.awt.headless=false \
		-Djava.library.path="$GAME_JAVA_DIR/native/macosx" \
		-cp "$CP:$BUILD/classes:$BUILD/tools" "$main" "$GAME_JAVA_DIR" "$BUILD" "$@"
}

# The player-facing mod, laid out as Starsector3D/... like earlier releases: no source, tools,
# screenshots or models_off/. "-no-models" when data/meshes/hulls holds no .obj models.
release() {
	local version stage name zip
	version="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$MOD_DIR/mod_info.json" | head -1)"
	[[ -n "$version" ]] || { echo "error: no version in mod_info.json" >&2; exit 1; }
	name="Starsector3D-$version"
	if ! compgen -G "$MOD_DIR/data/meshes/hulls/*.obj" > /dev/null; then name="$name-no-models"; fi
	stage="$BUILD/release"
	rm -rf "$stage"
	mkdir -p "$stage/Starsector3D/jars" "$stage/Starsector3D/data/meshes/hulls" "$MOD_DIR/dist"
	cp "$MOD_DIR/LICENSE" "$MOD_DIR/MODDING.md" "$MOD_DIR/mod_info.json" "$stage/Starsector3D/"
	cp "$MOD_DIR/jars/Starsector3D.jar" "$stage/Starsector3D/jars/"
	cp -R "$MOD_DIR/data/config" "$stage/Starsector3D/data/"
	if [[ -d "$MOD_DIR/data/meshes" ]]; then cp -R "$MOD_DIR/data/meshes/." "$stage/Starsector3D/data/meshes/"; fi
	find "$stage" -name '.DS_Store' -delete
	zip="$MOD_DIR/dist/$name.zip"
	rm -f "$zip"
	(cd "$stage" && zip -qrX "$zip" Starsector3D)
	echo "released dist/$name.zip ($(unzip -Z1 "$zip" | wc -l | tr -d ' ') entries)"
}

case "${1:-}" in
	"") build_mod ;;
	release) build_mod; release ;;
	tools) build_mod; build_tools ;;
	preview) shift; build_mod; build_tools; run_tool s3d.tools.MeshPreview "$@" ;;
	bench) build_mod; build_tools; run_tool s3d.tools.FlightBench ;;
	combat) build_mod; build_tools; run_tool s3d.tools.CombatBench ;;
	shots) build_mod; build_tools; run_tool s3d.tools.ForumShots ;;
	*) echo "usage: $0 [tools|preview|bench|combat|shots|release]" >&2; exit 2 ;;
esac
