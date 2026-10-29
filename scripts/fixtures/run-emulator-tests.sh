#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
mkdir -p build/emulator-evidence
adb logcat -c
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell getprop ro.build.fingerprint >build/emulator-evidence/emulator-fingerprint.txt
adb shell getprop ro.product.cpu.abilist >build/emulator-evidence/emulator-abis.txt
cleanup() {
  adb logcat -d >build/emulator-evidence/logcat.txt || true
  adb pull /sdcard/Download/rdpsync-evidence build/emulator-evidence/ || true
  adb exec-out screencap -p >build/emulator-evidence/final-emulator.png || true
}
trap cleanup EXIT
touch build/emulator-evidence/instrumentation-start.marker
./gradlew --no-daemon -PnativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.rdpFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.rdpTlsFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.fixtureFingerprint="$RDP_FIXTURE_FINGERPRINT" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rdp.sync.NativeRuntimeInstrumentedTest,com.rdp.sync.NativeTlsInstrumentedTest,com.rdp.sync.RdpFixtureInstrumentedTest,com.rdp.sync.security.CredentialStorageInstrumentedTest \
  :app:connectedDebugAndroidTest
python3 scripts/fixtures/verify_android_results.py \
  app/build/outputs/androidTest-results/connected/debug \
  build/emulator-evidence/instrumentation-start.marker \
  build/emulator-evidence/verified-android-test-counts.json
