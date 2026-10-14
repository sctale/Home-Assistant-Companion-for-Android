#!/usr/bin/env bash
#
# Builds, signs and verifies the release APK of the flavour this project ships (`minimal`, which links
# no Google Play services).
#
# Local-only by design: no hosted pipeline produces these artifacts.
#
# Usage:
#   scripts/build-release.sh <versionCode>
#   scripts/build-release.sh 1
#
# The version *name* comes from git, not from an argument: the reckon plugin derives it from the
# tag on HEAD. So the release flow is "commit, tag, build" - this script refuses to run on a tagless
# or dirty commit, because an APK that advertises 0.0.1-SNAPSHOT is not a release artifact.
#
# Signing material is read from keystore/signing.env (git-ignored, never commit it):
#   KEYSTORE_PATH keystore/release.keystore
#   KEYSTORE_PASSWORD ...
#   KEYSTORE_ALIAS ...
#   KEYSTORE_ALIAS_PASSWORD ...   (defaults to KEYSTORE_PASSWORD)
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION_CODE="${1:?usage: build-release.sh <versionCode>}"

if [[ -f keystore/signing.env ]]; then
  # shellcheck disable=SC1091
  source keystore/signing.env
fi
: "${KEYSTORE_PATH:?Set KEYSTORE_PATH (put it in keystore/signing.env)}"
: "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD}"
: "${KEYSTORE_ALIAS:?Set KEYSTORE_ALIAS}"
export KEYSTORE_ALIAS_PASSWORD="${KEYSTORE_ALIAS_PASSWORD:-$KEYSTORE_PASSWORD}"
export KEYSTORE_PATH KEYSTORE_PASSWORD KEYSTORE_ALIAS

[[ -f "$KEYSTORE_PATH" ]] || { echo "Keystore not found at $KEYSTORE_PATH" >&2; exit 1; }

TAG="$(git describe --tags --exact-match 2>/dev/null || true)"
[[ -n "$TAG" ]] || { echo "HEAD is not tagged. Commit the release and tag it before building." >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Working tree is dirty; reckon would produce a -SNAPSHOT version." >&2; exit 1; }

VERSION="${TAG#v}"
echo "==> Building minimalRelease v$VERSION (versionCode $VERSION_CODE) from $TAG"

VERSION_CODE="$VERSION_CODE" ./gradlew --console=plain :app:assembleMinimalRelease

APK="app/build/outputs/apk/minimal/release/app-minimal-release.apk"
[[ -f "$APK" ]] || { echo "Expected artifact missing: $APK" >&2; exit 1; }

BUILD_TOOLS="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

echo
echo "==> Package identity"
"$BUILD_TOOLS/aapt2" dump badging "$APK" 2>/dev/null |
  grep -E "^package:|sdkVersion:|targetSdkVersion:|application-label" |
  sed 's/^/  /'

echo "==> Signing"
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2>&1 | sed 's/^/  /'

# A release must never be signed by the debug key: it would be uninstallable over a real release and
# would silently change the trust root of every install.
if "$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2>&1 | grep -qi "Android Debug"; then
  echo "ERROR: artifact is signed with a debug key." >&2
  exit 1
fi

OUT="release/ha-companion-minimal-v$VERSION.apk"
mkdir -p release
cp "$APK" "$OUT"

echo "==> Size"
ls -lh "$OUT" | awk '{print "  " $5 "  " $9}'

echo
echo "Artifact: $OUT"
