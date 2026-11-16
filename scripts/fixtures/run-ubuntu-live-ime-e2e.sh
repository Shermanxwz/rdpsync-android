#!/usr/bin/env bash
# Disposable GitHub Actions runner live E2E harness for BOTH Ubuntu GNOME Remote Desktop 46 (Wayland)
# and Ubuntu xrdp (X11), exercised from the real Android 15 emulator running com.rdp.sync.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || {
  echo "Live Ubuntu RDP fixture runs only on disposable GitHub-hosted runners." >&2
  exit 2
}

BACKEND="${1:?gnome or xrdp required}"
LAYOUT_DIR="${2:-}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [[ -n "$LAYOUT_DIR" ]]; then
  EVIDENCE="$ROOT/build/product-ui-evidence/$LAYOUT_DIR/ubuntu-live-ime-$BACKEND"
else
  EVIDENCE="$ROOT/build/product-ui-evidence/ubuntu-live-ime-$BACKEND"
fi
PRIV="$ROOT/build/ubuntu-ime-private-$BACKEND"
rm -rf "$EVIDENCE" "$PRIV"
mkdir -p "$EVIDENCE" "$PRIV"
chmod 700 "$PRIV"

export ANDROID_SERIAL=emulator-5554
adb -s "$ANDROID_SERIAL" shell rm -rf /sdcard/Download/rdpsync-ubuntu-live-ime /sdcard/Android/data/com.rdp.sync.debug/files/rdpsync-ubuntu-live-ime /sdcard/Android/data/com.rdp.sync/files/rdpsync-ubuntu-live-ime

cleanup_server() {
  local rc=$?
  set +e
  adb -s "$ANDROID_SERIAL" pull "/sdcard/Download/rdpsync-ubuntu-live-ime/." "$EVIDENCE/" >/dev/null 2>&1 || adb -s "$ANDROID_SERIAL" pull "/sdcard/Android/data/com.rdp.sync/files/rdpsync-ubuntu-live-ime/." "$EVIDENCE/" >/dev/null 2>&1 || true
  adb -s "$ANDROID_SERIAL" logcat -d >"$EVIDENCE/android-logcat.txt" 2>/dev/null || true
  if [[ "$BACKEND" == "gnome" ]]; then
    if [[ "$rc" -ne 0 ]]; then
      echo "=== GNOME REMOTE DESKTOP & GTK LOGS ===" >&2
      tail -n 80 "$EVIDENCE/gnome-remote-desktop.log" "$EVIDENCE/gtk-desktop.log" "$EVIDENCE/mutter.log" "$EVIDENCE/namespace.log" >&2 2>/dev/null || true
    fi
    for pidf in "$PRIV/rdp-relay.pid" "$PRIV/state-relay.pid" "$PRIV/launcher.pid"; do
      [[ -f "$pidf" ]] && sudo kill -TERM "$(cat "$pidf")" 2>/dev/null || true
    done
  elif [[ "$BACKEND" == "xrdp" ]]; then
    sudo cp /var/log/xrdp.log /var/log/xrdp-sesman.log "$EVIDENCE/" 2>/dev/null || true
    sudo cp /home/rdpfixture/gtk-desktop.log /home/rdpfixture/openbox.log /home/rdpfixture/.xsession-errors "$EVIDENCE/" 2>/dev/null || true
    sudo cp -r /home/rdpfixture/ime-evidence/. "$EVIDENCE/" 2>/dev/null || true
    sudo chown -R "$(id -u):$(id -g)" "$EVIDENCE" 2>/dev/null || true
    if [[ "$rc" -ne 0 ]]; then
      echo "=== XRDP, SESMAN & GTK LOGS ===" >&2
      tail -n 80 "$EVIDENCE/xrdp-sesman.log" "$EVIDENCE/xrdp.log" "$EVIDENCE/gtk-desktop.log" "$EVIDENCE/openbox.log" "$EVIDENCE/.xsession-errors" >&2 2>/dev/null || true
    fi
    sudo systemctl stop xrdp xrdp-sesman 2>/dev/null || true
  fi
  exit "$rc"
}
trap cleanup_server EXIT

