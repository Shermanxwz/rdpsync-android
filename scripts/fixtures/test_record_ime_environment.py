import os
from pathlib import Path
import tempfile
import unittest
import json
import subprocess
import time
import record_ime_environment as m


class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home, self.sdk = self.root/'home', self.root/'sdk'
        self.config = self.home/'.android/avd/test.avd/config.ini'
        self.config.parent.mkdir(parents=True)
        self.config.write_text('AvdId=test\nimage.sysdir.1='+m.IMAGE+'/\n')
        os.utime(self.config, (110,110))
        self.marker=self.home/'.android/avd/test.ini'
        self.marker.write_text('path='+str(self.config.parent)+'\npath.rel=avd/test.avd\ntarget=android-35\n')
        os.utime(self.marker,(110,110))
        self.image = self.sdk/m.IMAGE
        self.image.mkdir(parents=True)
        (self.image/'source.properties').write_text('AndroidVersion.ApiLevel=35\nSystemImage.Abi=x86_64\nSystemImage.TagId=google_apis\n')
        (self.image/'package.xml').write_text('<repository><localPackage path="'+m.PACKAGE+'"><type-details><api-level>35</api-level><abi>x86_64</abi><tag><id>google_apis</id></tag></type-details></localPackage></repository>')
        self.workflow = self.root/'workflow.yml'
        self.workflow.write_text('        uses: reactivecircus/android-emulator-runner@'+m.ACTION+'\n        with:\n'+''.join('          '+k+': '+v+'\n' for k,v in {'api-level':'35','force-avd-creation':'true','enable-hw-keyboard':'false','target':'google_apis','arch':'x86_64','avd-name':'test','emulator-options':'-no-window -no-snapshot'}.items()))

    def inspect(self, boundary=100, now=120):
        return m.inspect_files(self.home, self.sdk, boundary, now, self.workflow)

    def test_actual_a0a95_config_old_red_new_green(self):
        actual=(Path(__file__).parent/'testdata/avd-config-a0a95.ini').read_bytes()
        self.assertNotIn('AvdId',m.properties(actual, {'AvdId','image.sysdir.1'}))
        self.assertFalse(m.properties(actual, {'AvdId','image.sysdir.1'}).get('AvdId') == 'test')
        self.config.write_bytes(actual);os.utime(self.config,(110,110))
        fields,_=self.inspect();self.assertEqual(fields['avd_metadata_path'],str(self.marker))
    def test_wrong_metadata_path(self):
        self.marker.write_text('path=/elsewhere/test.avd\ntarget=android-35\n');os.utime(self.marker,(110,110))
        with self.assertRaises(ValueError):self.inspect()
    def test_wrong_metadata_relative_path(self):
        self.marker.write_text('path='+str(self.config.parent)+'\npath.rel=avd/other.avd\ntarget=android-35\n');os.utime(self.marker,(110,110))
        with self.assertRaises(ValueError):self.inspect()
    def test_metadata_old(self):
        os.utime(self.marker,(90,90))
        with self.assertRaises(ValueError):self.inspect()
    def test_metadata_symlink(self):
        dest=self.root/'metadata';self.marker.rename(dest);self.marker.symlink_to(dest)
        with self.assertRaises(ValueError):self.inspect()
    def test_metadata_duplicate(self):
        with self.marker.open('a') as f:f.write('path=/other\n')
        os.utime(self.marker,(110,110))
        with self.assertRaises(ValueError):self.inspect()
    def test_raw_image_preserved_on_bad_config(self):
        self.config.write_text('junk');os.utime(self.config,(110,110));raw={}
        with self.assertRaises(ValueError):m.inspect_files(self.home,self.sdk,100,120,self.workflow,lambda n,d:raw.update({n:d}))
        self.assertIn('image-source.properties',raw);self.assertIn('image-package.xml',raw)
    def test_good_real_files(self):
        fields,raw=self.inspect();self.assertEqual(len(fields['avd_config_sha256']),64);self.assertEqual(len(raw),6)
    def test_old_avd(self):
        with self.assertRaises(ValueError):self.inspect(111)
    def test_future_avd(self):
        with self.assertRaises(ValueError):self.inspect(now=105)
    def test_bad_boundary(self):
        for x in [0,-1,121,100.5]:
            with self.subTest(x=x),self.assertRaises(ValueError):self.inspect(x)
    def test_expired_boundary(self):
        with self.assertRaises(ValueError):self.inspect(now=10000)
    def test_path_traversal(self):
        self.config.write_text('AvdId=test\nimage.sysdir.1=../'+m.IMAGE+'\n');os.utime(self.config,(110,110))
        with self.assertRaises(ValueError):self.inspect()
    def test_absolute_wrong_image(self):
        self.config.write_text('AvdId=test\nimage.sysdir.1=/tmp/'+m.IMAGE+'\n');os.utime(self.config,(110,110))
        with self.assertRaises(ValueError):self.inspect()
    def test_absolute_correct_image(self):
        self.config.write_text('AvdId=test\nimage.sysdir.1='+str(self.image)+'\n');os.utime(self.config,(110,110));self.inspect()
    def test_fifo_not_blocking(self):
        self.config.unlink();os.mkfifo(self.config)
        with self.assertRaises(ValueError):self.inspect()
    def test_symlink_config(self):
        target=self.root/'target';self.config.rename(target);self.config.symlink_to(target)
        with self.assertRaises(ValueError):self.inspect()
    def test_symlink_parent(self):
        target=self.root/'elsewhere';self.image.rename(target);self.image.symlink_to(target,target_is_directory=True)
        with self.assertRaises(ValueError):self.inspect()
    def test_action_appended_nonidentity_config(self):
        with self.config.open('a') as f:f.write('hw.ramSize=1536\nhw.ramSize=2048M\n')
        os.utime(self.config,(110,110));self.inspect()
    def test_duplicate_config(self):
        with self.config.open('a') as f:f.write('AvdId=test\n')
        os.utime(self.config,(110,110))
        with self.assertRaises(ValueError):self.inspect()
    def test_wrong_metadata(self):
        for key,value in [('AndroidVersion.ApiLevel','34'),('SystemImage.Abi','arm64'),('SystemImage.TagId','default')]:
            p=self.image/'source.properties';original=p.read_text();p.write_text(original.replace(key+'='+dict(AndroidVersion_ApiLevel='35',SystemImage_Abi='x86_64',SystemImage_TagId='google_apis')[key.replace('.','_')],key+'='+value))
            with self.subTest(key=key),self.assertRaises(ValueError):self.inspect()
            p.write_text(original)
    def test_malformed_package(self):
        (self.image/'package.xml').write_text('<invalid')
        with self.assertRaises(Exception):self.inspect()
    def test_wrong_package(self):
        p=self.image/'package.xml';p.write_text(p.read_text().replace('android-35','android-34'))
        with self.assertRaises(ValueError):self.inspect()
    def test_unpinned_action(self):
        self.workflow.write_text(self.workflow.read_text().replace(m.ACTION,'v2'))
        with self.assertRaises(ValueError):self.inspect()
    def test_ime_writer_enabled(self):
        self.workflow.write_text(self.workflow.read_text().replace('enable-hw-keyboard: false','enable-hw-keyboard: true'))
        with self.assertRaises(ValueError):self.inspect()
    def test_force_false(self):
        self.workflow.write_text(self.workflow.read_text().replace('force-avd-creation: true','force-avd-creation: false'))
        with self.assertRaises(ValueError):self.inspect()
    def test_duplicate_input(self):
        with self.workflow.open('a') as f:f.write('          target: google_apis\n')
        with self.assertRaises(ValueError):self.inspect()
    def test_prelaunch_override(self):
        with self.workflow.open('a') as f:f.write('          pre-emulator-launch-script: evil\n')
        with self.assertRaises(ValueError):self.inspect()
    def test_snapshot_reuse(self):
        self.workflow.write_text(self.workflow.read_text().replace('-no-snapshot','-snapshot saved'))
        with self.assertRaises(ValueError):self.inspect()
    def test_preflight_rejects_existing(self):
        with self.assertRaises(ValueError):m.preflight(self.root/'evidence', {'HOME':str(self.home)})
        self.assertFalse(__import__('json').loads((self.root/'evidence/ime-preflight.json').read_text())['success'])
    def test_preflight_rejects_dangling_symlink(self):
        self.config.unlink();self.config.parent.rmdir();self.config.parent.symlink_to(self.root/'missing')
        with self.assertRaises(ValueError):m.preflight(self.root/'evidence', {'HOME':str(self.home)})
    def collect_fixture(self, override=None):
        source='a'*40
        values={'device-avd-name':'test\nOK', 'device-sdk':'35', 'device-abi':'x86_64',
                'device-qemu':'1', 'device-fingerprint':'google/sdk_gphone64_x86_64/emu64xa:15/BUILD/123:userdebug/dev-keys',
                'device-boot-id':'01234567-1234-1234-1234-0123456789ab', 'source-head':source}
        calls=[]
        def run(args, **kwargs):
            calls.append(args)
            if values.get('timeout') and args[0]=='adb':raise subprocess.TimeoutExpired(args,10,output=b'partial',stderr=b'timed out')
            if values.get('denied') and args[0]=='adb':return subprocess.CompletedProcess(args,1,b'',b'permission denied')
            if args[0]=='git': value=source
            elif args[-3:]==['emu','avd','name']:value=values['device-avd-name']
            elif args[-1]=='ro.build.version.sdk':value=values['device-sdk']
            elif args[-1]=='ro.product.cpu.abi':value=values['device-abi']
            elif args[-1]=='ro.kernel.qemu':value=values['device-qemu']
            elif args[-1]=='ro.build.fingerprint':value=values['device-fingerprint']
            elif args[-1]=='/proc/sys/kernel/random/boot_id':value=values['device-boot-id']
            else:raise AssertionError(args)
            return subprocess.CompletedProcess(args,0,(value+'\n').encode(),b'')
        env={'HOME':str(self.home),'ANDROID_HOME':str(self.sdk), 'ANDROID_AVD_HOME':str(self.home/'.android/avd'),
             'ANDROID_SERIAL':'emulator-5554','GITHUB_RUN_ID':'12345'}
        out=self.root/'evidence'
        moved=self.root/'moved';self.config.parent.rename(moved)
        marker_bytes=self.marker.read_bytes();self.marker.unlink()
        pre=m.preflight(out,env,run);moved.rename(self.config.parent);os.utime(self.config,None)
        self.marker.write_bytes(marker_bytes)
        env['RDP_AVD_SETUP_STARTED']=str(pre['setup_started_epoch'])
        w=self.root/'.github/workflows/emulator.yml';w.parent.mkdir(parents=True);w.write_bytes(self.workflow.read_bytes())
        if override:override(env,values,out)
        old=Path.cwd()
        try:
            os.chdir(self.root)
            return m.collect(out,env,run),calls
        finally:os.chdir(old)

    def test_full_readonly_receipt(self):
        receipt,calls=self.collect_fixture();self.assertTrue(receipt['success']);self.assertEqual(receipt['device_sdk'],'35')
        self.assertTrue(all('put' not in c and 'push' not in c and 'root' not in c for c in calls))
    def test_wrong_device_fails(self):
        with self.assertRaises(ValueError):self.collect_fixture(lambda e,v,o:v.update({'device-sdk':'34'}))
        self.assertFalse(json.loads((self.root/'evidence/ime-environment.json').read_text())['success'])
    def test_timeout_preserves_raw(self):
        with self.assertRaises(subprocess.TimeoutExpired):self.collect_fixture(lambda e,v,o:v.update({'timeout':True}))
        self.assertEqual((self.root/'evidence/device-avd-name.stdout.txt').read_bytes(),b'partial')
    def test_command_denial_fails(self):
        with self.assertRaises(ValueError):self.collect_fixture(lambda e,v,o:v.update({'denied':True}))
    def test_fingerprint_fails(self):
        with self.assertRaises(ValueError):self.collect_fixture(lambda e,v,o:v.update({'device-fingerprint':'unknown'}))
    def test_wrong_avd_device_fails(self):
        with self.assertRaises(ValueError):self.collect_fixture(lambda e,v,o:v.update({'device-avd-name':'other\nOK'}))
    def test_preflight_run_binding(self):
        with self.assertRaises(ValueError):self.collect_fixture(lambda e,v,o:e.update({'GITHUB_RUN_ID':'987'}))
    def test_missing_preflight(self):
        with self.assertRaises(FileNotFoundError):self.collect_fixture(lambda e,v,o:(o/'ime-preflight.json').unlink())
    def test_bad_properties(self):
        for data in [b'junk',b'a=1\na=2',b'\xff']:
            with self.subTest(data=data),self.assertRaises(Exception):m.properties(data)

if __name__=='__main__':unittest.main()
