package com.rdp.sync.repository

import com.rdp.sync.data.Device
import com.rdp.sync.database.RdpSyncDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class DeviceRepository(private val database: RdpSyncDatabase) {
    private val dao = database.deviceDao()

    fun getAllDevices(): Flow<List<Device>> = flow {
        emit(dao.getAllDevices())
    }

    suspend fun getDeviceById(id: Long): Device? = dao.getDeviceById(id)

    suspend fun insertDevice(device: Device): Long =
        withContext(Dispatchers.IO) { dao.insertDevice(device) }

    suspend fun updateDevice(device: Device) =
        withContext(Dispatchers.IO) { dao.updateDevice(device) }

    suspend fun deleteDevice(device: Device) =
        withContext(Dispatchers.IO) { dao.deleteDevice(device) }

    companion object {
        fun create(database: RdpSyncDatabase): DeviceRepository {
            return DeviceRepository(database)
        }
    }
}
