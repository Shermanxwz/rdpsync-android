#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('transfer-fixture.py'))
fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)


class TransferFixtureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.runtime = self.root / 'runtime'; self.files = self.runtime / 'gnome-remote-desktop/cliprdr-test/0'
        self.files.mkdir(parents=True); self.out = self.root / 'received'; self.out.mkdir()
        self.mountinfo = f'123 1 0:42 / {self.files.parent} rw - fuse.gnome-remote-desktop gnome-remote-desktop rw\n'
        self.uris = []
        for name, data in fixture.samples('android').items():
            path = self.files / name; path.write_bytes(data); self.uris.append(path.as_uri())
    def tearDown(self): self.tmp.cleanup()
    def read(self, uris=None): return fixture.read_uploaded_uris(self.uris if uris is None else uris, self.runtime, self.out, test_mountinfo=self.mountinfo)
    def test_exact_binary_empty_unicode(self):
        actual = self.read()
        self.assertEqual({n: {k: v[k] for k in ('size', 'sha256')} for n, v in actual.items()}, fixture.manifest(fixture.samples('android')))
        self.assertTrue(all(not v['via_gnome_fuse'] and v['mount_metadata_origin'] == 'synthetic host-unit metadata' for v in actual.values()))
    def test_empty_list(self):
        with self.assertRaises(ValueError): self.read([])
    def test_non_file_uri(self):
        with self.assertRaises(ValueError): self.read(['https://example.invalid/a', *self.uris[1:]])
    def test_outside_runtime(self):
        outside = self.root / 'android-binary.bin'; outside.write_bytes(b'')
        with self.assertRaises(ValueError): self.read([outside.as_uri(), *self.uris[1:]])
    def test_duplicate_name(self):
        with self.assertRaises(ValueError): self.read([self.uris[0], self.uris[0], self.uris[2]])
    def test_wrong_bytes(self):
        (self.files / 'android-binary.bin').write_bytes(b'x' * 262149)
        with self.assertRaises(ValueError): self.read()
    def test_wrong_size(self):
        (self.files / 'android-binary.bin').write_bytes(b'x')
        with self.assertRaises(ValueError): self.read()
    def test_symlink_escape(self):
        target = self.root / 'android-binary.bin'; target.write_bytes(fixture.samples('android')['android-binary.bin'])
        path = self.files / 'android-binary.bin'; path.unlink(); path.symlink_to(target)
        with self.assertRaises(ValueError): self.read()
    def test_no_overwrite(self):
        sentinel = self.out / 'android-binary.bin'; sentinel.write_bytes(b'original')
        with self.assertRaises(FileExistsError): self.read()
        self.assertEqual(sentinel.read_bytes(), b'original')
    def test_missing_actual_mount(self):
        self.mountinfo = ''
        with self.assertRaises(ValueError): self.read()
    def test_wrong_filesystem_type(self):
        self.mountinfo = self.mountinfo.replace('fuse.gnome-remote-desktop', 'ext4')
        with self.assertRaises(ValueError): self.read()
    def test_mount_prefix_not_containment(self):
        self.mountinfo = self.mountinfo.replace('cliprdr-test', 'cliprdr-tes')
        with self.assertRaises(ValueError): self.read()
    def test_escaped_prefix_outside_owned_root(self):
        self.mountinfo = self.mountinfo.replace('gnome-remote-desktop/', 'gnome-remote-desktop\\040elsewhere/')
        with self.assertRaises(ValueError): self.read()


if __name__ == '__main__': unittest.main()
