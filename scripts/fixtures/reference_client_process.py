#!/usr/bin/env python3
"""Own only the reference child; preserve real exit-vs-signal status separately."""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import uuid


def finish_client(client):
    code = client.poll()
    if code is not None:
        return 'natural', code
    client.terminate()
    try:
        code = client.wait(timeout=5)
    except subprocess.TimeoutExpired:
        client.kill()
        code = client.wait(timeout=5)
    return 'cleanup-signal', code


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', required=True, type=pathlib.Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('reference client command is required')
    out = args.out.resolve()
    run_id = os.environ.get('RDPSYNC_REFERENCE_ATTEMPT_ID')
    if run_id is not None:
        if str(uuid.UUID(run_id)) != run_id or (out / 'attempt-id.txt').read_text().strip() != run_id:
            raise ValueError('matrix attempt identity mismatch')
    else:
        # Standalone manual diagnostic, not a matrix preflight.
        run_id = str(uuid.uuid4())
        (out / 'attempt-id.txt').write_text(run_id + '\n')
    (out / 'run-id.txt').write_text(run_id + '\n')
    record = {'schema_version': 1, 'run_id': run_id, 'case_exit': 70,
              'client_pid': None, 'client_returncode': None,
              'client_exit_origin': 'unknown', 'checker_timed_out': False}
    client = None
    try:
        with (out / 'client.log').open('w') as log:
            client = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            record['client_pid'] = client.pid
            env = dict(os.environ, RDPSYNC_REFERENCE_PID=str(client.pid),
                       RDPSYNC_REFERENCE_OUT=str(out), RDPSYNC_REFERENCE_RUN_ID=run_id)
            try:
                check = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name(
                    'reference_resize_check.py'))], env=env, timeout=240)
                record['case_exit'] = check.returncode
            except subprocess.TimeoutExpired:
                record.update(case_exit=124, checker_timed_out=True)
            # Popen preserves the distinction between normal exit 130 and SIGINT
            # (-2), which a shell's normalized `wait` status cannot distinguish.
            origin, code = finish_client(client)
            record['client_exit_origin'] = origin
            record['client_returncode'] = code
    except Exception as exc:
        record['wrapper_error'] = type(exc).__name__
        record['case_exit'] = 70
    finally:
        if client is not None and client.poll() is None:
            record['client_exit_origin'] = 'cleanup-signal'
            client.kill()
            record['client_returncode'] = client.wait(timeout=5)
        (out / 'process-result.json').write_text(json.dumps(record, indent=2))
    code = record['case_exit']
    return code if code >= 0 else 128 - code


if __name__ == '__main__':
    sys.exit(main())
