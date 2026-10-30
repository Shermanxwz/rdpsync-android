#!/usr/bin/env python3
"""Own exactly one isolated GNOME session; no GDM, saved users, or external servers."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

ROOT = Path(sys.argv[1]).resolve()
PRIVATE = Path(sys.argv[2]).resolve()
OUT = Path(sys.argv[3]).resolve()
MODE = sys.argv[4]
assert MODE in ('headless-extend', 'screen-share-mirror')
assert os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
assert os.getuid() != 0
children = []
files = []
stopping = False


def spawn(args, name):
    f = (OUT / f'{name}.log').open('wb')
    files.append(f)
    p = subprocess.Popen(args, stdout=f, stderr=subprocess.STDOUT, start_new_session=(name == 'session'))
    p.owns_group = name == 'session'
    children.append(p)
    return p


def run(args):
    subprocess.run(args, check=True, timeout=15, stdout=subprocess.DEVNULL)


def stop(*_):
    global stopping
    stopping = True
    for p in reversed(children):
        if p.poll() is None:
            if p.owns_group:
                os.killpg(p.pid, signal.SIGTERM)
            else:
                p.terminate()
    for p in reversed(children):
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            if p.owns_group:
                os.killpg(p.pid, signal.SIGKILL)
            else:
                p.kill()
            p.wait(timeout=5)
    for f in files:
        f.close()


signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
try:
    if '--inside-dbus' not in sys.argv:
        (PRIVATE / 'supervisor.pid').write_text(str(os.getpid()))
        spawn(['dbus-run-session', '--', sys.executable, __file__, str(ROOT), str(PRIVATE), str(OUT), MODE, '--inside-dbus'], 'session')
    else:
        # Upstream's own tests use keyfile settings and synthetic credential env;
        # credentials still traverse the actual GNOME NLA/NTLM authentication path.
        os.environ.update(GSETTINGS_BACKEND='keyfile', GDK_BACKEND='wayland', GDK_SCALE='1', GDK_DPI_SCALE='1',
                          WAYLAND_DISPLAY='rdpsync-wayland', LIBGL_ALWAYS_SOFTWARE='1',
                          GNOME_REMOTE_DESKTOP_TEST_RDP_USERNAME='rdpfixture',
                          GNOME_REMOTE_DESKTOP_TEST_RDP_PASSWORD='rdpsync-fixture-only',
                          GNOME_FIXTURE_EVIDENCE=str(OUT))
        schema = 'org.gnome.desktop.remote-desktop.rdp'
        for key, value in [('enable', 'true'), ('view-only', 'false'), ('negotiate-port', 'false'),
                           ('tls-cert', str(PRIVATE / 'cert.pem')), ('tls-key', str(PRIVATE / 'key.pem')),
                           ('screen-share-mode', 'extend' if MODE == 'headless-extend' else 'mirror-primary')]:
            run(['gsettings', 'set', schema, key, value])
        spawn(['pipewire'], 'pipewire')
        spawn(['wireplumber'], 'wireplumber')
        mutter = ['mutter', '--headless', '--wayland', '--no-x11', '--wayland-display=rdpsync-wayland']
        if MODE == 'screen-share-mirror':
            mutter += ['--virtual-monitor=1024x768']
        spawn(mutter, 'mutter')
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            p = subprocess.run(['gdbus', 'wait', '--session', '--timeout=1', 'org.gnome.Mutter.RemoteDesktop'], capture_output=True)
            if p.returncode == 0 and (Path(os.environ['XDG_RUNTIME_DIR']) / 'rdpsync-wayland').exists():
                break
            if any(p.poll() is not None for p in children):
                raise RuntimeError('A GNOME session dependency exited before readiness')
        else:
            raise RuntimeError('Mutter remote desktop D-Bus/Wayland readiness timed out')
        daemon = ['/usr/libexec/gnome-remote-desktop-daemon', '--rdp-port=3389']
        if MODE == 'headless-extend':
            daemon += ['--headless']
        spawn(daemon, 'gnome-remote-desktop')
        spawn([sys.executable, str(ROOT / 'scripts/fixtures/gnome/desktop.py')], 'desktop')
        spawn(['socat', f'UNIX-LISTEN:{PRIVATE}/rdp.sock,fork,mode=600', 'TCP:127.0.0.1:3389'], 'rdp-unix-relay')
        spawn(['socat', f'UNIX-LISTEN:{PRIVATE}/state.sock,fork,mode=600', 'TCP:127.0.0.1:8768'], 'state-unix-relay')
        (OUT / 'runtime.json').write_text(json.dumps(dict(mode=MODE, supervisor_pid=os.getpid(),
            net_namespace=os.readlink('/proc/self/ns/net'), nla='GNOME native required; no server downgrade',
            scope='per-user headless extend' if MODE == 'headless-extend' else 'per-user screen sharing mirror-primary',
            not_covered=['GDM system Remote Login / redirection', 'Windows server NLA']), indent=2))
    while not stopping:
        for p in children:
            if p.poll() is not None:
                raise RuntimeError(f'Owned fixture component exited ({p.returncode})')
        time.sleep(0.25)
finally:
    stop()
