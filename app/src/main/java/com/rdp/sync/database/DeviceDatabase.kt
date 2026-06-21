package com.rdp.sync.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rdp.sync.data.Device
import com.rdp.sync.data.DeviceDao

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
