#!/bin/bash
set -euo pipefail

# RdpSync - One-click APK build
# Usage: bash scripts/build-apk.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
JNILIBS="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
BDIR="$PROJECT_DIR/build/freerdp-android"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_NDK="${ANDROID_NDK:-/opt/android-sdk/ndk/28.0.13004108}"

echo "=== 1. Compile librdpsync.so ==="
export CC="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
rm -f "$JNILIBS/librdpsync.so"
$CC -shared -o "$JNILIBS/librdpsync.so" -O2 -fPIC \
    -I"$BDIR/freerdp-src/include" \
    -I"$BDIR/freerdp-src/winpr/include" \
    -I"$BDIR/build-arm64/include" \
    -I"$BDIR/build-arm64/winpr/include" \
    -I"$BDIR/openssl-arm64/include" \
    -L"$JNILIBS" -L"$BDIR/openssl-arm64/lib" \
    "$PROJECT_DIR/app/src/main/cpp/freerdp_bridge.c" \
    -lfreerdp-client3 -lfreerdp3 -lwinpr3 -lssl -lcrypto -ljnigraphics -llog -landroid \
    -Wl,-rpath-link="$JNILIBS"
echo "  librdpsync.so compiled: $(ls -lh $JNILIBS/librdpsync.so | awk '{print $5}')"

echo ""
echo "=== 2. Build APK ==="
cd "$PROJECT_DIR"
./gradlew clean :app:assembleDebug --no-daemon
echo "  APK built"

echo ""
echo "=== 3. Verify ==="
echo "  JNI symbols:"
nm -D "$JNILIBS/librdpsync.so" | grep -E 'nativeConnect|nativeDisconnect|nativeGetStatus|nativeGetDiag'
echo ""
echo "  APK native libs:"
jar tf app/build/outputs/apk/debug/app-debug.apk | grep 'lib/arm64-v8a/.*\.so'
echo ""
echo "  APK SHA256:"
sha256sum app/build/outputs/apk/debug/app-debug.apk

echo ""
echo "=== 4. Copy release ==="
mkdir -p releases
cp app/build/outputs/apk/debug/app-debug.apk releases/rdpsync-product.apk
echo "  releases/rdpsync-product.apk ready"
echo ""
echo "=== DONE ==="
