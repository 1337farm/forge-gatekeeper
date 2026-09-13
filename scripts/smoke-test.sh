#!/usr/bin/env bash
# smoke-test.sh — nanogatekeeper smoke: unit tests + release AARs + archive checks.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> unit tests"
./gradlew :nanogatekeeper:testDebugUnitTest :nanogatekeeper-litert:testDebugUnitTest --stacktrace

echo "==> assemble release AARs"
./gradlew :nanogatekeeper:assembleRelease :nanogatekeeper-litert:assembleRelease --stacktrace

check_aar() {
  local mod="$1" name="$2"
  local aar
  aar="$(find "$mod/build/outputs/aar" -name "$name" | head -1)"
  [ -n "$aar" ] || { echo "ERROR: $name not found"; exit 1; }
  echo "AAR: $aar"
  ls -lh "$aar"
  unzip -l "$aar" | grep -q "classes.jar" || { echo "ERROR: classes.jar missing in $name"; exit 1; }
  unzip -l "$aar" | grep -q "AndroidManifest.xml" || { echo "ERROR: AndroidManifest.xml missing in $name"; exit 1; }
}

check_aar nanogatekeeper nanogatekeeper-release.aar
check_aar nanogatekeeper-litert nanogatekeeper-litert-release.aar

echo "==> archive checks"
# Bytecode defense: no plaintext system-prompt strings in the core AAR classes.
TMP="$(mktemp -d)"
unzip -q "$(find nanogatekeeper/build/outputs/aar -name 'nanogatekeeper-release.aar' | head -1)" -d "$TMP"
if grep -r "lossless semantic compressor" "$TMP" >/dev/null 2>&1; then
  echo "ERROR: plaintext system prompt leaked into AAR"
  exit 1
fi
rm -rf "$TMP"

echo "smoke OK"
