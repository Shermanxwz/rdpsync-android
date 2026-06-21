package com.rdp.sync.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rdp.sync.data.Device

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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
