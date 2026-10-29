#!/usr/bin/env python3
"""Fail closed when the hosted Android gate omitted or skipped a required test."""
import collections
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

EXPECTED = {
    'com.rdp.sync.NativeRuntimeInstrumentedTest': 1,
    'com.rdp.sync.NativeTlsInstrumentedTest': 1,
    'com.rdp.sync.RdpFixtureInstrumentedTest': 1,
    'com.rdp.sync.security.CredentialStorageInstrumentedTest': 11,
}


def verify(results, marker):
    reports = sorted(results.rglob('TEST*.xml'))
    if not reports:
        raise ValueError('No genuine Android JUnit XML reports found')
    classes = collections.Counter()
    identities = set()
    for report in reports:
        if report.stat().st_mtime_ns < marker.stat().st_mtime_ns:
            raise ValueError(f'Stale Android result: {report.name}')
        root = ET.parse(report).getroot()
        for suite in root.iter('testsuite'):
            for status in ('failures', 'errors', 'skipped'):
                if int(suite.get(status, '0')) != 0:
                    raise ValueError(f'Android suite contains {status}')
        for case in root.iter('testcase'):
            if any(case.find(status) is not None for status in ('failure', 'error', 'skipped')):
                raise ValueError('Required Android test failed, errored or skipped')
            identity = (case.get('classname'), case.get('name'))
            if identity in identities:
                raise ValueError('Duplicate Android test result')
            identities.add(identity)
            classes[identity[0]] += 1
    if dict(classes) != EXPECTED or len(identities) != 14:
        raise ValueError(f'Expected all 14 required Android tests, observed {dict(classes)}')
    return {'tests': 14, 'failures': 0, 'errors': 0, 'skipped': 0,
            'classes': dict(classes), 'reports': [str(p.relative_to(results)) for p in reports]}


if __name__ == '__main__':
    result = verify(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
    pathlib.Path(sys.argv[3]).write_text(json.dumps(result, indent=2) + '\n')
    print('Verified all 14 required Android tests: zero failures, errors or skips')
