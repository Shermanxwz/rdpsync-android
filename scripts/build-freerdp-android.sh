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
if [[ -n "$(git -C "$FREERDP_DIR" diff --name-only HEAD -- | grep -Ev '^(libfreerdp/core/(transport|tcp|capabilities)|libfreerdp/codec/h264_mediacodec)\.c$')" ]] ||
   [[ -n "$(git -C "$FREERDP_DIR" ls-files --others --exclude-standard)" ]]; then
    echo "Unreviewed FreeRDP working-tree changes" >&2; exit 1
fi
python3 "$PROJECT_DIR/scripts/patch-freerdp-flow-control.py" "$FREERDP_DIR"
python3 - "$FREERDP_DIR/libfreerdp/core/capabilities.c" <<'PYCAPABILITIES'
import sys
from pathlib import Path

p = Path(sys.argv[1])
src = p.read_text("utf-8")
if "rdpsync_freerdp_peer_os_major" not in src:
    anchor = """static BOOL rdp_apply_general_capability_set(rdpSettings* settings, const rdpSettings* src)
{
	WINPR_ASSERT(settings);
	WINPR_ASSERT(src);
"""
    replacement = """static struct { const rdpSettings* key; UINT32 os_major; } g_rdpsync_peer_os[16];
static unsigned g_rdpsync_peer_os_idx;

static void rdpsync_record_peer_os_major(const rdpSettings* settings, UINT32 os_major)
{
	if (!settings)
		return;
	for (unsigned i = 0; i < 16; ++i)
	{
		if (g_rdpsync_peer_os[i].key == settings)
		{
			g_rdpsync_peer_os[i].os_major = os_major;
			return;
		}
	}
	const unsigned slot = (g_rdpsync_peer_os_idx++) & 15U;
	g_rdpsync_peer_os[slot].key = settings;
	g_rdpsync_peer_os[slot].os_major = os_major;
}

FREERDP_API UINT32 rdpsync_freerdp_peer_os_major(const rdpSettings* settings)
{
	if (!settings)
		return 0;
	for (unsigned i = 0; i < 16; ++i)
	{
		if (g_rdpsync_peer_os[i].key == settings)
			return g_rdpsync_peer_os[i].os_major;
	}
	return 0;
}

static BOOL rdp_apply_general_capability_set(rdpSettings* settings, const rdpSettings* src)
{
	WINPR_ASSERT(settings);
	WINPR_ASSERT(src);
	if (!settings->ServerMode)
		rdpsync_record_peer_os_major(settings, src->OsMajorType);
"""
    if src.count(anchor) != 1:
        raise SystemExit(f"Expected 1 capabilities.c anchor, got {src.count(anchor)}")
    p.write_text(src.replace(anchor, replacement, 1), "utf-8")
PYCAPABILITIES
python3 - "$FREERDP_DIR/libfreerdp/codec/h264_mediacodec.c" <<'PYMEDIACODEC'
import sys
from pathlib import Path