if [[ "$BACKEND" == "gnome" ]]; then
  sudo apt-get update -qq
  # fuse3 (/dev/fuse + fusermount3) is mandatory for GNOME Remote Desktop 46 grd_clipboard_rdp_new()
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    gnome-remote-desktop mutter pipewire wireplumber fuse3 \
    python3-gi python3-cairo python3-gi-cairo gir1.2-gtk-3.0 \
    dbus-x11 libgl1-mesa-dri socat openssl iproute2 fonts-wqy-microhei

  [[ -c /dev/fuse && -r /dev/fuse && -w /dev/fuse ]] || { echo "Accessible /dev/fuse required for GNOME Remote Desktop CLIPRDR" >&2; exit 1; }
  sudo systemctl stop gnome-remote-desktop.service 2>/dev/null || true
  /usr/libexec/gnome-remote-desktop-daemon --version >"$EVIDENCE/server-version.txt"

  mkdir -p "$PRIV/home" "$PRIV/runtime"
  chmod 700 "$PRIV/home" "$PRIV/runtime"
  openssl req -new -newkey rsa:3072 -nodes -x509 -days 1 -subj /CN=10.0.2.2 \
    -keyout "$PRIV/key.pem" -out "$PRIV/cert.pem" >"$PRIV/cert.log" 2>&1
  chmod 600 "$PRIV/key.pem"
  FINGERPRINT="$(openssl x509 -in "$PRIV/cert.pem" -outform DER | sha256sum | cut -d' ' -f1)"
  RDP_PORT=33891
  STATE_PORT=8768

  cat >"$PRIV/run-gnome-ime-session.py" <<'PYGNOME'
import os, signal, subprocess, sys, time
from pathlib import Path

root, priv, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
procs = []
def spawn(cmd, name, extra_env=None):
    env = dict(os.environ)
    if extra_env:
        env.update(extra_env)
    fh = (out / f"{name}.log").open("wb")
    p = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=env)
    procs.append((p, fh))
    return p

def shutdown(*_):
    for p, fh in reversed(procs):
        if p.poll() is None:
            p.terminate()
    for p, fh in reversed(procs):
        try:
            p.wait(timeout=4)
        except Exception:
            p.kill()
        fh.close()
    sys.exit(0)

signal.signal(signal.SIGTERM, shutdown)
signal.signal(signal.SIGINT, shutdown)

os.environ.update(
    GSETTINGS_BACKEND="keyfile",
    GDK_BACKEND="wayland",
    GDK_SCALE="1",
    GDK_DPI_SCALE="1",
    WAYLAND_DISPLAY="rdpsync-wayland",
    LIBGL_ALWAYS_SOFTWARE="1",
    GNOME_REMOTE_DESKTOP_TEST_RDP_USERNAME="rdpfixture",
    GNOME_REMOTE_DESKTOP_TEST_RDP_PASSWORD="rdpsync-fixture-only",
)
schema = "org.gnome.desktop.remote-desktop.rdp"
for k, v in [
    ("enable", "true"),
    ("view-only", "false"),
    ("negotiate-port", "false"),
    ("tls-cert", str(priv / "cert.pem")),
    ("tls-key", str(priv / "key.pem")),
    ("screen-share-mode", "mirror-primary"),
]:
    subprocess.run(["gsettings", "set", schema, k, v], check=True)

spawn(["pipewire"], "pipewire")
spawn(["wireplumber"], "wireplumber")
spawn(
    ["mutter", "--headless", "--wayland", "--no-x11", "--wayland-display=rdpsync-wayland", "--virtual-monitor=1280x720"],
    "mutter",
    {"MUTTER_DEBUG_DISABLE_ANIMATIONS": "1"},
)

deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    r = subprocess.run(["gdbus", "wait", "--session", "--timeout=1", "org.gnome.Mutter.RemoteDesktop"], capture_output=True)
    if r.returncode == 0 and (Path(os.environ["XDG_RUNTIME_DIR"]) / "rdpsync-wayland").exists():
        break
    time.sleep(0.2)
else:
    raise SystemExit("Mutter Wayland session did not become ready")

spawn(["/usr/libexec/gnome-remote-desktop-daemon", "--rdp-port=3389"], "gnome-remote-desktop")
spawn(
    [sys.executable, str(root / "scripts/fixtures/ubuntu_gtk_ime_desktop.py")],
    "gtk-desktop",
    {
        "GTK_IM_MODULE": "gtk-im-context-simple",
        "UBUNTU_IME_STATE_PORT": "8768",
        "UBUNTU_IME_EXPECTED_BACKEND": "Wayland",
        "UBUNTU_IME_EVIDENCE_DIR": str(out),
    },
)
spawn(["socat", f"UNIX-LISTEN:{priv}/rdp.sock,fork,mode=600", "TCP:127.0.0.1:3389"], "rdp-sock")
spawn(["socat", f"UNIX-LISTEN:{priv}/state.sock,fork,mode=600", "TCP:127.0.0.1:8768"], "state-sock")

while True:
    for p, _ in procs:
        if p.poll() is not None:
            raise SystemExit(f"Process exited prematurely: {p.returncode}")
    time.sleep(0.3)
