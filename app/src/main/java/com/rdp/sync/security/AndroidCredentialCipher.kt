package com.rdp.sync.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Device-bound key. Never exported, backed up, deleted, or silently rotated by the app. */
class AndroidCredentialCipher(alias: String = "rdpsync.credentials.v1") : CredentialCipher by AesGcmCredentialCipher({ create ->
    synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing != null) existing as SecretKey
        else {
            if (!create) throw CredentialStorageException()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
        }
    }
}) {
    companion object { private val keyLock = Any() }
}
