#!/usr/bin/env bash
# Builds the native core (core/: PaintCore + the JNI bridge) for Android with Swift's Android
# SDK and lays each ABI's libraries into app/src/main/jniLibs/<abi>/: libMoonlightCore.so, with
# the Swift runtime and FoundationEssentials linked in statically (one library to load, and the
# linker keeps only what the core reaches: about 5 MB less per ABI than the runtime's own .so
# files, docs/release.md › Size), and the NDK's libc++_shared.so. Any runtime library still
# NEEDED is followed and copied too.
#
#   scripts/build-core.sh [debug|release] [abi …]      (ABIs: arm64-v8a x86_64; default both)
#
# Needs: a Swift toolchain with the matching Android SDK installed (`swift sdk list` shows
# swift-<version>-RELEASE_android) and ANDROID_NDK_HOME (NDK r27 or newer) set up for it with
# the SDK's scripts/setup-android-sdk.sh. MOONLIGHT_PAINTCORE_PATH builds against a local
# paint-by-number checkout (core/Package.swift). Also builds moonlight-probe, left in
# build/core/<abi>/ beside a copy of the libraries, for scripts/probe-emulator.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${1:-release}"; shift || true
ABIS=("${@:-arm64-v8a x86_64}"); read -r -a ABIS <<<"${ABIS[*]}"
API=28
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"

SDK_ID="$(swift sdk list | grep -m1 _android || true)"
[[ -n "$SDK_ID" ]] || { echo "no Swift Android SDK installed (swift sdk install …)" >&2; exit 1; }
SDK_ROOT="$(swift sdk configure --show-configuration "$SDK_ID" x86_64-unknown-linux-android$API 2>/dev/null \
  | sed -n 's/^sdkRootPath: //p' | head -1)"
[[ -n "$SDK_ROOT" ]] || SDK_ROOT="$HOME/.swiftpm/swift-sdks/$SDK_ID.artifactbundle/swift-android/ndk-sysroot"
RESOURCES="$(dirname "$SDK_ROOT")/swift-resources/usr/lib"

for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a) arch=aarch64; ndk_triple=aarch64-linux-android ;;
    x86_64) arch=x86_64; ndk_triple=x86_64-linux-android ;;
    *) echo "unknown ABI $abi" >&2; exit 1 ;;
  esac
  scratch="$ROOT/build/swift/$abi"
  (cd "$ROOT/core" && swift build -c "$CONFIG" --swift-sdk "$arch-unknown-linux-android$API" --scratch-path "$scratch" --static-swift-stdlib)
  products="$scratch/$CONFIG"
  out="$ROOT/app/src/main/jniLibs/$abi"
  probe="$ROOT/build/core/$abi"
  rm -rf "$out" "$probe"; mkdir -p "$out" "$probe"
  cp "$products/libMoonlightCore.so" "$out/"

  # The runtime libraries, followed through NEEDED from the core library and the probe.
  runtime="$RESOURCES/swift-$arch/android"
  queue=("$products/libMoonlightCore.so" "$products/moonlight-probe")
  declare -A seen=()
  while ((${#queue[@]})); do
    file="${queue[0]}"; queue=("${queue[@]:1}")
    for lib in $(readelf -d "$file" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'); do
      [[ -n "${seen[$lib]:-}" ]] && continue
      seen[$lib]=1
      if [[ -f "$runtime/$lib" ]]; then cp "$runtime/$lib" "$out/"; queue+=("$runtime/$lib"); fi
    done
  done
  unset seen
  cp "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$ndk_triple/libc++_shared.so" "$out/"
  if [[ "$CONFIG" == release ]]; then
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$out"/*.so
  fi
  cp "$out"/*.so "$probe/"
  cp "$products/moonlight-probe" "$probe/"
  echo "$abi: $(du -sh "$out" | cut -f1) in $out"
done
