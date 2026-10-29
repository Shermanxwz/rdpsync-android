package com.rdp.sync.network

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

enum class SyncFile(val fileName: String) {
    PROFILES("rdpsync_profiles.json"), LEGACY_WITH_PASSWORDS("rdpsync_devices.json")
}

object WebDavSyncService {
    private const val MEDIA_TYPE = "application/json; charset=utf-8"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun syncToCloud(devices: JSONArray, baseUrl: String, username: String, password: String, file: SyncFile = SyncFile.PROFILES): Result<String> =
        safeSync { syncToCloudUnsafe(devices, baseUrl, username, password, file).getOrThrow() }

    private fun syncToCloudUnsafe(devices: JSONArray, baseUrl: String, username: String, password: String, file: SyncFile): Result<String> {
        val cleanBaseUrl = normalizeCollectionUrl(baseUrl)
        ensureCollectionExists(cleanBaseUrl, username, password).getOrElse { return Result.failure(it) }
        val targetUrl = buildFileUrl(cleanBaseUrl, file)
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

    fun syncFromCloud(baseUrl: String, username: String, password: String, file: SyncFile = SyncFile.PROFILES, missingIsEmpty: Boolean = false): Result<List<JSONObject>> =
        safeSync { syncFromCloudUnsafe(baseUrl, username, password, file, missingIsEmpty).getOrThrow() }

    private fun syncFromCloudUnsafe(baseUrl: String, username: String, password: String, file: SyncFile, missingIsEmpty: Boolean): Result<List<JSONObject>> {
        val cleanBaseUrl = normalizeCollectionUrl(baseUrl)
        ensureCollectionExists(cleanBaseUrl, username, password).getOrElse { return Result.failure(it) }
        val request = Request.Builder()
            .url(buildFileUrl(cleanBaseUrl, file))
            .get()
            .header("Authorization", Credentials.basic(username, password))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return if (missingIsEmpty) Result.success(emptyList()) else
                    Result.failure(SyncFailure.missing(file))
                if (!response.isSuccessful) return Result.failure(SyncFailure.http(response.code))
                val jsonStr = response.body?.string().orEmpty()
                val devicesArray = parseDevicesArray(jsonStr)
                val devices = mutableListOf<JSONObject>()
                for (i in 0 until devicesArray.length()) devices.add(devicesArray.getJSONObject(i))
                Result.success(devices)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun testConnection(baseUrl: String, username: String, password: String): Result<String> =
        safeSync { testConnectionUnsafe(baseUrl, username, password).getOrThrow() }

    private fun testConnectionUnsafe(baseUrl: String, username: String, password: String): Result<String> {
        val cleanBaseUrl = normalizeCollectionUrl(baseUrl)
        val directoryResult = ensureCollectionExists(cleanBaseUrl, username, password)
        if (directoryResult.isFailure) return Result.failure(directoryResult.exceptionOrNull() ?: IOException("WebDAV 目录检查失败"))
        val readWriteResult = verifyReadWrite(cleanBaseUrl, username, password)
        if (readWriteResult.isFailure) return Result.failure(readWriteResult.exceptionOrNull() ?: IOException("WebDAV 读写测试失败"))
        return Result.success("WebDAV 连接正常，目录可读写。")
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
                if (!response.isSuccessful) return Result.failure(SyncFailure.http(response.code))
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
                // Best-effort cleanup; exception messages can contain URLs or credentials.
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
                        else -> return Result.failure(SyncFailure.http(response.code))
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
                Result.failure(SyncFailure.http(response.code))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun parseDevicesArray(jsonStr: String): JSONArray = try {
        val trimmed = jsonStr.trim()
        if (trimmed.startsWith("{")) JSONObject(trimmed).getJSONArray("devices") else JSONArray(trimmed)
    } catch (_: Exception) {
        // org.json messages may quote the entire password-bearing source document.
        throw SyncFailure.invalidFormat()
    }

    private fun normalizeCollectionUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val suffix = SyncFile.entries.firstOrNull { trimmed.endsWith("/${it.fileName}", ignoreCase = true) }
        val directory = if (suffix == null) trimmed else trimmed.dropLast(suffix.fileName.length + 1)
        return directory.trimEnd('/') + "/"
    }

    internal fun buildFileUrl(baseUrl: String, file: SyncFile): String = normalizeCollectionUrl(baseUrl) + file.fileName

}
