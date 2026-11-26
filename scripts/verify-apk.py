#!/usr/bin/env python3
import sys as _sys; _sys.stdout.reconfigure(line_buffering=True)
"""Fail packaging if the APK lacks JNI closure, 16 KiB segments, launcher activity, or release signature."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

EXPECTED_OFFICIAL_CERT_SHA256 = "687344cefb4a919c65de9c095ed4a9395cf39dd87822f25b73b02f7524ddf918"

apk = Path(sys.argv[1])
is_debug_apk = "debug" in apk.name.lower()
required = {"librdpsync.so", "libfreerdp3.so", "libfreerdp-client3.so", "libwinpr3.so"}
system = {"libc.so", "libm.so", "libdl.so", "libz.so", "liblog.so", "libandroid.so", "libjnigraphics.so", "libOpenSLES.so", "libEGL.so", "libGLESv2.so", "libvulkan.so", "libaaudio.so", "libmediandk.so"}
expected_jni = set(re.findall(r"external fun (native\w+)\(", Path("app/src/main/java/com/rdp/sync/network/RdpConnector.kt").read_text()))
with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="rdpsync-apk-") as temp:
    # Native dependencies do not automatically carry their source license notices
    # into ELF libraries. Preserve the committed upstream texts in the user APK.
    notice_root = Path("app/src/main/assets/third_party")
    notices = sorted(path for path in notice_root.rglob("*") if path.is_file())
    required_notices = {"freerdp-3.32.2-LICENSE.txt", "openssl-3.5.9-LICENSE.txt",
                        "android-cpufeatures-NOTICE.txt", "native-source-NOTICES.txt", "manifest.json"}
    assert required_notices <= {path.relative_to(notice_root).as_posix() for path in notices}, \
        "Required pinned third-party notice assets are missing from the checkout"
    notice_manifest = json.loads((notice_root / "manifest.json").read_text())
    assert notice_manifest["schema"] == 1
    assert set(notice_manifest["assets"]) == required_notices - {"manifest.json"}
    for name, expected in notice_manifest["assets"].items():
        data = (notice_root / name).read_bytes()
        assert len(data) == expected["bytes"] and hashlib.sha256(data).hexdigest() == expected["sha256"], \
            f"Third-party notice provenance mismatch: {name}"
    for path in notices:
        original = path.read_bytes()
        assert original, f"Empty third-party notice asset: {path}"
        entry = "assets/third_party/" + path.relative_to(notice_root).as_posix()
        assert archive.read(entry) == original, f"Third-party notice missing or altered in APK: {entry}"
    print(f"Third-party notice assets: {len(notices)} exact committed files preserved")

    # Verify adaptive launcher icon resources are packaged inside the APK
    names_in_zip = set(archive.namelist())
    assert any(n.endswith("mipmap-anydpi-v26/ic_launcher.xml") for n in names_in_zip), \
        "Missing adaptive launcher icon (mipmap-anydpi-v26/ic_launcher.xml) in APK"
    assert any(n.endswith("mipmap-anydpi-v26/ic_launcher_round.xml") for n in names_in_zip), \
        "Missing round adaptive launcher icon (mipmap-anydpi-v26/ic_launcher_round.xml) in APK"

    libraries = [n for n in archive.namelist() if re.fullmatch(r"lib/[^/]+/[^/]+\.so", n)]
    assert libraries, "APK has no native libraries (Gradle-only APK is not functional)"
    abis = sorted({n.split("/")[1] for n in libraries})
    for abi in abis:
        names = {Path(n).name for n in libraries if n.split("/")[1] == abi}
        assert required <= names, f"Missing JNI runtime for {abi}: {required - names}"
        for name in sorted(names):
            blob = archive.read(f"lib/{abi}/{name}")
            assert blob[:5] == b"\x7fELF\x02" and blob[5] == 1, f"Not ELF64 little endian: {name}"
            machine = struct.unpack_from("<H", blob, 18)[0]
            assert machine == {"arm64-v8a": 183, "x86_64": 62}[abi], f"Wrong machine: {name}"
            phoff = struct.unpack_from("<Q", blob, 32)[0]
            phsize, phnum = struct.unpack_from("<HH", blob, 54)
            for i in range(phnum):
                ph = struct.unpack_from("<IIQQQQQQ", blob, phoff + i * phsize)
                if ph[0] == 1:
                    assert ph[7] >= 16384, f"{name} has non-16 KiB PT_LOAD alignment: {ph[7]}"
            path = Path(temp) / name
            path.write_bytes(blob)
            dynamic = subprocess.check_output(["readelf", "-W", "-d", str(path)], text=True)
            needed = set(re.findall(r"\(NEEDED\).*\[(.*?)\]", dynamic))
            assert needed <= names | system, f"{name} missing dependencies: {needed - names - system}"
            if name == "librdpsync.so":
                symbols = subprocess.check_output(["readelf", "-W", "--dyn-syms", str(path)], text=True)
                defined = {parts[7].split("@")[0] for line in symbols.splitlines()
                           if len(parts := line.split()) >= 8 and parts[0].endswith(":") and parts[6] != "UND"}
                missing = {n for n in expected_jni if "Java_com_rdp_sync_network_RdpConnector_" + n not in defined}
                assert not missing, f"JNI exports missing: {missing}"
            print(f"{abi}/{name}: ELF,16KiB,DT_NEEDED OK")
    print("ABIs:", ", ".join(abis))

sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/opt/android-sdk")))
build_tools = sdk / "build-tools/35.0.0"
za_out = subprocess.check_output([str(build_tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], text=True)
print(za_out, end="", flush=True)
certs_out = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--print-certs", "-v", str(apk)], text=True)
print(certs_out, end="")

badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
assert "name='com.rdp.sync'" in package_line, f"Unexpected official package identity: {package_line}"
assert "versionCode='22'" in package_line and "versionName='1.4.0'" in package_line, \
    f"Unexpected 1.4.0 version identity: {package_line}"

# Mandatory launcher icon & launchable HOME activity verification
launchable_lines = [line for line in badging.splitlines() if line.startswith("launchable-activity:")]
assert any("name='com.rdp.sync.ui.MainActivity'" in line for line in launchable_lines), \
    f"APK lacks launchable HOME/LAUNCHER MainActivity (desktop icon will not appear!): {launchable_lines}"

labels = [line.split(":", 1)[1] for line in badging.splitlines() if line.startswith("application-label")]
assert labels and all(label == "'RDPsync'" for label in labels), "App display name must be RDPsync in every locale"

if not is_debug_apk:
    assert not any(line.startswith("application-debuggable") for line in badging.splitlines()), \
        "Release APK must not carry application-debuggable flag"
    if os.environ.get("RDPSYNC_REQUIRE_OFFICIAL_SIGNER") == "1":
        assert "CN=Android Debug" not in certs_out, \
            f"Release APK must not be signed with ephemeral Android Debug certificate:\n{certs_out}"
        assert EXPECTED_OFFICIAL_CERT_SHA256 in certs_out.lower(), \
            f"Release APK certificate SHA-256 mismatch (expected {EXPECTED_OFFICIAL_CERT_SHA256}):\n{certs_out}"

print("\n".join(line for line in badging.splitlines()
                 if line.startswith(("package:", "launchable-activity:", "native-code:", "sdkVersion:", "targetSdkVersion:"))))
print("SHA256:", hashlib.sha256(apk.read_bytes()).hexdigest())
if not is_debug_apk and EXPECTED_OFFICIAL_CERT_SHA256 in certs_out.lower():
    print("APK verification passed; signed with persistent official RDPsync release certificate and verified launchable-activity.")
else:
    print("APK verification passed; verified launchable-activity and development signature.")

sys.stdout.flush()
