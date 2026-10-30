"""Fixed synthetic operations on the actual xrdp RDPDR drive, never clipboard paths."""
import errno
import hashlib
import os
from pathlib import Path
import re
import stat

DRIVE_NAME = 'RdpSync'
LARGE_SIZE = 40 * 1024 * 1024 + 19
BLOCK = bytes((i * 37 + 11) % 256 for i in range(65536))
SMALL = {
    'android-empty.txt': b'',
    'android-二进制.bin': bytes((i * 29 + 17) % 256 for i in range(262149)),
    'android-说明.txt': 'Owned Android import · 共享空间\n'.encode(),
}
REMOTE = bytes((i * 13 + 91) % 256 for i in range(196613))


def expected_large_digest():
    digest = hashlib.sha256()
    remaining = LARGE_SIZE
    while remaining:
        chunk = BLOCK[:min(remaining, len(BLOCK))]
        digest.update(chunk)
        remaining -= len(chunk)
    return digest.hexdigest()


def original_manifest():
    result = {name: {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
              for name, data in SMALL.items()}
    result['android-large.bin'] = {'size': LARGE_SIZE, 'sha256': expected_large_digest()}
    return result


def owned_drive(home, *, test_mountinfo=None):
    """Require one real owned FUSE mount, then its non-symlink named drive child."""
    home = Path(home).resolve()
    mount = home / 'thinclient_drives'
    drive = mount / DRIVE_NAME
    actual = test_mountinfo is None
    source = Path('/proc/self/mountinfo').read_text() if actual else test_mountinfo
    matches = []
    for line in source.splitlines():
        if ' - ' not in line:
            continue
        left, right = line.split(' - ', 1)
        fields, details = left.split(), right.split()
        if len(fields) < 6 or len(details) < 3:
            continue
        decoded = re.sub(r'\\([0-7]{3})', lambda m: chr(int(m.group(1), 8)), fields[4])
        if Path(decoded) != mount:
            continue
        if details[0] == 'fuse' or details[0].startswith('fuse.'):
            matches.append({'filesystem_type': details[0], 'mount_point': decoded,
                            'owned_mountinfo_line': line})
    if len(matches) != 1 or mount.resolve() != mount:
        raise ValueError('Expected one actual owned xrdp FUSE mount')
    if drive.is_symlink() or not drive.is_dir() or drive.resolve().parent != mount:
        raise ValueError('Named filesystem device RdpSync is absent or not contained in the mount')
    return drive, {'via_actual_rdpdr_fuse': actual, 'drive_name': DRIVE_NAME,
                   'mount': matches[0], 'clipboard_path_used': False,
                   'mount_metadata_origin': 'actual /proc/self/mountinfo' if actual else 'synthetic host-unit metadata'}


def hash_file(path, expected_size):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size != expected_size:
        raise ValueError('Synthetic file type/size mismatch')
    digest, size = hashlib.sha256(), 0
    with path.open('rb') as source:
        while block := source.read(65536):
            size += len(block)
            if size > expected_size:
                raise ValueError('Stream exceeded expected synthetic size')
            digest.update(block)
    if size != expected_size:
        raise ValueError('Short synthetic stream')
    return {'size': size, 'sha256': digest.hexdigest()}


def inspect_imports(drive):
    expected = original_manifest()
    actual = {}
    names = sorted(path.name for path in drive.iterdir())
    if not set(expected) <= set(names):
        raise ValueError('Missing explicitly imported Android originals')
    for name, item in expected.items():
        actual[name] = hash_file(drive / name, item['size'])
        if actual[name] != item:
            raise ValueError('Mounted-drive bytes differ from independent Android original')
    random_reads = []
    with (drive / 'android-large.bin').open('rb', buffering=0) as source:
        for offset, size in [(1, 37), (65529, 97), (32 * 1024 * 1024 + 3, 4096), (LARGE_SIZE - 41, 41)]:
            data = os.pread(source.fileno(), size, offset)
            expected_bytes = bytes(BLOCK[(offset + i) % len(BLOCK)] for i in range(size))
            if data != expected_bytes:
                raise ValueError('Actual random-offset mounted read mismatch')
            random_reads.append({'offset': offset, 'size': size, 'sha256': hashlib.sha256(data).hexdigest()})
    return {'enumerated_names': names, 'received': actual, 'random_reads': random_reads,
            'read_more_than_32mib': actual['android-large.bin']['size'] > 32 * 1024 * 1024}


def expect_denied(operation):
    try:
        operation()
    except OSError as error:
        if error.errno not in (errno.EACCES, errno.EPERM, errno.EROFS, errno.EIO, errno.ENOTEMPTY,
                               errno.EFBIG, errno.ENOSPC, errno.EDQUOT):
            raise
        return {'errno': error.errno, 'error': type(error).__name__}
    raise AssertionError('Operation unexpectedly succeeded')


def read_stall_range(descriptor, index, cancel_expected=False, read=os.pread):
    if index not in range(16):
        raise ValueError('Fixed finite read batch only')
    offset = index * 1024 * 1024
    try:
        data = read(descriptor, 256 * 1024, offset)
    except OSError as error:
        if not cancel_expected:
            raise
        def raise_original():
            raise error
        return {'offset': offset, 'denied': expect_denied(raise_original)}
    expected = BLOCK * 4
    if cancel_expected and 0 < len(data) < len(expected) and data == expected[:len(data)]:
        # A 256KiB pread may span multiple kernel/FUSE requests. POSIX can
        # return the already-read prefix if a later request is canceled.
        # This is incomplete, never a successful full range or fake EOF.
        return {'offset': offset, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                'incomplete_prefix': True}
    if data != expected:
        raise AssertionError('Actual mounted range is empty, short outside cancellation, or differs from original')
    return {'offset': offset, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def readonly_check(drive):
    candidate = drive / 'must-not-create-in-ro.txt'
    if candidate.exists():
        raise ValueError('Read-only negative-control name already exists')
    denial = expect_denied(lambda: candidate.open('xb').close())
    if candidate.exists():
        raise AssertionError('Read-only attempt created an entry')
    return {'create_denied': denial, 'no_created_entry': True}



def check_nonempty_rmdir(directory, child, *, operation=None):
    """Pinned xrdp maps DIRECTORY_NOT_EMPTY to EBADF; scope it to this command.

    An error alone is not enough: the exact directory/child identities and child
    contents must remain unchanged. No generic denial allowlist is broadened.
    """
    def snapshot():
        parent_info, child_info = directory.lstat(), child.lstat()
        if child.parent != directory or not stat.S_ISDIR(parent_info.st_mode):
            raise ValueError('Nonempty delete control needs an actual directory and direct child')
        return {'directory_identity': [parent_info.st_dev, parent_info.st_ino],
                'child_identity': [child_info.st_dev, child_info.st_ino],
                'child': hash_file(child, child_info.st_size)}
    before = snapshot()
    try:
        (directory.rmdir if operation is None else operation)()
    except OSError as error:
        if error.errno not in (errno.ENOTEMPTY, errno.EACCES, errno.EPERM, errno.EROFS, errno.EBADF):
            raise
        denial = {'errno': error.errno, 'error': type(error).__name__}
    else:
        raise AssertionError('Nonempty directory removal unexpectedly succeeded')
    after = snapshot()
    if before != after:
        raise AssertionError('Denied rmdir changed directory/child identity or content')
    return {'denied': denial, 'unchanged': True, 'before': before, 'after': after}


def exercise_rw(drive):
    """Actual mounted operations, no shell commands and no user-provided paths."""
    root = drive / 'remote-created'
    if root.exists():
        raise ValueError('Remote scenario requires a fresh owned directory')
    root.mkdir()
    first, second = root / '第一层', root / 'second'
    first.mkdir(); second.mkdir()
    empty = first / 'empty.txt'; empty.touch(exist_ok=False)
    source = first / 'binary.bin'
    with source.open('xb', buffering=0) as stream:
        assert stream.write(REMOTE) == len(REMOTE)
        stream.flush()
    initial = hash_file(source, len(REMOTE))
    assert initial['sha256'] == hashlib.sha256(REMOTE).hexdigest()
    with source.open('r+b', buffering=0) as stream:
        stream.truncate(131101)
        stream.seek(131101)
        assert stream.write(b'append\x00\xff') == 8
    final_bytes = REMOTE[:131101] + b'append\x00\xff'
    after_write = hash_file(source, len(final_bytes))
    assert after_write['sha256'] == hashlib.sha256(final_bytes).hexdigest()
    # Pinned xrdp implements rename(), but rejects renameat2 flags before RDPDR.
    same = first / 'renamed-同目录.bin'; os.rename(source, same)
    moved = second / 'remote-roundtrip.bin'; os.rename(same, moved)
    assert not source.exists() and not same.exists()
    after_rename = hash_file(moved, len(final_bytes))
    assert after_rename == after_write
    nonempty = check_nonempty_rmdir(first, empty)
    empty.unlink(); first.rmdir()
    assert not first.exists()
    empty_dir = root / 'empty-dir'; empty_dir.mkdir(); empty_dir.rmdir()
    overflow = root / 'quota-negative.bin'
    with overflow.open('xb', buffering=0) as stream:
        sparse_denied = expect_denied(lambda: stream.truncate(512 * 1024 * 1024 + 1))
    assert overflow.stat().st_size == 0
    overflow.unlink()
    return {'mkdir_nested': True, 'write_reopen': initial, 'truncate_append_reopen': after_write,
            'same_directory_rename': True, 'cross_directory_rename': True,
            'renamed_received': after_rename, 'file_and_empty_directory_delete': True,
            'nonempty_directory_delete_denied': nonempty, 'file_limit_sparse_overflow_denied': sparse_denied,
            'export_relative_path': 'remote-created/second/remote-roundtrip.bin',
            'export_expected': {'size': len(final_bytes), 'sha256': hashlib.sha256(final_bytes).hexdigest()}}
