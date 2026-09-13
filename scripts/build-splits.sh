#!/usr/bin/env bash
# build-splits.sh — demo app-bundle -> signed base + frozen arm64 runtime split.
#
# The ORT natives live in the ABI config split (split_config.arm64_v8a.apk).
# Because the demo's versionCode is FROZEN (5_000_000, see app/build.gradle.kts),
# that split can be downloaded once, cached on the phone's storage, and paired
# with ANY later base APK: `adb install-multiple -r base.apk runtime.apk` ratifies a
# new base against the already-installed runtime. This is the sideload-native
# equivalent of Play's config-split reuse for AAB. Updates therefore re-download
# only the small base (~30MB), not the ~40MB native runtime.
#
# Emits into apk-out/:
#   gatekeeper-base-$SHA.apk             code + resources, no natives
#   gatekeeper-runtime-arm64-$SHA.apk    libonnxruntime* + libllm_engine
#   gatekeeper-splits-$SHA.apks          full set for `install-multiple`
#
# Requires: JDK (bundletool) + Android SDK (bundleRelease). CI provides both;
# the committed demo keystore signs the splits (keystore/).
# SHA is taken from $SHA env (set by the workflow to head.sha) or GITHUB_SHA.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BUNDLETOOL_VER="1.18.3"
CACHE="$ROOT/.cache"
BUNDLETOOL="$CACHE/bundletool-all-${BUNDLETOOL_VER}.jar"
KS="$ROOT/keystore/gatekeeper-demo.keystore"
KS_PASS="gatekeeper"
KS_ALIAS="demo"

SHA="${SHA:-${GITHUB_SHA:-$(git rev-parse HEAD 2>/dev/null || echo local)}}"
SHA="$(printf '%s' "$SHA" | cut -c1-10)"
OUT="$ROOT/apk-out"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT" "$CACHE"

bt() { java -jar "$BUNDLETOOL" "$@"; }

if [ ! -f "$BUNDLETOOL" ]; then
  echo "==> downloading bundletool ${BUNDLETOOL_VER}"
  curl -fsSL -o "$BUNDLETOOL" \
    "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VER}/bundletool-all-${BUNDLETOOL_VER}.jar"
  [ -s "$BUNDLETOOL" ] || { echo "ERROR: bundletool download failed"; exit 1; }
fi

echo "==> build release bundle"
[ -x "./gradlew" ] || chmod +x ./gradlew
./gradlew :app:bundleRelease --stacktrace

AAB="$(find app/build/outputs/bundle/release -name 'app-release.aab' | head -1)"
[ -n "$AAB" ] || { echo "ERROR: no app-release.aab to split"; exit 1; }
echo "bundle: $AAB ($(du -h "$AAB" | cut -f1))"

echo "==> generate APK set from bundle (demo keystore)"
# Key pass omitted: bundletool defaults it to the keystore password, which the
# demo keystore uses (same pass for key + store).
bt build-apks --bundle="$AAB" --output="$WORK/app.apks" --mode=default \
  --ks="$KS" --ks-pass="pass:$KS_PASS" --ks-key-alias="$KS_ALIAS"

echo "==> extract base + arm64 config split"
cat > "$WORK/device-spec.json" <<'EOF'
{ "supported_abis": ["arm64-v8a"], "sdk_version": 35 }
EOF
bt extract-apks --apks="$WORK/app.apks" --device-spec="$WORK/device-spec.json" \
  --output-dir="$WORK/extract"
BASE="$(find "$WORK/extract" -maxdepth 1 -name 'base-master.apk' | head -1)"
SPLIT="$(find "$WORK/extract" -maxdepth 1 -name 'split_config.arm64_v8a.apk' | head -1)"
[ -n "$BASE" ] && [ -n "$SPLIT" ] || {
  echo "ERROR: base or split_config.arm64_v8a.apk missing from APK set:"; ls -1 "$WORK/extract"; exit 1; }

# Structural gates. The base must be natives-free (code+resources only) and the
# split must carry the real ORT natives; a malformed bundle must never publish.
if unzip -l "$BASE" 'lib/*' | grep -qE 'lib/(x86|x86_64|armeabi|arm64)' ; then
  echo "ERROR: native libs leaked into base APK"; exit 1
fi
for lib in 'lib/arm64-v8a/libllm_engine.so' 'lib/arm64-v8a/libonnxruntime.so' 'lib/arm64-v8a/libonnxruntime-genai.so'; do
  unzip -l "$SPLIT" | grep -q "$lib" || { echo "ERROR: $lib missing in arm64 split"; exit 1; }
done
# Compression is a download-size regression risk, not a functional break: warn
# (40MB stored still installs fine) rather than red the PR over alignment.
if unzip -lv "$SPLIT" 'lib/arm64-v8a/*.so' | grep -q " Stored "; then
  echo "WARN: runtime split natives stored uncompressed (expected deflated via useLegacyPackagingFromBundle)"
fi

DST_BASE="$OUT/gatekeeper-base-$SHA.apk"
DST_RUNTIME="$OUT/gatekeeper-runtime-arm64-$SHA.apk"
DST_SPLITS="$OUT/gatekeeper-splits-$SHA.apks"
cp "$BASE" "$DST_BASE"
cp "$SPLIT" "$DST_RUNTIME"
cp "$WORK/app.apks" "$DST_SPLITS"

echo
echo "==> split delivery (sha=$SHA)"
printf 'base    %8s MB  %s\n' "$(du -m "$DST_BASE"      | cut -f1)" "$(basename "$DST_BASE")"
printf 'runtime %8s MB  %s\n' "$(du -m "$DST_RUNTIME"   | cut -f1)" "$(basename "$DST_RUNTIME")"
printf '.apks  %8s MB  %s\n' "$(du -m "$DST_SPLITS"    | cut -f1)" "$(basename "$DST_SPLITS")"
echo "install fresh:  adb install-multiple $OUT/gatekeeper-base-$SHA.apk $OUT/gatekeeper-runtime-arm64-$SHA.apk"
echo "install (runtime cached):  adb install-multiple -r gatekeeper-base-$SHA.apk gatekeeper-runtime-arm64-$SHA.apk"
echo "splits OK"