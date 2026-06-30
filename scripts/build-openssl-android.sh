#!/bin/bash
set -euo pipefail

# Build OpenSSL 3.x for Android arm64-v8a
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
BUILD_DIR="$PROJECT_DIR/build/freerdp-android"

NDK_DIR="/opt/android-sdk/ndk/28.0.13004108"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
API=26

OPENSSL_VERSION="3.4.1"
OPENSSL_DIR="$BUILD_DIR/openssl-src"
OPENSSL_OUT="$BUILD_DIR/openssl-$API-arm64"

if [ ! -d "$OPENSSL_DIR" ]; then
    echo "Downloading OpenSSL $OPENSSL_VERSION..."
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"
    curl -L "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" | tar xz
    mv "openssl-$OPENSSL_VERSION" "openssl-src"
fi

echo "Configuring OpenSSL for Android arm64-v8a (API $API)..."
cd "$OPENSSL_DIR"

export PATH="$TOOLCHAIN/bin:$PATH"
export CC="aarch64-linux-android${API}-clang"
export CXX="aarch64-linux-android${API}-clang++"
export AR="llvm-ar"
export RANLIB="llvm-ranlib"
export LD="ld.lld"
export CFLAGS="-D__ANDROID_API__=$API -Os -fPIC"
export LDFLAGS="-Wl,-rpath-link=$SYSROOT/usr/lib/aarch64-linux-android/$API"

./Configure android-arm64 \
    -D__ANDROID_API__=$API \
    --prefix="$OPENSSL_OUT" \
    no-shared \
    no-asm \
    no-unit-test \
    no-tests \
    no-dso \
    2>&1 | tail -10

echo "Building OpenSSL..."
make -j$(nproc) 2>&1 | tail -10
make install_sw 2>&1 | tail -5

echo ""
echo "=== OpenSSL build complete ==="
echo "Output: $OPENSSL_OUT"
ls -la "$OPENSSL_OUT/lib/" 2>/dev/null
