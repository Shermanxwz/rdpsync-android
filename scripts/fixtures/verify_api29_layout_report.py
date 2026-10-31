#!/usr/bin/env python3
"""Fail-closed exact API29 layout + Shared-space storage counts and fresh IME evidence."""
import collections
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

CLASS = 'com.rdp.sync.ui.rdp.SessionFullscreenApi29ProbeTest'
TEST = 'actualPre30FullscreenBarsAndImeKeepLogicalViewportStable'
STORAGE_CLASS = 'com.rdp.sync.sharedspace.SharedSpaceStorageInstrumentedTest'
STORAGE_TESTS = (
    'realBinderSafImportAndUniqueExportHaveExactHashesAndPersistAcrossManagerInstances',
    'existingAndOutsideProviderReturnsAreNotWrittenAndCommittedSourceSurvives',
    'unknownSizeIsBoundedVirtualFilesAndRevokedGrantsFailWithoutNewWorkspaceData',
    'cancellationAndUnicodeCaseAliasesDoNotOverwritePublishedData',
    'nativeReadLeaseBlocksLocalDeleteAndRenameWithoutDisconnectingSession',
    'pickerSelectionsPinTheObjectAndParentAcrossRemoteStyleNamespaceChanges',
    'closedPickerSelectionNeverReopensAReplacementAtTheSamePath',
    'dedicatedSharedSpaceStagedAccessorAllowsLargeSyntheticFileWithoutChangingClipboardCap',
)
EXPECTED_CASES = {(CLASS, TEST): 1, **{(STORAGE_CLASS, name): 1 for name in STORAGE_TESTS}}


def verify(out: Path, reports: Path):
    marker = (out / 'instrumentation-start.marker').stat().st_mtime_ns
    xmls = list(reports.rglob('TEST*.xml'))
    assert xmls, 'Missing actual Android XML'
    cases = collections.Counter()
    for path in xmls:
        assert path.stat().st_mtime_ns >= marker, 'Stale Android XML'
        root = ET.parse(path).getroot()
        for suite in root.iter('testsuite'):
            assert all(int(suite.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped')), 'Failed or skipped suite'
        for case in root.iter('testcase'):
            assert not any(case.find(k) is not None for k in ('failure', 'error', 'skipped'))
            cases[(case.get('classname'), case.get('name'))] += 1
    assert cases == EXPECTED_CASES, f'Wrong actual test set: {cases}'
    start_seconds = int((out / 'instrumentation-device-start-seconds.txt').read_text().strip())
    assert int((out / 'report-device-mtime.txt').read_text().strip()) >= start_seconds, 'Stale device report'
    start_ms = float((out / 'instrumentation-device-uptime.txt').read_text().split()[0]) * 1000
    report = json.loads((out / 'api29-fullscreen-probe/measurements.json').read_text())
    assert report.get('success') is True and report['sdk'] == 29, 'Actual API29 test did not pass'
    assert report['production_commit'] == (out / 'tested-sha.txt').read_text().strip()
    assert report['production_tree'] == (out / 'production-tree.txt').read_text().strip()
    for field in ('samples', 'logical_callbacks'):
        assert report[field], f'Missing actual {field}'
        assert all(row['elapsed_ms'] >= start_ms for row in report[field]), 'Pre-attempt observation'
    for mode in ('portrait', 'landscape'):
        assert report[f'{mode}_ime_preserved_every_logical_callback'] is True
        assert report[f'{mode}_visible_samples'] >= 10
        assert report[f'{mode}_ime_callback_count'] > 0
    assert report['legacy_edge_gesture']['visibility_asserted'] is False, 'Do not infer API29 transient reveal from flags'
    assert report['restored_bars']['status'] == report['original_bars']['status']
    assert report['restored_bars']['navigation'] == report['original_bars']['navigation']
    assert report['production_orientation_roundtrips'] == 2
    assert report['original_orientation_request'] == report['restored_orientation_request'] == 1
    assert report['session_key_replacement_restored_predecessor'] is True
    assert report['disposed_callback_ignored'] is True
    hashes = {}
    for name in ('portrait-01-hidden', 'portrait-02-ime-visible', 'portrait-03-restored',
                 'portrait-04-bordered', 'portrait-05-fullscreen-restored',
                 'landscape-01-hidden', 'landscape-02-ime-visible', 'landscape-03-restored',
                 'landscape-04-bordered', 'landscape-05-fullscreen-restored', '08-exit-restored-bars'):
        path = out / 'api29-fullscreen-probe' / (name + '.png')
        data = path.read_bytes()
        assert data.startswith(b'\x89PNG\r\n\x1a\n') and len(data) > 1024, 'Missing actual screenshot'
        hashes[path.name] = hashlib.sha256(data).hexdigest()
    return dict(tests=9, failures=0, errors=0, skipped=0, classes={CLASS: 1, STORAGE_CLASS: 8},
                production_commit=report['production_commit'], production_tree=report['production_tree'],
                actual_ime_every_callback=True, fresh=True, screenshot_sha256=hashes,
                api29_transient_bar_reveal_automatically_verified=False,
                scope='Local editor with production fullscreen/layout and actual Shared-space broker/SAF storage; no RDP server, RDPDR mount or remote input proof')


if __name__ == '__main__':
    out, reports = map(Path, sys.argv[1:])
    result = verify(out, reports)
    (out / 'verified-api29-tests.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
