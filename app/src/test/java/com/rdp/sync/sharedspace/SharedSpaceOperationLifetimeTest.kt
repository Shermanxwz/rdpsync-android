package com.rdp.sync.sharedspace

import org.junit.Assert.*
import org.junit.Test

class SharedSpaceOperationLifetimeTest {
    @Test fun failedDeferredDeleteIsNotSuccessAndCleanupDoesNotReuseConsumedHandle() {
        var closes = 0
        val lifetime = SharedSpaceHandleLifetime { closes++; 0xC0000001.toInt() }
        lifetime.checkOpen()
        val failure = runCatching { lifetime.closeChecked() }.exceptionOrNull() as SharedSpaceFailure
        assertEquals(SharedSpaceFailure.Reason.IO, failure.reason)
        assertEquals(SharedSpaceFailure.Reason.CANCELLED,
            (runCatching { lifetime.checkOpen() }.exceptionOrNull() as SharedSpaceFailure).reason)
        lifetime.close(); lifetime.close()
        assertEquals(1, closes)
    }
    @Test fun successfulCheckedCloseIsTerminalAndIdempotentCleanup() {
        var closes = 0
        val lifetime = SharedSpaceHandleLifetime { closes++; 0 }
        lifetime.closeChecked(); lifetime.close()
        assertEquals(1, closes)
        assertTrue(runCatching { lifetime.closeChecked() }.exceptionOrNull() is SharedSpaceFailure)
    }
    @Test fun cancellationBetweenPrecheckAndNativeRegistrationIsCheckedAgainBeforeCopy() {
        var cancelled = false; var copied = false; var ended = 0
        val failure = runCatching {
            registeredSharedSpaceImport(
                begin = { cancelled = true; 0 },
                checkCancelled = { if (cancelled) throw SharedSpaceFailure(SharedSpaceFailure.Reason.CANCELLED) },
                end = { ended++; 0 }
            ) { copied = true }
        }.exceptionOrNull() as SharedSpaceFailure
        assertEquals(SharedSpaceFailure.Reason.CANCELLED, failure.reason)
        assertFalse(copied); assertEquals(1, ended)
    }
    @Test fun failedRegistrationDoesNotEndAnotherBatch() {
        var ended = false; var copied = false
        val failure = runCatching {
            registeredSharedSpaceImport({ 0xC0000043.toInt() }, {}, { ended = true; 0 }) { copied = true }
        }.exceptionOrNull() as SharedSpaceFailure
        assertEquals(SharedSpaceFailure.Reason.BUSY, failure.reason)
        assertFalse(ended); assertFalse(copied)
    }
    @Test fun registrationEndsAfterSuccessAndCopyFailure() {
        var ended = 0
        assertEquals("done", registeredSharedSpaceImport({ 0 }, {}, { ended++; 0 }) { "done" })
        val original = SharedSpaceFailure(SharedSpaceFailure.Reason.NO_SPACE)
        assertSame(original, runCatching {
            registeredSharedSpaceImport({ 0 }, {}, { ended++; 0 }) { throw original }
        }.exceptionOrNull())
        assertEquals(2, ended)
    }
    @Test fun cleanupFailurePreservesPrimaryButCannotTurnSuccessIntoSuccess() {
        val primary = SharedSpaceFailure(SharedSpaceFailure.Reason.CANCELLED)
        val actual = runCatching {
            registeredSharedSpaceImport({ 0 }, {}, { 0xC0000001.toInt() }) { throw primary }
        }.exceptionOrNull()
        assertSame(primary, actual)
        assertEquals(1, primary.suppressed.size)
        val cleanupOnly = runCatching {
            registeredSharedSpaceImport({ 0 }, {}, { 0xC0000001.toInt() }) { "published" }
        }.exceptionOrNull() as SharedSpaceFailure
        assertEquals(SharedSpaceFailure.Reason.IO, cleanupOnly.reason)
    }
}
