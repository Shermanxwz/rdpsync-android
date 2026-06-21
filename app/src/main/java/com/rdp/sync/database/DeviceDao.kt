package com.rdp.sync.database

import androidx.room.*
import com.rdp.sync.data.Device

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: Device)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devices: List<Device>)
    
    @Update
    suspend fun update(device: Device)
    
    @Delete
    suspend fun delete(device: Device)
    
    @Query("SELECT * FROM devices ORDER BY name ASC")
    fun getAllDevices(): kotlinx.coroutines.flow.Flow<List<Device>>
    
    @Query("SELECT * FROM devices WHERE id = :deviceId")
    suspend fun getDeviceById(deviceId: String): Device?
    
    @Query("DELETE FROM devices")
    suspend fun deleteAll()
}
