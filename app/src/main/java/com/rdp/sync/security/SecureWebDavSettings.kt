package com.rdp.sync.security

import android.content.SharedPreferences
import java.util.WeakHashMap

data class WebDavSettings(val url: String, val username: String, val password: String, val unavailable: Boolean = false)

class SecureWebDavSettings(private val prefs: SharedPreferences, private val cipher: CredentialCipher = AndroidCredentialCipher()) {
    fun load(): WebDavSettings = synchronized(lock) { loadLocked() }

    private fun loadLocked(): WebDavSettings {
        val url = prefs.getString("webdav_url", "").orEmpty()
        val username = prefs.getString("webdav_username", "").orEmpty()
        return try {
            failedWrites[prefs]?.let { pending ->
                // commit(false) may already mutate SharedPreferences' process cache. Never trust that cache
                // as durable success, even from a newly-created settings wrapper.
                if (!pending.migration || url != pending.url || username != pending.username) throw CredentialStorageException()
                cipher.decrypt(pending.encrypted, context(url, username))
                if (!write(url, username, pending.encrypted)) throw CredentialStorageException()
                failedWrites.remove(prefs)
            }
            val password = if (prefs.contains(ENCRYPTED) || prefs.contains(FORMAT)) {
                require(prefs.contains(ENCRYPTED) && prefs.getInt(FORMAT, 0) == 1)
                cipher.decrypt(prefs.getString(ENCRYPTED, "").orEmpty(), context(url, username))
            } else if (prefs.contains(LEGACY)) {
                val legacy = prefs.getString(LEGACY, "").orEmpty()
                val encrypted = cipher.encrypt(legacy, context(url, username))
                if (!write(url, username, encrypted)) {
                    failedWrites[prefs] = PendingWrite(url, username, encrypted, migration = true)
                    throw CredentialStorageException()
                }
                legacy
            } else ""
            WebDavSettings(url, username, password)
        } catch (_: Exception) { WebDavSettings(url, username, "", unavailable = true) }
    }

    fun save(url: String, username: String, password: String) = synchronized(lock) {
        if (loadLocked().unavailable && password.isEmpty()) throw CredentialStorageException("WebDAV 密码无法解密或保存，请明确重新输入密码；原数据已保留。")
        val encrypted = cipher.encrypt(password, context(url, username))
        if (!write(url, username, encrypted)) {
            failedWrites[prefs] = PendingWrite(url, username, encrypted, migration = false)
            throw CredentialStorageException("WebDAV 设置未保存，请检查存储空间后重试。")
        }
        failedWrites.remove(prefs)
        Unit
    }

    private fun write(url: String, username: String, encrypted: String): Boolean = prefs.edit()
        .putString("webdav_url", url).putString("webdav_username", username)
        .putString(ENCRYPTED, encrypted).putInt(FORMAT, 1).remove(LEGACY).commit()

    private data class PendingWrite(val url: String, val username: String, val encrypted: String, val migration: Boolean)
    companion object {
        const val LEGACY = "webdav_password"
        const val ENCRYPTED = "webdav_password_ciphertext"
        const val FORMAT = "webdav_password_format"
        private val lock = Any()
        // Android returns the same process-cached SharedPreferences instance for one preferences file.
        private val failedWrites = WeakHashMap<SharedPreferences, PendingWrite>()
        private fun context(url: String, username: String) = "rdpsync/webdav/v1/${url.length}:$url${username.length}:$username"
    }
}
