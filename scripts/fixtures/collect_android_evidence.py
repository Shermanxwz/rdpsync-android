#!/usr/bin/env python3
"""Read-only, ledger-first collection from the one disposable hosted emulator.

No tests, gestures, services, reconnects, or settings are retried. A successful
instrumentation XML cannot substitute for a missing fresh acceptance ledger.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

TRANSFER_FRAMES = ['01-real-saf-multiselect.png', '02-remote-read-completed.png',
                   '03-remote-metadata-before-consent.png', '04-destination-0.png',
                   '04-destination-1.png', '04-destination-2.png',
                   '05-real-transfer-roundtrip.png', '06-focused-desktop-before-route-back.png',
                   '99-transfer-final.png']
CLASSIC_FRAMES = ['01-direct-touch-fullscreen-profile.png', '03-negotiated-hd.png',
                  '04-landscape-live-resize.png', '04f-landscape-true-fullscreen.png',
                  '04g-before-system-edge.png', '04g-system-edge-observation-0.png',
                  '04h-system-bars-hidden-restored.png', '04d-landscape-ime-visible.png',
                  '04d1-ime-tools-popup.png', '04d0-ime-declared-layout.png',
                  '04d2-ime-before-newline-enter.png', '04d3-ime-after-enter.png',
                  '04e-landscape-ime-restored.png', '05-weak-link-live-session.png',
                  '06-changed-certificate-blocked.png', '07-rotated-certificate-approved.png',
                  '08-exit-restored-system-bars.png', '99-before-final-teardown.png']
DRIVE_FRAMES = ['01-real-saf-imported-browser.png', '02-read-only-mounted.png', '03-read-write-mounted.png',
                '04-real-saf-exported.png', '05-revoked-session-live.png', '06-persistent-after-reconnect.png',
                '99-shared-drive-final.png']
TRANSIENT_READ = re.compile(r'device offline|no devices/emulators found|device .* not found|'
                            r'connection reset|connection closed|protocol fault|error: closed', re.I)


def composition_frames(value):
    plan = value.get('software_keyboard_declared_plan', {})
    mode = plan.get('mode')
    assert mode in ('pinyin-compose-commit-newline', 'latin-direct-newline'), 'Missing declared IME mode'
    pinyin = mode == 'pinyin-compose-commit-newline'
    assert plan.get('gestures') == (['a', 'commit-Enter', 'newline-Enter'] if pinyin else ['a', 'newline-Enter'])
    keys = value.get('actual_software_keyboard_keys', [])
    assert [x.get('stage') for x in keys] == (['type-a', 'commit-composition', 'newline'] if pinyin else ['type-a', 'newline'])
    if pinyin:
        assert value.get('software_keyboard_composition_kept_local') is True
        assert value.get('software_keyboard_committed_text') == value.get('software_keyboard_composition_committed_text', '') + '\n'
        return ['04d2a-ime-composing-a.png', '04d2-ime-before-commit-composition-enter.png',
                '04d2c-ime-after-commit-composition-enter.png', '04d2b-ime-composition-committed.png']
    return []


def directories(kind):
    if kind == 'classic':
        return ['rdpsync-evidence']
    prefix = 'gnome' if kind == 'gnome' else 'xrdp'
    return ([f'rdpsync-{prefix}-evidence'] if kind == 'gnome' else []) + [
        f'rdpsync-{prefix}-transfer-evidence', f'rdpsync-{prefix}-transport-diagnostic'] + (['rdpsync-shared-drive-evidence'] if kind == 'xrdp' else [])


def validate_ledger(directory, value):
    if directory == 'rdpsync-evidence':
        for key in ('no_auth_before_first_trust', 'ime_tools_touch_and_software_typing_passed',
                    'transient_system_bar_reveal_verified', 'same_pin_reconnect_without_prompt',
                    'changed_certificate_blocked_until_explicit_reapproval', 'unsupported_resize_preserved_session'):
            assert value.get(key) is True, f'Missing classic completion: {key}'
        assert value.get('touch_attempts') and all(t.get('passed') is True for t in value['touch_attempts'])
    else:
        assert value.get('success') is True, f'Incomplete ledger: {directory}'
        if directory == 'rdpsync-shared-drive-evidence':
            assert value.get('clipboard_used') is False and value.get('windows_os_covered') is False
            assert value.get('saf_ui_covered') is True
            for key in ('actual_import_picker_cancel_no_import', 'actual_export_picker_cancel_no_copy',
                        'consent_cancel_kept_off', 'actual_ro_denied_write', 'actual_read_over_32mib',
                        'collision_original_unchanged', 'paused_revoke_and_stale_handle_denied',
                        'source_originals_unchanged', 'persistence_after_explicit_reconnect', 'disconnected'):
                assert value.get(key) is True, f'Missing actual drive outcome: {key}'
            assert value.get('actual_rw_operations') and value.get('peer_to_saf_export')
            assert len(value.get('stalls', [])) == 2 and {x['phase'] for x in value['stalls']} == {'resume', 'cancel'}
            assert all(x.get('actual_buffered_plateau') is True for x in value['stalls'])
            assert len(value.get('touch_attempts', [])) == 2 and all(x.get('passed') is True for x in value['touch_attempts'])
            assert len(value.get('synthetic_seed_cleanup', [])) == 5 and all(x.get('removed') is True for x in value['synthetic_seed_cleanup'])
        elif directory.endswith('-transfer-evidence'):
            for key in ('offer_survived_dialog_hide', 'collision_original_unchanged', 'route_back_revoked_offer'):
                assert value.get(key) is True, f'Missing transfer outcome: {key}'
            assert value.get('peer_to_saf_export') and value.get('synthetic_seed_cleanup')
            assert all(row.get('removed') is True for row in value['synthetic_seed_cleanup'])
        elif directory.endswith('-transport-diagnostic'):
            assert value.get('diagnostic_only') is True and value.get('saf_ui_covered') is False
            for key in ('no_retained_native_offer', 'private_snapshot_removed_after_close', 'disconnected'):
                assert value.get(key) is True, f'Incomplete diagnostic: {key}'


def validate_ime_setting(value):
    assert value.get('verified_before_activity') is True and value.get('restored') is True
    assert not any('failure' in key for key in value), 'IME prerequisite or restoration failure'
    streams = value['stream_projections']
    expected = {'initial', 'latest', 'provider'} if value.get('original') == '1' else {'initial', 'latest'}
    assert set(streams) == expected
    for key, stream in streams.items():
        scope = 'settings' if key == 'provider' else 'ime'
        assert stream['scope'] == scope and stream['command_source'] == ('dumpsys -t 2 settings' if scope == 'settings' else 'dumpsys -t 2 input_method')
        assert stream['producer_eof'] is True and stream['complete'] is True and stream['failure'] == ''
        assert stream['exit_status'] == 0 and stream['exit_trailers'] == 1
        assert stream['source_limit'] == 16*1024*1024 and stream['line_prefix_limit'] == 8192 and stream['selected_limit'] == 65536
        assert 0 < stream['selected_bytes'] <= 65536 and stream['selected_bytes'] <= stream['source_bytes'] <= stream['source_limit']
        assert re.fullmatch(r'[0-9a-f]{64}', stream['source_sha256']) and stream['lines'] > 0
        required = ('secure_sections', 'system_sections', 'setting_rows') if scope == 'settings' else ('service_headers', 'observer_rows', 'service_completions', 'selected_ids', 'bound_ids', 'method_ids')
        assert all(stream[k] == 1 for k in required)
    original = value.get('original')
    assert original in ('null', '0', '1') and value.get('requested') == '1'
    factory = original == '1'
    assert value.get('wrote_setting') is (not factory)
    assert value.get('readiness_mode') == ('factory-default-no-write' if factory else 'same-service-observer-transition')
    if factory:
        assert 'write_started_elapsed_ms' not in value
        proof = value['factory_default']
        assert proof['resolved_default'] is True
        assert re.fullmatch(r'_id:[0-9]+ name:show_ime_with_hard_keyboard pkg:android value:1 default:1 defaultSystemSet:true', proof['setting_row'])
        env = proof['environment']
        assert env['success'] is True and env['schema'] == 1 and env['action_sha'] == 'a421e43855164a8197daf9d8d40fe71c6996bb0d'
        assert re.fullmatch(r'[0-9a-f]{40}', env['source_sha']) and re.fullmatch(r'[0-9]+', env['run_id'])
        for key in ('avd_config_sha256', 'image_properties_sha256', 'preflight_sha256', 'avd_metadata_sha256'):
            assert re.fullmatch(r'[0-9a-f]{64}', env[key])
        assert env['force_avd_creation'] is True and env['enable_hw_keyboard'] is False
        assert env['fresh_avd_absence_verified'] is True and env['no_snapshot'] is True
        assert env['avd_name'] == 'test' and env['device_sdk'] == '35'
        assert env['avd_identity_proof'] == 'fresh named metadata path plus live emulator console name'
        assert env['avd_metadata_mtime'] >= env['setup_started_epoch']
        root = env['preflight']['avd_root']
        assert env['avd_metadata_path'] == root + '/test.ini' and env['avd_config_path'] == root + '/test.avd/config.ini'
        assert env['avd_config_mtime'] >= env['setup_started_epoch'] > 0
        assert env['image_sysdir'].replace('\\', '/').rstrip('/').endswith('/system-images/android-35/google_apis/x86_64')
        assert env['device_fingerprint'] and re.fullmatch(r'[0-9a-f-]{36}', env['device_boot_id'])
    initial = value['initial_service']
    ime = 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'
    assert value.get('ime') == ime
    assert initial['selected_ime'] == initial['bound_ime'] == [ime]
    assert initial['observer_cache'] == (['2'] if factory else ['1'])
    assert initial['dump_complete'] is True
    for key in ('service_instance', 'method_identity'):
        assert len(initial[key]) == 1 and re.fullmatch(r'[A-Za-z0-9_.$]+@[0-9a-fA-F]+', initial[key][0])
    samples = value['consumption_samples']
    assert 1 <= len(samples) <= 80 and samples[-1]['consumed'] is True
    assert samples[-1]['observer_cache'] == ['2']
    for sample in samples:
        assert sample['dump_complete'] is True
        for key in ('selected_ime', 'bound_ime', 'service_instance', 'method_identity'):
            assert sample[key] == initial[key], 'IME identity changed during consumption proof'
        assert sample['observer_cache'] in (['1'], ['2'])
    assert value['setup_elapsed_ms'] <= value['ready_elapsed_ms'] <= value['consumption_deadline_elapsed_ms']
    assert value['ready_elapsed_ms'] <= value['restore_elapsed_ms']


IME_DUMPS = ('ime-setting-initial-dump.txt', 'ime-setting-latest-dump.txt',
             'ime-setting-provider-dump.txt', 'ime-setting-overlay.txt', 'ime-setting-environment.txt')

def validate_ime_raw(value, dumps, out):
    for key, name in (('initial', IME_DUMPS[0]), ('latest', IME_DUMPS[1])):
        assert len(dumps[name].encode('utf-8')) == value['stream_projections'][key]['selected_bytes']
    if value['original'] == '1':
        assert len(dumps[IME_DUMPS[2]].encode('utf-8')) == value['stream_projections']['provider']['selected_bytes']
    if value['original'] != '1':
        assert all(not dumps[name] for name in IME_DUMPS[2:])
        return
    proof = value['factory_default']
    assert json.loads(dumps[IME_DUMPS[4]]) == proof['environment']
    assert dumps[IME_DUMPS[3]].strip().splitlines()[-1] == 'true'
    text = dumps[IME_DUMPS[2]]
    assert not any(x in text for x in ('DUMP TIMEOUT', 'Permission Denial'))
    sections = text.split('SECURE SETTINGS (user 0)\n')
    assert len(sections) == 2 and 'SYSTEM SETTINGS (user 0)' in sections[1]
    rows = [line for line in sections[1].split('SYSTEM SETTINGS (user 0)')[0].splitlines()
            if line.startswith('_id:') and ' name:show_ime_with_hard_keyboard ' in line]
    assert rows == [proof['setting_row']]
    env = proof['environment']
    assert env == json.loads((Path(out) / 'ime-environment.json').read_text())
    for field, name in (('avd_metadata_sha256', 'avd-metadata.ini'), ('preflight_sha256', 'ime-preflight.json'), ('avd_config_sha256', 'avd-config.ini'),
                        ('image_properties_sha256', 'image-source.properties'), ('image_package_sha256', 'image-package.xml'),
                        ('workflow_sha256', 'workflow.yml')):
        assert hashlib.sha256((Path(out) / name).read_bytes()).hexdigest() == env[field]
    assert json.loads((Path(out) / 'ime-preflight.json').read_text()) == env['preflight']
    metadata = {}
    for line in (Path(out) / 'avd-metadata.ini').read_text().splitlines():
        line = line.strip()
        if not line or line.startswith(('#', ';')): continue
        key, val = line.split('=', 1)
        key, val = key.strip(), val.strip()
        assert key and key not in metadata
        metadata[key] = val
    assert metadata['path'] == str(Path(env['avd_config_path']).parent)
    assert metadata.get('path.rel', 'avd/test.avd') == 'avd/test.avd' and metadata['target'] == 'android-35'
    assert env['source_sha'] == (Path(out) / 'tested-sha.txt').read_text().strip()
    assert env['device_fingerprint'] == (Path(out) / 'emulator-fingerprint.txt').read_text().strip()


class Collector:
    def __init__(self, out, started, best_effort=False, run=subprocess.run):
        self.out = Path(out)
        self.started = started
        self.best_effort = best_effort
        self.run = run
        self.deadline = time.monotonic() + 90
        self.records = []

    def read(self, argv):
        for attempt in range(1, 4):
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Evidence read budget exhausted')
            record = {'argv': argv, 'attempt': attempt, 'started_monotonic': time.monotonic()}
            self.records.append(record)
            try:
                result = self.run(['adb', *argv], capture_output=True, timeout=min(10, remaining))
                record.update(returncode=result.returncode, stderr=result.stderr.decode(errors='replace')[-2048:])
                if result.returncode == 0:
                    return result.stdout
                retryable = bool(TRANSIENT_READ.search(record['stderr']))
                error = RuntimeError(f'adb read failed: {record}')
            except subprocess.TimeoutExpired as exc:
                record['read_timeout'] = True
                retryable, error = True, exc
            finally:
                record['completed_monotonic'] = time.monotonic()
            if not retryable or attempt == 3:
                raise error
            time.sleep(min(.5, max(0, self.deadline - time.monotonic())))

    def pull(self, directory, filename):
        assert directory in directories('classic') + directories('gnome') + directories('xrdp')
        assert re.fullmatch(r'[A-Za-z0-9_.-]+\.(json|png|txt)', filename)
        remote = f'/sdcard/Download/{directory}/{filename}'
        mtime, size = map(int, self.read(['shell', 'stat', '-c', '%Y:%s', remote]).strip().split(b':'))
        assert 0 <= size <= 16 * 1024 * 1024, f'Unbounded evidence file: {remote}'
        assert mtime >= self.started, f'Stale device evidence: {remote}'
        destination = self.out / directory / filename
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + '.part')
        try:
            self.read(['pull', remote, str(temporary)])
            data = temporary.read_bytes()
            assert len(data) == size, f'Incomplete evidence read: {remote}'
            assert data or filename.endswith('.txt'), f'Empty evidence: {remote}'
            if filename.endswith('.json'):
                value = json.loads(data)
                if not self.best_effort:
                    if filename == 'ime-setting-receipt.json':
                        validate_ime_setting(value)
                    else:
                        validate_ledger(directory, value)
            elif filename.endswith('.png'):
                assert data.startswith(b'\x89PNG\r\n\x1a\n'), f'Invalid screenshot: {remote}'
            temporary.replace(destination)
            self.records.append({'file': f'{directory}/{filename}', 'device_mtime': mtime,
                                 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
            return data
        finally:
            temporary.unlink(missing_ok=True)

    def collect(self, kind):
        errors, values = [], {}
        try:
            # All tiny ledgers precede every screenshot; interrupted bulk image
            # transfer can no longer hide the sole acceptance outcome record.
            for directory in directories(kind):
                try:
                    values[directory] = json.loads(self.pull(directory, 'measurements.json'))
                except Exception as error:
                    errors.append(str(error))
                    if not self.best_effort:
                        raise
            if kind == 'classic':
                try:
                    prerequisite = json.loads(self.pull('rdpsync-evidence', 'ime-setting-receipt.json'))
                    dumps = {}
                    for name in IME_DUMPS:
                        data = self.pull('rdpsync-evidence', name)
                        dumps[name] = data.decode('utf-8')
                        expected = prerequisite['dump_files'][name]
                        assert len(data) == expected['bytes'] and hashlib.sha256(data).hexdigest() == expected['sha256']
                    validate_ime_raw(prerequisite, dumps, self.out)
                except Exception as error:
                    errors.append(f'IME prerequisite evidence: {error}')
                    if not self.best_effort:
                        raise
            for directory in directories(kind):
                frames = list(CLASSIC_FRAMES) if kind == 'classic' else list(TRANSFER_FRAMES) if directory.endswith('-transfer-evidence') else []
                if directory == 'rdpsync-shared-drive-evidence':
                    frames = list(DRIVE_FRAMES)
                if kind == 'classic':
                    frames.append('diagnostics.txt')
                    try:
                        frames += composition_frames(values.get(directory, {}))
                    except AssertionError as error:
                        errors.append(f'IME plan incomplete: {error}')
                        if not self.best_effort:
                            raise
                        # Failure diagnostics never turn an incomplete plan green.
                        # Read each possible stage once; missing frames stay errors.
                        frames += ['04d2a-ime-composing-a.png', '04d2-ime-before-commit-composition-enter.png',
                                   '04d2c-ime-after-commit-composition-enter.png', '04d2b-ime-composition-committed.png',
                                   '04d4-ime-key-failure-before-hide.png']
                    for phase in values.get(directory, {}).get('untrusted_certificate_phases', []):
                        if phase.get('system_immersive_coachmark', {}).get('acknowledged'):
                            assert phase['phase'] in ('initial', 'weak_link')
                            frames.append(f"00-pretrust-{phase['phase']}-system-immersive-coachmark.png")
                elif directory.endswith('-transfer-evidence'):
                    frames.append('accessibility-final.txt')
                if directory == 'rdpsync-gnome-evidence':
                    frames = ['01-native-gnome-frame.png', '99-final.png']
                    if values.get(directory, {}).get('mode') == 'headless-extend':
                        frames += ['02-gnome-real-disp-resize.png', '03-gnome-rotation-fullscreen.png']
                    else:
                        frames += ['02-gnome-mirror-retained-frame.png']
                if values.get(directory, {}).get('system_immersive_coachmark', {}).get('acknowledged'):
                    frames.append('00-system-immersive-coachmark.png')
                for filename in frames:
                    try:
                        self.pull(directory, filename)
                    except Exception as error:
                        errors.append(str(error))
                        if not self.best_effort:
                            raise
        except Exception as error:
            # Selection/schema failures must never leave a misleading complete receipt.
            if str(error) not in errors:
                errors.append(str(error))
            raise
        finally:
            self.out.mkdir(parents=True, exist_ok=True)
            name = 'evidence-collection-best-effort.json' if self.best_effort else 'evidence-collection.json'
            (self.out / name).write_text(json.dumps({'kind': kind, 'device_start_seconds': self.started,
                'best_effort': self.best_effort, 'complete': not errors, 'errors': errors, 'reads': self.records}, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('kind', choices=['classic', 'gnome', 'xrdp'])
    parser.add_argument('out', type=Path)
    parser.add_argument('--best-effort', action='store_true')
    args = parser.parse_args()
    started = int((args.out / 'evidence-start-device-seconds.txt').read_text().strip())
    Collector(args.out, started, args.best_effort).collect(args.kind)
