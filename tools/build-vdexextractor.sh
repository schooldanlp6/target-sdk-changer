#!/usr/bin/env bash
# Build tools/vdexExtractor (upstream source, unmodified):
#   tools/bin/<abi>/vdexExtractor  for the phone (arm + arm64, Android 5+), via the NDK
#   tools/bin/host/vdexExtractor   for this PC (reads vdex files copied from a phone)
set -eu
here=$(cd "$(dirname "$0")" && pwd)
src=$here/vdexExtractor
if [ ! -d "$src/src" ]; then
    echo "missing $src; get it with:"
    echo "  git clone --depth 1 https://github.com/anestisb/vdexExtractor.git $src"
    exit 1
fi
ndk=${NDK:-$(ls -d "$HOME"/sdktools/ndk/* 2>/dev/null | sort -V | head -1)}
if [ -n "$ndk" ]; then
    out=$(mktemp -d)
    "$ndk/ndk-build" NDK_PROJECT_PATH="$out" APP_BUILD_SCRIPT="$here/vdexextractor.mk" \
        APP_PLATFORM=android-21 APP_ABI="armeabi-v7a arm64-v8a" -j"$(nproc)" >/dev/null
    for abi in armeabi-v7a arm64-v8a; do
        mkdir -p "$here/bin/$abi"
        cp "$out/libs/$abi/vdexExtractor" "$here/bin/$abi/"
    done
    echo "(NDK intermediate files left in $out)"
else
    echo "warning: no Android NDK found (set NDK=...): phone binaries not built"
fi
mkdir -p "$here/bin/host"
# -Wno-array-parameter: newer compilers warn about a harmless array bound in upstream code
gcc -O2 -std=c11 -D_GNU_SOURCE -Wno-array-parameter -DVERSION=\"dev-78f283b\" \
    "$src"/src/*.c "$src"/src/*/*.c -lz -lm -o "$here/bin/host/vdexExtractor"
echo "built: $(ls "$here"/bin/*/vdexExtractor | tr '\n' ' ')"
