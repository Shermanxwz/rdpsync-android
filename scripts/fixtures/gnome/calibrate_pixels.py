#!/usr/bin/env python3
"""GNOME46 codec9 flat-patch empirical bound, not a universal codec tolerance."""
import hashlib
import json
from pathlib import Path
import sys
from PIL import Image

REV='97eff6ddfbbfaa8e42a126d362686bd001ff8bdc'
COLORS={(255,0,0),(0,255,0),(0,0,255),(85,204,204),(204,170,85),(153,85,221),(68,221,136),(221,68,136),(17,34,51)}
MAX_CHANNEL_ERROR=2
SCOPE='gnome46-flat-patches-codec9'

def source_matches(source, actual):
    return (tuple(source) in COLORS and len(actual)==3 and
            all(type(v)==int and 0<=v<=255 for v in actual) and
            all(abs(a-b)<=MAX_CHANNEL_ERROR for a,b in zip(source,actual)))

def phase_samples(name):
    """Independent fixed fixture geometry/colors; never infer source from pixels."""
    text_index={'initial.png':0,'after-click.png':0,'text-a.png':1,'text-newline.png':2,'resized.png':2}[name]
    points=[(40,40),(136,40),(232,40),(336,40),(40,140),
            (1200,600) if name=='resized.png' else (900,600)]
    colors=[(255,0,0),(0,255,0),(0,0,255),[(85,204,204),(204,170,85),(153,85,221)][text_index],
            (221,68,136) if name=='initial.png' else (68,221,136),(17,34,51)]
    return list(zip(points,colors))

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def packed(rgb):return (rgb[0]<<16)|(rgb[1]<<8)|rgb[2]

def calibrate(out):
    out=Path(out);result=json.loads((out/'result.json').read_text()); parent=out.parent
    if result.get('success') is not True or result.get('live_gates_passed') is not True or result.get('client_alive_after_actual_input_and_frame') is not True or result.get('observed_codec')!=9 or result.get('decoder_error'):
        raise ValueError('Reference must prove actual successful Progressive decoding')
    if (out/'source-revision.txt').read_text().strip()!=REV:raise ValueError('Wrong reference source')
    mode=(parent/'mode.txt').read_text().strip()
    if mode not in ('headless-extend','screen-share-mirror'):raise ValueError('Wrong mode')
    version=(parent/'gnome-version.txt').read_text().strip()
    if not version.startswith('GNOME Remote Desktop 46.'):raise ValueError('Wrong server family')
    commit=(parent/'tested-sha.txt').read_text().strip()
    if len(commit)!=40 or any(c not in '0123456789abcdef' for c in commit):raise ValueError('Missing exact checkout')
    for f in ('linked-libraries-sha256.txt','buildconfig.txt'):
        if not (out/f).is_file() or not (out/f).read_text().strip():raise ValueError('Missing linked runtime provenance')
    buildconfig=(out/'buildconfig.txt').read_text()
    if 'WITH_GFX_H264=OFF' not in buildconfig:raise ValueError('Oracle must be non-H264')
    required_frames=['initial.png','after-click.png','text-a.png','text-newline.png']
    if mode=='headless-extend':required_frames.append('resized.png')
    if [f['file'] for f in result['frames']]!=required_frames:raise ValueError('Missing/reordered phase evidence')
    mapping={};counts={};frames=[]
    for frame in result['frames']:
        filename=frame['file']
        if Path(filename).name!=filename or not filename.endswith('.png'):raise ValueError('Invalid frame path')
        path=out/filename
        if digest(path)!=frame['sha256']:raise ValueError('Frame hash changed')
        image=Image.open(path).convert('RGB')
        expected_size=(1280,720) if filename=='resized.png' else (1024,768)
        if image.size!=expected_size or list(image.size)!=frame['dimensions']:raise ValueError('Frame size changed')
        expected_samples=phase_samples(filename)
        if len(frame['samples'])!=len(expected_samples):raise ValueError('Missing spatial probes')
        for sample,(point,expected_source) in zip(frame['samples'],expected_samples):
            source=tuple(sample['source_rgb']);actual=tuple(sample['rgb'])
            if tuple(sample['point'])!=point or source!=expected_source:raise ValueError('Wrong fixture region or source color')
            if image.getpixel(tuple(sample['point']))!=actual:raise ValueError('Claimed sample disagrees with PNG')
            # GNOME46 uses lossy RFX TILE_SIMPLE under codec9. Actual repeated
            # source patches vary by phase; a first-frame exact map is invalid.
            # This empirical bound applies independently to source-vs-reference
            # and source-vs-Android, never +/-2 added to a shifted reference.
            if not source_matches(source,actual):raise ValueError('Unexplained reference/source drift')
            mapping.setdefault(source,set()).add(actual);counts[source]=counts.get(source,0)+1
        frames.append(frame)
    if set(mapping)!=COLORS:raise ValueError('Missing required source color')
    if any(counts[c]<2 for c in ((255,0,0),(0,255,0),(0,0,255),(17,34,51))):raise ValueError('No repeated static calibration')
    return {'schema':2,'codec':9,'scope':SCOPE,'max_channel_error':MAX_CHANNEL_ERROR,
            'source_revision':REV,'tested_commit':commit,'mode':mode,'server_version':version,
            'colors':{str(packed(s)):sorted(packed(a) for a in values) for s,values in mapping.items()},'frames':frames,
            'linked_libraries_manifest_sha256':digest(out/'linked-libraries-sha256.txt'),
            'buildconfig_sha256':digest(out/'buildconfig.txt'),
            'comparison':'Reference and Android independently within 2 per RGB channel of intended flat-patch SOURCE; codec9/GNOME46 only'}

if __name__=='__main__':
    out=Path(sys.argv[1]);data=calibrate(out)
    (out/'pixel-calibration.json').write_text(json.dumps(data,sort_keys=True,indent=2)+'\n')
    print('Verified same-run Progressive reference pixel calibration')
