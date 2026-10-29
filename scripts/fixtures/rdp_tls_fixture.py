#!/usr/bin/env python3
"""Synthetic X.224 -> TLS endpoint. Stops at MCS; NEVER implements login/desktop."""
import argparse
import hashlib
import json
import os
import pathlib
import socket
import ssl
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def read_exact(sock, length):
    data = b''
    while len(data) < length:
        part = sock.recv(length - len(data))
        if not part:
            return data
        data += part
    return data


def main():
    if os.environ.get('RDP_SYNTHETIC_TLS_FIXTURE') != 'true':
        raise SystemExit('Explicit synthetic TLS fixture opt-in required')
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', required=True)
    args = parser.parse_args()
    directory = pathlib.Path(args.directory)
    contexts, fingerprints = [], []
    for number in (1, 2):
        cert = directory / f'cert{number}.pem'
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, directory / f'key{number}.pem')
        contexts.append(context)
        fingerprints.append(hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest())
    lock = threading.Lock()
    state = {'active': 0, 'connections': 0, 'in_flight': 0, 'settled': 0, 'tls_completed': 0,
             'application_bytes': 0, 'mcs_boundaries': 0, 'unexpected_messages': 0}

    def snapshot():
        with lock:
            return {**state, 'fingerprint': fingerprints[state['active']],
                    'limitation': 'Synthetic TLS only; no MCS response, authentication or desktop'}

    def connection(sock):
        with sock:
            sock.settimeout(10)
            with lock:
                state['connections'] += 1
                state['in_flight'] += 1
                context = contexts[state['active']]
            try:
                header = read_exact(sock, 4)
                if len(header) != 4 or header[:2] != b'\x03\x00':
                    return
                length = struct.unpack('!H', header[2:])[0]
                if not 11 <= length <= 4096:
                    return
                request = read_exact(sock, length - 4)
                if len(request) != length - 4 or request[1] != 0xe0:
                    return
                # X.224 Connection Confirm + RDP_NEG_RSP(PROTOCOL_SSL).
                sock.sendall(bytes.fromhex('030000130ed000001234000200080001000000'))
                with context.wrap_socket(sock, server_side=True) as tls:
                    with lock:
                        state['tls_completed'] += 1
                    header = read_exact(tls, 4)
                    payload = b''
                    if len(header) == 4 and header[:2] == b'\x03\x00':
                        length = struct.unpack('!H', header[2:])[0]
                        if 7 <= length <= 65535:
                            payload = read_exact(tls, length - 4)
                    with lock:
                        state['application_bytes'] += len(header) + len(payload)
                        if payload.startswith(bytes.fromhex('02f0807f65')):
                            state['mcs_boundaries'] += 1
                        elif header:
                            state['unexpected_messages'] += 1
                    # Deliberate close: never respond to MCS, ask for credentials or authenticate.
            except (OSError, ssl.SSLError):
                pass  # Expected when an untrusted/changed cert is rejected by the client.
            finally:
                with lock:
                    state['in_flight'] -= 1
                    state['settled'] += 1

    def accept_connections():
        with socket.socket() as listener:
            # Permit deliberate restart of this owned synthetic fixture while
            # its previous completed TLS sockets remain in TIME_WAIT.
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 33890))
            listener.listen(8)
            while True:
                sock, _ = listener.accept()
                threading.Thread(target=connection, args=(sock,), daemon=True).start()

    class Control(BaseHTTPRequestHandler):
        def answer(self):
            body = json.dumps(snapshot()).encode()
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path != '/state': self.send_error(404)
            else: self.answer()

        def do_POST(self):
            if self.path != '/rotate': self.send_error(404); return
            with lock:
                state['active'] = 1
            self.answer()

        def log_message(self, *args):
            pass

    threading.Thread(target=accept_connections, daemon=True).start()
    ThreadingHTTPServer(('127.0.0.1', 8767), Control).serve_forever()


if __name__ == '__main__':
    main()
