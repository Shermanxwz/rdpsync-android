#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/native-env.sh"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
cd "$PROJECT_DIR"
bash scripts/build-native.sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
python3 scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
python3 scripts/verify-apk.py app/build/outputs/apk/release/app-release.apk
mkdir -p releases
cp app/build/outputs/apk/release/app-release.apk releases/RDPsync-v1.3.0-arm64-v8a.apk
sha256sum releases/RDPsync-v1.3.0-arm64-v8a.apk | tee releases/RDPsync-v1.3.0-arm64-v8a.apk.sha256
