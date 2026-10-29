package com.rdp.sync

import android.app.Application
import com.rdp.sync.network.RdpConnector

class RdpSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android does not supply HOME; initialize private WinPR/FreeRDP runtime paths
        // before any session context is allocated. Never use nativeLibraryDir as writable data.
        RdpConnector.initialize(this)
    }
}
