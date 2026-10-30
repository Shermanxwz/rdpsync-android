#!/usr/bin/env bash
# Diagnostic comparison only: never replaces the mandatory Android fixture silently.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || {
  echo 'Reference server setup requires a disposable GitHub-hosted runner.' >&2; exit 1;
}
variant=${1:?apt-0.9.24 or upstream-0.10.6.1}
[[ "$variant" == apt-0.9.24 || "$variant" == upstream-0.10.6.1 ]] || exit 2
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/reference-evidence"
mkdir -p "$OUT"
# Reuse the exact original desktop/account/certificate setup for the baseline.
bash "$ROOT/scripts/fixtures/start-rdp-fixture.sh"
printf '%s\n' "$(id -u rdpfixture)" >"$OUT/owned-fixture-uid"
dpkg-query -W xrdp xorgxrdp xserver-xorg-core >"$OUT/original-package-versions.txt"
grep -Eq '^xrdp[[:space:]]+0\.9\.24([-.+~]|$)' "$OUT/original-package-versions.txt" || {
  echo 'Original apt fixture version changed; comparison must be reviewed.' >&2; exit 3;
}
cp "$ROOT/build/emulator-evidence/xrdp-version.txt" "$OUT/original-xrdp-version.txt"
if [[ "$variant" == upstream-0.10.6.1 ]]; then
  sudo systemctl stop xrdp xrdp-sesman
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git build-essential autoconf automake libtool pkg-config nasm libssl-dev libpam0g-dev \
    libx11-dev libxfixes-dev libxrandr-dev libxfont-dev xserver-xorg-dev
  WORK="$ROOT/build/reference-server"
  mkdir -p "$WORK"
  # Immutable upstream releases; recursive submodule commits are fixed by xrdp's tree.
  git clone --depth 1 --branch v0.10.6.1 --recurse-submodules --shallow-submodules \
    https://github.com/neutrinolabs/xrdp.git "$WORK/xrdp"
  test "$(git -C "$WORK/xrdp" rev-parse HEAD)" = 80c52879fead585d6347df5b3c587718adf8f5a5
  git clone --depth 1 --branch v0.10.5 https://github.com/neutrinolabs/xorgxrdp.git "$WORK/xorgxrdp"
  test "$(git -C "$WORK/xorgxrdp" rev-parse HEAD)" = c2d3cbafebe00be3221f985c06ef6b97f1c63790
  {
    git -C "$WORK/xrdp" rev-parse HEAD
    git -C "$WORK/xrdp" submodule status --recursive
    git -C "$WORK/xorgxrdp" rev-parse HEAD
  } >"$OUT/upstream-source-commits.txt"
  (
    cd "$WORK/xrdp"
    ./bootstrap
    ./configure --prefix=/usr --sysconfdir=/etc --localstatedir=/var --runstatedir=/run \
      --libdir=/usr/lib/x86_64-linux-gnu --enable-pam --enable-pam-config=debian \
      --enable-devel-logging --with-socketdir=/run/xrdp --with-systemdsystemunitdir=/lib/systemd/system
    make -j4
    sudo make install
    # Use this version's actual generated units/config, not old distribution protocol defaults.
    sudo install -m 644 instfiles/xrdp.service instfiles/xrdp-sesman.service /etc/systemd/system/
    sudo install -m 644 xrdp/xrdp.ini sesman/sesman.ini /etc/xrdp/
  )
  (
    cd "$WORK/xorgxrdp"
    ./bootstrap
    ./configure --prefix=/usr --libdir=/usr/lib/x86_64-linux-gnu
    make -j4
    sudo make install
  )
  sudo ldconfig
  sudo sed -i \
    's|^port=3389$|port=tcp://127.0.0.1:3389|; s/^autorun=$/autorun=Xorg/; s/^LogLevel=.*/LogLevel=DEBUG/; s|^certificate=.*|certificate=/etc/xrdp/rdpsync-fixture-cert.pem|; s|^key_file=.*|key_file=/etc/xrdp/rdpsync-fixture-key.pem|; s/^#runtime_user=.*/runtime_user=xrdp/; s/^#runtime_group=.*/runtime_group=xrdp/' \
    /etc/xrdp/xrdp.ini
  sudo sed -i \
    's|^UserWindowManager=.*|UserWindowManager=.xsession|; s|^DefaultWindowManager=.*|DefaultWindowManager=/home/rdpfixture/.xsession|; s|^param=Xorg$|param=/usr/lib/xorg/Xorg|; s/^KillDisconnected=.*/KillDisconnected=true/; s/^DisconnectedTimeLimit=.*/DisconnectedTimeLimit=60/; s/^#SessionSockdirGroup=.*/SessionSockdirGroup=xrdp/' \
    /etc/xrdp/sesman.ini
  sudo systemctl daemon-reload
  sudo systemctl start xrdp-sesman xrdp
fi
for unused in $(seq 1 50); do
  if (echo >/dev/tcp/127.0.0.1/3389) 2>/dev/null; then break; fi
  sleep 0.2
done
ss -ltn | grep '127.0.0.1:3389'
xrdp --version >"$OUT/active-xrdp-version.txt" 2>&1
sha256sum /usr/sbin/xrdp /usr/sbin/xrdp-sesman >"$OUT/server-binaries.sha256"
printf '%s\n' "$variant" >"$OUT/server-variant.txt"
git -C "$ROOT" rev-parse HEAD >"$OUT/tested-sha.txt"
# The old auth-log marker is deliberately NOT used to assert trust on the newer server.
printf '%s\n' 'Reference resize comparison only; Android certificate/auth security assertions are not executed here.' >"$OUT/scope.txt"
