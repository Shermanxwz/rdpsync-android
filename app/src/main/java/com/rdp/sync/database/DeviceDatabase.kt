package com.rdp.sync.database

import androidx.room.*
import com.rdp.sync.data.Device

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY name ASC")
    suspend fun getAllDevices(): List<Device>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getDeviceById(id: Long): Device?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: Device): Long

    @Update
    suspend fun updateDevice(device: Device)

    @Delete
    suspend fun deleteDevice(device: Device)
}
