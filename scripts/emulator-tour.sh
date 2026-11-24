#!/usr/bin/env bash
# Runs on a booted emulator or device (adb): first the core's probe (build/core/<abi>/, from
# scripts/build-core.sh) on a photo, printing the template's hash beside the host's, then the
# app's tour (AppTour), and pulls its screenshots and log into the output directory.
#
#   scripts/emulator-tour.sh <photo.ppm> <out-dir> [expected-hash]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PHOTO="$1"; OUT="$2"; EXPECTED="${3:-}"
PKG=com.pbordjadze.paintbymoonlight
mkdir -p "$OUT"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "device ABI: $abi"

# 1. The core alone.
adb shell rm -rf /data/local/tmp/core
adb push "$ROOT/build/core/$abi" /data/local/tmp/core >/dev/null
adb push "$PHOTO" /data/local/tmp/core/photo.ppm >/dev/null
adb shell "cd /data/local/tmp/core && chmod +x moonlight-probe && LD_LIBRARY_PATH=. ./moonlight-probe photo.ppm relaxed" | tee "$OUT/probe.txt"
hash="$(sed -n 's/^hash //p' "$OUT/probe.txt" | tr -d '\r')"
if [[ -n "$EXPECTED" ]]; then
  if [[ "$hash" == "$EXPECTED" ]]; then echo "same template as the host: $hash"; else echo "::error::template differs from the host's ($hash vs $EXPECTED)"; exit 1; fi
fi

# 2. The app.
"$ROOT/gradlew" -p "$ROOT" --console=plain installDebug installDebugAndroidTest
adb logcat -c
set +e
adb shell am instrument -w -e class com.paintbymoonlight.AppTour "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"
set -e
adb logcat -d > "$OUT/logcat.txt"
grep -E "AppTour|MoonlightCore|AndroidRuntime" "$OUT/logcat.txt" | tail -50 || true
adb exec-out run-as "$PKG" tar c files/shots | tar x -C "$OUT" || true
ls -la "$OUT/files/shots" 2>/dev/null || true
grep -q "^OK (" "$OUT/instrument.txt"
