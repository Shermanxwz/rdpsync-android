#!/usr/bin/env python3
"""Fixed, loopback-only filesystem actions on a genuine RDPDR-mounted device."""
import concurrent.futures
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import stat
from pathlib import Path
import threading
import time
import uuid
import traceback

from xrdp_drive_pressure import FusePressure
from xrdp_drive_files import (STALL_READERS, BLOCK, LARGE_SIZE, original_manifest, owned_drive,
                             inspect_imports, readonly_check, exercise_rw, expect_denied, read_stall_range)

HOME = Path.home().resolve()
assert HOME == Path('/home/rdpfixture') and os.getuid() != 0
PEER_UUID = str(uuid.uuid4())
OUT = HOME / 'rdpsync-file-fixture/evidence/drive' / PEER_UUID
OUT.mkdir(parents=True, exist_ok=True)
SENTINEL = OUT.parent.parent / 'outside-drive-sentinel.txt'
if not SENTINEL.exists():
    SENTINEL.write_bytes(b'Owned fixture outside mounted drive; must remain unchanged.\n')
SENTINEL_HASH = hashlib.sha256(SENTINEL.read_bytes()).hexdigest()
state = {'peer': 'xrdp-rdpdr', 'pid': os.getpid(), 'uuid': PEER_UUID, 'operation': 0,
         'phase': 'idle', 'originals': original_manifest(), 'clipboard_used': False}
lock = threading.Lock()
held_fd = None
stall_fd = None
stall_pressure = None


def snapshot():
    with lock:
        return json.loads(json.dumps(state))


def update(**values):
    with lock:
        state.update(values)
        text = json.dumps(state, indent=2)
    temporary = OUT / 'drive-state.json.tmp'
    temporary.write_text(text)
    temporary.replace(OUT / 'drive-state.json')


def unchanged_sentinel():
    if hashlib.sha256(SENTINEL.read_bytes()).hexdigest() != SENTINEL_HASH:
        raise AssertionError('Independent outside-mounted-drive sentinel changed')
    return SENTINEL_HASH


