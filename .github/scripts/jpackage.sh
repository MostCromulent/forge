#!/usr/bin/env bash
# Build a native Forge installer (Windows .exe / macOS .dmg / Linux .deb) that bundles
# all three launchers -- Forge, Forge Adventure, Adventure Editor -- plus a JRE.
#
# Usage: jpackage.sh <linux|windows|mac>   (run on a matching OS runner; jpackage cannot
# cross-build). Expects jpkg/input to already hold the three uber-jars + res/.
#
# Done in three steps because of a jpackage quirk: --add-launcher puts EVERY jar in
# --input on EVERY launcher's classpath, and added launchers inherit the primary's
# main-class. Forge's three apps are independent shaded uber-jars, so a shared classpath
# would collide. So we:
#   1. build an app-image with all three launchers (classpath shared at this point),
#   2. rewrite each launcher's .cfg to load only its own uber-jar + main-class (isolate_cfg.py),
#   3. wrap that corrected app-image into the native installer.
set -euo pipefail

PLATFORM="$1"
# VERSION is the invisible monotonic upgrade key (major.minor.YYDDD, MSI-legal); HUMAN is the
# release anchor used for the filename. The workflow passes both; the fallbacks derive from the
# pom versionCode for local runs. The app reports its -SNAPSHOT version from its jar manifest regardless.
VC=$(sed -n 's/.*<versionCode>\(.*\)<\/versionCode>.*/\1/p' pom.xml | head -1)
VERSION="${FORGE_VERSION:-${VC%.*}.$(date -u +%y%j)}"
HUMAN="${FORGE_HUMAN_VERSION:-${VC}-SNAPSHOT}"
VENDOR="Card Forge"
DESCRIPTION="Forge - an open-source Magic: The Gathering rules engine"
INPUT="jpkg/input"; IMG="jpkg/image"; OUT="jpkg/out"
PYTHON="$(command -v python3 || command -v python)"
mkdir -p "$IMG" "$OUT"

case "$PLATFORM" in
  windows) ICON="forge-gui-desktop/src/main/config/forge.ico" ;;
  mac)     ICON="forge-gui-desktop/src/main/config/Forge.icns" ;;
  linux)   ICON="AppIcon.png" ;;
  *) echo "unknown platform: $PLATFORM"; exit 1 ;;
esac

DESKTOP_JAR="$(cd "$INPUT" && ls forge-gui-desktop-*-jar-with-dependencies.jar)"
ADV_JAR="$(cd "$INPUT" && ls forge-gui-mobile-dev-*-jar-with-dependencies.jar)"
EDITOR_JAR="$(cd "$INPUT" && ls adventure-editor-jar-with-dependencies.jar)"

sed -e "s|PLACEHOLDER_ADV_JAR|$ADV_JAR|"       -e "s|PLACEHOLDER_ICON|$ICON|" .github/scripts/launchers/adventure.properties > jpkg/adventure.properties
sed -e "s|PLACEHOLDER_EDITOR_JAR|$EDITOR_JAR|" -e "s|PLACEHOLDER_ICON|$ICON|" .github/scripts/launchers/editor.properties    > jpkg/editor.properties

# 1. App-image with the three launchers. All three uber-jars land on every launcher's
#    classpath here (jpackage limitation); step 2 corrects that.
jpackage --type app-image --name Forge --app-version "$VERSION" --input "$INPUT" \
  --vendor "$VENDOR" --description "$DESCRIPTION" --copyright "$VENDOR" --icon "$ICON" \
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

# 3. Wrap the isolated app-image into the native installer with a proper wizard.
COMMON_ARGS=( --vendor "$VENDOR" --app-version "$VERSION" --license-file LICENSE )
case "$PLATFORM" in
  linux)
    TYPE=deb
    EXTRA_ARGS=( --linux-shortcut --linux-menu-group Game ) ;;
  windows)
    TYPE=exe
    EXTRA_ARGS=( --win-per-user-install --win-dir-chooser --win-menu --win-menu-group Forge
                 --win-shortcut --win-shortcut-prompt --win-help-url https://github.com/Card-Forge/forge
                 --win-upgrade-uuid 6b3a1f20-9c4d-4e7a-b8f1-2c3d4e5f6a7b ) ;;
  mac)
    TYPE=dmg
    EXTRA_ARGS=() ;;
esac
# EXTRA_ARGS may be empty (mac); guard the expansion for macOS's bash 3.2 under `set -u`.
jpackage --type "$TYPE" --app-image "$IMAGEPATH" --name Forge "${COMMON_ARGS[@]}" ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} --dest "$OUT"

# Rename the installer to the human version. The MSI/deb internal version stays VERSION (the
# monotonic upgrade key); only the filename carries the readable, release-anchored name.
case "$PLATFORM" in
  windows) mv "$OUT"/Forge-*.exe       "$OUT/Forge-$HUMAN.exe" ;;
  mac)     mv "$OUT"/Forge-*.dmg       "$OUT/Forge-$HUMAN.dmg" ;;
  linux)   mv "$OUT"/forge_*_amd64.deb "$OUT/forge_${HUMAN}_amd64.deb" ;;
esac
ls -lh "$OUT"
