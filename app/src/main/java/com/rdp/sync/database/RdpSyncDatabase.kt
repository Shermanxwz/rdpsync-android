package com.rdp.sync.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rdp.sync.data.Device
import com.rdp.sync.data.DeviceDao

/**
 * RdpSync 数据库
 */
@Database(entities = [Device::class], version = 1, exportSchema = false)
abstract class RdpSyncDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    
    companion object {
        @Volatile
        private var INSTANCE: RdpSyncDatabase? = null
        
        fun getDatabase(context: Context): RdpSyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RdpSyncDatabase::class.java,
                    "rdpsync_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
