#!/usr/bin/env python3
"""Disposable CI desktop. Draws deterministic probes; no user files/accounts involved."""
import json
import os
import uuid
import pathlib
import re
import subprocess
import threading
import time
import tkinter as tk
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OUTPUT = pathlib.Path.home() / "rdpsync-fixture-state.json"
lock = threading.Lock()
state = {"session_id": str(uuid.uuid4()), "desktop_pid": os.getpid(), "ready": False, "width": 0, "height": 0, "clicks": 0, "right_clicks": 0,
         "presses": 0, "releases": 0, "drag_moves": 0, "wheels": 0, "text": "",
         "keys": [], "resizes": [], "started_monotonic": time.monotonic()}


def snapshot():
    with lock:
        return dict(state)


def save():
    OUTPUT.write_text(json.dumps(snapshot(), ensure_ascii=False))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/state":
            self.send_error(404)
            return
        body = json.dumps(snapshot(), ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


# Only the runner host / emulator host-loopback alias can inspect synthetic state.
threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", 8765), Handler).serve_forever, daemon=True).start()
root = tk.Tk()
root.title("RdpSync isolated CI desktop")
root.overrideredirect(True)
canvas = tk.Canvas(root, bg="#112233", highlightthickness=0, borderwidth=0)
canvas.pack(fill="both", expand=True)
root.focus_force()
canvas.focus_set()


def paint():
    canvas.delete("all")
    # Uniform regions allow color assertions away from compression/font edges.
    for x, color in [(16, "#ff0000"), (112, "#00ff00"), (208, "#0000ff")]:
        canvas.create_rectangle(x, 16, x + 64, 80, fill=color, outline=color)
    with lock:
        clicks, text = state["clicks"], state["text"]
    marker = "#44dd88" if clicks % 2 else "#dd4488"
    canvas.create_rectangle(16, 112, 80, 176, fill=marker, outline=marker)
    canvas.create_text(112, 120, anchor="nw", fill="white", text="Synthetic test desktop — no private data", font=("sans", 16))
    canvas.create_text(112, 156, anchor="nw", fill="white", text=f"Clicks: {clicks} / Text: {text!r}", font=("sans", 12))
    save()


def resize_to_server():
    # Query the server rather than a toolkit's cached initial screen dimensions.
    output = subprocess.run(["xrandr", "--current"], capture_output=True, text=True, timeout=2).stdout
    match = re.search(r"current (\d+) x (\d+)", output)
    width, height = (int(match[1]), int(match[2])) if match else (root.winfo_screenwidth(), root.winfo_screenheight())
    with lock:
        changed = (width, height) != (state["width"], state["height"])
        if changed:
            state["width"], state["height"] = width, height
            state["resizes"].append([width, height])
            state["ready"] = True
    if changed:
        root.geometry(f"{width}x{height}+0+0")
        paint()
    root.after(250, resize_to_server)


def event(name, ev):
    with lock:
        if name == "press":
            state["presses"] += 1
        elif name == "release":
            state["releases"] += 1
            state["clicks"] += 1
        elif name == "right":
            state["right_clicks"] += 1
        elif name == "drag":
            state["drag_moves"] += 1
        elif name == "wheel":
            state["wheels"] += 1
        elif name == "key":
            state["keys"].append(ev.keysym)
            state["keys"] = state["keys"][-100:]
            if ev.keysym == "BackSpace":
                state["text"] = state["text"][:-1]
            elif ev.keysym == "Return":
                state["text"] += "\n"
            elif ev.char and ev.char.isprintable():
                state["text"] += ev.char
    paint()


canvas.bind("<ButtonPress-1>", lambda e: event("press", e))
canvas.bind("<ButtonRelease-1>", lambda e: event("release", e))
canvas.bind("<ButtonRelease-3>", lambda e: event("right", e))
canvas.bind("<B1-Motion>", lambda e: event("drag", e))
canvas.bind("<Button-4>", lambda e: event("wheel", e))
canvas.bind("<Button-5>", lambda e: event("wheel", e))
root.bind("<KeyPress>", lambda e: event("key", e))
resize_to_server()
root.mainloop()
