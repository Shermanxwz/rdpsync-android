#!/usr/bin/env python3
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from PIL import Image

spec=importlib.util.spec_from_file_location('calibration',Path(__file__).with_name('calibrate_pixels.py'))
calibration=importlib.util.module_from_spec(spec);spec.loader.exec_module(calibration)

class CalibrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.out=self.root/'gfx-reference';self.out.mkdir()
        for name,value in [('mode.txt','headless-extend'),('gnome-version.txt','GNOME Remote Desktop 46.3'),('tested-sha.txt','a'*40)]:
            (self.root/name).write_text(value)
        for name,value in [('source-revision.txt',calibration.REV),('linked-libraries-sha256.txt','a'*64+' libfreerdp.so'),('buildconfig.txt','WITH_GFX_H264=OFF')]:
            (self.out/name).write_text(value)
        colors=sorted(calibration.COLORS);frames=[]
        for n in range(2):
            im=Image.new('RGB',(len(colors),1));samples=[]
            for i,c in enumerate(colors):
                rgb=tuple(max(0,v-1) for v in c);im.putpixel((i,0),rgb)
                samples.append(dict(point=[i,0],rgb=rgb,source_rgb=c))
            p=self.out/f'{n}.png';im.save(p)
            frames.append(dict(file=p.name,sha256=calibration.digest(p),dimensions=[len(colors),1],samples=samples))
        self.result=dict(success=True,live_gates_passed=True,client_alive_after_actual_input_and_frame=True,observed_codec=9,decoder_error=False,frames=frames);self.save()
    def tearDown(self):self.tmp.cleanup()
    def save(self):(self.out/'result.json').write_text(json.dumps(self.result))
    def reject(self):
        self.save()
        with self.assertRaises((ValueError,KeyError)):calibration.calibrate(self.out)
    def mutate_pixel(self,frame,index,rgb):
        f=self.result['frames'][frame];p=self.out/f['file'];im=Image.open(p).convert('RGB');im.putpixel(tuple(f['samples'][index]['point']),rgb);im.save(p)
        f['sha256']=calibration.digest(p);f['samples'][index]['rgb']=rgb
    def test_valid_exact_reference_map(self):
        self.assertEqual(len(calibration.calibrate(self.out)['colors']),9)
    def test_missing_color(self):
        for f in self.result['frames']:f['samples'].pop()
        self.reject()
    def test_conflicting_repeat(self):
        c=self.result['frames'][1]['samples'][0]['source_rgb'];self.mutate_pixel(1,0,tuple(c));self.reject()
    def test_tampered_png_hash(self):
        (self.out/'0.png').write_bytes(b'tampered');self.reject()
    def test_claimed_pixel_mismatch(self):
        self.result['frames'][0]['samples'][0]['rgb']=[20,20,20];self.reject()
    def test_wrong_codec(self):self.result['observed_codec']=0;self.reject()
    def test_missing_live_control(self):self.result["live_gates_passed"]=False;self.reject()
    def test_failed_reference(self):self.result['success']=False;self.reject()
    def test_unexplained_source_drift(self):
        c=self.result['frames'][0]['samples'][0]['source_rgb'];self.mutate_pixel(0,0,tuple(max(0,v-3) for v in c));self.reject()
    def test_wrong_source(self):
        (self.out/'source-revision.txt').write_text('b'*40);self.reject()
    def test_wrong_mode(self):
        (self.root/'mode.txt').write_text('GDM');self.reject()
    def test_missing_linked_runtime(self):
        (self.out/'linked-libraries-sha256.txt').unlink();self.reject()
    def test_h264_not_allowed(self):
        (self.out/'buildconfig.txt').write_text('WITH_GFX_H264=ON');self.reject()
    def test_path_escape(self):
        self.result['frames'][0]['file']='../0.png';self.reject()

if __name__=='__main__':unittest.main()
