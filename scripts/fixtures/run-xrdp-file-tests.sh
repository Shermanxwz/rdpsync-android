#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT=build/xrdp-file-evidence
mkdir -p "$OUT"
sha256sum app/src/androidTest/java/com/rdp/sync/FixtureAppLocaleRule.kt >"$OUT/fixture-app-locale-source.sha256"
sha256sum app/src/androidTest/java/com/rdp/sync/BoundedFixtureScroll.kt >"$OUT/bounded-scroll-source.sha256"
sha256sum app/src/androidTest/java/com/rdp/sync/transfer/*Diagnostic*.kt >"$OUT/transport-diagnostic-source.sha256"
sha256sum app/src/androidTest/java/com/rdp/sync/{XrdpSharedDriveInstrumentedTest,SharedSpaceDocumentsUi}.kt app/src/androidTest/java/com/rdp/sync/sharedspace/* >"$OUT/shared-drive-source.sha256"
python3 scripts/fixtures/emulator_boot_health.py "$OUT/boot-health"
adb logcat -c
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell getprop ro.build.fingerprint >"$OUT/emulator-fingerprint.txt"
evidence_collection_attempted=false
cleanup() {
  local result=$?
  trap - EXIT
  set +e
  # Failed instrumentation keeps its original nonzero result. No test/action is retried.
  if [[ "$evidence_collection_attempted" != true ]]; then
    python3 scripts/fixtures/collect_android_evidence.py xrdp "$OUT" --best-effort
  fi
  timeout 10 adb logcat -d >"$OUT/android-logcat.txt"
  exit "$result"
}
trap cleanup EXIT
# Only these fixture-owned exports are reset; no old ledger can satisfy this attempt.
for directory in rdpsync-xrdp-transfer-evidence rdpsync-xrdp-transport-diagnostic rdpsync-shared-drive-evidence; do
  adb shell rm -rf "/sdcard/Download/$directory"
  adb shell test ! -e "/sdcard/Download/$directory"
  rm -rf "$OUT/$directory"
done
adb shell date +%s >"$OUT/evidence-start-device-seconds.txt"
touch "$OUT/instrumentation-start.marker"
./gradlew --no-daemon -PnativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.sourceCommit="$(git rev-parse HEAD)" \
  -Pandroid.testInstrumentationRunnerArguments.xrdpFileFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.fixtureFingerprint="$RDP_FIXTURE_FINGERPRINT" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rdp.sync.NativeRuntimeInstrumentedTest,com.rdp.sync.transfer.TransferStorageInstrumentedTest,com.rdp.sync.transfer.XrdpNativeClipboardDiagnosticTest,com.rdp.sync.XrdpTransferInstrumentedTest,com.rdp.sync.sharedspace.SharedSpaceStorageInstrumentedTest,com.rdp.sync.XrdpSharedDriveInstrumentedTest \
  :app:connectedDebugAndroidTest
evidence_collection_attempted=true
python3 scripts/fixtures/collect_android_evidence.py xrdp "$OUT"
python3 - <<'PY'
import collections,json,pathlib,subprocess,xml.etree.ElementTree as ET
out=pathlib.Path('build/xrdp-file-evidence'); base=pathlib.Path('app/build/outputs/androidTest-results/connected/debug')
classes=collections.Counter(); identities=set(); reports=list(base.rglob('TEST*.xml'))
assert reports, 'Missing actual Android XML'
for p in reports:
    assert p.stat().st_mtime_ns >= (out/'instrumentation-start.marker').stat().st_mtime_ns, 'Stale report'
    root=ET.parse(p).getroot()
    for s in root.iter('testsuite'):
        assert all(int(s.get(k,'0'))==0 for k in ('failures','errors','skipped')), 'Failed or skipped suite'
    for c in root.iter('testcase'):
        assert not any(c.find(k) is not None for k in ('failure','error','skipped'))
        identity=(c.get('classname'),c.get('name')); assert identity not in identities
        identities.add(identity);classes[identity[0]]+=1
assert classes=={'com.rdp.sync.NativeRuntimeInstrumentedTest':1,
                'com.rdp.sync.XrdpTransferInstrumentedTest':1,'com.rdp.sync.transfer.XrdpNativeClipboardDiagnosticTest':1,'com.rdp.sync.transfer.TransferStorageInstrumentedTest':3,
                'com.rdp.sync.sharedspace.SharedSpaceStorageInstrumentedTest':8,'com.rdp.sync.XrdpSharedDriveInstrumentedTest':1},classes
collection=json.loads((out/'evidence-collection.json').read_text())
assert collection['complete'] is True and collection['best_effort'] is False, 'Missing fresh mandatory evidence'
diagnostic=json.loads((out/'rdpsync-xrdp-transport-diagnostic/measurements.json').read_text())
assert diagnostic['diagnostic_only'] is True and diagnostic['saf_ui_covered'] is False
assert diagnostic['success'] is True and diagnostic['no_retained_native_offer'] is True
assert diagnostic['private_snapshot_removed_after_close'] is True and diagnostic['disconnected'] is True
drive=json.loads((out/'rdpsync-shared-drive-evidence/measurements.json').read_text())
assert drive['source_commit']==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
(out/'verified-android-tests.json').write_text(json.dumps(dict(tests=15,failures=0,errors=0,skipped=0,classes=classes),indent=2))
PY
