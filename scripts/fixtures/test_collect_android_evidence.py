#!/usr/bin/env python3
"""Host-only synthetic adb read/ledger controls; never an Android runtime claim."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('collector', Path(__file__).with_name('collect_android_evidence.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


def transfer():
    return dict(success=True, offer_survived_dialog_hide=True, collision_original_unchanged=True,
                route_back_revoked_offer=True, peer_to_saf_export={'synthetic': 'hash'}, synthetic_seed_cleanup=[{'removed': True}])


def diagnostic():
    return dict(success=True, diagnostic_only=True, saf_ui_covered=False, no_retained_native_offer=True,
                private_snapshot_removed_after_close=True, disconnected=True)


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
        reader=Reader(self.files)
        with self.assertRaises(RuntimeError):self.collector(reader).collect('xrdp')
        pulls=[c[2] for c in reader.calls if c[1]=='pull'];self.assertEqual(len(pulls),2)
        self.assertTrue(all(p.endswith('/measurements.json') for p in pulls))
        report=json.loads((Path(self.tmp.name)/'evidence-collection.json').read_text());self.assertFalse(report['complete'])

    def test_best_effort_preserves_failed_ledger(self):
        self.files[self.remote]=json.dumps(dict(success=False,reason='synthetic')).encode()
        self.collector(Reader(self.files),True).collect('xrdp')
        report=json.loads((Path(self.tmp.name)/'evidence-collection-best-effort.json').read_text())
        self.assertFalse(report['complete']);self.assertTrue(report['best_effort'])

    def test_frame_selection_error_receipt_is_not_complete(self):
        value={key:True for key in ('no_auth_before_first_trust','ime_tools_touch_and_software_typing_passed',
            'transient_system_bar_reveal_verified','same_pin_reconnect_without_prompt',
            'changed_certificate_blocked_until_explicit_reapproval','unsupported_resize_preserved_session')}
        value['touch_attempts']=[{'passed':True}]
        value['untrusted_certificate_phases']=[{'phase':'unexpected','system_immersive_coachmark':{'acknowledged':True}}]
        reader=Reader({'/sdcard/Download/rdpsync-evidence/measurements.json':json.dumps(value).encode()})
        with self.assertRaises(AssertionError):self.collector(reader).collect('classic')
        report=json.loads((Path(self.tmp.name)/'evidence-collection.json').read_text())
        self.assertFalse(report['complete']);self.assertTrue(report['errors'])


if __name__=='__main__':unittest.main()
