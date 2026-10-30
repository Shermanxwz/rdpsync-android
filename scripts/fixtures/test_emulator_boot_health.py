#!/usr/bin/env python3
"""Source-shaped API29/API35 parser and one-action policy regressions (no adb)."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from emulator_boot_health import Gate, observe, process_states, resolve_home

PKG = 'com.google.android.apps.nexuslauncher'
HOME = PKG + '/' + PKG + '.NexusLauncherActivity'


def processes(error=None, pid=1200, extra=''):
    error_line = '' if not error else '    mDebugging=false mCrashing=false null mNotResponding=true com.android.server.am.AppNotRespondingDialog@abc bad=false\n'
    ui_error = '    mCrashing=true null mNotResponding=false null bad=false\n' if error == 'systemui' else ''
    launcher_error = error_line if error == 'launcher' else ''
    return ('ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n  All known processes:\n'
            f'  *APP* UID 10178 ProcessRecord{{aaa {pid}:{PKG}/u0a178}}\n    pid={pid} starting=false\n{launcher_error}'
            f'  *PERS* UID 10151 ProcessRecord{{bbb 900:com.android.systemui/u0a151}}\n    pid=900 starting=false\n{ui_error}'
            + extra + '  Process LRU list (sorted by oom_adj, 2 total):\nRDPSYNC_BOOT_DUMP_processes_COMPLETE=0\n')


def windows(anr=False):
    return ('WINDOW MANAGER WINDOWS (dumpsys window windows)\n'
            + (f'  Window #0 Window{{cc u0 Application Not Responding: {PKG}}}:\n' if anr else '')
            + f'  Window #1 Window{{dd u0 {HOME}}}:\n  mTopFocusedDisplayId=0\nRDPSYNC_BOOT_DUMP_windows_COMPLETE=0\n')


def displays(focus=HOME):
    value = 'null' if focus is None else f'Window{{cc u0 {focus}}}'
    return 'WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n  Display: mDisplayId=0 (organized)\n  mCurrentFocus=' + value + '\nRDPSYNC_BOOT_DUMP_displays_COMPLETE=0\n'


def activities(api=35):
    field = 'topResumedActivity=' if api == 35 else 'mResumedActivity: '
    return f'ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n  {field}ActivityRecord{{ab u0 {HOME} t3}}\nRDPSYNC_BOOT_DUMP_activities_COMPLETE=0\n'


class ParserTests(unittest.TestCase):
    def test_both_reviewed_api_formats(self):
        for api in (29, 35):
            self.assertTrue(observe(processes(), windows(), displays(), activities(api), HOME)['healthy'])

    def test_resolve_exact_known_home_and_expand_component(self):
        self.assertEqual(HOME, resolve_home('priority=0 preferredOrder=0\n' + PKG + '/.NexusLauncherActivity\n'))
        for invalid in ('com.evil.launcher/.Home', PKG + '/.Home\n' + PKG + '/.Other', 'No activity found'):
            with self.assertRaises(ValueError): resolve_home(invalid)

    def test_launcher_active_error_is_only_recoverable_one(self):
        value = observe(processes('launcher'), windows(True), displays(None), activities(), HOME)
        self.assertTrue(value['launcher_recoverable']); self.assertFalse(value['healthy'])
        value = observe(processes('systemui'), windows(), displays(), activities(), HOME)
        self.assertFalse(value['launcher_recoverable']); self.assertEqual(['com.android.systemui'], value['unrelated_errors'])

    def test_dialog_object_remains_active_even_when_error_flag_false(self):
        dump = processes('launcher').replace('mNotResponding=true', 'mNotResponding=false')
        self.assertTrue(process_states(dump)[PKG]['active_error'])

    def test_anr_window_without_process_error_does_not_pass(self):
        value = observe(processes(), windows(True), displays(None), activities(), HOME)
        self.assertFalse(value['healthy']); self.assertTrue(value['launcher_recoverable'])

    def test_missing_focus_does_not_authorize_restart(self):
        value = observe(processes(), windows(), displays(None), activities(), HOME)
        self.assertFalse(value['healthy']); self.assertFalse(value['launcher_recoverable'])

    def test_missing_systemui_cannot_be_healthy(self):
        dump = processes().replace('  *PERS* UID 10151 ProcessRecord{bbb 900:com.android.systemui/u0a151}\n    pid=900 starting=false\n', '')
        self.assertFalse(observe(dump, windows(), displays(), activities(), HOME)['healthy'])

    def test_other_active_anr_window_forbids_launcher_recovery(self):
        value = observe(processes('launcher'), windows(True).replace('RDPSYNC_BOOT_DUMP_windows_COMPLETE=0', '  Window #2 Window{ee u0 Application Not Responding: com.android.settings}:\nRDPSYNC_BOOT_DUMP_windows_COMPLETE=0'), displays(None), activities(), HOME)
        self.assertFalse(value['launcher_recoverable']); self.assertEqual(['com.android.settings'], value['unrelated_errors'])

    def test_prefix_match_is_not_exact_focus(self):
        value = observe(processes(), windows(), displays(HOME + 'Other'), activities(), HOME)
        self.assertFalse(value['healthy'])

    def test_unknown_truncated_duplicate_and_active_instrumentation_fail_closed(self):
        for bad in ('', 'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n',
                    processes().split('  Process LRU list ')[0],
                    processes() + '  Active instrumentation:\n', processes().replace('*APP* UID', '*APP* BROKEN UID'),
                    processes() + f'  *APP* UID 1 ProcessRecord{{ccc 500:{PKG}/u0a178}}\n'):
            with self.assertRaises(ValueError): process_states(bad)
        for bad in (displays() + '  mCurrentFocus=null\n', displays().replace('mDisplayId=0', 'mDisplayId=2'),
                    displays() + '  Display: mDisplayId=1\n', 'WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n'):
            with self.assertRaises(ValueError): observe(processes(), windows(), bad, activities(), HOME)

    def test_app_already_running_forbids_recovery(self):
        extra = '  *APP* UID 10209 ProcessRecord{ccc 1000:com.rdp.sync.preview3/u0a209}\n'
        with self.assertRaises(ValueError): observe(processes(extra=extra), windows(), displays(), activities(), HOME)

    def test_all_live_dumps_require_successful_complete_remote_marker(self):
        parts = [processes(), windows(), displays(), activities()]
        for index in range(4):
            for malformed in (parts[index].rsplit('RDPSYNC_BOOT_DUMP_', 1)[0],
                              parts[index].replace('_COMPLETE=0', '_COMPLETE=1'),
                              parts[index] + parts[index].splitlines()[-1] + '\n',
                              '*** DUMP TIMEOUT ***\n' + parts[index]):
                changed = parts.copy(); changed[index] = malformed
                with self.assertRaises(ValueError): observe(*changed, HOME)


class FakeAdb:
    def __init__(self, error=None, recover=True, uncertain=False, sdk='35'):
        self.error, self.recover, self.uncertain, self.sdk = error, recover, uncertain, sdk
        self.clock = 0.0; self.calls = []; self.stopped = False

    def sleep(self, seconds): self.clock += seconds

    def run(self, args, **kwargs):
        self.clock += .05; self.calls.append(args)
        line = ' '.join(args)
        if args[-1].startswith('dumpsys -t 5 '): args = args[:-1] + args[-1].split(';')[0].split()[3:]
        value = ''
        if args == ['adb', 'devices']: value = 'List of devices attached\nemulator-5554\tdevice\n'
        elif 'ro.kernel.qemu' in args or 'sys.boot_completed' in args: value = '1\n'
        elif 'ro.build.version.sdk' in args: value = self.sdk + '\n'
        elif 'resolve-activity' in args: value = HOME + '\n'
        elif args[-2:] == ['-a', 'processes']:
            value = processes(None if self.stopped and self.recover else self.error, 1300 if self.stopped and self.recover else 1200)
        elif args[-2:] == ['window', 'windows']:
            value = windows(self.error == 'launcher' and not (self.stopped and self.recover))
        elif args[-2:] == ['window', 'displays']:
            value = displays(None if self.error and not (self.stopped and self.recover) else HOME)
        elif args[-2:] == ['activity', 'activities']: value = activities(int(self.sdk))
        elif 'force-stop' in args:
            self.stopped = True
            if self.uncertain: raise subprocess.TimeoutExpired(args, kwargs['timeout'])
        elif 'start' in args: value = 'Starting: Intent {}\nStatus: ok\nComplete\n'
        elif 'lastanr' in args: value = 'Historical ANR in ' + PKG + '\nmNotResponding=true\n'
        elif 'logcat' in args: value = 'Old ANR in ' + PKG + '\n'
        elif 'screencap' in args: return subprocess.CompletedProcess(args, 0, b'\x89PNG\r\n\x1a\n', b'')
        else: raise AssertionError('Unexpected command: ' + line)
        return subprocess.CompletedProcess(args, 0, value.encode(), b'')


class PolicyTests(unittest.TestCase):
    def exercise(self, fake, expected_error=False, env=None):
        with tempfile.TemporaryDirectory() as root:
            out = Path(root) / 'health'
            gate = Gate(out, fake.run, lambda: fake.clock, fake.sleep,
                        {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted'} if env is None else env)
            if expected_error:
                with self.assertRaises(Exception): gate.execute()
            else: gate.execute()
            return json.loads((out / 'boot-health.json').read_text())

    def test_healthy_history_does_not_trigger_recovery(self):
        fake = FakeAdb(); report = self.exercise(fake)
        self.assertTrue(report['success']); self.assertEqual('pristine_boot', report['classification'])
        self.assertEqual(0, report['recovery_count']); self.assertGreaterEqual(report['stable_seconds'], 5)
        self.assertFalse(any('force-stop' in c or 'start' in c for c in fake.calls))

    def test_one_active_launcher_recovery_then_new_pid_and_stable_health(self):
        fake = FakeAdb('launcher'); report = self.exercise(fake)
        self.assertTrue(report['success']); self.assertEqual('recovered_boot', report['classification'])
        self.assertEqual(1, report['recovery_count']); self.assertEqual(1, sum('force-stop' in c for c in fake.calls))
        self.assertEqual(1, sum('start' in c for c in fake.calls))
        self.assertEqual(1300, report['observations'][-1]['launcher_pid'])

    def test_unresolved_launcher_never_retried(self):
        fake = FakeAdb('launcher', recover=False); report = self.exercise(fake, True)
        self.assertFalse(report['success']); self.assertEqual(1, sum('force-stop' in c for c in fake.calls))

    def test_uncertain_mutation_outcome_never_retried_or_started(self):
        fake = FakeAdb('launcher', uncertain=True); report = self.exercise(fake, True)
        self.assertEqual(1, report['recovery_count']); self.assertEqual(1, sum('force-stop' in c for c in fake.calls))
        self.assertFalse(any('start' in c for c in fake.calls))

    def test_systemui_error_fails_without_mutation(self):
        fake = FakeAdb('systemui'); report = self.exercise(fake, True)
        self.assertEqual(0, report['recovery_count']); self.assertFalse(any('force-stop' in c or 'start' in c for c in fake.calls))

    def test_unauthorized_environment_never_touches_adb(self):
        fake = FakeAdb(); self.exercise(fake, True, {})
        self.assertEqual([], fake.calls)

    def test_other_connected_device_forbids_diagnostics_and_recovery(self):
        class OtherDevice(FakeAdb):
            def run(self, args, **kwargs):
                self.calls.append(args)
                return subprocess.CompletedProcess(args, 0, b'List of devices attached\nemulator-5554\tdevice\nphysical-device\tdevice\n', b'')
        fake = OtherDevice(); self.exercise(fake, True)
        self.assertEqual([['adb', 'devices']], fake.calls)

    def test_missing_focus_exhausts_finite_window_without_restart(self):
        class NoFocus(FakeAdb):
            def run(self, args, **kwargs):
                result = super().run(args, **kwargs)
                if args[-1].startswith('dumpsys -t 5 window displays;'): result.stdout = displays(None).encode()
                return result
        fake = NoFocus(); report = self.exercise(fake, True)
        self.assertFalse(report['success']); self.assertEqual(0, report['recovery_count'])
        self.assertLessEqual(fake.clock, 120.1)

    def test_api29_healthy_shape(self):
        self.assertTrue(self.exercise(FakeAdb(sdk='29'))['success'])

    def test_launcher_recovered_during_diagnostics_is_not_force_stopped(self):
        class RecoveredDuringEvidence(FakeAdb):
            def run(self, args, **kwargs):
                if 'logcat' in args and sum('logcat' in c for c in self.calls) == 1:
                    self.error = None
                return super().run(args, **kwargs)
        fake = RecoveredDuringEvidence('launcher'); report = self.exercise(fake)
        self.assertEqual('self_recovered_boot', report['classification'])
        self.assertEqual(0, report['recovery_count']); self.assertFalse(any('force-stop' in c for c in fake.calls))

    def test_self_recovered_error_breaks_previous_healthy_observation_streak(self):
        class InterruptedHealth(FakeAdb):
            def __init__(self):
                super().__init__(); self.process_dumps = 0

            def run(self, args, **kwargs):
                if args[-1].startswith('dumpsys -t 5 activity -a processes;'):
                    self.process_dumps += 1
                    if self.process_dumps == 2: self.error = 'launcher'
                if 'logcat' in args and sum('logcat' in c for c in self.calls) == 1:
                    self.error = None
                return super().run(args, **kwargs)
        fake = InterruptedHealth(); report = self.exercise(fake)
        rows = report['observations']
        last_error = max(i for i, row in enumerate(rows) if row['launcher_recoverable'])
        after = rows[last_error + 1:]
        self.assertTrue(all(row['healthy'] for row in after))
        self.assertGreaterEqual(len(after), 3)
        self.assertGreaterEqual(after[-1]['observed_monotonic'] - after[0]['observed_monotonic'], 5)
        self.assertEqual('self_recovered_boot', report['classification'])
        self.assertEqual(0, report['recovery_count'])

    def test_new_systemui_error_during_capture_prevents_any_mutation(self):
        class ChangedDuringEvidence(FakeAdb):
            def run(self, args, **kwargs):
                if 'logcat' in args and sum('logcat' in c for c in self.calls) == 1: self.error = 'systemui'
                return super().run(args, **kwargs)
        fake = ChangedDuringEvidence('launcher'); report = self.exercise(fake, True)
        self.assertEqual(0, report['recovery_count']); self.assertFalse(any('force-stop' in c for c in fake.calls))


class HomeReadinessTests(unittest.TestCase):
    exercise = PolicyTests.exercise
    # Only these four focused methods run below; reuse the policy exercise helper.
    @staticmethod
    def resolver_fake(sequence, error=None):
        class ResolvingHome(FakeAdb):
            def __init__(self):
                super().__init__(error); self.resolutions = 0

            def run(self, args, **kwargs):
                result = super().run(args, **kwargs)
                if 'resolve-activity' in args:
                    value = sequence[min(self.resolutions, len(sequence) - 1)]
                    self.resolutions += 1
                    result.stdout = (value + '\n').encode()
                return result
        return ResolvingHome()

    def test_initial_setup_then_real_home_is_read_only_and_records_history(self):
        setup = 'com.google.android.googlesdksetup/.DefaultActivity'
        fallback = 'com.android.settings/.FallbackHome'
        fake = self.resolver_fake([setup, fallback, HOME]); report = self.exercise(fake)
        history = report['home_resolution_observations']
        self.assertEqual(['waiting_for_real_launcher', 'waiting_for_real_launcher', 'allowlisted_launcher'], [r['state'] for r in history])
        self.assertEqual(['home.stdout.txt', 'home-01.stdout.txt', 'home-02.stdout.txt'], [r['raw_stdout'] for r in history])
        self.assertEqual(sorted(r['observed_monotonic'] for r in history), [r['observed_monotonic'] for r in history])
        self.assertTrue(report['success']); self.assertEqual(0, report['recovery_count'])
        self.assertGreaterEqual(report['stable_seconds'], 5)
        self.assertFalse(any('force-stop' in c or 'start' in c for c in fake.calls))

    def test_persistent_setup_uses_only_original_deadline_without_mutation(self):
        for home in ('com.google.android.googlesdksetup/.DefaultActivity', 'com.android.settings/.FallbackHome'):
            fake = self.resolver_fake([home]); report = self.exercise(fake, True)
            self.assertFalse(report['success']); self.assertEqual(0, report['recovery_count'])
            self.assertEqual([], report['observations']); self.assertGreater(fake.resolutions, 1)
            self.assertLessEqual(fake.clock, 120.1)
            self.assertFalse(any('force-stop' in c or 'start' in c for c in fake.calls))

    def test_other_unknown_malformed_or_similar_setup_home_fails_immediately(self):
        for home in ('com.other.launcher/.Home', 'com.google.android.googlesdksetup/.DifferentActivity',
                     'com.android.settings/.FallbackHomeOther', 'No activity found', HOME + '\n' + HOME):
            fake = self.resolver_fake([home]); report = self.exercise(fake, True)
            self.assertFalse(report['success']); self.assertEqual(1, fake.resolutions)
            self.assertEqual('invalid_or_untrusted_home', report['home_resolution_observations'][0]['state'])
            self.assertFalse(any('force-stop' in c or 'start' in c for c in fake.calls))

    def test_setup_then_active_real_launcher_recovers_only_allowlisted_launcher(self):
        fake = self.resolver_fake(['com.google.android.googlesdksetup/.DefaultActivity', HOME], error='launcher')
        report = self.exercise(fake)
        self.assertEqual('recovered_boot', report['classification']); self.assertEqual(1, report['recovery_count'])
        actions = [c for c in fake.calls if 'force-stop' in c or 'start' in c]
        self.assertEqual(2, len(actions))
        self.assertEqual(PKG, actions[0][-1]); self.assertEqual(HOME, actions[1][-1])
        self.assertFalse(any('googlesdksetup' in ' '.join(c) for c in actions))



class StartupReadinessTests(unittest.TestCase):
    exercise = PolicyTests.exercise

    def flaky(self, phase, errors):
        class Flaky(FakeAdb):
            attempts = 0
            def run(self, args, **kwargs):
                target = ('sys.boot_completed' in args if phase == 'boot' else args[-2:] == ['logcat', '-c'])
                if target:
                    self.attempts += 1
                    if self.attempts <= len(errors):
                        self.clock += .05; self.calls.append(args)
                        error = errors[self.attempts-1]
                        if error == 'timeout': raise subprocess.TimeoutExpired(args, kwargs['timeout'])
                        return subprocess.CompletedProcess(args, 1, b'', error.encode())
                return super().run(args, **kwargs)
        return Flaky()

    def test_observed_boot_offline_then_real_success_no_recovery(self):
        fake = self.flaky('boot', ['adb: device offline'])
        report = self.exercise(fake)
        self.assertTrue(report['success']); self.assertEqual(2, report['boot_completed_attempts'])
        self.assertEqual(0, report['recovery_count'])
        self.assertEqual('offline', report['startup_transport_waits'][0]['reason'])

    def test_persistent_boot_offline_bounded_to_five_observations(self):
        fake = self.flaky('boot', ['adb: device offline'] * 10)
        report = self.exercise(fake, True)
        self.assertFalse(report['success']); self.assertEqual(5, fake.attempts)
        self.assertFalse(any('force-stop' in c or 'start' in c for c in fake.calls))

    def test_unknown_boot_error_not_retried(self):
        fake = self.flaky('boot', ['permission denied'])
        self.exercise(fake, True); self.assertEqual(1, fake.attempts)

    def test_logclear_observed_main_failure_requires_later_actual_success(self):
        fake = self.flaky('logclear', ["failed to clear the 'main' log"])
        report = self.exercise(fake)
        self.assertTrue(report['logcat_clear_verified']); self.assertEqual(2, report['logcat_clear_attempts'])
        self.assertEqual(0, report['recovery_count'])
        self.assertEqual(1, len(report['startup_transport_waits']))

    def test_persistent_logclear_never_permits_instrumentation(self):
        fake = self.flaky('logclear', ["failed to clear the 'main' log"] * 5)
        report = self.exercise(fake, True)
        self.assertFalse(report['success']); self.assertEqual(3, fake.attempts)
        self.assertNotIn('logcat_clear_verified', report)

    def test_unknown_logclear_error_not_retried(self):
        fake = self.flaky('logclear', ['Permission denied'])
        self.exercise(fake, True); self.assertEqual(1, fake.attempts)

    def test_uncertain_logclear_timeout_not_retried(self):
        fake = self.flaky('logclear', ['timeout'])
        self.exercise(fake, True); self.assertEqual(1, fake.attempts)

    def test_extra_device_during_readiness_fails_closed(self):
        fake = self.flaky('boot', ['adb: device offline'])
        original = fake.run
        def added(args, **kwargs):
            if args == ['adb', 'devices'] and fake.attempts:
                fake.calls.append(args)
                return subprocess.CompletedProcess(args, 0,
                    b'List of devices attached\nemulator-5554\tdevice\nphysical\tdevice\n', b'')
            return original(args, **kwargs)
        fake.run = added
        self.exercise(fake, True); self.assertEqual(1, fake.attempts)

    def test_logclear_success_does_not_mask_changed_health(self):
        fake = FakeAdb(); original = fake.run
        def changed(args, **kwargs):
            result = original(args, **kwargs)
            if args[-2:] == ['logcat', '-c']: fake.error = 'systemui'
            return result
        fake.run = changed
        report = self.exercise(fake, True)
        self.assertFalse(report['success']); self.assertNotIn('logcat_clear_verified', report)

if __name__ == '__main__': unittest.main()
