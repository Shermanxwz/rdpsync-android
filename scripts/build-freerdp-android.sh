#!/bin/bash
set -euo pipefail

# Build FreeRDP 3.x for Android arm64-v8a
# Run from repository root

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
NDK_DIR="/opt/android-sdk/ndk/28.0.13004108"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
API_LEVEL=26
ABI="arm64-v8a"

# Create build directory
BUILD_DIR="$PROJECT_DIR/build/freerdp-android"
mkdir -p "$BUILD_DIR"

# Download FreeRDP 3.9.0
FREERDP_DIR="$BUILD_DIR/freerdp-src"
if [ ! -d "$FREERDP_DIR" ]; then
    echo "Downloading FreeRDP 3.9.0..."
    cd "$BUILD_DIR"
    git clone --depth 1 --branch 3.9.0 https://github.com/FreeRDP/FreeRDP.git freerdp-src 2>&1
fi

# Build FreeRDP
BUILD_OUT="$BUILD_DIR/build-$ABI"
mkdir -p "$BUILD_OUT"
cd "$BUILD_OUT"

echo "Configuring FreeRDP 3.9.0 for $ABI..."
cmake "$FREERDP_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DWITH_SERVER=OFF \
    -DWITH_CLIENT_SDL=OFF \
    -DWITH_CLIENT_X11=OFF \
    -DWITH_CLIENT_WAYLAND=OFF \
    -DWITH_CUPS=OFF \
    -DWITH_PULSE=OFF \
    -DWITH_ALSA=OFF \
    -DWITH_FFMPEG=OFF \
    -DWITH_OPENH264=OFF \
    -DWITH_WEBVIEW=OFF \
    -DWITH_GSM=OFF \
    -DWITH_KRB5=OFF \
    -DWITH_PCSC=OFF \
    -DWITH_SAMPLE=OFF \
    -DWITH_MANPAGES=OFF \
    -DWITH_SWSCALE=OFF \
    -DWITH_CCACHE=OFF \
    -DWITH_DIRECTFB=OFF \
    -DWITH_OPENSSL=ON \
    -DWITH_ZLIB=ON \
    -DWITH_IPC=OFF \
    -DWITH_RDTK=OFF \
    -DWITH_DSM=OFF \
    -DWITH_PROXY=OFF \
    -DWITH_CLIENT_INTERFACE=OFF \
    -DWITH_DSP_FFMPEG=OFF \
    2>&1 | tail -30

echo "Building FreeRDP..."
cmake --build . -j$(nproc) 2>&1 | tail -20

# Copy output .so files to jniLibs
JNILIBS="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS"

echo "Copying FreeRDP .so files..."
find "$BUILD_OUT" -name "*.so" -type f | while read f; do
    basename "$f"
    cp "$f" "$JNILIBS/"
done

# Also copy the JNI bridge if built separately
if [ -f "$BUILD_OUT/libfreerdp-client/libfreerdp-client3.so" ]; then
    cp "$BUILD_OUT/libfreerdp-client/libfreerdp-client3.so" "$JNILIBS/"
fi

echo ""
echo "=== FreeRDP build complete ==="
echo "Libs in $JNILIBS:"
ls -lh "$JNILIBS/"*.so 2>/dev/null
echo ""
echo "To check FreeRDP version:"
strings "$JNILIBS/libfreerdp3.so" 2>/dev/null | grep -i "FreeRDP version" | head -3 || echo "version check via strings not available"
