#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/native-env.sh"
NDK_DIR="$ANDROID_NDK"
# Create build directory
BUILD_DIR="$PROJECT_DIR/build/freerdp-android"
mkdir -p "$BUILD_DIR"

# Download FreeRDP 3.32.2
FREERDP_DIR="$BUILD_DIR/freerdp-src"
if [ ! -d "$FREERDP_DIR" ]; then
    echo "Downloading FreeRDP 3.32.2..."
    cd "$BUILD_DIR"
    git clone --depth 1 --branch 3.32.2 https://github.com/FreeRDP/FreeRDP.git freerdp-src 2>&1
fi

# Verify the exact tested upstream commit, rather than silently accepting a moved tag.
[[ "$(git -C "$FREERDP_DIR" rev-parse HEAD)" == "97eff6ddfbbfaa8e42a126d362686bd001ff8bdc" ]] || { echo "Unexpected FreeRDP source revision" >&2; exit 1; }
# A pinned HEAD alone does not establish a reviewed working tree. Allow only
# the exact idempotent scoped flow-control addition; reject unrelated dirty cache sources.
if [[ -n "$(git -C "$FREERDP_DIR" diff --name-only HEAD -- | grep -Ev '^libfreerdp/core/(transport|tcp)\.c$')" ]] ||
   [[ -n "$(git -C "$FREERDP_DIR" ls-files --others --exclude-standard)" ]]; then
    echo "Unreviewed FreeRDP working-tree changes" >&2; exit 1
fi
python3 "$PROJECT_DIR/scripts/patch-freerdp-flow-control.py" "$FREERDP_DIR"
python3 - "$PROJECT_DIR" "$FREERDP_DIR" "$BUILD_DIR" "$ABI" <<'PYPROVENANCE'
import hashlib, importlib.util, json, sys
from pathlib import Path
project, source, build = map(Path, sys.argv[1:4])
script = project / "scripts/patch-freerdp-flow-control.py"
spec = importlib.util.spec_from_file_location("flow_patch", script)
patch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patch)
sha = lambda value: hashlib.sha256(value).hexdigest()
record = {
    "abi": sys.argv[4],
    "upstream_commit": "97eff6ddfbbfaa8e42a126d362686bd001ff8bdc",
    "flow_patch_script_sha256": sha(script.read_bytes()),
    "flow_patch_suffix_sha256": sha(patch.SUFFIX),
    "tcp_flow_suffix_sha256": sha(patch.TCP_SUFFIX),
    "patched_tcp_sha256": sha((source / "libfreerdp/core/tcp.c").read_bytes()),
    "patched_transport_sha256": sha((source / "libfreerdp/core/transport.c").read_bytes()),
    "unpatched_source_sha256": patch.PINNED,
    "scope": "direct TLS/default transport only; read-only buffered ciphertext count plus owner-only bounded nonblocking ciphertext drain; stock DRIVE disabled",
}
(build / ("rdpsync-native-provenance-" + sys.argv[4] + ".json")).write_text(json.dumps(record, indent=2, sort_keys=True) + "\n")
PYPROVENANCE

# RDPDR requires RDPSND sys:fake even with audio playback disabled. Build that
# protocol dependency without Android/host audio backends or microphone capture.
# Build FreeRDP
BUILD_OUT="$BUILD_DIR/build-$BUILD_ARCH"
mkdir -p "$BUILD_OUT"
cd "$BUILD_OUT"

