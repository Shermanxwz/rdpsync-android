#!/usr/bin/env python3
"""Temporary-fixture shell checks; no real SDK removal or Android runtime test."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from reclaim_api29_sdk import installed_packages, reclaim_candidates

ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / 'scripts/fixtures/prepare-api29-prebuilt.sh'


class PrebuiltContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        script = self.root / 'scripts/fixtures/prepare-api29-prebuilt.sh'
        script.parent.mkdir(parents=True)
        shutil.copyfile(PREPARE, script)
        shutil.copyfile(ROOT/'scripts/fixtures/reclaim_api29_sdk.py', script.parent/'reclaim_api29_sdk.py')
        self.script = script
        self.sdk = self.root / 'sdk'
        self.ndk = self.sdk / 'ndk/28.0.13004108'
        self.ndk.mkdir(parents=True)
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 28.0.13004108\n')
        self.inputs = [self.root / path for path in (
            'app/build/outputs/apk/debug/app-debug.apk',
            'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
            'app/build/outputs/apk/debug/output-metadata.json',
            'app/build/outputs/apk/androidTest/debug/output-metadata.json',
            'build/freerdp-android/rdpsync-native-provenance-x86_64.json',
            'app/src/main/jniLibs/x86_64/librdpsync.so')]
        for path in self.inputs:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'SYNTHETIC prebuilt input, not an APK')
        self.bin = self.root / 'bin'; self.bin.mkdir()
        self.command('git', '#!/bin/sh\nprintf "%040d\\n" 1\n')
        self.command('sdkmanager', """#!/usr/bin/env python3
import os, pathlib, shutil, sys
root = pathlib.Path(os.environ['HOME'])
with (root/'sdk-calls.txt').open('a') as f: f.write(repr(sys.argv[1:])+'\\n')
removed_path = root/'removed-packages.txt'
removed = removed_path.read_text().splitlines() if removed_path.exists() else []
if sys.argv[1:] == ['--list_installed']:
    installed = ['ndk;28.0.13004108', 'ndk;27.2.12479018',
        'system-images;android-29;default;x86_64', 'system-images;android-35;google_apis;x86_64',
        'emulator', 'platform-tools', 'build-tools;35.0.0', 'platforms;android-35']
    if os.environ.get('MOCK_BAD_PACKAGE') == '1': installed.append('ndk;../../unsafe')
    print('Installed packages:\\n  Path | Version | Description | Location\\n  ------- | ------- | ------- | -------')
    for package in installed:
        if package not in removed: print(package+' | 1 | mock package | mock')
elif sys.argv[1] == '--uninstall':
    package = sys.argv[2]
    if package != 'ndk;28.0.13004108' or os.environ.get('MOCK_KEEP_NDK') != '1':
        removed.append(package)
        if package == 'ndk;28.0.13004108': shutil.rmtree(root/'sdk/ndk/28.0.13004108')
    if os.environ.get('MOCK_EXTRA_REMOVAL') == '1': removed.append('emulator')
    removed_path.write_text('\\n'.join(removed))
    if os.environ.get('MOCK_MUTATE_APK') == '1':
        (root/'app/build/outputs/apk/debug/app-debug.apk').write_bytes(b'changed')
