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
                  '04d1-ime-tools-popup.png', '04d3-ime-after-enter.png',
                  '04e-landscape-ime-restored.png', '05-weak-link-live-session.png',
                  '06-changed-certificate-blocked.png', '07-rotated-certificate-approved.png',
                  '08-exit-restored-system-bars.png', '99-before-final-teardown.png']
DRIVE_FRAMES = ['01-real-saf-imported-browser.png', '02-read-only-mounted.png', '03-read-write-mounted.png',
                '04-real-saf-exported.png', '05-revoked-session-live.png', '06-persistent-after-reconnect.png',
                '99-shared-drive-final.png']
TRANSIENT_READ = re.compile(r'device offline|no devices/emulators found|device .* not found|'
                            r'connection reset|connection closed|protocol fault|error: closed', re.I)


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
            for directory in directories(kind):
                frames = list(CLASSIC_FRAMES) if kind == 'classic' else list(TRANSFER_FRAMES) if directory.endswith('-transfer-evidence') else []
                if directory == 'rdpsync-shared-drive-evidence':
                    frames = list(DRIVE_FRAMES)
                if kind == 'classic':
                    frames.append('diagnostics.txt')
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
