package com.rdp.sync

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import com.rdp.sync.ui.MainActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.data.Device
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.screens.RdpConnectionScreen
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlin.math.min

/** Real xrdp/Xorg server on a disposable runner; no mocks stand in for pixels or input. */
@RunWith(AndroidJUnit4::class)
class RdpFixtureInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence by lazy { File(instrumentation.targetContext.getExternalFilesDir(null), "rdp-evidence").apply { mkdirs() } }
    private val measurements = JSONObject()
    private val latencySamples = mutableListOf<Long>()

    @Test fun realDesktopDisplayInputResizeAndWeakLink() {
        val enabled = InstrumentationRegistry.getArguments().getString("rdpFixture")
        assertEquals("This test requires the isolated CI fixture, never a user's host", "true", enabled)
        val preferences = instrumentation.targetContext.getSharedPreferences("session_preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("display_quality", "SHARP").putString("resolution_mode", "PROFILE").commit()
        val showing = mutableStateOf(true)
        val device = mutableStateOf(Device(id = 900001, name = "Isolated CI fixture", host = "10.0.2.2", port = 3389,
            username = "rdpfixture", password = "rdpsync-fixture-only", width = 1024, height = 768))
        val connectStarted = SystemClock.elapsedRealtime()
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { if (showing.value) RdpConnectionScreen(device.value) { showing.value = false } } } }
        try {
            waitFor("first real RDP frame", 60_000) { RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && fixtureReady() }
            measurements.put("connect_to_first_frame_ms", SystemClock.elapsedRealtime() - connectStarted)
            measurements.put("latency_method", "Android injected touch (40ms hold) to matching synthetic response color in UiAutomation screen capture; 100ms polling + screenshot overhead included. Not click-to-photon or physical handset/radio latency.")
            waitFor("configured profile resolution") { RdpConnector.getWidth() == 1024 && RdpConnector.getHeight() == 768 }
            compose.onNodeWithContentDescription("切换到触控板").assertExists()
            assertColor(40, 40, Color.RED)
            assertColor(136, 40, Color.GREEN)
            assertColor(232, 40, Color.BLUE)
            screenshot("01-direct-touch-profile")

            repeat(5) { tapAndObserve() }
            measurements.put("normal_touch_to_observed_remote_frame_ms", latencySamples.joinToString(","))
            val beforeText = fixtureState().getString("text")
            compose.runOnUiThread {
                val connection = desktop().onCreateInputConnection(EditorInfo())
                connection.setComposingText("a", 1)
                connection.setComposingText("ab", 1)
                connection.commitText("abc", 1)
                connection.deleteSurroundingText(1, 0)
                connection.commitText("\n", 1)
                connection.closeConnection()
            }
            waitFor("committed IME composition and deletion") { fixtureState().getString("text") == beforeText + "ab\n" }
            screenshot("02-ime-commit-and-backspace")

            // Held-button lifecycle reaches the real X server (not a synthesized click-only API).
            val beforeDrag = fixtureState()
            assertEquals(0, RdpConnector.sendMouseButton(360, 240, 1, true))
            repeat(8) { RdpConnector.sendPointerEvent(380 + it * 8, 260 + it * 4, 0); SystemClock.sleep(20) }
            assertEquals(0, RdpConnector.sendMouseButton(450, 290, 1, false))
            waitFor("held mouse drag and release") {
                val state = fixtureState()
                state.getInt("drag_moves") > beforeDrag.getInt("drag_moves") && state.getInt("releases") > beforeDrag.getInt("releases")
            }
            val beforeWheel = fixtureState().getInt("wheels")
            RdpConnector.sendWheelEvent(400, 300, 120)
            waitFor("wheel event") { fixtureState().getInt("wheels") > beforeWheel }

            // This gate MUST fail if the test server/build cannot negotiate DISP. A request
            // return value is not evidence that the actual decoded desktop changed.
            waitFor("real display-control caps", 20_000) { RdpConnector.nativeGetDisplayControlState() == 2 }
            selectResolution("1280 × 720")
            waitFor("server-applied 1280x720 frame", 20_000) { actualSize(1280, 720) && fixtureSize(1280, 720) }
            assertColor(40, 40, Color.RED)
            screenshot("03-negotiated-hd")
            selectResolution("适应窗口")
            waitFor("portrait fit-window framebuffer", 20_000) { RdpConnector.getHeight() > RdpConnector.getWidth() }
            val beforeRotateFrame = RdpConnector.nativeGetFrameId()
            val beforeRotateGeneration = RdpConnector.nativeGetTelemetry()[11]
            assertTrue("connected native generation must be present", beforeRotateGeneration > 0)
            val beforeRotateSessionId = fixtureState().getString("session_id")
            val beforeRotateDesktopPid = fixtureState().getInt("desktop_pid")
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor("live landscape resize without disconnect", 25_000) {
                RdpConnector.isConnected() && RdpConnector.getWidth() > RdpConnector.getHeight() && RdpConnector.nativeGetFrameId() > beforeRotateFrame
            }
            assertEquals("rotation must keep the same native RDP connection", beforeRotateGeneration, RdpConnector.nativeGetTelemetry()[11])
            measurements.put("rotation_preserved_native_generation", beforeRotateGeneration)
            assertEquals("rotation must preserve the X desktop session", beforeRotateSessionId, fixtureState().getString("session_id"))
            assertEquals("rotation must preserve the X desktop process", beforeRotateDesktopPid, fixtureState().getInt("desktop_pid"))
            measurements.put("rotation_preserved_desktop_session_id", beforeRotateSessionId)
            assertColor(232, 40, Color.BLUE)
            screenshot("04-landscape-live-resize")
            tapAndObserve()
            measurements.put("native_telemetry_normal", RdpConnector.nativeGetTelemetry().joinToString(","))
            measurements.put("display_control_state", RdpConnector.nativeGetDisplayControlState())
            measurements.put("rdpei_status", RdpConnector.getDiag())

            // A changed session must return to touch-first even after trackpad use.
            compose.onNodeWithContentDescription("切换到触控板").performClick()
            compose.onNodeWithContentDescription("切换到直接触摸").assertExists()
            compose.runOnUiThread { showing.value = false }
            waitFor("clean teardown") { !RdpConnector.isConnected() }
            waitFor("disposable X session terminated", 20_000) { !fixtureReady() }
            preferences.edit().putString("resolution_mode", "PROFILE").commit()
            compose.runOnUiThread { device.value = device.value.copy(id = 900002, port = 3390); showing.value = true }
            waitFor("weak-link session frame", 90_000) { RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && fixtureReady() }
            compose.onNodeWithContentDescription("切换到触控板").assertExists()
            assertNotEquals("explicit new session gets a new native generation", beforeRotateGeneration, RdpConnector.nativeGetTelemetry()[11])
            compose.onNodeWithContentDescription("显示与网络设置").performClick()
            compose.onNode(hasAnySibling(hasText("自适应网络与渲染负载")) and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("完成").performClick()
            assertTrue(preferences.getBoolean("adaptive_quality", false))
            measurements.put("adaptive_opt_in", true)
            val weakSamples = mutableListOf<Long>()
            repeat(3) { weakSamples += tapAndObserve(15_000) }
            measurements.put("weak_link_touch_to_observed_remote_frame_ms", weakSamples.joinToString(","))
            measurements.put("weak_link_profile", "loopback proxy: 80ms per 16KiB read per direction, 1MiB/s per direction; not a radio simulation")
            measurements.put("native_telemetry_weak", RdpConnector.nativeGetTelemetry().joinToString(","))
            measurements.put("adaptive_state_weak", RdpConnector.pollDisplayStatus().adaptiveReason)
            measurements.put("adaptive_quality_weak", RdpConnector.pollDisplayStatus().quality.name)
            screenshot("05-weak-link-live-session")
            measurements.put("fixture_final_state", fixtureState())

            // A server/session without DISP must keep working at its negotiated size.
            compose.runOnUiThread { showing.value = false }
            waitFor("weak-link teardown") { !RdpConnector.isConnected() }
            waitFor("weak-link X session terminated", 20_000) { !fixtureReady() }
            assertTrue(RdpConnector.connectDevice("10.0.2.2", 3389, "rdpfixture", "rdpsync-fixture-only", "",
                width = 800, height = 600, enableTouchInput = false, compatibilityMode = true))
            waitFor("compatibility framebuffer", 60_000) { RdpConnector.isConnected() && actualSize(800, 600) && RdpConnector.nativeGetFrameId() > 0 }
            assertEquals(-1, RdpConnector.nativeGetDisplayControlState())
            assertEquals(-2, RdpConnector.nativeRequestResize(1024, 768, 100))
            SystemClock.sleep(1200)
            assertTrue("unsupported resize must not drop the session", RdpConnector.isConnected())
            assertTrue("unsupported resize must not masquerade as applied", actualSize(800, 600))
            measurements.put("unsupported_resize_preserved_session", true)
            RdpConnector.disconnect()
        } finally {
            runCatching { screenshot("99-before-final-teardown") }
            File(evidence, "measurements.json").writeText(measurements.toString(2))
            File(evidence, "diagnostics.txt").writeText(RdpConnector.getDiag())
            compose.runOnUiThread { showing.value = false }
            RdpConnector.disconnect()
            waitFor("final teardown", 20_000) { !RdpConnector.isConnected() }
        }
    }

    private fun selectResolution(label: String) {
        compose.onNodeWithContentDescription("显示与网络设置").performClick()
        compose.onNode(hasAnySibling(hasText(label)) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("完成").performClick()
    }

    private fun desktop(): RdpDesktopView {
        fun find(view: View): RdpDesktopView? {
            if (view is RdpDesktopView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return requireNotNull(find(compose.activity.window.decorView)) { "No attached remote desktop View" }
    }

    private fun tapAndObserve(timeout: Long = 10_000): Long {
        val before = fixtureState().getInt("clicks")
        val position = IntArray(2)
        var x = 0f; var y = 0f
        compose.runOnUiThread {
            val view = desktop()
            view.getLocationOnScreen(position)
            val fit = min(view.width.toFloat() / RdpConnector.getWidth(), view.height.toFloat() / RdpConnector.getHeight())
            x = position[0] + (view.width - RdpConnector.getWidth() * fit) / 2 + 400 * fit
            y = position[1] + (view.height - RdpConnector.getHeight() * fit) / 2 + 300 * fit
        }
        val start = SystemClock.elapsedRealtime()
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.sendPointerSync(event)
            event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
        waitFor("Android touch reaches server and returns painted response", timeout) {
            fixtureState().getInt("clicks") > before && colorAt(40, 140)?.let { closeColor(it, if ((before + 1) % 2 == 1) Color.rgb(68, 221, 136) else Color.rgb(221, 68, 136)) } == true
        }
        return (SystemClock.elapsedRealtime() - start).also { latencySamples += it }
    }

    private fun actualSize(w: Int, h: Int) = RdpConnector.getWidth() == w && RdpConnector.getHeight() == h
    private fun fixtureSize(w: Int, h: Int) = fixtureState().let { it.getInt("width") == w && it.getInt("height") == h }
    private fun fixtureReady(): Boolean = try { fixtureState().optBoolean("ready") } catch (_: Exception) { false }
    private fun fixtureState(): JSONObject = Socket().use { socket ->
        socket.connect(InetSocketAddress("10.0.2.2", 8765), 2000)
        socket.soTimeout = 2000
        socket.getOutputStream().write("GET /state HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
        val response = socket.getInputStream().bufferedReader().readText()
        JSONObject(response.substringAfter("\r\n\r\n"))
    }
    private fun colorAt(x: Int, y: Int): Int? {
        val position = IntArray(2)
        var sx = -1; var sy = -1
        compose.runOnUiThread {
            val view = desktop()
            view.getLocationOnScreen(position)
            val fit = min(view.width.toFloat() / RdpConnector.getWidth(), view.height.toFloat() / RdpConnector.getHeight())
            sx = (position[0] + (view.width - RdpConnector.getWidth() * fit) / 2 + x * fit).toInt()
            sy = (position[1] + (view.height - RdpConnector.getHeight() * fit) / 2 + y * fit).toInt()
        }
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        return try { if (sx in 0 until shot.width && sy in 0 until shot.height) shot.getPixel(sx, sy) else null } finally { shot.recycle() }
    }
    private fun closeColor(actual: Int, expected: Int) = abs(Color.red(actual) - Color.red(expected)) <= 6 && abs(Color.green(actual) - Color.green(expected)) <= 6 && abs(Color.blue(actual) - Color.blue(expected)) <= 6
    private fun assertColor(x: Int, y: Int, expected: Int) = waitFor("RGB pixel $x,$y") { colorAt(x, y)?.let { closeColor(it, expected) } == true }
    private fun screenshot(name: String) {
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(evidence, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (predicate()) return } catch (error: Throwable) { lastError = error }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $description; status=${RdpConnector.getStatus()}; diag=${RdpConnector.getDiag()}", lastError)
    }
}
