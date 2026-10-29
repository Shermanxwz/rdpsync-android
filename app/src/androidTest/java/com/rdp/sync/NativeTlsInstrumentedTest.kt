package com.rdp.sync

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.network.RdpConnector
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/** Optional synthetic TLS boundary test; never claims login, desktop or full RDP success. */
@RunWith(AndroidJUnit4::class)
class NativeTlsInstrumentedTest {
    @Test fun realTlsRejectsUnknownAndChangedCertAndOnlyMatchingPinReachesMcs() {
        assumeTrue("Optional test requires the isolated synthetic X.224/TLS fixture",
            InstrumentationRegistry.getArguments().getString("rdpTlsFixture") == "true")
        assertTrue(RdpConnector.initialize(InstrumentationRegistry.getInstrumentation().targetContext))
        RdpConnector.disconnect()
        val initial = state()
        val firstPin = initial.getString("fingerprint")
        assertTrue(firstPin.matches(Regex("[a-f0-9]{64}")))
        val applicationBefore = initial.getInt("application_bytes")
        try {
            connect("")
            await("unknown certificate challenge") {
                RdpConnector.certificateChallenge()?.let { it.fingerprint == firstPin && it.reason == "untrusted" } == true
            }
            assertFalse(RdpConnector.isConnected())
            RdpConnector.disconnect()
            settled(initial.getInt("connections") + 1)
            assertEquals("untrusted TLS must not send MCS or login data", applicationBefore, state().getInt("application_bytes"))
            recordAttempts("unknown")
            val mcsBefore = state().getInt("mcs_boundaries")
            connect(firstPin)
            // FreeRDP may retry transport after this peer deliberately closes at
            // MCS. Require the positive boundary, not exactly one internal try.
            await("matching pin reaches the deliberately terminated MCS boundary") { state().getInt("mcs_boundaries") >= mcsBefore + 1 }
            assertNull("matching exact pin must not create a new trust challenge", RdpConnector.certificateChallenge())
            assertFalse("fixture never completes MCS or authenticates", RdpConnector.isConnected())
            assertEquals(0, state().getInt("unexpected_messages"))
            RdpConnector.disconnect()
            settled(initial.getInt("connections") + 2)
            recordAttempts("matching")
            val rotated = state("/rotate", "POST")
            val secondPin = rotated.getString("fingerprint")
            assertNotEquals(firstPin, secondPin)
            val bytesBeforeChanged = rotated.getInt("application_bytes")
            connect(firstPin, compatibility = true)
            await("changed certificate challenge in compatibility mode") {
                RdpConnector.certificateChallenge()?.let { it.fingerprint == secondPin && it.reason == "changed" } == true
            }
            assertFalse(RdpConnector.isConnected())
            RdpConnector.disconnect()
            settled(rotated.getInt("connections") + 1)
            assertEquals("changed cert must not send MCS or login data", bytesBeforeChanged, state().getInt("application_bytes"))
            recordAttempts("changed")
        } finally {
            RdpConnector.disconnect()
        }
    }

    private fun recordAttempts(phase: String) {
        val current = state()
        android.util.Log.i("RdpTlsFixture", "phase=$phase connections=${current.getInt("connections")} " +
            "settled=${current.getInt("settled")} application_bytes=${current.getInt("application_bytes")} " +
            "mcs_boundaries=${current.getInt("mcs_boundaries")}")
    }

    private fun settled(minimum: Int) = await("fixture closes rejected connection before byte assertion") {
        state().let { it.getInt("in_flight") == 0 && it.getInt("settled") >= minimum }
    }

    private fun connect(pin: String, compatibility: Boolean = false) {
        val started = RdpConnector.connectDevice("10.0.2.2", 33890, "", "", "", "10.0.2.2",
            width = 800, height = 600, enableTouchInput = false, compatibilityMode = compatibility,
            expectedFingerprint = pin)
        assertTrue("native connect start: ${RdpConnector.getStatus()} / ${RdpConnector.getDiag()}", started)
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        val raw = RdpConnector.nativeGetCertificateChallenge()
        val safeChallenge = raw?.let { "fields=${it.size}, generation=${it.getOrNull(0)}, reason=${it.getOrNull(7)}" } ?: "none"
        fail("$label: status=${RdpConnector.getStatus()}; diag=${RdpConnector.getDiag()}; challenge=$safeChallenge; fixture=${state()}")
    }

    private fun state(path: String = "/state", method: String = "GET"): JSONObject = Socket().use { socket ->
        socket.connect(InetSocketAddress("10.0.2.2", 8767), 2_000)
        socket.soTimeout = 5_000
        socket.getOutputStream().write("$method $path HTTP/1.0\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".toByteArray())
        JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
    }
}
