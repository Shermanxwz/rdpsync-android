#!/usr/bin/env python3
"""Finite final-UI ledger-first collector; no instrumentation/action retries."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

from collect_android_evidence import Collector

DIRECTORY = 'rdpsync-product-ui'
CLASS = 'com.rdp.sync.ProductUiInstrumentedTest'
# Exact screenshot suffix -> rendered state contract in ProductUiInstrumentedTest.
STATE_OVERRIDES = {
    'delete-confirmation': 'detail', 'preconnect-drive-consent': 'detail',
    'editor-validation': 'editor', 'save-busy-action': 'saving',
    'settings-options': 'settings', 'session-menu-open': 'menu',
    'session-menu-shortcuts': 'menu', 'space-invalid-name': 'space_name_new',
    'space-busy-error': 'space_busy', 'drive-write-consent': 'drive_consent',
    'drive-stop-confirmation': 'drive_accepted', 'plaintext-warning': 'sync',
    'previous-fingerprint': 'changed',
}


def state_tag_for(suffix):
    return 'product-ui-' + STATE_OVERRIDES.get(suffix, suffix)



def methods_for(layout):
    return ['expandedWidth'] if layout == 'expanded' else [
        'englishLight', 'englishDark', 'chineseLight', 'chineseDark', 'narrowLargeText']


def prefix_for(method, spec):
    width = 600 if method == 'expandedWidth' else 320
    return f"{spec['locale']}-{'dark' if spec['dark'] else 'light'}-{width}dp-{spec['font_scale']:.1f}x"


def validate(value, method, spec, commit, started):
    prefix = prefix_for(method, spec)
    expected = {f'{prefix}-{suffix}.png' for suffix in spec['suffixes']}
    assert value['success'] is True and value['method'] == method
    assert value['source_commit'] == commit and value['package'] == 'com.rdp.sync.preview3'
    assert value['sdk'] == 35 and value['synthetic_ui_only'] is True
    assert value['credential_screenshots'] is False and value['physical_foldable_test'] is False
    assert value['locale'] == spec['locale'] and value['dark'] == spec['dark']
    assert value['font_scale'] == spec['font_scale']
    assert value['device_width_dp'] >= 600 if method == 'expandedWidth' else value['device_width_dp'] == 320
    assert value['started_at_epoch_ms'] >= started * 1000
    assert set(value['expected_files']) == expected and len(value['expected_files']) == len(expected)
    rows = value['captured_files']
    assert len(rows) == len(expected) and {row['filename'] for row in rows} == expected
    for row in rows:
        suffix = row['filename'][len(prefix) + 1:-4]
        assert type(row['draw_barriers']) is int and 1 <= row['draw_barriers'] <= 5
        assert row['active_state_tag'] == state_tag_for(suffix)
        assert re.fullmatch('[0-9a-f]{64}', row['sha256'])
        assert row['captured_at_epoch_ms'] >= value['started_at_epoch_ms']
    return expected


class UiCollector(Collector):
    def pull_ui(self, filename):
        assert re.fullmatch(r'(en|zh)-(light|dark)-(320|600)dp-(1\.0|1\.5)x(?:-[A-Za-z0-9_.-]+)?\.(json|png)', filename)
        remote = f'/sdcard/Download/{DIRECTORY}/{filename}'
        mtime, size = map(int, self.read(['shell', 'stat', '-c', '%Y:%s', remote]).strip().split(b':'))
        assert mtime >= self.started and 0 < size <= 16 * 1024 * 1024
        destination = self.out / DIRECTORY / filename
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(destination.suffix + '.part')
        try:
            self.read(['pull', remote, str(temporary)])
            data = temporary.read_bytes()
            assert len(data) == size
            if filename.endswith('.json'):
                json.loads(data)
            else:
                assert data.startswith(b'\x89PNG\r\n\x1a\n')
            temporary.replace(destination)
            self.records.append({'file': f'{DIRECTORY}/{filename}', 'device_mtime': mtime,
                                 'bytes': size, 'sha256': hashlib.sha256(data).hexdigest()})
            return data
        finally:
            temporary.unlink(missing_ok=True)

    def collect_ui(self, layout, commit, plan):
        errors, values = [], {}
        methods = methods_for(layout)
        try:
            for method in methods:
                try:
                    spec = plan['methods'][method]
                    value = json.loads(self.pull_ui(prefix_for(method, spec) + '.json'))
                    values[method] = value
                    if not self.best_effort:
                        validate(value, method, spec, commit, self.started)
                except Exception as error:
                    errors.append(f'{method}: {error}')
                    if not self.best_effort: raise
            # All required tiny outcome ledgers are durable before any large image.
            for method, value in values.items():
                rows = value.get('captured_files', [])
                assert len(rows) <= 64
                prefix = prefix_for(method, plan['methods'][method])
                for row in rows:
                    try:
                        assert row['filename'].startswith(prefix + '-')
                        data = self.pull_ui(row['filename'])
                        assert hashlib.sha256(data).hexdigest() == row['sha256']
                        dimensions = struct.unpack('>II', data[16:24])
                        assert dimensions == ((1280, 1920) if layout == 'expanded' else (960, 1920)), dimensions
                    except Exception as error:
                        errors.append(f"{row.get('filename')}: {error}")
                        if not self.best_effort: raise
        except Exception as error:
            if str(error) not in errors: errors.append(str(error))
            if not self.best_effort: raise
        finally:
            self.out.mkdir(parents=True, exist_ok=True)
            (self.out / 'ui-evidence-collection.json').write_text(json.dumps({
                'complete': not errors, 'best_effort': self.best_effort, 'errors': errors,
                'layout': layout, 'source_commit': commit, 'after_only': True,
                'methods': methods, 'reads_and_files': self.records}, indent=2))


def verify_xml(out, layout):
    base = Path('app/build/outputs/androidTest-results/connected/debug')
    marker = out / 'instrumentation-start.marker'
    reports = list(base.rglob('TEST*.xml')); assert reports
    found = set()
    for report in reports:
        assert report.stat().st_mtime_ns >= marker.stat().st_mtime_ns
        root = ET.parse(report).getroot()
        for suite in root.iter('testsuite'):
            assert all(int(suite.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped'))
        for case in root.iter('testcase'):
            assert case.get('classname') == CLASS
            assert not any(case.find(k) is not None for k in ('failure', 'error', 'skipped'))
            name = case.get('name'); assert name not in found; found.add(name)
    assert found == set(methods_for(layout)), found
    (out / 'verified-ui-tests.json').write_text(json.dumps({
        'tests': len(found), 'failures': 0, 'errors': 0, 'skipped': 0,
        'class': CLASS, 'methods': sorted(found), 'layout': layout, 'after_only': True}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('layout', choices=['phone', 'expanded'])
    parser.add_argument('out', type=Path)
    parser.add_argument('--best-effort', action='store_true')
    args = parser.parse_args()
    plan = json.loads(Path('scripts/fixtures/product-ui-capture-plan.json').read_text())
    assert plan['schema'] == 1 and plan['after_only'] is True
    commit = (args.out / 'source-commit.txt').read_text().strip()
    assert re.fullmatch('[0-9a-f]{40}', commit)
    started = int((args.out / 'evidence-start-device-seconds.txt').read_text().strip())
    UiCollector(args.out, started, args.best_effort).collect_ui(args.layout, commit, plan)
    if not args.best_effort: verify_xml(args.out, args.layout)
