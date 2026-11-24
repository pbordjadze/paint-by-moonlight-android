#!/usr/bin/env bash
# Runs on a booted emulator or device (adb), with the core built (scripts/build-core.sh): makes the
# app's baseline and startup profiles from the journey in baselineprofile/ (launch, the gallery,
# a painting opened, panned, zoomed and painted), then the macrobenchmarks (Benchmarks: cold start
# and the painting's frames, without and with the profile), and leaves in the output directory the
# profiles, the benchmarks' JSON and summary.md (scripts/benchmark-summary.py).
#
#   scripts/profile.sh <out-dir>
#
# The profiles land in app/src/main/generated/baselineProfiles/ too; commit them from there (or
# from a CI run's artifact) to ship them.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$1"
mkdir -p "$OUT"
gradle() { "$ROOT/gradlew" -p "$ROOT" --console=plain "$@"; }

gradle :app:generateBaselineProfile
cp "$ROOT"/app/src/main/generated/baselineProfiles/*.txt "$OUT/"

set +e
gradle :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.paintbymoonlight.baselineprofile.Benchmarks
status=$?
set -e
find "$ROOT/baselineprofile/build/outputs" -name '*benchmarkData.json' -exec cp {} "$OUT/" \;
python3 "$ROOT/scripts/benchmark-summary.py" "$OUT"/*benchmarkData.json | tee "$OUT/summary.md"
exit $status
