#!/usr/bin/env python3
"""Certificate/auth observations for the disposable GitHub runner's xrdp only."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

CERT = pathlib.Path('/etc/xrdp/rdpsync-fixture-cert.pem')
KEY = pathlib.Path('/etc/xrdp/rdpsync-fixture-key.pem')


def fingerprint():
    der = subprocess.check_output(['openssl', 'x509', '-in', str(CERT), '-outform', 'DER'])
    return hashlib.sha256(der).hexdigest()


def prepare():
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                    '-subj', '/CN=rdpsync-isolated-' + uuid.uuid4().hex,
                    '-keyout', str(KEY), '-out', str(CERT)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['chown', 'xrdp:ssl-cert', str(KEY)], check=True)
    KEY.chmod(0o640)
    CERT.chmod(0o644)
    return fingerprint()


def state():
    log = pathlib.Path('/var/log/xrdp.log').read_text(errors='replace')
    return {'fingerprint': fingerprint(),
            'auth_requests': log.lower().count('sending login info to session manager'),
            'successful_logins': log.lower().count('login successful for user'),
            'failed_logins': log.lower().count('login failed for user')}


class Handler(BaseHTTPRequestHandler):
    def reply(self, result):
        body = json.dumps(result).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/state': self.reply(state())
        else: self.send_error(404)

    def do_POST(self):
        if self.path != '/rotate-certificate':
            self.send_error(404)
            return
        # Fixed action and fixed paths only. No command/path/user input is accepted.
        # The test calls this only after disconnecting its disposable session.
        prepare()
        subprocess.run(['systemctl', 'restart', 'xrdp'], check=True)
        self.reply(state())

    def log_message(self, fmt, *args):
        print(fmt % args, flush=True)


if __name__ == '__main__':
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.geteuid() != 0:
        raise SystemExit('Only permitted in a disposable GitHub Actions runner as its fixture owner')
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare', action='store_true')
    args = parser.parse_args()
    if args.prepare: print(prepare())
    else: HTTPServer(('127.0.0.1', 8766), Handler).serve_forever()
