#!/usr/bin/env python3
"""Synthetic native Wayland surface for the disposable GNOME RDP fixture."""
import json
import importlib.util
import os
from pathlib import Path
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import gi
gi.require_version('Gtk', '3.0')
gi.require_version('Gdk', '3.0')
gi.require_foreign('cairo')
from gi.repository import Gdk, GLib, Gtk
from presentation import Presentation

OUT = Path(os.environ['GNOME_FIXTURE_EVIDENCE'])
state = dict(session_id=str(uuid.uuid4()), desktop_pid=os.getpid(), ready=False,
             backend='GdkWaylandDisplay', width=0, height=0, monitor_width=0, monitor_height=0,
             mapped=False, visible=False, paint_count=0,
             presentation_generation=0, painted_generation=0, after_paint_generation=0,
             monitor_count=0, monitor_geometry=None, fullscreen=False, presentation_events=[],
             clicks=0, presses=0, releases=0, last_press=None, last_release=None, text='', keys=[],
             resizes=[], button_events=[], started_monotonic=time.monotonic())
lock = threading.Lock()
ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('transfer_fixture', Path(__file__).with_name('transfer-fixture.py'))
transfer_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transfer_module)
transfer = transfer_module.TransferFixture(ROOT, OUT, Gtk, Gdk, GLib)


def snapshot():
    with lock:
        return dict(state)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ('/state', '/transfer'):
            self.send_error(404)
            return
        data = json.dumps(snapshot() if self.path == '/state' else transfer.snapshot()).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        # Fixed synthetic actions only. No supplied paths, commands, file bytes,
        # arbitrary clipboard formats, or access to a user's session.
        if self.path not in ('/transfer/offer-server-files', '/transfer/read-android-files') or self.headers.get('Content-Length', '0') != '0':
            self.send_error(400)
            return
        try:
            data = json.dumps(transfer.action(self.path.rsplit('/', 1)[1])).encode()
        except ValueError:
            self.send_error(409)
            return
        self.send_response(202)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


threading.Thread(target=ThreadingHTTPServer(('127.0.0.1', 8768), Handler).serve_forever, daemon=True).start()
window = Gtk.Window(title='RdpSync GNOME native fixture')
window.set_decorated(False)
area = Gtk.DrawingArea()
area.set_can_focus(True)
area.add_events(Gdk.EventMask.BUTTON_PRESS_MASK | Gdk.EventMask.BUTTON_RELEASE_MASK | Gdk.EventMask.KEY_PRESS_MASK)
window.add(area)


def record_presentation(event, **details):
    with lock:
        state['presentation_events'] = (state['presentation_events'] + [dict(
            event=event, observed_monotonic=time.monotonic(), **details)])[-128:]
        if event == 'output-present':
            state.update(presentation_generation=details['generation'], painted_generation=0,
                         after_paint_generation=0, ready=False)
    save()


presentation = Presentation(window, area, record_presentation)


def paint(_, cr):
    with lock:
        state['paint_count'] += 1
    allocation = area.get_allocation()
    with lock:
        expected = state['monitor_geometry']
        if expected and (allocation.width, allocation.height) == tuple(expected[2:]):
            state['painted_generation'] = presentation.generation
    record_presentation('draw', generation=presentation.generation, width=allocation.width, height=allocation.height)
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
    monitor = display.get_monitor_at_window(window.get_window()) if display.get_n_monitors() > 0 and window.get_window() else None
    geo = monitor.get_geometry() if monitor else None
    with lock:
        if (state['width'], state['height']) != (allocation.width, allocation.height):
            state['resizes'].append([allocation.width, allocation.height])
        state.update(width=allocation.width, height=allocation.height,
                     monitor_width=geo.width if geo else 0, monitor_height=geo.height if geo else 0,
                     mapped=window.get_mapped(), visible=window.get_visible(),
                     ready=bool(geo and window.get_mapped() and allocation.width == geo.width and allocation.height == geo.height
                                and presentation.monitor == monitor and presentation.generation > 0
                                and state['after_paint_generation'] == presentation.generation))
    save()


def event(_, ev):
    with lock:
        if ev.type in (Gdk.EventType.BUTTON_PRESS, Gdk.EventType.BUTTON_RELEASE):
            state['button_events'] = (state['button_events'] + [dict(
                action='press' if ev.type == Gdk.EventType.BUTTON_PRESS else 'release',
                button=int(ev.button), x=ev.x, y=ev.y, event_time_ms=int(ev.time),
                observed_monotonic=time.monotonic())])[-64:]
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
area.connect('size-allocate', lambda _, a: record_presentation('size-allocate',
    generation=presentation.generation, width=a.width, height=a.height))
area.connect('button-press-event', event)
area.connect('button-release-event', event)
window.connect('key-press-event', event)
window.connect('destroy', Gtk.main_quit)


def reconcile_monitor(*_):
    # A reported monitor is not sufficient: defer the single initial map until
    # that output has a real geometry. Never remap because oracle pixels fail.
    display = Gdk.Display.get_default()
    count = display.get_n_monitors()
    monitor = display.get_monitor(0) if count else None
    geo = monitor.get_geometry() if monitor else None
    geometry = (geo.x, geo.y, geo.width, geo.height) if geo else None
    with lock:
        state.update(monitor_count=count, monitor_geometry=geometry)
    usable = presentation.observe(monitor, geometry)
    if not usable:
        with lock:
            state.update(ready=False, monitor_width=0, monitor_height=0,
                         mapped=window.get_mapped(), visible=window.get_visible())
        save()
    return False


def after_paint(clock):
    with lock:
        if state['painted_generation'] == presentation.generation and presentation.monitor is not None:
            state['after_paint_generation'] = presentation.generation
    record_presentation('after-paint', generation=presentation.generation,
                        frame_counter=clock.get_frame_counter())


def realized(*_):
    clock = window.get_frame_clock()
    record_presentation('realize', generation=presentation.generation, frame_clock_present=clock is not None)
    if clock is not None:
        clock.connect('after-paint', after_paint)


def window_state(_, event):
    with lock:
        state['fullscreen'] = bool(event.new_window_state & Gdk.WindowState.FULLSCREEN)
    record_presentation('window-state', generation=presentation.generation,
                        new_window_state=int(event.new_window_state))
    return False


def configure(_, event):
    record_presentation('configure', generation=presentation.generation,
                        x=event.x, y=event.y, width=event.width, height=event.height)
    return False


window.connect('realize', realized)
window.connect('map', lambda *_: record_presentation('map', generation=presentation.generation))
window.connect('unmap', lambda *_: record_presentation('unmap', generation=presentation.generation))
window.connect('configure-event', configure)
window.connect('window-state-event', window_state)
display = Gdk.Display.get_default()

def monitor_changed(_, monitor, event):
    geo = monitor.get_geometry()
    record_presentation(event, generation=presentation.generation,
                        geometry=[geo.x, geo.y, geo.width, geo.height])
    GLib.idle_add(reconcile_monitor)

display.connect('monitor-added', lambda d,m: monitor_changed(d,m,'monitor-added'))
display.connect('monitor-removed', lambda d,m: monitor_changed(d,m,'monitor-removed'))
reconcile_monitor()
GLib.timeout_add(250, lambda: (reconcile_monitor(), resized(area, area.get_allocation()), True)[2])
Gtk.main()
