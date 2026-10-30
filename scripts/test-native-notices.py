#!/usr/bin/env python3
"""Small extractor regressions; the full pinned-input reproduction uses --check."""

import importlib.util
import io
from pathlib import Path
import tarfile
import unittest


spec = importlib.util.spec_from_file_location("notices", Path(__file__).with_name("collect-native-notices.py"))
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class NativeNoticesTest(unittest.TestCase):
    def test_exact_bytes_deduplicate_with_source_locations(self):
        block = "/* Copyright © Example\r\n * Licensed under Example.\r\n */".encode()
        result, scanned = notices.source_notices(
            {"src/one.c": b"\n" + block + b"\nint one;\n", "src/two.h": block}, ("src/",), "v1",
        )
        self.assertEqual(scanned, 2)
        self.assertEqual(result, {block: {"v1/src/one.c:2", "v1/src/two.h:1"}})

    def test_scope_and_tests_excluded_and_no_code_collected(self):
        block = b"/* Copyright Example */"
        result, scanned = notices.source_notices({
            "src/a.c": b"/* unrelated algorithm */\n" + block + b"\nint copyright_counter;",
            "src/test/a.c": block, "docs/a.c": block, "src/readme.md": block,
        }, ("src/",), "v1")
        self.assertEqual(scanned, 1)
        self.assertEqual(result, {block: {"v1/src/a.c:2"}})

    def test_line_and_assembly_comments_preserved(self):
        inputs = {
            "src/a.h": b"// Copyright Example\n// Permission is hereby granted.\n",
            "src/a.pl": b"# Copyright Example\n# Licensed under Example.\n",
            "src/a.asm": b";; Copyright Example\n;; Licensed under Example.\n",
        }
        result, scanned = notices.source_notices(inputs, ("src/",), "v1")
        self.assertEqual(scanned, 3)
        self.assertEqual(set(result), {value.rstrip(b"\n") for value in inputs.values()})

    def test_public_domain_and_permission_without_copyright(self):
        blocks = [b"/* This is public domain. */", b"/* Redistribution and use are permitted. */"]
        result, _ = notices.source_notices({"src/a.c": b"\n".join(blocks)}, ("src/",), "v1")
        self.assertEqual(set(result), set(blocks))

    def test_standalone_redistribution_author_and_provenance_notices(self):
        blocks = [
            b"# Rights for redistribution and usage in source and binary forms are granted.",
            b"# Written by Andy Example for the OpenSSL project.",
            b"/* Originally under BSD license; author agrees to Apache distribution. */",
        ]
        result, _ = notices.source_notices({"src/a.pl": b"\n\n".join(blocks)}, ("src/",), "v1")
        self.assertEqual(set(result), set(blocks))
        unrelated, _ = notices.source_notices({
            "src/a.c": b"/* A field written by winpr_exportHandleToString(). */",
        }, ("src/",), "v1")
        self.assertEqual(unrelated, {})

    def test_archive_reads_regular_files_without_extracting_or_following_links(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w") as archive:
            file = tarfile.TarInfo("release/LICENSE")
            file.size = 4
            archive.addfile(file, io.BytesIO(b"text"))
            link = tarfile.TarInfo("release/other")
            link.type = tarfile.SYMTYPE
            link.linkname = "/does/not/exist"
            archive.addfile(link)
        self.assertEqual(notices.archive_files(data.getvalue(), "release/"), {"LICENSE": b"text"})


if __name__ == "__main__":
    unittest.main()
