#!/usr/bin/env bash
# Build a release-signed APK with a LOW versionCode so an installed copy sees the
# currently published release as an update and runs the real self-update flow
# (prompt -> download from GitHub -> permission -> system installer).
#
#   tools/test-build.sh                 build only
#   tools/test-build.sh --install       also replace the app on the connected TV
#   tools/test-build.sh --manifest URL  point the build at another update.json
#                                       (e.g. a branch, to test a manifest change)
#
# The TV must be uninstalled first because Android refuses a version downgrade;
# --install does that. After launching, the update prompt appears within ~10 s.
set -euo pipefail

INSTALL=0 MANIFEST=""
while (($#)); do
  case "$1" in
    --install) INSTALL=1; shift ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

cd "$(git rev-parse --show-toplevel)"
[[ -f keystore.properties ]] || { echo "keystore.properties missing: the test build must carry the release signature" >&2; exit 1; }

ARGS=(-Pidanplusil.versionCode=1 -Pidanplusil.versionName=0.0.1-test)
[[ -n "$MANIFEST" ]] && ARGS+=("-PupdateManifestUrl=$MANIFEST")

echo "== Building test APK (versionCode 1)"
./gradlew --console=plain -q :app:assembleRelease "${ARGS[@]}"
SRC=app/build/outputs/apk/release/app-release.apk
OUT=app/build/outputs/apk/release/IdanPlusIL-test-vc1.apk
[[ -f "$SRC" ]] || { echo "unsigned build produced; is keystore.properties readable?" >&2; exit 1; }
mv "$SRC" "$OUT"
echo "APK: $OUT"

if (( INSTALL )); then
  echo "== Replacing the app on the connected TV"
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null
  adb uninstall com.idanplusil.tv >/dev/null 2>&1 || true
  adb install "$OUT"
  adb shell dumpsys package com.idanplusil.tv | grep -E 'versionCode|versionName' | head -2
  echo "Launch the app: the update prompt for the published release appears within ~10 s."
fi
