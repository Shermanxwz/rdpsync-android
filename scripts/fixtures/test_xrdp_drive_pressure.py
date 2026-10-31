"""Host identity/rollback controls only; never mutates actual FUSE or sysfs."""
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xrdp_drive_pressure as p


class PressureTest(unittest.TestCase):
    fixture_uid = 1000

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.mount = self.root/'mount'; self.mount.mkdir()
        self.control = self.root/'control'; self.control.mkdir()
        # Synthetic FUSE and fusectl identities are independent of the host
        # backing /tmp filesystem (overlay major 0, ext4 major 8/259, etc.).
        self.mount_inode = self.mount.stat().st_ino
        self.dev = os.makedev(0,51)
        self.control_dev = os.makedev(0,52)
        self.cid = str(os.minor(self.dev))
        self.conn = self.control/self.cid; self.conn.mkdir()
        for name,value in zip(p.NAMES,(12,9)):
            (self.conn/name).write_text(f'{value}\n')
        self.mountinfo = (f'10 9 0:{self.cid} / /home/rdpfixture/thinclient_drives rw - fuse xrdp-chansrv rw,user_id={self.fixture_uid},group_id={self.fixture_uid}\n'
                          f'11 9 0:{os.minor(self.control_dev)} / /sys/fs/fuse/connections rw - fusectl fusectl rw\n')
        self.original_write = p._write
        self.original_fstat = os.fstat
        self.original_readtext = Path.read_text
        self.original_directory = p._directory
        self.writes = []
        self.write_fault = None
        def fake_fstat(fd):
            x=self.original_fstat(fd)
            return SimpleNamespace(st_dev=self.dev if x.st_ino==self.mount_inode else self.control_dev,
                                   st_ino=x.st_ino,st_uid=self.fixture_uid,st_mode=x.st_mode)
        def directory(path):
            mapping={Path('/home/rdpfixture/thinclient_drives'):self.mount,p.CONTROL:self.control}
            return self.original_directory(mapping[Path(path)])
        def text(path,*args,**kwargs):
            if path==Path('/proc/self/mountinfo'):return self.mountinfo
            return self.original_readtext(path,*args,**kwargs)
        def write(attribute,value):
            # Ordinary temp files retain trailing bytes unlike fusectl values.
            # Truncate only this host seam; production performs one fresh write.
            fd=p._open_attribute(attribute,os.O_WRONLY)
            try:
                inode=self.original_fstat(fd).st_ino
                self.writes.append((inode,value))
                if self.write_fault:self.write_fault(fd,value)
                os.ftruncate(fd,0)
            finally:
                os.close(fd)
            return self.original_write(attribute,value)
        self.patches=[patch.object(p.os,'geteuid',return_value=self.fixture_uid),patch.object(p.os,'fstat',side_effect=fake_fstat),
                      patch.object(p,'_directory',side_effect=directory),patch.object(Path,'read_text',text),patch.object(p,'_write',side_effect=write)]
        for item in self.patches:item.start()

    def tearDown(self):
        for item in reversed(self.patches):item.stop()
        self.temp.cleanup()

    def prepare(self):return p.FusePressure(Path('/home/rdpfixture'),self.dev,'10')
    def configure(self):
        obj=self.prepare();obj.configure();return obj
    def values(self):return [int((self.conn/name).read_text()) for name in p.NAMES]

    def test_raw_host_device_and_uid_do_not_define_fuse_identity(self):
        raw_fstat=self.original_fstat
        try:
            for major,minor,uid in ((0,99,0),(8,1,1001),(259,2,1002)):
                def host_fstat(fd,device=os.makedev(major,minor),owner=uid):
                    info=raw_fstat(fd)
                    return SimpleNamespace(st_dev=device,st_ino=info.st_ino,st_uid=owner,st_mode=info.st_mode)
                self.original_fstat=host_fstat
                obj=self.configure()
                self.assertEqual(obj.identity['mount_device'],'0:51')
                self.assertEqual(obj.identity['control_device'],os.makedev(0,52))
                self.assertEqual(obj.identity['owner_uid'],self.fixture_uid)
                self.assertEqual(obj.restore()['restoration'],'restored_same_connection')
                self.assertEqual(self.values(),[12,9])
        finally:self.original_fstat=raw_fstat

    def test_same_connection_configure_restore(self):
        obj=self.configure();self.assertEqual(self.values(),[32,32]);self.assertEqual(obj.receipt['before'],dict(zip(p.NAMES,(12,9))))
        self.assertEqual(obj.restore()['restoration'],'restored_same_connection');self.assertEqual(self.values(),[12,9]);self.assertIsNone(obj.conn)
        before=len(self.writes);obj.restore();self.assertEqual(len(self.writes),before)

    def test_partial_configuration_rolls_back_both(self):
        def fault(fd,value):
            if value==32 and self.original_fstat(fd).st_ino==(self.conn/'congestion_threshold').stat().st_ino:
                raise PermissionError('owned control synthetic refusal')
        self.write_fault=fault
        with self.assertRaises(PermissionError) as failure:self.configure()
        self.assertEqual(self.values(),[12,9]);self.assertEqual(failure.exception.pressure_receipt['restoration'],'restored_same_connection')

    def test_write_took_effect_before_readback_failure_rolls_back(self):
        failed=False
        def fault(fd,value):
            nonlocal failed
            if value==32 and not failed:
                failed=True
                os.lseek(fd,0,os.SEEK_SET);os.ftruncate(fd,0);os.write(fd,b'32\n')
                raise OSError('synthetic post-write readback refusal')
        self.write_fault=fault
        with self.assertRaises(OSError) as failure:self.configure()
        self.assertEqual(self.values(),[12,9]);self.assertEqual(failure.exception.pressure_receipt['restoration'],'restored_same_connection')

    def test_attribute_uid_mismatch_is_prewrite_failure(self):
        target=(self.conn/'congestion_threshold').stat().st_ino
        def mismatch(fd):
            x=self.original_fstat(fd)
            return SimpleNamespace(st_dev=self.dev if x.st_ino==self.mount_inode else self.control_dev,
                                   st_ino=x.st_ino,st_uid=self.fixture_uid+123 if x.st_ino==target else self.fixture_uid,st_mode=x.st_mode)
        with patch.object(p.os,'fstat',side_effect=mismatch):
            with self.assertRaisesRegex(ValueError,'Unexpected control file identity'):self.configure()
        self.assertFalse(self.writes)

    def test_low_level_short_write_and_bad_readback_fail(self):
        obj=self.prepare();attribute=obj.attributes['max_background']
        try:
            with patch.object(p.os,'write',return_value=1):
                with self.assertRaises(OSError):self.original_write(attribute,32)
            with patch.object(p.os,'write',return_value=3),patch.object(p,'_read',return_value=12):
                with self.assertRaises(OSError):self.original_write(attribute,32)
        finally:obj.restore()

    def test_nonseekable_fresh_descriptions_and_no_paused_fuse_stat(self):
        obj=self.prepare()
        # After prepare the FUSE path must not be opened/stat'ed at all.
        def no_fuse_directory(path):
            self.assertEqual(Path(path),p.CONTROL)
            return self.original_directory(self.control)
        original_read=os.read;original_write=os.write;original_seek=os.lseek
        def read(fd,n):
            self.assertEqual(original_seek(fd,0,os.SEEK_CUR),0)
            return original_read(fd,n)
        def write(fd,data):
            self.assertEqual(original_seek(fd,0,os.SEEK_CUR),0)
            return original_write(fd,data)
        with (patch.object(p,'_directory',side_effect=no_fuse_directory),patch.object(p.os,'lseek',side_effect=OSError('ESPIPE')),
                patch.object(p.os,'pread',side_effect=AssertionError('no pread')),patch.object(p.os,'pwrite',side_effect=AssertionError('no pwrite')),
                patch.object(p.os,'read',side_effect=read),patch.object(p.os,'write',side_effect=write)):
            obj.configure();obj.restore()
        self.assertEqual(self.values(),[12,9])

    def test_payload_device_and_mount_id_must_match(self):
        for dev,mount_id in ((os.makedev(0,int(self.cid)+1),'10'),(self.dev,'99')):
            with self.assertRaises(ValueError):p.FusePressure(Path('/home/rdpfixture'),dev,mount_id)
        self.assertFalse(self.writes)

    def test_attribute_replacement_refused_before_configuration(self):
        obj=self.prepare();(self.conn/'max_background').unlink();(self.conn/'max_background').write_text('7\n')
        with self.assertRaises(ValueError):obj.configure()
        self.assertFalse(self.writes)

    def test_second_attribute_symlink_no_write(self):
        (self.conn/'congestion_threshold').unlink();(self.conn/'congestion_threshold').symlink_to(self.conn/'max_background')
        with self.assertRaises((OSError,ValueError)):self.configure()
        self.assertFalse(self.writes)

    def test_replaced_connection_never_restored(self):
        obj=self.configure();self.conn.rename(self.control/'old');self.conn.mkdir()
        for name in p.NAMES:(self.conn/name).write_text('7\n')
        before=len(self.writes);self.assertIn('replaced',obj.restore()['restoration']);self.assertEqual(len(self.writes),before);self.assertEqual(self.values(),[7,7])

    def test_new_mount_id_never_restored(self):
        obj=self.configure();self.mountinfo=self.mountinfo.replace('10 9 ','99 9 ')
        before=len(self.writes);obj.restore();self.assertEqual(len(self.writes),before)

    def test_destroyed_mount_records_no_write(self):
        obj=self.configure();self.mountinfo=''
        before=len(self.writes);self.assertIn('destroyed',obj.restore()['restoration']);self.assertEqual(len(self.writes),before)

    def test_mount_source_owner_device_fusectl_and_duplicates(self):
        original=self.mountinfo
        for changed in (original.replace('fuse xrdp-chansrv','fuse other'),original.replace(f'user_id={self.fixture_uid}',f'user_id={self.fixture_uid+123}'),
                        original.replace(f'0:{self.cid}',f'0:{int(self.cid)+1}',1),original.replace('fusectl fusectl','tmpfs tmpfs'),
                        original+original.splitlines()[0]+'\n'):
            self.mountinfo=changed
            with self.assertRaises(ValueError):self.configure()
            self.assertFalse(self.writes)
        self.mountinfo=original

    def test_connection_symlink_rejected(self):
        self.conn.rename(self.control/'old');self.conn.symlink_to(self.control/'old',target_is_directory=True)
        with self.assertRaises((OSError,ValueError)):self.configure()
        self.assertFalse(self.writes)

    def test_zero_malformed_values_fail_before_writes(self):
        for value in ('0\n','junk\n','65536\n','1'*70):
            (self.conn/'max_background').write_text(value)
            with self.assertRaises(ValueError):self.configure()
            self.assertFalse(self.writes)

    def test_rollback_failure_is_not_success(self):
        def fault(fd,value):
            if len(self.writes)>=2:raise PermissionError('both configure and restore refuse')
        self.write_fault=fault
        with self.assertRaises(PermissionError) as failure:self.configure()
        self.assertIn('restoration_errors',failure.exception.pressure_receipt)
        self.assertTrue(failure.exception.__notes__)

    def test_absolute_directory_symlink_components_rejected(self):
        target=self.root/'actual';target.mkdir();link=self.root/'link';link.symlink_to(target,target_is_directory=True)
        with self.assertRaises(OSError):self.original_directory(link)
        with self.assertRaises(ValueError):self.original_directory(Path('relative'))

    def test_only_fixed_nonroot_home(self):
        with self.assertRaises(ValueError):p.FusePressure(Path('/home/other'),self.dev,'10')
        with patch.object(p.os,'geteuid',return_value=0):
            with self.assertRaises(ValueError):self.configure()
        self.assertFalse(self.writes)

# Exercise the same ownership checks with distinct simulated fixture owners,
# without chown, real mounts, or dependence on the runner's actual UID.
class PressureUid1001Test(PressureTest):
    fixture_uid = 1001

class PressureUid1002Test(PressureTest):
    fixture_uid = 1002

if __name__=='__main__':unittest.main()
