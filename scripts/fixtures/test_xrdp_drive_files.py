#!/usr/bin/env python3
"""Host controls for fixed paths/content; not evidence of an RDPDR peer."""
import errno
from pathlib import Path
import tempfile
import unittest

from xrdp_drive_files import (BLOCK, LARGE_SIZE, SMALL, expected_large_digest,
                             original_manifest, owned_drive, hash_file, inspect_imports,
                             expect_denied, read_stall_range, check_nonempty_rmdir)


class DriveFilesTest(unittest.TestCase):
    def setUp(self):
        base = Path(__file__).resolve().parents[2] / 'build/drive-host-tests'
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.home = Path(self.temp.name)
        self.mount = self.home / 'thinclient_drives'
        self.drive = self.mount / 'RdpSync'
        self.drive.mkdir(parents=True)
        self.info = f'100 99 0:10 / {self.mount} rw - fuse.xrdp-chansrv xrdp rw\n'

    def tearDown(self):
        self.temp.cleanup()

    def seed(self):
        for name, data in SMALL.items():
            (self.drive / name).write_bytes(data)
        with (self.drive / 'android-large.bin').open('wb') as output:
            remaining = LARGE_SIZE
            while remaining:
                block = BLOCK[:min(remaining, len(BLOCK))]
                output.write(block); remaining -= len(block)

    def test_stall_range_success_is_exact(self):
        value=read_stall_range(1,3,read=lambda fd,size,offset: BLOCK*4)
        self.assertEqual(value['offset'],3*1024*1024);self.assertEqual(value['size'],256*1024)

    def test_stall_short_or_corrupt_never_counts_as_cancel(self):
        for data in (b'',BLOCK+b'x',BLOCK*4+b'x',b'x'*(256*1024)):
            with self.assertRaises(AssertionError):read_stall_range(1,0,True,lambda *args:data)

    def test_declared_cancel_can_return_exact_positive_incomplete_prefix(self):
        with self.assertRaises(AssertionError):read_stall_range(1,0,False,lambda *args:BLOCK*2)
        result=read_stall_range(1,0,True,lambda *args:BLOCK*2)
        self.assertTrue(result['incomplete_prefix']);self.assertEqual(result['size'],128*1024)
        self.assertNotIn('denied',result)

    def test_stall_denial_only_in_declared_cancel_phase(self):
        def denied(*args):raise PermissionError(errno.EACCES,'synthetic')
        with self.assertRaises(PermissionError):read_stall_range(1,0,False,denied)
        self.assertEqual(read_stall_range(1,0,True,denied)['denied']['errno'],errno.EACCES)

    def test_stall_invalid_descriptor_is_not_cancel_success(self):
        def bad(*args):raise OSError(errno.EBADF,'synthetic')
        with self.assertRaises(OSError):read_stall_range(1,0,True,bad)
        with self.assertRaises(ValueError):read_stall_range(1,16,True,bad)

    def test_synthetic_mount_is_never_reported_actual(self):
        path, evidence = owned_drive(self.home, test_mountinfo=self.info)
        self.assertEqual(path, self.drive)
        self.assertFalse(evidence['via_actual_rdpdr_fuse'])
        self.assertFalse(evidence['clipboard_path_used'])

    def test_missing_mount_fails(self):
        with self.assertRaises(ValueError): owned_drive(self.home, test_mountinfo='')

    def test_non_fuse_and_duplicate_mount_fail(self):
        for info in (self.info.replace('fuse.xrdp-chansrv', 'ext4'), self.info * 2):
            with self.assertRaises(ValueError): owned_drive(self.home, test_mountinfo=info)

    def test_clipboard_child_cannot_replace_drive(self):
        self.drive.rmdir(); (self.mount / '.clipboard').mkdir()
        with self.assertRaises(ValueError): owned_drive(self.home, test_mountinfo=self.info)

    def test_symlink_drive_is_rejected(self):
        self.drive.rmdir(); self.drive.symlink_to(self.home, target_is_directory=True)
        with self.assertRaises(ValueError): owned_drive(self.home, test_mountinfo=self.info)

    def test_exact_large_stream_and_random_offsets(self):
        self.seed()
        result = inspect_imports(self.drive)
        self.assertTrue(result['read_more_than_32mib'])
        self.assertEqual(result['received'], original_manifest())
        self.assertEqual(len(result['random_reads']), 4)
        self.assertEqual(result['received']['android-large.bin']['sha256'], expected_large_digest())

    def test_one_changed_byte_fails(self):
        self.seed()
        with (self.drive / 'android-large.bin').open('r+b') as target:
            target.seek(33 * 1024 * 1024 + 3); target.write(b'\xff')
        with self.assertRaises(ValueError): inspect_imports(self.drive)

    def test_short_stream_fails(self):
        self.seed(); (self.drive / 'android-large.bin').write_bytes(BLOCK)
        with self.assertRaises(ValueError): inspect_imports(self.drive)

    def test_regular_file_type_size_and_hardlink_guard(self):
        target = self.drive / 'file'; target.write_bytes(b'a')
        with self.assertRaises(ValueError): hash_file(target, 2)
        link = self.drive / 'link'; link.hardlink_to(target)
        with self.assertRaises(ValueError): hash_file(target, 1)

    def test_nonempty_rmdir_records_unchanged_identity_and_content(self):
        child = self.drive / 'child'; child.write_bytes(b'fixture')
        result = check_nonempty_rmdir(self.drive, child)
        self.assertTrue(result['unchanged'])
        self.assertEqual(result['before'], result['after'])
        def mapped(): raise OSError(errno.EBADF, 'pinned xrdp mapping')
        self.assertEqual(check_nonempty_rmdir(self.drive, child, operation=mapped)['denied']['errno'], errno.EBADF)
        with self.assertRaises(OSError): expect_denied(mapped)

    def test_nonempty_rmdir_rejects_success_unrelated_error_and_mutation(self):
        child = self.drive / 'child'; child.write_bytes(b'fixture')
        with self.assertRaises(AssertionError):
            check_nonempty_rmdir(self.drive, child, operation=lambda: None)
        def unrelated(): raise OSError(errno.ECONNREFUSED, 'not filesystem')
        with self.assertRaises(OSError): check_nonempty_rmdir(self.drive, child, operation=unrelated)
        def mutate():
            child.write_bytes(b'changed')
            raise OSError(errno.EBADF, 'must not pass')
        with self.assertRaises(AssertionError): check_nonempty_rmdir(self.drive, child, operation=mutate)
        def disappear():
            child.unlink()
            raise OSError(errno.EBADF, 'must not pass')
        with self.assertRaises(FileNotFoundError): check_nonempty_rmdir(self.drive, child, operation=disappear)

    def test_nonempty_rmdir_rejects_same_content_replacement(self):
        child = self.drive / 'child'; child.write_bytes(b'fixture')
        def replace():
            child.rename(self.drive / 'old-child')
            child.write_bytes(b'fixture')
            raise OSError(errno.EBADF, 'must not pass')
        with self.assertRaises(AssertionError): check_nonempty_rmdir(self.drive, child, operation=replace)

    def test_nonempty_rmdir_rejects_directory_replacement(self):
        child = self.drive / 'child'; child.write_bytes(b'fixture')
        def replace():
            self.drive.rename(self.mount / 'old-drive')
            self.drive.mkdir(); child.write_bytes(b'fixture')
            raise OSError(errno.EBADF, 'must not pass')
        with self.assertRaises(AssertionError): check_nonempty_rmdir(self.drive, child, operation=replace)

    def test_negative_operation_cannot_pass_on_success_or_unrelated_failure(self):
        with self.assertRaises(AssertionError): expect_denied(lambda: None)
        def unrelated(): raise OSError(errno.ECONNREFUSED, 'not a filesystem denial')
        with self.assertRaises(OSError): expect_denied(unrelated)
        def denied(): raise PermissionError(errno.EACCES, 'fixture negative')
        self.assertEqual(expect_denied(denied)['errno'], errno.EACCES)


if __name__ == '__main__':
    unittest.main()
