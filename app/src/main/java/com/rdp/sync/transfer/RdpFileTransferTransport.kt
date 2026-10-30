package com.rdp.sync.transfer

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.rdp.sync.network.RdpConnector
import java.io.InputStream

/** Narrow CLIPRDR bridge. Never resolves peer filenames or writes native-supplied filesystem paths. */
class RdpFileTransferTransport(override val generation: Long) : TransferTransport {
    override fun isCurrent(): Boolean = RdpConnector.nativeFileTransferState().getOrNull(0) == generation && generation > 0
    override fun remainingSendBytes(): Long? {
        val state = RdpConnector.nativeFileTransferState()
        return if (state.getOrNull(0) == generation) state.getOrNull(9)?.takeIf { it >= 0 } else null
    }
    override fun capability(): TransferCapability {
        val state = RdpConnector.nativeFileTransferState()
        if (state.getOrNull(0) != generation) return TransferCapability(false, "连接已变化，请重新打开文件传输")
        return when (state.getOrNull(1)?.toInt()) {
            1 -> TransferCapability(true)
            -2 -> TransferCapability(false, "文件传输未启用；需要明确同意后重新连接")
            0 -> TransferCapability(false, "正在协商文件剪贴板功能，请稍后刷新")
            else -> TransferCapability(false, "远程端未提供文件剪贴板流传输能力")
        }
    }

    override fun publish(files: List<TransferReadLease>, cancellation: TransferCancellation): Long {
        checkCurrent(); cancellation.check()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            files.forEach { lease ->
                cancellation.check()
                descriptors += AndroidStagedFiles.openReadOnly(lease)
            }
            checkCurrent(); cancellation.check()
            // Native rechecks expected generation atomically; a reconnect cannot receive an old picker result.
            return RdpConnector.nativeFileTransferOffer(generation, files.map { it.file.name }.toTypedArray(),
                files.map { it.file.size }.toLongArray(), descriptors.map { it.fd }.toIntArray()).also {
                if (it <= 0) nativeError(it.toInt())
            }
        } finally { descriptors.forEach { runCatching { it.close() } } }
    }

    override fun revoke(token: Long) { if (token > 0) RdpConnector.nativeFileTransferCancel(token) }
    override fun offerRetained(token: Long): Boolean = token > 0 && RdpConnector.nativeFileTransferOfferRetained(token)
    override fun finishReceive(token: Long) { if (token > 0) RdpConnector.nativeFileTransferFinishReceive(token) }

    override fun requestOffer(cancellation: TransferCancellation): RemoteTransferOffer {
        checkCurrent(); cancellation.check()
        val token = RdpConnector.nativeFileTransferRequestList(generation)
        if (token <= 0) nativeError(token.toInt())
        var success = false
        try {
            cancellation.onCancel { revoke(token) }.use {
                waitReady(token, 2, cancellation)
                val names = RdpConnector.nativeFileTransferNames(token) ?: throw TransferFailure(TransferFailure.Reason.STALE)
                val sizes = RdpConnector.nativeFileTransferSizes(token) ?: throw TransferFailure(TransferFailure.Reason.STALE)
                if (names.size != sizes.size) throw TransferFailure(TransferFailure.Reason.SIZE_MISMATCH)
                checkCurrent(); cancellation.check()
                val result = RemoteTransferOffer(token, names.mapIndexed { index, name -> RemoteTransferFile(index, name, sizes[index]) })
                success = true
                return result
            }
        } finally { if (!success) finishReceive(token) }
    }

    override fun openRemote(offer: RemoteTransferOffer, file: RemoteTransferFile, cancellation: TransferCancellation): InputStream {
        checkCurrent(); cancellation.check()
        return object : InputStream() {
            private var offset = 0L
            private var closed = false
            override fun read(): Int { val byte = ByteArray(1); return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255 }
            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                require(start >= 0 && length >= 0 && start <= buffer.size - length)
                if (length == 0) return 0
                if (closed) throw TransferFailure(TransferFailure.Reason.CANCELLED)
                checkCurrent(); cancellation.check()
                if (offset == file.size) return -1
                val requested = minOf(length.toLong(), 65536L, file.size - offset).toInt()
                val rc = RdpConnector.nativeFileTransferRequestChunk(offer.token, file.index, offset, requested)
                if (rc < 0) nativeError(rc)
                cancellation.onCancel { revoke(offer.token) }.use {
                    waitReady(offer.token, 4, cancellation)
                    val bytes = RdpConnector.nativeFileTransferTakeChunk(offer.token) ?: throw TransferFailure(TransferFailure.Reason.IO)
                    if (bytes.isEmpty() || bytes.size > requested) throw TransferFailure(TransferFailure.Reason.SIZE_MISMATCH)
                    System.arraycopy(bytes, 0, buffer, start, bytes.size); offset += bytes.size
                    return bytes.size
                }
            }
            override fun close() { closed = true }
        }
    }

    private fun waitReady(token: Long, phase: Long, cancellation: TransferCancellation) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (true) {
            cancellation.check()
            val state = RdpConnector.nativeFileTransferState()
            if (state.size < 9 || state[0] != generation || state[4] != token) throw TransferFailure(TransferFailure.Reason.STALE)
            if (state[5] == phase) return
            if (state[5] == 5L) nativeError(-state[8].toInt().let { if (it < 0) -it else it })
            if (SystemClock.elapsedRealtime() >= deadline) { revoke(token); throw TransferFailure(TransferFailure.Reason.IO) }
            Thread.sleep(10)
        }
    }
    private fun checkCurrent() { if (!isCurrent()) throw TransferFailure(TransferFailure.Reason.STALE) }
    private fun nativeError(code: Int): Nothing = throw TransferFailure(when (code) {
        -1, -4 -> TransferFailure.Reason.STALE
        -2 -> TransferFailure.Reason.UNSUPPORTED
        -3 -> TransferFailure.Reason.BUSY
        -5 -> TransferFailure.Reason.FILES_ONLY
        -7 -> TransferFailure.Reason.CANCELLED
        -9 -> TransferFailure.Reason.SEND_BUDGET
        else -> TransferFailure.Reason.IO
    })
}
