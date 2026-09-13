#!/usr/bin/env bash
# smoke-test.sh — nanogatekeeper smoke: unit tests + release AAR + archive checks.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> unit tests"
./gradlew :nanogatekeeper:testDebugUnitTest --stacktrace

echo "==> assemble release AAR"
./gradlew :nanogatekeeper:assembleRelease --stacktrace

AAR="$(find nanogatekeeper/build/outputs/aar -name 'nanogatekeeper-release.aar' | head -1)"
[ -n "$AAR" ] || { echo "ERROR: nanogatekeeper-release.aar not found"; exit 1; }
echo "AAR: $AAR"
ls -lh "$AAR"

echo "==> archive checks"
unzip -l "$AAR" | grep -q "classes.jar" || { echo "ERROR: classes.jar missing in AAR"; exit 1; }
unzip -l "$AAR" | grep -q "AndroidManifest.xml" || { echo "ERROR: AndroidManifest.xml missing in AAR"; exit 1; }
# Bytecode defense: no plaintext system-prompt strings in the AAR classes.
TMP="$(mktemp -d)"
unzip -q "$AAR" -d "$TMP"
if grep -r "lossless semantic compressor" "$TMP" >/dev/null 2>&1; then
  echo "ERROR: plaintext system prompt leaked into AAR"
  exit 1
fi
rm -rf "$TMP"

echo "smoke OK: $AAR"
