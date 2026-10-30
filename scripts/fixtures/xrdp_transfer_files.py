"""Independent byte checks for the fixed disposable xrdp clipboard mount only."""
import hashlib
import importlib.util
from pathlib import Path
import re
import stat
from urllib.parse import unquote, urlsplit

spec = importlib.util.spec_from_file_location('synthetic_transfer', Path(__file__).parent / 'gnome/transfer-fixture.py')
synthetic = importlib.util.module_from_spec(spec); spec.loader.exec_module(synthetic)
samples, manifest = synthetic.samples, synthetic.manifest


def read_xrdp_uris(uris, home, destination, *, test_mountinfo=None):
    root = Path(home).resolve() / 'thinclient_drives'
    allowed = root / '.clipboard'
    expected = samples('android')
    if not uris or len(uris) != len(expected):
        raise ValueError('Exactly three synthetic file URIs required')
    paths = {}
    for uri in uris:
        parsed = urlsplit(uri)
        if parsed.scheme != 'file' or parsed.netloc not in ('', 'localhost') or parsed.query or parsed.fragment:
            raise ValueError('Local file URI required')
        path = Path(unquote(parsed.path)).resolve()
        if path.parent != allowed or path.name not in expected or path.name in paths:
            raise ValueError('Not an expected file in the owned xrdp clipboard mount')
        paths[path.name] = path
    actual = test_mountinfo is None
    mountinfo = Path('/proc/self/mountinfo').read_text() if actual else test_mountinfo
    matches = []
    for line in mountinfo.splitlines():
        if ' - ' not in line:
            continue
        left, right = line.split(' - ', 1); fields, details = left.split(), right.split()
        if len(fields) < 6 or len(details) < 3:
            continue
        mount = Path(re.sub(r'\\([0-7]{3})', lambda m: chr(int(m.group(1), 8)), fields[4]))
        if mount == root and mount.resolve() == root and (details[0] == 'fuse' or details[0].startswith('fuse.')):
            matches.append({'filesystem_type': details[0], 'mount_point': str(mount), 'owned_mountinfo_line': line})
    if len(matches) != 1:
        raise ValueError('Expected one actual owned xrdp FUSE mount')
    result = {}
    for name, path in paths.items():
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_size != len(expected[name]):
            raise ValueError('Synthetic source type/length mismatch')
        size = 0; digest = hashlib.sha256()
        with path.open('rb') as source, (Path(destination) / name).open('xb') as sink:
            while True:
                block = source.read(65536)
                if not block:
                    break
                size += len(block)
                if size > len(expected[name]):
                    raise ValueError('Oversized synthetic stream')
                sink.write(block); digest.update(block)
        if size != len(expected[name]) or digest.hexdigest() != hashlib.sha256(expected[name]).hexdigest():
            raise ValueError('Received bytes do not match independent original')
        result[name] = {'size': size, 'sha256': digest.hexdigest(), 'via_xrdp_fuse': actual,
                        'mount_metadata_origin': 'actual /proc/self/mountinfo' if actual else 'synthetic host-unit metadata',
                        'mount': matches[0]}
    return result
