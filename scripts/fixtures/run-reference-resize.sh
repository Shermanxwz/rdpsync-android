#!/usr/bin/env bash
# One controlled case. Caller resets ONLY the disposable rdpfixture session
# between cases. Neither this script nor the client contacts non-loopback hosts.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || { echo 'Only for a disposable GitHub-hosted Actions VM.' >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FLUSH="${1:?TRUE or FALSE flush setting required}"
SYNC="${2:?TRUE or FALSE synchronous DVC setting required}"
OUT="${3:?evidence directory required}"
[[ "$FLUSH" =~ ^(TRUE|FALSE)$ && "$SYNC" =~ ^(TRUE|FALSE)$ ]]
mkdir -p "$OUT"
OUT="$(realpath "$OUT")"
CLIENT="$(cat "$ROOT/build/reference-freerdp/client-path.txt")"
[[ -x "$CLIENT" ]]
FINGERPRINT="$(openssl x509 -in /etc/xrdp/rdpsync-fixture-cert.pem -outform DER | sha256sum | cut -d' ' -f1)"
[[ "$FINGERPRINT" =~ ^[0-9a-f]{64}$ ]]
export DISPLAY=:98
export HOME="$OUT/home"
mkdir -p "$HOME"
client_pid= xvfb_pid=
cleanup() {
  status=$?
  if [[ -n "$client_pid" ]]; then kill "$client_pid" 2>/dev/null || true; wait "$client_pid" 2>/dev/null || true; fi
  if [[ -n "$xvfb_pid" ]]; then kill "$xvfb_pid" 2>/dev/null || true; wait "$xvfb_pid" 2>/dev/null || true; fi
  printf '%s\n' "$status" >"$OUT/exit-code.txt"
}
trap cleanup EXIT
Xvfb "$DISPLAY" -screen 0 1920x1080x24 -nolisten tcp >"$OUT/xvfb.log" 2>&1 &
xvfb_pid=$!
for i in $(seq 1 50); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.1; done
xdpyinfo >"$OUT/xdisplay.txt"
cp "$ROOT/build/reference-freerdp/"{version.txt,source-revision.txt,client.sha256,buildconfig.txt,openssl-version.txt,linked-libraries.txt} "$OUT/"
cat >"$OUT/profile.txt" <<EOF
Official unmodified FreeRDP 3.32.2 X11 client. Classic GDI, 32bpp, one monitor.
WaitForOutputBufferFlush=$FLUSH
SynchronousDynamicChannels=$SYNC
SupportGraphicsPipeline=FALSE; RDPGFX channel not built.
No smart sizing. Actual server desktop and received RGB checked after resize.
All credentials and certificate belong only to the disposable loopback fixture.
EOF
# /gfx is an optional-value argument, not a reliable negative boolean flag in
# this version. Explicit /tune plus the absent GFX addin enforce classic GDI.
TUNE="FreeRDP_WaitForOutputBufferFlush:$FLUSH,FreeRDP_SynchronousDynamicChannels:$SYNC"
TUNE+=",FreeRDP_SupportGraphicsPipeline:FALSE,FreeRDP_GfxH264:FALSE,FreeRDP_SmartSizing:FALSE"
TUNE+=",FreeRDP_SupportMonitorLayoutPdu:FALSE,FreeRDP_NetworkAutoDetect:TRUE,FreeRDP_DeviceRedirection:TRUE"
TUNE+=",FreeRDP_AudioPlayback:FALSE,FreeRDP_AudioCapture:FALSE,FreeRDP_RedirectClipboard:FALSE"
TUNE+=",FreeRDP_AutoReconnectionEnabled:FALSE"
TUNE+=",FreeRDP_EncomspVirtualChannel:FALSE"
"$CLIENT" /v:127.0.0.1:3389 /u:rdpfixture /p:rdpsync-fixture-only \
  /cert:fingerprint:sha256:"$FINGERPRINT" /sec:tls /size:1024x768 /bpp:32 \
  /gdi:sw /dynamic-resolution /t:rdpsync-reference /log-level:DEBUG \
  "/tune:$TUNE" >"$OUT/client.log" 2>&1 &
client_pid=$!
export RDPSYNC_REFERENCE_PID="$client_pid" RDPSYNC_REFERENCE_OUT="$OUT"
python3 "$ROOT/scripts/fixtures/reference_resize_check.py"
