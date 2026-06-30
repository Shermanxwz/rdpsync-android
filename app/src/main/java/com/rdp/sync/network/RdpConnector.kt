package com.rdp.sync.network

import android.graphics.Bitmap
import android.util.Log

object RdpConnector {
    init {
        System.loadLibrary("rdpsync")
    }

    external fun nativeConnect(host: String, port: Int, username: String, password: String, domain: String, width: Int, height: Int): Int
    external fun nativeConnect2(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int): Int
    external fun nativeDisconnect(): Int
    external fun nativeIsConnected(): Boolean
    external fun nativeGetStatus(): String
    external fun nativeGetDiag(): String
    external fun nativeSetLibDir(path: String)
    external fun nativeGetFrameArgb(width: Int, height: Int): IntArray?
    external fun nativeGetFrameId(): Long
    external fun nativeCopyFrameToBitmap(bitmap: Bitmap): Boolean
    external fun nativeSendPointerEvent(x: Int, y: Int, button: Int): Int
    external fun nativeSendWheelEvent(x: Int, y: Int, delta: Int): Int
    external fun nativeSendHWheelEvent(x: Int, y: Int, delta: Int): Int
    external fun nativeSendWheelBatch(x: Int, y: Int, verticalDelta: Int, horizontalDelta: Int): Int
    external fun nativeSendKeyEvent(keyCode: Int, down: Int): Int
    external fun nativeSendUnicodeChar(code: Int): Int
    external fun nativeSendClipboardText(text: String): Int
    external fun nativeGetWidth(): Int
    external fun nativeGetHeight(): Int

    private const val TAG = "RdpConnector"
    @Volatile
    private var lastKotlinError: String = ""

    fun connectDevice(host: String, port: Int, username: String, password: String, domain: String, rdpServerName: String = "", width: Int = 1280, height: Int = 720): Boolean {
        return try {
            lastKotlinError = ""
            val result = nativeConnect2(
                host.trim(),
                port,
                username.trim(),
                password,
                domain.trim(),
                rdpServerName.trim(),
                width,
                height
            )
            Log.d(TAG, "Connect result: $result")
            result == 1
        } catch (e: Throwable) {
            lastKotlinError = "Native 启动失败：${e::class.java.simpleName}: ${e.message}"
            Log.e(TAG, lastKotlinError, e)
            false
        }
    }

    fun disconnect(): Int = safeNative("disconnect", -1) { nativeDisconnect() }

    fun isConnected(): Boolean = safeNative("isConnected", false) { nativeIsConnected() }

    fun getStatus(): String {
        if (lastKotlinError.isNotBlank()) return lastKotlinError
        return safeNative("getStatus", "未连接") { nativeGetStatus() }
    }

    fun getDiag(): String = safeNative("getDiag", "") { nativeGetDiag() }

    fun getWidth(): Int = safeNative("getWidth", 1280) { nativeGetWidth().coerceAtLeast(320) }

    fun getHeight(): Int = safeNative("getHeight", 720) { nativeGetHeight().coerceAtLeast(240) }

    fun getFrameId(): Long = safeNative("getFrameId", 0L) { nativeGetFrameId() }

    fun copyFrameToBitmap(bitmap: Bitmap): Boolean = safeNative("copyFrameToBitmap", false) {
        nativeCopyFrameToBitmap(bitmap)
    }

    fun getFrameBitmap(): Bitmap? {
        return try {
            val width = getWidth()
            val height = getHeight()
            val pixels = nativeGetFrameArgb(width, height) ?: return null
            if (pixels.size != width * height) return null
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            Log.e(TAG, "getFrameBitmap failed", e)
            null
        }
    }

    fun sendPointerEvent(x: Int, y: Int, button: Int): Int = safeNative("sendPointerEvent", -1) {
        nativeSendPointerEvent(x, y, button)
    }

    fun sendWheelEvent(x: Int, y: Int, delta: Int): Int = safeNative("sendWheelEvent", -1) {
        nativeSendWheelEvent(x, y, delta)
    }

    fun sendHWheelEvent(x: Int, y: Int, delta: Int): Int = safeNative("sendHWheelEvent", -1) {
        nativeSendHWheelEvent(x, y, delta)
    }

    fun sendWheelBatch(x: Int, y: Int, verticalDelta: Int, horizontalDelta: Int): Int = safeNative("sendWheelBatch", -1) {
        nativeSendWheelBatch(x, y, verticalDelta, horizontalDelta)
    }

    fun sendKeyEvent(keyCode: Int, down: Int): Int = safeNative("sendKeyEvent", -1) {
        nativeSendKeyEvent(keyCode, down)
    }

    fun sendTextInput(text: String): Int {
        var result = 0
        text.forEach { char ->
            result = when (char) {
                '\n', '\r' -> sendKeyStroke(0x1C)
                '\b' -> sendKeyStroke(0x0E)
                else -> safeNative("sendUnicodeChar", -1) { nativeSendUnicodeChar(char.code) }
            }
        }
        return result
    }

    fun sendKeyStroke(keyCode: Int): Int {
        val down = sendKeyEvent(keyCode, 1)
        val up = sendKeyEvent(keyCode, 0)
        return if (down == 0 && up == 0) 0 else -1
    }

    fun sendClipboardText(text: String): Int = safeNative("sendClipboardText", -1) {
        nativeSendClipboardText(text)
    }

    private inline fun <T> safeNative(name: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Throwable) {
            Log.e(TAG, "$name failed", e)
            fallback
        }
    }
}
