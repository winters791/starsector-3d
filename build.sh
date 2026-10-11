#!/bin/bash
# Starsector 3D build script.
#   ./build.sh            compile src/ against the game's jars -> jars/Starsector3D.jar
#   ./build.sh tools      also compile the offline dev tools (src-tools/) into build/tools
#   ./build.sh preview [variantSprite...]   render relief-mesh previews to build/preview/*.png
#   ./build.sh bench      headless flight-model numbers for a few hulls
#   ./build.sh combat     headless weapon/damage rule checks on real hull shapes
set -euo pipefail

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAME_JAVA_DIR="$MOD_DIR/../../Contents/Resources/Java"
GAME_JRE="$MOD_DIR/../../Contents/Home"   # x86_64 Zulu 17 that ships with this install (runs under Rosetta)

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
	find "$MOD_DIR/src" -name '*.java' > "$BUILD/sources.txt"
	"$JAVAC" --release 17 -encoding UTF-8 -Xlint:all,-serial,-processing -cp "$CP" -d "$BUILD/classes" @"$BUILD/sources.txt"
	"$JAR" cf "$MOD_DIR/jars/Starsector3D.jar" -C "$BUILD/classes" .
	echo "built jars/Starsector3D.jar ($(find "$BUILD/classes" -name '*.class' | wc -l | tr -d ' ') classes)"
}

build_tools() {
	rm -rf "$BUILD/tools"
	mkdir -p "$BUILD/tools"
	find "$MOD_DIR/src-tools" -name '*.java' > "$BUILD/tool-sources.txt"
	"$JAVAC" --release 17 -encoding UTF-8 -cp "$CP:$BUILD/classes" -d "$BUILD/tools" @"$BUILD/tool-sources.txt"
}

# The tools need LWJGL's x86_64 natives, so they run on the game's own (x86_64) JRE.
run_tool() {
	local main="$1"; shift
	arch -x86_64 "$GAME_JRE/bin/java" -Djava.awt.headless=false \
		-Djava.library.path="$GAME_JAVA_DIR/native/macosx" \
		-cp "$CP:$BUILD/classes:$BUILD/tools" "$main" "$GAME_JAVA_DIR" "$BUILD" "$@"
}

case "${1:-}" in
	"") build_mod ;;
	tools) build_mod; build_tools ;;
	preview) shift; build_mod; build_tools; run_tool s3d.tools.MeshPreview "$@" ;;
	bench) build_mod; build_tools; run_tool s3d.tools.FlightBench ;;
	combat) build_mod; build_tools; run_tool s3d.tools.CombatBench ;;
	*) echo "usage: $0 [tools|preview|bench|combat]" >&2; exit 2 ;;
esac
