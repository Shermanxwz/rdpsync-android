#!/usr/bin/env bash
# Collect only synthetic fixture evidence, then stop this runner's owned session.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT="$ROOT/build/xrdp-file-evidence"
mkdir -p "$OUT"
sudo cp /var/log/xrdp.log /var/log/xrdp-sesman.log "$OUT/" 2>/dev/null || true
BASE=/home/rdpfixture/rdpsync-file-fixture
if sudo test -d "$BASE/evidence"; then sudo cp -R "$BASE/evidence" "$OUT/peer-evidence"; fi
sudo cp "$BASE/peer.log" /home/rdpfixture/fixture.log /home/rdpfixture/rdpsync-fixture-state.json "$OUT/" 2>/dev/null || true
while IFS= read -r logfile; do
  sudo cp "$logfile" "$OUT/xorg-${logfile##*/}"
done < <(sudo find /home/rdpfixture -maxdepth 1 -type f -name '.xorgxrdp.*.log' 2>/dev/null)
sudo chown -R "$(id -u):$(id -g)" "$OUT"
sudo systemctl stop xrdp xrdp-sesman || true
if [[ -f build/reference-evidence/owned-fixture-uid ]] && id rdpfixture >/dev/null 2>&1; then
  uid=$(cat build/reference-evidence/owned-fixture-uid)
  [[ "$uid" =~ ^[0-9]+$ && "$uid" -gt 0 && "$uid" == "$(id -u rdpfixture)" ]]
  [[ "$(getent passwd rdpfixture | cut -d: -f6)" == /home/rdpfixture ]]
  sudo pkill -TERM -u "$uid" || true
  for unused in $(seq 1 25); do
    if ! pgrep -u "$uid" >/dev/null; then break; fi
    sleep 0.2
  done
  if pgrep -u "$uid" >/dev/null; then sudo pkill -KILL -u "$uid" || true; fi
  ! pgrep -u "$uid" >/dev/null
  printf 'owned_fixture_uid=%s\nremaining_processes=0\n' "$uid" >"$OUT/cleanup.txt"
fi
if [[ -f build/emulator-evidence/control.pid ]]; then
  control_pid=$(cat build/emulator-evidence/control.pid)
  [[ "$control_pid" =~ ^[0-9]+$ ]]
  if [[ -r "/proc/$control_pid/cmdline" ]]; then
    tr '\0' ' ' <"/proc/$control_pid/cmdline" | grep -F "$ROOT/scripts/fixtures/rdp_fixture_control.py" >/dev/null
    sudo kill -TERM "$control_pid"
  fi
fi
