#!/usr/bin/env bash
# Hosted CI-only synthetic X.224/TLS boundary. Never authenticates a user.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This setup is for the disposable GitHub runner only' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PRIVATE="${RUNNER_TEMP:?}/rdpsync-tls-fixture-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
EVIDENCE="$ROOT/build/emulator-evidence"
python3 - <<'PORTCHECK'
import socket
for port in (33890, 8767):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', port))
PORTCHECK
mkdir -p "$PRIVATE" "$EVIDENCE"
chmod 700 "$PRIVATE"
for number in 1 2; do
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj "/CN=rdpsync-synthetic-tls-$number" \
    -keyout "$PRIVATE/key$number.pem" -out "$PRIVATE/cert$number.pem" >/dev/null 2>&1
  chmod 600 "$PRIVATE/key$number.pem"
done
nohup env RDP_SYNTHETIC_TLS_FIXTURE=true python3 -u "$ROOT/scripts/fixtures/rdp_tls_fixture.py" \
  --directory "$PRIVATE" >"$EVIDENCE/tls-fixture.log" 2>&1 &
PID=$!
echo "$PID" >"$EVIDENCE/tls-fixture.pid"
for attempt in $(seq 1 20); do
  kill -0 "$PID"
  if python3 - <<'READY'
import json, urllib.request
state = json.load(urllib.request.urlopen('http://127.0.0.1:8767/state', timeout=1))
assert state['connections'] == 0 and state['application_bytes'] == 0
READY
  then
    echo 'Synthetic TLS boundary ready; no authentication or desktop implementation'
    exit 0
  fi
  sleep 1
done
echo 'Synthetic TLS fixture did not become ready' >&2
kill "$PID" 2>/dev/null || true
exit 1
