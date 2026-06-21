package com.rdp.sync.database

import android.content.Context
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

@Database(entities = [Device::class], version = 1, exportSchema = false)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    companion object {
        @Volatile
        private var INSTANCE: DeviceDatabase? = null

        fun getDatabase(context: Context): DeviceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DeviceDatabase::class.java,
                    "device_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
