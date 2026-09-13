#!/usr/bin/env bash
# smoke-test.sh — nanogatekeeper smoke: unit tests + release AARs + archive checks.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> unit tests"
./gradlew :nanogatekeeper:testDebugUnitTest :nanogatekeeper-litert:testDebugUnitTest :nanogatekeeper-ort:testDebugUnitTest --stacktrace

echo "==> assemble release AARs"
./gradlew :nanogatekeeper:assembleRelease :nanogatekeeper-litert:assembleRelease :nanogatekeeper-ort:assembleRelease --stacktrace

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
check_aar nanogatekeeper-ort nanogatekeeper-ort-release.aar

echo "==> native lib check (ORT backend)"
ORT_AAR="$(find nanogatekeeper-ort/build/outputs/aar -name 'nanogatekeeper-ort-release.aar' | head -1)"
for lib in 'jni/arm64-v8a/libllm_engine.so' 'jni/arm64-v8a/libonnxruntime.so' 'jni/arm64-v8a/libonnxruntime-genai.so'; do
  unzip -l "$ORT_AAR" | grep -q "$lib" || { echo "ERROR: $lib missing in ORT AAR"; exit 1; }
done
echo "ORT natives present (llm_engine + onnxruntime + onnxruntime-genai)"

echo "==> demo APK checks (arm64-only natives, decompressed for download)"
./gradlew :app:assembleDebug --stacktrace
APP_APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
[ -n "$APP_APK" ] || { echo "ERROR: demo APK not built"; exit 1; }
unzip -l "$APP_APK" 'lib/*' | grep -qE 'lib/(x86|x86_64|armeabi)/' && {
  echo "ERROR: non-arm64 native ABI leaked into demo APK"; exit 1; }
unzip -lv "$APP_APK" 'lib/arm64-v8a/*.so' | grep -q " Stored " && {
  echo "ERROR: natives stored uncompressed (useLegacyPackaging must stay on)"; exit 1; } || true
echo "demo APK OK: arm64-v8a only, natives compressed"

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
