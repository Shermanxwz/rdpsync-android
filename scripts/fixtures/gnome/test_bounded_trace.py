#!/usr/bin/env python3
"""Real subprocess/pipe collector regressions; no Wayland/runtime pixels claim."""
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from bounded_trace import BoundedTrace, LIMIT, launch, verify, finish_collectors, cleanup_preserving_primary


class TraceTests(unittest.TestCase):
    def run_child(self, script):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name)/'desktop.log'
        process, trace = launch([sys.executable, '-c', script], path, dict(os.environ))
        self.addCleanup(lambda: process.kill() if process.poll() is None else None)
        return process, trace, path

    def test_complete_stdout_stderr_order_hash(self):
        process, trace, path = self.run_child('import os; os.write(1,b"first\\n");os.write(2,b"second\\n")')
        self.assertEqual(process.wait(timeout=5),0)
        result=trace.finish()
        self.assertTrue(result['complete']);self.assertFalse(result['overflow'])
        self.assertEqual(path.read_bytes(),b'first\nsecond\n')
        self.assertEqual(result['whole_received_stream_sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual(result['termination_requests'],[]);verify(path)

    def test_over_cap_still_drains_full_stream(self):
        count=160
        process,trace,path=self.run_child('import os\nfor i in range(160):\n b=bytes([i])*65536\n while b:\n  n=os.write(1,b);b=b[n:]')
        self.assertEqual(process.wait(timeout=10),0)
        result=trace.finish()
        expected=b''.join(bytes([i])*65536 for i in range(count))
        self.assertEqual(result['received_bytes'],len(expected))
        self.assertEqual(path.stat().st_size,LIMIT)
        self.assertEqual(path.read_bytes(),expected[:LIMIT])
        self.assertEqual(result['whole_received_stream_sha256'],hashlib.sha256(expected).hexdigest())
        self.assertTrue(result['overflow']);self.assertTrue(result['producer_eof'])
        self.assertFalse(result['negative_event_inference_allowed']);verify(path)

    def test_nonzero_is_not_relabelled_success(self):
        process,trace,path=self.run_child('import os,sys;os.write(1,b"failed\\n");sys.exit(7)')
        self.assertEqual(process.wait(timeout=5),7)
        result=trace.finish()
        self.assertTrue(result['complete']);self.assertEqual(result['process_returncode'],7)
        self.assertEqual(result['termination_requests'],[])
        self.assertFalse(result['negative_event_inference_allowed']);self.assertFalse(result['producer_natural_success']);verify(path)

    def test_termination_and_eof_are_separate(self):
        process,trace,path=self.run_child('import os,signal;os.write(1,b"ready\\n");signal.pause()')
        deadline=time.monotonic()+3
        while trace.total==0 and time.monotonic()<deadline:time.sleep(.01)
        self.assertGreater(trace.total,0)
        trace.note_termination('SIGTERM');process.terminate()
        self.assertEqual(process.wait(timeout=5),-signal.SIGTERM)
        result=trace.finish();self.assertTrue(result['producer_eof'])
        self.assertEqual(result['process_signal'],signal.SIGTERM)
        self.assertEqual(result['termination_requests'],['SIGTERM'])
        self.assertFalse(result['negative_event_inference_allowed']);self.assertFalse(result['producer_natural_success']);verify(path)

    def test_incomplete_reader_deadline_is_not_clean_eof(self):
        process,trace,path=self.run_child('import time;time.sleep(10)')
        result=trace.finish(timeout=.01)
        self.assertTrue(result['collector_finished']);self.assertFalse(result['producer_eof'])
        self.assertFalse(result['complete']);self.assertIsNone(result['process_returncode'])
        self.assertIn('reader-eof-deadline',result['errors'])
        with self.assertRaises(AssertionError):verify(path)
        process.terminate();process.wait(timeout=5)

    def test_reader_start_failure_does_not_orphan_real_child(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        original=subprocess.Popen;created=[]
        def create(*args,**kwargs):
            process=original(*args,**kwargs);created.append(process);return process
        with patch('bounded_trace.subprocess.Popen',side_effect=create), patch('bounded_trace.threading.Thread.start',side_effect=RuntimeError('injected reader start')):
            with self.assertRaisesRegex(RuntimeError,'injected reader start'):
                launch([sys.executable,'-c','import time;time.sleep(30)'],Path(temporary.name)/'desktop.log',dict(os.environ))
        self.assertEqual(len(created),1);self.assertIsNotNone(created[0].poll())
        self.assertTrue(created[0].stdout.closed)

    def test_launch_signal_exit_race_still_reaps(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        original=subprocess.Popen;created=[]
        def create(*args,**kwargs):
            process=original(*args,**kwargs);created.append(process)
            terminate=process.terminate
            def exited_during_signal():
                terminate();raise ProcessLookupError('simulated exit race')
            process.terminate=exited_during_signal
            return process
        with patch('bounded_trace.subprocess.Popen',side_effect=create),patch('bounded_trace.threading.Thread.start',side_effect=RuntimeError('reader start')):
            with self.assertRaisesRegex(RuntimeError,'reader start'):
                launch([sys.executable,'-c','import time;time.sleep(30)'],Path(temporary.name)/'desktop.log',dict(os.environ))
        self.assertIsNotNone(created[0].returncode);self.assertTrue(created[0].stdout.closed)

    def test_receipt_failure_preserves_primary_and_closes_other_files(self):
        process,trace,path=self.run_child('print("synthetic")')
        process.wait(timeout=5)
        other=path.with_name('other.log').open('wb')
        primary=RuntimeError('original oracle failure')
        with patch.object(Path,'write_text',side_effect=OSError('injected receipt failure')):
            cleanup_preserving_primary(lambda:finish_collectors([trace],[other]),primary)
        self.assertTrue(other.closed);self.assertTrue(trace.completed.is_set())
        self.assertIn('original oracle failure',str(primary))
        self.assertTrue(any('injected receipt failure' in note for note in primary.__notes__))
        for normal in (None, SystemExit(0), SystemExit(None)):
            with self.assertRaisesRegex(OSError,'cleanup-only'):
                cleanup_preserving_primary(lambda:(_ for _ in ()).throw(OSError('cleanup-only')),normal)
        nonzero=SystemExit(7)
        cleanup_preserving_primary(lambda:(_ for _ in ()).throw(OSError('suppressed')),nonzero)
        self.assertEqual(nonzero.code,7);self.assertTrue(nonzero.__notes__)

    def test_oversized_evidence_rejected_before_read(self):
        process,trace,path=self.run_child('print("synthetic")')
        process.wait(timeout=5);trace.finish()
        with path.open('ab') as output:output.truncate(LIMIT+1)
        with self.assertRaisesRegex(AssertionError,'Oversized'):verify(path)

    def test_oversized_receipt_rejected_before_read(self):
        process,trace,path=self.run_child('print("synthetic")')
        process.wait(timeout=5);trace.finish()
        with path.with_suffix('.receipt.json').open('ab') as output:output.truncate(65537)
        with self.assertRaisesRegex(AssertionError,'Oversized diagnostic receipt'):verify(path)

    def test_missing_and_tampered_evidence_rejected(self):
        process,trace,path=self.run_child('print("synthetic")')
        process.wait(timeout=5);trace.finish()
        path.write_bytes(b'tampered')
        with self.assertRaises(AssertionError):verify(path)
        path.with_suffix('.receipt.json').unlink()
        with self.assertRaises(FileNotFoundError):verify(path)


if __name__ == '__main__':unittest.main()