PYGNOME

  nohup sudo -E unshare --net --fork --kill-child=TERM bash -c '
    set -euo pipefail
    ip link set lo up
    exec setpriv --reuid="$1" --regid="$2" --init-groups \
      env -i PATH=/usr/bin:/bin LANG=C.UTF-8 HOME="$4/home" \
      XDG_CONFIG_HOME="$4/home/.config" XDG_DATA_HOME="$4/home/.local/share" \
      XDG_RUNTIME_DIR="$4/runtime" \
      dbus-run-session -- python3 "$4/run-gnome-ime-session.py" "$3" "$4" "$5"
  ' rdpsync-gnome-ime "$(id -u)" "$(id -g)" "$ROOT" "$PRIV" "$EVIDENCE" >"$EVIDENCE/namespace.log" 2>&1 &
  echo $! >"$PRIV/launcher.pid"

  for _ in $(seq 1 120); do
    [[ -S "$PRIV/rdp.sock" && -S "$PRIV/state.sock" ]] && break
    sleep 0.25
  done
  [[ -S "$PRIV/rdp.sock" && -S "$PRIV/state.sock" ]] || { cat "$EVIDENCE/namespace.log"; exit 1; }

  nohup setsid socat TCP-LISTEN:33891,bind=127.0.0.1,reuseaddr,fork "UNIX-CONNECT:$PRIV/rdp.sock" >"$EVIDENCE/rdp-relay.log" 2>&1 &
  echo $! >"$PRIV/rdp-relay.pid"
  nohup setsid socat TCP-LISTEN:8768,bind=127.0.0.1,reuseaddr,fork "UNIX-CONNECT:$PRIV/state.sock" >"$EVIDENCE/state-relay.log" 2>&1 &
  echo $! >"$PRIV/state-relay.pid"

  python3 - <<'PYWAIT'
import json, time, urllib.request
for _ in range(100):
    try:
        s = json.load(urllib.request.urlopen("http://127.0.0.1:8768/state", timeout=1))
        if s.get("ready") and "Wayland" in s.get("backend", ""):
            break
    except Exception:
        pass
    time.sleep(0.25)
else:
    raise SystemExit("GNOME Wayland GTK3 window did not reach ready state")
PYWAIT

elif [[ "$BACKEND" == "xrdp" ]]; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    xrdp xorgxrdp xserver-xorg-core x11-xserver-utils xauth openbox fuse3 dbus-x11 \
    python3-gi python3-cairo python3-gi-cairo gir1.2-gtk-3.0 fonts-wqy-microhei openssl

  sudo systemctl stop xrdp xrdp-sesman 2>/dev/null || true
  if ! id rdpfixture >/dev/null 2>&1; then
    sudo useradd --create-home --shell /bin/bash rdpfixture
  fi
  printf '%s\n' 'rdpfixture:rdpsync-fixture-only' | sudo chpasswd
  sudo mkdir -p /opt/rdpsync-ime-fixture /home/rdpfixture/ime-evidence
  sudo install -m 755 "$ROOT/scripts/fixtures/ubuntu_gtk_ime_desktop.py" /opt/rdpsync-ime-fixture/ubuntu_gtk_ime_desktop.py

  sudo tee /home/rdpfixture/.xsession >/dev/null <<'XSESSION'
