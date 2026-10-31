#!/usr/bin/env python3
"""Add scoped direct-TLS observation and nonblocking drain to reviewed source.

The original probe stays read-only. The separate owner-only drain sends only
already-encrypted bytes, never TLS, certificate handling, or authentication.
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

/* RDPSYNC_DIRECT_TLS_DRAIN_V1. Protocol owner only. Preserve the original
 * read-only probe. >=0 remaining ciphertext; -1 closed, -2 unsupported;
 * -3 fatal/budget violation. Never enqueue bytes or run TLS handshake code.
 * One bounded BufferedSocket drain visits at most two ringbuffer spans and
 * sends at most 256 KiB. Successful sends reduce the finite original queue;
 * EAGAIN/EINTR/zero progress return at once.
 * No wait, sleep, retry loop, peer acknowledgement or application retry here.
 */
FREERDP_API BOOL rdpsync_freerdp_tcp_layer_nonblocking(const rdpTransportLayer* layer);
FREERDP_API int rdpsync_freerdp_tcp_drain_buffered(BIO* buffered);

FREERDP_API INT64 rdpsync_freerdp_drain_pending(rdpContext* context)
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
        transport->blocking ||
        transport->io.SetBlockingMode != transport_default_set_blocking_mode ||
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
    BIO* layer = BIO_next(buffered);
    if (!layer || BIO_next(layer) || BIO_method_type(layer) != BIO_TYPE_SIMPLE ||
        strcmp(BIO_method_name(layer), "TransportLayer") != 0 ||
        !rdpsync_freerdp_tcp_layer_nonblocking((const rdpTransportLayer*)BIO_get_data(layer)))
        goto out;
    const long before = BIO_ctrl(buffered, BIO_CTRL_WPENDING, 0, NULL);
    if (before < 0)
        goto out;
    result = -3;
    /* Shared ciphertext can include a valid 8 MiB CLIPRDR response. Limit
     * work per call, never reject a legal queue just for aggregate size. */
    if (before > 0 && rdpsync_freerdp_tcp_drain_buffered(buffered) != 0)
        goto out;
    const long after = BIO_ctrl(buffered, BIO_CTRL_WPENDING, 0, NULL);
    if (after < 0 || after > before || before - after > 256 * 1024)
        goto out;
    result = after;
out:
    LeaveCriticalSection(&transport->WriteLock);
    return result;
}

'''


TCP_SUFFIX = br'''

/* RDPSYNC_STOCK_TCP_NONBLOCKING_V1. Read-only identity check, invoked only
 * while the protocol owner holds transport WriteLock. Never changes fd flags.
 */
FREERDP_API BOOL rdpsync_freerdp_tcp_layer_nonblocking(const rdpTransportLayer* layer)
{
#if defined(__linux__) || defined(__ANDROID__)
    if (!layer || !layer->userContext ||
        layer->Read != freerdp_tcp_layer_read ||
        layer->Write != freerdp_tcp_layer_write ||
        layer->Close != freerdp_tcp_layer_close ||
        layer->Wait != freerdp_tcp_layer_wait ||
        layer->GetEvent != freerdp_tcp_layer_get_event)
        return FALSE;
    const rdpTcpLayer* tcp = (const rdpTcpLayer*)layer->userContext;
    if (tcp->sockfd < 0 || !tcp->hEvent)
        return FALSE;
    const int flags = fcntl(tcp->sockfd, F_GETFL);
    return flags >= 0 && (flags & O_NONBLOCK) != 0;
#else
    (void)layer;
    return FALSE;
#endif
}

/* Caller holds transport WriteLock and verified the complete direct TLS
 * stack. Mirrors stock transport_bio_buffered_write(NULL,0), except that
 * peek is limited to 256 KiB and retry-without-write/zero progress cannot
 * loop. No append, reallocation, TLS operation, wait or new response credit.
 */
FREERDP_API int rdpsync_freerdp_tcp_drain_buffered(BIO* buffered)
{
    if (!buffered || BIO_method_type(buffered) != BIO_TYPE_BUFFERED ||
        strcmp(BIO_method_name(buffered), "BufferedSocket") != 0)
        return -1;
    BIO* next = BIO_next(buffered);
    if (!next || BIO_next(next) || BIO_method_type(next) != BIO_TYPE_SIMPLE ||
        strcmp(BIO_method_name(next), "TransportLayer") != 0 ||
        !rdpsync_freerdp_tcp_layer_nonblocking((const rdpTransportLayer*)BIO_get_data(next)))
        return -1;
    WINPR_BIO_BUFFERED_SOCKET* ptr = (WINPR_BIO_BUFFERED_SOCKET*)BIO_get_data(buffered);
    if (!ptr)
        return -1;
    DataChunk chunks[2] = WINPR_C_ARRAY_INIT;
    const size_t used = ringbuffer_used(&ptr->xmitBuffer);
    const size_t budget = MIN(used, 256U * 1024U);
    const int count = ringbuffer_peek(&ptr->xmitBuffer, chunks, budget);
    size_t committed = 0;
    int result = 0;
    ptr->writeBlocked = FALSE;
    BIO_clear_flags(buffered, BIO_FLAGS_WRITE);
    for (int i = 0; i < count; ++i)
    {
        if (!chunks[i].size) continue;
        ERR_clear_error();
        const int requested = (int)chunks[i].size; /* <= 256 KiB */
        const int status = BIO_write(next, chunks[i].data, requested);
        if (status <= 0)
        {
            if (!BIO_should_retry(next) || !BIO_should_write(next))
            {
                BIO_clear_flags(buffered, BIO_FLAGS_SHOULD_RETRY);
                result = -1;
            }
            else
            {
                BIO_set_flags(buffered, BIO_FLAGS_WRITE);
                ptr->writeBlocked = TRUE;
            }
            goto out; /* EAGAIN/EINTR/zero progress never spins */
        }
        if (status > requested) { result = -1; goto out; }
        committed += (size_t)status;
        if (status < requested) goto out; /* short write: defer, never spin */
    }

out:
    ringbuffer_commit_read_bytes(&ptr->xmitBuffer, committed);
    /* Remaining bytes are deferred, not lost or reported as delivered. */
    if (!result && ringbuffer_used(&ptr->xmitBuffer))
    {
        ptr->writeBlocked = TRUE;
        BIO_set_flags(buffered, BIO_FLAGS_WRITE);
    }
    return result;
}

'''


TCP_VALIDATOR_SUFFIX = TCP_SUFFIX.split(b"/* Caller holds transport WriteLock", 1)[0]

# Exact previous read-only suffix is the sole accepted cache upgrade.
READ_ONLY_SUFFIX = SUFFIX.split(b"/* RDPSYNC_DIRECT_TLS_DRAIN_V1", 1)[0][:-1]

def apply(source: Path, pins=PINNED):
    additions = {"libfreerdp/core/transport.c": SUFFIX, "libfreerdp/core/tcp.c": TCP_SUFFIX}
    originals = {}
    changed = []
    # Validate every file before either write. A partial/unknown suffix never
    # authorizes replacing source, even when the other file matches exactly.
    for relative, digest in pins.items():
        data = (source / relative).read_bytes()
        suffix = additions.get(relative)
        applied = bool(suffix and data.endswith(suffix))
        if applied:
            data = data[:-len(suffix)]
        elif relative == "libfreerdp/core/transport.c" and data.endswith(READ_ONLY_SUFFIX):
            data = data[:-len(READ_ONLY_SUFFIX)]
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Unreviewed FreeRDP source drift: {relative}")
        originals[relative] = data
        if suffix and not applied:
            changed.append(relative)
    if not additions.keys() <= originals.keys():
        raise ValueError("Missing transport/TCP source pin")
    for relative in changed:
        (source / relative).write_bytes(originals[relative] + additions[relative])
    return bool(changed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    apply(args.source)
