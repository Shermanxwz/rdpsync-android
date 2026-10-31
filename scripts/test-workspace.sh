#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${RDPSYNC_WORKSPACE_TEST_OUT:-$ROOT/build/workspace-tests}"
mkdir -p "$OUT"
"${HOST_CC:-cc}" -std=c11 -Wall -Wextra -Werror -pthread -g -O1 \
  -DRDPSYNC_WS_TEST_FAULTS -fsanitize="${SANITIZERS:-address,undefined}" -fno-omit-frame-pointer \
  "$ROOT/app/src/test/cpp/workspace_test.c" "$ROOT/app/src/main/cpp/rdpsync_workspace.c" \
  -o "$OUT/workspace_test"
"$OUT/workspace_test"
