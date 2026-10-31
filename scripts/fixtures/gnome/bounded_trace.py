"""Bounded diagnostic capture for the owned synthetic Wayland desktop only.

The dedicated reader drains beyond the retained prefix and hashes every received
byte. A pipe can still backpressure its producer; this diagnostic is not free of
timing overhead. Overflow/incomplete capture cannot prove an event was absent.
"""
import hashlib
import json
import os
import selectors
import sys
from pathlib import Path
import subprocess
import threading

LIMIT = 8 * 1024 * 1024
CHUNK = 64 * 1024


class BoundedTrace:
    def __init__(self, process, path, limit=LIMIT):
        if limit <= 0 or limit > LIMIT:
            raise ValueError('Invalid diagnostic retention limit')
        self.process, self.path, self.limit = process, Path(path), limit
        self.total = self.retained = 0
        self.digest, self.prefix_digest = hashlib.sha256(), hashlib.sha256()
        self.eof = False
        self.errors, self.termination_requests = [], []
        self.completed = threading.Event()
        self.stop_reader = threading.Event()
        self.thread = threading.Thread(target=self._drain, name='owned-wayland-trace', daemon=True)
        self.thread.start()

    def _drain(self):
        output = None
        selector = selectors.DefaultSelector()
        try:
            os.set_blocking(self.process.stdout.fileno(), False)
            selector.register(self.process.stdout, selectors.EVENT_READ)
            try:
                output = self.path.open('wb', buffering=0)
            except OSError as error:
                self.errors.append('open:' + type(error).__name__)
            while not self.stop_reader.is_set():
                if not selector.select(0.1):
                    continue
                try:
                    chunk = os.read(self.process.stdout.fileno(), CHUNK)
                except BlockingIOError:
                    continue
                if not chunk:
                    self.eof = True
                    break
                self.total += len(chunk)
                self.digest.update(chunk)
                keep = chunk[:max(0, self.limit - self.retained)]
                if output is not None and keep:
                    try:
                        offset = 0
                        while offset < len(keep):
                            count = output.write(keep[offset:])
                            if not count:
                                raise OSError('No write progress')
                            self.prefix_digest.update(keep[offset:offset+count])
                            self.retained += count
                            offset += count
                    except OSError as error:
                        self.errors.append('write:' + type(error).__name__)
                        try:
                            output.close()
                        except OSError:
                            pass
                        output = None  # Continue draining/hash; never stop at the retention cap.
        except Exception as error:
            self.errors.append('read:' + type(error).__name__)
        finally:
            selector.close()
            for stream in (output, self.process.stdout):
                try:
                    if stream is not None:
                        stream.close()
                except OSError as error:
                    self.errors.append('close:' + type(error).__name__)
            self.completed.set()

    def note_termination(self, signal_name):
        if signal_name not in ('SIGTERM', 'SIGKILL'):
            raise ValueError('Unknown shutdown signal')
        self.termination_requests.append(signal_name)

    def finish(self, timeout=5):
        finished = self.completed.wait(timeout)
        if not finished:
            self.stop_reader.set()
            self.thread.join(1)
            finished = self.completed.is_set()
            self.errors.append("reader-eof-deadline")
        else:
            self.thread.join()
        # Never race a live hasher to claim a whole-stream hash.
        code = self.process.poll()
        result = dict(schema=1, retention_limit=self.limit, retained_bytes=self.retained,
                      received_bytes=self.total, overflow=self.total > self.limit,
                      producer_eof=self.eof, collector_finished=finished,
                      process_exited=code is not None, process_returncode=code,
                      process_signal=-code if code is not None and code < 0 else None,
                      termination_requests=list(self.termination_requests), errors=list(self.errors),
                      whole_received_stream_sha256=self.digest.hexdigest() if finished else None,
                      retained_prefix_sha256=self.prefix_digest.hexdigest() if finished else None,
                      complete=finished and self.eof and not self.errors and code is not None,
                      retained_received_stream_complete_only=finished and self.eof and not self.errors and code is not None and self.total <= self.limit,
                      producer_natural_success=code == 0 and not self.termination_requests,
                      negative_event_inference_allowed=False,
                      scope='Owned synthetic desktop stdout+WAYLAND_DEBUG=client stderr; protocol object IDs are not authentication tokens',
                      timing_caveat='Dedicated bounded-memory reader; pipe writes can backpressure and tracing adds timing overhead')
        self.path.with_suffix('.receipt.json').write_text(json.dumps(result, indent=2)+'\n')
        return result


def launch(args, path, env):
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               bufsize=0, env=env)
    try:
        trace = BoundedTrace(process, path)
    except BaseException as primary:
        # Popen succeeded but ownership has not reached session.children yet.
        # Do not orphan the producer if reader construction/start fails.
        try:
            if process.poll() is None:
                try:
                    process.terminate()
                except ProcessLookupError:
                    pass  # Still wait/reap after an exit raced with the signal.
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)
        except BaseException as cleanup:
            primary.add_note('Unregistered producer cleanup: ' + repr(cleanup))
        finally:
            try:
                process.stdout.close()
            except BaseException as cleanup:
                primary.add_note('Unregistered stdout cleanup: ' + repr(cleanup))
        raise
    process.bounded_trace = trace
    return process, trace


def finish_collectors(traces, files):
    failure = None
    for close in [*(trace.finish for trace in traces), *(file.close for file in files)]:
        try:
            close()
        except BaseException as error:
            if failure is None:
                failure = error
            else:
                failure.add_note('Additional cleanup failure: ' + repr(error))
    if failure is not None:
        raise failure


def cleanup_preserving_primary(cleanup, primary):
    try:
        cleanup()
    except BaseException as error:
        if primary is None or (isinstance(primary, SystemExit) and primary.code in (None, 0)):
            raise
        primary.add_note('Diagnostic cleanup failure (primary preserved): ' + repr(error))


def verify(path):
    path = Path(path)
    receipt_path = path.with_suffix('.receipt.json')
    assert receipt_path.stat().st_size <= 65536, 'Oversized diagnostic receipt'
    receipt = json.loads(receipt_path.read_text())
    assert path.stat().st_size <= LIMIT, 'Oversized diagnostic artifact'
    data = path.read_bytes()
    assert receipt['schema'] == 1 and receipt['retention_limit'] == LIMIT
    assert receipt['complete'] and receipt['collector_finished'] and receipt['producer_eof']
    assert receipt['process_exited'] and not receipt['errors']
    assert len(data) == receipt['retained_bytes'] <= LIMIT
    assert receipt['received_bytes'] >= len(data)
    assert hashlib.sha256(data).hexdigest() == receipt['retained_prefix_sha256']
    assert receipt['overflow'] == (receipt['received_bytes'] > LIMIT)
    assert receipt['negative_event_inference_allowed'] is False
    assert receipt['retained_received_stream_complete_only'] == (not receipt['overflow'])
    assert receipt['producer_natural_success'] == (receipt['process_returncode'] == 0 and not receipt['termination_requests'])
    if not receipt['overflow']:
        assert receipt['received_bytes'] == len(data)
        assert receipt['whole_received_stream_sha256'] == receipt['retained_prefix_sha256']
    print('Owned desktop bounded Wayland diagnostic receipt verified; process exit is recorded separately, not an oracle result.')


if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] != '--verify':
        raise SystemExit('usage: bounded_trace.py --verify LOG')
    verify(sys.argv[2])
