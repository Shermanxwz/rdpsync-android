#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
# Only this disposable reference binary is reconfigured; the existing classic
# comparison script and its separately hashed negative evidence are unchanged.
cmake -S build/freerdp-android/freerdp-src -B build/reference-freerdp \
  -DCHANNEL_RDPGFX=ON -DCHANNEL_RDPGFX_CLIENT=ON -DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF
cmake --build build/reference-freerdp --target xfreerdp -j4
mkdir -p build/gnome-evidence/gfx-reference
CLIENT=$(cat build/reference-freerdp/client-path.txt)
"$CLIENT" /buildconfig >build/gnome-evidence/gfx-reference/buildconfig.txt 2>&1
cp build/reference-freerdp/source-revision.txt build/gnome-evidence/gfx-reference/
export DISPLAY=:96 HOME="$ROOT/build/gnome-private/gfx-reference-home"
mkdir -p "$HOME"
Xvfb "$DISPLAY" -screen 0 1600x1200x24 -nolisten tcp >build/gnome-evidence/gfx-reference/xvfb.log 2>&1 &
XPID=$!
trap 'kill "$XPID" 2>/dev/null || true; wait "$XPID" 2>/dev/null || true' EXIT
for i in $(seq 1 50); do xdpyinfo >/dev/null 2>&1 && break; sleep .1; done
xdpyinfo >/dev/null
python3 scripts/fixtures/gnome/gfx-oracle.py
