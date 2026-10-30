package com.rdp.sync

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.data.Device
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.ui.MainActivity
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.screens.RdpConnectionScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/** Native GNOME/Mutter/PipeWire fixture. Never xrdp, GDM, or a user's desktop. */
@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class GnomeFixtureInstrumentedTest {
    // Compose1.7.2 defaults to UnconfinedTestDispatcher: external IO completion
    // may recompose Dialog/layout on that worker through its test interceptor.
    // Queue effects until the existing UI-thread test-clock pump instead.
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = StandardTestDispatcher())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence by lazy { File(instrumentation.targetContext.getExternalFilesDir(null), "gnome-evidence").apply { mkdirs() } }
    private val measurements = JSONObject()
    private lateinit var decodedColors: JSONObject

    @Test fun nativeGnomeRdpPixelsInputAndDisplayControl() {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("Explicit isolated fixture argument required", "true", args.getString("gnomeFixture"))
        val mode = requireNotNull(args.getString("gnomeFixtureMode"))
        require(mode in setOf("headless-extend", "screen-share-mirror"))
        val calibration = JSONObject(String(Base64.decode(requireNotNull(args.getString("gnomePixelCalibration")), Base64.DEFAULT), Charsets.UTF_8))
        assertEquals(2, calibration.getInt("schema"))
        assertEquals(9, calibration.getInt("codec"))
        assertEquals("gnome46-flat-patches-codec9", calibration.getString("scope"))
        assertEquals(2, calibration.getInt("max_channel_error"))
        assertTrue(calibration.getString("server_version").startsWith("GNOME Remote Desktop 46."))
        assertEquals(mode, calibration.getString("mode"))
        assertEquals("97eff6ddfbbfaa8e42a126d362686bd001ff8bdc", calibration.getString("source_revision"))
        decodedColors = calibration.getJSONObject("colors")
        val requiredColors = listOf(0xff0000, 0x00ff00, 0x0000ff, 0x55cccc, 0xccaa55, 0x9955dd, 0x44dd88, 0xdd4488, 0x112233)
        assertEquals(requiredColors.size, decodedColors.length())
        requiredColors.forEach { source ->
            val observed = decodedColors.getJSONArray(source.toString())
            assertTrue(observed.length() > 0)
            for (i in 0 until observed.length()) {
                val decoded = observed.getInt(i)
                assertTrue(decoded in 0..0xffffff)
                assertTrue(withinSourceBound(source, decoded))
            }
        }
        measurements.put("pixel_calibration", calibration)
        val fingerprint = requireNotNull(args.getString("gnomeFixtureFingerprint"))
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(RdpConnector.initialize(instrumentation.targetContext))
        instrumentation.targetContext.getSharedPreferences("certificate_trust_v1", Context.MODE_PRIVATE).edit().clear().commit()
        instrumentation.targetContext.getSharedPreferences("session_preferences", Context.MODE_PRIVATE).edit().clear()
            .putString("resolution_mode", "PROFILE").putString("display_quality", "SHARP").commit()
        val showing = mutableStateOf(true)
        val device = Device(id = 910001, name = "Native GNOME isolated fixture", host = "10.0.2.2", port = 33891,
            username = "rdpfixture", password = "rdpsync-fixture-only", width = 1024, height = 768)
        measurements.put("mode", mode).put("fixture_scope", "GNOME46 per-user native Wayland; not GDM Remote Login or Windows NLA")
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { if (showing.value) RdpConnectionScreen(device) { showing.value = false } } } }
        try {
            waitFor("native GNOME certificate challenge before frame", 45_000) {
                RdpConnector.certificateChallenge()?.fingerprint == fingerprint &&
                    compose.onAllNodesWithText("确认服务器证书").fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(RdpConnector.isConnected())
            assertEquals(0L, RdpConnector.nativeGetFrameId())
            compose.onNodeWithText(fingerprint).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("保存指纹并重连").assertIsNotEnabled()
            compose.onNodeWithText("我已通过独立渠道核对 SHA-256 指纹").performScrollTo().performClick()
            compose.onNodeWithText("保存指纹并重连").performClick()
            waitFor("real GNOME decoded desktop", 60_000) {
                RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && serverSize(1024, 768) && actualSize(1024, 768)
            }
            assertFalse("GNOME must not silently switch to legacy compatibility", RdpConnector.getDiag().contains("connection=compatibility-retry"))
            waitFor("actual software GFX Progressive command decoded") {
                val diag = RdpConnector.getDiag()
                diag.contains("gfx=software-ready") && diag.contains("gfx-last-codec=9") &&
                    Regex("gfx-decoded-commands=([0-9]+)").find(diag)?.groupValues?.get(1)?.toLongOrNull()?.let { it > 0 } == true
            }
            measurements.put("system_immersive_coachmark", acknowledgeSystemImmersiveCoachmark(compose.activity,
                { compose.mainClock.advanceTimeBy(32) }, { screenshot("00-system-immersive-coachmark") },
                { measurements.put("system_immersive_coachmark", it) }))
            assertEquals("GdkWaylandDisplay", state().getString("backend"))
            assertColor(40, 40, Color.RED)
            assertColor(136, 40, Color.GREEN)
            assertColor(232, 40, Color.BLUE)
            screenshot("01-native-gnome-frame")
            measurements.put("initial", state()).put("initial_diagnostics", RdpConnector.getDiag())
            val generation = RdpConnector.nativeGetTelemetry()[11]
            val sessionId = state().getString("session_id")
            val desktopPid = state().getInt("desktop_pid")
            tapAndObserve(400f, 300f)
            val textBefore = state().getString("text")
            compose.runOnUiThread {
                val ic = desktop().onCreateInputConnection(EditorInfo())
                ic.commitText("gnome\n", 1)
                ic.closeConnection()
            }
            waitFor("View input connection reaches native GNOME Wayland") { state().getString("text") == textBefore + "gnome\n" }
            val textLength = state().getString("text").length
            assertColor(336, 40, listOf(Color.rgb(85,204,204), Color.rgb(204,170,85), Color.rgb(153,85,221))[textLength % 3])
            measurements.put("view_input_connection_commit", true)
            // Distinct from actual Gboard-key coverage in the mandatory xrdp UI test.
            openSettings()
            compose.onNode(hasText("1280 × 720") and hasClickAction()).performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("完成").performClick()
            if (mode == "headless-extend") {
                waitFor("GNOME DISP caps and actual new framebuffer", 25_000) {
                    RdpConnector.nativeGetDisplayControlState() == 2 && actualSize(1280, 720) && serverSize(1280, 720)
                }
                assertColor(1200, 600, Color.rgb(17,34,51))
                assertSession(generation, sessionId, desktopPid)
                tapAndObserve(1100f, 500f)
                measurements.put("actual_disp_resize", true)
                screenshot("02-gnome-real-disp-resize")
                openSettings()
                compose.onNode(hasText("适应窗口") and hasClickAction()).performScrollTo().performClick()
                compose.onNodeWithText("完成").performClick()
                waitFor("portrait virtual monitor") { RdpConnector.getHeight() > RdpConnector.getWidth() && serverSize(RdpConnector.getWidth(), RdpConnector.getHeight()) }
                compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitFor("same-session native GNOME landscape resize", 25_000) {
                    RdpConnector.getWidth() > RdpConnector.getHeight() && serverSize(RdpConnector.getWidth(), RdpConnector.getHeight())
                }
                assertSession(generation, sessionId, desktopPid)
                assertColor(232, 40, Color.BLUE)
                measurements.put("rotation_preserved_native_and_wayland_session", true)
                screenshot("03-gnome-rotation-fullscreen")
            } else {
                // Mirroring an existing monitor cannot be advertised as a resized
                // virtual desktop. Observe real session-loop polls after the request.
                var previous = RdpConnector.pollDisplayStatus()
                var polls = 0
                val pollTimeline = JSONArray()
                val pollEvidence = JSONObject().put("wall_start_ms", SystemClock.elapsedRealtime())
                    .put("compose_start_ms", compose.mainClock.currentTime).put("fresh_polls", pollTimeline)
                measurements.put("mirror_poll_observation", pollEvidence)
                try {
                    waitFor("mirror mode retains its actual monitor after requested resize", 12_000) {
                        val current = RdpConnector.pollDisplayStatus()
                        if (current !== previous) {
                            previous = current; polls++
                            pollTimeline.put(JSONObject().put("wall_ms", SystemClock.elapsedRealtime())
                                .put("compose_ms", compose.mainClock.currentTime).put("status", current.toString()))
                        }
                        assertTrue("mirror geometry must remain actual physical/virtual primary size", actualSize(1024,768) && serverSize(1024,768))
                        polls >= 3
                    }
                } finally {
                    pollEvidence.put("wall_end_ms", SystemClock.elapsedRealtime())
                        .put("compose_end_ms", compose.mainClock.currentTime).put("observed_fresh_polls", polls)
                }
                assertSession(generation, sessionId, desktopPid)
                assertColor(40,40,Color.RED)
                tapAndObserve(400f,300f)
                measurements.put("mirror_fixed_monitor_fallback", true)
                    .put("mirror_display_state", RdpConnector.pollDisplayStatus().toString())
                screenshot("02-gnome-mirror-retained-frame")
            }
            measurements.put("final", state()).put("success", true)
        } finally {
            measurements.put("diagnostics", RdpConnector.getDiag())
                .put("telemetry", RdpConnector.nativeGetTelemetry().joinToString(","))
                .put("display_control_state", RdpConnector.nativeGetDisplayControlState())
            screenshot("99-final")
            File(evidence, "measurements.json").writeText(measurements.toString(2))
            shell("mkdir -p /sdcard/Download/rdpsync-gnome-evidence")
            shell("cp -R ${evidence.absolutePath}/. /sdcard/Download/rdpsync-gnome-evidence/")
            compose.runOnUiThread { showing.value = false }
            RdpConnector.disconnect()
        }
    }

    private fun openSettings() {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 15_000
        val observation = JSONObject().put("started_elapsed_ms", started)
        val observations = measurements.optJSONArray("settings_readiness") ?: JSONArray().also { measurements.put("settings_readiness", it) }
        observations.put(observation)
        compose.onNodeWithContentDescription("会话菜单").performTouchInput { click() }
        waitFor("actual GNOME session popup after one physical menu tap", (deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
            val count = compose.onAllNodesWithText("显示与网络设置").fetchSemanticsNodes().size
            observation.put("menu_node_count", count)
            assertTrue("Settings menu target must remain unique", count <= 1)
            count == 1
        }
        observation.put("popup_ready_elapsed_ms", SystemClock.elapsedRealtime())
        compose.onNodeWithText("显示与网络设置").performScrollTo().performClick()
        waitFor("actual display settings dialog", (deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
            compose.onAllNodesWithText("完成").fetchSemanticsNodes().size == 1
        }
        observation.put("dialog_ready_elapsed_ms", SystemClock.elapsedRealtime())
    }
    private fun assertSession(generation: Long, id: String, pid: Int) {
        assertTrue(RdpConnector.isConnected())
        assertEquals(generation, RdpConnector.nativeGetTelemetry()[11])
        assertEquals(id, state().getString("session_id"))
        assertEquals(pid, state().getInt("desktop_pid"))
    }
    private fun actualSize(w: Int, h: Int) = RdpConnector.getWidth() == w && RdpConnector.getHeight() == h
    private fun serverSize(w: Int, h: Int): Boolean = state().let { it.getBoolean("ready") && it.getInt("width") == w && it.getInt("height") == h && it.getInt("monitor_width") == w && it.getInt("monitor_height") == h }
    private fun state(): JSONObject = Socket().use { s ->
        s.connect(InetSocketAddress("10.0.2.2", 8768), 2000); s.soTimeout = 2000
        s.getOutputStream().write("GET /state HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
        JSONObject(s.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
    }
    private fun desktopOrNull(): RdpDesktopView? {
        fun find(v: View): RdpDesktopView? {
            if (v is RdpDesktopView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(compose.activity.window.decorView)
    }
    private fun desktop(): RdpDesktopView = requireNotNull(desktopOrNull())
    private fun colorAt(x: Int, y: Int): Int? {
        var point: Pair<Int,Int>? = null
        compose.runOnUiThread {
            val view = desktopOrNull() ?: return@runOnUiThread
            val mapped = view.viewportSnapshot().toScreen(x.toFloat(),y.toFloat())
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            if (mapped.x >= 0 && mapped.y >= 0 && mapped.x < view.width && mapped.y < view.height)
                point = Pair(loc[0] + mapped.x.toInt(),loc[1] + mapped.y.toInt())
        }
        val p = point ?: return null
        val b = instrumentation.uiAutomation.takeScreenshot() ?: return null
        return try { b.getPixel(p.first,p.second) and 0xffffff } finally { b.recycle() }
    }
    private fun withinSourceBound(source: Int, actual: Int) = listOf(0,8,16).all { shift ->
        abs(((source shr shift) and 255)-((actual shr shift) and 255)) <= 2
    }
    private fun decodedSourceMatches(source: Int, actual: Int?): Boolean {
        require(decodedColors.has((source and 0xffffff).toString()))
        val diag = RdpConnector.getDiag()
        return actual != null && diag.contains("gfx=software-ready") && diag.contains("gfx-last-codec=9") &&
            Regex("gfx-decoded-commands=([0-9]+)").find(diag)?.groupValues?.get(1)?.toLongOrNull()?.let { it > 0 } == true &&
            withinSourceBound(source, actual)
    }
    private fun assertColor(x: Int, y: Int, expected: Int) {
        // Empirical bound for these GNOME46 codec9 uniform patches only. Compare
        // independently to SOURCE, never add 2 to an already shifted reference.
        // All reference phase values/PNG hashes remain attached as evidence.
        waitFor("received GNOME codec9 pixel ($x,$y) within 2/channel of source RGB ${expected and 0xffffff}") {
            val actual = colorAt(x,y)
            val values = measurements.optJSONArray("raw_pixel_samples") ?: JSONArray().also { measurements.put("raw_pixel_samples",it) }
            if(values.length() < 100) values.put(JSONObject().put("x",x).put("y",y).put("actual",actual)
                .put("source_rgb",expected and 0xffffff).put("reference_phase_values",decodedColors.getJSONArray((expected and 0xffffff).toString())))
            decodedSourceMatches(expected, actual)
        }
    }
    private fun tapAndObserve(x: Float, y: Float) {
        waitFor("visible focused GNOME before single tap") { var focused=false; compose.runOnUiThread { focused=desktopOrNull()?.hasWindowFocus() == true }; focused && decodedSourceMatches(0xff0000, colorAt(40,40)) }
        val before = state().getInt("clicks")
        var sx=0f; var sy=0f
        compose.runOnUiThread { val v=desktop(); val p=v.viewportSnapshot().toScreen(x,y); val loc=IntArray(2); v.getLocationOnScreen(loc); assertTrue(p.x in 0f..v.width.toFloat() && p.y in 0f..v.height.toFloat()); sx=loc[0]+p.x; sy=loc[1]+p.y }
        val down=SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val ev=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,sx,sy,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { instrumentation.sendPointerSync(ev) } finally { ev.recycle() }
            if(action==MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
        waitFor("GNOME receives exact touch coordinates and paints response",15_000) {
            val s=state(); val p=s.optJSONArray("last_press"); val r=s.optJSONArray("last_release")
            s.getInt("clicks")==before+1 && p!=null && r!=null &&
                abs(p.getDouble(0)-x)<=1 && abs(p.getDouble(1)-y)<=1 && abs(r.getDouble(0)-x)<=1 && abs(r.getDouble(1)-y)<=1 &&
                decodedSourceMatches(if((before+1)%2==1) 0x44dd88 else 0xdd4488, colorAt(40,140))
        }
        measurements.put("last_touch",state())
    }
    private fun waitFor(reason: String, timeout: Long=15_000, predicate: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        var last: Throwable?=null
        while(SystemClock.elapsedRealtime()<deadline) {
            // Match the established real-RDP harness: advance Compose's independent
            // delay clock and drain Android layout, not merely real Thread.sleep.
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            try { if(predicate()) return } catch(e: java.io.IOException) { last=e }
            SystemClock.sleep(100)
        }
        throw AssertionError("$reason: ${RdpConnector.getDiag()}",last)
    }
    private fun screenshot(name: String) {
        val b=instrumentation.uiAutomation.takeScreenshot() ?: return
        try { File(evidence,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
