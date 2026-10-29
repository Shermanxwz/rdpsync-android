#!/usr/bin/env python3
"""User-space weak-link fixture; never changes runner-wide firewall/qdisc settings."""
import asyncio
import json
import os
import pathlib
import time

DELAY = float(os.environ.get("RDP_PROXY_DELAY_MS", "80")) / 1000.0
RATE = int(os.environ.get("RDP_PROXY_BYTES_PER_SECOND", "1048576"))
received = 0
started = time.monotonic()


async def copy(reader, writer):
    global received
    try:
        while chunk := await reader.read(16 * 1024):
            await asyncio.sleep(DELAY + len(chunk) / RATE)
            writer.write(chunk)
            await writer.drain()
            received += len(chunk)
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


async def client(reader, writer):
    try:
        upstream_reader, upstream_writer = await asyncio.open_connection("127.0.0.1", 3389)
    except OSError:
        writer.close()
        return
    await asyncio.gather(copy(reader, upstream_writer), copy(upstream_reader, writer))


async def main():
    server = await asyncio.start_server(client, "127.0.0.1", 3390)
    print(json.dumps({"listener": "127.0.0.1:3390", "upstream": "127.0.0.1:3389",
                      "per_chunk_delay_ms": DELAY * 1000, "bytes_per_second_per_direction": RATE}), flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    finally:
        print(json.dumps({"forwarded_bytes": received, "elapsed_seconds": time.monotonic() - started}), flush=True)
