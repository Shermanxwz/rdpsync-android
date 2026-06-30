package com.rdp.sync.manager

import android.util.Log
import com.rdp.sync.data.Device
import com.rdp.sync.network.WebDavSyncService
import org.json.JSONArray
import org.json.JSONObject

enum class SyncDirection { UPLOAD, DOWNLOAD, MERGE }

data class DeviceSyncResult(
    val message: String,
    val devicesToStore: List<Device>? = null
)

object SyncManager {
    private const val TAG = "SyncManager"

    private var webDavBaseUrl: String? = null
    private var webDavUsername: String? = null
    private var webDavPassword: String? = null

    fun configureWebDav(baseUrl: String, username: String, password: String) {
        webDavBaseUrl = baseUrl.trim().trimEnd('/').ifBlank { null }
        webDavUsername = username.trim().ifBlank { null }
        webDavPassword = password.ifBlank { null }
        Log.d(TAG, "WebDAV configured")
    }

    fun devicesToJsonArray(devices: List<Device>): JSONArray {
        val array = JSONArray()
        devices.sortedWith(compareBy<Device> { it.name.lowercase() }.thenBy { it.host }.thenBy { it.port }).forEach { device ->
            val obj = JSONObject()
            obj.put("id", device.id)
            obj.put("name", device.name)
            obj.put("host", device.host)
            obj.put("port", device.port)
            obj.put("username", device.username)
            obj.put("password", device.password)
            obj.put("domain", device.domain)
            obj.put("rdpServerName", device.rdpServerName)
            obj.put("width", device.width)
            obj.put("height", device.height)
            obj.put("updatedAt", System.currentTimeMillis())
            array.put(obj)
        }
        return array
    }

    fun uploadDevices(devices: List<Device>): Result<DeviceSyncResult> {
        val config = requireConfig().getOrElse { return Result.failure(it) }
        Log.d(TAG, "Uploading ${devices.size} devices to WebDAV")
        return WebDavSyncService.syncToCloud(devicesToJsonArray(devices), config.baseUrl, config.username, config.password)
            .map { DeviceSyncResult("已上传 ${devices.size} 台设备到 WebDAV") }
    }

    fun downloadDevices(): Result<DeviceSyncResult> {
        val config = requireConfig().getOrElse { return Result.failure(it) }
        Log.d(TAG, "Downloading devices from WebDAV")
        return WebDavSyncService.syncFromCloud(config.baseUrl, config.username, config.password)
            .map { jsonObjects ->
                val devices = jsonObjects.mapNotNull { it.toDeviceOrNull() }
                DeviceSyncResult("已从 WebDAV 下载 ${devices.size} 台设备", devices)
            }
    }

    fun mergeDevices(localDevices: List<Device>): Result<DeviceSyncResult> {
        val config = requireConfig().getOrElse { return Result.failure(it) }
        Log.d(TAG, "Merging ${localDevices.size} local devices with WebDAV")
        return WebDavSyncService.syncFromCloud(config.baseUrl, config.username, config.password)
            .mapCatching { jsonObjects ->
                val remoteDevices = jsonObjects.mapNotNull { it.toDeviceOrNull() }
                val merged = mergeByStableKey(localDevices, remoteDevices)
                WebDavSyncService.syncToCloud(devicesToJsonArray(merged), config.baseUrl, config.username, config.password).getOrThrow()
                DeviceSyncResult("同步完成：本地 ${localDevices.size} 台，云端 ${remoteDevices.size} 台，合并后 ${merged.size} 台", merged)
            }
    }

    private fun mergeByStableKey(local: List<Device>, remote: List<Device>): List<Device> {
        val merged = linkedMapOf<String, Device>()
        (remote + local).forEach { device ->
            val key = "${device.host.trim().lowercase()}:${device.port}:${device.username.trim().lowercase()}:${device.domain.trim().lowercase()}"
            val current = merged[key]
            merged[key] = when {
                current == null -> device.copy(id = 0)
                current.name.isBlank() && device.name.isNotBlank() -> device.copy(id = 0)
                else -> current.copy(
                    id = 0,
                    name = current.name.ifBlank { device.name },
                    password = current.password.ifBlank { device.password },
                    rdpServerName = current.rdpServerName.ifBlank { device.rdpServerName },
                    width = maxOf(current.width, device.width),
                    height = maxOf(current.height, device.height)
                )
            }
        }
        return merged.values.sortedWith(compareBy<Device> { it.name.lowercase() }.thenBy { it.host })
    }

    private fun JSONObject.toDeviceOrNull(): Device? {
        val host = optString("host", "").trim()
        val username = optString("username", "").trim()
        if (host.isBlank() || username.isBlank()) return null
        return Device(
            id = 0,
            name = optString("name", host).ifBlank { host },
            host = host,
            port = optInt("port", 3389).coerceIn(1, 65535),
            username = username,
            password = optString("password", ""),
            domain = optString("domain", ""),
            rdpServerName = optString("rdpServerName", ""),
            width = optInt("width", 1280).coerceAtLeast(320),
            height = optInt("height", 720).coerceAtLeast(240)
        )
    }

    fun testWebDav(): Result<DeviceSyncResult> {
        val config = requireConfig().getOrElse { return Result.failure(it) }
        return WebDavSyncService.testConnection(config.baseUrl, config.username, config.password)
            .map { DeviceSyncResult(it) }
    }

    private fun requireConfig(): Result<WebDavConfig> {
        val baseUrl = webDavBaseUrl ?: return Result.failure(IllegalStateException("请先在设置里填写 WebDAV 地址"))
        val username = webDavUsername ?: return Result.failure(IllegalStateException("请先填写 WebDAV 用户名"))
        val password = webDavPassword ?: return Result.failure(IllegalStateException("请先填写 WebDAV 密码"))
        return Result.success(WebDavConfig(baseUrl, username, password))
    }

    private data class WebDavConfig(val baseUrl: String, val username: String, val password: String)
}
