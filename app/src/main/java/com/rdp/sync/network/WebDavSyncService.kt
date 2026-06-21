package com.rdp.sync.network

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object WebDavSyncService {
    private const val TAG = "WebDavSyncService"
    private const val MEDIA_TYPE = "application/json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun syncToCloud(devices: JSONArray, baseUrl: String, username: String, password: String): Result<String> {
        val body = RequestBody.create(MEDIA_TYPE.toMediaType(), devices.toString())

        val request = Request.Builder()
            .url("$baseUrl/rdpsync_devices.json")
            .put(body)
            .header("Authorization", basicAuth(username, password))
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success("Synced ${devices.length()} devices")
            } else {
                Result.failure(IOException("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync to cloud failed", e)
            Result.failure(e)
        }
    }

    fun syncFromCloud(baseUrl: String, username: String, password: String): Result<List<JSONObject>> {
        val request = Request.Builder()
            .url("$baseUrl/rdpsync_devices.json")
            .get()
            .header("Authorization", basicAuth(username, password))
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: "[]"
                val array = JSONArray(jsonStr)
                val devices = mutableListOf<JSONObject>()
                for (i in 0 until array.length()) {
                    devices.add(array.getJSONObject(i))
                }
                Result.success(devices)
            } else {
                Result.failure(IOException("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync from cloud failed", e)
            Result.failure(e)
        }
    }

    private fun basicAuth(username: String, password: String): String {
        val credentials = "$username:$password"
        val bytes = credentials.toByteArray(Charsets.UTF_8)
        return "Basic " + java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
