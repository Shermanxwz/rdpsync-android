package com.rdp.sync.manager

import com.rdp.sync.di.AppModule
import com.rdp.sync.network.WebDavSyncService

/**
 * 同步管理器
 * 协调本地数据库和 WebDAV 同步
 */
class SyncManager(
    private val webDavSyncService: WebDavSyncService
) {
    companion object {
        const val TAG = "SyncManager"
    }
    
    /**
     * 同步设备列表到 WebDAV
     */
    fun syncToDeviceList(): Boolean {
        return try {
            // Implementation will be added later
            true
        } catch (e: Exception) {
            println("[$TAG] Sync failed: ${e.message}")
            false
        }
    }
}
