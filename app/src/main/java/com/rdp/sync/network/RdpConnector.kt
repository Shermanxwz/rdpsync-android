package com.rdp.sync.network

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log

object RdpConnector {
    init {
        System.loadLibrary("rdpsync")
    }

    external fun nativeConnect(host: String, port: Int, username: String, password: String, domain: String, width: Int, height: Int): Int
    external fun nativeConnect2(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int): Int
    external fun nativeConnect3(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int, enableTouchInput: Boolean): Int
    external fun nativeConnect4(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int, enableTouchInput: Boolean, compatMode: Boolean): Int
    external fun nativeDisconnect(): Int
    external fun nativeIsConnected(): Boolean
    external fun nativeGetStatus(): String
    external fun nativeGetDiag(): String
    external fun nativeSetLibDir(path: String)
    external fun nativeGetFrameArgb(width: Int, height: Int): IntArray?
    external fun nativeGetFrameId(): Long
    external fun nativeCopyFrameArgb(buffer: IntArray): Long
    external fun nativeCopyFrameDirtyArgb(buffer: IntArray, dirty: IntArray): Long
    external fun nativeCopyFrameToBitmap(bitmap: Bitmap, dirty: IntArray, forceFull: Boolean): Long
    external fun nativeSendPointerEvent(x: Int, y: Int, button: Int): Int
    external fun nativeSendWheelEvent(x: Int, y: Int, delta: Int): Int
    external fun nativeSendHWheelEvent(x: Int, y: Int, delta: Int): Int
    external fun nativeSendTouchEvent(pointerId: Int, eventType: Int, x: Int, y: Int): Int
    external fun nativeSendKeyEvent(keyCode: Int, down: Int): Int
    external fun nativeSendUnicodeChar(code: Int): Int
    external fun nativeSendClipboardText(text: String): Int
    external fun nativeGetWidth(): Int
    external fun nativeGetHeight(): Int

    private const val TAG = "RdpConnector"
    @Volatile private var lastKotlinError: String = ""
    private var cachedBitmap: Bitmap? = null
    private var cachedFrameId = 0L
    private val dirtyScratch = IntArray(4)
    private val frameLock = Any()

    @Volatile var rdpeiConnected = false
    @Volatile var rdpeiDisabled = false
    @Volatile var compatMode = false
    @Volatile var touchFailedCode = 0

    data class FrameBitmap(val bitmap: Bitmap, val frameId: Long, val dirtyRect: Rect)

    fun connectDevice(host: String, port: Int, username: String, password: String, domain: String, rdpServerName: String = "", width: Int = 1280, height: Int = 720, enableTouchInput: Boolean = true, compatibilityMode: Boolean = false): Boolean {
        return try {
            lastKotlinError = ""
            resetFrameCache()
            compatMode = compatibilityMode
            rdpeiConnected = false
            rdpeiDisabled = !enableTouchInput
            touchFailedCode = 0
            val result = nativeConnect4(
                host.trim(), port, username.trim(), password, domain.trim(), rdpServerName.trim(),
                width, height, enableTouchInput, compatibilityMode
            )
            Log.d(TAG, "Connect result: $result")
            result == 1
        } catch (e: Throwable) {
            lastKotlinError = "Native ${e::class.java.simpleName}: ${e.message}"
            Log.e(TAG, lastKotlinError, e)
            false
        }
    }

    fun disconnect(): Int {
        rdpeiConnected = false
        return safeNative("disconnect", -1) { nativeDisconnect() }
    }

    fun isConnected(): Boolean = safeNative("isConnected", false) { nativeIsConnected() }

    fun sendPointerEvent(x: Int, y: Int, button: Int): Int = safeNative("sendPointerEvent", -1) { nativeSendPointerEvent(x, y, button) }
    fun sendWheelEvent(x: Int, y: Int, delta: Int): Int = safeNative("sendWheelEvent", -1) { nativeSendWheelEvent(x, y, delta) }
    fun sendHWheelEvent(x: Int, y: Int, delta: Int): Int = safeNative("sendHWheelEvent", -1) { nativeSendHWheelEvent(x, y, delta) }

