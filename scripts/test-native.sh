#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPS="${RDPSYNC_DEPS_DIR:-$ROOT/build/freerdp-android}"
GENERATED="${RDPSYNC_GENERATED_DIR:-$DEPS/build-arm64}"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
HOST_CC="${HOST_CC:-cc}"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
FLAGS=(-std=c11 -g -O1 -ffunction-sections -fdata-sections -Wl,--gc-sections -fsanitize="${SANITIZERS:-address,undefined}" -fno-omit-frame-pointer)
"$HOST_CC" "${FLAGS[@]}" -Wall -Wextra -Werror "$ROOT/app/src/test/cpp/geometry_test.c" -o "$OUT/geometry_test"
"$OUT/geometry_test"
[[ -f "$GENERATED/include/freerdp/settings_keys.h" ]] || {
    echo 'Configure/build native dependencies first (scripts/build-native.sh).' >&2; exit 1;
}
[[ -f "$JAVA_HOME/include/jni.h" ]] || { echo 'JAVA_HOME must point to a full JDK.' >&2; exit 1; }
"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/bridge_frame_test.c" -o "$OUT/bridge_frame_test"
"$OUT/bridge_frame_test"

# The certificate fixture generates ephemeral self-signed test certificates in
# memory and links the host OpenSSL development library (no saved private keys).
"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/certificate_test.c" -o "$OUT/certificate_test" -lcrypto
"$OUT/certificate_test"

# Execute the actual JNI private HOME/XDG bootstrap without creating a network
# context. Android instrumentation exercises the resulting FreeRDP allocation.
"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/runtime_test.c" -o "$OUT/runtime_test"
"$OUT/runtime_test"
