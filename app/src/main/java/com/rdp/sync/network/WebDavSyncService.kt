package com.rdp.sync.network

import android.util.Base64
import android.util.Log
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object WebDavSyncService {
    private const val TAG = "WebDavSyncService"
    private const val MEDIA_TYPE = "application/json; charset=utf-8"
    private const val FILE_NAME = "rdpsync_devices.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun syncToCloud(devices: JSONArray, baseUrl: String, username: String, password: String): Result<String> {
        val targetUrl = buildFileUrl(baseUrl)
        val payload = JSONObject()
            .put("schema", 1)
            .put("app", "RdpSync")
            .put("updatedAt", System.currentTimeMillis())
            .put("devices", devices)
            .toString(2)

        val request = Request.Builder()
            .url(targetUrl)
            .put(payload.toRequestBody(MEDIA_TYPE.toMediaType()))
            .header("Authorization", Credentials.basic(username, password))
            .header("Content-Type", MEDIA_TYPE)
            .build()

        return executeUnit(request, "Synced ${devices.length()} devices")
    }

    fun syncFromCloud(baseUrl: String, username: String, password: String): Result<List<JSONObject>> {
        val request = Request.Builder()
            .url(buildFileUrl(baseUrl))
            .get()
            .header("Authorization", Credentials.basic(username, password))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return Result.success(emptyList())
                if (!response.isSuccessful) return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                val jsonStr = response.body?.string().orEmpty().ifBlank { "[]" }
                val devicesArray = parseDevicesArray(jsonStr)
                val devices = mutableListOf<JSONObject>()
                for (i in 0 until devicesArray.length()) devices.add(devicesArray.getJSONObject(i))
                Result.success(devices)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync from cloud failed", e)
            Result.failure(e)
        }
    }

    fun testConnection(baseUrl: String, username: String, password: String): Result<String> {
        val request = Request.Builder()
            .url(baseUrl.trim().trimEnd('/') + "/")
            .method("PROPFIND", "".toRequestBody(null))
            .header("Depth", "0")
            .header("Authorization", Credentials.basic(username, password))
            .build()
        return executeUnit(request, "WebDAV 连接正常")
    }

    private fun executeUnit(request: Request, successMessage: String): Result<String> = try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 207 || response.code == 201 || response.code == 204) {
                Result.success(successMessage)
            } else {
                Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "WebDAV request failed", e)
        Result.failure(e)
    }

    private fun parseDevicesArray(jsonStr: String): JSONArray {
        val trimmed = jsonStr.trim()
        return if (trimmed.startsWith("{")) {
            JSONObject(trimmed).optJSONArray("devices") ?: JSONArray()
        } else {
            JSONArray(trimmed)
        }
    }

    private fun buildFileUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/$FILE_NAME"
}
