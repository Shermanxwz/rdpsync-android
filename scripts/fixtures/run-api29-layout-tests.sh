#!/usr/bin/env bash
# Own standard-hosted emulator only; no RDP server, account, or network connection.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || { echo 'Hosted disposable runner required' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT=build/api29-layout-evidence
[[ ! -e "$OUT" ]] || { echo 'Fresh evidence directory required' >&2; exit 2; }
mkdir -p "$OUT"
export ANDROID_SERIAL=emulator-5554
ADB=(adb -s "$ANDROID_SERIAL")
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[[ "${#DEVICES[@]}" == 1 && "${DEVICES[0]}" == "$ANDROID_SERIAL" ]] || { echo 'Expected only the action-owned emulator-5554' >&2; exit 2; }
[[ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
[[ "$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" == 29 ]]
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
PACKAGE="$($AAPT dump badging "$APP_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
TEST_PACKAGE="$($AAPT dump badging "$TEST_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
[[ "$PACKAGE" =~ ^com\.rdp\.sync\.preview[0-9]*$ && "$TEST_PACKAGE" == "$PACKAGE.test" ]]
SHA=$(git rev-parse HEAD)
TREE=$(git rev-parse HEAD^{tree})
printf '%s\n' "$SHA" >"$OUT/tested-sha.txt"
printf '%s\n' "$TREE" >"$OUT/production-tree.txt"
printf '%s\n' "$PACKAGE" >"$OUT/package.txt"
sha256sum "$APP_APK" "$TEST_APK" app/src/main/jniLibs/x86_64/*.so \
  app/src/androidTest/java/com/rdp/sync/ui/rdp/SessionFullscreenApi29ProbeTest.kt >"$OUT/input-sha256.txt"
"${ADB[@]}" shell getprop ro.build.fingerprint >"$OUT/emulator-fingerprint.txt"
"${ADB[@]}" shell wm size >"$OUT/wm-size.txt"
"${ADB[@]}" shell wm density >"$OUT/wm-density.txt"
"${ADB[@]}" logcat -c
STARTED=false
COLLECTED=false
REMOTE_PRIVATE="/sdcard/Android/data/$PACKAGE/files/api29-fullscreen-probe"
# UTP may uninstall the app before returning. The test exports this owned folder
# in finally, so collect only its fresh durable copy, never a stale private path.
REMOTE="/sdcard/Download/rdpsync-api29-fullscreen"
collect() {
  [[ "$STARTED" == true ]] || return 0
  [[ "$COLLECTED" != true ]] || return 0
  "${ADB[@]}" shell stat -c %Y "$REMOTE/measurements.json" >"$OUT/report-device-mtime.txt"
  mkdir -p "$OUT/api29-fullscreen-probe"
  "${ADB[@]}" pull "$REMOTE/." "$OUT/api29-fullscreen-probe/" >"$OUT/pull.log" 2>&1
  COLLECTED=true
}
cleanup() {
  "${ADB[@]}" logcat -b all -d >"$OUT/android-logcat.txt" 2>&1 || true
  collect || true
  "${ADB[@]}" exec-out screencap -p >"$OUT/final-emulator.png" 2>/dev/null || true
  "${ADB[@]}" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
  "${ADB[@]}" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1 || true
  # android-emulator-runner owns and tears down this emulator, never another device.
}
trap cleanup EXIT
"${ADB[@]}" shell rm -rf "$REMOTE_PRIVATE" "$REMOTE"
"${ADB[@]}" shell test ! -e "$REMOTE"
"${ADB[@]}" shell test ! -e "$REMOTE_PRIVATE"
"${ADB[@]}" shell date +%s >"$OUT/instrumentation-device-start-seconds.txt"
"${ADB[@]}" shell cat /proc/uptime >"$OUT/instrumentation-device-uptime.txt"
touch "$OUT/instrumentation-start.marker"
STARTED=true
./gradlew --no-daemon -PnativeAbis=x86_64 -Pandroid.injected.device.serial="$ANDROID_SERIAL" \
  -Pandroid.testInstrumentationRunnerArguments.api29FullscreenProbe=true \
  -Pandroid.testInstrumentationRunnerArguments.productionCommit="$SHA" \
  -Pandroid.testInstrumentationRunnerArguments.productionTree="$TREE" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rdp.sync.ui.rdp.SessionFullscreenApi29ProbeTest \
  :app:connectedDebugAndroidTest
collect
python3 scripts/fixtures/verify_api29_layout_report.py "$OUT" app/build/outputs/androidTest-results/connected/debug
