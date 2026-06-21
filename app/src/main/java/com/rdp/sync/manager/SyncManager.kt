package com.rdp.sync.manager

import android.util.Log
import com.rdp.sync.data.Device
import com.rdp.sync.network.WebDavSyncService
import org.json.JSONArray

object SyncManager {
    private const val TAG = "SyncManager"

    private var webDavBaseUrl: String? = null
    private var webDavUsername: String? = null
    private var webDavPassword: String? = null

    fun configureWebDav(baseUrl: String, username: String, password: String) {
        webDavBaseUrl = baseUrl
        webDavUsername = username
        webDavPassword = password
        Log.d(TAG, "WebDav configured: $baseUrl")
    }

    fun devicesToJsonArray(devices: List<Device>): JSONArray {
        val array = JSONArray()
        for (device in devices) {
            val obj = org.json.JSONObject()
            obj.put("id", device.id)
            obj.put("name", device.name)
            obj.put("host", device.host)
            obj.put("port", device.port)
            obj.put("username", device.username)
            obj.put("password", device.password)
            obj.put("domain", device.domain)
            obj.put("width", device.width)
            obj.put("height", device.height)
            array.put(obj)
        }
        return array
    }

    fun syncDevices(devices: List<Device>): Result<String> {
        val baseUrl = webDavBaseUrl ?: return Result.failure(IllegalStateException("WebDav not configured"))
        val username = webDavUsername ?: return Result.failure(IllegalStateException("WebDav username not set"))
        val password = webDavPassword ?: return Result.failure(IllegalStateException("WebDav password not set"))

        Log.d(TAG, "Syncing ${devices.size} devices to WebDav")
        return WebDavSyncService.syncToCloud(devicesToJsonArray(devices), baseUrl, username, password)
    }

    fun syncFromCloud(): Result<List<Device>> {
        val baseUrl = webDavBaseUrl ?: return Result.failure(IllegalStateException("WebDav not configured"))
        val username = webDavUsername ?: return Result.failure(IllegalStateException("WebDav username not set"))
        val password = webDavPassword ?: return Result.failure(IllegalStateException("WebDav password not set"))

        Log.d(TAG, "Syncing from WebDav")
        return WebDavSyncService.syncFromCloud(baseUrl, username, password)
            .map { jsonObjects ->
                val devices = mutableListOf<Device>()
                for (obj in jsonObjects) {
                    devices.add(
                        Device(
                            id = obj.optLong("id", 0),
                            name = obj.optString("name", ""),
                            host = obj.optString("host", ""),
                            port = obj.optInt("port", 3389),
                            username = obj.optString("username", ""),
                            password = obj.optString("password", ""),
                            domain = obj.optString("domain", ""),
                            width = obj.optInt("width", 1280),
                            height = obj.optInt("height", 720)
                        )
                    )
                }
                devices
            }
    }
}
