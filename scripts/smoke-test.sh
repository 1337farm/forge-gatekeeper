#!/usr/bin/env bash
# smoke-test.sh — gatekeeper smoke: unit tests + release AARs + archive checks.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> unit tests"
./gradlew :gatekeeper:testDebugUnitTest :gatekeeper-litert:testDebugUnitTest :gatekeeper-ort:testDebugUnitTest --stacktrace

echo "==> assemble release AARs"
./gradlew :gatekeeper:assembleRelease :gatekeeper-litert:assembleRelease :gatekeeper-ort:assembleRelease --stacktrace

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

check_aar gatekeeper gatekeeper-release.aar
check_aar gatekeeper-litert gatekeeper-litert-release.aar
check_aar gatekeeper-ort gatekeeper-ort-release.aar

echo "==> native lib check (ORT backend)"
ORT_AAR="$(find gatekeeper-ort/build/outputs/aar -name 'gatekeeper-ort-release.aar' | head -1)"
for lib in 'jni/arm64-v8a/libllm_engine.so' 'jni/arm64-v8a/libonnxruntime.so' 'jni/arm64-v8a/libonnxruntime-genai.so' 'jni/arm64-v8a/libmat.so'; do
  unzip -l "$ORT_AAR" | grep -q "$lib" || { echo "ERROR: $lib missing in ORT AAR"; exit 1; }
done
echo "ORT natives present (llm_engine + onnxruntime + onnxruntime-genai + libmat)"

echo "==> demo APK checks (arm64-only natives, both backends bundled)"
./gradlew :app:assembleRelease --stacktrace
APP_APK="$(find app/build/outputs/apk/release -name '*.apk' | head -1)"
[ -n "$APP_APK" ] || { echo "ERROR: demo APK not built"; exit 1; }
unzip -l "$APP_APK" 'lib/*' | grep -qE 'lib/(x86|x86_64|armeabi)/' && {
  echo "ERROR: non-arm64 native ABI leaked into demo APK"; exit 1; }
# Monolith: ORT (llm_engine/onnxruntime) and MediaPipe (libmediapipe_tasks_genai) natives
# must both be inside the APK — nothing is fetched at runtime anymore.
for lib in 'lib/arm64-v8a/libllm_engine.so' 'lib/arm64-v8a/libonnxruntime.so' 'lib/arm64-v8a/libonnxruntime-genai.so' 'lib/arm64-v8a/libmat.so' 'lib/arm64-v8a/libllm_inference_engine_jni.so'; do
  unzip -l "$APP_APK" | grep -q "$lib" || { echo "ERROR: $lib missing from monolith demo APK"; exit 1; }
done
echo "demo APK OK: arm64-v8a only, ORT + MediaPipe natives bundled"
echo "demo APK size:"
ls -lh "$APP_APK"

echo "==> archive checks"
# Bytecode defense: no plaintext system-prompt strings in the core AAR classes.
TMP="$(mktemp -d)"
unzip -q "$(find gatekeeper/build/outputs/aar -name 'gatekeeper-release.aar' | head -1)" -d "$TMP"
if grep -r "lossless semantic compressor" "$TMP" >/dev/null 2>&1; then
  echo "ERROR: plaintext system prompt leaked into AAR"
  exit 1
fi
rm -rf "$TMP"

echo "smoke OK"
