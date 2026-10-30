#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT=build/xrdp-file-evidence
sha256sum app/src/androidTest/java/com/rdp/sync/transfer/*Diagnostic*.kt >"$OUT/transport-diagnostic-source.sha256"
adb logcat -c
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell getprop ro.build.fingerprint >"$OUT/emulator-fingerprint.txt"
cleanup() {
  adb logcat -d >"$OUT/android-logcat.txt" || true
  adb pull /sdcard/Download/rdpsync-xrdp-transfer-evidence "$OUT/" || true
  adb pull /sdcard/Download/rdpsync-xrdp-transport-diagnostic "$OUT/" || true
  adb exec-out screencap -p >"$OUT/final-emulator.png" || true
}
trap cleanup EXIT
adb shell rm -rf /sdcard/Download/rdpsync-xrdp-transport-diagnostic
adb shell test ! -e /sdcard/Download/rdpsync-xrdp-transport-diagnostic
adb shell date +%s >"$OUT/diagnostic-start-device-seconds.txt"
touch "$OUT/instrumentation-start.marker"
./gradlew --no-daemon -PnativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.xrdpFileFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.fixtureFingerprint="$RDP_FIXTURE_FINGERPRINT" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rdp.sync.NativeRuntimeInstrumentedTest,com.rdp.sync.transfer.TransferStorageInstrumentedTest,com.rdp.sync.transfer.XrdpNativeClipboardDiagnosticTest,com.rdp.sync.XrdpTransferInstrumentedTest \
  :app:connectedDebugAndroidTest
adb shell stat -c %Y /sdcard/Download/rdpsync-xrdp-transport-diagnostic/measurements.json >"$OUT/diagnostic-device-mtime.txt"
adb pull /sdcard/Download/rdpsync-xrdp-transport-diagnostic "$OUT/"
python3 - <<'PY'
import collections,json,pathlib,xml.etree.ElementTree as ET
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
                'com.rdp.sync.XrdpTransferInstrumentedTest':1,'com.rdp.sync.transfer.XrdpNativeClipboardDiagnosticTest':1,'com.rdp.sync.transfer.TransferStorageInstrumentedTest':3},classes
assert int((out/'diagnostic-device-mtime.txt').read_text().strip()) >= int((out/'diagnostic-start-device-seconds.txt').read_text().strip()), 'Stale transport diagnostic'
diagnostic=json.loads((out/'rdpsync-xrdp-transport-diagnostic/measurements.json').read_text())
assert diagnostic['diagnostic_only'] is True and diagnostic['saf_ui_covered'] is False
assert diagnostic['success'] is True and diagnostic['no_retained_native_offer'] is True
assert diagnostic['private_snapshot_removed_after_close'] is True and diagnostic['disconnected'] is True
(out/'verified-android-tests.json').write_text(json.dumps(dict(tests=6,failures=0,errors=0,skipped=0,classes=classes),indent=2))
PY
