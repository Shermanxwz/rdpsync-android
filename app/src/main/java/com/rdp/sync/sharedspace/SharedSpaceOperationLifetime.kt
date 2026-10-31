package com.rdp.sync.sharedspace

import java.util.concurrent.atomic.AtomicBoolean

/** Keeps cleanup idempotent while allowing explicit local deletion to report
 * the actual deferred unlink result, not just its accepted disposition. */
internal class SharedSpaceHandleLifetime(private val closeHandle: () -> Int) {
    private val closed = AtomicBoolean(false)
    fun checkOpen() {
        if (closed.get()) throw SharedSpaceFailure(SharedSpaceFailure.Reason.CANCELLED)
    }
    fun close() { if (closed.compareAndSet(false, true)) closeHandle() }
    fun closeChecked() {
        if (!closed.compareAndSet(false, true)) throw SharedSpaceFailure(SharedSpaceFailure.Reason.CANCELLED)
        // Terminal even if unlink failed: the native handle has been consumed.
        requireSharedSpaceStatus(closeHandle())
    }
}

/** Register before the final cancellation check: cancellation in the handoff
 * between Java and native cannot be lost. Cleanup occurs for the entire batch,
 * including SAF staging before the first native file copy. */
internal inline fun <T> registeredSharedSpaceImport(
    begin: () -> Int, checkCancelled: () -> Unit, end: () -> Int, block: () -> T
): T {
    requireSharedSpaceStatus(begin())
    var primary: Throwable? = null
    try {
        checkCancelled()
        return block()
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try { requireSharedSpaceStatus(end()) }
        catch (cleanup: Throwable) {
            if (primary == null) throw cleanup
            primary.addSuppressed(cleanup)
        }
    }
}
