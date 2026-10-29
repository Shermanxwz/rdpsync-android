package com.rdp.sync.security

import android.content.Context

class AndroidCertificatePinStorage(context: Context) : CertificateTrustStore.Storage {
    override val namespace = context.applicationContext.packageName + ":certificate_trust_v1"
    private val preferences = context.applicationContext.getSharedPreferences("certificate_trust_v1", Context.MODE_PRIVATE)
    override fun read(key: String): String? = runCatching { preferences.getString(key, null) }.getOrNull()
    override fun write(key: String, value: String): Boolean = preferences.edit().putString(key, value).commit()
}
