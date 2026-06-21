package com.rdp.sync.network

import android.util.Log

/**
 * RDP 连接器 - 使用 IronRDP JNI
 * 在安卓 App 内直接渲染远程桌面
 */
object RdpConnector {
    init {
        // 加载 IronRDP 原生库
        try {
            System.loadLibrary("ironrdp")
            Log.d("RdpConnector", "IronRDP library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("RdpConnector", "Failed to load IronRDP: ${e.message}")
        }
    }

    // JNI 接口（待实现 native 方法）
    external fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String
    ): Int

    external fun disconnect()

    external fun getWidth(): Int
    external fun getHeight(): Int

    external fun getSurfaceBytes(): ByteArray

    external fun sendKeyEvent(keyCode: Int, down: Boolean)

    external fun sendPointerEvent(x: Int, y: Int, button: Int)

    external fun sendClipboardText(text: String)
}
