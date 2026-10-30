"""Fixed synthetic URI clipboard actions, never arbitrary paths or commands."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import threading
import time
from urllib.parse import unquote, urlsplit


def samples(direction):
    assert direction in ('server', 'android')
    return {f'{direction}-binary.bin': bytes((i * 31 + 7) & 255 for i in range(262149)),
            f'{direction}-empty.bin': b'',
            ('远程文件.txt' if direction == 'server' else '安卓文件.txt'): 'RdpSync synthetic 文件\n\u0000🙂\n'.encode()}


def manifest(files):
    return {name: {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()} for name, data in files.items()}


def owned_fuse_mounts(paths, runtime, mountinfo):
    base = (Path(runtime) / 'gnome-remote-desktop').resolve()
    mounts = {}
    for line in mountinfo.splitlines():
        if ' - ' not in line:
            continue
        before, after = line.split(' - ', 1)
        fields, details = before.split(), after.split()
        if len(fields) < 6 or len(details) < 3:
            continue
        decoded = re.sub(r'\\([0-7]{3})', lambda m: chr(int(m.group(1), 8)), fields[4])
        mount = Path(decoded)
        if not mount.is_absolute() or mount.parent != base or not mount.name.startswith('cliprdr-'):
            continue
        if mount.resolve() != mount or details[0] != 'fuse' and not details[0].startswith('fuse.'):
            continue
        mounts[mount] = {'filesystem_type': details[0], 'mount_point': str(mount), 'owned_mountinfo_line': line}
    selected = {}
    for name, path in paths.items():
        matches = [value for mount, value in mounts.items() if path.is_relative_to(mount)]
        if len(matches) != 1:
            raise ValueError('No unique actual FUSE mount for owned clipboard URI')
        selected[name] = matches[0]
    return selected


def read_uploaded_uris(uris, runtime, destination, *, test_mountinfo=None):
    """Only the three expected fixture files inside GNOME's own FUSE mount."""
    expected = samples('android')
    if not uris or len(uris) != len(expected):
        raise ValueError('Expected exactly three real clipboard URIs')
    base = (Path(runtime) / 'gnome-remote-desktop').resolve()
    paths = {}
    for uri in uris:
        parsed = urlsplit(uri)
        if parsed.scheme != 'file' or parsed.netloc not in ('', 'localhost') or parsed.query or parsed.fragment:
            raise ValueError('Only local GNOME clipboard file URIs are accepted')
        path = Path(unquote(parsed.path)).resolve()
        relative = path.relative_to(base)
        if len(relative.parts) < 3 or not relative.parts[0].startswith('cliprdr-'):
            raise ValueError('URI is not in the owned GNOME FUSE clipboard')
        if path.name not in expected or path.name in paths:
            raise ValueError('Unexpected or duplicate synthetic file')
        paths[path.name] = path
    # Host mutation tests inject explicitly labelled metadata. Only the real
    # runtime reads actual kernel mount state and may claim via_gnome_fuse=true.
    actual_mountinfo = test_mountinfo is None
    mounts = owned_fuse_mounts(paths, runtime,
        Path('/proc/self/mountinfo').read_text() if actual_mountinfo else test_mountinfo)
    result = {}
    for name, path in paths.items():
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_size != len(expected[name]):
            raise ValueError('Synthetic file type/size mismatch')
        target = Path(destination) / name
        digest = hashlib.sha256(); size = 0
        with path.open('rb') as source, target.open('xb') as sink:
            while True:
                block = source.read(65536)
                if not block:
                    break
                size += len(block)
                if size > len(expected[name]):
                    raise ValueError('Source exceeded declared synthetic length')
                digest.update(block); sink.write(block)
        if size != len(expected[name]) or digest.hexdigest() != hashlib.sha256(expected[name]).hexdigest():
            raise ValueError('Actual FUSE bytes disagree with independent original manifest')
        result[name] = {'size': size, 'sha256': digest.hexdigest(), 'via_gnome_fuse': actual_mountinfo,
                        'mount_metadata_origin': 'actual /proc/self/mountinfo' if actual_mountinfo else 'synthetic host-unit metadata',
                        'mount': mounts[name]}
    return result


