package com.rdp.sync.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 设备数据访问对象
 */
@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY name ASC")
    fun getAllDevices(): Flow<List<Device>>
    
    @Query("SELECT * FROM devices WHERE id = :deviceId")
    suspend fun getDeviceById(deviceId: Long): Device?
    
    @Query("SELECT * FROM devices WHERE name = :name LIMIT 1")
    suspend fun getDeviceByName(name: String): Device?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: Device): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevices(devices: List<Device>): List<Long>
    
    @Update
    suspend fun updateDevice(device: Device): Int
    
    @Delete
    suspend fun deleteDevice(device: Device): Int
    
    @Query("DELETE FROM devices")
    suspend fun deleteAllDevices(): Int

    @Transaction
    suspend fun replaceAll(devices: List<Device>) {
        deleteAllDevices()
        insertDevices(devices)
    }
}
