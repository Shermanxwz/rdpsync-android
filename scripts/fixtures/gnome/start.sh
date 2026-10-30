#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || { echo 'Disposable standard GitHub-hosted runner only' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
MODE=${1:?headless-extend or screen-share-mirror}
[[ $MODE == headless-extend || $MODE == screen-share-mirror ]]
PRIVATE="$ROOT/build/gnome-private"
OUT="$ROOT/build/gnome-evidence"
[[ ! -e $PRIVATE ]] || { echo 'Refuse to reuse private fixture state' >&2; exit 2; }
mkdir -p "$PRIVATE/home" "$PRIVATE/runtime" "$OUT"
chmod 700 "$PRIVATE" "$PRIVATE/home" "$PRIVATE/runtime"
# These packages/services belong only to this disposable runner, never a user host.
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends gnome-remote-desktop mutter pipewire wireplumber \
  python3-gi python3-cairo python3-gi-cairo gir1.2-gtk-3.0 dbus-x11 libgl1-mesa-dri socat openssl iproute2
# A distro package may auto-start its system Remote Login service. This test uses
# only its private per-user daemon, not GDM and not the system service.
sudo systemctl stop gnome-remote-desktop.service 2>/dev/null || true
if sudo systemctl is-active --quiet gnome-remote-desktop.service; then
  echo "Refuse fixture with a separately active system GRD service" >&2; exit 1
fi
/usr/libexec/gnome-remote-desktop-daemon --version >"$OUT/gnome-version.txt"
grep -Eq 'GNOME Remote Desktop 46\.' "$OUT/gnome-version.txt"
mutter --version >"$OUT/mutter-version.txt"
dpkg-query -W -f='${Package}\t${Version}\t${source:Package}\t${source:Version}\n' \
  gnome-remote-desktop mutter pipewire wireplumber libfreerdp3-3 libwinpr3-3 libgl1-mesa-dri >"$OUT/packages.tsv"
sha256sum /usr/libexec/gnome-remote-desktop-daemon /usr/bin/mutter >"$OUT/server-binary-sha256.txt"
ldd /usr/libexec/gnome-remote-desktop-daemon >"$OUT/server-linked-libraries.txt"
git rev-parse HEAD >"$OUT/tested-sha.txt"
printf '%s\n' "$MODE" >"$OUT/mode.txt"
readlink /proc/self/ns/net >"$OUT/host-network-namespace.txt"
openssl req -new -newkey rsa:3072 -nodes -x509 -days 1 -subj /CN=rdpsync-gnome-fixture \
  -keyout "$PRIVATE/key.pem" -out "$PRIVATE/cert.pem" >"$PRIVATE/certificate-generation.log" 2>&1
chmod 600 "$PRIVATE/key.pem"
FINGERPRINT=$(openssl x509 -in "$PRIVATE/cert.pem" -outform DER | sha256sum | cut -d' ' -f1)
printf '%s\n' "$FINGERPRINT" >"$OUT/certificate-sha256.txt"
echo "GNOME_FIXTURE_FINGERPRINT=$FINGERPRINT" >>"$GITHUB_ENV"
echo "GNOME_FIXTURE_MODE=$MODE" >>"$GITHUB_ENV"
# A private network namespace contains GRD's wildcard listener. Only owned
# host-loopback relays reach it via permission-restricted Unix sockets. No veth,
# firewall, routing, global sysctl, external address, or user network is changed.
nohup sudo -E unshare --net --fork --kill-child=TERM bash -c '
  set -euo pipefail
  ip link set lo up
  exec setpriv --reuid="$1" --regid="$2" --init-groups \
    env -i PATH=/usr/bin:/bin LANG=C.UTF-8 HOME="$4/home" XDG_CONFIG_HOME="$4/home/.config" XDG_DATA_HOME="$4/home/.local/share" \
      XDG_RUNTIME_DIR="$4/runtime" GITHUB_ACTIONS=true RUNNER_ENVIRONMENT=github-hosted \
      python3 "$3/scripts/fixtures/gnome/session.py" "$3" "$4" "$5" "$6"
' rdpsync-gnome "$(id -u)" "$(id -g)" "$ROOT" "$PRIVATE" "$OUT" "$MODE" >"$OUT/namespace.log" 2>&1 &
echo $! >"$PRIVATE/launcher.pid"
for attempt in $(seq 1 120); do
  [[ -S $PRIVATE/rdp.sock && -S $PRIVATE/state.sock ]] && break
  kill -0 "$(cat "$PRIVATE/launcher.pid")" || { cat "$OUT/namespace.log"; exit 1; }
  sleep 0.25
done
[[ -S $PRIVATE/rdp.sock && -S $PRIVATE/state.sock ]]
nohup setsid socat TCP-LISTEN:33891,bind=127.0.0.1,reuseaddr,fork "UNIX-CONNECT:$PRIVATE/rdp.sock" >"$OUT/rdp-loopback-relay.log" 2>&1 &
echo $! >"$PRIVATE/rdp-relay.pid"
nohup setsid socat TCP-LISTEN:8768,bind=127.0.0.1,reuseaddr,fork "UNIX-CONNECT:$PRIVATE/state.sock" >"$OUT/state-loopback-relay.log" 2>&1 &
echo $! >"$PRIVATE/state-relay.pid"
python3 - "$OUT" <<'PY'
import json, pathlib, time, urllib.request
out = pathlib.Path(__import__('sys').argv[1])
for _ in range(120):
    try:
        s = json.load(urllib.request.urlopen('http://127.0.0.1:8768/state', timeout=1))
        assert s['backend'] == 'GdkWaylandDisplay'
        (out / 'initial-desktop-state.json').write_text(json.dumps(s, indent=2))
        break
    except Exception:
        time.sleep(.25)
else:
    raise SystemExit('Native Wayland fixture state endpoint did not become ready')
assert json.loads((out / 'runtime.json').read_text())['net_namespace'] != (out / 'host-network-namespace.txt').read_text().strip()
PY
ss -ltnp '( sport = :33891 or sport = :8768 )' >"$OUT/host-listeners.txt"
grep -q '127.0.0.1:33891' "$OUT/host-listeners.txt"
grep -q '127.0.0.1:8768' "$OUT/host-listeners.txt"
! grep -Eq '(0\.0\.0\.0|\[::\]|\*):(33891|8768)' "$OUT/host-listeners.txt"
