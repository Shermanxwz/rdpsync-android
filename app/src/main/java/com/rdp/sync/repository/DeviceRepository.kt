package com.rdp.sync.repository

import com.rdp.sync.data.Device
import com.rdp.sync.database.DeviceDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DeviceRepository(private val database: DeviceDatabase) {
    private val dao = database.deviceDao()

    fun getAllDevices(): Flow<List<Device>> = dao.getAllDevices()

    suspend fun getDeviceById(id: Long): Device? = dao.getDeviceById(id)

    suspend fun insertDevice(device: Device): Long =
        withContext(Dispatchers.IO) { dao.insertDevice(device) }

    suspend fun updateDevice(device: Device) =
        withContext(Dispatchers.IO) { dao.updateDevice(device) }

    suspend fun deleteDevice(device: Device) =
        withContext(Dispatchers.IO) { dao.deleteDevice(device) }
}
