package com.rdp.sync.storage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Microsoft Windows App–style phone storage redirection helper.
 * Once the user enables phone storage sharing and grants Android file access,
 * RDP sessions mount real phone directories (Download, DCIM, Documents, Pictures, etc.)
 * directly as "Android 上的 RdpSync" / "RdpSync on Android".
 */
object PhoneStorageAccess {
    private const val PREFS_NAME = "session_preferences"
    private const val KEY_SHARE_PHONE_STORAGE = "share_phone_storage"

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun legacyPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    @Suppress("DEPRECATION")
    fun storageRootPath(): String = Environment.getExternalStorageDirectory().absolutePath

    fun isSharePhoneStorageEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHARE_PHONE_STORAGE, false)

    fun setSharePhoneStorageEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHARE_PHONE_STORAGE, enabled)
            .apply()
    }

    fun shouldMountOnConnect(context: Context): Boolean =
        isSharePhoneStorageEnabled(context) && hasAllFilesAccess(context)

    fun openManageAllFilesSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val pkgUri = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
        for (intent in candidates) {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val started = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return true
        }
        return false
    }
}
