package com.rdp.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.network.RdpConnector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A real Android/WinPR/FreeRDP context gate, independent of server availability. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeInstrumentedTest {
    @Test fun appPrivateRuntimeCreatesAndFreesRealContextWithoutConnecting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val initialized = RdpConnector.initialize(context)
        assertTrue("native runtime initialization: ${RdpConnector.getDiag()}", initialized)
        assertTrue("repeated initialization must be idempotent", RdpConnector.initialize(context))
        val homeRoot = context.noBackupFilesDir.canonicalFile
        val cacheRoot = context.cacheDir.canonicalFile
        val home = File(homeRoot, "freerdp").canonicalFile
        val cache = File(cacheRoot, "freerdp").canonicalFile
        assertTrue("native HOME must exist in the app-private no-backup directory", home.isDirectory)
        assertTrue("native cache must exist in the app-private cache directory", cache.isDirectory)
        assertTrue(home.path.startsWith(homeRoot.path + File.separator))
        assertTrue(cache.path.startsWith(cacheRoot.path + File.separator))
        assertFalse("smoke test must not connect to any server", RdpConnector.isConnected())
        assertNull(RdpConnector.certificateChallenge())
        val contextCreated = RdpConnector.nativeCheckRuntime()
        assertTrue("actual FreeRDP context allocation/free: ${RdpConnector.getDiag()}", contextCreated)
        assertFalse(RdpConnector.isConnected())
        assertNull(RdpConnector.certificateChallenge())
    }
}
