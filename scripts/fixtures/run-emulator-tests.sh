#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT=build/emulator-evidence
mkdir -p "$OUT"
sha256sum app/src/androidTest/java/com/rdp/sync/FixtureAppLocaleRule.kt >"$OUT/fixture-app-locale-source.sha256"
python3 scripts/fixtures/emulator_boot_health.py "$OUT/boot-health"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell getprop ro.build.fingerprint >build/emulator-evidence/emulator-fingerprint.txt
adb shell getprop ro.product.cpu.abilist >build/emulator-evidence/emulator-abis.txt
evidence_collection_attempted=false
cleanup() {
  local result=$?
  trap - EXIT
  set +e
  # Failed instrumentation keeps its original nonzero result. No test/action is retried.
  if [[ "$evidence_collection_attempted" != true ]]; then
    python3 scripts/fixtures/collect_android_evidence.py classic "$OUT" --best-effort
  fi
  timeout 10 adb logcat -d >"$OUT/logcat.txt"
  exit "$result"
}
trap cleanup EXIT
# Only these fixture-owned exports are reset; no old ledger can satisfy this attempt.
for directory in rdpsync-evidence; do
  adb shell rm -rf "/sdcard/Download/$directory"
  adb shell test ! -e "/sdcard/Download/$directory"
  rm -rf "$OUT/$directory"
done
adb shell date +%s >"$OUT/evidence-start-device-seconds.txt"
touch build/emulator-evidence/instrumentation-start.marker
./gradlew --no-daemon -PnativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.rdpFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.rdpTlsFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.fixtureFingerprint="$RDP_FIXTURE_FINGERPRINT" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rdp.sync.NativeRuntimeInstrumentedTest,com.rdp.sync.NativeTlsInstrumentedTest,com.rdp.sync.RdpFixtureInstrumentedTest,com.rdp.sync.security.CredentialStorageInstrumentedTest \
  :app:connectedDebugAndroidTest
evidence_collection_attempted=true
python3 scripts/fixtures/collect_android_evidence.py classic "$OUT"

python3 scripts/fixtures/verify_android_results.py \
  app/build/outputs/androidTest-results/connected/debug \
  build/emulator-evidence/instrumentation-start.marker \
  build/emulator-evidence/verified-android-test-counts.json
