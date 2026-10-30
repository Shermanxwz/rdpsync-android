#!/usr/bin/env python3
"""Observe official X11 client without changing protocol/security state checks."""
import json
import hashlib
import os
import pathlib
import subprocess
import time
import urllib.request

from PIL import Image

out = pathlib.Path(os.environ['RDPSYNC_REFERENCE_OUT'])
pid = int(os.environ['RDPSYNC_REFERENCE_PID'])
result = {'schema_version': 2, 'run_id': os.environ['RDPSYNC_REFERENCE_RUN_ID'],
          'client_pid': pid, 'passed': False, 'stage': 'initial', 'frames': {}}


def alive():
    # kill(pid,0) is also true for a zombie; /proc state distinguishes exit.
    try:
        return pathlib.Path(f'/proc/{pid}/stat').read_text().split(') ')[1][0] != 'Z'
    except FileNotFoundError:
        return False


def state(timeout=2):
    with urllib.request.urlopen('http://127.0.0.1:8765/state', timeout=timeout) as response:
        return json.load(response)


def observe_failure_state(initial):
    """Record fresh server observations; never replace the immediate snapshot.

    The desktop polls XRandR every 250ms. Its HTTP metadata can lag a completed
    server resize when the client already rejected the reactivation sequence.
    """
    start = time.monotonic()
    deadline = start + 2.0
    observations = []
    while (remaining := deadline - time.monotonic()) > 0:
        try:
            actual = state(timeout=min(0.4, remaining))
            observations.append({'observed_monotonic': time.monotonic(), 'state': actual})
            identity = (actual.get('session_id'), actual.get('desktop_pid'))
            if identity != (initial.get('session_id'), initial.get('desktop_pid')):
                break
            if (actual.get('width'), actual.get('height')) == (1280, 720):
                break
        except (OSError, ValueError) as exc:
            observations.append({'observed_monotonic': time.monotonic(), 'error': type(exc).__name__})
        time.sleep(min(0.1, max(0, deadline - time.monotonic())))
    return {'started_monotonic': start, 'ended_monotonic': time.monotonic(),
            'budget_seconds': 2.0, 'observations': observations}


def wait_for(predicate, seconds=35):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if not alive():
            raise AssertionError('Official client exited before comparison completed')
        try:
            value = predicate()
            if value:
                return value
        except (OSError, ValueError):
            pass
        time.sleep(0.2)
    raise AssertionError(f'Timed out during {result["stage"]}')


def geometry(width, height):
    current = state()
    return current if current.get('ready') and (current['width'], current['height']) == (width, height) else None


def screenshot(window, name, width, height, clicks=None):
    path = out / name
    subprocess.run(['import', '-window', window, str(path)], check=True, timeout=8)
    image = Image.open(path).convert('RGB')
    if image.size != (width, height):
        raise AssertionError(f'Client window size {image.size} does not match {(width, height)}')
    probes = [((40, 40), (255, 0, 0)), ((136, 40), (0, 255, 0)), ((232, 40), (0, 0, 255))]
    if width > 1024:
        # Original 1024-wide framebuffer cannot supply this pixel. A larger local
        # window or stale top-left RGB patches alone are not successful resizing.
        probes.append(((1200, 600), (0x11, 0x22, 0x33)))
    if clicks is not None:
        probes.append(((40, 140), (0x44, 0xdd, 0x88) if clicks % 2 else (0xdd, 0x44, 0x88)))
    for point, expected in probes:
        actual = image.getpixel(point)
        if actual != expected:
            raise AssertionError(f'Received RGB mismatch at {point}: {actual}')
    result['frames'][name] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                            'width': width, 'height': height, 'clicks': clicks}
    return image.size


def wait_for_paint(window, name, width, height, clicks=None):
    def ready():
        try:
            return screenshot(window, name, width, height, clicks)
        except AssertionError as exc:
            result['last_pending_paint'] = str(exc)
            return None
    return wait_for(ready, 15)


try:
    initial = wait_for(lambda: geometry(1024, 768), 60)
    result['initial_server'] = initial
    window = wait_for(lambda: subprocess.run(
        ['xdotool', 'search', '--onlyvisible', '--name', '^rdpsync-reference$'],
        capture_output=True, text=True, timeout=2).stdout.strip().splitlines(), 10)[0]
    # Desktop state can precede delivery; wait for observable pixels, not a sleep.
    wait_for_paint(window, 'before.png', 1024, 768, initial['clicks'])
    result['stage'] = 'display-control-caps'
    wait_for(lambda: 'DisplayControlCapsPdu: setting the window as resizable' in
             (out / 'client.log').read_text(errors='replace'), 15)
    result['stage'] = 'resize'
    result['resize_requested_monotonic'] = time.monotonic()
    subprocess.run(['xdotool', 'windowsize', '--sync', window, '1280', '720'], check=True, timeout=8)
    resized = wait_for(lambda: geometry(1280, 720))
    result['resized_server'] = resized
    if resized['session_id'] != initial['session_id']:
        raise AssertionError('Server desktop session changed during resize')
    wait_for_paint(window, 'after.png', 1280, 720, resized['clicks'])
    result['stage'] = 'post-resize-input'
    before_clicks = state()['clicks']
    result['clicks_before_post_resize'] = before_clicks
    subprocess.run(['xdotool', 'mousemove', '--window', window, '40', '140', 'click', '1'], check=True, timeout=5)
    after = wait_for(lambda: (s if (s := state())['clicks'] > before_clicks else None), 10)
    if after['session_id'] != initial['session_id']:
        raise AssertionError('Server session changed after input')
    wait_for_paint(window, 'after-input.png', 1280, 720, after['clicks'])
    if not alive():
        raise AssertionError('Official client exited during final received repaint')
    result.pop('last_pending_paint', None)
    result.update(passed=True, stage='complete', final_server=after)
except Exception as exc:
    result['error'] = str(exc)
    result['error_observed_monotonic'] = time.monotonic()
    try:
        result['final_server'] = state()
    except Exception:
        pass
    if (result['stage'] == 'resize' and
            str(exc) == 'Official client exited before comparison completed' and not alive()):
        result['post_failure_server_observation'] = observe_failure_state(result.get('initial_server', {}))
    raise
finally:
    result['client_alive_at_finish'] = alive()
    (out / 'result.json').write_text(json.dumps(result, indent=2))
