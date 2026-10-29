package com.rdp.sync.network

/** An untrusted certificate rejected BEFORE authentication; never an approval token by itself. */
data class CertificateChallenge(
    val generation: Long,
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val host: String,
    val port: Int,
    val effectiveServerName: String,
    val reason: String
) {
    companion object {
        fun fromNative(values: Array<String>?): CertificateChallenge? {
            if (values == null || values.size != 8) return null
            val generation = values[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val fingerprint = values[1].lowercase(java.util.Locale.ROOT)
            if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) return null
            val port = values[5].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (values[4].isBlank() || values[6].isBlank()) return null
            if (values[7] !in setOf("untrusted", "changed", "invalid-pin")) return null
            return CertificateChallenge(generation, fingerprint, values[2], values[3], values[4], port, values[6], values[7])
        }
    }
}
