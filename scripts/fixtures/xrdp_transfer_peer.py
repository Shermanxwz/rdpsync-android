#!/usr/bin/env python3
"""Native GTK/X11 selection owner for a disposable xrdp session; no RDP mocks."""
import ctypes
import json
import os
from pathlib import Path
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import gi
gi.require_version('Gtk', '3.0')
from gi.repository import Gtk, Gdk, GLib
from xrdp_transfer_files import samples, manifest, read_xrdp_uris

HOME = Path.home().resolve()
assert HOME == Path('/home/rdpfixture') and os.getuid() != 0
BASE = HOME / 'rdpsync-file-fixture'
OUT = BASE / 'evidence'; OUT.mkdir(exist_ok=True)
SOURCE = BASE / 'originals'; SOURCE.mkdir(exist_ok=True)
for name, data in samples('server').items():
    (SOURCE / name).write_bytes(data)
lib = ctypes.CDLL(str(BASE / 'clipboard-provider.so'))
lib.rdpsync_fixture_offer_uris.argtypes = [ctypes.POINTER(ctypes.c_char_p)]
lib.rdpsync_fixture_offer_uris.restype = ctypes.c_int
lib.rdpsync_fixture_uris_available.argtypes = []; lib.rdpsync_fixture_uris_available.restype = ctypes.c_int
Gtk.init([])
assert 'X11' in Gdk.Display.get_default().__class__.__name__, 'This peer must use actual session X11'
state = {'peer': 'xrdp', 'phase': 'idle', 'operation': 0, 'pid': os.getpid(),
         'server_originals': manifest(samples('server')), 'android_originals': manifest(samples('android'))}
lock = threading.Lock()


def snapshot():
    with lock: return json.loads(json.dumps(state))


def update(**values):
    with lock: state.update(values)
    (OUT / 'transfer-state.json').write_text(json.dumps(snapshot(), indent=2))


def uris_received(clipboard, uris, operation):
    update(phase='reading', uri_count=len(uris or []))
    def worker():
        try:
            destination = OUT / f'upload-{operation}'; destination.mkdir()
            received = read_xrdp_uris(uris, HOME, destination)
            update(phase='received', received=received, completed_monotonic=time.monotonic())
        except Exception as error:
            update(phase='failed', error=type(error).__name__ + ': ' + str(error))
    threading.Thread(target=worker, daemon=True).start()


def await_offer(operation, deadline):
    if lib.rdpsync_fixture_uris_available():
        update(uri_format_observed=True)
        Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD).request_uris(uris_received, operation)
    elif time.monotonic() < deadline:
        GLib.timeout_add(100, await_offer, operation, deadline)
    else:
        update(phase='failed', error='No actual X11 URI clipboard format within readiness bound')
    return False


def perform(action, operation):
    try:
        if action == 'offer-server-files':
            uris = [(SOURCE / name).as_uri().encode() for name in samples('server')]
            if lib.rdpsync_fixture_offer_uris((ctypes.c_char_p * 4)(*uris, None)) != 1:
                raise RuntimeError('GTK refused synthetic URI list')
            update(phase='offered', offered_count=3)
        else:
            await_offer(operation, time.monotonic() + 10)
    except Exception as error:
        update(phase='failed', error=type(error).__name__ + ': ' + str(error))
    return False


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, value):
        data = json.dumps(value).encode(); self.send_response(code)
        self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path == '/transfer': self.reply(200, snapshot())
        else: self.send_error(404)
    def do_POST(self):
        if self.path not in ('/transfer/offer-server-files', '/transfer/read-android-files') or self.headers.get('Content-Length', '0') != '0':
            self.send_error(400); return
        with lock:
            if state['phase'] in ('queued', 'reading') or state['operation'] >= 8:
                self.send_error(409); return
            operation = state['operation'] + 1
            state.update(phase='queued', operation=operation, error=None, action=self.path.rsplit('/', 1)[1])
        GLib.idle_add(perform, self.path.rsplit('/', 1)[1], operation)
        self.reply(202, snapshot())
    def log_message(self, *_): pass


threading.Thread(target=ThreadingHTTPServer(('127.0.0.1', 8769), Handler).serve_forever, daemon=True).start()
update()
Gtk.main()
