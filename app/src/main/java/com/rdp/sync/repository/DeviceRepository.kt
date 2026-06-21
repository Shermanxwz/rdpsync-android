package com.rdp.sync.repository

import com.rdp.sync.data.Device
import com.rdp.sync.database.DeviceDao
import kotlinx.coroutines.flow.Flow

/**
 * 设备数据仓库
 * 管理设备数据的本地存储和网络同步
 */
class DeviceRepository(
    private val deviceDao: DeviceDao
) {
    fun getAllDevices(): Flow<List<Device>> {
        return deviceDao.getAllDevices()
    }
    
    suspend fun insertDevice(device: Device) {
        deviceDao.insert(device)
    }
    
    suspend fun updateDevice(device: Device) {
        deviceDao.update(device)
    }
    
    suspend fun deleteDevice(device: Device) {
        deviceDao.delete(device)
    }
    
    suspend fun getDeviceById(id: String): Device? {
        return deviceDao.getDeviceById(id)
    }
}
