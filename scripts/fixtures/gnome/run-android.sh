#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
OUT=build/gnome-evidence
sha256sum app/src/androidTest/java/com/rdp/sync/transfer/*Diagnostic*.kt >"$OUT/transport-diagnostic-source.sha256"
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
    python3 scripts/fixtures/collect_android_evidence.py gnome "$OUT" --best-effort
  fi
  timeout 10 adb logcat -d >"$OUT/android-logcat.txt"
  exit "$result"
}
trap cleanup EXIT
# Only these fixture-owned exports are reset; no old ledger can satisfy this attempt.
for directory in rdpsync-gnome-evidence rdpsync-gnome-transfer-evidence rdpsync-gnome-transport-diagnostic; do
  adb shell rm -rf "/sdcard/Download/$directory"
  adb shell test ! -e "/sdcard/Download/$directory"
  rm -rf "$OUT/$directory"
done
adb shell date +%s >"$OUT/evidence-start-device-seconds.txt"
touch "$OUT/instrumentation-start.marker"
CALIBRATION=$(base64 -w0 "$OUT/gfx-reference/pixel-calibration.json")
./gradlew --no-daemon -PnativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.gnomeFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.gnomeFixtureMode="$GNOME_FIXTURE_MODE" \
  -Pandroid.testInstrumentationRunnerArguments.gnomePixelCalibration="$CALIBRATION" \
  -Pandroid.testInstrumentationRunnerArguments.gnomeFixtureFingerprint="$GNOME_FIXTURE_FINGERPRINT" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rdp.sync.NativeRuntimeInstrumentedTest,com.rdp.sync.transfer.TransferStorageInstrumentedTest,com.rdp.sync.GnomeFixtureInstrumentedTest,com.rdp.sync.transfer.GnomeNativeClipboardDiagnosticTest,com.rdp.sync.GnomeTransferInstrumentedTest \
  :app:connectedDebugAndroidTest
evidence_collection_attempted=true
python3 scripts/fixtures/collect_android_evidence.py gnome "$OUT"
python3 - <<'PY'
import collections,json,pathlib,xml.etree.ElementTree as ET
out=pathlib.Path('build/gnome-evidence'); base=pathlib.Path('app/build/outputs/androidTest-results/connected/debug')
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
assert classes=={'com.rdp.sync.NativeRuntimeInstrumentedTest':1,'com.rdp.sync.GnomeFixtureInstrumentedTest':1,
                'com.rdp.sync.GnomeTransferInstrumentedTest':1,'com.rdp.sync.transfer.GnomeNativeClipboardDiagnosticTest':1,'com.rdp.sync.transfer.TransferStorageInstrumentedTest':3},classes
collection=json.loads((out/'evidence-collection.json').read_text())
assert collection['complete'] is True and collection['best_effort'] is False, 'Missing fresh mandatory evidence'
diagnostic=json.loads((out/'rdpsync-gnome-transport-diagnostic/measurements.json').read_text())
assert diagnostic['diagnostic_only'] is True and diagnostic['saf_ui_covered'] is False
assert diagnostic['success'] is True and diagnostic['no_retained_native_offer'] is True
assert diagnostic['private_snapshot_removed_after_close'] is True and diagnostic['disconnected'] is True
(out/'verified-android-tests.json').write_text(json.dumps(dict(tests=7,failures=0,errors=0,skipped=0,classes=classes),indent=2))
PY
