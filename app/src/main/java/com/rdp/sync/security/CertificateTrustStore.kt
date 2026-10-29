package com.rdp.sync.security

import java.net.IDN
import java.security.MessageDigest
import java.util.Locale

/** A trust decision belongs to a saved profile AND its actual connection authority. */
data class CertificateEndpoint private constructor(
    val profileId: Long,
    val host: String,
    val port: Int,
    val effectiveServerName: String
) {
    internal val recordIdentity: String
        get() = "v1|$profileId|${host.length}:$host|$port|${effectiveServerName.length}:$effectiveServerName"
    internal val storageKey: String
        get() = "pin_" + MessageDigest.getInstance("SHA-256").digest(recordIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun of(profileId: Long, host: String, port: Int, serverName: String): CertificateEndpoint? {
            if (profileId < 0 || port !in 1..65535) return null
            val canonicalHost = canonicalHost(host) ?: return null
            val authority = if (serverName.isBlank()) canonicalHost else canonicalHost(serverName) ?: return null
            return CertificateEndpoint(profileId, canonicalHost, port, authority)
        }

        /** Pure parsing only: never resolve DNS while creating a trust-store key. */
        internal fun canonicalHost(value: String): String? {
            var host = value.trim()
            if (host.isEmpty() || host.any { it.isWhitespace() || it.code < 0x20 || it in "/\\@?#" }) return null
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
            if (host.contains('[') || host.contains(']')) return null
            if (host.contains(':')) {
                // Conservatively retain IPv6 spelling and zone identity; equivalent
                // alternative spellings may ask again, never inherit another host's pin.
                val parts = host.split('%', limit = 2)
                if (!parts[0].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) return null
                if (parts.size == 2 && (parts[1].isEmpty() || !parts[1].all { it.isLetterOrDigit() || it in "_-" })) return null
                return parts[0].lowercase(Locale.ROOT) + if (parts.size == 2) "%${parts[1]}" else ""
            }
            host = host.removeSuffix(".")
            return runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
                .getOrNull()?.takeIf { it.isNotEmpty() && it.length <= 253 }
        }
    }
}

data class CertificateChallengeSnapshot(
    val generation: Long,
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val host: String,
    val port: Int,
    val effectiveServerName: String,
    val reason: String
)

data class PendingCertificateTrust internal constructor(
    val endpoint: CertificateEndpoint,
    val attemptNonce: String,
    val challenge: CertificateChallengeSnapshot,
    val previousFingerprint: String?
) {
    val changed: Boolean get() = challenge.reason == "changed" || previousFingerprint?.let { it != challenge.fingerprint } == true
}

object CertificateTrustPolicy {
    fun normalizedFingerprint(value: String?): String? = value?.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char.lowercaseChar() in 'a'..'f' } }?.lowercase(Locale.ROOT)

    fun propose(endpoint: CertificateEndpoint, attemptNonce: String, challenge: CertificateChallengeSnapshot, previousFingerprint: String?): PendingCertificateTrust? {
        if (attemptNonce.isBlank() || challenge.generation <= 0 || challenge.reason !in setOf("untrusted", "changed")) return null
        val fingerprint = normalizedFingerprint(challenge.fingerprint) ?: return null
        val observed = CertificateEndpoint.of(endpoint.profileId, challenge.host, challenge.port, challenge.effectiveServerName) ?: return null
        if (observed != endpoint) return null
        val previous = if (previousFingerprint == null) null else normalizedFingerprint(previousFingerprint) ?: return null
        return PendingCertificateTrust(endpoint, attemptNonce, challenge.copy(fingerprint = fingerprint), previous)
    }

    fun canApprove(pending: PendingCertificateTrust, endpoint: CertificateEndpoint, attemptNonce: String, liveChallenge: CertificateChallengeSnapshot?, currentPin: String?): Boolean {
        if (liveChallenge == null || pending.endpoint != endpoint || pending.attemptNonce != attemptNonce) return false
        return propose(endpoint, attemptNonce, liveChallenge, currentPin) == pending
    }
}

/** Pins are not secrets. The full canonical scope is also stored to reject corrupt/misbound records. */
class CertificateTrustStore(private val storage: Storage) {
    interface Storage {
        /** Stable identity of the backing store, shared by every wrapper for it. */
        val namespace: String
        fun read(key: String): String?
        /** Must return true only after durable commit, never merely after scheduling apply(). */
        fun write(key: String, value: String): Boolean
    }

    fun fingerprint(endpoint: CertificateEndpoint): String? = synchronized(storeLock) {
        val key = endpoint.storageKey
        if ((storage.namespace to key) in failedWrites) return@synchronized null
        val record = runCatching { storage.read(key) }.getOrNull() ?: return@synchronized null
        val prefix = endpoint.recordIdentity + "\n"
        if (!record.startsWith(prefix)) return@synchronized null
        CertificateTrustPolicy.normalizedFingerprint(record.substring(prefix.length))
    }

    /** Compare the exact displayed evidence AND old pin again immediately before durable write. */
    fun approve(pending: PendingCertificateTrust, endpoint: CertificateEndpoint, attemptNonce: String, liveChallenge: CertificateChallengeSnapshot?): Boolean = synchronized(storeLock) {
        if (!CertificateTrustPolicy.canApprove(pending, endpoint, attemptNonce, liveChallenge, fingerprint(endpoint))) return@synchronized false
        val key = endpoint.storageKey
        val saved = runCatching { storage.write(key, endpoint.recordIdentity + "\n" + pending.challenge.fingerprint) }.getOrDefault(false)
        // SharedPreferences.commit(false) may still mutate its memory cache. Never
        // treat that undurable value as a trusted pin within this store instance.
        if (saved) failedWrites.remove(storage.namespace to key) else failedWrites.add(storage.namespace to key)
        saved
    }

    companion object { private val storeLock = Any(); private val failedWrites = mutableSetOf<Pair<String, String>>() }
}

/** Linearizes dismissal/disposal with the explicit trust write; never waits for network. */
class CertificateAttemptGuard {
    private var active = true
    @Synchronized fun invalidate() { active = false }
    @Synchronized fun isActive(): Boolean = active
    @Synchronized fun approveIfActive(action: () -> Boolean): Boolean = active && action()
}
