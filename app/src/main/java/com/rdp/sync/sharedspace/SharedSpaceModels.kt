package com.rdp.sync.sharedspace

import com.rdp.sync.network.RdpConnector
import com.rdp.sync.transfer.TransferCancellation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

const val SHARED_SPACE_MAX_FILE_BYTES = 512L * 1024 * 1024
const val SHARED_SPACE_QUOTA_BYTES = 1024L * 1024 * 1024
const val SHARED_SPACE_RESERVE_BYTES = 128L * 1024 * 1024

/** Constructors are also the native broker's bounded JNI result contract. */
data class SharedSpaceEntry(val name: String, val directory: Boolean, val size: Long,
                            val modifiedMillis: Long, val readOnly: Boolean)
data class SharedSpaceListingResult(val status: Int, val entries: Array<SharedSpaceEntry>)
data class SharedSpaceReadResult(val status: Int, val data: ByteArray)
data class SharedSpaceItem(val name: String, val relativePath: String, val directory: Boolean,
                           val size: Long, val modifiedMillis: Long, val readOnly: Boolean = false)
data class SharedSpaceListing(val path: String, val items: List<SharedSpaceItem>,
                              val usedBytes: Long, val availableBytes: Long,
                              val quotaBytes: Long = SHARED_SPACE_QUOTA_BYTES)
data class SharedSpaceImportResult(val imported: List<SharedSpaceItem>, val failure: SharedSpaceFailure? = null)

/** Error text never includes provider identifiers, phone paths, or native exceptions. */
class SharedSpaceFailure(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason { BUSY, INVALID_NAME, COLLISION, LIMIT, NO_SPACE, CANCELLED, IO,
        UNSUPPORTED, PERMISSION, NOT_FOUND, DIRECTORY_NOT_EMPTY, UNSAFE_DESTINATION, EXPORT_INCOMPLETE }
}

class SharedSpaceCancellation {
    internal val transfer = TransferCancellation()
    internal val token = nextToken.getAndIncrement().also { check(it > 0) }
    private val cancelled = AtomicBoolean(false)
    fun isCancelled(): Boolean = cancelled.get()
    /** UI-safe: only flags; native cancellation must remain an atomic, nonblocking signal. */
    fun requestCancel() {
        cancelled.set(true)
        transfer.requestCancel()
        runCatching { RdpConnector.nativeSharedSpaceCancelImport(token) }
    }
    /** Call on an IO thread: provider cancellation/FD closure can cross Binder. */
    fun cancel() { requestCancel(); transfer.cancel() }
    fun check() { if (isCancelled() || Thread.currentThread().isInterrupted) throw SharedSpaceFailure(SharedSpaceFailure.Reason.CANCELLED) }
    private companion object { val nextToken = AtomicLong(1) }
}

internal fun requireSharedSpaceStatus(status: Int) {
    if (status == 0) return
    val reason = when (status.toLong() and 0xffffffffL) {
        0xC0000043L, 0xC0000056L, 0x0000010CL -> SharedSpaceFailure.Reason.BUSY
        0xC0000033L, 0xC000000DL -> SharedSpaceFailure.Reason.INVALID_NAME
        0xC0000035L -> SharedSpaceFailure.Reason.COLLISION
        0xC000009AL, 0xC0000904L -> SharedSpaceFailure.Reason.LIMIT
        0xC000007FL -> SharedSpaceFailure.Reason.NO_SPACE
        0xC0000120L -> SharedSpaceFailure.Reason.CANCELLED
        0xC0000002L, 0xC00000BBL -> SharedSpaceFailure.Reason.UNSUPPORTED
        0xC0000022L -> SharedSpaceFailure.Reason.PERMISSION
        0xC0000034L, 0xC000003AL, 0xC000000FL -> SharedSpaceFailure.Reason.NOT_FOUND
        0xC0000101L -> SharedSpaceFailure.Reason.DIRECTORY_NOT_EMPTY
        else -> SharedSpaceFailure.Reason.IO
    }
    throw SharedSpaceFailure(reason)
}

/** A broker selection pins the chosen object across pickers/confirmations.
 * Close on an IO thread on every cancel, disposal, stale result and completion.
 * No path is reopened when a selected operation is eventually performed. */
sealed class SharedSpaceSelection protected constructor(val relativePath: String, internal val handle: Long) : java.io.Closeable {
    private val lifetime = SharedSpaceHandleLifetime { RdpConnector.nativeSharedSpaceCloseExport(handle) }
    internal fun requireOpen(): Long { lifetime.checkOpen(); return handle }
    internal fun closeChecked() = lifetime.closeChecked()
    override fun close() = lifetime.close()
}
class SharedSpaceExportSelection internal constructor(path: String, handle: Long, val size: Long) : SharedSpaceSelection(path, handle)
class SharedSpaceImportSelection internal constructor(path: String, handle: Long) : SharedSpaceSelection(path, handle)
class SharedSpaceMutationSelection internal constructor(path: String, handle: Long) : SharedSpaceSelection(path, handle)
