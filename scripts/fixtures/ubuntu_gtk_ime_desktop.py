#!/usr/bin/env python3
"""Real GTK3 graphical desktop text-editor window for live Ubuntu Wayland (GNOME) & X11 (xrdp) Chinese IME E2E verification."""
import json
import os
from pathlib import Path
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GLib, Gtk, Pango

TRAP_TEXT = "OLD_CLIPBOARD_TRAP_旧剪贴板污染测试"
STATE_PORT = int(os.environ.get("UBUNTU_IME_STATE_PORT", "8768"))
EXPECTED_BACKEND = os.environ.get("UBUNTU_IME_EXPECTED_BACKEND", "")
OUT_DIR = Path(os.environ.get("UBUNTU_IME_EVIDENCE_DIR", "/tmp/ubuntu-ime-evidence"))
OUT_DIR.mkdir(parents=True, exist_ok=True)

lock = threading.Lock()
state = {
    "pid": os.getpid(),
    "ready": False,
    "backend": "",
    "mapped": False,
    "width": 0,
    "height": 0,
    "entry_focused": False,
    "trap_armed": False,
    "trap_verified_text": "",
    "paste_signals": 0,
    "entry_text": "",
    "history": [],
}


def snapshot():
    with lock:
        return json.loads(json.dumps(state, ensure_ascii=False))


def persist():
    (OUT_DIR / "gtk-ime-desktop-state.json").write_text(
        json.dumps(snapshot(), ensure_ascii=False, indent=2), encoding="utf-8"
    )


window = Gtk.Window(title="RDPsync Ubuntu Graphical Desktop Chinese IME Verification")
window.set_default_size(1280, 720)
window.set_decorated(False)
window.move(0, 0)

vbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
vbox.set_margin_top(24)
vbox.set_margin_bottom(24)
vbox.set_margin_start(32)
vbox.set_margin_end(32)

header = Gtk.Label(label="Ubuntu Graphical Desktop — Live Chinese IME & Send-Text E2E Target")
header.set_xalign(0.0)
vbox.pack_start(header, False, False, 0)

textview = Gtk.TextView()
textview.set_editable(True)
textview.set_cursor_visible(True)
textview.set_wrap_mode(Gtk.WrapMode.CHAR)
textview.override_font(Pango.FontDescription("Sans 22"))
buf = textview.get_buffer()

frame = Gtk.Frame(label="Focused GTK3 TextView (center at 640x360)")
frame.add(textview)
vbox.pack_start(frame, True, True, 0)
window.add(vbox)


def on_buffer_changed(_buffer):
    start, end = buf.get_bounds()
    txt = buf.get_text(start, end, True)
    with lock:
        state["entry_text"] = txt
        state["history"] = (state["history"] + [txt])[-32:]
    persist()


def on_paste(_tv):
    with lock:
        state["paste_signals"] += 1
    persist()


def update_geometry(*_args):
    disp = Gdk.Display.get_default()
    backend_name = disp.__class__.__name__ if disp else "unknown"
    if EXPECTED_BACKEND:
        assert EXPECTED_BACKEND.lower() in backend_name.lower(), (
            f"Expected {EXPECTED_BACKEND} display backend, got {backend_name}"
        )
    alloc = window.get_allocation()
    with lock:
        state["backend"] = backend_name
        state["mapped"] = bool(window.get_mapped())
        state["width"] = int(alloc.width)
        state["height"] = int(alloc.height)
        state["entry_focused"] = bool(textview.has_focus())
        state["ready"] = bool(window.get_mapped() and alloc.width >= 600 and alloc.height >= 400)
    persist()
    return True


buf.connect("changed", on_buffer_changed)
textview.connect("paste-clipboard", on_paste)
window.connect("map-event", lambda *_: update_geometry())
window.connect("size-allocate", lambda *_: update_geometry())
window.connect("destroy", Gtk.main_quit)


def arm_trap_on_main_loop(done_event: threading.Event):
    try:
        window.maximize()
        window.present()
        gdk_win = window.get_window()
        if gdk_win is not None:
            try:
                gdk_win.focus(Gdk.CURRENT_TIME)
            except Exception:
                pass
        buf.set_text("")
        textview.grab_focus()
        clipboard = Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD)
        clipboard.set_text(TRAP_TEXT, -1)

        def on_clip_read(_clip, observed_text):
            with lock:
                state["paste_signals"] = 0
                state["entry_text"] = ""
                state["history"] = []
                state["entry_focused"] = bool(textview.has_focus())
                state["trap_verified_text"] = observed_text or ""
                state["trap_armed"] = (observed_text == TRAP_TEXT)
            persist()
            done_event.set()

        clipboard.request_text(on_clip_read)
    except Exception as exc:
        with lock:
            state["trap_armed"] = False
            state["trap_verified_text"] = f"ERR:{exc}"
        persist()
        done_event.set()
    return False


class StateHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/state":
            self.send_error(404)
            return
        payload = json.dumps(snapshot(), ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        if self.path != "/arm-trap":
            self.send_error(404)
            return
        ev = threading.Event()
        GLib.idle_add(arm_trap_on_main_loop, ev)
        ev.wait(timeout=5.0)
        payload = json.dumps(snapshot(), ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_args):
        pass


threading.Thread(
    target=ThreadingHTTPServer(("127.0.0.1", STATE_PORT), StateHandler).serve_forever,
    daemon=True,
).start()

window.maximize()
window.show_all()
textview.grab_focus()
GLib.timeout_add(200, update_geometry)
Gtk.main()
