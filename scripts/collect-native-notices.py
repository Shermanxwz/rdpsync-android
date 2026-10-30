#!/usr/bin/env python3
"""Collect exact upstream notices offline; no dependency downloads or build changes.

This is a conservative source-notice collection, not a linked-object inventory or
a license classification tool. Read immutable upstream inputs rather than generated
build files. Kept notice blocks may cover optional components not in this APK.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile


ROOT = Path(__file__).resolve().parents[1]
FREERDP_COMMIT = "97eff6ddfbbfaa8e42a126d362686bd001ff8bdc"
OPENSSL_SHA256 = "603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
FREERDP_SCOPES = (
    "include/", "libfreerdp/", "client/common/", "channels/",
    "winpr/include/", "winpr/libwinpr/",
)
OPENSSL_SCOPES = ("include/", "crypto/", "ssl/", "providers/", "engines/")
EXTENSIONS = {".c", ".h", ".cpp", ".cc", ".in", ".inc", ".s", ".asm", ".pl", ".pm"}
MARKER = re.compile(
    rb"copyright|SPDX-License-Identifier|licensed under|redistribution and use|"
    rb"permission is hereby granted|public domain|all rights reserved|"
    rb"this software is provided|rights for redistribution|BSD license|"
    rb"(?-i:Written by [A-Z])", re.I,
)
COMMENTS = re.compile(rb"/\*.*?\*/|(?m:^[ \t]*//[^\n]*(?:\n[ \t]*//[^\n]*)*)", re.S)
HASH_COMMENTS = re.compile(rb"(?m:^[ \t]*\#[^\n]*(?:\n[ \t]*\#[^\n]*)*)")
ASM_COMMENTS = re.compile(rb"(?m:^[ \t]*;[^\n]*(?:\n[ \t]*;[^\n]*)*)")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def archive_files(data, strip_prefix=""):
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as archive:
        result = {}
        for entry in archive:
            if not entry.isfile() or not entry.name.startswith(strip_prefix):
                continue
            path = entry.name[len(strip_prefix):]
            # Only read archive members; never extract or follow symlinks.
            result[path] = archive.extractfile(entry).read()
        return result


def source_notices(files, scopes, component):
    notices = {}
    scanned = 0
    for path, data in sorted(files.items()):
        parts = PurePosixPath(path).parts
        if (not path.startswith(scopes)
                or any(part.lower() in {"test", "tests", "fuzz", "fuzzers"} for part in parts)
                or PurePosixPath(path).suffix.lower() not in EXTENSIONS):
            continue
        scanned += 1
        patterns = [COMMENTS]
        if PurePosixPath(path).suffix.lower() in {".pl", ".pm", ".s", ".asm"}:
            patterns.append(HASH_COMMENTS)
        if PurePosixPath(path).suffix.lower() == ".asm":
            patterns.append(ASM_COMMENTS)
        for pattern in patterns:
            for match in pattern.finditer(data):
                block = match.group()
                if MARKER.search(block):
                    location = f"{component}/{path}:{data[:match.start()].count(bytes([10])) + 1}"
                    notices.setdefault(block, set()).add(location)
    return notices, scanned


def collect(freerdp_dir, openssl_archive):
    revision = subprocess.check_output(
        ["git", "-C", str(freerdp_dir), "rev-parse", "HEAD"], text=True,
    ).strip()
    if revision != FREERDP_COMMIT:
        raise ValueError("FreeRDP checkout does not match the pinned revision")
    freerdp = archive_files(subprocess.check_output(
        ["git", "-C", str(freerdp_dir), "archive", "--format=tar", FREERDP_COMMIT],
    ))
    compressed = openssl_archive.read_bytes()
    if sha256(compressed) != OPENSSL_SHA256:
        raise ValueError("OpenSSL archive does not match the pinned SHA256")
    openssl = archive_files(compressed, "openssl-3.5.9/")
    assets = {
        "freerdp-3.32.2-LICENSE.txt": freerdp["LICENSE"],
        "openssl-3.5.9-LICENSE.txt": openssl["LICENSE.txt"],
        "android-cpufeatures-NOTICE.txt": freerdp["winpr/libwinpr/sysinfo/cpufeatures/NOTICE"],
    }
    combined = {}
    components = []
    for name, files, scopes, identity in (
        ("FreeRDP-3.32.2", freerdp, FREERDP_SCOPES, {"git_commit": FREERDP_COMMIT}),
        ("OpenSSL-3.5.9", openssl, OPENSSL_SCOPES, {"archive_sha256": OPENSSL_SHA256}),
    ):
        notices, scanned = source_notices(files, scopes, name)
        components.append({"component": name, **identity, "source_scopes": list(scopes),
                           "source_files_scanned": scanned, "distinct_notice_blocks": len(notices)})
        for block, locations in notices.items():
            combined.setdefault(block, set()).update(locations)
    # Guard the independently identified non-root notices against extractor drift.
    for required in (
        "FreeRDP-3.32.2/winpr/libwinpr/crypto/md4.c:",
        "FreeRDP-3.32.2/winpr/libwinpr/sysinfo/cpufeatures/cpu-features.c:",
        "FreeRDP-3.32.2/include/freerdp/codec/region.h:",
    ):
        if not any(location.startswith(required) for locations in combined.values() for location in locations):
            raise ValueError("A required distinct source notice was not collected")
    output = bytearray(
        b"Native upstream source notices for RdpSync Android\n"
        b"=================================================\n\n"
        b"Exact upstream copyright/license comment blocks, deduplicated without rewriting.\n"
        b"Source paths and line numbers identify each retained block. This conservative\n"
        b"collection includes optional source components and is NOT a claim that each\n"
        b"listed component is linked or enabled. Tests/docs are excluded. Root license\n"
        b"texts and the upstream Android cpufeatures NOTICE are bundled separately.\n"
        b"Generated offline by scripts/collect-native-notices.py from pinned upstream\n"
        b"inputs; see manifest.json and the repository THIRD_PARTY_NOTICES.md.\n\n"
    )
    for block, locations in sorted(combined.items(), key=lambda entry: min(entry[1])):
        output.extend(b"------------------------------------------------------------------------\n")
        output.extend(("Sources:\n" + "\n".join("  " + p for p in sorted(locations)) + "\n\n").encode())
        output.extend(block)
        output.extend(b"\n\n")
    assets["native-source-NOTICES.txt"] = bytes(output)
    manifest = {
        "schema": 1,
        "scope": "Conservative exact upstream source-notice collection; not a linked-component inventory",
        "generator": "scripts/collect-native-notices.py",
        "source_extensions": sorted(EXTENSIONS),
        "components": components,
        "assets": {name: {"sha256": sha256(data), "bytes": len(data)} for name, data in sorted(assets.items())},
    }
    assets["manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    return assets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freerdp-dir", type=Path, default=ROOT / "build/freerdp-android/freerdp-src")
    parser.add_argument("--openssl-archive", type=Path, default=ROOT / "build/freerdp-android/openssl-3.5.9.tar.gz")
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/third_party")
    parser.add_argument("--check", action="store_true", help="Compare regenerated bytes without changing files")
    args = parser.parse_args()
    assets = collect(args.freerdp_dir, args.openssl_archive)
    if args.check:
        for name, data in assets.items():
            if (args.output / name).read_bytes() != data:
                raise SystemExit(f"Native notice mismatch: {name}")
    else:
        args.output.mkdir(parents=True, exist_ok=True)
        for name, data in assets.items():
            (args.output / name).write_bytes(data)
    print(f"{'Verified' if args.check else 'Wrote'} {len(assets)} native notice assets ({sum(map(len, assets.values()))} bytes)")


if __name__ == "__main__":
    main()
