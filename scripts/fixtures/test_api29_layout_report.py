#!/usr/bin/env python3
"""Synthetic validator mutations, not Android execution or screenshots."""
import json
from pathlib import Path
import tempfile
import unittest
from verify_api29_layout_report import CLASS, TEST, STORAGE_CLASS, STORAGE_TESTS, EXPECTED_CASES, verify


class Api29EvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / 'out'
        self.reports = Path(self.tmp.name) / 'xml'
        self.out.mkdir(); self.reports.mkdir()
        (self.out / 'instrumentation-start.marker').touch()
        for name, value in {'instrumentation-device-start-seconds.txt': '1000',
                            'report-device-mtime.txt': '1001', 'instrumentation-device-uptime.txt': '10.0 2.0',
                            'tested-sha.txt': 'a' * 40, 'production-tree.txt': 'b' * 40}.items():
            (self.out / name).write_text(value)
        self.xml = self.reports / 'TEST-probe.xml'
        self.complete_xml = '<testsuite tests="9" failures="0" errors="0" skipped="0">' + ''.join(
            f'<testcase classname="{class_name}" name="{name}"/>' for class_name, name in EXPECTED_CASES) + '</testsuite>'
        self.xml.write_text(self.complete_xml)
        self.folder = self.out / 'api29-fullscreen-probe'; self.folder.mkdir()
        self.report = dict(success=True, sdk=29, production_commit='a' * 40, production_tree='b' * 40,
                           samples=[dict(elapsed_ms=10001)], logical_callbacks=[dict(elapsed_ms=10002)],
                           legacy_edge_gesture=dict(visibility_asserted=False),
                           original_bars=dict(status=True, navigation=True), restored_bars=dict(status=True, navigation=True))
        for mode in ('portrait', 'landscape'):
            self.report[f'{mode}_ime_preserved_every_logical_callback'] = True
            self.report[f'{mode}_visible_samples'] = 10
            self.report[f'{mode}_ime_callback_count'] = 2
            for phase in ('01-hidden', '02-ime-visible', '03-restored', '04-bordered', '05-fullscreen-restored'):
                (self.folder / f'{mode}-{phase}.png').write_bytes(b'\x89PNG\r\n\x1a\n' + b'synthetic-validator-data' * 100)
        (self.folder / '08-exit-restored-bars.png').write_bytes(b'\x89PNG\r\n\x1a\n' + b'synthetic-validator-data' * 100)

    def validate(self):
        (self.folder / 'measurements.json').write_text(json.dumps(self.report))
        return verify(self.out, self.reports)

    def test_complete_synthetic_metadata_is_accepted(self):
        self.assertEqual(9, self.validate()['tests'])
        self.assertEqual({CLASS: 1, STORAGE_CLASS: 8}, self.validate()['classes'])

    def test_missing_storage_case_rejected(self):
        self.xml.write_text(self.complete_xml.replace(f'<testcase classname="{STORAGE_CLASS}" name="{STORAGE_TESTS[0]}"/>', ''))
        with self.assertRaises(AssertionError): self.validate()

    def test_duplicate_storage_case_rejected(self):
        case = f'<testcase classname="{STORAGE_CLASS}" name="{STORAGE_TESTS[0]}"/>'
        self.xml.write_text(self.complete_xml.replace('</testsuite>', case + '</testsuite>'))
        with self.assertRaises(AssertionError): self.validate()

    def test_unknown_storage_name_rejected(self):
        self.xml.write_text(self.complete_xml.replace(STORAGE_TESTS[0], 'unexecutedSubstitute'))
        with self.assertRaises(AssertionError): self.validate()

    def test_skipped_storage_case_rejected(self):
        case = f'<testcase classname="{STORAGE_CLASS}" name="{STORAGE_TESTS[0]}"/>'
        skipped = f'<testcase classname="{STORAGE_CLASS}" name="{STORAGE_TESTS[0]}"><skipped/></testcase>'
        self.xml.write_text(self.complete_xml.replace(case, skipped))
        with self.assertRaises(AssertionError): self.validate()

    def test_storage_failure_rejected(self):
        case = f'<testcase classname="{STORAGE_CLASS}" name="{STORAGE_TESTS[0]}"/>'
        failed = f'<testcase classname="{STORAGE_CLASS}" name="{STORAGE_TESTS[0]}"><failure>synthetic</failure></testcase>'
        self.xml.write_text(self.complete_xml.replace(case, failed))
        with self.assertRaises(AssertionError): self.validate()

    def test_wrong_source_rejected(self):
        self.report['production_tree'] = 'c' * 40
        with self.assertRaises(AssertionError): self.validate()

    def test_failure_rejected(self):
        self.report['success'] = False
        with self.assertRaises(AssertionError): self.validate()

    def test_skipped_class_rejected(self):
        self.xml.write_text(f'<testsuite skipped="1"><testcase classname="{CLASS}" name="{TEST}"><skipped/></testcase></testsuite>')
        with self.assertRaises(AssertionError): self.validate()

    def test_stale_callback_rejected(self):
        self.report['logical_callbacks'][0]['elapsed_ms'] = 9999
        with self.assertRaises(AssertionError): self.validate()

    def test_stale_report_rejected(self):
        (self.out / 'report-device-mtime.txt').write_text('999')
        with self.assertRaises(AssertionError): self.validate()

    def test_missing_real_ime_observations_rejected(self):
        self.report['landscape_visible_samples'] = 9
        with self.assertRaises(AssertionError): self.validate()

    def test_falsely_claimed_transient_reveal_rejected(self):
        self.report['legacy_edge_gesture']['visibility_asserted'] = True
        with self.assertRaises(AssertionError): self.validate()

    def test_missing_required_screenshot_rejected(self):
        (self.folder / 'portrait-02-ime-visible.png').unlink()
        with self.assertRaises(FileNotFoundError): self.validate()


if __name__ == '__main__':
    unittest.main()
