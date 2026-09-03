#!/usr/bin/env bash
# Cut a release of Idan Plus IL.
#
#   tools/release.sh <X.Y.Z> [--notes "one line shown on the TV prompt"] [--dry-run]
#
# What it does, in order:
#   1. checks the tree is clean, on main, in sync, and the tag/release do not exist
#   2. bumps idanplusil.versionCode (+1) and idanplusil.versionName in gradle.properties
#   3. runs the JVM tests and builds the signed release APK
#   4. refuses to continue unless the APK is signed with the release key
#      (tools/release-signer.sha256) - installed TVs reject any other signer
#   5. writes config/update.json (versionCode, sha256, size, asset URL), commits, tags
#   6. pushes the TAG ONLY, creates the GitHub Release with the APK attached,
#      waits until the asset URL resolves
#   7. pushes main - the manifest goes live only once the asset exists
#
# --dry-run stops after step 5 and prints how to undo the local commit and tag.
set -euo pipefail

REPO_SLUG="davidturchak/IdanPlusIL"
ASSET_PREFIX="IdanPlusIL"

die() { printf 'release: %s\n' "$*" >&2; exit 1; }
note() { printf '\n== %s\n' "$*"; }

VERSION="" NOTES="" DRY_RUN=0
while (($#)); do
  case "$1" in
    --notes) [[ $# -ge 2 ]] || die "--notes needs a value"; NOTES="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    -*) die "unknown option: $1" ;;
    *) [[ -z "$VERSION" ]] || die "unexpected argument: $1"; VERSION="$1"; shift ;;
  esac
done
[[ -n "$VERSION" ]] || die "usage: tools/release.sh <X.Y.Z> [--notes \"...\"] [--dry-run]"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "version must be X.Y.Z, got '$VERSION'"
TAG="v$VERSION"
NOTES="${NOTES:-Idan Plus IL $VERSION}"

cd "$(git rev-parse --show-toplevel)"

# ---- 1. preconditions ------------------------------------------------------
note "Checking preconditions"
for tool in git gh jq sha256sum stat curl; do
  command -v "$tool" >/dev/null || die "missing tool: $tool"
done
APKSIGNER="$(command -v apksigner || true)"
if [[ -z "$APKSIGNER" ]]; then
  SDK="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)}"
  APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
fi
[[ -x "${APKSIGNER:-}" ]] || die "apksigner not found (install build-tools or put it on PATH)"

[[ -f keystore.properties ]] || die "keystore.properties missing - see keystore.properties.example"
[[ -f tools/release-signer.sha256 ]] || die "tools/release-signer.sha256 missing"
EXPECTED_SIGNER="$(tr -d '[:space:]' < tools/release-signer.sha256 | tr 'A-F' 'a-f')"

[[ -z "$(git status --porcelain)" ]] || die "working tree is not clean"
[[ "$(git rev-parse --abbrev-ref HEAD)" == "main" ]] || die "not on main"
git fetch -q origin main "+refs/tags/*:refs/tags/*"
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "main is not in sync with origin/main"
! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "tag $TAG already exists"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated"
! gh release view "$TAG" --repo "$REPO_SLUG" >/dev/null 2>&1 || die "release $TAG already exists on GitHub"

# ---- 2. bump ---------------------------------------------------------------
OLD_CODE="$(sed -n 's/^idanplusil\.versionCode=//p' gradle.properties)"
OLD_NAME="$(sed -n 's/^idanplusil\.versionName=//p' gradle.properties)"
[[ "$OLD_CODE" =~ ^[0-9]+$ ]] || die "cannot read idanplusil.versionCode from gradle.properties"
CODE=$((OLD_CODE + 1))
note "Bumping $OLD_NAME ($OLD_CODE) -> $VERSION ($CODE)"
sed -i "s/^idanplusil\.versionCode=.*/idanplusil.versionCode=$CODE/" gradle.properties
sed -i "s/^idanplusil\.versionName=.*/idanplusil.versionName=$VERSION/" gradle.properties

# ---- 3. build --------------------------------------------------------------
note "Testing and building"
./gradlew --console=plain -q :resolver:test :app:testReleaseUnitTest :app:assembleRelease
APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || die "expected $APK (an unsigned build means keystore.properties was not picked up)"

# ---- 4. signer guard -------------------------------------------------------
ACTUAL_SIGNER="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1 | tr 'A-F' 'a-f')"
[[ -n "$ACTUAL_SIGNER" ]] || die "could not read the APK signer"
[[ "$ACTUAL_SIGNER" == "$EXPECTED_SIGNER" ]] || die "APK is signed with the WRONG key ($ACTUAL_SIGNER). Installed TVs would reject it."
echo "Signer OK: $ACTUAL_SIGNER"

# ---- 5. manifest, commit, tag ---------------------------------------------
# The download URL uses the uploaded FILE NAME (gh's "path#label" only changes the label).
ASSET_NAME="$ASSET_PREFIX-$VERSION.apk"
ASSET="app/build/outputs/apk/release/$ASSET_NAME"
cp "$APK" "$ASSET"
SHA="$(sha256sum "$ASSET" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$ASSET")"
APK_URL="https://github.com/$REPO_SLUG/releases/download/$TAG/$ASSET_NAME"

note "Writing config/update.json"
jq -n \
  --argjson versionCode "$CODE" --arg versionName "$VERSION" --arg apkUrl "$APK_URL" \
  --arg sha256 "$SHA" --argjson sizeBytes "$SIZE" --arg notes "$NOTES" \
  '{versionCode: $versionCode, versionName: $versionName, apkUrl: $apkUrl,
    sha256: $sha256, sizeBytes: $sizeBytes, notes: $notes}' > config/update.json
cat config/update.json

git add gradle.properties config/update.json
git commit -q -m "Release $TAG (versionCode $CODE)"
git tag -a "$TAG" -m "Idan Plus IL $VERSION"

if (( DRY_RUN )); then
  note "Dry run: nothing pushed. Undo with:"
  echo "  git tag -d $TAG && git reset --hard origin/main"
  exit 0
fi

# ---- 6. publish the asset ---------------------------------------------------
rollback_hint() {
  cat >&2 <<MSG

release: FAILED after the tag was pushed. Nothing on main has changed yet.
Manual rollback:
  gh release delete $TAG --repo $REPO_SLUG --yes    # if the release was created
  git push --delete origin $TAG
  git tag -d $TAG
  git reset --hard origin/main
MSG
}
trap rollback_hint ERR

note "Pushing tag $TAG"
git push -q origin "refs/tags/$TAG"

note "Creating GitHub release"
gh release create "$TAG" "$ASSET" --repo "$REPO_SLUG" --verify-tag \
  --title "Idan Plus IL $VERSION" --notes "$NOTES"

note "Waiting for $APK_URL"
ASSET_LIVE=0
for attempt in $(seq 1 12); do
  if curl -sfIL "$APK_URL" >/dev/null; then echo "asset is live"; ASSET_LIVE=1; break; fi
  sleep 5
done
# A `die` inside an || list bypasses the ERR trap, so print the hint by hand.
if (( ! ASSET_LIVE )); then rollback_hint; die "asset URL did not resolve"; fi

# ---- 7. publish the manifest ------------------------------------------------
note "Pushing main"
git push -q origin main
trap - ERR

cat <<MSG

Released Idan Plus IL $VERSION (versionCode $CODE).
  asset:    $APK_URL
  manifest: https://raw.githubusercontent.com/$REPO_SLUG/main/config/update.json
raw.githubusercontent.com caches for about 5 minutes; TVs launched before then
still see the previous manifest.
MSG
