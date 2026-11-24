#!/usr/bin/env bash
# Runs on a booted emulator or device (adb): first the core's probe (build/core/<abi>/, from
# scripts/build-core.sh) on a photo, twice, and on the iOS app's `.moonlight` fixtures (the
# androidTest assets' copies), then the app's tour (AppTour, then ExchangeTest), and pulls its
# screenshots and log into the output directory. The two runs must make the same bytes, and the
# painting must be the host's (settings, areas, paints, sizes, valid). Its bytes may differ from
# the host's: Bionic's libm rounds some functions differently from glibc's (as Darwin's does),
# so a last bit moves a point, which is why the hash is reported, not required.
#
#   scripts/emulator-tour.sh <photo.ppm> <out-dir> [host-probe.txt]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PHOTO="$1"; OUT="$2"; HOST="${3:-}"
PKG=com.pbordjadze.paintbymoonlight
mkdir -p "$OUT"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "device ABI: $abi"

# 1. The core alone.
adb shell rm -rf /data/local/tmp/core
adb push "$ROOT/build/core/$abi" /data/local/tmp/core >/dev/null
adb push "$PHOTO" /data/local/tmp/core/photo.ppm >/dev/null
probe() { adb shell "cd /data/local/tmp/core && chmod +x moonlight-probe && LD_LIBRARY_PATH=. ./moonlight-probe photo.ppm relaxed" | tr -d '\r'; }
probe | tee "$OUT/probe.txt"
again="$(probe | sed -n 's/^hash //p')"
hash="$(sed -n 's/^hash //p' "$OUT/probe.txt")"
if [[ "$hash" != "$again" ]]; then echo "::error::the core made different bytes twice ($hash, $again)"; exit 1; fi
if [[ -n "$HOST" ]]; then
  painting() { grep -E '^(photo|settings|template|scene|valid) ' "$1"; }
  if ! diff <(painting "$HOST") <(painting "$OUT/probe.txt"); then echo "::error::the painting differs from the host's"; exit 1; fi
  echo "same painting as the host; bytes: $hash here, $(sed -n 's/^hash //p' "$HOST") on the host"
fi

# The iOS app's paintings, read and sent again by the core here.
for f in "$ROOT"/app/src/androidTest/assets/*.moonlight; do adb push "$f" /data/local/tmp/core/ >/dev/null; done
adb shell "cd /data/local/tmp/core && LD_LIBRARY_PATH=. ./moonlight-probe --shared *.moonlight" | tr -d '\r' | tee "$OUT/probe-shared.txt"
if grep -q -v -e '^  ' -e '\.moonlight$' "$OUT/probe-shared.txt" || [[ "$(grep -c 'read back whole' "$OUT/probe-shared.txt")" != 2 ]]; then
  echo "::error::the core here didn't read the iOS app's paintings"; exit 1
fi

# 2. The app.
"$ROOT/gradlew" -p "$ROOT" --console=plain installDebug installDebugAndroidTest
adb logcat -c
set +e
adb shell am instrument -w -e class com.paintbymoonlight.AppTour,com.paintbymoonlight.ExchangeTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"
set -e
adb logcat -d > "$OUT/logcat.txt"
grep -E "AppTour|MoonlightCore|AndroidRuntime" "$OUT/logcat.txt" | tail -50 || true
adb exec-out run-as "$PKG" tar c files/shots | tar x -C "$OUT" || true
ls -la "$OUT/files/shots" 2>/dev/null || true
grep -q "^OK (" "$OUT/instrument.txt"
