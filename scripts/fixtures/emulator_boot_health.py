#!/usr/bin/env python3
"""Bounded pre-instrumentation health gate for our disposable hosted emulator.

Historical ANR logs are evidence, never a recovery predicate. Only an active
error of the resolved, allowlisted HOME package permits one force-stop/start.
No app/test retries, SystemUI restarts, data clearing, dialog clicks, or settings.
The receipt describes observed startup health, not immunity to a later ANR.

Public shell commands: https://developer.android.com/tools/adb#am and
https://developer.android.com/tools/dumpsys . Dump fields are pinned to AOSP
android-10.0.0_r47 ProcessRecord/DisplayContent and android-15.0.0_r1
ProcessErrorStateRecord/ProcessList/DisplayContent; no hidden app API is used.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

SERIAL = 'emulator-5554'
HOME_PACKAGES = {'com.google.android.apps.nexuslauncher', 'com.android.launcher3', 'com.android.launcher'}
# Readiness-only identities, never healthy launchers or recovery targets. The
# first was observed on our fresh API35 Google image; AOSP15 Settings
# FallbackHome waits for user unlock and a real HOME before finishing:
# https://android.googlesource.com/platform/packages/apps/Settings/+/android-15.0.0_r1/src/com/android/settings/FallbackHome.java
PENDING_HOME_COMPONENTS = {
    'com.google.android.googlesdksetup/com.google.android.googlesdksetup.DefaultActivity',
    'com.android.settings/com.android.settings.FallbackHome',
}
PROCESS_HEADER = re.compile(r'^  \*(?:APP|PERS)\* UID \d+ ProcessRecord\{[^ }]+ (\d+):([\w.:]+)/[^}]+\}$', re.M)
ACTIVE_ERROR = re.compile(r'\b(?:mCrashing|mNotResponding|bad)=true\b|\b(?:AppNotRespondingDialog|AppErrorDialog)[{@]')
DUMP_ERROR = re.compile(r'(?i)permission denial|dump.*?time(?:d[ -]out|out)|exception occurred')
APP_PROCESS = re.compile(r'^com\.rdp\.sync(?:\.preview\d*)?(?:\.test)?(?::|$)')


def component(value):
    """Normalize an Android flattened ComponentName, with no prefix matching."""
    match = re.fullmatch(r'([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)', value)
    if not match:
        raise ValueError(f'Invalid component: {value!r}')
    package, activity = match.groups()
    return package + '/' + (package + activity if activity.startswith('.') else activity)


class HomePending(ValueError):
    def __init__(self, home):
        self.home = home
        super().__init__('Known setup HOME is not yet a ready launcher: ' + home)


def resolve_home(output):
    values = [line.strip() for line in output.splitlines() if '/' in line]
    if len(values) != 1:
        raise ValueError('HOME resolution must have exactly one component')
    value = component(values[0])
    if value in PENDING_HOME_COMPONENTS:
        raise HomePending(value)
    if value.split('/')[0] not in HOME_PACKAGES:
        raise ValueError('Resolved HOME is not an owned CI image launcher')
    return value


def complete_dump(output, kind):
    marker = f'RDPSYNC_BOOT_DUMP_{kind}_COMPLETE=0'
    if output.rstrip().splitlines()[-1:] != [marker] or output.count(marker) != 1:
        raise ValueError('Incomplete or unsuccessful remote dump: ' + kind)
    if DUMP_ERROR.search(output):
        raise ValueError('Platform reported an incomplete dump: ' + kind)
    return output[:output.rfind(marker)].rstrip()


def process_states(output):
    output = complete_dump(output, 'processes')
    # -a is required: the abbreviated LRU-only dump cannot prove current state.
    if not output.startswith('ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)') or '  All known processes:' not in output or '  Process LRU list ' not in output:
        raise ValueError('Missing full process dump')
    if re.search(r'(?i)active instrumentation', output):
        raise ValueError('Incomplete process dump or instrumentation already running')
    starts = list(PROCESS_HEADER.finditer(output))
    if not starts or len(starts) != len(re.findall(r'^  \*(?:APP|PERS)\*', output, re.M)):
        raise ValueError('Unknown process record format')
    result = {}
    for index, match in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(output)
        # Stop before subsequent top-level sections; never parse historical data.
        body = re.split(r'^  [A-Z][^\n]*:', output[match.end():end], maxsplit=1, flags=re.M)[0]
        pid, name = int(match.group(1)), match.group(2)
        if name in result:
            raise ValueError('Ambiguous duplicate process identity')
        result[name] = {'pid': pid, 'active_error': bool(ACTIVE_ERROR.search(body)),
                        'error_lines': [s.strip() for s in body.splitlines() if ACTIVE_ERROR.search(s)]}
    return result


def observe(processes, windows, displays, activities, home):
    states = process_states(processes)
    windows = complete_dump(windows, 'windows')
    displays = complete_dump(displays, 'displays')
    activities = complete_dump(activities, 'activities')
    if any(APP_PROCESS.match(name) and row['pid'] > 0 for name, row in states.items()):
        raise ValueError('App/test process already running: recovery is pre-instrumentation only')
    if ('WINDOW MANAGER WINDOWS (dumpsys window windows)' not in windows
            or 'WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)' not in displays
            or 'ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)' not in activities):
        raise ValueError('Missing window/activity dump headers')
    if re.findall(r'Display: mDisplayId=(\d+)', displays) != ['0']:
        raise ValueError('Expected exactly the owned default display')
    focuses = re.findall(r'^\s*mCurrentFocus=(.*)$', displays, re.M)
    if len(focuses) != 1:
        raise ValueError('Expected one unambiguous focused window')
    focus = focuses[0].strip()
    focus_component = None
    if focus != 'null':
        match = re.fullmatch(r'Window\{[^ }]+ u0 (.+)\}', focus)
        if not match:
            raise ValueError('Unknown current focus format')
        if re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', match.group(1)):
            focus_component = component(match.group(1))
    # These dumps contain live windows only, unlike dumpsys window lastanr.
    anr_windows = sorted(set(re.findall(r'Application (?:Not Responding|Error): ([A-Za-z0-9_.]+)', windows)))
    active_errors = sorted(name for name, row in states.items() if row['active_error'])
    package = home.split('/')[0]
    launcher = states.get(package, {'pid': 0, 'active_error': False})
    systemui = states.get('com.android.systemui', {'pid': 0, 'active_error': False})
    resumed = []
    for value in re.findall(r'^\s*(?:mResumedActivity|ResumedActivity|topResumedActivity):?\s*=?\s*ActivityRecord\{[^\n]+', activities, re.M):
        match = re.search(r'\bu0 ([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\b', value)
        if match:
            resumed.append(component(match.group(1)))
    resumed_home = bool(resumed) and set(resumed) == {home}
    unrelated_errors = sorted((set(active_errors) | set(anr_windows)) - {package})
    healthy = (launcher['pid'] > 0 and systemui['pid'] > 0 and not active_errors and not anr_windows
               and focus_component == home and resumed_home)
    return {'healthy': healthy, 'launcher_pid': launcher['pid'], 'systemui_pid': systemui['pid'],
            'focus': focus, 'resumed_components': resumed, 'active_errors': active_errors,
            'active_anr_windows': anr_windows, 'unrelated_errors': unrelated_errors,
            'launcher_recoverable': launcher['pid'] > 0 and (launcher['active_error'] or package in anr_windows) and not unrelated_errors,
            'error_lines': {n: states[n]['error_lines'] for n in active_errors}}


class Gate:
    def __init__(self, out, run=subprocess.run, monotonic=time.monotonic, sleep=time.sleep, environ=None):
        self.out, self.run, self.now, self.sleep = Path(out), run, monotonic, sleep
        self.env = os.environ if environ is None else environ
        self.deadline = self.now() + 120
        self.report = {'schema': 1, 'scope': 'disposable hosted pre-instrumentation startup only',
                       'success': False, 'recovery_count': 0, 'commands': [], 'observations': []}
        self.adb = ['adb', '-s', SERIAL]
        self.device_authorized = False

    def command(self, args, name, *, raw=False, allowed=(0,)):
        remaining = self.deadline - self.now()
        if remaining <= 0:
            raise TimeoutError('Boot health deadline exhausted')
        row = {'args': args, 'started_monotonic': self.now()}
        self.report['commands'].append(row)
        try:
            result = self.run(args, capture_output=True, timeout=min(10, remaining))
            row.update(returncode=result.returncode)
            self.out.joinpath(name + '.stdout' + ('.png' if raw else '.txt')).write_bytes(result.stdout)
            self.out.joinpath(name + '.stderr.txt').write_bytes(result.stderr)
            if result.returncode not in allowed:
                raise RuntimeError(f'Boot command failed: {name} ({result.returncode})')
            return result.stdout if raw else result.stdout.decode('utf-8', errors='strict').replace('\r', '')
        except subprocess.TimeoutExpired:
            row['timeout'] = True
            raise
        finally:
            row['completed_monotonic'] = self.now()

    def shell(self, args, name):
        return self.command([*self.adb, 'shell', *args], name)

    def dump(self, args, name, kind):
        # adb shell runs this fixed remote shell program (unlike UiAutomation's
        # argv-only executor). No resolved/user string is interpolated here.
        script = ('dumpsys -t 5 ' + ' '.join(args) + '; status=$?; '
                  + f'printf \'\\nRDPSYNC_BOOT_DUMP_{kind}_COMPLETE=%s\\n\' "$status"; exit "$status"')
        return self.command([*self.adb, 'shell', script], name)

    def snapshot(self, home):
        index = len(self.report['observations'])
        processes = self.dump(['activity', '-a', 'processes'], f'{index:02d}-processes', 'processes')
        windows = self.dump(['window', 'windows'], f'{index:02d}-windows', 'windows')
        displays = self.dump(['window', 'displays'], f'{index:02d}-displays', 'displays')
        activities = self.dump(['activity', 'activities'], f'{index:02d}-activities', 'activities')
        row = observe(processes, windows, displays, activities, home)
        row['observed_monotonic'] = self.now()
        self.report['observations'].append(row)
        if row['launcher_recoverable']:
            self.report['observed_active_launcher_error'] = True
        if row['unrelated_errors']:
            raise RuntimeError('Active error outside the resolved launcher; no recovery authorized')
        return row

    def evidence(self, name):
        if not self.device_authorized:
            return
        # Diagnostics are bounded and independent; they cannot turn failure into success.
        for args, suffix, raw in [([*self.adb, 'logcat', '-b', 'all', '-d'], 'logcat', False),
                                  ([*self.adb, 'exec-out', 'screencap', '-p'], 'screen', True),
                                  ([*self.adb, 'shell', 'dumpsys', 'activity', 'lastanr'], 'historical-lastanr', False)]:
            try:
                self.command(args, name + '-' + suffix, raw=raw)
            except Exception as error:
                self.report.setdefault('diagnostic_errors', []).append(str(error))

    def recheck_owned_transport(self, name):
        # Never reconnect/restart adb or any device. Observe only the same sole
        # action-owned serial; an extra/unauthorized device remains fatal.
        output = self.command(['adb', 'devices'], name)
        listed = [line.split() for line in output.splitlines()[1:] if line.strip()]
        if listed not in ([[SERIAL, 'device']], [[SERIAL, 'offline']]):
            raise ValueError('Transport changed away from the sole owned emulator')

    def await_boot_completed(self):
        for attempt in range(1, 6):
            name = f'boot-completed-{attempt:02d}'
            output = self.command([*self.adb, 'shell', 'getprop', 'sys.boot_completed'],
                                  name, allowed=(0, 1, 255))
            row = self.report['commands'][-1]
            error = self.out.joinpath(name + '.stderr.txt').read_text().strip()
            if row['returncode'] == 0 and output.strip() == '1':
                self.report['boot_completed_attempts'] = attempt
                return
            pending = row['returncode'] == 0 and output.strip() in ('', '0')
            offline = row['returncode'] in (1, 255) and error in ('adb: device offline', 'error: device offline')
            if not (pending or offline):
                raise RuntimeError('Unknown boot-completion failure; no startup retry permitted')
            self.report.setdefault('startup_transport_waits', []).append(
                {'phase': 'boot-completed', 'attempt': attempt, 'reason': 'offline' if offline else 'boot-pending'})
            if attempt == 5:
                raise RuntimeError('Owned boot-completion did not become ready within five observations')
            self.recheck_owned_transport(f'boot-devices-{attempt:02d}')
            self.sleep(min(1, max(0, self.deadline - self.now())))

    def prepare_logcat(self, home):
        # Clear startup logs only after archived initial evidence and healthy
        # pre-instrumentation snapshots. Never repeat app/tests or a timed-out
        # mutation. Explicit observed logd/transport failures get <=3 attempts.
        for attempt in range(1, 4):
            if not self.snapshot(home)['healthy']:
                raise RuntimeError('Startup health changed before logcat preparation')
            name = f'logcat-clear-{attempt:02d}'
            self.command([*self.adb, 'logcat', '-b', 'all', '-d'], name + '-before')
            output = self.command([*self.adb, 'logcat', '-c'], name, allowed=(0, 1, 255))
            row = self.report['commands'][-1]
            error = self.out.joinpath(name + '.stderr.txt').read_text().strip()
            if row['returncode'] == 0:
                if not self.snapshot(home)['healthy']:
                    raise RuntimeError('Startup health changed after logcat preparation')
                self.report['logcat_clear_attempts'] = attempt
                self.report['logcat_clear_verified'] = True
                return
            message = (output.strip() + '\n' + error).strip()
            transient = message in ("failed to clear the 'main' log", 'adb: device offline', 'error: device offline')
            self.report.setdefault('startup_transport_waits', []).append(
                {'phase': 'logcat-clear', 'attempt': attempt, 'reason': message})
            if not transient or attempt == 3:
                raise RuntimeError('Logcat preparation failed; no instrumentation permitted')
            self.recheck_owned_transport(f'logcat-devices-{attempt:02d}')
            self.sleep(min(1, max(0, self.deadline - self.now())))

    def await_home(self):
        history = self.report.setdefault('home_resolution_observations', [])
        while self.now() < self.deadline:
            index = len(history)
            name = 'home' if index == 0 else f'home-{index:02d}'
            output = self.shell(['cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
                                 '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'], name)
            row = {'observed_monotonic': self.now(), 'raw_stdout': name + '.stdout.txt'}
            history.append(row)
            try:
                home = resolve_home(output)
            except HomePending as pending:
                row.update(component=pending.home, state='waiting_for_real_launcher')
                # Read-only observation: never start, stop, dismiss or provision
                # this setup/fallback activity. The original total deadline stays.
                self.sleep(min(1, max(0, self.deadline - self.now())))
                continue
            except ValueError as error:
                row.update(state='invalid_or_untrusted_home', error=str(error))
                raise
            row.update(component=home, state='allowlisted_launcher')
            return home
        raise TimeoutError('Known setup HOME did not resolve to a real launcher within the startup deadline')

    def check(self):
        if self.env.get('GITHUB_ACTIONS') != 'true' or self.env.get('RUNNER_ENVIRONMENT') != 'github-hosted':
            raise ValueError('Only disposable GitHub-hosted CI is authorized')
        devices = self.command(['adb', 'devices'], 'devices').splitlines()
        listed = [line.split() for line in devices[1:] if line.strip()]
        if listed != [[SERIAL, 'device']]:
            raise ValueError('Expected only action-owned emulator-5554, with no other connected devices')
        if self.shell(['getprop', 'ro.kernel.qemu'], 'qemu').strip() != '1':
            raise ValueError('Not an emulator')
        sdk = self.shell(['getprop', 'ro.build.version.sdk'], 'sdk').strip()
        if sdk not in {'29', '35'}:
            raise ValueError('Unreviewed emulator API')
        self.report['sdk'] = int(sdk)
        self.device_authorized = True
        self.evidence('initial')
        self.await_boot_completed()
        home = self.await_home()
        package = home.split('/')[0]
        self.report['home_component'] = home
        stable_since, stable_count, stable_pids = None, 0, None
        original_pid = None
        while self.now() < self.deadline:
            row = self.snapshot(home)
            if row['launcher_recoverable'] and self.report['recovery_count'] == 0:
                # An observed error breaks continuity even if it self-recovers
                # during diagnostics and no restart is subsequently warranted.
                stable_since, stable_count, stable_pids = None, 0, None
                self.evidence('before-launcher-recovery')
                fresh = self.snapshot(home)
                if not fresh['launcher_recoverable'] or fresh['launcher_pid'] != row['launcher_pid']:
                    # Evidence capture may outlive an ANR or process. Never act
                    # on the old row; the next iteration evaluates current state.
                    continue
                original_pid = fresh['launcher_pid']
                # Reserve before any mutation. A timeout/uncertain result is NOT retried.
                self.report.update(recovery_count=1, original_launcher_pid=original_pid)
                self.write_report()
                self.shell(['am', 'force-stop', '--user', '0', package], 'launcher-force-stop')
                result = self.shell(['am', 'start', '-W', '--user', '0', '-a', 'android.intent.action.MAIN',
                                     '-c', 'android.intent.category.HOME', '-n', home], 'launcher-home-start')
                if not re.search(r'^Status: ok\s*$', result, re.M):
                    raise RuntimeError('Launcher start did not report Status: ok')
                self.evidence('after-launcher-recovery')
                stable_since, stable_count, stable_pids = None, 0, None
                continue
            pids = (row['launcher_pid'], row['systemui_pid'])
            healthy = row['healthy'] and (original_pid is None or row['launcher_pid'] != original_pid)
            if healthy:
                if stable_pids != pids:
                    stable_since, stable_count, stable_pids = self.now(), 0, pids
                stable_count += 1
                if stable_count >= 3 and self.now() - stable_since >= 5:
                    if self.now() > self.deadline:
                        raise TimeoutError('Final health observation exceeded the startup deadline')
                    classification = ('recovered_boot' if original_pid else 'self_recovered_boot'
                                      if self.report.get('observed_active_launcher_error') else 'pristine_boot')
                    self.prepare_logcat(home)
                    if self.now() > self.deadline:
                        raise TimeoutError('Logcat preparation exceeded startup deadline')
                    self.report.update(success=True, classification=classification,
                                       healthy_observation_count=stable_count, stable_seconds=self.now() - stable_since)
                    # Startup readiness is the last health observation. Do not add
                    # slow diagnostics after it and claim a later state was checked.
                    return
            else:
                stable_since, stable_count, stable_pids = None, 0, None
            self.sleep(min(1, max(0, self.deadline - self.now())))
        raise TimeoutError('Launcher/SystemUI/focused HOME did not become healthy within the finite startup window')

    def write_report(self):
        self.out.joinpath('boot-health.json').write_text(json.dumps(self.report, indent=2) + '\n')

    def execute(self):
        self.out.mkdir(parents=True, exist_ok=False)
        try:
            self.check()
        except Exception as error:
            self.report.update(success=False, classification='infrastructure_failure', error=str(error))
            self.evidence('failed')
            raise
        finally:
            self.write_report()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path, help='Fresh boot-health directory beneath this run evidence')
    args = parser.parse_args()
    try:
        Gate(args.out).execute()
    except Exception as error:
        print(f'BOOT_INFRASTRUCTURE_FAILURE: {error}', file=sys.stderr)
        sys.exit(4)
