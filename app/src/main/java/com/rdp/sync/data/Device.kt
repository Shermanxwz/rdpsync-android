package com.rdp.sync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * RDP 设备实体类
 * 存储远程 Windows 机器的连接信息
 */
@Entity(tableName = "devices")
data class Device(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                    // 设备名称（如 "办公电脑"）
    val host: String,                   // IP 地址或域名
    val port: Int = 3389,               // RDP 端口，默认 3389
    val username: String = "",          // 登录用户名
    val password: String = "",          // 登录密码
    val domain: String = "",            // 域（可选）
    val width: Int = 0,                 // 分辨率宽度 (0=自动)
    val height: Int = 0,                // 分辨率高度 (0=自动)
    val colorDepth: Int = 24,           // 颜色深度
    val fullscreen: Boolean = false,    // 是否全屏
    val sound: Boolean = true,          // 是否播放声音
    val clipboard: Boolean = true,      // 是否启用剪贴板
    val notes: String = "",             // 备注
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_PORT = 3389
    }
}
