#!/usr/bin/env bash
# Only for a disposable GitHub-hosted VM. Never run against a user's RDP host.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Fixture setup is restricted to disposable GitHub Actions VMs.' >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xrdp xorgxrdp xserver-xorg-core x11-xserver-utils xauth python3-tk fonts-dejavu-core
sudo systemctl stop xrdp xrdp-sesman || true
# A fixed documented test-only password is safe here: the listener is host-loopback-only,
# this account exists solely in an ephemeral runner, and no host credentials are used.
sudo useradd --create-home --shell /bin/bash rdpfixture
printf '%s\n' 'rdpfixture:rdpsync-fixture-only' | sudo chpasswd
sudo install -m 755 "$ROOT/scripts/fixtures/rdp_desktop.py" /home/rdpfixture/rdp_desktop.py
sudo tee /home/rdpfixture/.xsession >/dev/null <<'SESSION'
#!/bin/sh
unset DBUS_SESSION_BUS_ADDRESS
unset XDG_RUNTIME_DIR
exec python3 /home/rdpfixture/rdp_desktop.py >/home/rdpfixture/fixture.log 2>&1
SESSION
sudo chmod 755 /home/rdpfixture/.xsession
sudo chown -R rdpfixture:rdpfixture /home/rdpfixture
sudo adduser xrdp ssl-cert
FINGERPRINT=$(sudo env GITHUB_ACTIONS=true python3 "$ROOT/scripts/fixtures/rdp_fixture_control.py" --prepare)
mkdir -p "$ROOT/build/emulator-evidence"
printf '%s\n' "$FINGERPRINT" >"$ROOT/build/emulator-evidence/fixture-initial-cert.sha256"
printf 'RDP_FIXTURE_FINGERPRINT=%s\n' "$FINGERPRINT" >>"$GITHUB_ENV"
sudo sed -i 's|^certificate=.*|certificate=/etc/xrdp/rdpsync-fixture-cert.pem|; s|^key_file=.*|key_file=/etc/xrdp/rdpsync-fixture-key.pem|; s/^LogLevel=.*/LogLevel=DEBUG/' /etc/xrdp/xrdp.ini
nohup sudo env GITHUB_ACTIONS=true python3 -u "$ROOT/scripts/fixtures/rdp_fixture_control.py" >"$ROOT/build/emulator-evidence/fixture-control.log" 2>&1 &
echo $! >"$ROOT/build/emulator-evidence/control.pid"
# Bind only the listener on the ephemeral runner's loopback; 10.0.2.2 maps here in emulator.
sudo sed -i 's/^port=3389$/port=tcp:\/\/127.0.0.1:3389/' /etc/xrdp/xrdp.ini
sudo sed -i 's/^autorun=$/autorun=Xorg/' /etc/xrdp/xrdp.ini
sudo sed -i 's/^max_bpp=.*/max_bpp=32/' /etc/xrdp/xrdp.ini
sudo sed -i 's/^X11DisplayOffset=.*/X11DisplayOffset=10/' /etc/xrdp/sesman.ini
sudo sed -i 's/^KillDisconnected=.*/KillDisconnected=true/; s/^DisconnectedTimeLimit=.*/DisconnectedTimeLimit=60/' /etc/xrdp/sesman.ini
sudo systemctl start xrdp-sesman xrdp
for i in $(seq 1 50); do
  if (echo >/dev/tcp/127.0.0.1/3389) 2>/dev/null; then break; fi
  sleep 0.2
done
ss -ltn | grep '127.0.0.1:3389'
mkdir -p "$ROOT/build/emulator-evidence"
xrdp --version >"$ROOT/build/emulator-evidence/xrdp-version.txt" 2>&1
git -C "$ROOT" rev-parse HEAD >"$ROOT/build/emulator-evidence/tested-sha.txt"
