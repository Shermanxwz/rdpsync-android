package com.rdp.sync

import android.app.Application
import com.rdp.sync.network.RdpConnector

class RdpSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Tell FreeRDP's OpenSSL where to find the legacy provider (for MD4/NTLM)
        RdpConnector.nativeSetLibDir(applicationInfo.nativeLibraryDir)
    }
}
