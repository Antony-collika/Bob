#!/bin/sh
# Gradle wrapper shim for Bob. Always uses the distribution named in
# gradle/wrapper/gradle-wrapper.properties. It must NOT prefer a
# locally installed Gradle: on CI runners that can be a newer major
# version than the Android Gradle Plugin supports (the runner's
# Gradle 9.8.2 broke the build that needs Gradle 8.7).
set -e
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_URL=$(grep '^distributionUrl=' "$APP_HOME/gradle/wrapper/gradle-wrapper.properties" | cut -d= -f2- | sed 's/\\:/:/g')
DIST_DIR="$HOME/.gradle/wrapper/dists/bob-gradle"
mkdir -p "$DIST_DIR"
ZIP="$DIST_DIR/gradle.zip"
if [ ! -f "$ZIP" ]; then
  echo "Dang tai Gradle tu $DIST_URL ..."
  if command -v curl >/dev/null 2>&1; then curl -L -o "$ZIP" "$DIST_URL"; else wget -O "$ZIP" "$DIST_URL"; fi
  (cd "$DIST_DIR" && unzip -o -q "$ZIP")
fi
# The zip extracts to gradle-8.7/bin/gradle, which is 3 levels below
# DIST_DIR, so maxdepth must be at least 3 (maxdepth 2 found nothing
# and exec of an empty path failed with exit code 126).
GRADLE_BIN=$(find "$DIST_DIR" -maxdepth 3 -name gradle -type f -path "*/bin/gradle" | head -n 1)
if [ -z "$GRADLE_BIN" ]; then
  echo "Khong tim thay file gradle trong $DIST_DIR" >&2
  exit 1
fi
chmod +x "$GRADLE_BIN"
exec "$GRADLE_BIN" "$@"
