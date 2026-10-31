#!/usr/bin/env python3
"""Reclaim only enumerated compiler/factory-image packages on a disposable runner."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

NDK = re.compile(r'ndk;[0-9]+\.[0-9]+\.[0-9]+')
IMAGE = re.compile(r'system-images;android-([0-9]+)(?:-ext[0-9]+)?;[A-Za-z0-9_.-]+;(?:x86|x86_64|armeabi-v7a|arm64-v8a)')
KEEP_IMAGE = 'system-images;android-29;default;x86_64'


def installed_packages(listing):
    if len(listing.encode()) > 1024 * 1024:
        raise ValueError('Installed SDK inventory exceeds bounded size')
    rows = []
    in_table = False
    for line in listing.splitlines():
        if line.strip() == 'Installed packages:':
            if in_table:
                raise ValueError('Ambiguous installed SDK inventory')
            in_table = True
            continue
        if not in_table:
            continue
        if line.strip().endswith(':') and '|' not in line:
            break
        if '|' not in line:
            continue
        path = line.split('|', 1)[0].strip()
        if path == 'Path' or re.fullmatch(r'[-\s]+', path):
            continue
        if not path or path in rows:
            raise ValueError('Empty/duplicate installed package')
        rows.append(path)
    if not in_table or not rows or len(rows) > 256:
        raise ValueError('Missing or excessive installed SDK inventory')
    return rows


def reclaim_candidates(packages):
    selected = []
    for package in packages:
        if package.startswith('ndk;'):
            if not NDK.fullmatch(package):
                raise ValueError('Unexpected NDK package identity')
            selected.append(package)
        elif package.startswith('system-images;'):
            match = IMAGE.fullmatch(package)
            if not match:
                raise ValueError('Unexpected factory image package identity')
            if int(match[1]) != 29:
                selected.append(package)
    if len(selected) > 32:
        raise ValueError('Too many SDK packages selected for bounded cleanup')
    return sorted(selected)


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise RuntimeError('Hosted disposable runner required')
    out = Path(sys.argv[1])
    if not out.is_dir():
        raise RuntimeError('Preparation evidence directory required')
    actions = []

    def command(arguments, name):
        argv = ['sdkmanager', *arguments]
        record = {'argv': argv, 'state': 'started'}
        actions.append(record)
        (out/'sdk-actions.json').write_text(json.dumps(actions, indent=2) + '\n')
        try:
            result = subprocess.run(argv, text=True, capture_output=True, timeout=180)
            (out/(name+'.stdout.txt')).write_text(result.stdout)
            (out/(name+'.stderr.txt')).write_text(result.stderr)
            record.update(state='completed', exit_code=result.returncode)
            result.check_returncode()
            return result.stdout
        except Exception as error:
            record.update(state='failed', error=type(error).__name__)
            raise
        finally:
            (out/'sdk-actions.json').write_text(json.dumps(actions, indent=2) + '\n')

    before = installed_packages(command(['--list_installed'], 'sdk-before'))
    if KEEP_IMAGE not in before or 'ndk;28.0.13004108' not in before:
        raise RuntimeError('Expected API29 image and completed compiler missing from inventory')
    selected = reclaim_candidates(before)
    (out/'sdk-selection.json').write_text(json.dumps({'installed': before, 'selected': selected,
        'preserved_api29_images': [x for x in before if IMAGE.fullmatch(x) and int(IMAGE.fullmatch(x)[1]) == 29],
        'scope': 'Enumerated installed NDK compilers and non-API29 factory images only'}, indent=2) + '\n')
    for index, package in enumerate(selected):
        command(['--uninstall', package], f'sdk-uninstall-{index:02d}')
    after = installed_packages(command(['--list_installed'], 'sdk-after'))
    if set(before) - set(after) != set(selected) or set(after) - set(before):
        raise RuntimeError('Installed SDK delta does not match exact allowlisted removal')
    if KEEP_IMAGE not in after:
        raise RuntimeError('Required API29 image was not preserved')


if __name__ == '__main__':
    main()
