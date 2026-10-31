"""Finite pressure on ONE owned disposable xrdp FUSE connection, not sysctls.

No root helper, global kernel setting, app socket option, or adaptive retry.
Held O_NOFOLLOW descriptors and rediscovered mount identity prevent restoring
onto a new connection after disconnect/reconnect. Host tests isolate these OS
operations; they are not evidence that a real plateau was produced.
"""
import os
from pathlib import Path
import re
import stat

READERS = 32
CONTROL = Path('/sys/fs/fuse/connections')
NAMES = ('max_background', 'congestion_threshold')


def _unescape(value):
    return re.sub(r'\\([0-7]{3})', lambda m: chr(int(m.group(1), 8)), value)


def _mounts(text, path):
    found = []
    for line in text.splitlines():
        if ' - ' not in line:
            continue
        left, right = line.split(' - ', 1)
        fields, detail = left.split(), right.split()
        if len(fields) >= 6 and len(detail) >= 3 and _unescape(fields[4]) == str(path):
            found.append((fields, detail, line))
    return found


def _directory(path):
    """Walk each absolute directory component without following symlinks."""
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('Absolute confined directory required')
    fd = os.open('/', os.O_PATH | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        for part in path.parts[1:]:
            next_fd = os.open(part, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
            os.close(fd); fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise


def _discover(home, verify_mount=True):
    """Return identity and held conn fd; never enumerate arbitrary connections."""
    if Path(home) != Path('/home/rdpfixture') or os.geteuid() == 0:
        raise ValueError('Only non-root disposable fixture owner permitted')
    mount = Path(home) / 'thinclient_drives'
    source = Path('/proc/self/mountinfo').read_text()
    matches = _mounts(source, mount)
    if not matches:
        raise FileNotFoundError('Owned FUSE mount was destroyed')
    if len(matches) != 1:
        raise ValueError('Ambiguous owned mount')
    fields, detail, line = matches[0]
    if detail[0] not in ('fuse', 'fuse.xrdp-chansrv') or detail[1] != 'xrdp-chansrv':
        raise ValueError('Not the owned xrdp FUSE mount')
    options = detail[2].split(',')
    if f'user_id={os.geteuid()}' not in options:
        raise ValueError('Mount owner mismatch')
    control = _mounts(source, CONTROL)
    if len(control) != 1 or control[0][1][0] != 'fusectl':
        raise ValueError('Expected actual fusectl control mount')
    major, minor = map(int, fields[2].split(':'))
    if major != 0 or minor <= 0:
        raise ValueError('Invalid owned FUSE device')
    # This filesystem stat is permitted ONLY during prepare, before pause.
    if verify_mount:
        mount_fd = _directory(mount)
        try:
            info = os.fstat(mount_fd)
            if info.st_uid != os.geteuid() or info.st_dev != os.makedev(major, minor):
                raise ValueError('Mounted directory device/owner mismatch')
        finally:
            os.close(mount_fd)
    root = _directory(CONTROL)
    try:
        root_info = os.fstat(root)
        if f'{os.major(root_info.st_dev)}:{os.minor(root_info.st_dev)}' != control[0][0][2]:
            raise ValueError('fusectl device mismatch')
        connection_id = str(minor)
        conn = os.open(connection_id, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=root)
    finally:
        os.close(root)
    try:
        connection = os.fstat(conn)
        if connection.st_uid != os.geteuid() or connection.st_dev != root_info.st_dev:
            raise ValueError('FUSE connection owner/device mismatch')
        identity = {'mount_id': fields[0], 'mount_device': fields[2], 'mountinfo': line,
                    'owner_uid': os.geteuid(), 'connection_id': connection_id,
                    'control_mount_id': control[0][0][0],
                    'control_device': connection.st_dev, 'connection_inode': connection.st_ino}
        return identity, conn
    except BaseException:
        os.close(conn)
        raise


def _file_identity(info):
    return (info.st_dev, info.st_ino, info.st_uid, info.st_mode)


def _open_attribute(attribute, flags):
    conn, name, expected = attribute
    fd = os.open(name, flags | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=conn)
    try:
        if _file_identity(os.fstat(fd)) != expected:
            raise ValueError('FUSE control attribute identity changed')
        return fd
    except BaseException:
        os.close(fd)
        raise


def _read(attribute):
    # fusectl files use nonseekable_open/no_llseek. Every operation needs a
    # fresh open description at offset zero; dup/pread/seek are not substitutes.
    fd = _open_attribute(attribute, os.O_RDONLY)
    try:
        value = os.read(fd, 64)
    finally:
        os.close(fd)
    if not re.fullmatch(rb'[0-9]+\n?', value) or len(value) >= 64:
        raise ValueError('Invalid FUSE control value')
    number = int(value)
    if not 1 <= number <= 65535:
        raise ValueError('FUSE control value outside fixed bounds')
    return number


def _write(attribute, value):
    if not 1 <= value <= 65535:
        raise ValueError('Invalid restore/configure value')
    fd = _open_attribute(attribute, os.O_WRONLY)
    try:
        data = f'{value}\n'.encode()
        if os.write(fd, data) != len(data):
            raise OSError('Short FUSE control write')
    finally:
        os.close(fd)
    if _read(attribute) != value:
        raise OSError('FUSE control readback mismatch')


class FusePressure:
    def __init__(self, home, expected_device, expected_mount_id):
        # Called by prepare-stall-handle, while transport is NOT paused.
        self.home = home
        self.identity, self.conn = _discover(home, verify_mount=True)
        if (self.identity['mount_device'] != f'{os.major(expected_device)}:{os.minor(expected_device)}' or
                self.identity['mount_id'] != str(expected_mount_id)):
            os.close(self.conn)
            raise ValueError('Prepared payload descriptor belongs to a different FUSE mount')
        self.attributes = {}
        self.attribute_refs = []
        self.before = {}
        self.changed = []
        self.receipt = {'connection': self.identity, 'readers': READERS, 'configured': False,
                        'prepared_before_pause': True, 'before': self.before, 'global_settings_changed': False}
        try:
            # Capture BOTH identities/values before any mutation; hold only the
            # connection directory. Attribute I/O uses newly opened descriptions.
            for name in NAMES:
                fd = os.open(name, os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=self.conn)
                self.attribute_refs.append(fd)  # pin inode against number reuse
                info = os.fstat(fd)
                if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_dev != self.identity['control_device']:
                    raise ValueError('Unexpected control file identity')
                self.attributes[name] = (self.conn, name, _file_identity(info))
                self.before[name] = _read(self.attributes[name])
        except BaseException as error:
            self.receipt['preparation_error'] = str(error)
            self.restore()
            error.pressure_receipt = self.receipt
            raise

    def configure(self):
        try:
            if self.conn is None or self.changed or not self._same_connection():
                raise ValueError('Prepared connection changed or already configured')
            # Refuse another actor's alteration, even on the same connection.
            if {name: _read(attr) for name, attr in self.attributes.items()} != self.before:
                raise ValueError('Prepared FUSE settings changed before batch')
            for name in NAMES:
                self.changed.append(name)  # write may succeed before readback fails
                _write(self.attributes[name], READERS)
            self.receipt['after'] = {name: _read(attr) for name, attr in self.attributes.items()}
            self.receipt['configured'] = True
            return self.receipt
        except BaseException as error:
            self.receipt['configuration_error'] = str(error)
            try:
                self.restore()
            except BaseException as cleanup:
                error.add_note(f'FUSE rollback failed: {cleanup}')
            error.pressure_receipt = self.receipt
            raise

    def _same_connection(self):
        try:
            # No FUSE path opens/stats during pause: only /proc mountinfo and
            # anchored fusectl metadata. Full device/owner check was in prepare.
            identity, fd = _discover(self.home, verify_mount=False)
        except FileNotFoundError:
            return False
        try:
            return identity == self.identity and os.fstat(fd).st_ino == os.fstat(self.conn).st_ino
        finally:
            os.close(fd)

    def restore(self):
        if self.conn is None:
            return self.receipt
        errors = []
        try:
            if not self.changed:
                self.receipt['restoration'] = 'no_changes'
            elif not self._same_connection():
                self.receipt['restoration'] = 'original_connection_destroyed_or_replaced_no_write'
            else:
                for name in reversed(self.changed):
                    try:
                        _write(self.attributes[name], self.before[name])
                    except BaseException as error:
                        errors.append(f'{name}: {error}')
                if errors:
                    self.receipt['restoration_errors'] = errors
                    raise OSError('; '.join(errors))
                self.receipt['restoration'] = 'restored_same_connection'
                self.receipt['restored'] = {name: _read(attr) for name, attr in self.attributes.items()}
        except BaseException as error:
            self.receipt['restoration_error'] = str(error)
            raise
        finally:
            for fd in self.attribute_refs:
                os.close(fd)
            self.attribute_refs.clear()
            os.close(self.conn)
            self.conn = None
        return self.receipt
