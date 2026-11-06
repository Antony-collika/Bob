#!/bin/sh
# Gradle wrapper shim for Bob. Prefers a locally installed Gradle; otherwise
# downloads the distribution named in gradle/wrapper/gradle-wrapper.properties.
set -e
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
DIST_URL=$(grep '^distributionUrl=' "$APP_HOME/gradle/wrapper/gradle-wrapper.properties" | cut -d= -f2- | sed 's/\\:/:/g')
DIST_DIR="$HOME/.gradle/wrapper/dists/bob-gradle"
mkdir -p "$DIST_DIR"
ZIP="$DIST_DIR/gradle.zip"
if [ ! -f "$ZIP" ]; then
  echo "Dang tai Gradle tu $DIST_URL ..."
  if command -v curl >/dev/null 2>&1; then curl -L -o "$ZIP" "$DIST_URL"; else wget -O "$ZIP" "$DIST_URL"; fi
  (cd "$DIST_DIR" && unzip -o -q "$ZIP")
fi
GRADLE_BIN=$(find "$DIST_DIR" -maxdepth 2 -name gradle -type f | head -n 1)
exec "$GRADLE_BIN" "$@"
