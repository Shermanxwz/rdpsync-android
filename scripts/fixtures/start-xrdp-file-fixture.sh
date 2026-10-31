#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
RDP_FIXTURE_PURPOSE=android-file-transfer bash scripts/fixtures/start-reference-server.sh upstream-0.10.6.1
OUT="$ROOT/build/xrdp-file-evidence"
PRIVATE="$ROOT/build/xrdp-file-private"
mkdir -p "$OUT" "$PRIVATE"
cc -shared -fPIC -Wall -Wextra -Werror scripts/fixtures/gnome/clipboard-provider.c \
  $(pkg-config --cflags --libs gtk+-3.0) -o "$PRIVATE/clipboard-provider.so"
sha256sum "$PRIVATE/clipboard-provider.so" >"$OUT/clipboard-provider-sha256.txt"
BASE=/home/rdpfixture/rdpsync-file-fixture
sudo install -d -m 700 -o rdpfixture -g rdpfixture "$BASE" "$BASE/gnome" "$BASE/evidence"
sudo install -m 600 -o rdpfixture -g rdpfixture "$PRIVATE/clipboard-provider.so" "$BASE/clipboard-provider.so"
for source in xrdp_transfer_files.py xrdp_transfer_peer.py xrdp_file_session.py xrdp_drive_files.py xrdp_drive_peer.py xrdp_drive_pressure.py; do
  sudo install -m 600 -o rdpfixture -g rdpfixture "scripts/fixtures/$source" "$BASE/$source"
done
sudo install -m 600 -o rdpfixture -g rdpfixture scripts/fixtures/gnome/transfer-fixture.py "$BASE/gnome/transfer-fixture.py"
sudo tee /home/rdpfixture/.xsession >/dev/null <<'SESSION'
#!/bin/sh
unset DBUS_SESSION_BUS_ADDRESS
unset XDG_RUNTIME_DIR
exec python3 /home/rdpfixture/rdpsync-file-fixture/xrdp_file_session.py
SESSION
sudo chmod 755 /home/rdpfixture/.xsession
sudo chown rdpfixture:rdpfixture /home/rdpfixture/.xsession
cp build/reference-evidence/{server-configure-options.txt,server-binaries.sha256,upstream-source-commits.txt,fuse-device.txt,fuse-version.txt} "$OUT/"
sudo sha256sum /usr/sbin/xrdp-chansrv >"$OUT/chansrv-sha256.txt"
git rev-parse HEAD >"$OUT/tested-sha.txt"
printf '%s\n' 'Pinned xrdp native CLIPRDR/FUSE peer; classic graphics; separate actual RDPDR mapped-drive scenario; no Windows OS/NLA claim. Separate from the original fourteen-test scenario.' >"$OUT/scope.txt"

sha256sum scripts/fixtures/xrdp_drive_files.py scripts/fixtures/xrdp_drive_peer.py scripts/fixtures/xrdp_drive_pressure.py scripts/fixtures/rdp_drive_relay.py >"$OUT/drive-fixture-source.sha256"
nohup python3 -u "$ROOT/scripts/fixtures/rdp_drive_relay.py" >"$OUT/drive-relay.log" 2>&1 &
printf '%s\n' "$!" >"$PRIVATE/drive-relay.pid"
for unused in $(seq 1 50); do
  if curl --fail --silent http://127.0.0.1:8771/relay >"$OUT/drive-relay-ready.json"; then break; fi
  sleep 0.1
done
python3 - "$OUT/drive-relay-ready.json" <<'PYCHECK'
import json,sys
v=json.load(open(sys.argv[1])); assert v['listener']=='127.0.0.1:3391' and v['connections']==0 and not v['paused']
PYCHECK