""")
        self.env = dict(os.environ, HOME=str(self.root), ANDROID_HOME=str(self.sdk),
                        GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted',
                        PATH=str(self.bin)+os.pathsep+os.environ['PATH'])

    def command(self, name, source):
        path = self.bin/name
        path.write_text(source); path.chmod(0o755)

    def run_prepare(self, **extra):
        return subprocess.run(['bash', str(self.script)], cwd=self.root,
                              env=dict(self.env, **extra), text=True, capture_output=True)

    def test_guard_rejects_nonhost_before_sdk_mutation(self):
        result = self.run_prepare(RUNNER_ENVIRONMENT='self-hosted')
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(self.ndk.is_dir())
        self.assertFalse((self.root/'sdk-calls.txt').exists())

    def test_missing_prebuilt_input_rejects_before_sdk_mutation(self):
        self.inputs[0].unlink()
        self.assertNotEqual(0, self.run_prepare().returncode)
        self.assertFalse((self.root/'sdk-calls.txt').exists())

    def test_wrong_ndk_version_rejects_before_sdk_mutation(self):
        (self.ndk/'source.properties').write_text('Pkg.Revision = 27.0.1\n')
        self.assertNotEqual(0, self.run_prepare().returncode)
        self.assertFalse((self.root/'sdk-calls.txt').exists())

    def test_exact_package_removed_and_all_prebuilt_bytes_preserved(self):
        before = [p.read_bytes() for p in self.inputs]
        result = self.run_prepare()
        # The real host's free-space preflight may fail in a small sandbox; SDK
        # scope and byte-integrity assertions still run before that preflight.
        receipt = json.loads((self.root/'build/api29-prebuilt-evidence/disk-space.json').read_text())
        self.assertEqual(receipt['enough_space'], result.returncode == 0, result.stderr)
        self.assertEqual(8*1024**3, receipt['minimum_bytes'])
        self.assertEqual(before, [p.read_bytes() for p in self.inputs])
        self.assertFalse(self.ndk.exists())
        calls = (self.root/'sdk-calls.txt').read_text().splitlines()
        self.assertEqual(["['emulator', 'system-images;android-29;default;x86_64']",
                          "['--list_installed']", "['--uninstall', 'ndk;27.2.12479018']",
                          "['--uninstall', 'ndk;28.0.13004108']",
                          "['--uninstall', 'system-images;android-35;google_apis;x86_64']",
                          "['--list_installed']"], calls)


    def test_mutated_apk_and_unremoved_compiler_fail(self):
        self.assertNotEqual(0, self.run_prepare(MOCK_MUTATE_APK='1').returncode)
        self.assertFalse((self.root/'build/api29-prebuilt-evidence/disk-space.json').exists())

    def test_unremoved_compiler_fails(self):
        self.assertNotEqual(0, self.run_prepare(MOCK_KEEP_NDK='1').returncode)
        self.assertTrue(self.ndk.exists())

    def test_insufficient_postimage_disk_space_fails_closed(self):
        # Inject only this subprocess fixture's filesystem observation; never
        # allocate/fill a real disk to exercise the low-space branch.
        (self.root/'sitecustomize.py').write_text(
            'import os, types\nos.statvfs = lambda path: types.SimpleNamespace(f_bavail=1, f_frsize=1)\n')
        result = self.run_prepare(PYTHONPATH=str(self.root))
        self.assertNotEqual(0, result.returncode)
        receipt = json.loads((self.root/'build/api29-prebuilt-evidence/disk-space.json').read_text())
        self.assertFalse(receipt['enough_space'])
        self.assertEqual(1, receipt['available_bytes'])
        self.assertIn('Insufficient space', result.stdout)

    def test_stale_evidence_is_not_overwritten(self):
        (self.root/'build/api29-prebuilt-evidence').mkdir()
        self.assertNotEqual(0, self.run_prepare().returncode)
        self.assertFalse((self.root/'sdk-calls.txt').exists())

    def test_unexpected_package_delta_fails_closed(self):
        result = self.run_prepare(MOCK_EXTRA_REMOVAL='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('delta does not match', result.stdout)

    def test_malformed_enumerated_identity_never_uninstalls(self):
        self.assertNotEqual(0, self.run_prepare(MOCK_BAD_PACKAGE='1').returncode)
        self.assertNotIn('--uninstall', (self.root/'sdk-calls.txt').read_text())
        self.assertTrue(self.ndk.exists())

    def test_parser_preserves_all_api29_images_and_nonallowlisted_packages(self):
        packages = ['ndk;28.0.13004108', 'system-images;android-35;google_apis;x86_64',
                    'system-images;android-29;default;x86_64',
                    'system-images;android-29-ext4;google_apis;arm64-v8a',
                    'emulator', 'platform-tools', 'build-tools;35.0.0', 'platforms;android-35',
                    'cmake;3.22.1', 'sources;android-35']
        self.assertEqual(packages[:2], reclaim_candidates(packages))

    def test_parser_bounds_and_rejects_malformed_candidates(self):
        for package in ['ndk;../../path', 'ndk;28.0.1 extra',
                        'system-images;android-35;tag;../../path']:
            with self.assertRaises(ValueError): reclaim_candidates([package])
        with self.assertRaises(ValueError): reclaim_candidates([f'ndk;1.2.{i}' for i in range(33)])
        with self.assertRaises(ValueError): installed_packages('Available packages:\nndk;1.2.3 | 1 | fake')
        with self.assertRaises(ValueError): installed_packages('Installed packages:\nndk;1.2.3 | 1\nndk;1.2.3 | 1')

    def test_instrumentation_consumes_prebuilt_without_skipping_tests(self):
        source = (ROOT/'scripts/fixtures/run-api29-layout-tests.sh').read_text()
        self.assertIn('  :app:connectedDebugAndroidTest\n', source)
        self.assertNotIn(' -x ', source)
        self.assertIn('-I scripts/fixtures/api29-prebuilt.init.gradle', source)
        init = (ROOT/'scripts/fixtures/api29-prebuilt.init.gradle').read_text()
        self.assertIn("gradle.startParameter.taskNames != [':app:connectedDebugAndroidTest']", init)
        self.assertIn("['packageDebug', 'packageDebugAndroidTest', 'stripDebugDebugSymbols', 'stripDebugAndroidTestDebugSymbols']", init)
        self.assertIn('app.tasks.named(name).configure { enabled = false }', init)
        self.assertIn('sha256sum -c build/api29-prebuilt-evidence/input-sha256.txt >"$OUT/prebuilt-after.txt"\ncollect', source)
        workflow = (ROOT/'.github/workflows/api29-layout-emulator.yml').read_text()
        self.assertLess(workflow.index('actions/cache/save@v4'), workflow.index('run: bash scripts/fixtures/prepare-api29-prebuilt.sh'))
        self.assertLess(workflow.index('run: bash scripts/fixtures/prepare-api29-prebuilt.sh'), workflow.index('uses: reactivecircus/android-emulator-runner@v2'))
        self.assertIn('build/api29-prebuilt-evidence/', workflow)


if __name__ == '__main__':
    unittest.main()
