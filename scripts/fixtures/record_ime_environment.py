#!/usr/bin/env python3
"""Read-only, fail-closed provenance for a fresh hosted Classic test emulator.

Not an attestation against a hostile host: the pinned workflow/action and audited
pre-test scripts establish the no-IME-writer boundary. Never changes settings.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ACTION = 'a421e43855164a8197daf9d8d40fe71c6996bb0d'
IMAGE = 'system-images/android-35/google_apis/x86_64'
PACKAGE = 'system-images;android-35;google_apis;x86_64'


def require(value, message):
    if not value:
        raise ValueError(message)


def read_regular(path, limit=1024*1024):
    path = Path(path)
    require(path.is_absolute() and '..' not in path.parts, 'invalid absolute path')
    for part in [path, *path.parents]:
        require(not part.is_symlink(), 'symlink in receipt path')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and before.st_size <= limit, 'not bounded regular file')
        data = os.read(fd, limit + 1)
        after = os.fstat(fd)
        require(len(data) <= limit and len(data) == before.st_size and
                (before.st_ino, before.st_mtime_ns, before.st_size) ==
                (after.st_ino, after.st_mtime_ns, after.st_size), 'file changed while reading')
        return data, before
    finally:
        os.close(fd)


def properties(data, unique_keys=None):
    result = {}
    for line in data.decode('utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith(('#', ';')):
            continue
        require('=' in line, 'malformed property')
        key, value = (x.strip() for x in line.split('=', 1))
        require(key and (key not in result or (unique_keys is not None and key not in unique_keys)), 'duplicate property')
        result[key] = value
    return result


def workflow_policy(data):
    lines = data.decode('utf-8').splitlines()
    indices = [i for i, s in enumerate(lines) if 'uses: reactivecircus/android-emulator-runner@' in s]
    require(len(indices) == 1, 'expected one emulator action')
    i = indices[0]
    require(lines[i].strip() == 'uses: reactivecircus/android-emulator-runner@' + ACTION, 'unreviewed emulator action')
    block = []
    for line in lines[i+1:]:
        if line.startswith('      - '):
            break
        block.append(line)
    # Restricted scalar inputs only; aliases/expressions/duplicates fail closed.
    required = {'api-level': '35', 'force-avd-creation': 'true', 'enable-hw-keyboard': 'false',
                'target': 'google_apis', 'arch': 'x86_64', 'avd-name': 'test'}
    for key, value in required.items():
        matches = [s.strip().split(':', 1)[1].strip().strip("'\"") for s in block
                   if s.startswith('          ' + key + ':')]
        require(matches == [value], 'invalid action input: ' + key)
    require(not any('pre-emulator-launch-script:' in s or 'system-image-api-level:' in s for s in block), 'unreviewed action override')
    options = [s.split(':', 1)[1].strip().split() for s in block if s.startswith('          emulator-options:')]
    require(len(options) == 1 and '-no-snapshot' in options[0] and not any(x in options[0] for x in ['-snapshot', '-snapshot-storage']), 'snapshot state not excluded')
    return '\n'.join([lines[i], *block]) + '\n'


def inspect_files(home, sdk, boundary, now, workflow, save=lambda name, data: None):
    require(isinstance(boundary, int) and 0 < boundary <= now and now-boundary <= 7200, 'invalid setup boundary')
    home, sdk = Path(home), Path(sdk)
    require(home.is_absolute() and sdk.is_absolute(), 'relative host root')
    raw = {}
    data, _ = read_regular(workflow)
    raw['workflow.yml'] = data
    save('workflow.yml', data)
    raw['action-block.txt'] = workflow_policy(data).encode()
    config = home / '.android/avd/test.avd/config.ini'
    data, info = read_regular(config)
    raw['avd-config.ini'] = data
    save('avd-config.ini', data)
    require(boundary <= info.st_mtime <= now, 'AVD configuration predates setup or is future dated')
    props = properties(data, {'AvdId', 'image.sysdir.1'})
    require(props.get('AvdId') == 'test', 'wrong AVD identity')
    image = props.get('image.sysdir.1', '')
    require(image.rstrip('/') == IMAGE or image.rstrip('/') == str(sdk / IMAGE), 'unexpected system image path')
    image_path = sdk / IMAGE
    p, _ = read_regular(image_path / 'source.properties')
    raw['image-source.properties'] = p
    save('image-source.properties', p)
    ip = properties(p)
    for key, value in {'AndroidVersion.ApiLevel': '35', 'SystemImage.Abi': 'x86_64', 'SystemImage.TagId': 'google_apis'}.items():
        require(ip.get(key) == value, 'unexpected image metadata: ' + key)
    x, _ = read_regular(image_path / 'package.xml')
    raw['image-package.xml'] = x
    save('image-package.xml', x)
    root = ET.fromstring(x)
    local = [e for e in root.iter() if e.tag.split('}')[-1] == 'localPackage']
    require(len(local) == 1 and local[0].get('path') == PACKAGE, 'unexpected package identity')
    def texts(name):
        return [e.text for e in local[0].iter() if e.tag.split('}')[-1] == name]
    require(texts('api-level') == ['35'] and texts('abi') == ['x86_64'] and 'google_apis' in texts('id'), 'unexpected package details')
    return dict(avd_config_path=str(config), avd_config_mtime=info.st_mtime,
                avd_config_sha256=hashlib.sha256(data).hexdigest(), image_sysdir=str(image_path),
                image_properties_sha256=hashlib.sha256(p).hexdigest(), image_package_sha256=hashlib.sha256(x).hexdigest(),
                image_properties=ip, workflow_sha256=hashlib.sha256(raw['workflow.yml']).hexdigest()), raw


def preflight(out, env=os.environ, run=subprocess.run):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    receipt = {'schema': 1, 'success': False}
    try:
        home = Path(env.get('HOME', ''))
        require(home.is_absolute(), 'invalid HOME')
        root = home / '.android/avd'
        for part in [root, *root.parents]:
            require(not part.is_symlink(), 'symlink in AVD root')
        paths = [root / 'test.avd', root / 'test.ini']
        require(all(not os.path.lexists(p) for p in paths), 'preexisting AVD; refuse reuse or deletion')
        result = run(['git', 'rev-parse', 'HEAD'], capture_output=True, timeout=10, check=False)
        require(result.returncode == 0 and not result.stderr.strip(), 'cannot identify preflight source')
        sha = result.stdout.decode().strip()
        require(re.fullmatch(r'[0-9a-f]{40}', sha), 'invalid preflight source')
        run_id = env.get('GITHUB_RUN_ID', '')
        require(re.fullmatch(r'[1-9][0-9]*', run_id), 'invalid preflight run')
        receipt.update(success=True, setup_started_epoch=int(time.time()), avd_root=str(root),
                       absent_paths=[str(p) for p in paths], source_sha=sha, run_id=run_id)
        return receipt
    except Exception as error:
        receipt['failure'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        (out / 'ime-preflight.json').write_text(json.dumps(receipt, indent=2)+'\n')


def collect(out, env=os.environ, run=subprocess.run):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    receipt = {'schema': 1, 'success': False, 'commands': []}
    start = time.monotonic()
    def command(name, args):
        remaining = 60 - (time.monotonic() - start)
        require(remaining > 0, 'provenance deadline exhausted')
        try:
            result = run(args, capture_output=True, timeout=min(10, remaining), check=False)
        except subprocess.TimeoutExpired as error:
            (out / (name + '.stdout.txt')).write_bytes(error.stdout or b'')
            (out / (name + '.stderr.txt')).write_bytes(error.stderr or b'')
            receipt['commands'].append({'name': name, 'argv': args, 'timeout': True})
            raise
        (out / (name + '.stdout.txt')).write_bytes(result.stdout)
        (out / (name + '.stderr.txt')).write_bytes(result.stderr)
        receipt['commands'].append({'name': name, 'argv': args, 'returncode': result.returncode})
        require(result.returncode == 0 and not result.stderr.strip(), 'failed read: ' + name)
        return result.stdout.decode('utf-8').strip()
    try:
        boundary_text = env.get('RDP_AVD_SETUP_STARTED', '')
        require(re.fullmatch(r'[0-9]{10}', boundary_text), 'missing numeric setup boundary')
        boundary = int(boundary_text)
        home, sdk = env.get('HOME', ''), env.get('ANDROID_HOME', '')
        require(env.get('ANDROID_AVD_HOME') == str(Path(home)/'.android/avd'), 'unexpected action AVD home')
        preflight_bytes, _ = read_regular(out.absolute() / 'ime-preflight.json')
        pre = json.loads(preflight_bytes)
        expected_root = Path(home)/'.android/avd'
        require(pre.get('schema') == 1 and pre.get('success') is True and pre.get('setup_started_epoch') == boundary and pre.get('avd_root') == str(expected_root) and pre.get('absent_paths') == [str(expected_root/'test.avd'), str(expected_root/'test.ini')], 'missing or mismatched absent-AVD proof')
        receipt['preflight_sha256'] = hashlib.sha256(preflight_bytes).hexdigest()
        receipt['preflight'] = pre
        fields, raw = inspect_files(home, sdk, boundary, time.time(), Path.cwd()/'.github/workflows/emulator.yml', lambda name, data: (out/name).write_bytes(data))
        for name, data in raw.items():
            (out / name).write_bytes(data)
        receipt.update(fields)
        serial = env.get('ANDROID_SERIAL', '')
        require(re.fullmatch(r'emulator-[0-9]+', serial), 'not an explicit emulator')
        adb = ['adb', '-s', serial]
        avd = command('device-avd-name', adb+['emu', 'avd', 'name']).splitlines()
        require(avd == ['test', 'OK'], 'device is not expected AVD')
        sdk_version = command('device-sdk', adb+['shell', 'getprop', 'ro.build.version.sdk'])
        require(sdk_version == '35', 'device is not API35')
        require(command('device-abi', adb+['shell', 'getprop', 'ro.product.cpu.abi']) == 'x86_64', 'wrong device ABI')
        require(command('device-qemu', adb+['shell', 'getprop', 'ro.kernel.qemu']) == '1', 'not qemu')
        fingerprint = command('device-fingerprint', adb+['shell', 'getprop', 'ro.build.fingerprint'])
        require(re.fullmatch(r'google/sdk_gphone64_x86_64/emu64xa:15/[^\s]+', fingerprint), 'unexpected image fingerprint')
        boot = command('device-boot-id', adb+['shell', 'cat', '/proc/sys/kernel/random/boot_id'])
        require(re.fullmatch(r'[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}', boot), 'invalid boot identity')
        sha = command('source-head', ['git', 'rev-parse', 'HEAD'])
        require(re.fullmatch(r'[0-9a-f]{40}', sha), 'invalid source SHA')
        run_id = env.get('GITHUB_RUN_ID', '')
        require(re.fullmatch(r'[1-9][0-9]*', run_id), 'invalid run ID')
        require(pre.get('source_sha') == sha and pre.get('run_id') == run_id, 'preflight source/run changed')
        require(time.monotonic() - start < 60, 'provenance deadline exhausted')
        receipt.update(success=True, action_sha=ACTION, source_sha=sha, run_id=run_id,
                       force_avd_creation=True, enable_hw_keyboard=False, avd_name='test',
                       fresh_avd_absence_verified=True, no_snapshot=True,
                       setup_started_epoch=boundary, device_fingerprint=fingerprint,
                       device_boot_id=boot, device_sdk=sdk_version, serial=serial,
                       scope='Pinned workflow plus fresh host AVD and device identity; no settings written')
        return receipt
    except Exception as error:
        receipt['failure'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        (out / 'ime-environment.json').write_text(json.dumps(receipt, indent=2)+'\n')


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--preflight':
        print(preflight(sys.argv[2])['setup_started_epoch'])
    else:
        require(len(sys.argv) == 2, 'usage: record_ime_environment.py [--preflight] OUTDIR')
        collect(sys.argv[1])
