#!/usr/bin/env python3
"""Own the two synthetic children in this disposable xrdp login session."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

home = Path.home().resolve()
assert home == Path('/home/rdpfixture') and os.getuid() != 0
base = home / 'rdpsync-file-fixture'
children = []; logs = []


def stop(*_):
    for child in children:
        if child.poll() is None: child.terminate()
    for child in children:
        try: child.wait(timeout=5)
        except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=5)
    for log in logs: log.close()


signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
try:
    for script, logpath in [(base / 'xrdp_transfer_peer.py', base / 'peer.log'), (home / 'rdp_desktop.py', home / 'fixture.log')]:
        log = logpath.open('wb'); logs.append(log)
        children.append(subprocess.Popen([sys.executable, str(script)], stdout=log, stderr=subprocess.STDOUT))
    while all(child.poll() is None for child in children): time.sleep(.25)
finally:
    stop()
