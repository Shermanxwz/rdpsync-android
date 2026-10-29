#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/native-env.sh"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
cd "$PROJECT_DIR"
bash scripts/build-native.sh
# Preview package is side-by-side; generated development signing must never overwrite user data.
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
mkdir -p releases
cp app/build/outputs/apk/debug/app-debug.apk releases/rdpsync-preview.apk
sha256sum releases/rdpsync-preview.apk
