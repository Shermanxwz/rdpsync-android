#!/usr/bin/env python3
"""Synthetic mutation tests for the *actual* evidence validator, no RDP host."""
import copy
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import uuid

from PIL import Image

import verify_reference_results as verify
from reference_client_process import finish_client

INITIAL_LOG = ('[freerdp_connect_begin]: resetting error state\n'
               'CONNECTION_STATE_FINALIZATION_CLIENT_FONT_MAP --> CONNECTION_STATE_ACTIVE\n'
               'DisplayControlCapsPdu: setting the window as resizable\n'
               '[rdp_client_connect_confirm_active]: new size 1280x720\n')
FAILURE_LOG = ('[rdp_handle_sc_flags][0x1]: [CONNECTION_STATE_FINALIZATION_CLIENT_SYNC] unexpected server message, '
               'expected flag FINALIZE_SC_SYNCHRONIZE_PDU [0x00000001] [have NO_FLAG_SET [0x00000000]]\n'
               '[update_recv]: State CONNECTION_STATE_ACTIVE (or higher) requested, but '
               'CONNECTION_STATE_FINALIZATION_CLIENT_SYNC found. Aborting.\n'
               'DATA_PDU_TYPE_UPDATE - update_recv() failed\n'
               'AutoReconnect not enabled, quitting.\n')


def write_json(path, value):
    path.write_text(json.dumps(value))


def image_proof(directory, name, width, height, clicks):
    image = Image.new('RGB', (width, height), (17, 34, 51))
    for xy, color in [((40, 40), (255, 0, 0)), ((136, 40), (0, 255, 0)), ((232, 40), (0, 0, 255)),
                      ((40, 140), (68, 221, 136) if clicks % 2 else (221, 68, 136))]:
        image.putpixel(xy, color)
    path = directory / name
    image.save(path)
    return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'width': width, 'height': height, 'clicks': clicks}


def make_case(directory, variant='apt-0.9.24', flush='TRUE', sync='FALSE'):
    directory.mkdir(parents=True, exist_ok=True)
    run_id = str(uuid.uuid4())
    old = variant == 'apt-0.9.24'
    initial = {'ready': True, 'width': 1024, 'height': 768, 'desktop_pid': 4321,
               'session_id': str(uuid.uuid4()), 'clicks': 0, 'resizes': [[1024, 768]]}
    final = dict(initial, width=1280, height=720, resizes=[[1024, 768], [1280, 720]], clicks=0 if old else 1)
    data = {'schema_version': 2, 'run_id': run_id, 'client_pid': 1234, 'passed': not old,
            'stage': 'resize' if old else 'complete', 'initial_server': initial, 'final_server': final,
            'resize_requested_monotonic': 12.5, 'client_alive_at_finish': not old, 'frames': {}}
    data['frames']['before.png'] = image_proof(directory, 'before.png', 1024, 768, 0)
    if old:
        data['error'] = 'Official client exited before comparison completed'
    else:
        data['resized_server'] = dict(final, clicks=0)
        data['clicks_before_post_resize'] = 0
        data['frames']['after.png'] = image_proof(directory, 'after.png', 1280, 720, 0)
        data['frames']['after-input.png'] = image_proof(directory, 'after-input.png', 1280, 720, 1)
    process = {'schema_version': 1, 'run_id': run_id, 'client_pid': 1234, 'case_exit': 1 if old else 0,
               'client_returncode': 130 if old else -15,
               'client_exit_origin': 'natural' if old else 'cleanup-signal', 'checker_timed_out': False}
    write_json(directory / 'result.json', data)
    write_json(directory / 'process-result.json', process)
    (directory / 'run-id.txt').write_text(run_id)
    (directory / 'exit-code.txt').write_text(str(process['case_exit']))
    (directory / 'source-revision.txt').write_text(verify.SOURCE_REVISION)
    (directory / 'version.txt').write_text('This is FreeRDP version 3.32.2 (97eff6d)')
    (directory / 'client.sha256').write_text('a' * 64 + '  xfreerdp\n')
    (directory / 'profile.txt').write_text(f'WaitForOutputBufferFlush={flush}\nSynchronousDynamicChannels={sync}\n'
                                           'SupportGraphicsPipeline=FALSE; RDPGFX channel not built.\n')
    (directory / 'client.log').write_text(INITIAL_LOG + (FAILURE_LOG if old else
        'CONNECTION_STATE_FINALIZATION_CLIENT_FONT_MAP --> CONNECTION_STATE_ACTIVE\n'))
    return data, process


class ReferenceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.data, self.process = make_case(self.root)

    def check(self, variant='apt-0.9.24', raw=1):
        return verify.verify_case(self.root, variant, raw, 'TRUE', 'FALSE')

    def change_data(self, operation):
        operation(self.data)
        write_json(self.root / 'result.json', self.data)

    def change_process(self, **values):
        self.process.update(values)
        write_json(self.root / 'process-result.json', self.process)

    def test_old_is_named_incompatibility_not_resize_success(self):
        result = self.check()
        self.assertEqual(result['outcome'], 'old interoperability failure reproduced')
        self.assertEqual(result['raw_case_exit'], 1)
        self.assertEqual(result['client_returncode'], 130)  # Not incorrectly forced to 1.

    def test_current_strict_positive(self):
        make_case(self.root, 'upstream-0.10.6.1')
        self.assertEqual(self.check('upstream-0.10.6.1', 0)['outcome'], 'current server live resize passed')

    def test_old_mutations_rejected(self):
        original = copy.deepcopy(self.data)
        mutations = {
            'setup': lambda d: d.update(stage='initial'),
            'TLS': lambda d: d.update(error='certificate not trusted'),
            'timeout': lambda d: d.update(error='Timed out during resize'),
            'client alive': lambda d: d.update(client_alive_at_finish=True),
            'unexpected pass': lambda d: d.update(passed=True),
            'no resize': lambda d: d['final_server'].update(width=1024, height=768),
            'UUID changed': lambda d: d['final_server'].update(session_id=str(uuid.uuid4())),
            'PID changed': lambda d: d['final_server'].update(desktop_pid=9999),
            'initial unready': lambda d: d['initial_server'].update(ready=False),
            'no initial': lambda d: d.pop('initial_server'),
            'stale run': lambda d: d.update(run_id=str(uuid.uuid4())),
            'wrong child': lambda d: d.update(client_pid=9999),
            'missing frame proof': lambda d: d.update(frames={}),
            'no resize request': lambda d: d.pop('resize_requested_monotonic'),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                self.data = copy.deepcopy(original)
                self.change_data(mutation)
                with self.assertRaises((verify.InvalidEvidence, KeyError)):
                    self.check()

    def test_process_mutations_rejected(self):
        original = dict(self.process)
        for values in ({'client_exit_origin': 'cleanup-signal'}, {'client_returncode': -15},
                       {'client_returncode': None}, {'checker_timed_out': True}, {'case_exit': 124},
                       {'wrapper_error': 'TimeoutExpired'}, {'run_id': str(uuid.uuid4())}):
            with self.subTest(values=values):
                self.process = dict(original)
                self.change_process(**values)
                with self.assertRaises(verify.InvalidEvidence):
                    self.check()

    def test_log_mutations_rejected(self):
        for log in (INITIAL_LOG, FAILURE_LOG, INITIAL_LOG + FAILURE_LOG.replace('NO_FLAG_SET', 'OTHER_FLAG'),
                    INITIAL_LOG + FAILURE_LOG.replace('DATA_PDU_TYPE_UPDATE', 'POINTER'),
                    INITIAL_LOG + FAILURE_LOG + '--> CONNECTION_STATE_ACTIVE\n',
                    INITIAL_LOG + FAILURE_LOG + '[freerdp_connect_begin]: reset\n',
                    INITIAL_LOG + FAILURE_LOG.replace('AutoReconnect not enabled, quitting.', 'reconnected')):
            with self.subTest(log=log):
                (self.root / 'client.log').write_text(log)
                with self.assertRaises(verify.InvalidEvidence):
                    self.check()

    def test_initial_rgb_mismatch_even_with_updated_hash_rejected(self):
        path = self.root / 'before.png'
        with Image.open(path) as original:
            image = original.copy()
        image.putpixel((40, 40), (255, 6, 23))
        image.save(path)
        self.change_data(lambda d: d['frames']['before.png'].update(sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        with self.assertRaises(verify.InvalidEvidence):
            self.check()

    def test_current_new_region_and_marker_required(self):
        for name, point in [('after.png', (1200, 600)), ('after-input.png', (40, 140))]:
            with self.subTest(name=name):
                data, _ = make_case(self.root, 'upstream-0.10.6.1')
                path = self.root / name
                with Image.open(path) as original:
                    image = original.copy()
                image.putpixel(point, (0, 0, 0))
                image.save(path)
                data['frames'][name]['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
                write_json(self.root / 'result.json', data)
                with self.assertRaises(verify.InvalidEvidence):
                    self.check('upstream-0.10.6.1', 0)

    def test_missing_evidence_rejected(self):
        (self.root / 'process-result.json').unlink()
        with self.assertRaises(OSError):
            self.check()

    def test_wrong_raw_status_rejected(self):
        with self.assertRaises(verify.InvalidEvidence):
            self.check(raw=0)

    def test_current_alive_and_post_input_count_required(self):
        for mutation in (lambda d: d.update(client_alive_at_finish=False),
                         lambda d: d['final_server'].update(clicks=0)):
            with self.subTest(mutation=mutation):
                self.data, _ = make_case(self.root, 'upstream-0.10.6.1')
                self.change_data(mutation)
                with self.assertRaises(verify.InvalidEvidence):
                    self.check('upstream-0.10.6.1', 0)

    def test_matrix_all_profiles_and_unique_runs_required(self):
        (self.root / 'server-variant.txt').write_text('apt-0.9.24')
        (self.root / 'active-xrdp-version.txt').write_text('xrdp 0.9.24\n')
        rows = ['flush\tsynchronous_dynamic_channels\texit_code']
        for flush, sync in sorted(verify.PROFILES):
            make_case(self.root / f'flush-{flush}-sync-{sync}', flush=flush, sync=sync)
            rows.append(f'{flush}\t{sync}\t1')
        (self.root / 'results.tsv').write_text('\n'.join(rows) + '\n')
        self.assertTrue(verify.verify_matrix(self.root)['validated'])
        (self.root / 'results.tsv').write_text('\n'.join(rows[:-1]) + '\n')
        with self.assertRaises(verify.InvalidEvidence):
            verify.verify_matrix(self.root)
        (self.root / 'results.tsv').write_text('\n'.join(rows) + '\n')
        first = self.root / 'flush-FALSE-sync-FALSE'
        second = self.root / 'flush-FALSE-sync-TRUE'
        run_id = (first / 'run-id.txt').read_text()
        (second / 'run-id.txt').write_text(run_id)
        for name in ('result.json', 'process-result.json'):
            data = json.loads((second / name).read_text())
            data['run_id'] = run_id
            write_json(second / name, data)
        with self.assertRaisesRegex(verify.InvalidEvidence, 'reused'):
            verify.verify_matrix(self.root)


class ActualProcessStatusTest(unittest.TestCase):
    def test_normal_high_exit_is_not_signal(self):
        child = subprocess.Popen([sys.executable, '-c', 'raise SystemExit(130)'])
        child.wait(timeout=5)
        self.assertEqual(finish_client(child), ('natural', 130))

    def test_external_signal_keeps_negative_code(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        child.terminate()
        child.wait(timeout=5)
        self.assertEqual(finish_client(child), ('natural', -15))

    def test_cleanup_signal_is_distinguished(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        self.assertEqual(finish_client(child), ('cleanup-signal', -15))


if __name__ == '__main__':
    unittest.main()
