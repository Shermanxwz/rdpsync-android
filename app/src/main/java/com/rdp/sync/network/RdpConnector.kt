package com.rdp.sync.network

import com.rdp.sync.data.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RDP 连接器
 * 使用 xfreerdp 命令行工具进行 RDP 连接
 * WSL 环境下 xfreerdp 可直接调用
 */
class RdpConnector {
    
    companion object {
        private const val XFREERDP = "xfreerdp"
    }
    
    /**
     * 构建 RDP 连接参数
     * 生成 xfreerdp 命令行参数
     */
    fun buildRdpParams(device: Device): List<String> {
        val params = mutableListOf<String>()
        
        // 基本连接参数
        params.add("/v:${device.host}")
        if (device.port != Device.DEFAULT_PORT) {
            params.add("/port:${device.port}")
        }
        params.add("/u:${device.username}")
        if (device.password.isNotEmpty()) {
            params.add("/p:${device.password}")
        }
        if (device.domain.isNotEmpty()) {
            params.add("/d:${device.domain}")
        }
        
        // 显示参数
        if (device.fullscreen) {
            params.add("/fullscreen")
        } else if (device.width > 0 && device.height > 0) {
            params.add("/size:${device.width}x${device.height}")
        }
        
        // 音频
        if (device.sound) {
            params.add("/sound:sys:alsa")
        }
        
        // 剪贴板
        if (!device.clipboard) {
            params.add("/clipboard")
        }
        
        // 颜色深度
        if (device.colorDepth != 24) {
            params.add("/bpp:${device.colorDepth}")
        }
        
        // 其他常用参数
        params.add("/cert:ignore")  // 忽略证书
        params.add("/dynamic-resolution")  // 动态分辨率
        params.add("/wallpaper")  // 显示壁纸
        params.add("/themes")  // 启用主题
        
        return params
    }
    
    /**
     * 测试连接（ping 主机）
     */
    suspend fun testConnection(device: Device): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("ping", "-c", "3", device.host)
                .redirectErrorStream(true)
                .start()
            
            val exitCode = process.waitFor()
            Result.success(exitCode == 0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 启动 RDP 连接
     * 返回进程，可等待其退出
     */
    fun startRdpConnection(device: Device): Process {
        val params = buildRdpParams(device)
        val command = listOf(XFREERDP) + params
        
        println("Starting RDP: ${command.joinToString(" ")}")
        
        return ProcessBuilder(*command.toTypedArray())
            .redirectErrorStream(true)
            .start()
    }
    
    /**
     * 检查 xfreerdp 是否可用
     */
    fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder(XFREERDP, "/version")
                .redirectErrorStream(true)
                .start()
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        }
    }
}
