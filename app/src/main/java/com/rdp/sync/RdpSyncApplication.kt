package com.rdp.sync

import android.app.Application
import com.rdp.sync.network.RdpConnector

class RdpSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Retain the JNI initialization entry point; WinPR supplies its tested internal MD4 path.
        RdpConnector.nativeSetLibDir(applicationInfo.nativeLibraryDir)
    }
}
