package com.rdp.sync.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rdp.sync.data.Device
import com.rdp.sync.di.AppModule

abstract class DeviceDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    
    companion object {
        @Volatile
        private var INSTANCE: DeviceDatabase? = null
        
        fun getInstance(context: Context): DeviceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext as android.app.Application,
                    DeviceDatabase::class.java,
                    "rdpsync_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
