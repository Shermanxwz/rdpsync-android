#!/usr/bin/env python3
"""Offline tests of the fail-closed, idempotent source patch mechanism."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("flow_patch", Path(__file__).with_name("patch-freerdp-flow-control.py"))
patch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patch)


class FlowPatchTest(unittest.TestCase):
    def test_idempotent_and_source_upgrade_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pins = {}
            for relative in patch.PINNED:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                data = ("reviewed fixture " + relative).encode()
                path.write_bytes(data)
                pins[relative] = hashlib.sha256(data).hexdigest()
            self.assertTrue(patch.apply(root, pins))
            self.assertFalse(patch.apply(root, pins))
            source = root / "libfreerdp/core/transport.c"
            self.assertEqual(source.read_bytes().count(patch.SUFFIX), 1)
            source.write_bytes(source.read_bytes() + b"unreviewed edit")
            with self.assertRaises(ValueError):
                patch.apply(root, pins)

    def test_dependency_drift_does_not_modify_transport(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pins = {}
            for relative in patch.PINNED:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"original")
                pins[relative] = hashlib.sha256(b"original").hexdigest()
            (root / "libfreerdp/crypto/tls.c").write_bytes(b"different TLS stack")
            with self.assertRaises(ValueError):
                patch.apply(root, pins)
            self.assertEqual((root / "libfreerdp/core/transport.c").read_bytes(), b"original")


if __name__ == "__main__":
    unittest.main()
