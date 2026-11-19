#!/usr/bin/env bash
set -euo pipefail

# Copies the few files rust-lld needs to link an `aarch64-linux-android`
# cdylib out of an Android NDK, so the Rust extension can link without the NDK.
# usage: stage-android-sysroot.sh <ndk-directory> <output-directory> [api-level]
NDK=${1:?usage: stage-android-sysroot.sh <ndk-directory> <output-directory> [api-level]}
OUTPUT=${2:?usage: stage-android-sysroot.sh <ndk-directory> <output-directory> [api-level]}
API=${3:-26}

LLVM=$(find "$NDK/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)
test -d "$LLVM" || { echo "NDK LLVM toolchain not found in $NDK" >&2; exit 1; }
SYSROOT_LIB="$LLVM/sysroot/usr/lib/aarch64-linux-android"
test -d "$SYSROOT_LIB/$API" || { echo "NDK has no API $API libraries in $SYSROOT_LIB" >&2; exit 1; }

find_one() {
  local found
  found=$(find "$LLVM/lib" -path "$1" -type f | sort | head -n 1)
  test -n "$found" || { echo "NDK file not found: $1" >&2; exit 1; }
  printf '%s\n' "$found"
}

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"
for file in libc.so libm.so libdl.so liblog.so libandroid.so crtbegin_so.o crtend_so.o; do
  test -f "$SYSROOT_LIB/$API/$file" || { echo "missing $SYSROOT_LIB/$API/$file" >&2; exit 1; }
  cp "$SYSROOT_LIB/$API/$file" "$OUTPUT/$file"
done
cp "$(find_one '*/libclang_rt.builtins-aarch64-android.a')" "$OUTPUT/libclang_rt.builtins-aarch64-android.a"
# Rust's std links the unwinder statically on Android.
if [[ -f "$SYSROOT_LIB/libunwind.a" ]]; then
  cp "$SYSROOT_LIB/libunwind.a" "$OUTPUT/libunwind.a"
else
  cp "$(find_one '*/aarch64/libunwind.a')" "$OUTPUT/libunwind.a"
fi
echo "Staged Android link files for API $API in $OUTPUT"
ls -l "$OUTPUT"
