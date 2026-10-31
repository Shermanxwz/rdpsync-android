#!/usr/bin/env python3
"""Explicit inner-session cleanup publication; not an oracle/natural-exit claim."""
import json
from pathlib import Path
import time


def publish(path, pid, success, error=None):
    path = Path(path)
    if path.exists():
        raise FileExistsError('Refuse to overwrite an existing inner cleanup receipt')
    temporary = path.with_suffix(path.suffix + '.tmp')
    value = dict(schema=1, kind='inner-session-cleanup', pid=pid,
                 cleanup_completed=True, cleanup_success=success, error=error)
    # The waiter must never parse a partially written receipt.
    with temporary.open('x') as output:
        json.dump(value, output)
        output.write('\n')
    temporary.replace(path)


def finish_with_receipt(cleanup, path, pid):
    try:
        cleanup()
    except BaseException as error:
        try:
            publish(path, pid, False, type(error).__name__)
        except BaseException as publication:
            error.add_note('Cleanup receipt publication failed: ' + repr(publication))
        raise
    publish(path, pid, True)


def validate(path, pid_path):
    path, pid_path = Path(path), Path(pid_path)
    assert path.stat().st_size <= 4096, 'Oversized inner cleanup receipt'
    value = json.loads(path.read_text())
    expected = int(pid_path.read_text())
    assert type(value['schema']) is int and value['schema'] == 1 and value['kind'] == 'inner-session-cleanup'
    assert type(value['pid']) is int and value['pid'] == expected and expected > 0, 'Inner cleanup PID mismatch'
    assert value['cleanup_completed'] is True, 'Inner cleanup not completed'
    assert value['cleanup_success'] is True and value['error'] is None, 'Inner cleanup failed'
    return value


def wait_for_cleanup(supervisor_running, path, pid_path, deadline):
    """One caller-owned overall deadline; never retry a published invalid receipt."""
    path = Path(path)
    receipt = None
    while time.monotonic() < deadline:
        if receipt is None and path.exists():
            receipt = validate(path, pid_path)
        if not supervisor_running() and receipt is not None:
            return receipt
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(.25, remaining))
    raise TimeoutError('Owned GNOME supervisor/inner cleanup did not complete within the existing cleanup deadline')
