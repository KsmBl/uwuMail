#!/usr/bin/env bash
#
# Builds uwuMail and drops the APK in the project root as uwuMail-<variant>.apk.
#
# Usage:
#   ./build.sh                   # release build (signed, minified) -> uwuMail-release.apk
#   ./build.sh debug             # debug build
#   ./build.sh --install         # build, then adb install onto the connected device
#   ./build.sh --clean           # clean first
#   ./build.sh release --no-sign # release build without creating/using a keystore
#
# The first release build creates keystore/uwumail-release.jks and
# keystore.properties with a random password. Both are gitignored. Keep them:
# an APK signed with a different key will not install over an existing one.
#
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$PWD"

VARIANT="release"
INSTALL=0
CLEAN=0
SIGN=1
for arg in "$@"; do
  case "$arg" in
    debug|release) VARIANT="$arg" ;;
    --install|-i)  INSTALL=1 ;;
    --clean|-c)    CLEAN=1 ;;
    --no-sign)     SIGN=0 ;;
    -h|--help)
      sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//;s/^#$//'
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

BUILD_TOOLS="$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null \
               | sort -V | tail -1)"

# --- pick a gradle ----------------------------------------------------------
if [ -x ./gradlew ]; then
  GRADLE="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="gradle"
else
  CACHED="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists" \
            -name gradle -type f -path '*/bin/*' 2>/dev/null | sort | tail -1)"
  if [ -n "$CACHED" ]; then
    GRADLE="$CACHED"
  else
    echo "error: no gradlew, no gradle on PATH, no cached distribution." >&2
    exit 1
  fi
fi

# --- create a signing key on the first release build ------------------------
KEYSTORE="keystore/uwumail-release.jks"
ALIAS="uwumail"

if [ "$VARIANT" = "release" ] && [ "$SIGN" -eq 1 ] && [ ! -f keystore.properties ]; then
  if ! command -v keytool >/dev/null 2>&1; then
    echo "error: keytool not found (install a JDK), or pass --no-sign." >&2
    exit 1
  fi
  echo "==> no signing key yet, creating one"
  mkdir -p keystore
  PASS="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32)"
  keytool -genkeypair \
    -keystore "$KEYSTORE" -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=uwuMail, O=uwuMail, C=DE" >/dev/null 2>&1
  # Subshell so the restrictive umask does not follow the rest of the script
  # and leave the built APK unreadable.
  ( umask 077
  cat > keystore.properties <<EOF
# Created by build.sh. Keep this file and $KEYSTORE together, and back them up:
# an APK signed with a different key cannot be installed over an existing one.
storeFile=$KEYSTORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF
  )
  chmod 600 keystore.properties
  echo "    $KEYSTORE + keystore.properties (back these up)"
fi

TASK="assembleDebug"
[ "$VARIANT" = "release" ] && TASK="assembleRelease"

if [ "$CLEAN" -eq 1 ]; then
  echo "==> cleaning"
  "$GRADLE" clean --console=plain
fi

echo "==> building $VARIANT"
"$GRADLE" ":app:$TASK" --console=plain

# --- copy the artifact to the project root ---------------------------------
OUTDIR="app/build/outputs/apk/$VARIANT"
APK="$(find "$OUTDIR" -name '*.apk' -newermt '-1 hour' 2>/dev/null | head -1)"
[ -n "$APK" ] || APK="$(find "$OUTDIR" -name '*.apk' 2>/dev/null | head -1)"
if [ -z "$APK" ]; then
  echo "error: build reported success but no APK was produced." >&2
  exit 1
fi

OUT="$ROOT/uwuMail-$VARIANT.apk"
cp -f "$APK" "$OUT"
chmod 644 "$OUT"
echo "==> $OUT ($(du -h "$OUT" | cut -f1))"

# --- report signing state ---------------------------------------------------
if [ -n "$BUILD_TOOLS" ] && [ -x "$BUILD_TOOLS/apksigner" ]; then
  if "$BUILD_TOOLS/apksigner" verify --print-certs "$OUT" >/tmp/uwumail-certs.$$ 2>/dev/null; then
    SHA1="$(sed -n 's/.*SHA-1 digest: *//p' /tmp/uwumail-certs.$$ | head -1 \
            | tr 'a-f' 'A-F' | sed 's/\(..\)/\1:/g;s/:$//')"
    echo "    signed, certificate SHA-1: ${SHA1:-unknown}"
    if [ "$VARIANT" = "release" ]; then
      echo "    add this fingerprint to your Google OAuth client if you use"
      echo "    Sign in with Google in release builds (the debug key differs)"
    fi
  else
    echo "    NOT SIGNED - this APK will not install." >&2
    echo "    Re-run without --no-sign, or add a signingConfig." >&2
  fi
  rm -f /tmp/uwumail-certs.$$
fi

if [ "$INSTALL" -eq 1 ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
  [ -x "$ADB" ] || ADB="adb"
  echo "==> installing"
  if ! "$ADB" install -r "$OUT" 2>&1 | tee /tmp/uwumail-install.$$; then
    if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" \
         /tmp/uwumail-install.$$ 2>/dev/null; then
      echo >&2
      echo "The installed copy was signed with a different key (a debug build," >&2
      echo "most likely). Uninstall it first - this wipes its accounts and mail:" >&2
      echo "    $ADB uninstall de.uwumail" >&2
    fi
    rm -f /tmp/uwumail-install.$$
    exit 1
  fi
  rm -f /tmp/uwumail-install.$$
fi
