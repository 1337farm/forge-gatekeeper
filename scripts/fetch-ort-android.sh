#!/usr/bin/env bash
# fetch-ort-android.sh — stage bare-metal ORT GenAI natives for arm64-v8a.
#
# The GenAI Android AAR is NOT on Maven (built per release); fetch it from the
# GitHub release tag plus the matching ORT Android AAR from Maven Central.
# Idempotent: skips when the version marker matches. CI runs this before
# externalNativeBuild; the same .so files are what AGP packages into the APK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/gatekeeper-ort/src/main/jniLibs/arm64-v8a"
MARKER="$OUT/.ort-version"

GENAI_VER="0.15.2"
ORT_VER="1.29.0"
GENAI_URL="https://github.com/microsoft/onnxruntime-genai/releases/download/v${GENAI_VER}/onnxruntime-genai-android-${GENAI_VER}.aar"
ORT_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ORT_VER}/onnxruntime-android-${ORT_VER}.aar"

if [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "genai-${GENAI_VER} ort-${ORT_VER}" ]; then
  echo "ORT natives already staged ($MARKER); skipping"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"

echo "==> downloading GenAI Android AAR v${GENAI_VER}"
curl -fsSL -o "$TMP/genai.aar" "$GENAI_URL"
echo "==> downloading ORT Android AAR v${ORT_VER}"
curl -fsSL -o "$TMP/ort.aar" "$ORT_URL"

echo "==> extracting arm64-v8a shared libs"
rm -f "$OUT"/libonnxruntime*.so
unzip -o -j "$TMP/genai.aar" 'jni/arm64-v8a/libonnxruntime-genai.so' -d "$OUT"
unzip -o -j "$TMP/ort.aar" 'jni/arm64-v8a/libonnxruntime.so' -d "$OUT"

# Structural gate: both libs present and non-trivial (a poison/truncated
# download must never reach the APK).
for so in libonnxruntime.so libonnxruntime-genai.so; do
  SZ="$(stat -c%s "$OUT/$so" 2>/dev/null || echo 0)"
  if [ "$SZ" -lt 1000000 ]; then
    echo "ERROR: $so missing or suspiciously small ($SZ bytes)" >&2
    exit 1
  fi
  echo "staged $so ($SZ bytes)"
done

echo "genai-${GENAI_VER} ort-${ORT_VER}" > "$MARKER"
echo "ORT natives staged in $OUT"
