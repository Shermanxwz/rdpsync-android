#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
OUT=build/gnome-evidence
adb logcat -c
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell getprop ro.build.fingerprint >"$OUT/emulator-fingerprint.txt"
cleanup() {
  adb logcat -d >"$OUT/android-logcat.txt" || true
  adb pull /sdcard/Download/rdpsync-gnome-evidence "$OUT/" || true
  adb exec-out screencap -p >"$OUT/final-emulator.png" || true
}
trap cleanup EXIT
touch "$OUT/instrumentation-start.marker"
./gradlew --no-daemon -PnativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.gnomeFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.gnomeFixtureMode="$GNOME_FIXTURE_MODE" \
  -Pandroid.testInstrumentationRunnerArguments.gnomeFixtureFingerprint="$GNOME_FIXTURE_FINGERPRINT" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rdp.sync.NativeRuntimeInstrumentedTest,com.rdp.sync.GnomeFixtureInstrumentedTest \
  :app:connectedDebugAndroidTest
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
assert classes=={'com.rdp.sync.NativeRuntimeInstrumentedTest':1,'com.rdp.sync.GnomeFixtureInstrumentedTest':1},classes
(out/'verified-android-tests.json').write_text(json.dumps(dict(tests=2,failures=0,errors=0,skipped=0,classes=classes),indent=2))
PY
