#!/usr/bin/env bash
# Disposable hosted runner only. The native compiler is no longer needed after
# both APKs are assembled and the workflow has saved its native build cache.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || {
  echo 'Hosted disposable runner required' >&2; exit 2;
}
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
SDK="${ANDROID_HOME:?ANDROID_HOME required}"
[[ "$SDK" == /* && "$SDK" != / ]]
NDK="$SDK/ndk/28.0.13004108"
[[ -d "$NDK" && ! -L "$NDK" ]]
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.0\.13004108[[:space:]]*$' "$NDK/source.properties"
OUT=build/api29-prebuilt-evidence
[[ ! -e "$OUT" ]] || { echo 'Fresh prebuilt evidence directory required' >&2; exit 2; }
mkdir -p "$OUT"
exec > >(tee "$OUT/prepare.log") 2>&1
APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
APP_METADATA=app/build/outputs/apk/debug/output-metadata.json
TEST_METADATA=app/build/outputs/apk/androidTest/debug/output-metadata.json
PROVENANCE=build/freerdp-android/rdpsync-native-provenance-x86_64.json
for file in "$APP" "$TEST" "$APP_METADATA" "$TEST_METADATA" "$PROVENANCE" app/src/main/jniLibs/x86_64/*.so; do
  [[ -s "$file" ]] || { echo "Missing prebuilt input: $file" >&2; exit 2; }
done
sha256sum "$APP" "$TEST" "$APP_METADATA" "$TEST_METADATA" "$PROVENANCE" app/src/main/jniLibs/x86_64/*.so >"$OUT/input-sha256.txt"
git rev-parse HEAD >"$OUT/tested-sha.txt"
df -B1 "$HOME" "$SDK" >"$OUT/disk-before.txt"
# Materialize the same emulator/image packages used by the next action before
# measuring userdata headroom; no smaller userdata or weaker test profile.
sdkmanager 'emulator' 'system-images;android-29;default;x86_64'
df -B1 "$HOME" "$SDK" >"$OUT/disk-after-image.txt"
# Reclaim only enumerated NDK compiler packages and non-API29 factory images.
# Never remove AVDs, userdata, emulator/tools, Gradle caches or build evidence.
python3 scripts/fixtures/reclaim_api29_sdk.py "$OUT"
[[ ! -e "$NDK" ]] || { echo 'Completed compiler package was not removed' >&2; exit 2; }
sha256sum -c "$OUT/input-sha256.txt"
df -B1 "$HOME" "$SDK" >"$OUT/disk-after.txt"
python3 - "$HOME" "$OUT" <<'PY'
import json, os, pathlib, sys
path, out = sys.argv[1], pathlib.Path(sys.argv[2])
stat = os.statvfs(path)
available = stat.f_bavail * stat.f_frsize
# The observed full Pixel 2/API29 userdata needs 7372.80 MiB; keep >=8 GiB
# after emulator/system-image materialization, without reducing its size.
required = 8 * 1024**3
receipt = dict(path=path, available_bytes=available, minimum_bytes=required,
               enough_space=available >= required, apk_inputs_preserved=True)
(out / 'disk-space.json').write_text(json.dumps(receipt, indent=2) + '\n')
assert receipt['enough_space'], 'Insufficient space for unchanged API29 userdata; no app tests started'
PY
