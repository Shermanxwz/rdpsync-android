package com.rdp.sync.network

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SharedDriveAttemptGrantTest {
    @Test fun stopBeforeFirstConnectNeverIssuesDrive() {
        val grant = SharedDriveAttemptGrant(SharedDriveMode.READ_WRITE)
        grant.requestRevocation()
        assertTrue(grant.revoke { error("No drive generation exists") })
        grant.connect { mode -> assertEquals(SharedDriveMode.OFF, mode); true to 42L }
        assertTrue(grant.revoke { error("An OFF generation requires no drive revoke") })
    }
    @Test fun stopDuringNativePublicationRevokesExactlyThePublishedGeneration() {
        val grant = SharedDriveAttemptGrant(SharedDriveMode.READ_WRITE)
        val entered = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val revoked = AtomicLong(0)
        val failure = AtomicReference<Throwable?>()
        val connect = thread {
            try { grant.connect { mode ->
                assertEquals(SharedDriveMode.READ_WRITE, mode)
                entered.countDown(); check(publish.await(3, TimeUnit.SECONDS))
                true to 73L
            } } catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        // This UI operation must not wait for the IO monitor held by connect.
        grant.requestRevocation()
        val stop = thread {
            try { stopStarted.countDown(); grant.revoke { generation -> revoked.set(generation); true } }
            catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(stopStarted.await(3, TimeUnit.SECONDS))
        assertEquals(0L, revoked.get())
        publish.countDown()
        connect.join(3000); stop.join(3000)
        assertFalse(connect.isAlive); assertFalse(stop.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(73L, revoked.get())
        grant.connect { mode -> assertEquals(SharedDriveMode.OFF, mode); true to 74L }
    }
    @Test fun staleGenerationFailureIsNotReportedAsStopped() {
        val grant = SharedDriveAttemptGrant(SharedDriveMode.READ_ONLY)
        grant.connect { false to 81L } // Rejected TLS may retain a challenge generation.
        assertFalse(grant.revoke { generation -> assertEquals(81L, generation); false })
        grant.connect { mode -> assertEquals(SharedDriveMode.OFF, mode); false to 82L }
    }
    @Test fun ambiguousPublicationDoesNotClaimSuccessfulRevocation() {
        val grant = SharedDriveAttemptGrant(SharedDriveMode.READ_WRITE)
        try { grant.connect { throw IllegalStateException("synthetic JNI publication failure") }; fail() }
        catch (_: IllegalStateException) { }
        assertFalse(grant.revoke { error("No known generation") })
        grant.connect { mode -> assertEquals(SharedDriveMode.OFF, mode); false to 90L }
    }
    @Test fun revokeFailureReleasesMonitorWithoutReauthorizing() {
        val grant = SharedDriveAttemptGrant(SharedDriveMode.READ_WRITE)
        grant.connect { true to 91L }
        try { grant.revoke { throw IllegalStateException("synthetic revoke failure") }; fail() }
        catch (_: IllegalStateException) { }
        grant.connect { mode -> assertEquals(SharedDriveMode.OFF, mode); true to 92L }
        assertTrue(grant.revoke { error("New generation did not receive a grant") })
    }
    @Test fun offAndFailedAllocationDoNotRevokeAnUnrelatedSession() {
        val off = SharedDriveAttemptGrant(SharedDriveMode.OFF)
        off.connect { true to 100L }
        assertTrue(off.revoke { error("OFF is not a drive grant") })
        val empty = SharedDriveAttemptGrant(SharedDriveMode.READ_ONLY)
        empty.connect { false to 0L }
        assertFalse(empty.revoke { error("No known generation must never target another session") })
    }
}
