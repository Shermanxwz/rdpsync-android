#!/usr/bin/env bash
# Unmodified official client, for the disposable resize compatibility comparison.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || { echo 'Only for a disposable GitHub-hosted Actions VM.' >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE="$ROOT/build/freerdp-android/freerdp-src"
OUT="$ROOT/build/reference-freerdp"
REV=97eff6ddfbbfaa8e42a126d362686bd001ff8bdc
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  build-essential cmake ninja-build pkg-config git libssl-dev zlib1g-dev \
  libx11-dev libxext-dev libxinerama-dev libxcursor-dev libxrandr-dev \
  libxrender-dev libxfixes-dev libxi-dev libxkbfile-dev libxv-dev \
  xvfb xdotool x11-utils imagemagick python3-pil
if [[ ! -d "$SOURCE/.git" ]]; then
  mkdir -p "$(dirname "$SOURCE")"
  git clone --depth 1 --branch 3.32.2 https://github.com/FreeRDP/FreeRDP.git "$SOURCE"
fi
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$REV" ]]
[[ -z "$(git -C "$SOURCE" status --porcelain --untracked-files=no)" ]]
mkdir -p "$OUT"
# CHANNEL_RDPGFX=OFF is deliberate: this comparison must exercise the same
# classic GDI Deactivate/Reactivate path, not a newer GFX-only resize shortcut.
# Official X11 keyboard code unconditionally references the ENCOMSP context;
# build that dependency, but do not enable its channel in comparison sessions.
cmake -S "$SOURCE" -B "$OUT" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DBUILD_SHARED_LIBS=ON \
  -DWITH_CLIENT=ON -DWITH_X11=ON -DWITH_CLIENT_SDL=OFF -DWITH_WAYLAND=OFF \
  -DWITH_SERVER=OFF -DWITH_SAMPLE=OFF -DWITH_SHADOW=OFF -DWITH_PROXY=OFF \
  -DWITH_MANPAGES=OFF -DWITH_DOCS=OFF -DWITH_CCACHE=OFF -DWITH_WEBVIEW=OFF \
  -DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF -DWITH_SWSCALE=OFF -DWITH_DSP_FFMPEG=OFF \
  -DWITH_ALSA=OFF -DWITH_PULSE=OFF -DWITH_OSS=OFF -DWITH_SNDIO=OFF \
  -DWITH_CUPS=OFF -DWITH_PCSC=OFF -DWITH_KRB5=OFF -DWITH_FUSE=OFF \
  -DWITH_GSM=OFF -DWITH_INTERNAL_MD4=ON -DWITH_INTERNAL_RC4=ON \
  -DCHANNEL_DISP=ON -DCHANNEL_DISP_CLIENT=ON -DCHANNEL_DRDYNVC=ON \
  -DCHANNEL_RDPEI=ON -DCHANNEL_RDPDR=ON -DCHANNEL_RDPSND=ON \
  -DWITH_SERVER_CHANNELS=OFF -DCHANNEL_URBDRC=OFF \
  -DCHANNEL_RDPGFX=OFF -DCHANNEL_AUDIN=OFF -DCHANNEL_RDPECAM=OFF \
  -DCHANNEL_CLIPRDR=OFF -DCHANNEL_DRIVE=OFF -DCHANNEL_PRINTER=OFF \
  -DCHANNEL_SMARTCARD=OFF -DCHANNEL_SERIAL=OFF -DCHANNEL_PARALLEL=OFF \
  -DCHANNEL_AINPUT=OFF -DCHANNEL_ECHO=OFF -DCHANNEL_ENCOMSP=ON \
  -DCHANNEL_GEOMETRY=OFF -DCHANNEL_LOCATION=OFF -DCHANNEL_RAIL=OFF \
  -DCHANNEL_RDPEMSC=OFF -DCHANNEL_REMDESK=OFF -DCHANNEL_TELEMETRY=OFF \
  -DCHANNEL_VIDEO=OFF
cmake --build "$OUT" --target xfreerdp -j"${JOBS:-4}"
CLIENT="$(find "$OUT/client/X11" -type f -name 'xfreerdp*' -executable | head -1)"
[[ -n "$CLIENT" ]]
printf '%s\n' "$CLIENT" >"$OUT/client-path.txt"
"$CLIENT" /version >"$OUT/version.txt" 2>&1
"$CLIENT" /buildconfig >"$OUT/buildconfig.txt" 2>&1
openssl version -a >"$OUT/openssl-version.txt"
ldd "$CLIENT" >"$OUT/linked-libraries.txt"
printf '%s\n' "$REV" >"$OUT/source-revision.txt"
sha256sum "$CLIENT" >"$OUT/client.sha256"
