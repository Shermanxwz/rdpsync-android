package com.rdp.sync.network

import android.graphics.Bitmap
import android.util.Log

object RdpConnector {
    init {
        System.loadLibrary("rdpsync")
    }

    external fun nativeConnect(host: String, port: Int, username: String, password: String, domain: String, width: Int, height: Int): Int
    external fun nativeDisconnect(): Int
    external fun nativeGetFrameArgb(width: Int, height: Int): IntArray?
    external fun nativeSendPointerEvent(x: Int, y: Int, button: Int): Int
    external fun nativeSendKeyEvent(keyCode: Int, down: Int): Int
    external fun nativeSendClipboardText(text: String): Int
    external fun nativeGetWidth(): Int
    external fun nativeGetHeight(): Int

    private const val TAG = "RdpConnector"

    fun connectDevice(host: String, port: Int, username: String, password: String, domain: String, width: Int = 1280, height: Int = 720): Boolean {
        return try {
            val result = nativeConnect(host, port, username, password, domain, width, height)
            Log.d(TAG, "Connect result: $result")
            result == 1
        } catch (e: Throwable) {
            Log.e(TAG, "Connect failed", e)
            false
        }
    }

    fun disconnect(): Int = safeNative("disconnect", -1) { nativeDisconnect() }

    fun getWidth(): Int = safeNative("getWidth", 1280) { nativeGetWidth().coerceAtLeast(320) }

    fun getHeight(): Int = safeNative("getHeight", 720) { nativeGetHeight().coerceAtLeast(240) }

    fun getFrameBitmap(): Bitmap? {
        return try {
            val width = getWidth()
            val height = getHeight()
            val pixels = nativeGetFrameArgb(width, height) ?: return null
            if (pixels.size < width * height) return null
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            Log.e(TAG, "getFrameBitmap failed", e)
            null
        }
    }

    fun sendPointerEvent(x: Int, y: Int, button: Int): Int = safeNative("sendPointerEvent", -1) {
        nativeSendPointerEvent(x, y, button)
    }

    fun sendKeyEvent(keyCode: Int, down: Int): Int = safeNative("sendKeyEvent", -1) {
        nativeSendKeyEvent(keyCode, down)
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
