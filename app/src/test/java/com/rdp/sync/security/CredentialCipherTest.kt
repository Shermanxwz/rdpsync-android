package com.rdp.sync.security

import com.rdp.sync.data.Device
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class CredentialCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun roundTripUnicodeEmptyAndFreshIv() {
        val key = key(); val cipher = AesGcmCredentialCipher { key }
        listOf("", "synthetic 密码 🗝️").forEach {
            val a = cipher.encrypt(it, "device-a"); val b = cipher.encrypt(it, "device-a")
            assertNotEquals(a, b); assertEquals(it, cipher.decrypt(a, "device-a"))
            assertFalse(a.contains("synthetic"))
        }
    }
    @Test fun tamperingWrongContextWrongKeyAndVersionFailClosed() {
        val key = key(); val cipher = AesGcmCredentialCipher { key }
        val value = cipher.encrypt("synthetic", "a")
        val bytes = java.util.Base64.getDecoder().decode(value)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        expectFailure { cipher.decrypt(java.util.Base64.getEncoder().encodeToString(bytes), "a") }
        expectFailure { cipher.decrypt(value, "b") }
        expectFailure { AesGcmCredentialCipher { key() }.decrypt(value, "a") }
        bytes[0] = 2; expectFailure { cipher.decrypt(java.util.Base64.getEncoder().encodeToString(bytes), "a") }
    }
    @Test fun missingKeyReadNeverRequestsKeyCreation() {
        val cipher = AesGcmCredentialCipher { create -> assertFalse(create); throw IllegalStateException("missing") }
        val encrypted = AesGcmCredentialCipher { key() }.encrypt("fixture", "a")
        expectFailure { cipher.decrypt(encrypted, "a") }
    }
    @Test fun identitySeparatesAccountsAndServerNamesAndAvoidsDelimiterCollisions() {
        val a = Device(name="x", host=" HOST ", username="Alice", password="", rdpServerName="SERVER")
        assertEquals(a.stableIdentity(), a.copy(name="renamed", host="host", rdpServerName="server").stableIdentity())
        assertNotEquals(a.stableIdentity(), a.copy(username="alice").stableIdentity())
        assertNotEquals(a.stableIdentity(), a.copy(domain="other").stableIdentity())
        assertNotEquals(a.stableIdentity(), a.copy(rdpServerName="other").stableIdentity())
        assertNotEquals(a.copy(username="a:b", domain="c").stableIdentity(), a.copy(username="a", domain="b:c").stableIdentity())
    }
    @Test fun availabilityIsNotConflatedWithValidEmptyPassword() {
        val valid = Device(name="x", host="host", username="u", password="")
        val locked = valid.copy().apply { credentialUnavailable = true; passwordProvided = false }
        assertNotEquals(valid, locked)
        assertTrue(locked.copy(name="renamed").credentialUnavailable)
        assertFalse(locked.copy(name="renamed").passwordProvided)
    }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("must fail closed") } catch (_: CredentialStorageException) { }
    }
}
