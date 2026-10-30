#!/usr/bin/env python3
"""Fail packaging if the APK cannot load its JNI dependency closure or 16 KiB segments."""
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

apk = Path(sys.argv[1])
required = {"librdpsync.so", "libfreerdp3.so", "libfreerdp-client3.so", "libwinpr3.so"}
system = {"libc.so", "libm.so", "libdl.so", "libz.so", "liblog.so", "libandroid.so", "libjnigraphics.so", "libOpenSLES.so", "libEGL.so", "libGLESv2.so", "libvulkan.so", "libaaudio.so"}
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
subprocess.run([str(build_tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
subprocess.run([str(build_tools / "apksigner"), "verify", "--print-certs", str(apk)], check=True)
badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
assert "name='com.rdp.sync.preview3'" in package_line, "Not the authorized side-by-side Preview3 package"
assert "versionCode='15'" in package_line and "versionName='1.2.0-preview-debug'" in package_line, \
    "Unexpected Preview3 version identity"
print("\n".join(line for line in badging.splitlines() if line.startswith(("package:", "native-code:", "sdkVersion:", "targetSdkVersion:"))))
print("SHA256:", hashlib.sha256(apk.read_bytes()).hexdigest())
print("APK verification passed; signature is development-only unless independently matched to the installed release.")