    fun sendTouchEvent(pointerId: Int, eventType: Int, x: Int, y: Int): Boolean {
        val rc = safeNative("sendTouchEvent", -1) { nativeSendTouchEvent(pointerId, eventType, x, y) }
        if (rc == 0) {
            if (eventType == 0) rdpeiConnected = true
            return true
        }
        touchFailedCode = rc
        return false
    }

    fun sendRdpeiTap(x: Int, y: Int): Boolean {
        if (!sendTouchEvent(0, 0, x, y)) return false
        Thread.sleep(30)
        return sendTouchEvent(0, 2, x, y)
    }
    fun sendRdpeiDoubleTap(x: Int, y: Int): Boolean {
        if (!sendTouchEvent(0, 0, x, y)) return false
        Thread.sleep(25)
        if (!sendTouchEvent(0, 2, x, y)) return false
        Thread.sleep(80)
        if (!sendTouchEvent(0, 0, x, y)) return false
        Thread.sleep(25)
        return sendTouchEvent(0, 2, x, y)
    }
    fun sendRdpeiLongPress(x: Int, y: Int): Boolean {
        if (!sendTouchEvent(0, 0, x, y)) return false
        Thread.sleep(550)
        return sendTouchEvent(0, 2, x, y)
    }

    fun getStatus(): String {
        if (lastKotlinError.isNotBlank()) return lastKotlinError
        return safeNative("getStatus", "not connected") { nativeGetStatus() }
    }

    fun getDiag(): String {
        val native = safeNative("getDiag", "") { nativeGetDiag() }
        val kotlin = buildString {
            if (rdpeiConnected) append("rdpei=connected\n")
            else if (rdpeiDisabled) append("rdpei=disabled\n")
            else append("rdpei=not-negotiated\n")
            if (touchFailedCode != 0) append("rdpei=touch-send-failed:$touchFailedCode\n")
            if (compatMode) append("connection=compatibility-retry\n")
        }
        return "$kotlin$native"
    }

    fun getWidth(): Int = safeNative("getWidth", 1280) { nativeGetWidth().coerceAtLeast(320) }
    fun getHeight(): Int = safeNative("getHeight", 720) { nativeGetHeight().coerceAtLeast(240) }

    fun getFrameBitmap(): Bitmap? = pollFrameBitmap()?.bitmap ?: cachedBitmap

    fun pollFrameBitmap(): FrameBitmap? {
        return try {
            val frameId = nativeGetFrameId()
            if (frameId <= 0L || frameId == cachedFrameId) return null
            val width = getWidth()
            val height = getHeight()
            var cacheReset = false
            val bitmap = cachedBitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    cachedBitmap = it; cacheReset = true
                }
            val copiedFrameId = synchronized(frameLock) {
                nativeCopyFrameToBitmap(bitmap, dirtyScratch, cacheReset)
            }
            if (copiedFrameId <= 0L) return null
            val x = dirtyScratch[0].coerceIn(0, width - 1)
            val y = dirtyScratch[1].coerceIn(0, height - 1)
            val dirtyWidth = dirtyScratch[2].coerceIn(1, width - x)
            val dirtyHeight = dirtyScratch[3].coerceIn(1, height - y)
            cachedFrameId = copiedFrameId
            FrameBitmap(bitmap, copiedFrameId, Rect(x, y, x + dirtyWidth, y + dirtyHeight))
        } catch (e: Throwable) {
            Log.e(TAG, "getFrameBitmap failed", e); null
        }
    }

    fun sendKeyEvent(keyCode: Int, down: Int): Int = safeNative("sendKeyEvent", -1) { nativeSendKeyEvent(keyCode, down) }

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

    fun sendClipboardText(text: String): Int = safeNative("sendClipboardText", -1) { nativeSendClipboardText(text) }

    fun <T> withFrameLock(block: () -> T): T = synchronized(frameLock) { block() }

    private fun resetFrameCache() { cachedBitmap = null; cachedFrameId = 0L }

    private inline fun <T> safeNative(name: String, fallback: T, block: () -> T): T {
        return try { block() } catch (e: Throwable) { Log.e(TAG, "$name failed", e); fallback }
    }
}
