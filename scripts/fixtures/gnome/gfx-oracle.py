#!/usr/bin/env python3
"""Independent pinned upstream GFX decoder pixel characterization, not tolerance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
from PIL import Image

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'build/gnome-evidence/gfx-reference'
OUT.mkdir(parents=True,exist_ok=True)
CLIENT=(ROOT/'build/reference-freerdp/client-path.txt').read_text().strip()
PIN=(ROOT/'build/gnome-evidence/certificate-sha256.txt').read_text().strip()
result={'scope':'Unmodified same-version software Progressive decoder raw-pixel oracle; not relaxed Android RGB acceptance',
        'client_sha256':hashlib.sha256(Path(CLIENT).read_bytes()).hexdigest(), 'frames':[], 'success':False}
args=[CLIENT,'/v:127.0.0.1:33891','/u:rdpfixture','/p:rdpsync-fixture-only',f'/cert:fingerprint:sha256:{PIN}',
      '/sec:nla','/size:1024x768','/bpp:32','/gdi:sw','/gfx:progressive','/dynamic-resolution',
      '/t:rdpsync-gnome-gfx-oracle','/log-level:DEBUG',
      '/tune:FreeRDP_GfxH264:FALSE,FreeRDP_GfxAVC444:FALSE,FreeRDP_GfxAVC444v2:FALSE,FreeRDP_AutoReconnectionEnabled:FALSE,FreeRDP_SmartSizing:FALSE']
log=(OUT/'client.log').open('wb')
p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)

def state():
    return json.load(urllib.request.urlopen('http://127.0.0.1:8768/state',timeout=2))

def wait(predicate,seconds=30):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        assert p.poll() is None, f'Official GFX client exited {p.returncode}'
        try:
            v=predicate()
            if v:return v
        except (OSError,ValueError):pass
        time.sleep(.2)
    raise AssertionError('Official GFX oracle observable readiness timed out')

def capture(window,name):
    path=OUT/f'{name}.png'
    subprocess.run(['import','-window',window,str(path)],check=True,timeout=5)
    im=Image.open(path).convert('RGB')
    assert im.size==(1024,768),im.size
    points=[(40,40),(136,40),(232,40),(336,40),(40,140),(900,600)]
    samples=[{'point':pt,'rgb':im.getpixel(pt)} for pt in points]
    return {'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'dimensions':im.size,'samples':samples}

try:
    def find_window():
        q=subprocess.run(['xdotool','search','--onlyvisible','--name','^rdpsync-gnome-gfx-oracle$'],capture_output=True,text=True)
        return q.stdout.strip().splitlines()[0] if q.returncode==0 and q.stdout.strip() else None
    window=wait(find_window)
    def initial_ready():
        s=state()
        return s if s.get('ready') and (s['width'],s['height'])==(1024,768) else None
    initial=wait(initial_ready)
    def painted():
        f=capture(window,'initial')
        # No numeric error allowance is selected here. Characterize only once the
        # three independently colored source blocks are actually visible.
        rgb=[x['rgb'] for x in f['samples'][:3]]
        return f if all(v[i]>max(v[j] for j in range(3) if j!=i) for i,v in enumerate(rgb)) else None
    first=wait(painted)
    result['frames'].append(first);result['initial_server']=initial
    subprocess.run(['xdotool','mousemove','--window',window,'400','300','click','1'],check=True,timeout=5)
    def click_received():
        s=state()
        return s if s['clicks']==initial['clicks']+1 and s['last_release'] and abs(s['last_release'][0]-400)<=1 and abs(s['last_release'][1]-300)<=1 else None
    after=wait(click_received)
    old=first['samples'][4]['rgb']
    def response_painted():
        f=capture(window,'after-click')
        return f if f['samples'][4]['rgb']!=old else None
    second=wait(response_painted)
    result['frames'].append(second);result['after_click_server']=after
    assert (after['session_id'],after['desktop_pid'])==(initial['session_id'],initial['desktop_pid'])
    result['client_alive_after_actual_input_and_frame']=p.poll() is None
    decoder_log=(OUT/'client.log').read_text(errors='replace')
    assert 'Got GFX RDPGFX_CODECID_CAPROGRESSIVE' in decoder_log, 'No observed actual Progressive command'
    assert 'context->SurfaceCommand failed' not in decoder_log, 'Official decoder reported failure'
    result['observed_codec']='RDPGFX_CODECID_CAPROGRESSIVE (9)'
    result['success']=True
finally:
    if p.poll() is None:
        p.terminate()
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
    log.close()
    result['cleanup_returncode']=p.returncode
    (OUT/'result.json').write_text(json.dumps(result,indent=2))