def perform(action, operation):
    global held_fd, stall_fd, stall_pressure
    started = time.monotonic()
    try:
        if action in ('read-revoked-handle', 'read-stall-batch', 'read-cancel-batch'):
            # Do not issue a fresh stat/open before observing the pre-revoke handle.
            drive, mount = None, snapshot().get('mount')
            if not mount or not mount['via_actual_rdpdr_fuse']:
                raise ValueError('No previous actual mounted-device evidence')
        else:
            drive, mount = owned_drive(HOME)
        update(phase='running', mount=mount, started_monotonic=started)
        if action == 'inspect-imports':
            result = inspect_imports(drive)
        elif action == 'check-readonly':
            result = readonly_check(drive)
        elif action == 'exercise-writable':
            result = exercise_rw(drive)
        elif action == 'hold-stale-handle':
            if held_fd is not None:
                raise ValueError('One held fixture descriptor only')
            held_fd = os.open(drive / 'android-large.bin', os.O_RDONLY | os.O_CLOEXEC)
            os.posix_fadvise(held_fd, 0, 0, os.POSIX_FADV_DONTNEED)
            result = {'held_regular_file': True, 'read_after_revoke_offset': LARGE_SIZE - 4096}
        elif action == 'read-revoked-handle':
            if held_fd is None:
                raise ValueError('No pre-revoke descriptor')
            try:
                denial = expect_denied(lambda: os.pread(held_fd, 4096, LARGE_SIZE - 4096))
                result = {'stale_handle_read_denied': denial}
            finally:
                os.close(held_fd); held_fd = None
        elif action == 'prepare-stall-handle':
            if stall_fd is not None:
                raise ValueError('One prepared stall descriptor only')
            stall_fd = os.open(drive / 'android-large.bin', os.O_RDONLY | os.O_CLOEXEC)
            os.posix_fadvise(stall_fd, 0, 0, os.POSIX_FADV_DONTNEED)
            try:
                payload = os.fstat(stall_fd)  # prepare only: transport is unpaused
                if not stat.S_ISREG(payload.st_mode) or payload.st_size != LARGE_SIZE:
                    raise ValueError('Prepared payload identity/size mismatch')
                info = Path(f'/proc/self/fdinfo/{stall_fd}').read_text()
                match = re.search(r'^mnt_id:\s*([0-9]+)$', info, re.MULTILINE)
                if not match:
                    raise ValueError('Prepared payload mount ID unavailable')
                stall_pressure = FusePressure(HOME, payload.st_dev, match.group(1))
            except BaseException:
                os.close(stall_fd); stall_fd = None
                raise
            result = {'prepared_before_pause': True, 'fadvise_dontneed_completed': True,
                      'pressure_preparation': stall_pressure.receipt}
        elif action in ('read-stall-batch', 'read-cancel-batch'):
            # Independent uncached ranges. The transport relay is paused by the Android
            # test BEFORE this action; no native or server protocol calls are faked.
            if stall_fd is None:
                raise ValueError('Payload read descriptor must be prepared before the relay pause')
            descriptor = stall_fd
            stall_fd = None
            pressure = stall_pressure
            stall_pressure = None
            try:
                if pressure is None:
                    raise ValueError('No pre-pause FUSE identity')
                # Only proc mountinfo and fusectl are touched while paused;
                # the payload/root filesystem stats happened during prepare.
                pressure.configure()
                update(pressure=pressure.receipt)
                gate = threading.Barrier(STALL_READERS)
                def read_one(index):
                    gate.wait(timeout=5)
                    return read_stall_range(descriptor, index, action == 'read-cancel-batch')
                with concurrent.futures.ThreadPoolExecutor(max_workers=STALL_READERS) as pool:
                    results = list(pool.map(read_one, range(STALL_READERS)))
                denied = sum('denied' in item for item in results)
                incomplete = sum(item.get('incomplete_prefix') is True for item in results)
                if action == 'read-cancel-batch' and denied + incomplete == 0:
                    raise AssertionError('Revoke did not deny any actually pending mounted read')
                result = {'concurrent_readers': STALL_READERS, 'denied_readers': denied, 'incomplete_readers': incomplete, 'range_size': 256 * 1024,
                          'fadvise_dontneed_completed': True, 'resumed_ranges': results,
                          'pressure': pressure.receipt}
            finally:
                try:
                    os.close(descriptor)
                finally:
                    if pressure is not None:
                        try:
                            pressure.restore()
                        finally:
                            update(pressure=pressure.receipt)
        elif action == 'verify-persistence':
            result = inspect_imports(drive)
            from xrdp_drive_files import REMOTE, hash_file
            expected = REMOTE[:131101] + b'append\x00\xff'
            actual = hash_file(drive / 'remote-created/second/remote-roundtrip.bin', len(expected))
            if actual['sha256'] != hashlib.sha256(expected).hexdigest():
                raise AssertionError('Remote-created file did not persist after reconnect')
            result['remote_created_persisted'] = actual
        else:
            raise ValueError('Unsupported fixed action')
        result['outside_mount_sentinel_sha256'] = unchanged_sentinel()
        update(phase='completed', result=result, completed_monotonic=time.monotonic(),
               duration_seconds=time.monotonic() - started)
        (OUT / f'operation-{operation}.json').write_text(json.dumps(snapshot(), indent=2))
    except Exception as error:
        if hasattr(error, 'pressure_receipt'):
            update(pressure=error.pressure_receipt)
        update(phase='failed', error=type(error).__name__ + ': ' + str(error),
               traceback=traceback.format_exc(),
               completed_monotonic=time.monotonic(), duration_seconds=time.monotonic() - started)
        (OUT / f'operation-{operation}.json').write_text(json.dumps(snapshot(), indent=2))


ACTIONS = {'inspect-imports', 'check-readonly', 'exercise-writable', 'hold-stale-handle',
           'read-revoked-handle', 'prepare-stall-handle', 'read-stall-batch', 'read-cancel-batch', 'verify-persistence'}


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, value):
        data = json.dumps(value).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path == '/drive': self.reply(200, snapshot())
        else: self.send_error(404)
    def do_POST(self):
        action = self.path.removeprefix('/drive/')
        if not self.path.startswith('/drive/') or action not in ACTIONS or self.headers.get('Content-Length', '0') != '0':
            self.send_error(400); return
        with lock:
            if state['phase'] in ('queued', 'running') or state['operation'] >= 12:
                self.send_error(409); return
            operation = state['operation'] + 1
            state.update(operation=operation, phase='queued', action=action, result=None, error=None)
        threading.Thread(target=perform, args=(action, operation), daemon=True).start()
        self.reply(202, snapshot())
    def log_message(self, *_): pass


if __name__ == '__main__':
    update()
    try:
        ThreadingHTTPServer(('127.0.0.1', 8770), Handler).serve_forever()
    finally:
        if held_fd is not None:
            os.close(held_fd)
        if stall_fd is not None:
            os.close(stall_fd)
        if stall_pressure is not None:
            stall_pressure.restore()
