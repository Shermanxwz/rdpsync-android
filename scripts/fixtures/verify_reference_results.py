#!/usr/bin/env python3
"""Strict positive resize and explicitly named old-server characterization.

Never turns arbitrary failures into passes. Raw harness and child statuses stay
in their original artifacts; the negative outcome means incompatibility was
reproduced, NOT that old-server live resizing works.
"""
import argparse
import csv
import hashlib
import json
import math
import pathlib
import re
import sys
import uuid

from PIL import Image

SOURCE_REVISION = '97eff6ddfbbfaa8e42a126d362686bd001ff8bdc'
PROFILES = {(f, s) for f in ('TRUE', 'FALSE') for s in ('FALSE', 'TRUE')}


class InvalidEvidence(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidEvidence(message)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def valid_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError):
        return False


def load(path):
    value = json.loads(path.read_text())
    require(isinstance(value, dict), f'{path.name} must contain an object')
    return value


def server_state(state, width, height):
    require(isinstance(state, dict), 'missing server state')
    require(state.get('ready') is True, 'server desktop was not ready')
    require((state.get('width'), state.get('height')) == (width, height), 'wrong server dimensions')
    require(valid_uuid(state.get('session_id')), 'missing/invalid server session UUID')
    require(integer(state.get('desktop_pid'), 1), 'missing/invalid server desktop PID')
    require(integer(state.get('clicks')), 'invalid server click count')


def same_session(initial, later):
    require((initial['session_id'], initial['desktop_pid']) ==
            (later['session_id'], later['desktop_pid']), 'server UUID/PID changed')


def observed_resize_after_failure(data, initial):
    record = data.get('post_failure_server_observation')
    require(isinstance(record, dict), 'fresh bounded post-failure observation missing')
    start, end = record.get('started_monotonic'), record.get('ended_monotonic')
    error_at = data.get('error_observed_monotonic')
    require(all(type(t) in (int, float) and math.isfinite(t) for t in (start, end, error_at)),
            'invalid observation timestamps')
    require(data['resize_requested_monotonic'] <= error_at <= start <= error_at + 3.0 and
            start <= end <= start + 3.0 and
            record.get('budget_seconds') == 2.0, 'observation freshness/bound invalid')
    # The two-second poll has a small scheduling allowance, not an unbounded
    # retry; all network calls use the remaining budget. No target is inferred.
    observations = record.get('observations')
    require(isinstance(observations, list) and 1 <= len(observations) <= 32, 'missing/bad observation sequence')
    previous = start
    resized = None
    for observation in observations:
        require(isinstance(observation, dict), 'malformed observation')
        at = observation.get('observed_monotonic')
        require(type(at) in (int, float) and math.isfinite(at) and previous <= at <= end,
                'stale/out-of-order observation')
        previous = at
        actual = observation.get('state')
        require(isinstance(actual, dict), 'server observation failed')
        size = (actual.get('width'), actual.get('height'))
        require(size in ((1024, 768), (1280, 720)), 'unexpected observed server dimensions')
        server_state(actual, *size)
        same_session(initial, actual)
        if size == (1280, 720):
            resized = actual
    require(resized is not None, 'requested server resize was never actually observed')
    require([1024, 768] in resized.get('resizes', []) and [1280, 720] in resized.get('resizes', []),
            'observed server resize history missing')
    return resized


def frame(directory, data, name, width, height, clicks):
    frames = data.get('frames')
    require(isinstance(frames, dict), 'frame verification metadata missing')
    proof = frames.get(name)
    require(isinstance(proof, dict), f'{name} verification metadata missing')
    path = directory / name
    require(proof.get('sha256') == hashlib.sha256(path.read_bytes()).hexdigest(),
            f'{name} missing, stale or changed since verification')
    require((proof.get('width'), proof.get('height'), proof.get('clicks')) ==
            (width, height, clicks), f'{name} frame metadata mismatch')
    with Image.open(path) as source:
        image = source.convert('RGB')
    require(image.size == (width, height), f'{name} wrong image dimensions')
    probes = [((40, 40), (255, 0, 0)), ((136, 40), (0, 255, 0)), ((232, 40), (0, 0, 255)),
              ((40, 140), (68, 221, 136) if clicks % 2 else (221, 68, 136))]
    if width > 1024:
        probes.append(((1200, 600), (17, 34, 51)))
    for point, expected in probes:
        require(image.getpixel(point) == expected, f'{name} exact RGB mismatch at {point}')


