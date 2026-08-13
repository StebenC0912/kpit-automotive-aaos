#!/usr/bin/env bash
# Builds vspManagerTool into a runnable jar (build/vspManagerTool.jar) using only javac/jar
# from a plain JDK - no Gradle/Maven, no external dependencies. For sanity-building this tool
# on a Linux dev machine; see build.bat for the Windows target this tool actually ships to.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$DIR/build"
CLASSES_DIR="$OUT_DIR/classes"
JAR_PATH="$OUT_DIR/vspManagerTool.jar"

rm -rf "$OUT_DIR"
mkdir -p "$CLASSES_DIR"

echo "Compiling sources..."
find "$DIR/src" -name '*.java' > "$OUT_DIR/sources.txt"
javac -d "$CLASSES_DIR" @"$OUT_DIR/sources.txt"

echo "Packaging $JAR_PATH..."
jar --create --file "$JAR_PATH" --main-class com.kpit.vspmanager.Main -C "$CLASSES_DIR" .

echo "Build complete: $JAR_PATH"
echo "Run with: java -jar $JAR_PATH"
