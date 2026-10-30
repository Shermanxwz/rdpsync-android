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
"$HOST_CC" "${FLAGS[@]}" -Wall -Wextra -Werror "$ROOT/app/src/test/cpp/gfx_policy_test.c" -o "$OUT/gfx_policy_test"
"$OUT/gfx_policy_test"
"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/transfer_test.c" -o "$OUT/transfer_test"
"$OUT/transfer_test"

"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/transfer_stall_test.c" -o "$OUT/transfer_stall_test"
"$OUT/transfer_stall_test"
[[ -f "$GENERATED/include/freerdp/settings_keys.h" ]] || {
    echo 'Configure/build native dependencies first (scripts/build-native.sh).' >&2; exit 1;
}
[[ -f "$JAVA_HOME/include/jni.h" ]] || { echo 'JAVA_HOME must point to a full JDK.' >&2; exit 1; }
"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/bridge_frame_test.c" "$ROOT/app/src/main/cpp/rdpsync_transfer.c" -o "$OUT/bridge_frame_test"
"$OUT/bridge_frame_test"

# Exercise production GFX guards/rollback with deterministic failure delegates.
"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/gfx_bridge_test.c" "$ROOT/app/src/main/cpp/rdpsync_transfer.c" -o "$OUT/gfx_bridge_test"

"$OUT/gfx_bridge_test"

"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/transfer_ingress_test.c" "$ROOT/app/src/main/cpp/rdpsync_transfer.c" -o "$OUT/transfer_ingress_test"
"$OUT/transfer_ingress_test"

# The certificate fixture generates ephemeral self-signed test certificates in
# memory and links the host OpenSSL development library (no saved private keys).
"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/certificate_test.c" "$ROOT/app/src/main/cpp/rdpsync_transfer.c" -o "$OUT/certificate_test" -lcrypto
"$OUT/certificate_test"

# Execute the actual JNI private HOME/XDG bootstrap without creating a network
# context. Android instrumentation exercises the resulting FreeRDP allocation.
"$HOST_CC" "${FLAGS[@]}" -pthread -Wno-deprecated-declarations \
    -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
    -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
    "$ROOT/app/src/test/cpp/runtime_test.c" "$ROOT/app/src/main/cpp/rdpsync_transfer.c" -o "$OUT/runtime_test"
"$OUT/runtime_test"

# Clang -O2 once removed the session-tail store when the factory returned only
# ctx->instance: upstream marks its initialized cyclic-context allocator malloc.
# Keep allocator and caller separate (no LTO), and test the production factory.
# Prefer the same Clang toolchain that produced the Android libraries. Its plain
# clang driver targets the host, unlike the Android-target wrapper executable.
NATIVE_AR="$(sed -n 's/^CMAKE_C_COMPILER_AR:FILEPATH=//p' "$GENERATED/CMakeCache.txt" | head -1)"
DEFAULT_CLANG="$(dirname "$NATIVE_AR")/clang"
[[ -x "$DEFAULT_CLANG" ]] || DEFAULT_CLANG=clang
HOST_CLANG="${HOST_CLANG:-$DEFAULT_CLANG}"
command -v "$HOST_CLANG" >/dev/null || { echo 'Clang is required for the optimized context-binding regression.' >&2; exit 1; }
BINDING_FLAGS=(-std=c11 -D_GNU_SOURCE -O2 -g -Wno-deprecated-declarations
    -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include"
    -I"$GENERATED/include" -I"$GENERATED/winpr/include")
"$HOST_CLANG" "${BINDING_FLAGS[@]}" -c "$ROOT/app/src/test/cpp/context_binding_allocator.c" -o "$OUT/context_binding_allocator.o"
"$HOST_CLANG" "${BINDING_FLAGS[@]}" -c "$ROOT/app/src/test/cpp/context_binding_test.c" -o "$OUT/context_binding_test.o"
"$HOST_CLANG" "$OUT/context_binding_test.o" "$OUT/context_binding_allocator.o" -o "$OUT/context_binding_test"
"$OUT/context_binding_test"
