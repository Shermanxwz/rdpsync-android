package com.rdp.sync.security

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CertificateTrustStoreTest {
    private val first = "ab".repeat(32)
    private val second = "cd".repeat(32)
    private fun endpoint(id: Long = 7, host: String = "example.com", port: Int = 3389, server: String = "") = CertificateEndpoint.of(id, host, port, server)!!
    private fun challenge(endpoint: CertificateEndpoint = endpoint(), pin: String = first, generation: Long = 1, reason: String = "untrusted") =
        CertificateChallengeSnapshot(generation, pin, "CN=example.com", "CN=example.com", endpoint.host, endpoint.port, endpoint.effectiveServerName, reason)
    private class MemoryStorage : CertificateTrustStore.Storage {
        override val namespace = UUID.randomUUID().toString()
        val values = mutableMapOf<String, String>()
        var durable = true
        var writes = 0
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String): Boolean { writes++; values[key] = value; return durable }
    }

    @Test fun firstContactNeedsExplicitExactApprovalThenSamePinCanBeRead() {
        val storage = MemoryStorage(); val store = CertificateTrustStore(storage); val endpoint = endpoint(); val live = challenge()
        assertNull(store.fingerprint(endpoint))
        val pending = CertificateTrustPolicy.propose(endpoint, "attempt-A", live, null)!!
        assertFalse(pending.changed)
        assertEquals(0, storage.writes)
        assertTrue(store.approve(pending, endpoint, "attempt-A", live))
        assertEquals(first, store.fingerprint(endpoint))
        assertEquals(first, CertificateTrustStore(storage).fingerprint(endpoint))
    }
    @Test fun changedCertificateDoesNotReplaceTrustedPinUntilFreshApproval() {
        val storage = MemoryStorage(); val store = CertificateTrustStore(storage); val endpoint = endpoint()
        val initial = challenge(); val firstPending = CertificateTrustPolicy.propose(endpoint, "A", initial, null)!!
        assertTrue(store.approve(firstPending, endpoint, "A", initial))
        val changed = challenge(pin = second, generation = 2, reason = "changed")
        val pending = CertificateTrustPolicy.propose(endpoint, "B", changed, store.fingerprint(endpoint))!!
        assertTrue(pending.changed); assertEquals(first, pending.previousFingerprint)
        assertEquals(first, store.fingerprint(endpoint))
        assertTrue(store.approve(pending, endpoint, "B", changed))
        assertEquals(second, store.fingerprint(endpoint))
    }
    @Test fun staleGenerationAttemptProfileOrAuthorityCannotApprove() {
        val storage = MemoryStorage(); val store = CertificateTrustStore(storage); val endpoint = endpoint(); val live = challenge()
        val pending = CertificateTrustPolicy.propose(endpoint, "A", live, null)!!
        assertFalse(store.approve(pending, endpoint, "B", live))
        assertFalse(store.approve(pending, endpoint, "A", live.copy(generation = 2)))
        assertFalse(store.approve(pending, endpoint(8), "A", live))
        assertFalse(store.approve(pending, endpoint(host = "other.example"), "A", live))
        assertFalse(store.approve(pending, endpoint(port = 3390), "A", live))
        assertFalse(store.approve(pending, endpoint(server = "different-authority.example"), "A", live))
        assertFalse(store.approve(pending, endpoint, "A", live.copy(fingerprint = second)))
        assertFalse(store.approve(pending, endpoint, "A", null))
        assertEquals(0, storage.writes)
    }
    @Test fun scopeCanonicalizesDnsCaseTrailingDotAndIdnButNeverDropsServerAuthority() {
        assertEquals(endpoint(), endpoint(host = " EXAMPLE.com. "))
        assertEquals(endpoint(host = "xn--bcher-kva.example"), endpoint(host = "bücher.example"))
        assertEquals(endpoint(host = "[2001:DB8::1]"), endpoint(host = "2001:db8::1"))
        assertEquals(endpoint(server = "SERVER.example."), endpoint(server = "server.EXAMPLE"))
        assertNotEquals(endpoint(), endpoint(server = "server.example"))
        assertNotEquals(endpoint(), endpoint(id = 8))
        assertNull(CertificateEndpoint.of(7, "https://example.com", 3389, ""))
        assertNull(CertificateEndpoint.of(7, "example.com", 0, ""))
    }
    @Test fun challengeForOtherEndpointOrInvalidFingerprintNeverProducesDialogApproval() {
        assertNull(CertificateTrustPolicy.propose(endpoint(), "A", challenge().copy(host = "attacker.example"), null))
        assertNull(CertificateTrustPolicy.propose(endpoint(), "A", challenge().copy(effectiveServerName = "other.example"), null))
        assertNull(CertificateTrustPolicy.propose(endpoint(), "A", challenge().copy(fingerprint = "ab:cd"), null))
        assertNull(CertificateTrustPolicy.propose(endpoint(), "A", challenge().copy(reason = "invalid-pin"), null))
        assertNull(CertificateTrustPolicy.propose(endpoint(), "A", challenge().copy(generation = 0), null))
    }
    @Test fun failedDurableWriteIsNotTrustedEvenIfStorageChangedItsMemoryCache() {
        val storage = MemoryStorage().apply { durable = false }; val store = CertificateTrustStore(storage); val endpoint = endpoint(); val live = challenge()
        val pending = CertificateTrustPolicy.propose(endpoint, "A", live, null)!!
        assertFalse(store.approve(pending, endpoint, "A", live))
        assertNull(store.fingerprint(endpoint))
        assertNull(CertificateTrustStore(storage).fingerprint(endpoint))
        storage.durable = true
        assertTrue(store.approve(pending, endpoint, "A", live))
        assertEquals(first, store.fingerprint(endpoint))
    }
    @Test fun concurrentPinReplacementInvalidatesOldDialog() {
        val storage = MemoryStorage(); val store = CertificateTrustStore(storage); val endpoint = endpoint(); val live = challenge()
        val stale = CertificateTrustPolicy.propose(endpoint, "A", live, null)!!
        val newer = challenge(pin = second, generation = 2)
        assertTrue(store.approve(CertificateTrustPolicy.propose(endpoint, "B", newer, null)!!, endpoint, "B", newer))
        assertFalse(store.approve(stale, endpoint, "A", live))
        assertEquals(second, store.fingerprint(endpoint))
    }
    @Test fun corruptRecordAndDifferentProfileCannotInheritPin() {
        val storage = MemoryStorage(); val store = CertificateTrustStore(storage); val original = endpoint(); val live = challenge()
        assertTrue(store.approve(CertificateTrustPolicy.propose(original, "A", live, null)!!, original, "A", live))
        assertNull(store.fingerprint(endpoint(id = 8)))
        val other = endpoint(host = "other.example")
        storage.values[other.storageKey] = storage.values[original.storageKey]!!
        assertNull(store.fingerprint(other))
    }
    @Test fun dismissedOrDisposedAttemptNeverExecutesTrustWrite() {
        val guard = CertificateAttemptGuard()
        guard.invalidate()
        var writes = 0
        assertFalse(guard.approveIfActive { writes++; true })
        assertEquals(0, writes)
    }
}
