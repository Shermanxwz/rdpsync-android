#!/usr/bin/env bash
# Shared, overrideable native toolchain. Source this file from build scripts.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_NDK="${ANDROID_NDK:-$ANDROID_HOME/ndk/28.0.13004108}"
ABI="${ABI:-arm64-v8a}"
API_LEVEL=26
case "$ABI" in
    arm64-v8a) BUILD_ARCH=arm64; OPENSSL_TARGET=android-arm64; CLANG_TARGET=aarch64-linux-android; ELF_MACHINE=AArch64 ;;
    x86_64) BUILD_ARCH=x86_64; OPENSSL_TARGET=android-x86_64; CLANG_TARGET=x86_64-linux-android; ELF_MACHINE='Advanced Micro Devices X86-64' ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
export ANDROID_NDK_ROOT="$ANDROID_NDK"
BUILD_DIR="$PROJECT_DIR/build/freerdp-android"
OPENSSL_OUT="$BUILD_DIR/openssl-$BUILD_ARCH"
BUILD_OUT="$BUILD_DIR/build-$BUILD_ARCH"
JNILIBS="$PROJECT_DIR/app/src/main/jniLibs/$ABI"
JOBS="${JOBS:-4}"
mkdir -p "$BUILD_DIR" "$JNILIBS"
[[ -x "$TOOLCHAIN/bin/${CLANG_TARGET}${API_LEVEL}-clang" ]] || { echo "Missing NDK: $ANDROID_NDK" >&2; exit 1; }
