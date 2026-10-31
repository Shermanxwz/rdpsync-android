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

def stream_receipt(scope='ime', size=100):
    result = dict(scope=scope, source_bytes=size+30, selected_bytes=size, source_sha256='a'*64,
                  producer_eof=True, complete=True, failure='', exit_status=0, exit_trailers=1,
                  source_limit=16*1024*1024, line_prefix_limit=8192, selected_limit=65536, lines=10,
                  command_source='dumpsys -t 2 settings' if scope=='settings' else 'dumpsys -t 5 input_method',
                  command_timeout_ms=5000 if scope=='settings' else 6000)
    for key in ('secure_sections','system_sections','setting_rows') if scope=='settings' else ('service_headers','observer_rows','service_completions','selected_ids','bound_ids','method_ids'):
        result[key]=1
    result.update(manager_headers=1, client_headers=0, client_method_rows=0, manager_observer_rows=0)
    return result

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
        self.assertIn('initial.cache == if (original == "1") "2" else "1"', src)
        self.assertIn('current.instance == initial.instance && current.method == initial.method', src)
        self.assertIn('if (original != "1")', src)
        self.assertIn('consumed = current.cache == "2"', src)
    def test_before_activity_and_restoration(self):
        test = (ROOT/'app/src/androidTest/java/com/rdp/sync/RdpFixtureInstrumentedTest.kt').read_text()
        self.assertIn('private val imeSetting = FixtureImeSettingRule()', test)
        # The navigation-only test never opens the IME. Only the real keyboard
        # method owns prerequisite receipts; it still runs before Activity setup.
        self.assertRegex(test, r'@get:Rule\(order = 1\) val keyboardPrerequisite = org\.junit\.rules\.TestRule \{ base, description ->\s*'
                         r'if \(description\.methodName == "realDesktopDisplayInputResizeAndWeakLink"\)\s*'
                         r'imeSetting\.apply\(base, description\) else base\s*\}')
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
        self.assertIn('val initial = observeIme(6_000)', src)
        self.assertIn('val current = observeIme(6_000)', src)
        self.assertIn('observeIme(minOf(6_000, remaining))', src)
        self.assertIn('dumpsys -t 5 input_method', src)
        self.assertIn('single-attempt-per-observation', src)
        self.assertIn('command_timeout_ms', src)
        self.assertNotIn('ime set ', src)
        self.assertIn('executeShellCommandRw("sh")', src)
        self.assertIn('FixtureDumpProjection.shellScript(command)', src)
        self.assertIn('ownedInput.getAndSet(null)', src)
        self.assertIn('val buffer = ByteArray(4096)', src)
        self.assertNotIn('executeShellCommand(executed)', src)
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
                    wrote_setting=True, readiness_mode='same-service-observer-transition', ime=GBOARD, initial_service=initial, consumption_samples=[after],
                    setup_elapsed_ms=1, ready_elapsed_ms=12, consumption_deadline_elapsed_ms=15, restore_elapsed_ms=20)
        good['stream_projections'] = dict(initial=stream_receipt(),latest=stream_receipt())
        collector.validate_ime_setting(good)
        collector.validate_ime_setting(dict(good, original='null'))
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
        self.assertLess(src.index('val initial = observeIme'), src.index('if (original == "1") observeEnabledSetting()'))
    def test_observed_enabled_no_write_and_raw_binding(self):
        import tempfile, hashlib
        spec = importlib.util.spec_from_file_location('observed_collector', ROOT/'scripts/fixtures/collect_android_evidence.py')
        collector = importlib.util.module_from_spec(spec); spec.loader.exec_module(collector)
        initial = dict(selected_ime=[GBOARD], bound_ime=[GBOARD], observer_cache=['2'],
                       service_instance=['Gboard@aaa'], method_identity=['Invoker@bbb'], dump_complete=True)
        row = '_id:42 name:show_ime_with_hard_keyboard pkg:com.google.android.googlesdksetup value:1 default:1 defaultSystemSet:true'
        env = dict(schema=1, success=True, action_sha='a421e43855164a8197daf9d8d40fe71c6996bb0d',
                   source_sha='a'*40, run_id='123', force_avd_creation=True, enable_hw_keyboard=False,
                   fresh_avd_absence_verified=True, no_snapshot=True, avd_name='test', device_sdk='35',
                   avd_config_mtime=2, setup_started_epoch=1, image_sysdir='/sdk/system-images/android-35/google_apis/x86_64',
                   device_fingerprint='synthetic', device_boot_id='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', preflight={'success':True,'avd_root':'/home/runner/.android/avd'},
                   avd_metadata_path='/home/runner/.android/avd/test.ini', avd_config_path='/home/runner/.android/avd/test.avd/config.ini',
                   avd_metadata_mtime=2,avd_identity_proof='fresh named metadata path plus live emulator console name')
        with tempfile.TemporaryDirectory() as directory:
            out=Path(directory)
            for field,name in (('avd_metadata_sha256','avd-metadata.ini'),('preflight_sha256','ime-preflight.json'),('avd_config_sha256','avd-config.ini'),
                               ('image_properties_sha256','image-source.properties'),('image_package_sha256','image-package.xml'),('workflow_sha256','workflow.yml')):
                raw=json.dumps(env['preflight']).encode() if name=='ime-preflight.json' else b'path=/home/runner/.android/avd/test.avd\ntarget=android-35\n' if name=='avd-metadata.ini' else b'synthetic'
                (out/name).write_bytes(raw);env[field]=hashlib.sha256(raw).hexdigest()
            (out/'ime-environment.json').write_text(json.dumps(env))
            (out/'tested-sha.txt').write_text(env['source_sha'])
            (out/'emulator-fingerprint.txt').write_text(env['device_fingerprint'])
            good=dict(verified_before_activity=True,restored=True,original='1',requested='1',wrote_setting=False,
                      readiness_mode='observed-enabled-no-write',ime=GBOARD,initial_service=initial,
                      observed_service=dict(initial),setup_elapsed_ms=1,ready_elapsed_ms=2,restore_elapsed_ms=20,
                      observed_setting=dict(provider_value='1',resolved_default=False,setting_row=row,environment=env),
                      notification_consumption_proven=False,pending_notifications_excluded=False)
            dumps=dict(zip(collector.IME_DUMPS,[dump(2),dump(2),'SECURE SETTINGS (user 0)\n'+row+'\nSYSTEM SETTINGS (user 0)\n','trace\nfalse\n',json.dumps(env)]))
            good['stream_projections'] = dict(initial=stream_receipt(size=len(dumps[collector.IME_DUMPS[0]].encode())),
                latest=stream_receipt(size=len(dumps[collector.IME_DUMPS[1]].encode())),
                provider=stream_receipt('settings',len(dumps[collector.IME_DUMPS[2]].encode())))
            collector.validate_ime_setting(good);collector.validate_ime_raw(good,dumps,out)
            for field, badvalue in [('producer_eof',False),('complete',False),('exit_trailers',2),('exit_status',1),('source_bytes',17*1024*1024),('service_headers',2),('source_sha256',''),('failure','late error'),('manager_headers',2),('client_headers',3),('client_method_rows',1),('manager_observer_rows',2)]:
                bad=copy.deepcopy(good);bad['stream_projections']['initial'][field]=badvalue
                with self.assertRaises(AssertionError,msg=field):collector.validate_ime_setting(bad)
            for key,badvalue in [('success',False),('fresh_avd_absence_verified',False),('no_snapshot',False),('enable_hw_keyboard',True),('device_sdk','34'),('avd_config_mtime',0),('action_sha','b'*40),('avd_metadata_mtime',0),('avd_metadata_path','/other/test.ini'),('avd_identity_proof','placeholder')]:
                bad=copy.deepcopy(good);bad['observed_setting']['environment'][key]=badvalue
                with self.assertRaises(AssertionError,msg=key):collector.validate_ime_setting(bad)
            # Attribution is observed, not restricted to the image default or a privileged writer.
            for observed_row in (row.replace('pkg:com.google.android.googlesdksetup','pkg:android'),
                                 row.replace('default:1','default:0').replace('defaultSystemSet:true','defaultSystemSet:false')):
                allowed=copy.deepcopy(good);allowed['observed_setting']['setting_row']=observed_row
                collector.validate_ime_setting(allowed)
            for altered in (row.replace('value:1','value:0'), row.replace('pkg:com.google.android.googlesdksetup','pkg:'), row+' trailing'):
                bad=copy.deepcopy(good);bad['observed_setting']['setting_row']=altered
                with self.assertRaises(AssertionError):collector.validate_ime_setting(bad)
            for key,badvalue in [('notification_consumption_proven',True),('pending_notifications_excluded',True),
                                 ('consumption_samples',[]),('consumption_deadline_elapsed_ms',15),('factory_default',{})]:
                bad=copy.deepcopy(good);bad[key]=badvalue
                with self.assertRaises(AssertionError,msg=key):collector.validate_ime_setting(bad)
            for field,badvalue in [('observer_cache',['1']),('bound_ime',['other/IME']),('service_instance',['Gboard@ccc']),
                                   ('method_identity',['Invoker@ddd']),('dump_complete',False),('consumed',True)]:
                bad=copy.deepcopy(good);bad['observed_service'][field]=badvalue
                with self.assertRaises(AssertionError,msg=field):collector.validate_ime_setting(bad)
            for key,badvalue in [('wrote_setting',True),('write_started_elapsed_ms',0),('readiness_mode','same-service-observer-transition')]:
                bad=copy.deepcopy(good);bad[key]=badvalue
                with self.assertRaises(AssertionError,msg=key):collector.validate_ime_setting(bad)
            for changed in [dict(dumps,**{'ime-setting-overlay.txt':'true'}),
                            dict(dumps,**{'ime-setting-provider-dump.txt':dumps['ime-setting-provider-dump.txt'].replace('pkg:com.google.android.googlesdksetup','pkg:com.android.shell')}),
                            dict(dumps,**{'ime-setting-provider-dump.txt':dumps['ime-setting-provider-dump.txt'].replace(row,row+'\n'+row)}),
                            dict(dumps,**{'ime-setting-environment.txt':'{}'})]:
                with self.assertRaises(AssertionError):collector.validate_ime_raw(good,changed,out)
            metadata_path = out/'avd-metadata.ini'
            original_metadata = metadata_path.read_bytes()
            metadata_path.write_bytes(original_metadata + b'# tampered\n')
            with self.assertRaises(AssertionError):collector.validate_ime_raw(good,dumps,out)
            metadata_path.write_bytes(original_metadata)
            collector.validate_ime_raw(good,dumps,out)
            for raw in (b'path=/other/test.avd\ntarget=android-35\n',
                        original_metadata + b'path=/home/runner/.android/avd/test.avd\n',
                        original_metadata.replace(b'android-35',b'android-34')):
                metadata_path.write_bytes(raw)
                changed = copy.deepcopy(good)
                changed['observed_setting']['environment']['avd_metadata_sha256'] = hashlib.sha256(raw).hexdigest()
                changed_env = changed['observed_setting']['environment']
                (out/'ime-environment.json').write_text(json.dumps(changed_env))
                changed_dumps = dict(dumps, **{'ime-setting-environment.txt':json.dumps(changed_env)})
                with self.assertRaises(AssertionError):collector.validate_ime_raw(changed,changed_dumps,out)
            metadata_path.write_bytes(original_metadata)
            (out/'ime-environment.json').write_text(json.dumps(env))
            collector.validate_ime_raw(good,dumps,out)
            (out/'tested-sha.txt').write_text('b'*40)
            with self.assertRaises(AssertionError):collector.validate_ime_raw(good,dumps,out)
            (out/'tested-sha.txt').write_text(env['source_sha'])
            (out/'avd-config.ini').write_text('tampered')
            with self.assertRaises(AssertionError):collector.validate_ime_raw(good,dumps,out)

if __name__ == '__main__': unittest.main()
