package com.rdp.sync.transfer

import org.junit.Assert.*
import org.junit.Test

class TransferNativeFailureTest {
    @Test fun unsupportedSnapshotLockRequiresDeliberateReconnectNotBusyRetry() {
        val failure = nativeTransferFailure(-10)
        assertEquals(TransferFailure.Reason.RECONNECT_REQUIRED, failure.reason)
        assertTrue(failure.message!!.contains("手动重新连接"))
        assertTrue(failure.message!!.contains("不会自动重连"))
        assertNotEquals(nativeTransferFailure(-3).reason, failure.reason)
    }

    @Test fun quotaAndTemporaryBackpressureRemainSeparate() {
        assertEquals(TransferFailure.Reason.SEND_BUDGET, nativeTransferFailure(-9).reason)
        assertEquals(TransferFailure.Reason.BUSY, nativeTransferFailure(-3).reason)
        assertEquals(TransferFailure.Reason.IO, nativeTransferFailure(-99).reason)
    }
}
