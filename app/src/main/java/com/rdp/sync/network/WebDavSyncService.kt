package com.rdp.sync.network

import android.util.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
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
        val cleanBaseUrl = normalizeCollectionUrl(baseUrl)
        ensureCollectionExists(cleanBaseUrl, username, password).getOrElse { return Result.failure(it) }
        val targetUrl = buildFileUrl(cleanBaseUrl)
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
        val cleanBaseUrl = normalizeCollectionUrl(baseUrl)
        ensureCollectionExists(cleanBaseUrl, username, password).getOrElse { return Result.failure(it) }
        val request = Request.Builder()
            .url(buildFileUrl(cleanBaseUrl))
            .get()
            .header("Authorization", Credentials.basic(username, password))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return Result.success(emptyList())
                if (!response.isSuccessful) return Result.failure(IOException(readableHttpError(response.code, response.message)))
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
        val cleanBaseUrl = normalizeCollectionUrl(baseUrl)
        val directoryResult = ensureCollectionExists(cleanBaseUrl, username, password)
        if (directoryResult.isFailure) return Result.failure(directoryResult.exceptionOrNull() ?: IOException("WebDAV 目录检查失败"))
        val readWriteResult = verifyReadWrite(cleanBaseUrl, username, password)
        if (readWriteResult.isFailure) return Result.failure(readWriteResult.exceptionOrNull() ?: IOException("WebDAV 读写测试失败"))
        return Result.success("WebDAV 连接正常，目录可读写：$cleanBaseUrl")
    }

    private fun verifyReadWrite(baseUrl: String, username: String, password: String): Result<Unit> {
        val auth = Credentials.basic(username, password)
        val testUrl = normalizeCollectionUrl(baseUrl) + ".rdpsync_webdav_test"
        val putRequest = Request.Builder()
            .url(testUrl)
            .put("ok".toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("Authorization", auth)
            .build()
        executeUnit(putRequest, "OK").getOrElse { return Result.failure(it) }

        val getRequest = Request.Builder()
            .url(testUrl)
            .get()
            .header("Authorization", auth)
            .build()
        try {
            client.newCall(getRequest).execute().use { response ->
                if (!response.isSuccessful) return Result.failure(IOException("WebDAV 测试文件读取失败：${readableHttpError(response.code, response.message)}"))
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            val deleteRequest = Request.Builder()
                .url(testUrl)
                .delete()
                .header("Authorization", auth)
                .build()
            try {
                client.newCall(deleteRequest).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete WebDAV test file", e)
            }
        }
        return Result.success(Unit)
    }

    private fun ensureCollectionExists(baseUrl: String, username: String, password: String): Result<Unit> {
        val auth = Credentials.basic(username, password)
        propfind(baseUrl, auth).onSuccess { return Result.success(Unit) }

        // 目录不存在时逐级 MKCOL。409 通常代表父目录不存在；405 通常代表目录已存在或服务端不允许在文件路径 MKCOL。
        val uri = URI(baseUrl)
        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        var currentPath = if (uri.path.startsWith("/")) "/" else ""
        for (segment in segments) {
            currentPath = (currentPath.trimEnd('/') + "/" + segment).trimEnd('/') + "/"
            val currentUri = URI(uri.scheme, uri.authority, currentPath, null, null).toString()
            val exists = propfind(currentUri, auth).isSuccess
            if (!exists) {
                val mkcol = Request.Builder()
                    .url(currentUri)
                    .method("MKCOL", "".toRequestBody(null))
                    .header("Authorization", auth)
                    .build()
                client.newCall(mkcol).execute().use { response ->
                    when (response.code) {
                        201, 204, 405 -> Unit
                        else -> return Result.failure(IOException("创建 WebDAV 目录失败 ${readableHttpError(response.code, response.message)}，目录：$currentUri"))
                    }
                }
            }
        }

        return propfind(baseUrl, auth).map { Unit }
    }

    private fun propfind(url: String, auth: String): Result<Unit> {
        val body = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", "0")
            .header("Authorization", auth)
            .build()
        return executeUnit(request, "OK").map { Unit }
    }

    private fun executeUnit(request: Request, successMessage: String): Result<String> = try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 207 || response.code == 201 || response.code == 204) {
                Result.success(successMessage)
            } else {
                Result.failure(IOException(readableHttpError(response.code, response.message)))
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

    private fun normalizeCollectionUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val directory = if (trimmed.endsWith("/$FILE_NAME", ignoreCase = true)) {
            trimmed.removeSuffix("/$FILE_NAME")
        } else {
            trimmed
        }
        return directory.trimEnd('/') + "/"
    }

    private fun buildFileUrl(baseUrl: String): String = normalizeCollectionUrl(baseUrl) + FILE_NAME

    private fun readableHttpError(code: Int, message: String): String = when (code) {
        401, 403 -> "HTTP $code: 认证失败或无权限，请检查用户名/密码/目录权限"
        404 -> "HTTP 404: WebDAV 路径不存在"
        405 -> "HTTP 405: 服务端不允许当前方法；请确认填写的是 WebDAV 目录地址，不是网页/文件地址"
        409 -> "HTTP 409: 父目录不存在；RdpSync 已尝试自动创建目录，仍失败请检查上级路径权限"
        else -> "HTTP $code: $message"
    }
}
