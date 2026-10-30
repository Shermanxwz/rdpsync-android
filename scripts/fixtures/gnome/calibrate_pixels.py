#!/usr/bin/env python3
"""Fail-closed same-run, same-codec reference map. Android still compares exactly."""
import hashlib
import json
from pathlib import Path
import sys
from PIL import Image

REV='97eff6ddfbbfaa8e42a126d362686bd001ff8bdc'
COLORS={(255,0,0),(0,255,0),(0,0,255),(85,204,204),(204,170,85),(153,85,221),(68,221,136),(221,68,136),(17,34,51)}

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
    mapping={};counts={};frames=[]
    for frame in result['frames']:
        filename=frame['file']
        if Path(filename).name!=filename or not filename.endswith('.png'):raise ValueError('Invalid frame path')
        path=out/filename
        if digest(path)!=frame['sha256']:raise ValueError('Frame hash changed')
        image=Image.open(path).convert('RGB')
        if list(image.size)!=frame['dimensions']:raise ValueError('Frame size changed')
        for sample in frame['samples']:
            source=tuple(sample['source_rgb']);actual=tuple(sample['rgb'])
            if source not in COLORS or len(actual)!=3 or any(type(v)!=int or not 0<=v<=255 for v in actual):raise ValueError('Invalid color')
            if image.getpixel(tuple(sample['point']))!=actual:raise ValueError('Claimed sample disagrees with PNG')
            # Independently observed on both GNOME modes with the official pinned
            # decoder. This bounds calibration validity, NOT Android comparison.
            if any(abs(a-b)>2 for a,b in zip(source,actual)):raise ValueError('Unexplained reference/source drift')
            if source in mapping and mapping[source]!=actual:raise ValueError('Unstable repeated reference color')
            mapping[source]=actual;counts[source]=counts.get(source,0)+1
        frames.append({'file':filename,'sha256':frame['sha256']})
    if set(mapping)!=COLORS:raise ValueError('Missing required source color')
    if any(counts[c]<2 for c in ((255,0,0),(0,255,0),(0,0,255),(17,34,51))):raise ValueError('No repeated static calibration')
    return {'schema':1,'codec':9,'source_revision':REV,'tested_commit':commit,'mode':mode,'server_version':version,
            'colors':{str(packed(s)):packed(a) for s,a in mapping.items()},'frames':frames,
            'linked_libraries_manifest_sha256':digest(out/'linked-libraries-sha256.txt'),
            'buildconfig_sha256':digest(out/'buildconfig.txt'),
            'comparison':'Android exact RGB equality to same-run official Progressive decoder; no Android tolerance'}

if __name__=='__main__':
    out=Path(sys.argv[1]);data=calibrate(out)
    (out/'pixel-calibration.json').write_text(json.dumps(data,sort_keys=True,indent=2)+'\n')
    print('Verified same-run Progressive reference pixel calibration')