def verify_case(directory, variant, raw_exit, flush, sync):
    data = load(directory / 'result.json')
    process = load(directory / 'process-result.json')
    run_id = (directory / 'run-id.txt').read_text().strip()
    attempt_id = (directory / 'attempt-id.txt').read_text().strip()
    require(valid_uuid(run_id) and attempt_id == run_id and
            data.get('run_id') == run_id and process.get('run_id') == run_id,
            'missing/stale per-run identity')
    require(data.get('schema_version') == 2 and process.get('schema_version') == 1, 'unsupported evidence schema')
    require(integer(data.get('client_pid'), 1) and data['client_pid'] == process.get('client_pid'),
            'client process identity mismatch')
    require(process.get('checker_timed_out') is False and 'wrapper_error' not in process, 'wrapper timeout/error')
    require(type(process.get('client_returncode')) is int, 'actual child return code was not recorded')
    require(raw_exit == int((directory / 'exit-code.txt').read_text()) == process.get('case_exit'),
            'raw harness/observer statuses disagree')
    require((directory / 'source-revision.txt').read_text().strip() == SOURCE_REVISION, 'untested client source')
    require('FreeRDP version 3.32.2 ' in (directory / 'version.txt').read_text(), 'untested client version')
    binary_hash = (directory / 'client.sha256').read_text().split()[0]
    require(re.fullmatch('[0-9a-f]{64}', binary_hash) is not None, 'missing executable hash')
    profile = (directory / 'profile.txt').read_text()
    require(f'WaitForOutputBufferFlush={flush}\n' in profile and
            f'SynchronousDynamicChannels={sync}\n' in profile, 'profile settings mismatch')
    require('SupportGraphicsPipeline=FALSE; RDPGFX channel not built.' in profile, 'classic-GDI profile missing')
    initial, final = data.get('initial_server'), data.get('final_server')
    server_state(initial, 1024, 768)
    require(isinstance(final, dict), 'immediate/final server snapshot missing')
    final_size = (final.get('width'), final.get('height'))
    require(final_size in ((1024, 768), (1280, 720)), 'wrong immediate/final server dimensions')
    server_state(final, *final_size)
    same_session(initial, final)
    requested = data.get('resize_requested_monotonic')
    require(type(requested) in (float, int) and math.isfinite(requested) and requested > 0,
            'resize request timestamp missing')
    frame(directory, data, 'before.png', 1024, 768, initial['clicks'])
    log = (directory / 'client.log').read_text(errors='replace')
    require(log.count('[freerdp_connect_begin]') == 1, 'missing initial connection or unexpected reconnect')
    resize = log.find('new size 1280x720')
    require(resize >= 0 and '--> CONNECTION_STATE_ACTIVE' in log[:resize], 'no initially active session before resize')
    require('DisplayControlCapsPdu: setting the window as resizable' in log[:resize], 'DISP caps were not ready')
    require(variant in ('apt-0.9.24', 'upstream-0.10.6.1'), 'unknown server variant')
    observed = final
    if variant == 'apt-0.9.24' and raw_exit == 1:
        require(raw_exit == 1 and data.get('passed') is False and data.get('stage') == 'resize',
                'old control must fail the raw resize case with exit 1')
        require(data.get('error') == 'Official client exited before comparison completed', 'unexpected old-server failure')
        require(data.get('client_alive_at_finish') is False and process.get('client_exit_origin') == 'natural',
                'client did not terminate naturally before cleanup')
        require(process['client_returncode'] >= 0, 'child was externally terminated by a signal')
        observed = observed_resize_after_failure(data, initial)
        warning = re.search(r'\[rdp_handle_sc_flags\][^\n]*\[CONNECTION_STATE_FINALIZATION_CLIENT_SYNC\]'
                            r'[^\n]*expected flag FINALIZE_SC_SYNCHRONIZE_PDU[^\n]*NO_FLAG_SET', log[resize:])
        require(warning is not None, 'missing exact reactivation synchronization failure')
        suffix = log[resize + warning.start():]
        update = suffix.find('State CONNECTION_STATE_ACTIVE (or higher) requested, but '
                             'CONNECTION_STATE_FINALIZATION_CLIENT_SYNC found. Aborting.')
        require(update >= 0 and 'DATA_PDU_TYPE_UPDATE - update_recv() failed' in suffix[update:],
                'missing exact non-ACTIVE UPDATE rejection')
        require('--> CONNECTION_STATE_ACTIVE' not in log[resize:], 'server later completed reactivation')
        require('AutoReconnect not enabled, quitting.' in suffix, 'missing explicit no-reconnect termination')
        outcome = 'old interoperability failure reproduced'
    else:
        require(raw_exit == 0 and data.get('passed') is True and data.get('stage') == 'complete' and
                data.get('client_alive_at_finish') is True and process.get('client_exit_origin') == 'cleanup-signal',
                'server did not complete a live positive resize')
        server_state(final, 1280, 720)
        require([1024, 768] in final.get('resizes', []) and [1280, 720] in final.get('resizes', []),
                'server resize history missing')
        require('error' not in data and '--> CONNECTION_STATE_ACTIVE' in log[resize:], 'reactivation incomplete')
        require('DATA_PDU_TYPE_UPDATE - update_recv() failed' not in log, 'positive case contains protocol failure')
        resized = data.get('resized_server')
        server_state(resized, 1280, 720)
        same_session(initial, resized)
        before = data.get('clicks_before_post_resize')
        require(integer(before) and before >= initial['clicks'] and final['clicks'] > before,
                'post-resize input was not observed')
        frame(directory, data, 'after.png', 1280, 720, resized['clicks'])
        frame(directory, data, 'after-input.png', 1280, 720, final['clicks'])
        outcome = ('current server live resize passed' if variant == 'upstream-0.10.6.1'
                   else 'old server live resize passed in this attempt')
    return {'outcome': outcome, 'raw_case_exit': raw_exit, 'client_returncode': process['client_returncode'],
            'client_exit_origin': process['client_exit_origin'], 'run_id': run_id, 'client_sha256': binary_hash,
            'immediate_or_final_server_size': list(final_size),
            'verified_observed_server_size': [observed['width'], observed['height']]}


