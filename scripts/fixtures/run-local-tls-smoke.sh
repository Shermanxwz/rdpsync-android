#!/usr/bin/env bash
# Real native TLS callback, synthetic peer; not a full RDP/login/display test.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_APK="$(realpath "${1:?app x86_64 APK required}")"
TEST_APK="$(realpath "${2:?test APK required}")"
OUT="$(realpath -m "${3:-$ROOT/build/local-tls-smoke-$(date -u +%Y%m%dT%H%M%SZ)}")"
# Never accidentally bind/connect to an unrelated existing local fixture.
python3 - <<'PORTCHECK'
import socket
for port in (33890, 8767):
    with socket.socket() as s:
        s.bind(('127.0.0.1', port))
PORTCHECK
mkdir -p "$OUT/private"
chmod 700 "$OUT/private"
for n in 1 2; do
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=rdpsync-synthetic-tls-$n" \
    -keyout "$OUT/private/key$n.pem" -out "$OUT/private/cert$n.pem" >/dev/null 2>&1
  chmod 600 "$OUT/private/key$n.pem"
done
RDP_SYNTHETIC_TLS_FIXTURE=true python3 -u "$ROOT/scripts/fixtures/rdp_tls_fixture.py" \
  --directory "$OUT/private" >"$OUT/tls-fixture.log" 2>&1 &
FIXTURE_PID=$!
cleanup() {
  python3 -c 'import urllib.request; print(urllib.request.urlopen("http://127.0.0.1:8767/state",timeout=2).read().decode())' >"$OUT/fixture-final-state.json" 2>/dev/null || true
  kill "$FIXTURE_PID" 2>/dev/null || true
  wait "$FIXTURE_PID" 2>/dev/null || true
}
trap cleanup EXIT
sleep 1
kill -0 "$FIXTURE_PID"
RDP_SMOKE_CLASSES=com.rdp.sync.NativeTlsInstrumentedTest RDP_SMOKE_TLS_FIXTURE=true RDP_TLS_FIXTURE_CERT="$OUT/private/cert1.pem" \
  bash "$ROOT/scripts/fixtures/run-local-native-smoke.sh" "$APP_APK" "$TEST_APK" "$OUT/emulator"