#!/bin/sh
unset DBUS_SESSION_BUS_ADDRESS
unset XDG_RUNTIME_DIR
openbox >/home/rdpfixture/openbox.log 2>&1 &
sleep 0.4
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export GDK_BACKEND=x11
export GTK_IM_MODULE=gtk-im-context-simple
export UBUNTU_IME_STATE_PORT=8765
export UBUNTU_IME_EXPECTED_BACKEND=X11
export UBUNTU_IME_EVIDENCE_DIR=/home/rdpfixture/ime-evidence
mkdir -p "$UBUNTU_IME_EVIDENCE_DIR"
exec python3 /opt/rdpsync-ime-fixture/ubuntu_gtk_ime_desktop.py >/home/rdpfixture/gtk-desktop.log 2>&1
XSESSION
  sudo chmod 755 /home/rdpfixture/.xsession
  sudo chown -R rdpfixture:rdpfixture /home/rdpfixture

  sudo adduser xrdp ssl-cert >/dev/null 2>&1 || true
  sudo openssl req -new -newkey rsa:3072 -nodes -x509 -days 1 -subj /CN=10.0.2.2 \
    -keyout /etc/xrdp/rdpsync-ime-key.pem -out /etc/xrdp/rdpsync-ime-cert.pem >/dev/null 2>&1
  sudo chown root:xrdp /etc/xrdp/rdpsync-ime-key.pem /etc/xrdp/rdpsync-ime-cert.pem
  sudo chmod 640 /etc/xrdp/rdpsync-ime-key.pem
  sudo chmod 644 /etc/xrdp/rdpsync-ime-cert.pem
  FINGERPRINT="$(openssl x509 -in /etc/xrdp/rdpsync-ime-cert.pem -outform DER | sha256sum | cut -d' ' -f1)"
  RDP_PORT=3389
  STATE_PORT=8765

  sudo sed -i 's|^certificate=.*|certificate=/etc/xrdp/rdpsync-ime-cert.pem|; s|^key_file=.*|key_file=/etc/xrdp/rdpsync-ime-key.pem|; s/^LogLevel=.*/LogLevel=DEBUG/' /etc/xrdp/xrdp.ini
  sudo sed -i 's/^port=3389$/port=tcp:\/\/127.0.0.1:3389/' /etc/xrdp/xrdp.ini
  sudo sed -i 's/^autorun=$/autorun=Xorg/' /etc/xrdp/xrdp.ini
  sudo sed -i 's/^max_bpp=.*/max_bpp=32/' /etc/xrdp/xrdp.ini
  # Keep sesman's default startwm.sh -> /etc/X11/Xsession -> ~/.xsession bootstrap intact
  sudo sed -i 's/^X11DisplayOffset=.*/X11DisplayOffset=10/' /etc/xrdp/sesman.ini
  sudo sed -i 's/^KillDisconnected=.*/KillDisconnected=true/; s/^DisconnectedTimeLimit=.*/DisconnectedTimeLimit=60/' /etc/xrdp/sesman.ini

  if ! grep -q '^LANG=C.UTF-8' /etc/xrdp/sesman.ini; then
    sudo sed -i '/^\[SessionVariables\]/a LANG=C.UTF-8\nLC_ALL=C.UTF-8' /etc/xrdp/sesman.ini
  fi
  sudo mkdir -p /etc/systemd/system/xrdp-sesman.service.d /etc/systemd/system/xrdp.service.d
  printf '[Service]\nEnvironment="LANG=C.UTF-8" "LC_ALL=C.UTF-8"\n' | sudo tee /etc/systemd/system/xrdp-sesman.service.d/locale.conf >/dev/null
  printf '[Service]\nEnvironment="LANG=C.UTF-8" "LC_ALL=C.UTF-8"\n' | sudo tee /etc/systemd/system/xrdp.service.d/locale.conf >/dev/null
  sudo systemctl daemon-reload
  sudo systemctl start xrdp-sesman xrdp
  for _ in $(seq 1 50); do
    if (echo >/dev/tcp/127.0.0.1/3389) 2>/dev/null; then break; fi
    sleep 0.2
  done
  xrdp --version >"$EVIDENCE/server-version.txt" 2>&1 || true
else
  echo "Unsupported backend: $BACKEND" >&2
  exit 2
fi

printf '%s\n' "$FINGERPRINT" >"$EVIDENCE/server-cert-sha256.txt"

./gradlew --no-daemon -PnativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rdp.sync.UbuntuLiveChineseImeInstrumentedTest#liveUbuntuDesktopDirectChineseAndSendText \
  -Pandroid.testInstrumentationRunnerArguments.ubuntuLiveBackend="$BACKEND" \
  -Pandroid.testInstrumentationRunnerArguments.ubuntuLivePort="$RDP_PORT" \
  -Pandroid.testInstrumentationRunnerArguments.ubuntuLiveStatePort="$STATE_PORT" \
  -Pandroid.testInstrumentationRunnerArguments.ubuntuLiveFingerprint="$FINGERPRINT" \
  :app:connectedDebugAndroidTest

adb -s "$ANDROID_SERIAL" pull "/sdcard/Download/rdpsync-ubuntu-live-ime/." "$EVIDENCE/"
python3 - "$EVIDENCE/$BACKEND-receipt.json" <<'PYVERIFY'
import json, pathlib, sys
receipt = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = "你好Ubuntu桌面，连续输入不丢字 [发送文字100%正常]"
assert receipt["success"] is True, receipt
assert receipt["observed_text"] == expected, receipt
assert receipt["trap_armed_before_input"] is True, receipt
assert "OLD_CLIPBOARD_TRAP" not in receipt["observed_text"], receipt
assert (receipt["paste_signals"] >= 3 if receipt["backend"] == "xrdp" else receipt["paste_signals"] == 0), receipt
print(f"Verified live Ubuntu ({receipt['backend']} / {receipt['display_backend']}) Chinese IME & Send-Text E2E: {receipt['observed_text']}")
PYVERIFY
