#!/usr/bin/env python3
"""Synthetic mutation tests for the *actual* evidence validator, no RDP host."""
import copy
import ast
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
def phase_failure_log(phase='SYNC', interleaved=False):
    states = [verify.STATE_PREFIX + item[0] for item in verify.REACTIVATION_PHASES]
    index = [item[0] for item in verify.REACTIVATION_PHASES].index(phase)
    completed = '[12:00:00:100] [rdp_client_transition_to_state]: CONNECTION_STATE_FINALIZATION_FONT_LIST --> ' + states[0] + '\n'
    for prior in range(index):
        completed += ('[12:00:00:110] [rdp_finalize_set_flag]: [' + states[prior] + '] received flag ' +
                      verify.REACTIVATION_PHASES[prior][1] + ' [0x1]\n')
        completed += ('[12:00:00:110] [rdp_client_transition_to_state]: ' + states[prior] + ' --> ' + states[prior + 1] + '\n')
    if index == 0:
        completed += ('[12:00:00:120] [rdp_handle_sc_flags][0x1]: [' + states[0] + '] unexpected server message, '
                      'expected flag FINALIZE_SC_SYNCHRONIZE_PDU [0x00000001] [have NO_FLAG_SET [0x00000000]]\n')
    failure = ('[12:00:00:130] [update_recv]: State CONNECTION_STATE_ACTIVE (or higher) requested, but ' +
               states[index] + ' found. Aborting.\n'
               '[12:00:00:130] DATA_PDU_TYPE_UPDATE - update_recv() failed\n' +
               '[12:00:00:130] [rdp_recv_callback_int]: ' + states[index] + ' status STATE_RUN_FAILED [-1]\n')
    return (failure + completed if interleaved else completed + failure) + 'AutoReconnect not enabled, quitting.\n'


