#!/usr/bin/env python3
"""Host-only synthetic adb read/ledger controls; never an Android runtime claim."""
import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('collector', Path(__file__).with_name('collect_android_evidence.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class CompositionPlanTests(unittest.TestCase):
    def test_declared_pinyin_frames_and_no_extra_gesture(self):
        value = dict(software_keyboard_declared_plan=dict(mode='pinyin-compose-commit-newline', gestures=['a', 'commit-Enter', 'newline-Enter']),
                     actual_software_keyboard_keys=[dict(stage=x) for x in ['type-a', 'commit-composition', 'newline']],
                     software_keyboard_composition_kept_local=True, software_keyboard_composition_committed_text='ab\na',
                     software_keyboard_committed_text='ab\na\n')
        self.assertEqual(len(composition_frames := m.composition_frames(value)), 4)
        self.assertEqual(len(set(composition_frames)), 4)
        value['actual_software_keyboard_keys'].append(dict(stage='newline'))
        with self.assertRaises(AssertionError): m.composition_frames(value)

    def test_incomplete_plan_keeps_best_effort_failure_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            collector = m.Collector(Path(directory), 0, best_effort=True)
            pulled = []
            def pull(folder, name):
                pulled.append(name)
                return b'{}'
            collector.pull = pull
            collector.collect('classic')
            self.assertIn('04d4-ime-key-failure-before-hide.png', pulled)
            self.assertIn('04d3-ime-after-enter.png', pulled)
            receipt = json.loads((Path(directory) / 'evidence-collection-best-effort.json').read_text())
            self.assertFalse(receipt['complete'])
            self.assertTrue(receipt['errors'])

    def test_declared_latin_has_one_enter(self):
        value = dict(software_keyboard_declared_plan=dict(mode='latin-direct-newline', gestures=['a', 'newline-Enter']),
                     actual_software_keyboard_keys=[dict(stage=x) for x in ['type-a', 'newline']])
        self.assertEqual(m.composition_frames(value), [])
        value['software_keyboard_declared_plan']['mode'] = 'unknown'
        with self.assertRaises(AssertionError): m.composition_frames(value)


def transfer():
    return dict(success=True, offer_survived_dialog_hide=True, collision_original_unchanged=True,
                route_back_revoked_offer=True, peer_to_saf_export={'synthetic': 'hash'}, synthetic_seed_cleanup=[{'removed': True}])


def diagnostic():
    return dict(success=True, diagnostic_only=True, saf_ui_covered=False, no_retained_native_offer=True,
                private_snapshot_removed_after_close=True, disconnected=True)


def drive():
    value={key: True for key in ('success','saf_ui_covered','actual_import_picker_cancel_no_import',
        'actual_export_picker_cancel_no_copy','consent_cancel_kept_off','actual_ro_denied_write',
        'actual_read_over_32mib','collision_original_unchanged','paused_revoke_and_stale_handle_denied',
        'source_originals_unchanged','persistence_after_explicit_reconnect','disconnected')}
    value.update(clipboard_used=False,windows_os_covered=False,actual_rw_operations={'fixture':True},
        peer_to_saf_export={'fixture':'hash'},stalls=[{'phase':p,'actual_buffered_plateau':True} for p in ('resume','cancel')],
        touch_attempts=[{'passed':True}]*2,synthetic_seed_cleanup=[{'removed':True}]*5)
    return value


class Reader:
    def __init__(self, files, failures=()):
        self.files=files; self.calls=[]; self.failures=list(failures); self.mtime=101; self.truncate=False

    def __call__(self, argv, **kwargs):
        self.calls.append(argv)
        if self.failures:
            error=self.failures.pop(0)
            if isinstance(error, Exception): raise error
            return subprocess.CompletedProcess(argv,1,b'',error)
        remote=argv[-1] if argv[1]=='shell' else argv[2]
        data=self.files.get(remote)
        if data is None:return subprocess.CompletedProcess(argv,1,b'',b'No such file or directory')
        if argv[1]=='shell':return subprocess.CompletedProcess(argv,0,f'{self.mtime}:{len(data)}'.encode(),b'')
        Path(argv[3]).write_bytes(data[:-1] if self.truncate else data)
        return subprocess.CompletedProcess(argv,0,b'pulled',b'')


class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.remote='/sdcard/Download/rdpsync-xrdp-transfer-evidence/measurements.json'
        self.files={self.remote:json.dumps(transfer()).encode()}

    def collector(self, reader, best=False):return m.Collector(Path(self.tmp.name),100,best,reader)

    def test_fresh_json(self):
        reader=Reader(self.files);self.collector(reader).pull('rdpsync-xrdp-transfer-evidence','measurements.json')
        self.assertTrue((Path(self.tmp.name)/'rdpsync-xrdp-transfer-evidence/measurements.json').exists())

    def test_stale_rejected(self):
        reader=Reader(self.files);reader.mtime=99
        with self.assertRaises(AssertionError):self.collector(reader).pull('rdpsync-xrdp-transfer-evidence','measurements.json')

    def test_missing_not_retried(self):
        reader=Reader({})
        with self.assertRaises(RuntimeError):self.collector(reader).pull('rdpsync-xrdp-transfer-evidence','measurements.json')
        self.assertEqual(len(reader.calls),1)

    def test_offline_read_retry_only(self):
        reader=Reader(self.files,[b'error: device offline'])
        self.collector(reader).pull('rdpsync-xrdp-transfer-evidence','measurements.json')
        self.assertTrue(all(c[1] in ('shell','pull') for c in reader.calls));self.assertEqual(len(reader.calls),3)

    def test_timeout_bounded_three(self):
        reader=Reader(self.files,[subprocess.TimeoutExpired(['adb'],10)]*3)
        with self.assertRaises(subprocess.TimeoutExpired):self.collector(reader).pull('rdpsync-xrdp-transfer-evidence','measurements.json')
        self.assertEqual(len(reader.calls),3)

    def test_partial_read_rejected(self):
        reader=Reader(self.files);reader.truncate=True
        with self.assertRaises(AssertionError):self.collector(reader).pull('rdpsync-xrdp-transfer-evidence','measurements.json')
        self.assertFalse((Path(self.tmp.name)/'rdpsync-xrdp-transfer-evidence/measurements.json').exists())

    def test_false_success_rejected(self):
        self.files[self.remote]=json.dumps(dict(transfer(),success=False)).encode()
        with self.assertRaises(AssertionError):self.collector(Reader(self.files)).pull('rdpsync-xrdp-transfer-evidence','measurements.json')

    def test_invalid_json_rejected(self):
        self.files[self.remote]=b'not JSON'
        with self.assertRaises(json.JSONDecodeError):self.collector(Reader(self.files)).pull('rdpsync-xrdp-transfer-evidence','measurements.json')

    def test_ledgers_before_images_and_missing_image_fails(self):
        self.files['/sdcard/Download/rdpsync-xrdp-transport-diagnostic/measurements.json']=json.dumps(diagnostic()).encode()
        self.files['/sdcard/Download/rdpsync-shared-drive-evidence/measurements.json']=json.dumps(drive()).encode()
        reader=Reader(self.files)
        with self.assertRaises(RuntimeError):self.collector(reader).collect('xrdp')
        pulls=[c[2] for c in reader.calls if c[1]=='pull'];self.assertEqual(len(pulls),3)
        self.assertTrue(all(p.endswith('/measurements.json') for p in pulls))
        report=json.loads((Path(self.tmp.name)/'evidence-collection.json').read_text());self.assertFalse(report['complete'])

    def test_best_effort_preserves_failed_ledger(self):
        self.files[self.remote]=json.dumps(dict(success=False,reason='synthetic')).encode()
        self.collector(Reader(self.files),True).collect('xrdp')
        report=json.loads((Path(self.tmp.name)/'evidence-collection-best-effort.json').read_text())
        self.assertFalse(report['complete']);self.assertTrue(report['best_effort'])

    def test_actual_drive_ledger(self):
        m.validate_ledger('rdpsync-shared-drive-evidence', drive())

    def test_drive_cannot_be_clipboard_or_synthetic_plateau(self):
        for key in ('clipboard_used','windows_os_covered'):
            with self.subTest(key=key), self.assertRaises(AssertionError):
                m.validate_ledger('rdpsync-shared-drive-evidence',dict(drive(),**{key:True}))
        value=drive();value['stalls'][0]['actual_buffered_plateau']=False
        with self.assertRaises(AssertionError):m.validate_ledger('rdpsync-shared-drive-evidence',value)

    def test_drive_missing_declared_phase_fails(self):
        value=drive();value['stalls']=value['stalls'][:1]
        with self.assertRaises(AssertionError):m.validate_ledger('rdpsync-shared-drive-evidence',value)

    def test_drive_failed_tap_or_missing_seed_cleanup_fails(self):
        for key in ('touch_attempts','synthetic_seed_cleanup'):
            with self.subTest(key=key), self.assertRaises(AssertionError):
                m.validate_ledger('rdpsync-shared-drive-evidence',dict(drive(),**{key:[]}))

    def test_frame_selection_error_receipt_is_not_complete(self):
        value={key:True for key in ('no_auth_before_first_trust','ime_tools_touch_and_software_typing_passed',
            'transient_system_bar_reveal_verified','same_pin_reconnect_without_prompt',
            'changed_certificate_blocked_until_explicit_reapproval','unsupported_resize_preserved_session')}
        value['touch_attempts']=[{'passed':True}]
        value['untrusted_certificate_phases']=[{'phase':'unexpected','system_immersive_coachmark':{'acknowledged':True}}]
        ime = 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'
        initial = dict(selected_ime=[ime], bound_ime=[ime], observer_cache=['1'],
                       service_instance=['Gboard@aaa'], method_identity=['Invoker@bbb'], dump_complete=True)
        prerequisite = dict(verified_before_activity=True, restored=True, original='0', requested='1',
            wrote_setting=True, ime=ime, initial_service=initial,
            consumption_samples=[dict(initial, observer_cache=['2'], consumed=True)],
            setup_elapsed_ms=1, ready_elapsed_ms=12, consumption_deadline_elapsed_ms=15, restore_elapsed_ms=20)
        names = ('ime-setting-initial-dump.txt', 'ime-setting-latest-dump.txt')
        data = b'synthetic dump fixture'
        prerequisite['dump_files'] = {name: dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest()) for name in names}
        prefix = '/sdcard/Download/rdpsync-evidence/'
        files = {prefix+'measurements.json': json.dumps(value).encode(),
                 prefix+'ime-setting-receipt.json': json.dumps(prerequisite).encode()}
        files.update({prefix+name: data for name in names})
        reader=Reader(files)
        with self.assertRaises(AssertionError):self.collector(reader).collect('classic')
        report=json.loads((Path(self.tmp.name)/'evidence-collection.json').read_text())
        self.assertFalse(report['complete']);self.assertTrue(report['errors'])


if __name__=='__main__':unittest.main()
