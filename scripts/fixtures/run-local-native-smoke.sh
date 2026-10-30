#!/usr/bin/env bash
# Offline Android context/channel smoke. No RDP server, OS users, or host policies.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_APK="$(realpath "${1:?Usage: $0 app-x86_64.apk test.apk [output-directory]}")"
TEST_APK="$(realpath "${2:?test APK required}")"
OUT="$(realpath -m "${3:-$ROOT/build/local-native-smoke-$(date -u +%Y%m%dT%H%M%SZ)}")"
: "${ANDROID_HOME:?Set ANDROID_HOME to an existing SDK with emulator and android-29/default/x86_64}"
mkdir -p "$OUT" "$OUT/home" "$OUT/avd" "$OUT/tmp"
# Each invocation owns isolated AVD/user paths. Never remove or mutate a user's AVD.
export HOME="$OUT/home" ANDROID_USER_HOME="$OUT/home/.android" ANDROID_AVD_HOME="$OUT/avd" TMPDIR="$OUT/tmp"
SDKMANAGER_ROOT="$ANDROID_HOME/cmdline-tools/latest/bin"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
AVD="rdpsync-native-smoke-$$"
ADB_PORT=5039
EMU_PORT=5558
SERIAL="emulator-$EMU_PORT"
unset ADB_SERVER_SOCKET ANDROID_SERIAL
export ANDROID_ADB_SERVER_PORT="$ADB_PORT"
ADB_CMD=("$ADB" -P "$ADB_PORT")
# Refuse occupied ports rather than attaching to another test/developer session.
python3 - "$ADB_PORT" "$EMU_PORT" "$((EMU_PORT + 1))" <<'PORTCHECK'
import socket, sys
for port in map(int, sys.argv[1:]):
    with socket.socket() as s:
        s.bind(('127.0.0.1', port))
