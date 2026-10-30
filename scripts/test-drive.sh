#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPS="${RDPSYNC_DEPS_DIR:-$ROOT/build/freerdp-android}"
GENERATED="${RDPSYNC_GENERATED_DIR:-$DEPS/build-arm64}"
OUT="$ROOT/build/drive-tests"
mkdir -p "$OUT"
"${HOST_CC:-cc}" -std=c11 -D_GNU_SOURCE= -g -O1 -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -Wall -Wextra -Wno-deprecated-declarations -Wno-type-limits -Wno-bool-compare -Wno-sign-compare -pthread \
  -fsanitize="${SANITIZERS:-address,undefined}" -fno-omit-frame-pointer \
  -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
  -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
  "$ROOT/app/src/test/cpp/drive_test.c" "$ROOT/app/src/main/cpp/rdpsync_workspace.c" \
  "$DEPS/freerdp-src/winpr/libwinpr/utils/stream.c" -o "$OUT/drive_test"
"$OUT/drive_test"
python3 "$ROOT/scripts/test-freerdp-flow-patch.py"

JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
"${HOST_CC:-cc}" -std=c11 -g -O1 -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -pthread -Wno-deprecated-declarations -fsanitize="${SANITIZERS:-address,undefined}" -fno-omit-frame-pointer \
  -I"$ROOT/app/src/test/cpp/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -I"$DEPS/freerdp-src/include" -I"$DEPS/freerdp-src/winpr/include" \
  -I"$GENERATED/include" -I"$GENERATED/winpr/include" \
  "$ROOT/app/src/test/cpp/sharedspace_jni_test.c" "$ROOT/app/src/main/cpp/rdpsync_workspace.c" "$ROOT/app/src/main/cpp/rdpsync_transfer.c" -o "$OUT/sharedspace_jni_test"
"$OUT/sharedspace_jni_test"

# Compile the exact patched function, not a handwritten duplicate. The object
# shapes are isolated seams; only the separate xrdp relay proves real drain.
python3 - "$ROOT" "$OUT" <<'PYPROBE'
import importlib.util
from pathlib import Path
import sys
root, out = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("probe", root / "scripts/patch-freerdp-flow-control.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
(out / "flow_accessor_test.c").write_bytes((root / "app/src/test/cpp/flow_accessor_test.c").read_bytes() + module.SUFFIX)
PYPROBE
"${HOST_CC:-cc}" -std=c11 -g -O1 -Wall -Wextra -Werror \
  -fsanitize="${SANITIZERS:-address,undefined}" -fno-omit-frame-pointer \
  "$OUT/flow_accessor_test.c" -lssl -lcrypto -o "$OUT/flow_accessor_test"
"$OUT/flow_accessor_test"
