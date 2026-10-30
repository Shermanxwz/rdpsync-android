#!/usr/bin/env bash
# Control: unchanged upstream classic client must fail for the precise capability
# reason, not merely for any connection/authentication/environment failure.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
OUT="$ROOT/build/gnome-evidence/classic-negative"
mkdir -p "$OUT"
CLIENT=$(cat build/reference-freerdp/client-path.txt)
FINGERPRINT=$(cat build/gnome-evidence/certificate-sha256.txt)
[[ $FINGERPRINT =~ ^[a-f0-9]{64}$ ]]
export DISPLAY=:97 HOME="$ROOT/build/gnome-private/classic-home"
mkdir -p "$HOME"
Xvfb "$DISPLAY" -screen 0 1280x800x24 -nolisten tcp >"$OUT/xvfb.log" 2>&1 &
XPID=$!
trap 'kill "$XPID" 2>/dev/null || true; wait "$XPID" 2>/dev/null || true' EXIT
for i in $(seq 1 50); do xdpyinfo >/dev/null 2>&1 && break; sleep .1; done
xdpyinfo >/dev/null
cp build/reference-freerdp/{version.txt,source-revision.txt,client.sha256,buildconfig.txt,openssl-version.txt,linked-libraries.txt} "$OUT/"
wc -c <build/gnome-evidence/gnome-remote-desktop.log >"$OUT/server-log-start-offset.txt"
python3 - "$CLIENT" "$FINGERPRINT" "$OUT" <<'PYCONTROL'
import json, pathlib, subprocess, sys
client,pin,out=sys.argv[1],sys.argv[2],pathlib.Path(sys.argv[3])
args=[client,'/v:127.0.0.1:33891','/u:rdpfixture','/p:rdpsync-fixture-only',
      f'/cert:fingerprint:sha256:{pin}','/sec:nla','/size:1024x768','/bpp:32','/gdi:sw','/log-level:DEBUG',
      '/tune:FreeRDP_SupportGraphicsPipeline:FALSE,FreeRDP_GfxH264:FALSE,FreeRDP_AutoReconnectionEnabled:FALSE']
with (out/'client.log').open('wb') as log:
    p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)
    timed_out=False
    try:
        rc=p.wait(timeout=25)
    except subprocess.TimeoutExpired:
        timed_out=True;p.terminate()
        try: rc=p.wait(timeout=5)
        except subprocess.TimeoutExpired: p.kill();rc=p.wait(timeout=5)
(out/'process-result.json').write_text(json.dumps(dict(returncode=rc,timed_out=timed_out,natural_exit=not timed_out and rc>=0),indent=2))
(out/'raw-exit-code.txt').write_text(str(rc)+'\n')
assert not timed_out and rc>0, 'Expected a natural nonzero capability rejection, never a timeout/signal/setup success'
PYCONTROL
python3 - "$OUT" <<'PY'
import json, pathlib, sys, time
out=pathlib.Path(sys.argv[1]); log=out.parent/'gnome-remote-desktop.log'
start=int((out/'server-log-start-offset.txt').read_text())
expected='Client did not advertise support for the Graphics Pipeline'
for _ in range(30):
    new=log.read_bytes()[start:].decode(errors='replace')
    if expected in new:
        (out/'server-rejection.log').write_text(new)
        (out/'result.json').write_text(json.dumps(dict(result='expected classic capability rejection',
            exact_server_marker=expected, raw_client_exit=int((out/'raw-exit-code.txt').read_text()),
            scope='Official pinned no-GFX client with verified fixture pin and NLA; not attribution of a user host failure'),indent=2))
        break
    time.sleep(.1)
else:
    raise SystemExit('Classic negative control failed for an unclassified reason; see raw logs')
PY
