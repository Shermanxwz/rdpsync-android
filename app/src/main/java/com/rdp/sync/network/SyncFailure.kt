package com.rdp.sync.network

import com.rdp.sync.security.CredentialStorageException
import org.json.JSONException
import java.io.IOException

/** Never retain a server payload, URL, HTTP reason phrase, parser exception, or its cause. */
class SyncFailure private constructor(message: String) : IOException(message) {
    companion object {
        fun invalidFormat() = SyncFailure("云端文件格式不正确，请检查备份格式；未写入云端或导入本机。")
        fun configuration() = SyncFailure("请先填写有效的 WebDAV 地址、用户名和密码。")
        fun network() = SyncFailure("WebDAV 请求失败，请检查网络、HTTPS 证书和服务器地址后重试。")
        fun missing(file: SyncFile) = SyncFailure("云端没有 ${file.fileName}。旧版备份请明确选择「旧版含密码文件」；本机设备未改动。")
        fun http(code: Int) = SyncFailure(when (code) {
            401, 403 -> "HTTP $code: 认证失败或无权限，请检查用户名、密码和目录权限。"
            404 -> "HTTP 404: WebDAV 路径不存在。"
            405 -> "HTTP 405: 服务端不允许当前方法，请检查 WebDAV 目录地址。"
            409 -> "HTTP 409: 父目录不存在，请检查目录权限。"
            else -> "HTTP $code: WebDAV 服务器请求失败。"
        })
    }
}

internal fun sanitizedSyncFailure(error: Exception): Exception = when (error) {
    is SyncFailure -> error
    is CredentialStorageException -> CredentialStorageException() // Drop any nested raw cause.
    is JSONException -> SyncFailure.invalidFormat()
    is IllegalArgumentException -> SyncFailure.configuration()
    else -> SyncFailure.network()
}

internal inline fun <T> safeSync(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Exception) { Result.failure(sanitizedSyncFailure(error)) }

/** Presentation boundary: unknown database/network/parser errors are never copied into UI state. */
fun safeOperationMessage(error: Throwable): String = when (error) {
    is SyncFailure -> error.message.orEmpty()
    is CredentialStorageException -> "本机凭据无法解密或保存，请检查存储空间，必要时明确重新输入密码；原数据已保留。"
    else -> "操作失败，请检查网络或可用存储空间后重试；未保存的更改不会标为成功。"
}
