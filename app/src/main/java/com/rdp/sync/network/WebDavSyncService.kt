package com.rdp.sync.network

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * WebDAV 同步服务
 * 通过 WebDAV 协议同步设备列表（JSON 格式）
 */
class WebDavSyncService(
    private val webDavUrl: String,
    private val username: String,
    private val password: String
) {
    
    companion object {
        const val SYNC_FILE = "rdpsync_devices.json"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
    
    private val client = OkHttpClient.Builder()
        .eventListener(object : EventListener() {})
        .build()
    
    private fun basicAuthCredentials(): String {
        return android.util.Base64.encodeToString(
            "${username}:${password}".toByteArray(), 
            android.util.Base64.NO_WRAP
        )
    }
    
    @Throws(IOException::class)
    fun pullDevices(): String {
        val request = Request.Builder()
            .url("$webDavUrl/$SYNC_FILE")
            .get()
            .header("Authorization", "Basic ${basicAuthCredentials()}")
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WebDAV PULL failed: ${response.code}")
            }
            return response.body?.string() ?: "[]"
        }
    }
    
    @Throws(IOException::class)
    fun pushDevices(devicesJson: String) {
        val requestBody = devicesJson.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$webDavUrl/$SYNC_FILE")
            .put(requestBody)
            .header("Authorization", "Basic ${basicAuthCredentials()}")
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WebDAV PUSH failed: ${response.code}")
            }
        }
    }
    
    fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url(webDavUrl)
                .method("PROPFIND", null)
                .header("Authorization", "Basic ${basicAuthCredentials()}")
                .build()
                
            client.newCall(request).execute().use { response ->
                response.code == 207
            }
        } catch (e: Exception) {
            false
        }
    }
}