def verify_matrix(root):
    variant = (root / 'server-variant.txt').read_text().strip()
    require(variant in ('apt-0.9.24', 'upstream-0.10.6.1'), 'unknown server variant')
    version = '0.9.24' if variant == 'apt-0.9.24' else '0.10.6.1'
    require((root / 'active-xrdp-version.txt').read_text().splitlines()[0] == 'xrdp ' + version,
            'unexpected actual server version')
    with (root / 'results.tsv').open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    require(len(rows) == 4, 'exactly four raw cases are required')
    seen, identities, hashes, cases = set(), set(), set(), []
    for row in rows:
        pair = (row.get('flush'), row.get('synchronous_dynamic_channels'))
        require(pair in PROFILES and pair not in seen, 'missing/duplicate/unknown profile')
        seen.add(pair)
        case = verify_case(root / f'flush-{pair[0]}-sync-{pair[1]}', variant, int(row['exit_code']), *pair)
        require(case['run_id'] not in identities, 'run evidence reused between cases')
        identities.add(case['run_id'])
        hashes.add(case['client_sha256'])
        cases.append(dict(flush=pair[0], synchronous_dynamic_channels=pair[1], **case))
    require(len(hashes) == 1, 'client binary changed between controlled cases')
    failed = sum(case['outcome'] == 'old interoperability failure reproduced' for case in cases)
    return {'schema_version': 1, 'validated': True, 'server_variant': variant, 'cases': cases,
            'live_resize_positive_cases': len(cases) - failed, 'reactivation_failure_cases': failed,
            'meaning': 'Old-server compatibility characterization: proven successes and/or the exact '
                       'known reactivation failure only. A reproduced failure is NOT a resize success; '
                       'successes in this run do not establish reliable old-server compatibility.' if
            variant == 'apt-0.9.24' else 'Current-server positive live-resize controls passed.'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=pathlib.Path)
    args = parser.parse_args()
    try:
        report = verify_matrix(args.evidence)
    except (OSError, ValueError, KeyError, TypeError, IndexError) as exc:
        report = {'schema_version': 1, 'validated': False, 'error': str(exc)}
    (args.evidence / 'validated-outcomes.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    return 0 if report['validated'] else 1


if __name__ == '__main__':
    sys.exit(main())
