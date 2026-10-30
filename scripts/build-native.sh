#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/native-env.sh"
if [[ "${SKIP_OPENSSL:-0}" != 1 ]]; then bash "$PROJECT_DIR/scripts/build-openssl-android.sh"; fi
bash "$PROJECT_DIR/scripts/build-freerdp-android.sh"
"$TOOLCHAIN/bin/${CLANG_TARGET}${API_LEVEL}-clang" -shared -o "$JNILIBS/librdpsync.so" -O2 -g -fPIC -Wall -Wextra \
    -Wno-unused-parameter -Werror=implicit-function-declaration -Werror=incompatible-function-pointer-types \
    -I"$BUILD_DIR/freerdp-src/include" -I"$BUILD_DIR/freerdp-src/winpr/include" \
    -I"$BUILD_OUT/include" -I"$BUILD_OUT/winpr/include" -I"$OPENSSL_OUT/include" \
    -L"$JNILIBS" -L"$OPENSSL_OUT/lib" "$PROJECT_DIR/app/src/main/cpp/freerdp_bridge.c" "$PROJECT_DIR/app/src/main/cpp/rdpsync_transfer.c" \
    -lfreerdp-client3 -lfreerdp3 -lwinpr3 -lssl -lcrypto -ljnigraphics -llog -landroid -lz \
    -Wl,-rpath-link="$JNILIBS" -Wl,-z,max-page-size=16384 -Wl,--no-undefined
# Resolve libc++ only when a dependency needs it. Never rely on device-global C++ runtime.
if "$TOOLCHAIN/bin/llvm-readelf" -d "$JNILIBS/"*.so | grep -q 'libc++_shared.so'; then
    cp "$TOOLCHAIN/sysroot/usr/lib/$CLANG_TARGET/libc++_shared.so" "$JNILIBS/"
fi
