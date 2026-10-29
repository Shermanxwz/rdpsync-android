package com.rdp.sync.security

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStorageException(message: String = "本机凭据无法解密，请编辑设备重新输入密码；原有加密数据已保留。", cause: Throwable? = null) : IllegalStateException(message, cause)

interface CredentialCipher {
    fun encrypt(plaintext: String, context: String): String
    fun decrypt(envelope: String, context: String): String
}

/** Versioned AES-GCM envelope. The database format column, NOT a text prefix, identifies encryption. */
class AesGcmCredentialCipher(private val key: (create: Boolean) -> SecretKey) : CredentialCipher {
    override fun encrypt(plaintext: String, context: String): String = guarded {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(context.toByteArray(UTF_8))
        check(cipher.iv.size == 12)
        Base64.getEncoder().encodeToString(byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext.toByteArray(UTF_8)))
    }

    override fun decrypt(envelope: String, context: String): String = guarded {
        val bytes = Base64.getDecoder().decode(envelope)
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Never generate a replacement key while reading existing ciphertext.
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(context.toByteArray(UTF_8))
        String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), UTF_8)
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        throw CredentialStorageException(cause = e)
    }
}
