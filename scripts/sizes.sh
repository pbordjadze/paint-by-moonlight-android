#!/usr/bin/env bash
# What the app costs a phone, as Markdown: Play's download size per ABI for the release bundle
# (`./gradlew :app:bundleSize`: bundletool's get-size over the split APKs Play would serve), and
# each ABI's native libraries as installed (stored uncompressed, so also their bytes on disk).
#
#   scripts/sizes.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAB="$ROOT/app/build/outputs/bundle/release/app-release.aab"
sizes="$("$ROOT/gradlew" -p "$ROOT" --console=plain -q :app:bundleSize)"

echo "### app-release.aab: $(( $(stat -c %s "$AAB") / 1024 )) KiB"
echo
echo "Download from Play, in bytes (smallest and largest device of each ABI):"
echo
echo '```'
echo "$sizes" | grep -v -e "^Picked up" -e "^INFO:"
echo '```'
echo
echo "| ABI | native libraries | KiB installed |"
echo "| --- | ---: | ---: |"
unzip -l "$AAB" 'base/lib/*' | awk '$4 ~ /^base\/lib\// { split($4, p, "/"); size[p[3]] += $1; n[p[3]]++ }
  END { for (abi in size) printf "| %s | %d | %d |\n", abi, n[abi], size[abi] / 1024 }' | sort