FAILURE_LOG = phase_failure_log()
OLD_SYNC_OUTCOME = 'old interoperability failure reproduced (CLIENT_SYNC)'


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
        data['error_observed_monotonic'] = 12.6
        data['post_failure_server_observation'] = {
            'started_monotonic': 12.7, 'ended_monotonic': 12.8, 'budget_seconds': 2.0,
            'observations': [{'observed_monotonic': 12.75, 'state': copy.deepcopy(final)}]}
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
    (directory / 'attempt-id.txt').write_text(run_id)
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
        self.assertEqual(result['outcome'], OLD_SYNC_OUTCOME)
        self.assertEqual(result['raw_case_exit'], 1)
        self.assertEqual(result['client_returncode'], 130)  # Not incorrectly forced to 1.

    def test_only_source_enumerated_phases_have_explicit_outcomes(self):
        for phase, awaited in verify.REACTIVATION_PHASES:
            with self.subTest(phase=phase):
                (self.root / 'client.log').write_text(INITIAL_LOG + phase_failure_log(phase))
                result = self.check()
                self.assertEqual(result['reactivation_guard_state'], verify.STATE_PREFIX + phase)
                self.assertEqual(result['awaited_server_pdu'], awaited)
                self.assertEqual(result['outcome'], 'old interoperability failure reproduced (CLIENT_' + phase + ')')

    def test_merged_stream_order_uses_actual_record_timestamps(self):
        log = INITIAL_LOG + phase_failure_log('FONT_MAP', interleaved=True)
        log = log.replace('[12:00:00:130] [update_recv]',
                          '[12:00:00:110] [DEBUG][[12:00:00:130] [update_recv]')
        (self.root / 'client.log').write_text(log)
        result = self.check()
        self.assertEqual(result['awaited_server_pdu'], 'FINALIZE_SC_FONT_MAP_PDU')

    def test_sync_warning_before_buffered_resize_marker_uses_bounded_chronology(self):
        phase = phase_failure_log()
        warning = next(line for line in phase.splitlines(True) if '[rdp_handle_sc_flags]' in line)
        # Reproduce the original raw archive: stderr appears inside an earlier
        # stdout record, ahead of the buffered resize marker. Its actual clock
        # lies after CLIENT_SYNC entry and before UPDATE rejection.
        prefix = INITIAL_LOG.replace('[rdp_client_connect_confirm_active]',
                                     '[12:00:00:090] [DEBUG][partial' + warning +
                                     '[rdp_client_connect_confirm_active]')
        (self.root / 'client.log').write_text(prefix + phase.replace(warning, ''))
        self.assertEqual(self.check()['outcome'], OLD_SYNC_OUTCOME)

    def test_displaced_sync_warning_cannot_use_stale_future_or_wrong_evidence(self):
        phase = phase_failure_log()
        warning = next(line for line in phase.splitlines(True) if '[rdp_handle_sc_flags]' in line)
        for invalid in (
            '',
            warning.replace('[12:00:00:120]', '[12:00:00:090]'),  # Before this reactivation.
            warning.replace('[12:00:00:120]', '[11:59:00:120]'),  # Outside bounded attempt.
            warning.replace('[12:00:00:120]', '[12:00:00:140]'),  # After UPDATE rejection.
            warning.replace('[12:00:00:120]', ''),
            warning.replace('NO_FLAG_SET', 'FINALIZE_SC_SYNCHRONIZE_PDU'),
            warning.replace('[rdp_handle_sc_flags]', '[other_callback]'),
            warning.replace('FINALIZATION_CLIENT_SYNC', 'FINALIZATION_CLIENT_FONT_MAP'),
        ):
            with self.subTest(warning=invalid):
                prefix = INITIAL_LOG.replace('[rdp_client_connect_confirm_active]',
                                             invalid + '[rdp_client_connect_confirm_active]')
                (self.root / 'client.log').write_text(prefix + phase.replace(warning, ''))
                with self.assertRaises(verify.InvalidEvidence):
                    self.check()

    def test_later_phase_requires_every_prior_flag_and_transition(self):
        log = phase_failure_log('FONT_MAP')
        records = [line for line in log.splitlines() if 'received flag' in line or ' --> ' in line]
        for record in records:
            with self.subTest(record=record):
                (self.root / 'client.log').write_text(INITIAL_LOG + log.replace(record + '\n', ''))
                with self.assertRaises(verify.InvalidEvidence):
                    self.check()

    def test_finalization_state_and_error_chain_mutations_rejected(self):
        log = phase_failure_log('FONT_MAP')
        later_flag = ('[12:00:00:120] [rdp_finalize_set_flag]: [CONNECTION_STATE_FINALIZATION_CLIENT_FONT_MAP] '
                      'received flag FINALIZE_SC_FONT_MAP_PDU [0x8]\n')
        mutations = [
            log.replace('CONNECTION_STATE_FINALIZATION_CLIENT_FONT_MAP found.', 'CONNECTION_STATE_CAPABILITIES_EXCHANGE_CONFIRM_ACTIVE found.'),
            log.replace('CONNECTION_STATE_FINALIZATION_CLIENT_FONT_MAP found.', 'CONNECTION_STATE_FINALIZATION_FONT_LIST found.'),
            log.replace('CONNECTION_STATE_FINALIZATION_CLIENT_FONT_MAP found.', 'CONNECTION_STATE_FINALIZATION_CLIENT_UNKNOWN found.'),
            log.replace('[update_recv]', '[unrelated_callback]'),
            log.replace('DATA_PDU_TYPE_UPDATE - update_recv() failed', 'DATA_PDU_TYPE_POINTER failed'),
            log.replace('status STATE_RUN_FAILED [-1]', 'status OTHER_FAILURE [-1]'),
            log.replace('[12:00:00:110]', '[12:00:00:140]'),
            log.replace('[12:00:00:110]', '[11:59:00:110]'),
            log.replace('[12:00:00:110]', ''),
            log.replace('[12:00:00:110] [rdp_finalize_set_flag]', '[12:00:00:115] [rdp_finalize_set_flag]', 1),
            log + later_flag,
            log + '--> CONNECTION_STATE_ACTIVE\n',
            log + '[freerdp_connect_begin]: reset\n',
            log + log,
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                (self.root / 'client.log').write_text(INITIAL_LOG + mutation)
                with self.assertRaises(verify.InvalidEvidence):
                    self.check()

    def test_current_server_cannot_use_any_negative_phase(self):
        for phase, _ in verify.REACTIVATION_PHASES:
            with self.subTest(phase=phase):
                (self.root / 'client.log').write_text(INITIAL_LOG + phase_failure_log(phase))
                with self.assertRaises(verify.InvalidEvidence):
                    self.check('upstream-0.10.6.1', 1)

    def test_current_strict_positive(self):
        make_case(self.root, 'upstream-0.10.6.1')
        self.assertEqual(self.check('upstream-0.10.6.1', 0)['outcome'], 'current server live resize passed')

    def test_real_old_positive_is_not_forced_into_negative_class(self):
        make_case(self.root, 'upstream-0.10.6.1')
        self.assertEqual(self.check('apt-0.9.24', 0)['outcome'], 'old server live resize passed in this attempt')

    def test_immediate_snapshot_preserved_while_fresh_observation_settles(self):
        self.change_data(lambda d: d.update(final_server=copy.deepcopy(d['initial_server'])))
        self.assertEqual(self.check()['outcome'], OLD_SYNC_OUTCOME)
        self.assertEqual(json.loads((self.root / 'result.json').read_text())['final_server']['width'], 1024)

    def test_unobserved_target_and_stale_or_unbounded_observation_rejected(self):
        original = copy.deepcopy(self.data)
        mutations = [
            lambda d: d.pop('post_failure_server_observation'),
            lambda d: d['post_failure_server_observation'].update(ended_monotonic=99),
            lambda d: (d['post_failure_server_observation'].update(started_monotonic=100, ended_monotonic=100.2),
                       d['post_failure_server_observation']['observations'][0].update(observed_monotonic=100.1)),
            lambda d: d['post_failure_server_observation']['observations'][0].update(observed_monotonic=1),
            lambda d: d['post_failure_server_observation']['observations'][0].update(state=copy.deepcopy(d['initial_server'])),
            lambda d: d['post_failure_server_observation']['observations'][0]['state'].update(session_id=str(uuid.uuid4())),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.data = copy.deepcopy(original)
                self.change_data(mutate)
                with self.assertRaises(verify.InvalidEvidence):
                    self.check()

    def test_old_mutations_rejected(self):
        original = copy.deepcopy(self.data)
        mutations = {
            'setup': lambda d: d.update(stage='initial'),
            'TLS': lambda d: d.update(error='certificate not trusted'),
            'timeout': lambda d: d.update(error='Timed out during resize'),
            'client alive': lambda d: d.update(client_alive_at_finish=True),
            'unexpected pass': lambda d: d.update(passed=True),
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
        for values in ({'client_exit_origin': 'cleanup-signal'}, {'client_returncode': -15}, {'client_returncode': 0},
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

    def test_new_preflight_failure_cannot_reuse_old_complete_artifacts(self):
        # Everything previously valid remains, including raw case exit 1. Only
        # the matrix-issued attempt changed before a simulated early failure.
        (self.root / 'attempt-id.txt').write_text(str(uuid.uuid4()))
        with self.assertRaisesRegex(verify.InvalidEvidence, 'stale'):
            self.check()

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
        (second / 'attempt-id.txt').write_text(run_id)
        for name in ('result.json', 'process-result.json'):
            data = json.loads((second / name).read_text())
            data['run_id'] = run_id
            write_json(second / name, data)
        with self.assertRaisesRegex(verify.InvalidEvidence, 'reused'):
            verify.verify_matrix(self.root)

    def test_mixed_old_matrix_counts_real_outcomes_separately(self):
        (self.root / 'server-variant.txt').write_text('apt-0.9.24')
        (self.root / 'active-xrdp-version.txt').write_text('xrdp 0.9.24\n')
        rows = ['flush\tsynchronous_dynamic_channels\texit_code']
        for index, (flush, sync) in enumerate(sorted(verify.PROFILES)):
            positive = index == 0
            make_case(self.root / f'flush-{flush}-sync-{sync}',
                      'upstream-0.10.6.1' if positive else 'apt-0.9.24', flush, sync)
            rows.append(f'{flush}\t{sync}\t{0 if positive else 1}')
        (self.root / 'results.tsv').write_text('\n'.join(rows) + '\n')
        result = verify.verify_matrix(self.root)
        self.assertEqual(result['live_resize_positive_cases'], 1)
        self.assertEqual(result['reactivation_failure_cases'], 3)


class ActualObservationFunctionTest(unittest.TestCase):
    def observer(self, states):
        # Execute the exact production function without importing the checker
        # main, replacing only loopback I/O and the clock for deterministic time.
        source = pathlib.Path(__file__).with_name('reference_resize_check.py')
        function = next(n for n in ast.parse(source.read_text()).body
                        if isinstance(n, ast.FunctionDef) and n.name == 'observe_failure_state')

        class Clock:
            now = 0.0
            def monotonic(self): return self.now
            def sleep(self, seconds): self.now += seconds

        clock = Clock()
        calls = []
        def fetch(timeout):
            self.assertGreater(timeout, 0)
            self.assertLessEqual(timeout, 0.4)
            clock.now += min(timeout, 0.01)
            value = states[min(len(calls), len(states) - 1)]
            calls.append(value)
            return copy.deepcopy(value)
        namespace = {'time': clock, 'state': fetch}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(source), 'exec'), namespace)
        return namespace['observe_failure_state'], calls

    def test_poll_records_transition_without_inventing_it(self):
        initial = {'session_id': 'same', 'desktop_pid': 10, 'width': 1024, 'height': 768}
        resized = dict(initial, width=1280, height=720)
        observer, calls = self.observer([initial, resized])
        result = observer(initial)
        self.assertEqual([o['state']['width'] for o in result['observations']], [1024, 1280])
        self.assertEqual(len(calls), 2)
        self.assertLessEqual(result['ended_monotonic'] - result['started_monotonic'], 2.0)

    def test_poll_stops_at_budget_without_target_inference(self):
        initial = {'session_id': 'same', 'desktop_pid': 10, 'width': 1024, 'height': 768}
        observer, _ = self.observer([initial])
        result = observer(initial)
        self.assertTrue(all(o['state']['width'] == 1024 for o in result['observations']))
        self.assertLessEqual(result['ended_monotonic'] - result['started_monotonic'], 2.0)


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
