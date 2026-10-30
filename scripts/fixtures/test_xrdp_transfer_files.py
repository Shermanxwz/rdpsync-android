#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest
from xrdp_transfer_files import samples, manifest, read_xrdp_uris


class XrdpTransferFilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.home = Path(self.tmp.name)
        self.root = self.home / 'thinclient_drives'; self.files = self.root / '.clipboard'; self.files.mkdir(parents=True)
        self.out = self.home / 'out'; self.out.mkdir(); self.uris = []
        self.mountinfo = f'13 1 0:43 / {self.root} rw - fuse.xrdp-chansrv xrdp-chansrv rw\n'
        for name, data in samples('android').items():
            path = self.files / name; path.write_bytes(data); self.uris.append(path.as_uri())
    def tearDown(self): self.tmp.cleanup()
    def read(self): return read_xrdp_uris(self.uris, self.home, self.out, test_mountinfo=self.mountinfo)
    def test_exact_bytes_with_explicitly_synthetic_metadata(self):
        result = self.read()
        self.assertEqual({n: {k: v[k] for k in ('size', 'sha256')} for n, v in result.items()}, manifest(samples('android')))
        self.assertTrue(all(not value['via_xrdp_fuse'] for value in result.values()))
    def test_no_mount(self):
        self.mountinfo = ''
        with self.assertRaises(ValueError): self.read()
    def test_wrong_type(self):
        self.mountinfo = self.mountinfo.replace('fuse.xrdp-chansrv', 'ext4')
        with self.assertRaises(ValueError): self.read()
    def test_wrong_mount_prefix(self):
        self.mountinfo = self.mountinfo.replace('thinclient_drives', 'thinclient_drive')
        with self.assertRaises(ValueError): self.read()
    def test_path_outside_clipboard(self):
        self.uris[0] = (self.root / 'android-binary.bin').as_uri()
        with self.assertRaises(ValueError): self.read()
    def test_symlink_escape(self):
        original = self.files / 'android-binary.bin'; original.unlink()
        target = self.home / 'android-binary.bin'; target.write_bytes(samples('android')['android-binary.bin']); original.symlink_to(target)
        with self.assertRaises(ValueError): self.read()
    def test_duplicate(self):
        self.uris[1] = self.uris[0]
        with self.assertRaises(ValueError): self.read()
    def test_wrong_hash(self):
        (self.files / 'android-binary.bin').write_bytes(b'x' * 262149)
        with self.assertRaises(ValueError): self.read()
    def test_no_overwrite(self):
        sentinel = self.out / 'android-binary.bin'; sentinel.write_bytes(b'original')
        with self.assertRaises(FileExistsError): self.read()
        self.assertEqual(sentinel.read_bytes(), b'original')


if __name__ == '__main__': unittest.main()
