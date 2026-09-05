#!/usr/bin/env bash
#
# Builds uwuMail and drops the APK in the project root as uwuMail-<variant>.apk.
#
# Usage:
#   ./build.sh              # debug build
#   ./build.sh release      # release build (unsigned unless keystore.properties exists)
#   ./build.sh debug --install   # build, then adb install onto the connected device
#
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$PWD"

VARIANT="debug"
INSTALL=0
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    debug|release) VARIANT="$arg" ;;
    --install|-i)  INSTALL=1 ;;
    --clean|-c)    CLEAN=1 ;;
    -h|--help)
      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# --- locate the Android SDK -------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -f local.properties ] && grep -q '^sdk.dir=' local.properties; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
  elif [ -d "$HOME/Android/Sdk" ]; then
    ANDROID_HOME="$HOME/Android/Sdk"
  else
    echo "error: Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties." >&2
    exit 1
  fi
  export ANDROID_HOME
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

# --- pick a gradle ----------------------------------------------------------
if [ -x ./gradlew ]; then
  GRADLE="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="gradle"
else
  # Fall back to a wrapper distribution already in the Gradle user home.
  CACHED="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists" \
            -name gradle -type f -path '*/bin/*' 2>/dev/null | sort | tail -1)"
  if [ -n "$CACHED" ]; then
    GRADLE="$CACHED"
  else
    echo "error: no gradlew, no gradle on PATH, no cached distribution." >&2
    exit 1
  fi
fi

TASK="assembleDebug"
[ "$VARIANT" = "release" ] && TASK="assembleRelease"

if [ "$CLEAN" -eq 1 ]; then
  echo "==> cleaning"
  "$GRADLE" clean --console=plain
fi

echo "==> building $VARIANT with $GRADLE"
"$GRADLE" ":app:$TASK" --console=plain

# --- copy the artifact to the project root ---------------------------------
APK="$(find app/build/outputs/apk/"$VARIANT" -name '*.apk' -newermt '-1 hour' 2>/dev/null | head -1)"
if [ -z "$APK" ]; then
  APK="$(find app/build/outputs/apk/"$VARIANT" -name '*.apk' 2>/dev/null | head -1)"
fi
if [ -z "$APK" ]; then
  echo "error: build reported success but no APK was produced." >&2
  exit 1
fi

OUT="$ROOT/uwuMail-$VARIANT.apk"
cp -f "$APK" "$OUT"
SIZE="$(du -h "$OUT" | cut -f1)"
echo "==> $OUT ($SIZE)"

case "$APK" in
  *unsigned*)
    echo "    note: this build is unsigned and will not install as-is." >&2
    echo "    Add a signingConfig to app/build.gradle.kts, or sign it manually:" >&2
    echo "      \$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks <keystore> \\" >&2
    echo "          --out uwuMail-release-signed.apk \"$OUT\"" >&2
    ;;
esac

if [ "$INSTALL" -eq 1 ]; then
  ADB="${ANDROID_HOME}/platform-tools/adb"
  [ -x "$ADB" ] || ADB="adb"
  echo "==> installing"
  "$ADB" install -r "$OUT"
fi
