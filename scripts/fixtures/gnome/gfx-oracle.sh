#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
# Only this disposable reference binary is reconfigured; the existing classic
# comparison script and its separately hashed negative evidence are unchanged.
SOURCE="$ROOT/build/reference-freerdp-src"
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == 97eff6ddfbbfaa8e42a126d362686bd001ff8bdc ]]
[[ -z "$(git -C "$SOURCE" status --porcelain --untracked-files=no)" ]]
cmake -S "$SOURCE" -B build/reference-freerdp \
  -DCHANNEL_RDPGFX=ON -DCHANNEL_RDPGFX_CLIENT=ON -DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF
cmake --build build/reference-freerdp --target xfreerdp -j4
mkdir -p build/gnome-evidence/gfx-reference
CLIENT=$(cat build/reference-freerdp/client-path.txt)
"$CLIENT" /buildconfig >build/gnome-evidence/gfx-reference/buildconfig.txt 2>&1
cp build/reference-freerdp/source-revision.txt build/gnome-evidence/gfx-reference/
ldd "$CLIENT" >build/gnome-evidence/gfx-reference/linked-libraries.txt
{ sha256sum "$CLIENT"; ldd "$CLIENT" | awk '$3 ~ /^\// {print $3}' | sort -u | xargs sha256sum; } >build/gnome-evidence/gfx-reference/linked-libraries-sha256.txt
export DISPLAY=:96 HOME="$ROOT/build/gnome-private/gfx-reference-home"
mkdir -p "$HOME"
Xvfb "$DISPLAY" -screen 0 1600x1200x24 -nolisten tcp >build/gnome-evidence/gfx-reference/xvfb.log 2>&1 &
XPID=$!
trap 'kill "$XPID" 2>/dev/null || true; wait "$XPID" 2>/dev/null || true' EXIT
for i in $(seq 1 50); do xdpyinfo >/dev/null 2>&1 && break; sleep .1; done
xdpyinfo >/dev/null
python3 scripts/fixtures/gnome/test_presentation.py
python3 scripts/fixtures/gnome/test_calibrate_pixels.py
python3 scripts/fixtures/gnome/gfx-oracle.py
python3 scripts/fixtures/gnome/calibrate_pixels.py build/gnome-evidence/gfx-reference