PORTCHECK
CLASSES="${RDP_SMOKE_CLASSES:-com.rdp.sync.NativeRuntimeInstrumentedTest}"
AAPT=$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 -name aapt -type f | sort -V | tail -1)
[[ -n "$AAPT" ]]
APP_PACKAGE=$("$AAPT" dump badging "$APP_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
TEST_PACKAGE=$("$AAPT" dump badging "$TEST_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ $APP_PACKAGE =~ ^com\.rdp\.sync(\.preview[0-9]*)?$ && $TEST_PACKAGE == "$APP_PACKAGE.test" ]]
"$AAPT" dump xmltree "$TEST_APK" AndroidManifest.xml >"$OUT/test-manifest.txt"
grep -q "android:targetPackage.*\"$APP_PACKAGE\"" "$OUT/test-manifest.txt"
printf '%s\n%s\n' "$APP_PACKAGE" "$TEST_PACKAGE" >"$OUT/package-identities.txt"
git -C "$ROOT" rev-parse HEAD >"$OUT/tested-sha.txt"
git -C "$ROOT" status --porcelain >"$OUT/source-status.txt"
sha256sum "$APP_APK" "$TEST_APK" >"$OUT/apk-sha256.txt"
printf 'no\n' | "$SDKMANAGER_ROOT/avdmanager" create avd --force -n "$AVD" \
    -k 'system-images;android-29;default;x86_64' --device pixel_2 >"$OUT/create-avd.log" 2>&1
EMU_PID=""
ADB_PID=""
OWNED_EMULATOR=false
cleanup() {
    if [[ "$OWNED_EMULATOR" == true ]]; then
        timeout 5 "${ADB_CMD[@]}" -s "$SERIAL" logcat -d >"$OUT/logcat.txt" 2>&1 || true
        timeout 5 "${ADB_CMD[@]}" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    fi
    if [[ -n "$EMU_PID" ]]; then
        kill "$EMU_PID" 2>/dev/null || true
        for _ in $(seq 1 10); do kill -0 "$EMU_PID" 2>/dev/null || break; sleep 1; done
        kill -9 "$EMU_PID" 2>/dev/null || true
        wait "$EMU_PID" 2>/dev/null || true
    fi
    if [[ -n "$ADB_PID" ]]; then
        kill "$ADB_PID" 2>/dev/null || true
        wait "$ADB_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT
# Foreground adb gives us an owned PID; no global adb kill-server is needed.
"${ADB_CMD[@]}" nodaemon server >"$OUT/adb-start.log" 2>&1 &
ADB_PID=$!
sleep 1
kill -0 "$ADB_PID"
"$EMU" -avd "$AVD" -accel off -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -memory 1536 -cores 2 -port "$EMU_PORT" >"$OUT/emulator.log" 2>&1 &
EMU_PID=$!
DEADLINE=$((SECONDS + 300))
READY=false
while (( SECONDS < DEADLINE )); do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then tail -30 "$OUT/emulator.log"; exit 2; fi
    if timeout 3 "${ADB_CMD[@]}" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q '^1'; then
        READY=true; break
    fi
    sleep 5
done
if [[ "$READY" != true ]]; then echo 'Software emulator did not boot within 300 seconds.' >&2; exit 3; fi
# Refuse to install into or terminate any emulator other than the one just created.
timeout 5 "${ADB_CMD[@]}" -s "$SERIAL" emu avd name >"$OUT/avd-name.txt"
tr -d '\r' <"$OUT/avd-name.txt" | grep -Fxq "$AVD"
OWNED_EMULATOR=true
"${ADB_CMD[@]}" -s "$SERIAL" shell getprop ro.build.fingerprint >"$OUT/fingerprint.txt"
"${ADB_CMD[@]}" -s "$SERIAL" logcat -c
timeout 90 "${ADB_CMD[@]}" -s "$SERIAL" install -t "$APP_APK" >"$OUT/install-app.log" 2>&1
timeout 60 "${ADB_CMD[@]}" -s "$SERIAL" install -t "$TEST_APK" >"$OUT/install-test.log" 2>&1
# Optional fixed-purpose Android PEM export probe, only for the synthetic TLS harness.
if [[ "${RDP_SMOKE_TLS_FIXTURE:-false}" == true && -n "${RDP_TLS_PEM_PROBE:-}" ]]; then
    : "${RDP_TLS_FIXTURE_CERT:?public fixture certificate required for PEM probe}"
    grep -q -- '-----BEGIN CERTIFICATE-----' "$RDP_TLS_FIXTURE_CERT"
    if grep -q 'PRIVATE KEY' "$RDP_TLS_FIXTURE_CERT"; then echo 'Refusing private-key transfer' >&2; exit 4; fi
    "${ADB_CMD[@]}" -s "$SERIAL" shell mkdir -p /data/local/tmp/rdpsync-pem-probe
    "${ADB_CMD[@]}" -s "$SERIAL" push "$RDP_TLS_PEM_PROBE" /data/local/tmp/rdpsync-pem-probe/probe >"$OUT/probe-push.log" 2>&1
    "${ADB_CMD[@]}" -s "$SERIAL" push "$RDP_TLS_FIXTURE_CERT" /data/local/tmp/rdpsync-pem-probe/cert.pem >>"$OUT/probe-push.log" 2>&1
    "${ADB_CMD[@]}" -s "$SERIAL" push "$ROOT"/app/src/main/jniLibs/x86_64/*.so /data/local/tmp/rdpsync-pem-probe/ >>"$OUT/probe-push.log" 2>&1
    "${ADB_CMD[@]}" -s "$SERIAL" shell chmod 755 /data/local/tmp/rdpsync-pem-probe/probe
    if timeout 60 "${ADB_CMD[@]}" -s "$SERIAL" shell 'LD_LIBRARY_PATH=/data/local/tmp/rdpsync-pem-probe /data/local/tmp/rdpsync-pem-probe/probe /data/local/tmp/rdpsync-pem-probe/cert.pem' >"$OUT/pem-probe.log" 2>&1; then
        echo 0 >"$OUT/pem-probe-exit-code.txt"
    else
        echo $? >"$OUT/pem-probe-exit-code.txt"
    fi
fi
TEST_ARGS=()
if [[ "${RDP_SMOKE_TLS_FIXTURE:-false}" == true ]]; then TEST_ARGS+=(-e rdpTlsFixture true); fi
timeout 180 "${ADB_CMD[@]}" -s "$SERIAL" shell am instrument -w -r -e class "$CLASSES" "${TEST_ARGS[@]}" \
    "$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" >"$OUT/instrumentation.log" 2>&1
cat "$OUT/instrumentation.log"
# adb/am can return exit0 when JUnit failed. Require the actual runner result.
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$OUT/instrumentation.log"

# Assumptions/ignored tests must not masquerade as explicit smoke coverage.
! grep -q '^INSTRUMENTATION_STATUS_CODE: -' "$OUT/instrumentation.log"
