package com.rdp.sync.network

import org.junit.Assert.*
import org.junit.Test

class SharedDriveStateTest {
    private fun value(status: Int = 1, pending: Long = -1) = longArrayOf(1, 42, 1, status.toLong(), 7, 0,
        10, 20, 2, 128, pending, 3, 4096)

    @Test fun capsAreNotAcceptedAndSentinelsAreNotDrained() {
        for (status in listOf(1, 2)) {
            val state = SharedDriveState.fromNative(value(status))
            assertFalse(state.accepted)
            assertEquals(-1, state.transportPending)
            assertTrue(state.mayRevoke)
        }
        val unavailable = SharedDriveState.fromNative(value(-4, -2))
        assertEquals(SharedDriveStatus.TRANSPORT_UNAVAILABLE, unavailable.status)
        assertEquals(-2, unavailable.transportPending)
        assertFalse(unavailable.accepted)
    }
    @Test fun acceptedAndUsedRequireNativeAcknowledgement() {
        assertTrue(SharedDriveState.fromNative(value(3, 0)).accepted)
        assertTrue(SharedDriveState.fromNative(value(4, 262144)).accepted)
        val revoked = SharedDriveState.fromNative(value(-3, -1))
        assertFalse(revoked.accepted)
        assertFalse(revoked.mayRevoke)
    }
    @Test fun contradictoryAcknowledgementsCannotRenderAccepted() {
        for (status in listOf(3, 4)) {
            for (invalid in listOf(value(status).also { it[4] = 0 }, value(status).also { it[5] = 0xc0000022L })) {
                val state = SharedDriveState.fromNative(invalid)
                assertEquals(SharedDriveStatus.UNKNOWN, state.status)
                assertFalse(state.accepted)
            }
        }
    }
    @Test fun malformedAndFutureSchemasFailClosed() {
        val invalid = listOf(longArrayOf(), value().copyOf(12), value().copyOf(14),
            value().also { it[0] = 2 }, value().also { it[2] = 9 }, value(99),
            value().also { it[1] = 0 }, value().also { it[4] = -1 },
            value().also { it[5] = 0x100000000L }, value().also { it[6] = -1 },
            value().also { it[8] = 33 }, value().also { it[9] = 4L * 1024 * 1024 + 1 },
            value(pending = -3), value().also { it[11] = -1 }, value().also { it[12] = -1 })
        invalid.forEach { data ->
            val state = SharedDriveState.fromNative(data)
            assertEquals(SharedDriveStatus.UNKNOWN, state.status)
            assertFalse(state.accepted)
            assertFalse(state.mayRevoke)
        }
    }
    @Test fun automaticCompatibilityRetryCannotResurrectRevokedGrant() {
        assertTrue(permitsAutomaticActivationRetry(SharedDriveMode.OFF))
        for (capturedAttemptMode in listOf(SharedDriveMode.READ_ONLY, SharedDriveMode.READ_WRITE)) {
            // A later UI stop sets the next explicit attempt to OFF; it must not
            // authorize reusing this captured grant in an automatic generation.
            assertFalse(permitsAutomaticActivationRetry(capturedAttemptMode))
            assertTrue(permitsAutomaticActivationRetry(SharedDriveMode.OFF))
        }
    }
    @Test fun offAndUnsignedServerRejectionArePreserved() {
        val off = SharedDriveState.fromNative(longArrayOf(1, 42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(SharedDriveStatus.OFF, off.status)
        assertFalse(off.mayRevoke)
        val denied = SharedDriveState.fromNative(value(-2).also { it[5] = 0xc0000022L })
        assertEquals(0xc0000022L, denied.replyStatus)
        assertEquals(SharedDriveStatus.REJECTED, denied.status)
    }
}
