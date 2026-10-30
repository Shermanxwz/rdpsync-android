#!/usr/bin/env python3
"""Pinned upstream decoder: source-bounded GNOME46 codec9 flat-patch evidence."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
from PIL import Image
from calibrate_pixels import source_matches

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'build/gnome-evidence/gfx-reference'
OUT.mkdir(parents=True,exist_ok=True)
CLIENT=(ROOT/'build/reference-freerdp/client-path.txt').read_text().strip()
PIN=(ROOT/'build/gnome-evidence/certificate-sha256.txt').read_text().strip()
result={'scope':'Unmodified software Progressive decoder; empirical source RGB bound 2/channel for GNOME46 synthetic flat patches only',
        'client_sha256':hashlib.sha256(Path(CLIENT).read_bytes()).hexdigest(), 'frames':[], 'success':False,
        'initial_paint_observations':[], 'initial_paint_observation_count':0,
        'initial_paint_trace_scope':'Last64 actual captures; initial.png retains only the latest capture, not every trace PNG'}
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

def capture(window,name,width=1024,height=768,observe=False):
    started=time.monotonic()
    path=OUT/f'{name}.png'
    subprocess.run(['import','-window',window,str(path)],check=True,timeout=5)
    im=Image.open(path).convert('RGB')
    assert im.size==(width,height),im.size
    current=state()
    points=[(40,40),(136,40),(232,40),(336,40),(40,140),(1200,600) if width>1024 else (900,600)]
    expected=[(255,0,0),(0,255,0),(0,0,255),[(85,204,204),(204,170,85),(153,85,221)][len(current['text'])%3],
              (68,221,136) if current['clicks']%2 else (221,68,136),(17,34,51)]
    samples=[{'point':pt,'rgb':im.getpixel(pt),'source_rgb':rgb} for pt,rgb in zip(points,expected)]
    frame={'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'dimensions':im.size,'samples':samples}
    if observe:
        frame.update(capture_started_monotonic=started,capture_completed_monotonic=time.monotonic(),server_state=current)
    return frame

try:
    def find_window():
        q=subprocess.run(['xdotool','search','--onlyvisible','--name','^rdpsync-gnome-gfx-oracle$'],capture_output=True,text=True)
        return q.stdout.strip().splitlines()[0] if q.returncode==0 and q.stdout.strip() else None
    window=wait(find_window)
    def initial_ready():
        s=state()
        return s if s.get('ready') and (s['width'],s['height'])==(1024,768) else None
    initial=wait(initial_ready)
    # Keep the actual readiness evidence even if the following pixel gate fails.
    result['initial_server']=initial
    result['initial_ready_observed_monotonic']=time.monotonic()
    def painted():
        f=capture(window,'initial',observe=True)
        matches=all(source_matches(x['source_rgb'],x['rgb']) for x in f['samples'])
        result['initial_paint_observation_count']+=1
        result['initial_paint_observations']=(result['initial_paint_observations']+[dict(f,source_regions_match=matches)])[-64:]
        return f if matches else None
    assert initial['text']=='', 'Oracle requires a pristine synthetic text state'
    assert initial['clicks']==0, 'Oracle requires a pristine synthetic pointer state'
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
        return f if f['samples'][4]['rgb']!=old and all(source_matches(x['source_rgb'],x['rgb']) for x in f['samples']) else None
    second=wait(response_painted)
    result['frames'].append(second);result['after_click_server']=after
    assert (after['session_id'],after['desktop_pid'])==(initial['session_id'],initial['desktop_pid'])
    result['client_alive_after_actual_input_and_frame']=p.poll() is None
    # Calibrate every text-marker source color through actual remote keyboard
    # input, not guessed conversion constants or a blanket Android tolerance.
    for key,expected_text,name in [('a','a','text-a'),('Return','a\n','text-newline')]:
        old_text_rgb=result['frames'][-1]['samples'][3]['rgb']
        subprocess.run(['xdotool','key','--window',window,key],check=True,timeout=5)
        wait(lambda: state()['text']==expected_text)
        def text_painted():
            f=capture(window,name)
            return f if f['samples'][3]['rgb']!=old_text_rgb and all(source_matches(x['source_rgb'],x['rgb']) for x in f['samples']) else None
        result['frames'].append(wait(text_painted))
    mode=(ROOT/'build/gnome-evidence/mode.txt').read_text().strip()
    if mode=='headless-extend':
        subprocess.run(['xdotool','windowsize',window,'1280','720'],check=True,timeout=5)
        wait(lambda: state()['width']==1280 and state()['height']==720)
        def resized_painted():
            f=capture(window,'resized',1280,720)
            # Re-encoded static tiles need not equal the first lossy decode.
            # All six fixed source regions (including genuinely new x=1200)
            # must independently stay inside the empirical source bound.
            return f if all(source_matches(x['source_rgb'],x['rgb']) for x in f['samples']) else None
        result['frames'].append(wait(resized_painted))
    assert p.poll() is None, 'Official client exited after final paint'
    result['client_alive_after_actual_input_and_frame']=True
    result['live_gates_passed']=True
except BaseException as error:
    result['failure']={'type':type(error).__name__,'message':str(error)[:512],'observed_monotonic':time.monotonic()}
    raise
finally:
    # Read-only state before this owned client's teardown removes the headless monitor.
    # Diagnostic failure must never replace the original pixel/input failure.
    try:
        result['pre_cleanup_server']=state()
        result['pre_cleanup_observed_monotonic']=time.monotonic()
    except Exception as error:
        result['pre_cleanup_observation_error']=type(error).__name__+': '+str(error)[:256]
    if p.poll() is None:
        p.terminate()
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
    log.close()
    result['cleanup_returncode']=p.returncode
    # WLog stdout may be block-buffered while connected. Inspect only after the
    # owned client is joined and the file closed, without weakening live gates.
    decoder_log=(OUT/'client.log').read_text(errors='replace')
    result['observed_codec']=9 if 'Got GFX RDPGFX_CODECID_CAPROGRESSIVE' in decoder_log else None
    result['decoder_error']='context->SurfaceCommand failed' in decoder_log
    result['success']=bool(result.get('live_gates_passed') and result['observed_codec']==9 and not result['decoder_error'])
    (OUT/'result.json').write_text(json.dumps(result,indent=2))
assert result['success'], 'Finalized log must prove actual Progressive decoding with all live gates'
