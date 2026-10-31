#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PRIVATE="$ROOT/build/gnome-private"
OUT="$ROOT/build/gnome-evidence"
mkdir -p "$OUT"
python3 - <<'PY' >"$OUT/final-state.json" 2>/dev/null || true
import urllib.request
print(urllib.request.urlopen('http://127.0.0.1:8768/state', timeout=2).read().decode())
PY
# Verify each stored PID still denotes this fixture before sending any signal.
python3 - "$PRIVATE" "$OUT" "$ROOT/scripts/fixtures/gnome" <<'PY'
import os, pathlib, signal, sys, time
private = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
sys.path.insert(0, sys.argv[3])
from cleanup_receipt import wait_for_cleanup
# Share the original 60 * .25s budget across signalling and both completion
# conditions; the outer process disappearing is not inner cleanup completion.
deadline = time.monotonic() + 15
for name, required in [('supervisor.pid', str(private)), ('rdp-relay.pid', str(private / 'rdp.sock')), ('state-relay.pid', str(private / 'state.sock'))]:
    path = private / name
    if not path.exists():
        continue
    pid = int(path.read_text())
    try:
        cmd = pathlib.Path(f'/proc/{pid}/cmdline').read_bytes().replace(b'\0', b' ').decode()
        assert required in cmd, f'PID identity changed: {name}'
        assert pathlib.Path(f'/proc/{pid}').stat().st_uid == os.getuid()
        if name.endswith('relay.pid'):
            assert os.getpgid(pid) == pid, 'Relay no longer owns its process group'
            os.killpg(pid, signal.SIGTERM)
        else:
            os.kill(pid, signal.SIGTERM)
    except FileNotFoundError:
        pass
supervisor = private / 'supervisor.pid'
def supervisor_running():
    return supervisor.exists() and pathlib.Path(f'/proc/{int(supervisor.read_text())}').exists()
if supervisor.exists() or (out / 'runtime.json').exists() or (out / 'desktop.log').exists():
    wait_for_cleanup(supervisor_running, out / 'inner-cleanup.json',
                     private / 'inner-supervisor.pid', deadline)
print('Owned GNOME supervisor terminated and inner cleanup completed; private keys were never artifact inputs.')
PY
# Validation runs only after explicit inner cleanup/receipt publication, not
# merely the outer dbus-run-session leader exiting.
# Preserve a missing/incomplete trace as an evidence failure, never an oracle pass.
if [[ -f "$OUT/runtime.json" || -f "$OUT/desktop.log" ]]; then
  python3 "$ROOT/scripts/fixtures/gnome/bounded_trace.py" --verify "$OUT/desktop.log"
fi