p = Path(sys.argv[1])
src = p.read_text("utf-8")
if "__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__" not in src:
    repls = [
        (
            "#include <freerdp/config.h>\n",
            "/* Backport upstream FreeRDP PR #13706 for Android API 26 NDK strict availability */\n"
            "#define __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__\n\n"
            "#include <freerdp/config.h>\n",
        ),
        (
            "\tAMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PRIORITY, 0);\n",
            "\tif (__builtin_available(android 28, *))\n"
            "\t\tAMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PRIORITY, 0);\n",
        ),
        (
            "\tinputFormat = AMediaCodec_getInputFormat(sys->decoder);\n"
            "\tif (inputFormat == nullptr)\n"
            "\t{\n"
            "\t\tWLog_Print(h264->log, WLOG_ERROR, \"AMediaCodec_getInputFormat failed\");\n"
            "\t\treturn -1;\n"
            "\t}\n",
            "\tif (__builtin_available(android 28, *))\n"
            "\t{\n"
            "\t\tinputFormat = AMediaCodec_getInputFormat(sys->decoder);\n"
            "\t\tif (inputFormat == nullptr)\n"
            "\t\t{\n"
            "\t\t\tWLog_Print(h264->log, WLOG_ERROR, \"AMediaCodec_getInputFormat failed\");\n"
            "\t\t\treturn -1;\n"
            "\t\t}\n"
            "\t}\n"
            "\telse\n"
            "\t\tinputFormat = sys->inputFormat;\n",
        ),
        (
            "\tif (!AMediaFormat_getInt32(sys->outputFormat, AMEDIAFORMAT_KEY_SLICE_HEIGHT,\n"
            "\t                           &outputSliceHeight) ||\n"
            "\t    outputSliceHeight < outputHeight)\n"
            "\t\toutputSliceHeight = outputHeight;\n",
            "\toutputSliceHeight = outputHeight;\n"
            "\tif (__builtin_available(android 28, *))\n"
            "\t{\n"
            "\t\tint32_t reportedSliceHeight = 0;\n"
            "\t\tif (AMediaFormat_getInt32(sys->outputFormat, AMEDIAFORMAT_KEY_SLICE_HEIGHT,\n"
            "\t\t                          &reportedSliceHeight) &&\n"
            "\t\t    reportedSliceHeight >= outputHeight)\n"
            "\t\t\toutputSliceHeight = reportedSliceHeight;\n"
            "\t}\n",
        ),
        (
            "\t\tAMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BITRATE_MODE, MEDIACODEC_BITRATE_MODE_VBR);\n",
            "\t\tif (__builtin_available(android 28, *))\n"
            "\t\t\tAMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BITRATE_MODE,\n"
            "\t\t\t                      MEDIACODEC_BITRATE_MODE_VBR);\n",
        ),
        (
            "\tAMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BITRATE_MODE, MEDIACODEC_BITRATE_MODE_VBR);\n",
            "\tif (__builtin_available(android 28, *))\n"
            "\t\tAMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BITRATE_MODE, MEDIACODEC_BITRATE_MODE_VBR);\n",
        ),
        (
            "\tAMediaFormat* inputFormat = AMediaCodec_getInputFormat(sys->encoder);\n"
            "\tif (inputFormat)\n"
            "\t{\n"
            "\t\tint32_t stride = 0;\n"
            "\t\tint32_t sliceHeight = 0;\n"
            "\t\tif (AMediaFormat_getInt32(inputFormat, AMEDIAFORMAT_KEY_STRIDE, &stride) &&\n"
            "\t\t    (stride >= sys->encoderWidth))\n"
            "\t\t\tsys->encoderStride = stride;\n"
            "\t\tif (AMediaFormat_getInt32(inputFormat, AMEDIAFORMAT_KEY_SLICE_HEIGHT, &sliceHeight) &&\n"
            "\t\t    (sliceHeight >= sys->encoderHeight))\n"
            "\t\t\tsys->encoderSliceHeight = sliceHeight;\n"
            "\t\tmediacodec_warn(h264->log, AMediaFormat_delete(inputFormat), \"AMediaFormat_delete\");\n"
            "\t}\n",
            "\tif (__builtin_available(android 28, *))\n"
            "\t{\n"
            "\t\tAMediaFormat* inputFormat = AMediaCodec_getInputFormat(sys->encoder);\n"
            "\t\tif (inputFormat)\n"
            "\t\t{\n"
            "\t\t\tint32_t stride = 0;\n"
            "\t\t\tint32_t sliceHeight = 0;\n"
            "\t\t\tif (AMediaFormat_getInt32(inputFormat, AMEDIAFORMAT_KEY_STRIDE, &stride) &&\n"
            "\t\t\t    (stride >= sys->encoderWidth))\n"
            "\t\t\t\tsys->encoderStride = stride;\n"
            "\t\t\tif (AMediaFormat_getInt32(inputFormat, AMEDIAFORMAT_KEY_SLICE_HEIGHT, &sliceHeight) &&\n"
            "\t\t\t    (sliceHeight >= sys->encoderHeight))\n"
            "\t\t\t\tsys->encoderSliceHeight = sliceHeight;\n"
            "\t\t\tmediacodec_warn(h264->log, AMediaFormat_delete(inputFormat), \"AMediaFormat_delete\");\n"
            "\t\t}\n"
            "\t}\n",
        ),
        (
            "\tchar* codec_name = nullptr;\n"
            "\tstatus = AMediaCodec_getName(sys->decoder, &codec_name);\n"
            "\tif (status != AMEDIA_OK)\n"
            "\t{\n"
            "\t\tWLog_Print(h264->log, WLOG_ERROR, \"AMediaCodec_getName failed: %d\", status);\n"
            "\t\tgoto EXCEPTION;\n"
            "\t}\n\n"
            "\tWLog_Print(h264->log, WLOG_DEBUG, \"MediaCodec using %s codec [%s]\", CODEC_NAME, codec_name);\n"
            "\tAMediaCodec_releaseName(sys->decoder, codec_name);\n",
            "\tif (__builtin_available(android 28, *))\n"
            "\t{\n"
            "\t\tchar* codec_name = nullptr;\n"
            "\t\tstatus = AMediaCodec_getName(sys->decoder, &codec_name);\n"
            "\t\tif (status != AMEDIA_OK)\n"
            "\t\t{\n"
            "\t\t\tWLog_Print(h264->log, WLOG_ERROR, \"AMediaCodec_getName failed: %d\", status);\n"
            "\t\t\tgoto EXCEPTION;\n"
            "\t\t}\n\n"
            "\t\tWLog_Print(h264->log, WLOG_DEBUG, \"MediaCodec using %s codec [%s]\", CODEC_NAME, codec_name);\n"
            "\t\tAMediaCodec_releaseName(sys->decoder, codec_name);\n"
            "\t}\n"
            "\telse\n"
            "\t\tWLog_Print(h264->log, WLOG_DEBUG, \"MediaCodec using %s codec\", CODEC_NAME);\n",
        ),
    ]
    for old, new in repls:
        if src.count(old) != 1:
            raise SystemExit(f"Expected 1 match in h264_mediacodec.c, got {src.count(old)} for:\n{old[:80]}")
        src = src.replace(old, new, 1)
    p.write_text(src, "utf-8")
PYMEDIACODEC
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
    -DWITH_MEDIACODEC=ON \
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