class TransferFixture:
    def __init__(self, root, out, Gtk, Gdk, GLib):
        self.root, self.out, self.Gtk, self.Gdk, self.GLib = Path(root), Path(out), Gtk, Gdk, GLib
        self.private = self.root / 'build/gnome-private/transfer'
        self.private.mkdir(mode=0o700)
        self.lock = threading.Lock(); self.operations = 0
        self.value = {'phase': 'idle', 'operation': 0, 'server_originals': manifest(samples('server')),
                      'android_originals': manifest(samples('android'))}
        self.lib = ctypes.CDLL(str(self.root / 'build/gnome-private/clipboard-provider.so'))
        self.lib.rdpsync_fixture_offer_uris.argtypes = [ctypes.POINTER(ctypes.c_char_p)]
        self.lib.rdpsync_fixture_offer_uris.restype = ctypes.c_int
        self.lib.rdpsync_fixture_uris_available.argtypes = []
        self.lib.rdpsync_fixture_uris_available.restype = ctypes.c_int
        for name, data in samples('server').items():
            (self.private / name).write_bytes(data)
        self.save()

    def snapshot(self):
        with self.lock:
            return json.loads(json.dumps(self.value))

    def save(self):
        (self.out / 'transfer-state.json').write_text(json.dumps(self.snapshot(), indent=2))

    def update(self, **values):
        with self.lock:
            self.value.update(values)
        self.save()

    def action(self, action):
        if action not in ('offer-server-files', 'read-android-files'):
            raise ValueError('Unknown fixed fixture action')
        with self.lock:
            if self.value['phase'] in ('queued', 'reading') or self.operations >= 8:
                raise ValueError('Fixture action busy or bounded operation limit reached')
            self.operations += 1
            self.value.update(operation=self.operations, phase='queued', action=action, error=None,
                              started_monotonic=time.monotonic())
        self.save()
        self.GLib.idle_add(self.perform, action, self.operations)
        return self.snapshot()

    def perform(self, action, operation):
        try:
            if action == 'offer-server-files':
                uris = [str((self.private / name).resolve().as_uri()).encode() for name in samples('server')]
                array = (ctypes.c_char_p * 4)(*uris, None)
                if self.lib.rdpsync_fixture_offer_uris(array) != 1:
                    raise RuntimeError('GTK refused the synthetic file URI offer')
                self.update(phase='offered', offered_count=3)
            else:
                self.await_uri_offer(operation, time.monotonic() + 10)
        except Exception as error:
            self.update(phase='failed', error=type(error).__name__ + ': ' + str(error))
        return False

    def await_uri_offer(self, operation, deadline):
        # The native offer may be queued while the dialog closes. Observe real
        # GTK format metadata before ONE URI request; never retry a content read.
        if self.lib.rdpsync_fixture_uris_available():
            self.update(uri_format_observed=True)
            self.Gtk.Clipboard.get(self.Gdk.SELECTION_CLIPBOARD).request_uris(self.uris_received, operation)
        elif time.monotonic() < deadline:
            self.GLib.timeout_add(100, self.await_uri_offer, operation, deadline)
        else:
            self.update(phase='failed', error='No actual GTK URI clipboard format within readiness bound')
        return False

    def uris_received(self, clipboard, uris, operation):
        self.update(phase='reading', uri_count=len(uris or []))
        def worker():
            try:
                destination = self.out / f'transfer-upload-{operation}'
                destination.mkdir()
                received = read_uploaded_uris(uris, os.environ['XDG_RUNTIME_DIR'], destination)
                self.update(phase='received', received=received, completed_monotonic=time.monotonic())
            except Exception as error:
                self.update(phase='failed', error=type(error).__name__ + ': ' + str(error))
        threading.Thread(target=worker, daemon=True).start()
