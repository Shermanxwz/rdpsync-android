package com.rdp.sync

import android.app.Application

class RdpSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load JNI library on app startup
        System.loadLibrary("rdpsync")
    }
}
