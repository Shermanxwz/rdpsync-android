#!/usr/bin/env python3
"""Owned loopback RDP relay with two declared bounded client-read pauses; no payload logging."""
import asyncio
import contextlib
import json
import os
from pathlib import Path
import socket
import time

assert os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/xrdp-file-evidence/drive-relay'
OUT.mkdir(parents=True, exist_ok=True)
state = {'listener': '127.0.0.1:3391', 'upstream': '127.0.0.1:3389', 'control': '127.0.0.1:8771',
         'connections': 0, 'active_connections': 0, 'paused': False, 'pause_count': 0,
         'client_to_server_bytes': 0, 'server_to_client_bytes': 0,
         'payload_logged': False, 'kernel_receive_buffer': None, 'zero_window_proven': False, 'pause_expired': False}
permit = asyncio.Event(); permit.set()
owned = set()


def record(event):
    value = dict(state, event=event, monotonic=time.monotonic())
    with (OUT / 'events.jsonl').open('a') as output:
        output.write(json.dumps(value) + '\n')
    (OUT / 'state.json').write_text(json.dumps(value, indent=2))


async def copy(source, destination, client_to_server):
    loop = asyncio.get_running_loop()
    key = 'client_to_server_bytes' if client_to_server else 'server_to_client_bytes'
    while True:
        if client_to_server:
            await permit.wait()
        data = await loop.sock_recv(source, 16384)
        if not data:
            return
        # At most this one 16KiB buffer can have been admitted before pause.
        if client_to_server:
            await permit.wait()
        await loop.sock_sendall(destination, data)
        state[key] += len(data)


async def connection(client):
    upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    upstream.setblocking(False)
    owned.update((client, upstream))
    state['connections'] += 1; state['active_connections'] += 1
    state['kernel_receive_buffer'] = client.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)
    record('connected')
    tasks = []
    try:
        if state['active_connections'] > 2 or state['connections'] > 16:
            raise RuntimeError('Exceeded bounded owned reconnect overlap')
        await asyncio.get_running_loop().sock_connect(upstream, ('127.0.0.1', 3389))
        tasks = [asyncio.create_task(copy(client, upstream, True)),
                 asyncio.create_task(copy(upstream, client, False))]
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
    except (OSError, RuntimeError, asyncio.CancelledError) as error:
        record(type(error).__name__)
    finally:
        for task in tasks: task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        for item in (client, upstream):
            owned.discard(item); item.close()
        state['active_connections'] -= 1
        record('disconnected')


async def accept(listener):
    loop = asyncio.get_running_loop()
    while True:
        client, address = await loop.sock_accept(listener)
        if address[0] != '127.0.0.1':
            client.close(); raise RuntimeError('Unexpected relay peer address')
        client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        client.setblocking(False)
        asyncio.create_task(connection(client))


async def pause_watchdog(started):
    await asyncio.sleep(15)
    if state['paused'] and state.get('pause_started_monotonic') == started:
        state['pause_expired'] = True; state['paused'] = False; permit.set()
        record('pause-expired-infrastructure-failure')


async def control(reader, writer):
    code, response = 400, {'error': 'Only fixed zero-body fixture control actions are supported'}
    try:
        header = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 3)
        if len(header) > 4096:
            raise ValueError('Oversized control header')
        lines = header.decode('ascii').split('\r\n')
        method, path, version = lines[0].split(' ')
        if version != 'HTTP/1.1':
            raise ValueError('Expected HTTP1.1')
        headers = {}
        for line in lines[1:]:
            if line:
                name, value = line.split(':', 1)
                if name.lower() in headers:
                    raise ValueError('Duplicate control header')
                headers[name.lower()] = value.strip()
        if headers.get('content-length', '0') != '0' or 'transfer-encoding' in headers:
            raise ValueError('Zero-body control required')
        if method == 'GET' and path == '/relay':
            code, response = 200, dict(state)
        elif method == 'POST' and path == '/relay/pause-client-read':
            if state['paused'] or state['pause_count'] >= 2 or state['pause_expired'] or state['active_connections'] != 1:
                code, response = 409, {'error': 'At most two declared pauses on an active owned connection only'}
            else:
                permit.clear(); state['paused'] = True; state['pause_count'] += 1
                state['pause_started_monotonic'] = time.monotonic()
                asyncio.create_task(pause_watchdog(state['pause_started_monotonic']))
                record('pause-client-read'); code, response = 200, dict(state)
        elif method == 'POST' and path == '/relay/resume-client-read':
            if not state['paused']:
                code, response = 409, {'error': 'No active pause'}
            else:
                state['paused'] = False; permit.set()
                record('resume-client-read'); code, response = 200, dict(state)
    except (ValueError, UnicodeError, asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
        pass
    finally:
        data = json.dumps(response).encode()
        with contextlib.suppress(OSError):
            writer.write(f'HTTP/1.1 {code} Fixture\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n'.encode() + data)
            await writer.drain()
        writer.close()
        with contextlib.suppress(OSError): await writer.wait_closed()


async def main():
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    listener.bind(('127.0.0.1', 3391)); listener.listen(4); listener.setblocking(False)
    server = await asyncio.start_server(control, '127.0.0.1', 8771, limit=4096)
    record('ready')
    try:
        async with server:
            await accept(listener)
    finally:
        permit.set()
        for item in list(owned): item.close()
        listener.close(); record('cleanup')


if __name__ == '__main__':
    asyncio.run(main())
