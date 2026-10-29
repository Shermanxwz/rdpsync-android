package com.rdp.sync.network

import org.junit.Assert.*
import org.junit.Test

class CertificateChallengeTest {
    private fun tuple() = arrayOf("7", "A".repeat(64), "CN=fixture", "CN=fixture", "example.com", "3389", "example.com", "untrusted")
    @Test fun validChallengeRetainsEndpointAndGeneration() {
        val parsed = requireNotNull(CertificateChallenge.fromNative(tuple()))
        assertEquals(7L, parsed.generation)
        assertEquals("a".repeat(64), parsed.fingerprint)
        assertEquals("example.com", parsed.host)
    }
    @Test fun malformedChallengeIsNeverApprovable() {
        assertNull(CertificateChallenge.fromNative(null))
        assertNull(CertificateChallenge.fromNative(arrayOf("7")))
        for ((index, value) in listOf(0 to "0", 0 to "NaN", 1 to "ab", 4 to "", 5 to "65536", 6 to "", 7 to "accept-any")) {
            assertNull("field $index", CertificateChallenge.fromNative(tuple().apply { this[index] = value }))
        }
    }
}
