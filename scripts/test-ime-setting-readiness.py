#!/usr/bin/env python3
"""Host source-contract/parser checks, NOT an emulator or Gboard runtime test."""
import json
import copy
import importlib.util
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/java/com/rdp/sync/FixtureImeSettingRule.kt'
src = SOURCE.read_text()
# Exercise the exact Kotlin escaped regex literals (all use Java/Python shared syntax).
patterns = [json.loads('"' + p + '"') for p in re.findall(r'matches\("((?:\\.|[^"\\])*)"\)', src)]
GBOARD = 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'
def dump(cache=1, instance='Gboard@aaa', method='Invoker@bbb'):
    return f'''  mCurMethodId={GBOARD}
  mCurId={GBOARD} mHaveConnection=true mBoundToMethod=true
  mCurMethod={method}
Input method service state for {instance}:
  mSettingsObserver=SettingsObserver{{mShowImeWithHardKeyboard={cache}}}
  mNavigationBarController=ImeNavigationBarController
'''
def parse(text):
    values = [re.findall(p, text) for p in patterns]
    selected, bound, caches, instances, methods = values
    complete = 'mNavigationBarController=' in text and not any(s in text for s in ('DUMP TIMEOUT', 'Failed to dump', 'Permission Denial'))
    ready = complete and selected == [GBOARD] and bound == selected and all(len(v) == 1 for v in (caches, instances, methods)) and methods != ['null']
    return ready, values
class ReadinessContract(unittest.TestCase):
    def test_false_to_true_same_existing_service(self):
        ok, before = parse(dump(1)); self.assertTrue(ok)
        ok, after = parse(dump(2)); self.assertTrue(ok)
        self.assertEqual(before[3:], after[3:]); self.assertEqual(after[2], ['2'])
    def test_duplicate_partial_unknown_and_wrong_binding(self):
        for text in (dump()+dump(), dump().replace('mNavigationBarController=', 'truncated='), dump()+'DUMP TIMEOUT', dump()+'Failed to dump service', dump().replace(GBOARD, 'other/IME'), dump().replace('mHaveConnection=true', 'mHaveConnection=false')):
            self.assertFalse(parse(text)[0])
        self.assertNotEqual(parse(dump(0))[1][2], ['1'])
    def test_identity_turnover_never_proves_callback(self):
        before = parse(dump(1))[1]
        for after in (dump(2, instance='Gboard@ccc'), dump(2, method='Invoker@ddd')):
            self.assertNotEqual(before[3:], parse(after)[1][3:])
    def test_no_provider_only_or_unknown_cache_success(self):
        self.assertIn('initial.cache == "1"', src)
        self.assertIn('current.instance == initial.instance && current.method == initial.method', src)
        self.assertIn('if (original != "1")', src)
        self.assertIn('consumed = current.cache == "2"', src)
    def test_before_activity_and_restoration(self):
        test = (ROOT/'app/src/androidTest/java/com/rdp/sync/RdpFixtureInstrumentedTest.kt').read_text()
        self.assertIn('@get:Rule(order = 1) val imeSetting', test)
        self.assertIn('@get:Rule(order = 2) val compose', test)
        self.assertNotIn('show_ime_with_hard_keyboard', test)
        self.assertIn('failure.addSuppressed(cleanup)', src)
        self.assertIn('check(readSetting() == original)', src)
        self.assertIn('if (writeAttempted)', src)
        self.assertIn('base.evaluate()', src)
    def test_fixture_scope_and_budgets(self):
        self.assertIn('Build.VERSION.SDK_INT == 35', src)
        self.assertIn('getString("rdpFixture") == "true"', src)
        self.assertIn('SystemClock.elapsedRealtime() + 15_000', src)
        self.assertNotIn('sleep(10_000)', src)
        self.assertNotIn('ime set ', src)
        workflow = (ROOT/'.github/workflows/emulator.yml').read_text()
        self.assertIn('force-avd-creation: true', workflow)
        self.assertIn('enable-hw-keyboard: false', workflow)
    def test_actual_collector_state_machine_rejects_false_ready(self):
        path = ROOT/'scripts/fixtures/collect_android_evidence.py'
        spec = importlib.util.spec_from_file_location('collector', path)
        collector = importlib.util.module_from_spec(spec); spec.loader.exec_module(collector)
        initial = dict(selected_ime=[GBOARD], bound_ime=[GBOARD], observer_cache=['1'],
                       service_instance=['Gboard@aaa'], method_identity=['Invoker@bbb'], dump_complete=True)
        after = dict(initial, observer_cache=['2'], consumed=True)
        good = dict(verified_before_activity=True, restored=True, original='0', requested='1',
                    wrote_setting=True, ime=GBOARD, initial_service=initial, consumption_samples=[after],
                    setup_elapsed_ms=1, ready_elapsed_ms=12, consumption_deadline_elapsed_ms=15, restore_elapsed_ms=20)
        collector.validate_ime_setting(good)
        for mutation in ('new_service', 'unknown', 'already_true', 'late', 'not_restored', 'no_callback', 'cleanup_failure'):
            bad = copy.deepcopy(good)
            if mutation == 'new_service': bad['consumption_samples'][0]['service_instance'] = ['Gboard@ccc']
            if mutation == 'unknown': bad['initial_service']['observer_cache'] = ['0']
            if mutation == 'already_true': bad['initial_service']['observer_cache'] = ['2']
            if mutation == 'late': bad['ready_elapsed_ms'] = 16
            if mutation == 'not_restored': bad['restored'] = False
            if mutation == 'no_callback': bad['consumption_samples'][0]['observer_cache'] = ['1']
            if mutation == 'cleanup_failure': bad['restore_failure_type'] = 'failure'
            with self.assertRaises(AssertionError, msg=mutation): collector.validate_ime_setting(bad)
        unchanged = copy.deepcopy(good)
        unchanged.update(original='1', wrote_setting=False)
        unchanged['initial_service']['observer_cache'] = ['2']
        with self.assertRaises(AssertionError): collector.validate_ime_setting(unchanged)
        self.assertIn('check(original != "1")', src)
if __name__ == '__main__': unittest.main()
