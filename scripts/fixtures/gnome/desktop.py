#!/usr/bin/env python3
"""Synthetic native Wayland surface for the disposable GNOME RDP fixture."""
import json
import os
from pathlib import Path
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import gi
gi.require_version('Gtk', '3.0')
gi.require_foreign('cairo')
from gi.repository import Gdk, GLib, Gtk

OUT = Path(os.environ['GNOME_FIXTURE_EVIDENCE'])
state = dict(session_id=str(uuid.uuid4()), desktop_pid=os.getpid(), ready=False,
             backend='GdkWaylandDisplay', width=0, height=0, monitor_width=0, monitor_height=0,
             clicks=0, presses=0, releases=0, last_press=None, last_release=None, text='', keys=[],
             resizes=[], started_monotonic=time.monotonic())
lock = threading.Lock()


def snapshot():
    with lock:
        return dict(state)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/state':
            self.send_error(404)
            return
        data = json.dumps(snapshot()).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


threading.Thread(target=ThreadingHTTPServer(('127.0.0.1', 8768), Handler).serve_forever, daemon=True).start()
window = Gtk.Window(title='RdpSync GNOME native fixture')
window.set_decorated(False)
window.fullscreen()
area = Gtk.DrawingArea()
area.set_can_focus(True)
area.add_events(Gdk.EventMask.BUTTON_PRESS_MASK | Gdk.EventMask.BUTTON_RELEASE_MASK | Gdk.EventMask.KEY_PRESS_MASK)
window.add(area)


def paint(_, cr):
    cr.set_source_rgb(17 / 255, 34 / 255, 51 / 255)
    cr.paint()
    for x, rgb in [(16, (1, 0, 0)), (112, (0, 1, 0)), (208, (0, 0, 1))]:
        cr.set_source_rgb(*rgb)
        cr.rectangle(x, 16, 64, 64)
        cr.fill()
    s = snapshot()
    for x, y, rgb in [(16, 112, (68, 221, 136) if s['clicks'] % 2 else (221, 68, 136)),
                       (304, 16, [(85, 204, 204), (204, 170, 85), (153, 85, 221)][len(s['text']) % 3])]:
        cr.set_source_rgb(*(v / 255 for v in rgb))
        cr.rectangle(x, y, 64, 64)
        cr.fill()
    return False


def resized(_, allocation):
    display = Gdk.Display.get_default()
    assert 'Wayland' in display.__class__.__name__, 'Fixture must use native Wayland, never X11'
    monitor = display.get_monitor_at_window(window.get_window()) if window.get_window() else None
    geo = monitor.get_geometry() if monitor else None
    with lock:
        if (state['width'], state['height']) != (allocation.width, allocation.height):
            state['resizes'].append([allocation.width, allocation.height])
        state.update(width=allocation.width, height=allocation.height,
                     monitor_width=geo.width if geo else 0, monitor_height=geo.height if geo else 0,
                     ready=bool(geo and allocation.width == geo.width and allocation.height == geo.height))
    save()


def event(_, ev):
    with lock:
        if ev.type == Gdk.EventType.BUTTON_PRESS and ev.button == 1:
            state['presses'] += 1
            state['last_press'] = [ev.x, ev.y]
        elif ev.type == Gdk.EventType.BUTTON_RELEASE and ev.button == 1:
            state['releases'] += 1
            state['clicks'] += 1
            state['last_release'] = [ev.x, ev.y]
        elif ev.type == Gdk.EventType.KEY_PRESS:
            name = Gdk.keyval_name(ev.keyval)
            state['keys'] = (state['keys'] + [name])[-100:]
            if name in ('Return', 'KP_Enter'):
                state['text'] += '\n'
            elif name == 'BackSpace':
                state['text'] = state['text'][:-1]
            else:
                codepoint = Gdk.keyval_to_unicode(ev.keyval)
                if codepoint and chr(codepoint).isprintable():
                    state['text'] += chr(codepoint)
    area.queue_draw()
    save()
    return False


def save():
    (OUT / 'desktop-state.json').write_text(json.dumps(snapshot(), indent=2))


area.connect('draw', paint)
area.connect('size-allocate', resized)
area.connect('button-press-event', event)
area.connect('button-release-event', event)
window.connect('key-press-event', event)
window.connect('destroy', Gtk.main_quit)
window.show_all()
area.grab_focus()
GLib.timeout_add(250, lambda: (resized(area, area.get_allocation()), True)[1])
Gtk.main()
