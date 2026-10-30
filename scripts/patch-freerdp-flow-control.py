#!/usr/bin/env python3
"""Add a read-only, direct-TLS pending-byte probe to the exact reviewed source.

This does not alter transport writes, TLS, certificate handling, or authentication.
Refuse source drift (including a partially applied patch) rather than guessing at
the semantics of a newer FreeRDP transport. The reference client stays unmodified.
"""
import argparse
import hashlib
from pathlib import Path

PINNED = {
    "libfreerdp/core/transport.c": "71fdd19c73bcae7088d91500abfd5632df259e062f63e292a6b26144be55bf46",
    "libfreerdp/crypto/tls.c": "503238d4bbd48bc5889b4facc810709a51fcfad6db86eccea8e2cc6ba3edf515",
    "libfreerdp/core/tcp.c": "918e5c77346fe520be12d503447c74d87ad136413e7a8327ef31d635e3ebcfa7",
    "include/freerdp/transport_io.h": "b9ef749bbfbb80a13fccfc2a0b000608f82ed926bbe8bfbeb8aa70af5dad6908",
}
SUFFIX = br'''

/* RDPSYNC_DIRECT_TLS_PENDING_V1: read-only application backpressure probe.
 * FreeRDP 97eff6dd only. No write path or security behavior is changed.
 * >=0: ciphertext bytes in the actual buffered socket BIO; -1: closed/not
 * connected; -2: an unsupported transport stack. Zero never means that the
 * remote application consumed the bytes; only that this local queue drained.
 */
FREERDP_API INT64 rdpsync_freerdp_pending_bytes(rdpContext* context)
{
    if (!context || !context->rdp || !context->rdp->transport)
        return -1;
    rdpTransport* transport = context->rdp->transport;
    INT64 result = -1;
    EnterCriticalSection(&transport->WriteLock);
    if (!transport->frontBio || transport->layer == TRANSPORT_LAYER_CLOSED)
        goto out;
    result = -2;
    if (transport->GatewayEnabled || transport->layer != TRANSPORT_LAYER_TLS ||
        transport->io.WritePdu != transport_default_write ||
        transport->io.TLSConnect != transport_default_connect_tls ||
        transport->io.ConnectLayer != transport_default_connect_layer ||
        transport->io.AttachLayer != transport_default_attach_layer ||
        !transport->tls || !transport->tls->ssl ||
        transport->frontBio != transport->tls->bio)
        goto out;
    BIO* buffered = transport->tls->underlying;
    if (!buffered || SSL_get_wbio(transport->tls->ssl) != buffered ||
        BIO_method_type(buffered) != BIO_TYPE_BUFFERED ||
        strcmp(BIO_method_name(buffered), "BufferedSocket") != 0)
        goto out;
    /* tls.c forwards WPENDING to this exact BIO; tcp.c returns xmitBuffer used.
     * Query it directly, under the same lock as the complete outer write.
     */
    const long pending = BIO_ctrl(buffered, BIO_CTRL_WPENDING, 0, NULL);
    if (pending >= 0)
        result = pending;
out:
    LeaveCriticalSection(&transport->WriteLock);
    return result;
}
'''


def apply(source: Path, pins=PINNED):
    target = source / "libfreerdp/core/transport.c"
    original = None
    applied = False
    for relative, digest in pins.items():
        data = (source / relative).read_bytes()
        if relative == "libfreerdp/core/transport.c":
            if data.endswith(SUFFIX):
                data = data[:-len(SUFFIX)]
                applied = True
            original = data
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Unreviewed FreeRDP source drift: {relative}")
    if original is None:
        raise ValueError("Missing transport source pin")
    if not applied:
        target.write_bytes(original + SUFFIX)
    return not applied


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    apply(args.source)
