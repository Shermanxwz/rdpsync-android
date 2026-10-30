#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
LAYOUT=${1:?phone or expanded required}
case "$LAYOUT" in
  phone) SIZE=960x1920; DENSITY=480; METHODS=(englishLight englishDark chineseLight chineseDark narrowLargeText) ;;
  expanded) SIZE=1280x1920; DENSITY=320; METHODS=(expandedWidth) ;;
  *) exit 2 ;;
esac
OUT="build/product-ui-evidence/$LAYOUT"
mkdir -p "$OUT"
git rev-parse HEAD >"$OUT/source-commit.txt"
sha256sum app/src/androidTest/java/com/rdp/sync/ProductUiInstrumentedTest.kt scripts/fixtures/product-ui-capture-plan.json >"$OUT/ui-source.sha256"
# Confirm the single owned emulator BEFORE any display/settings mutation.
export ANDROID_SERIAL=emulator-5554
adb_owned() { timeout 10 adb -s "$ANDROID_SERIAL" "$@"; }
timeout 10 adb devices >"$OUT/adb-devices-before.txt"
python3 - "$OUT/adb-devices-before.txt" <<'PYDEVICES'
import pathlib,sys
rows=[line.split() for line in pathlib.Path(sys.argv[1]).read_text().splitlines()[1:] if line.strip()]
assert rows == [['emulator-5554','device']], rows
PYDEVICES
[[ "$(adb_owned shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
[[ "$(adb_owned shell getprop ro.build.version.sdk | tr -d '\r')" == 35 ]]
adb_owned shell wm size >"$OUT/original-wm-size.txt"
adb_owned shell wm density >"$OUT/original-wm-density.txt"
original_rotation=$(adb_owned shell settings get system user_rotation | tr -d '\r')
original_accelerometer=$(adb_owned shell settings get system accelerometer_rotation | tr -d '\r')
[[ "$original_rotation" =~ ^(null|[0-3])$ && "$original_accelerometer" =~ ^(null|[01])$ ]]
printf '%s\n' "$original_rotation" >"$OUT/original-rotation.txt"
printf '%s\n' "$original_accelerometer" >"$OUT/original-accelerometer.txt"
original_size=$(sed -n 's/^Override size: //p' "$OUT/original-wm-size.txt" | tr -d '\r')
original_density=$(sed -n 's/^Override density: //p' "$OUT/original-wm-density.txt" | tr -d '\r')
[[ -z "$original_size" || "$original_size" =~ ^[0-9]+x[0-9]+$ ]]
[[ -z "$original_density" || "$original_density" =~ ^[0-9]+$ ]]
collected=false
restore_configuration() {
  adb_owned shell wm size "${original_size:-reset}" &&
  adb_owned shell wm density "${original_density:-reset}" &&
  { if [[ "$original_rotation" == null ]]; then adb_owned shell settings delete system user_rotation;
    else adb_owned shell settings put system user_rotation "$original_rotation"; fi; } &&
  { if [[ "$original_accelerometer" == null ]]; then adb_owned shell settings delete system accelerometer_rotation;
    else adb_owned shell settings put system accelerometer_rotation "$original_accelerometer"; fi; } &&
  [[ "$(adb_owned shell settings get system user_rotation | tr -d '\r')" == "$original_rotation" ]] &&
  [[ "$(adb_owned shell settings get system accelerometer_rotation | tr -d '\r')" == "$original_accelerometer" ]] &&
  adb_owned shell wm size >"$OUT/restored-wm-size.txt" &&
  adb_owned shell wm density >"$OUT/restored-wm-density.txt" &&
  cmp "$OUT/original-wm-size.txt" "$OUT/restored-wm-size.txt" &&
  cmp "$OUT/original-wm-density.txt" "$OUT/restored-wm-density.txt"
}
cleanup() {
  local result=$?
  trap - EXIT
  set +e
  if [[ "$collected" != true && -f "$OUT/evidence-start-device-seconds.txt" ]]; then
    python3 scripts/fixtures/collect_product_ui.py "$LAYOUT" "$OUT" --best-effort
  fi
  adb_owned logcat -d >"$OUT/logcat.txt"
  restore_configuration >"$OUT/configuration-restore.log" 2>&1
  local restored=$?
  printf 'restore_exit_code=%s\n' "$restored" >>"$OUT/configuration-restore.log"
  if [[ "$result" == 0 && "$restored" != 0 ]]; then result=$restored; fi
  exit "$result"
}
trap cleanup EXIT
# Actual emulator window size, not a constrained composable pretending a Dialog is narrow.
adb_owned shell wm size "$SIZE"
adb_owned shell wm density "$DENSITY"
adb_owned shell settings put system accelerometer_rotation 0
adb_owned shell settings put system user_rotation 0
python3 scripts/fixtures/emulator_boot_health.py "$OUT/boot-health"
adb_owned shell wm size >"$OUT/wm-size.txt"
adb_owned shell wm density >"$OUT/wm-density.txt"
adb_owned shell rm -rf /sdcard/Download/rdpsync-product-ui
adb_owned shell test ! -e /sdcard/Download/rdpsync-product-ui
adb_owned shell date +%s >"$OUT/evidence-start-device-seconds.txt"
touch "$OUT/instrumentation-start.marker"
FILTER=""
for method in "${METHODS[@]}"; do FILTER+="${FILTER:+,}com.rdp.sync.ProductUiInstrumentedTest#$method"; done
./gradlew --no-daemon -PnativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class="$FILTER" \
  -Pandroid.testInstrumentationRunnerArguments.sourceCommit="$(cat "$OUT/source-commit.txt")" \
  :app:connectedDebugAndroidTest
collected=true
python3 scripts/fixtures/collect_product_ui.py "$LAYOUT" "$OUT"
