package com.rdp.sync.network

import android.util.Log

object RdpConnector {
    init {
        System.loadLibrary("rdpsync")
    }

    // Native JNI methods — renamed to avoid conflicts
    external fun nativeConnect(host: String, port: Int, username: String, password: String, domain: String): Int
    external fun nativeDisconnect(): Int
    external fun nativeGetSurfaceBytes(outputPtr: Long, outputSize: Long): Int
    external fun nativeSendPointerEvent(x: Int, y: Int, button: Int): Int
    external fun nativeSendKeyEvent(keyCode: Int, down: Int): Int
    external fun nativeSendClipboardText(text: String): Int
    external fun nativeGetWidth(): Int
    external fun nativeGetHeight(): Int

    private const val TAG = "RdpConnector"

    fun connectDevice(host: String, port: Int, username: String, password: String, domain: String): Boolean {
        return try {
            val result = nativeConnect(host, port, username, password, domain)
            Log.d(TAG, "Connect result: $result")
            result == 1
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed", e)
            false
        }
    }

    fun disconnect(): Int {
        return try {
            nativeDisconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed", e)
            -1
        }
    }

    fun getSurfaceBytes(outputPtr: Long = 0L, outputSize: Long = 0L): Int {
        return try {
            nativeGetSurfaceBytes(outputPtr, outputSize)
        } catch (e: Exception) {
            Log.e(TAG, "getSurfaceBytes failed", e)
            0
        }
    }

    fun getWidth(): Int {
        return try {
            nativeGetWidth()
        } catch (e: Exception) {
            Log.e(TAG, "getWidth failed", e)
            1280
        }
    }

    fun getHeight(): Int {
        return try {
            nativeGetHeight()
        } catch (e: Exception) {
            Log.e(TAG, "getHeight failed", e)
            720
        }
    }

    fun sendPointerEvent(x: Int, y: Int, button: Int): Int {
        return try {
            nativeSendPointerEvent(x, y, button)
        } catch (e: Exception) {
            Log.e(TAG, "sendPointerEvent failed", e)
            -1
        }
    }

    fun sendKeyEvent(keyCode: Int, down: Int): Int {
        return try {
            nativeSendKeyEvent(keyCode, down)
        } catch (e: Exception) {
            Log.e(TAG, "sendKeyEvent failed", e)
            -1
        }
    }
}
