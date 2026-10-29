package com.rdp.sync.manager

import com.rdp.sync.data.Device
import com.rdp.sync.network.SyncFailure
import com.rdp.sync.network.safeSync
import com.rdp.sync.network.SyncFile
import com.rdp.sync.network.WebDavSyncService
import com.rdp.sync.security.CredentialStorageException
import org.json.JSONArray
import org.json.JSONObject

enum class SyncDirection {
    UPLOAD, DOWNLOAD, MERGE, UPLOAD_WITH_PASSWORDS, DOWNLOAD_WITH_PASSWORDS, MERGE_WITH_PASSWORDS;
    val includesPasswords: Boolean get() = this in setOf(UPLOAD_WITH_PASSWORDS, DOWNLOAD_WITH_PASSWORDS, MERGE_WITH_PASSWORDS)
    fun withPasswords(): SyncDirection = when (this) {
        UPLOAD -> UPLOAD_WITH_PASSWORDS; DOWNLOAD -> DOWNLOAD_WITH_PASSWORDS; MERGE -> MERGE_WITH_PASSWORDS; else -> this
    }
}

data class DeviceSyncResult(val message: String, val devicesToStore: List<Device>? = null, val mergeOnlyNew: Boolean = false)

object SyncManager {
    private var config: WebDavConfig? = null
    fun configureWebDav(baseUrl: String, username: String, password: String) {
        config = WebDavConfig(baseUrl.trim().trimEnd('/'), username.trim(), password)
    }

    fun devicesToJsonArray(devices: List<Device>, includePasswords: Boolean = false): JSONArray {
        if (includePasswords && devices.any { it.credentialUnavailable || !it.passwordProvided }) throw CredentialStorageException()
        return JSONArray().apply {
            devices.sortedWith(compareBy<Device> { it.name.lowercase() }.thenBy { it.host }.thenBy { it.port }).forEach { device ->
                put(JSONObject().apply {
                    put("id", device.id); put("name", device.name); put("host", device.host); put("port", device.port)
                    put("username", device.username); put("domain", device.domain); put("rdpServerName", device.rdpServerName)
                    put("width", device.width); put("height", device.height)
                    if (includePasswords) put("password", device.password)
                })
            }
        }
    }

    fun uploadDevices(devices: List<Device>, includePasswords: Boolean = false): Result<DeviceSyncResult> = safeSync {
        val c = requireConfig()
        WebDavSyncService.syncToCloud(devicesToJsonArray(devices, includePasswords), c.baseUrl, c.username, c.password, file(includePasswords)).getOrThrow()
        DeviceSyncResult("已上传 ${devices.size} 台设备至 ${file(includePasswords).fileName}（${if (includePasswords) "含明文密码" else "不含密码，旧版文件未改动"}）")
    }

    fun downloadDevices(includePasswords: Boolean = false): Result<DeviceSyncResult> = safeSync {
        val c = requireConfig()
        val devices = parseDevices(WebDavSyncService.syncFromCloud(c.baseUrl, c.username, c.password, file(includePasswords)).getOrThrow(), includePasswords)
        DeviceSyncResult("已导入 ${devices.size} 台设备；未匹配的本机设备保留，缺失密码不覆盖本机密码", devices)
    }

    fun mergeDevices(localDevices: List<Device>, includePasswords: Boolean = false): Result<DeviceSyncResult> = safeSync {
        val c = requireConfig()
        if (includePasswords && localDevices.any { it.credentialUnavailable }) throw CredentialStorageException()
        val remote = parseDevices(WebDavSyncService.syncFromCloud(c.baseUrl, c.username, c.password, file(includePasswords), missingIsEmpty = true).getOrThrow(), includePasswords)
        val merged = SyncPolicy.merge(localDevices, remote)
        WebDavSyncService.syncToCloud(devicesToJsonArray(merged, includePasswords), c.baseUrl, c.username, c.password, file(includePasswords)).getOrThrow()
        DeviceSyncResult("已合并 ${merged.size} 台设备（相同设备以本机为准）；文件：${file(includePasswords).fileName}", merged, mergeOnlyNew = true)
    }

    internal fun parseDevices(objects: List<JSONObject>, includePasswords: Boolean): List<Device> = try { objects.map { obj ->
        val host = obj.stringField("host", required = true).trim()
        val username = obj.stringField("username", required = true).trim()
        require(host.isNotEmpty() && username.isNotEmpty()) { "云端设备格式不正确，本机数据未改动" }
        val hasPassword = includePasswords && obj.has("password") && !obj.isNull("password")
        Device(name = obj.stringField("name").ifBlank { host }, host = host,
            port = obj.optInt("port", 3389).coerceIn(1, 65535), username = username,
            password = if (hasPassword) obj.stringField("password", required = true) else "",
            domain = obj.stringField("domain"), rdpServerName = obj.stringField("rdpServerName"),
            width = obj.optInt("width", 1280).coerceAtLeast(320), height = obj.optInt("height", 720).coerceAtLeast(240)
        ).apply { passwordProvided = hasPassword }
    } } catch (_: Exception) {
        // Field/type failures can quote password values just like top-level parser failures.
        throw SyncFailure.invalidFormat()
    }

    private fun JSONObject.stringField(name: String, required: Boolean = false): String {
        if (!has(name) || isNull(name)) {
            if (required) throw SyncFailure.invalidFormat()
            return ""
        }
        // Android's org.json can stringify objects in getString; never turn malformed fields into UI text.
        return get(name) as? String ?: throw SyncFailure.invalidFormat()
    }

    fun testWebDav(): Result<DeviceSyncResult> = safeSync {
        val c = requireConfig()
        DeviceSyncResult(WebDavSyncService.testConnection(c.baseUrl, c.username, c.password).getOrThrow())
    }
    private fun file(secrets: Boolean) = if (secrets) SyncFile.LEGACY_WITH_PASSWORDS else SyncFile.PROFILES
    private fun requireConfig(): WebDavConfig = (config ?: throw SyncFailure.configuration()).also {
        if (it.baseUrl.isBlank() || it.username.isBlank() || it.password.isBlank()) throw SyncFailure.configuration()
    }
    private data class WebDavConfig(val baseUrl: String, val username: String, val password: String)
}
