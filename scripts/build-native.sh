#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/native-env.sh"
if [[ "${SKIP_OPENSSL:-0}" != 1 ]]; then bash "$PROJECT_DIR/scripts/build-openssl-android.sh"; fi
bash "$PROJECT_DIR/scripts/build-freerdp-android.sh"
# Guard a shared engineering workspace against mixed-revision native objects.
# This is build provenance, not a new runtime trust or signing mechanism.
native_source_manifest() {
    python3 - "$PROJECT_DIR" <<'PYSOURCES'
import hashlib, json, sys
from pathlib import Path
root = Path(sys.argv[1])
paths = sorted(path for path in (root / "app/src/main/cpp").iterdir() if path.suffix in (".c", ".h", ".inc"))
print(json.dumps({str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}, sort_keys=True))
PYSOURCES
}
native_source_manifest > "$BUILD_OUT/rdpsync-bridge-source-before.json"
"$TOOLCHAIN/bin/${CLANG_TARGET}${API_LEVEL}-clang" -shared -o "$JNILIBS/librdpsync.so" -O2 -g -fPIC -Wall -Wextra \
    -Wno-unused-parameter -Werror=implicit-function-declaration -Werror=incompatible-function-pointer-types \
    -I"$BUILD_DIR/freerdp-src/include" -I"$BUILD_DIR/freerdp-src/winpr/include" \
    -I"$BUILD_OUT/include" -I"$BUILD_OUT/winpr/include" -I"$OPENSSL_OUT/include" \
    -L"$JNILIBS" -L"$OPENSSL_OUT/lib" "$PROJECT_DIR/app/src/main/cpp/freerdp_bridge.c" "$PROJECT_DIR/app/src/main/cpp/rdpsync_transfer.c" \
    "$PROJECT_DIR/app/src/main/cpp/rdpsync_drive.c" "$PROJECT_DIR/app/src/main/cpp/rdpsync_workspace.c" \
    -lfreerdp-client3 -lfreerdp3 -lwinpr3 -lssl -lcrypto -ljnigraphics -llog -landroid -lz \
    -Wl,-rpath-link="$JNILIBS" -Wl,-z,max-page-size=16384 -Wl,--no-undefined
# Resolve libc++ only when a dependency needs it. Never rely on device-global C++ runtime.
if "$TOOLCHAIN/bin/llvm-readelf" -d "$JNILIBS/"*.so | grep -q 'libc++_shared.so'; then
    cp "$TOOLCHAIN/sysroot/usr/lib/$CLANG_TARGET/libc++_shared.so" "$JNILIBS/"
fi

native_source_manifest > "$BUILD_OUT/rdpsync-bridge-source-after.json"
if ! cmp -s "$BUILD_OUT/rdpsync-bridge-source-before.json" "$BUILD_OUT/rdpsync-bridge-source-after.json"; then
    echo "Native sources changed during compilation; rebuild this ABI before packaging" >&2
    exit 1
fi
python3 - "$PROJECT_DIR" "$BUILD_DIR" "$BUILD_OUT" "$JNILIBS" "$ABI" <<'PYBRIDGE'
import hashlib, json, sys
from pathlib import Path
project, build, output, libraries = map(Path, sys.argv[1:5])
record_path = build / ("rdpsync-native-provenance-" + sys.argv[5] + ".json")
record = json.loads(record_path.read_text())
record["bridge_sources_sha256"] = json.loads((output / "rdpsync-bridge-source-after.json").read_text())
record["packaged_shared_libraries_sha256"] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(libraries.glob("*.so"))}
record_path.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n")
PYBRIDGE