echo "Configuring FreeRDP 3.32.2 for $ABI..."
cmake "$FREERDP_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DWITH_SERVER=OFF \
    -DWITH_CLIENT_SDL=OFF \
    -DWITH_CLIENT_X11=OFF \
    -DWITH_CLIENT_WAYLAND=OFF \
    -DWITH_CUPS=OFF \
    -DWITH_PULSE=OFF \
    -DWITH_ALSA=OFF \
    -DWITH_OPENSLES=OFF \
    -DWITH_AAUDIO=OFF \
    -DWITH_OSS=OFF \
    -DWITH_SNDIO=OFF \
    -DWITH_FFMPEG=OFF \
    -DWITH_OPENH264=OFF \
    -DWITH_WEBVIEW=OFF \
    -DWITH_GSM=OFF \
    -DWITH_KRB5=OFF \
    -DWITH_PCSC=OFF \
    -DWITH_SAMPLE=OFF \
    -DWITH_MANPAGES=OFF \
    -DWITH_SWSCALE=OFF \
    -DWITH_CCACHE=OFF \
    -DWITH_DIRECTFB=OFF \
    -DWITH_OPENSSL=ON \
    -DWITH_ZLIB=ON \
    -DWITH_IPC=OFF \
    -DWITH_RDTK=OFF \
    -DWITH_DSM=OFF \
    -DWITH_PROXY=OFF \
    -DWITH_CLIENT_INTERFACE=OFF \
    -DWITH_CHANNELS=ON \
    -DWITH_CLIENT_CHANNELS=ON \
    -DCHANNEL_RDPEI=ON \
    -DCHANNEL_RDPEI_CLIENT=ON \
    -DCHANNEL_RDPEI_SERVER=OFF \
    -DCHANNEL_DRDYNVC=ON \
    -DCHANNEL_DRDYNVC_CLIENT=ON \
    -DCHANNEL_DRDYNVC_SERVER=OFF \
    -DCHANNEL_AINPUT=OFF \
    -DCHANNEL_AUDIN=OFF \
    -DCHANNEL_CLIPRDR=ON \
    -DCHANNEL_CLIPRDR_CLIENT=ON \
    -DCHANNEL_CLIPRDR_SERVER=OFF \
    -DCHANNEL_DISP=ON \
    -DCHANNEL_DISP_CLIENT=ON \
    -DCHANNEL_DISP_SERVER=OFF \
    -DCHANNEL_DRIVE=OFF \
    -DCHANNEL_ECHO=OFF \
    -DCHANNEL_ENCOMSP=OFF \
    -DCHANNEL_GEOMETRY=OFF \
    -DCHANNEL_LOCATION=OFF \
    -DCHANNEL_PARALLEL=OFF \
    -DCHANNEL_PRINTER=OFF \
    -DCHANNEL_RAIL=OFF \
    -DCHANNEL_RDPDR=ON \
    -DCHANNEL_RDPDR_CLIENT=ON \
    -DCHANNEL_RDPDR_SERVER=OFF \
    -DCHANNEL_RDPECAM=OFF \
    -DCHANNEL_RDPEMSC=OFF \
    -DCHANNEL_RDPGFX=ON \
    -DCHANNEL_RDPGFX_CLIENT=ON \
    -DCHANNEL_RDPGFX_SERVER=OFF \
    -DCHANNEL_RDPSND=ON \
    -DCHANNEL_RDPSND_CLIENT=ON \
    -DCHANNEL_RDPSND_SERVER=OFF \
    -DCHANNEL_REMDESK=OFF \
    -DCHANNEL_SERIAL=OFF \
    -DCHANNEL_SMARTCARD=OFF \
    -DCHANNEL_TELEMETRY=OFF \
    -DCHANNEL_VIDEO=OFF \
    -DWITH_DSP_FFMPEG=OFF \
    -DWITH_INTERNAL_MD4=ON \
    -DWITH_INTERNAL_RC4=ON \
    -DOPENSSL_ROOT_DIR="$OPENSSL_OUT" \
    -DOPENSSL_INCLUDE_DIR="$OPENSSL_OUT/include" \
    -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL_OUT/lib/libcrypto.a" \
    -DOPENSSL_SSL_LIBRARY="$OPENSSL_OUT/lib/libssl.a" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \


echo "Building FreeRDP..."
cmake --build . -j"$JOBS"

# Copy output .so files to jniLibs
JNILIBS="$PROJECT_DIR/app/src/main/jniLibs/$ABI"
mkdir -p "$JNILIBS"

echo "Copying FreeRDP .so files..."
find "$BUILD_OUT" -name "*.so" -type f | while read f; do
    basename "$f"
    cp "$f" "$JNILIBS/"
done

# Also copy the JNI bridge if built separately
if [ -f "$BUILD_OUT/libfreerdp-client/libfreerdp-client3.so" ]; then
    cp "$BUILD_OUT/libfreerdp-client/libfreerdp-client3.so" "$JNILIBS/"
fi

echo ""
echo "=== FreeRDP build complete ==="
echo "Libs in $JNILIBS:"
ls -lh "$JNILIBS/"*.so 2>/dev/null
echo ""
echo "To check FreeRDP version:"
strings "$JNILIBS/libfreerdp3.so" 2>/dev/null | grep -i "FreeRDP version" | head -3 || echo "version check via strings not available"
