#!/usr/bin/env bash
# Build a native Forge installer bundling all three launchers, each isolated to
# its own uber-jar (matching Forge's per-app `java -jar X` model).
# Usage: jpackage.sh <linux|windows|mac>
# Expects jpkg/input to already contain the three uber-jars + res/.
set -euo pipefail

PLATFORM="$1"
VERSION="${FORGE_VERSION:-2.0.13}"   # jpackage requires a clean numeric version (no -SNAPSHOT)
INPUT="jpkg/input"; IMG="jpkg/image"; OUT="jpkg/out"
PYTHON="$(command -v python3 || command -v python)"
mkdir -p "$IMG" "$OUT"

DESKTOP_JAR="$(cd "$INPUT" && ls forge-gui-desktop-*-jar-with-dependencies.jar)"
ADV_JAR="$(cd "$INPUT" && ls forge-gui-mobile-dev-*-jar-with-dependencies.jar)"
EDITOR_JAR="$(cd "$INPUT" && ls adventure-editor-jar-with-dependencies.jar)"

sed "s|PLACEHOLDER_ADV_JAR|$ADV_JAR|"       .github/scripts/launchers/adventure.properties > jpkg/adventure.properties
sed "s|PLACEHOLDER_EDITOR_JAR|$EDITOR_JAR|" .github/scripts/launchers/editor.properties    > jpkg/editor.properties

# 1. App-image with the three launchers (jpackage shares the classpath here; fixed in step 2).
jpackage --type app-image --name Forge --app-version "$VERSION" --input "$INPUT" \
  --main-jar "$DESKTOP_JAR" --main-class forge.view.Main \
  --java-options "-Xmx4096m" --java-options "-Dio.netty.tryReflectionSetAccessible=true" --java-options "-Dfile.encoding=UTF-8" \
  --add-launcher "ForgeAdventure=jpkg/adventure.properties" \
  --add-launcher "AdventureEditor=jpkg/editor.properties" \
  --dest "$IMG"

# 2. Isolate each launcher's classpath + main class to its own jar.
case "$PLATFORM" in
  mac)     APPCFG="$IMG/Forge.app/Contents/app"; IMAGEPATH="$IMG/Forge.app" ;;
  linux)   APPCFG="$IMG/Forge/lib/app";          IMAGEPATH="$IMG/Forge" ;;
  windows) APPCFG="$IMG/Forge/app";              IMAGEPATH="$IMG/Forge" ;;
esac
"$PYTHON" .github/scripts/isolate_cfg.py "$APPCFG" \
  "Forge=$DESKTOP_JAR=forge.view.Main" \
  "ForgeAdventure=$ADV_JAR=forge.app.Main" \
  "AdventureEditor=$EDITOR_JAR=forge.adventure.Main"

# 3. Wrap the isolated app-image into the native installer.
case "$PLATFORM" in
  linux)   TYPE=deb;  EXTRA="" ;;
  windows) TYPE=exe;  EXTRA="--win-per-user-install --win-menu --win-shortcut" ;;
  mac)     TYPE=dmg;  EXTRA="" ;;
  *) echo "unknown platform: $PLATFORM"; exit 1 ;;
esac
jpackage --type "$TYPE" --app-image "$IMAGEPATH" --name Forge --app-version "$VERSION" $EXTRA --dest "$OUT"
ls -lh "$OUT"
