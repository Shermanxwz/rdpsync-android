package com.rdp.sync

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import com.rdp.sync.ui.MainActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.data.Device
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.rdp.ViewportTransform
import com.rdp.sync.ui.screens.RdpConnectionScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlin.math.max

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
        val nativeInitialized = RdpConnector.initialize(instrumentation.targetContext)
        assertTrue("app-private native runtime initialization: ${RdpConnector.getDiag()}", nativeInitialized)
        val expectedFingerprint = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureFingerprint"))
        assertTrue(expectedFingerprint.matches(Regex("[a-f0-9]{64}")))
        instrumentation.targetContext.getSharedPreferences("certificate_trust_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val authBeforeFirstTrust = controlState().getInt("auth_requests")
        val preferences = instrumentation.targetContext.getSharedPreferences("session_preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("display_quality", "SHARP").putString("resolution_mode", "PROFILE").commit()
        val showing = mutableStateOf(true)
        val device = mutableStateOf(Device(id = 900001, name = "Isolated CI fixture", host = "10.0.2.2", port = 3389,
            username = "rdpfixture", password = "rdpsync-fixture-only", width = 1024, height = 768))
        val connectStarted = SystemClock.elapsedRealtime()
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { if (showing.value) RdpConnectionScreen(device.value) { showing.value = false } } } }
        try {
            approveCertificate(expectedFingerprint, changed = false, authRequestsBefore = authBeforeFirstTrust)
            waitFor("first real RDP frame", 60_000) { RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && fixtureReady() }
            assertTrue("auth counter must observe a real login after explicit trust", controlState().getInt("auth_requests") > authBeforeFirstTrust)
            measurements.put("no_auth_before_first_trust", true)
            measurements.put("connect_to_first_frame_ms", SystemClock.elapsedRealtime() - connectStarted)
            measurements.put("initial_display_control_state", RdpConnector.nativeGetDisplayControlState())
            assertFalse("normal fixture session must not silently downgrade to compatibility mode: ${RdpConnector.getDiag()}",
                RdpConnector.getDiag().contains("connection=compatibility-retry"))
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
            selectQuality("均衡")
            assertTrue("manual profile resolution remains exact when adaptive is off", actualSize(1024, 768))
            selectQuality("清晰")
            selectResolution("1280 × 720")
            waitFor("server-applied 1280x720 frame", 20_000) { actualSize(1280, 720) && fixtureSize(1280, 720) }
            assertColor(40, 40, Color.RED)
            // A retained old top-left corner plus new dimensions cannot pass:
            // this visible pixel is outside the original 1024-wide framebuffer.
            assertColor(1200, 600, Color.rgb(17, 34, 51))
            measurements.put("hd_new_frame_region_exact_rgb", true)
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
            val compactWidth = RdpConnector.getWidth()
            val compactHeight = RdpConnector.getHeight()
            val compactBounds = desktopBounds()
            measurements.put("landscape_compact_viewport", compactBounds)
            assertTrue("compact landscape desktop must occupy at least 72% of the full window: $compactBounds",
                compactBounds.getDouble("height_fraction_of_window") >= 0.72)
            assertTrue("remote pixels must stay below visible status/cutout overlays: $compactBounds",
                compactBounds.getInt("top") >= compactBounds.getInt("window_top") + compactBounds.getInt("safe_top"))
            assertTrue("remote pixels must avoid visible side system/cutout overlays: $compactBounds",
                compactBounds.getInt("left") >= compactBounds.getInt("window_left") + compactBounds.getInt("safe_left") &&
                    compactBounds.getInt("left") + compactBounds.getInt("width") <=
                    compactBounds.getInt("window_left") + compactBounds.getInt("window_width") - compactBounds.getInt("safe_right"))
            assertTrue("remote pixels must stay above visible bottom system/cutout overlays: $compactBounds",
                compactBounds.getInt("top") + compactBounds.getInt("height") <=
                    compactBounds.getInt("window_top") + compactBounds.getInt("window_height") - compactBounds.getInt("safe_bottom"))
            listOf("切换到触控板", "显示或隐藏键盘", "显示与网络设置", "断开", "展开会话工具").forEach { label ->
                val control = hasContentDescription(label) and hasClickAction()
                compose.onAllNodes(control).assertCountEquals(1)
                compose.onNode(control).assertIsDisplayed()
            }
            val compactFrame = RdpConnector.nativeGetFrameId()
            compose.onNodeWithContentDescription("展开会话工具").performClick()
            waitFor("expanded landscape controls cause a real smaller framebuffer", 20_000) {
                RdpConnector.isConnected() && RdpConnector.getHeight() < compactHeight &&
                    RdpConnector.nativeGetFrameId() > compactFrame && fixtureSize(RdpConnector.getWidth(), RdpConnector.getHeight())
            }
            assertEquals(beforeRotateGeneration, RdpConnector.nativeGetTelemetry()[11])
            assertEquals(beforeRotateSessionId, fixtureState().getString("session_id"))
            assertEquals(beforeRotateDesktopPid, fixtureState().getInt("desktop_pid"))
            assertColor(40, 40, Color.RED)
            measurements.put("landscape_expanded_viewport", desktopBounds())
            screenshot("04b-landscape-expanded-controls")
            val expandedFrame = RdpConnector.nativeGetFrameId()
            compose.onNodeWithContentDescription("收起会话工具").performClick()
            waitFor("compact landscape framebuffer restored without reconnect", 20_000) {
                RdpConnector.isConnected() && actualSize(compactWidth, compactHeight) && fixtureSize(compactWidth, compactHeight) &&
                    RdpConnector.nativeGetFrameId() > expandedFrame
            }
            assertEquals(beforeRotateGeneration, RdpConnector.nativeGetTelemetry()[11])
            assertEquals(beforeRotateSessionId, fixtureState().getString("session_id"))
            assertEquals(beforeRotateDesktopPid, fixtureState().getInt("desktop_pid"))
            assertColor(compactWidth - 80, compactHeight - 80, Color.rgb(17, 34, 51))
            measurements.put("landscape_controls_preserved_session", true)
            screenshot("04c-landscape-compact-restored")
            exerciseImePreservingRemoteSession(compactWidth, compactHeight, beforeRotateGeneration,
                beforeRotateSessionId, beforeRotateDesktopPid, desktopBounds().getInt("height"))
            tapAndObserve()
            measurements.put("native_telemetry_normal", RdpConnector.nativeGetTelemetry().joinToString(","))
            measurements.put("display_control_state", RdpConnector.nativeGetDisplayControlState())
            measurements.put("rdpei_status", RdpConnector.getDiag())

            // A changed session must return to touch-first even after trackpad use.
            compose.onNodeWithContentDescription("切换到触控板").performClick()
            compose.onNodeWithContentDescription("切换到直接触摸").assertExists()
            compose.runOnUiThread { showing.value = false }
            waitFor("clean teardown") { !RdpConnector.isConnected() }
            waitFor("disposable X session terminated", 90_000) { !fixtureReady() }
            preferences.edit().putString("resolution_mode", "PROFILE").commit()
            // Reenter the same profile/authority: the explicitly approved pin must be reused.
            compose.runOnUiThread { showing.value = true }
            waitFor("same-pin reconnect without a new trust prompt", 60_000) { RdpConnector.isConnected() && fixtureReady() }
            compose.onNodeWithText("确认服务器证书").assertDoesNotExist()
            compose.onNodeWithText("服务器证书已改变").assertDoesNotExist()
            compose.onNodeWithContentDescription("切换到触控板").assertExists()
            measurements.put("same_pin_reconnect_without_prompt", true)
            compose.runOnUiThread { showing.value = false }
            waitFor("same-pin teardown") { !RdpConnector.isConnected() }
            waitFor("same-pin X session terminated", 90_000) { !fixtureReady() }
            val beforeWeakTrust = controlState().getInt("auth_requests")
            compose.runOnUiThread { device.value = device.value.copy(id = 900002, port = 3390); showing.value = true }
            approveCertificate(expectedFingerprint, changed = false, authRequestsBefore = beforeWeakTrust)
            waitFor("weak-link session frame", 90_000) { RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && fixtureReady() }
            compose.onNodeWithContentDescription("切换到触控板").assertExists()
            assertNotEquals("explicit new session gets a new native generation", beforeRotateGeneration, RdpConnector.nativeGetTelemetry()[11])
            compose.onNodeWithContentDescription("显示与网络设置").performClick()
            val adaptive = hasText("自适应网络与渲染负载") and hasClickAction()
            compose.onAllNodes(adaptive).assertCountEquals(1)
            compose.onNode(adaptive).performScrollTo().assertIsOff().performClick().assertIsOn()
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
            waitFor("weak-link X session terminated", 90_000) { !fixtureReady() }
            // Rotate only this disposable runner's synthetic certificate, with no live session.
            val beforeChangedTrust = controlState().getInt("auth_requests")
            val changedFingerprint = controlRequest("/rotate-certificate", "POST").getString("fingerprint")
            assertNotEquals(expectedFingerprint, changedFingerprint)
            compose.runOnUiThread { device.value = device.value.copy(id = 900001, port = 3389); showing.value = true }
            waitFor("changed-certificate challenge", 30_000) { RdpConnector.certificateChallenge()?.reason == "changed" && dialogExists("服务器证书已改变") }
            compose.onNodeWithText("服务器证书已改变").assertExists()
            compose.onNodeWithText(expectedFingerprint).assertExists()
            compose.onNodeWithText(changedFingerprint).assertExists()
            assertEquals(beforeChangedTrust, controlState().getInt("auth_requests"))
            assertFalse("changed certificate must not authenticate", fixtureReady())
            screenshot("06-changed-certificate-blocked")
            compose.onNodeWithText("取消连接").performClick()
            compose.mainClock.advanceTimeBy(1500)
            SystemClock.sleep(500)
            assertEquals("cancel must not submit credentials", beforeChangedTrust, controlState().getInt("auth_requests"))
            assertFalse(RdpConnector.isConnected())
            compose.runOnUiThread { showing.value = false }
            waitFor("cancelled challenge teardown") { !RdpConnector.isConnected() }
            compose.runOnUiThread { showing.value = true }
            approveCertificate(changedFingerprint, changed = true, authRequestsBefore = beforeChangedTrust)
            waitFor("explicitly approved rotated certificate frame", 60_000) { RdpConnector.isConnected() && fixtureReady() }
            assertTrue(controlState().getInt("auth_requests") > beforeChangedTrust)
            measurements.put("changed_certificate_blocked_until_explicit_reapproval", true)
            assertColor(40, 40, Color.RED)
            screenshot("07-rotated-certificate-approved")
            compose.runOnUiThread { showing.value = false }
            waitFor("rotated-certificate teardown") { !RdpConnector.isConnected() }
            waitFor("rotated-certificate X session terminated", 90_000) { !fixtureReady() }
            assertTrue(RdpConnector.connectDevice("10.0.2.2", 3389, "rdpfixture", "rdpsync-fixture-only", "",
                width = 800, height = 600, enableTouchInput = false, compatibilityMode = true, expectedFingerprint = changedFingerprint))
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
            // AGP/UTP uninstalls the app after tests. Shell-owned shared test output survives it.
            // UiAutomation executes argv, not a shell script: do not use ; or shell quoting.
            shellOutput("mkdir -p /sdcard/Download/rdpsync-evidence")
            shellOutput("cp -R ${evidence.absolutePath}/. /sdcard/Download/rdpsync-evidence/")
            assertTrue("test evidence must survive UTP uninstall",
                shellOutput("ls /sdcard/Download/rdpsync-evidence").contains("measurements.json"))
            compose.runOnUiThread { showing.value = false }
            RdpConnector.disconnect()
            waitFor("final teardown", 20_000) { !RdpConnector.isConnected() }
        }
    }

    private fun shellOutput(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun approveCertificate(fingerprint: String, changed: Boolean, authRequestsBefore: Int) {
        val title = if (changed) "服务器证书已改变" else "确认服务器证书"
        waitFor("certificate challenge before authentication", 45_000) { RdpConnector.certificateChallenge()?.fingerprint == fingerprint && dialogExists(title) }
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(fingerprint).performScrollTo().assertIsDisplayed()
        assertEquals("credentials must not reach the session manager before approval", authRequestsBefore, controlState().getInt("auth_requests"))
        assertFalse("no authenticated desktop may exist before approval", fixtureReady())
        val button = if (changed) "替换指纹并重连" else "保存指纹并重连"
        compose.onNodeWithText(button).assertIsNotEnabled()
        compose.onNodeWithText("我已通过独立渠道核对 SHA-256 指纹").performScrollTo().performClick()
        compose.onNodeWithText(button).assertIsEnabled().performClick()
    }

    private fun dialogExists(title: String): Boolean = compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    private fun controlState(): JSONObject = controlRequest("/state")
    private fun controlRequest(path: String, method: String = "GET"): JSONObject = Socket().use { socket ->
        socket.connect(InetSocketAddress("10.0.2.2", 8766), 2000)
        socket.soTimeout = 10_000
        socket.getOutputStream().write("$method $path HTTP/1.0\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".toByteArray())
        JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
    }

    private fun selectResolution(label: String) {
        compose.onNodeWithContentDescription("显示与网络设置").performClick()
        val control = hasText(label) and hasClickAction()
        compose.onAllNodes(control).assertCountEquals(1)
        compose.onNode(control).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("完成").performClick()
    }

    private fun selectQuality(label: String) {
        compose.onNodeWithContentDescription("显示与网络设置").performClick()
        val control = hasText(label) and hasClickAction()
        compose.onAllNodes(control).assertCountEquals(1)
        compose.onNode(control).performScrollTo().performClick().assertIsSelected()
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

    private fun desktopBounds(): JSONObject {
        val result = JSONObject()
        compose.runOnUiThread {
            val view = desktop()
            val location = IntArray(2)
            val rootLocation = IntArray(2)
            view.getLocationOnScreen(location)
            view.rootView.getLocationOnScreen(rootLocation)
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(view))
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            result.put("left", location[0]).put("top", location[1])
                .put("width", view.width).put("height", view.height)
                .put("window_left", rootLocation[0]).put("window_top", rootLocation[1])
                .put("window_width", view.rootView.width).put("window_height", view.rootView.height)
                .put("safe_left", safe.left).put("safe_top", safe.top)
                .put("safe_right", safe.right).put("safe_bottom", safe.bottom)
                .put("ime_bottom", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                .put("height_fraction_of_window", view.height.toDouble() / view.rootView.height.coerceAtLeast(1))
        }
        return result
    }

    private fun imeVisible(): Boolean {
        var visible = false
        compose.runOnUiThread {
            visible = ViewCompat.getRootWindowInsets(desktop())?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun exerciseImePreservingRemoteSession(width: Int, height: Int, generation: Long,
                                                   sessionId: String, desktopPid: Int, fullViewHeight: Int) {
        // This class runs only with the explicit disposable-fixture argument.
        // Emulators report a hardware keyboard; still exercise a real software IME.
        val previousImeSetting = shellOutput("settings get secure show_ime_with_hard_keyboard").trim()
        require(previousImeSetting in setOf("0", "1", "null"))
        try {
            // Establish a real recent input target near the probes before local IME cropping.
            tapAndObserve(remoteX = 400f, remoteY = 40f)
            val beforeViewport = viewportSnapshot()
            val beforePatchSpan = observedPatchSpan(40, 40, Color.RED)
            measurements.put("ime_pre_scale", beforeViewport.scale).put("ime_pre_red_patch_span_px", beforePatchSpan)
            shellOutput("settings put secure show_ime_with_hard_keyboard 1")
            tapAccessibleControl("显示或隐藏键盘")
            waitFor("software IME visible and local desktop viewport reduced") {
                imeVisible() && desktopBounds().getInt("height") < fullViewHeight
            }
            measurements.put("landscape_ime_viewport", desktopBounds())
            val observations = JSONArray()
            measurements.put("ime_display_poll_observations", observations)
            var previousStatus = RdpConnector.pollDisplayStatus()
            var observedPolls = 0
            var changed = false
            val started = SystemClock.elapsedRealtime()
            waitFor("IME preserves remote geometry across three actual display polls", 10_000) {
                val state = fixtureState()
                val status = RdpConnector.pollDisplayStatus()
                val nativeGeneration = RdpConnector.nativeGetTelemetry()[11]
                changed = changed || !RdpConnector.isConnected() || !actualSize(width, height) ||
                    state.getInt("width") != width || state.getInt("height") != height ||
                    nativeGeneration != generation || state.getString("session_id") != sessionId ||
                    state.getInt("desktop_pid") != desktopPid || !imeVisible() ||
                    status.requested?.let { it.width != width || it.height != height } == true
                // updateDisplay publishes a new immutable status on each session-loop
                // poll. Observe those real polls, rather than calling it from the test.
                if (status !== previousStatus) {
                    previousStatus = status
                    observedPolls++
                    observations.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("client_width", RdpConnector.getWidth()).put("client_height", RdpConnector.getHeight())
                        .put("server_width", state.getInt("width")).put("server_height", state.getInt("height"))
                        .put("native_generation", nativeGeneration).put("requested", status.requested?.toString())
                        .put("ever_changed", changed))
                }
                observedPolls >= 3 && SystemClock.elapsedRealtime() - started >= 2_000
            }
            assertFalse("IME must not resize/reconnect the remote session, even transiently: $observations", changed)
            assertColor(40, 40, Color.RED)
            val imeViewport = viewportSnapshot()
            val imeBounds = desktopBounds()
            val availableHeight = imeBounds.getInt("window_height") - imeBounds.getInt("safe_top") -
                max(imeBounds.getInt("safe_bottom"), imeBounds.getInt("ime_bottom"))
            assertTrue("IME must leave a usable unobscured area: $imeBounds", availableHeight > 0)
            assertTrue("Typing chrome must not consume the remaining desktop area: $imeBounds",
                imeBounds.getInt("height").toDouble() / availableHeight >= 0.90)
            assertEquals("IME crop must preserve the pre-IME fit/zoom scale", beforeViewport.scale, imeViewport.scale, 0.000001f)
            val imePatchSpan = observedPatchSpan(40, 40, Color.RED)
            assertTrue("Actual received patch must retain readable pixel scale, not just report it",
                abs(imePatchSpan - beforePatchSpan) <= 2)
            measurements.put("ime_readable_scale", imeViewport.scale).put("ime_red_patch_span_px", imePatchSpan)
                .put("ime_unobscured_height_px", availableHeight)
                .put("ime_desktop_fraction_of_unobscured_area", imeBounds.getInt("height").toDouble() / availableHeight)
            listOf("显示或隐藏键盘", "会话工具", "断开").forEach { label ->
                val control = hasContentDescription(label) and hasClickAction()
                compose.onAllNodes(control).assertCountEquals(1)
                compose.onNode(control).assertIsDisplayed()
            }
            val clicksBeforeTools = fixtureState().getInt("clicks")
            tapAccessibleControl("会话工具")
            listOf("Esc", "Tab", "Ctrl+C", "Ctrl+V", "Ctrl+Alt+Del", "显示与网络设置").forEach { label ->
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            }
            val escapeBefore = fixtureState().getJSONArray("keys").toString()
            compose.onNodeWithText("Esc").performScrollTo().assertIsDisplayed()
            tapObservedAppPopupItem("Esc")
            waitFor("IME tools shortcut reaches server and popup restores desktop focus") {
                imeVisible() && fixtureState().getJSONArray("keys").toString() != escapeBefore &&
                    fixtureState().getJSONArray("keys").toString().contains("Escape") &&
                    desktopHasWindowFocus()
            }
            assertEquals("Overlay/tools touches must not leak pointer clicks to the desktop", clicksBeforeTools, fixtureState().getInt("clicks"))
            tapAndObserve(remoteX = 400f, remoteY = 40f)
            assertTrue("Direct touch must not dismiss the software keyboard", imeVisible())
            exerciseActualSoftwareKeyboard()
            assertTrue(actualSize(width, height) && fixtureSize(width, height))
            assertEquals(generation, RdpConnector.nativeGetTelemetry()[11])
            assertEquals(sessionId, fixtureState().getString("session_id"))
            assertEquals(desktopPid, fixtureState().getInt("desktop_pid"))
            measurements.put("ime_tools_touch_and_software_typing_passed", true)
            screenshot("04d-landscape-ime-visible")
            val clicksBeforeHide = fixtureState().getInt("clicks")
            tapAccessibleControl("显示或隐藏键盘")
            waitFor("IME hidden and compact local viewport restored") {
                !imeVisible() && desktopBounds().getInt("height") == fullViewHeight
            }
            assertEquals("Overlay hide touch must not leak to the desktop", clicksBeforeHide, fixtureState().getInt("clicks"))
            assertTrue(actualSize(width, height))
            assertTrue(fixtureSize(width, height))
            assertEquals(generation, RdpConnector.nativeGetTelemetry()[11])
            assertEquals(sessionId, fixtureState().getString("session_id"))
            assertEquals(desktopPid, fixtureState().getInt("desktop_pid"))
            assertColor(width - 80, height - 80, Color.rgb(17, 34, 51))
            measurements.put("ime_preserved_remote_geometry_and_session", true)
            screenshot("04e-landscape-ime-restored")
        } finally {
            runCatching { compose.runOnUiThread { desktop().hideKeyboard() } }
            shellOutput(if (previousImeSetting == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $previousImeSetting")
        }
    }

    private fun tapAndObserve(timeout: Long = 10_000, remoteX: Float = 400f, remoteY: Float = 300f): Long {
        val attempt = JSONObject()
        val attempts = measurements.optJSONArray("touch_attempts") ?: JSONArray().also { measurements.put("touch_attempts", it) }
        attempts.put(attempt)
        measurements.put("rgb_assertion", "exact 8-bit RGB channels at uniform interior pixels")
        // Compose semantics can disappear before Android disposes the dialog's
        // input window. Do not inject a measured gesture into that transition.
        // A native frame counter also does not establish a drawn View bitmap.
        waitFor("visible, attached and focused remote desktop before touch") {
            var viewReady = false
            compose.runOnUiThread {
                val view = desktop()
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                viewReady = view.isAttachedToWindow && view.isShown && view.hasWindowFocus() && view.width > 0 && view.height > 0
                attempt.put("attached", view.isAttachedToWindow).put("shown", view.isShown)
                    .put("window_focus", view.hasWindowFocus()).put("view_left", location[0]).put("view_top", location[1])
                    .put("view_width", view.width).put("view_height", view.height)
            }
            val dialogAbsent = !dialogExists("显示与网络")
            val painted = viewReady && dialogAbsent && RdpConnector.isConnected() &&
                colorAt(40, 40)?.let { sameColor(it, Color.RED) } == true &&
                colorAt(136, 40)?.let { sameColor(it, Color.GREEN) } == true &&
                colorAt(232, 40)?.let { sameColor(it, Color.BLUE) } == true
            attempt.put("settings_dialog_absent", dialogAbsent).put("visible_exact_rgb", painted)
                .put("native_generation", RdpConnector.nativeGetTelemetry()[11]).put("native_frame_id", RdpConnector.nativeGetFrameId())
                .put("frame_width", RdpConnector.getWidth()).put("frame_height", RdpConnector.getHeight())
            painted
        }
        val before = fixtureState().getInt("clicks")
        val point = requireNotNull(remoteScreenPoint(remoteX, remoteY)) { "Touch target is outside the actual cropped viewport" }
        attempt.put("screen_x", point.x).put("screen_y", point.y).put("remote_x", remoteX).put("remote_y", remoteY)
            .put("server_clicks_before", before)
        val start = SystemClock.elapsedRealtime()
        injectSingleTap(point.x, point.y)
        waitFor("Android touch reaches server and returns painted response", timeout) {
            val state = fixtureState()
            state.getInt("clicks") > before && receivedPointMatches(state.optJSONArray("last_press"), remoteX, remoteY) &&
                receivedPointMatches(state.optJSONArray("last_release"), remoteX, remoteY) &&
                colorAt(40, 140)?.let { sameColor(it, if ((before + 1) % 2 == 1) Color.rgb(68, 221, 136) else Color.rgb(221, 68, 136)) } == true
        }
        return (SystemClock.elapsedRealtime() - start).also {
            latencySamples += it
            val state = fixtureState()
            attempt.put("touch_to_observed_response_ms", it).put("server_clicks_after", state.getInt("clicks"))
                .put("server_last_press", state.getJSONArray("last_press")).put("server_last_release", state.getJSONArray("last_release"))
        }
    }

    private fun receivedPointMatches(point: JSONArray?, x: Float, y: Float): Boolean =
        point != null && point.length() == 2 && abs(point.getDouble(0) - x) <= 1 && abs(point.getDouble(1) - y) <= 1

    private fun tapObservedAppPopupItem(label: String) {
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            var target: Rect? = null
            waitFor("nonfocusable app popup exposes one real $label hit target") {
                val matches = linkedMapOf<String, Rect>()
                for (window in automation.windows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = window.root ?: continue
                    try {
                        if (root.packageName?.toString() != instrumentation.targetContext.packageName) continue
                        fun visit(node: AccessibilityNodeInfo, depth: Int) {
                            if (depth > 30) return
                            val rect = Rect().also(node::getBoundsInScreen)
                            if (node.text?.toString() == label && node.isVisibleToUser && rect.width() > 0 && rect.height() > 0) {
                                matches["${window.id}:${rect.toShortString()}"] = rect
                            }
                            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                                try { visit(child, depth + 1) } finally { child.recycle() }
                            }
                        }
                        visit(root, 0)
                    } finally { root.recycle() }
                }
                target = matches.values.singleOrNull()
                target != null
            }
            val rect = requireNotNull(target)
            measurements.put("ime_tools_popup_hit", JSONObject().put("label", label).put("screen_bounds", rect.toShortString()))
            screenshot("04d1-ime-tools-popup")
            // The menu deliberately does not take IME focus. Enumerate actual app
            // windows, not only the active window or the main activity's origin.
            injectSingleTap(rect.exactCenterX(), rect.exactCenterY())
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun tapAccessibleControl(label: String) {
        val node = compose.onNode(hasContentDescription(label) and hasClickAction()).assertIsDisplayed().fetchSemanticsNode()
        val bounds = node.boundsInWindow
        val screen = IntArray(2); val window = IntArray(2)
        compose.runOnUiThread {
            compose.activity.window.decorView.getLocationOnScreen(screen)
            compose.activity.window.decorView.getLocationInWindow(window)
        }
        val x = screen[0] - window[0] + bounds.center.x
        val y = screen[1] - window[1] + bounds.center.y
        val events = measurements.optJSONArray("actual_toolbar_taps") ?: JSONArray().also { measurements.put("actual_toolbar_taps", it) }
        events.put(JSONObject().put("label", label).put("screen_x", x).put("screen_y", y))
        injectSingleTap(x, y)
    }

    private fun viewportSnapshot(): ViewportTransform.Snapshot {
        var snapshot: ViewportTransform.Snapshot? = null
        compose.runOnUiThread { snapshot = desktop().viewportSnapshot() }
        return requireNotNull(snapshot)
    }

    private fun desktopHasWindowFocus(): Boolean {
        var focused = false
        compose.runOnUiThread { focused = desktop().hasWindowFocus() }
        return focused
    }

    private fun remoteScreenPoint(x: Float, y: Float): ViewportTransform.Point? {
        var point: ViewportTransform.Point? = null
        compose.runOnUiThread {
            val view = desktop()
            val snapshot = view.viewportSnapshot()
            if (snapshot.remoteWidth == RdpConnector.getWidth() && snapshot.remoteHeight == RdpConnector.getHeight()) {
                val local = snapshot.toScreen(x, y)
                if (local.x >= 0 && local.x < view.width && local.y >= 0 && local.y < view.height) {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    point = ViewportTransform.Point(location[0] + local.x, location[1] + local.y)
                }
            }
        }
        return point
    }

    private fun observedPatchSpan(x: Int, y: Int, expected: Int): Int {
        val point = requireNotNull(remoteScreenPoint(x.toFloat(), y.toFloat()))
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            val sx = point.x.toInt(); val sy = point.y.toInt()
            assertTrue("Uniform patch center must be an exact received color", sameColor(shot.getPixel(sx, sy), expected))
            var left = sx; var right = sx
            while (left > 0 && sameColor(shot.getPixel(left - 1, sy), expected)) left--
            while (right + 1 < shot.width && sameColor(shot.getPixel(right + 1, sy), expected)) right++
            right - left + 1
        } finally { shot.recycle() }
    }

    private fun injectSingleTap(x: Float, y: Float, inputMethodWindow: Boolean = false) {
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                if (inputMethodWindow) {
                    // The real IME belongs to a different UID. UiAutomation's
                    // system input injection can target its observed window.
                    assertTrue("System must accept the single observed IME key event",
                        instrumentation.uiAutomation.injectInputEvent(event, true))
                } else instrumentation.sendPointerSync(event)
            } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
    }

    private fun exerciseActualSoftwareKeyboard() {
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val before = fixtureState().getString("text")
        val keyEvidence = JSONArray()
        measurements.put("actual_software_keyboard_keys", keyEvidence)
        try {
            tapObservedImeKey("a", keyEvidence)
            // Some IMEs keep a composing character local until Enter. Record, do not
            // fabricate a commit or send a replacement/native key as a fallback.
            measurements.put("software_keyboard_interim_text", fixtureState().getString("text"))
            tapObservedImeKey("Enter", keyEvidence)
            val expected = before + "a\n"
            val length = expected.codePointCount(0, expected.length)
            val colors = intArrayOf(Color.rgb(85, 204, 204), Color.rgb(204, 170, 85), Color.rgb(153, 85, 221))
            waitFor("real software keyboard commits a/newline and paints distinct remote text feedback") {
                fixtureState().getString("text") == expected &&
                    colorAt(328, 40)?.let { sameColor(it, colors[length % 3]) } == true
            }
            measurements.put("software_keyboard_committed_text", expected)
                .put("software_keyboard_text_patch_exact_rgb", true)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun tapObservedImeKey(label: String, evidence: JSONArray) {
        var target: Rect? = null
        var targetLabel = ""
        var imeBounds = Rect()
        val observed = JSONArray()
        waitFor("actual input-method accessibility key $label") {
            target = null
            for (window in instrumentation.uiAutomation.windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val bounds = Rect().also(window::getBoundsInScreen)
                val root = window.root ?: continue
                fun visit(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 30 || target != null) return
                    val text = node.text?.toString()?.trim().orEmpty()
                    val description = node.contentDescription?.toString()?.trim().orEmpty()
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (observed.length() < 150 && (text.isNotBlank() || description.isNotBlank())) {
                        observed.put(JSONObject().put("text", text.take(80)).put("description", description.take(80))
                            .put("bounds", rect.toShortString()))
                    }
                    val matches = if (label == "a") text == label || description == label
                        else text.equals(label, true) || description.equals(label, true)
                    if (matches && node.isVisibleToUser && rect.width() > 0 && rect.height() > 0 && bounds.contains(rect)) {
                        target = rect; targetLabel = if (text.isNotEmpty()) text else description; imeBounds = bounds
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { visit(child, depth + 1) } finally { child.recycle() }
                    }
                }
                try { visit(root, 0) } finally { root.recycle() }
            }
            measurements.put("ime_accessibility_observed_nodes", observed)
            target != null
        }
        val rect = requireNotNull(target)
        evidence.put(JSONObject().put("requested", label).put("observed", targetLabel)
            .put("key_bounds", rect.toShortString()).put("ime_window_bounds", imeBounds.toShortString())
            .put("injection", "UiAutomation.injectInputEvent(sync=true)")
            .put("injected_at_ms", SystemClock.elapsedRealtime()))
        injectSingleTap(rect.exactCenterX(), rect.exactCenterY(), inputMethodWindow = true)
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
        val point = remoteScreenPoint(x.toFloat(), y.toFloat()) ?: return null
        val sx = point.x.toInt(); val sy = point.y.toInt()
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        return try { if (sx in 0 until shot.width && sy in 0 until shot.height) shot.getPixel(sx, sy) else null } finally { shot.recycle() }
    }
    private fun sameColor(actual: Int, expected: Int) = Color.red(actual) == Color.red(expected) && Color.green(actual) == Color.green(expected) && Color.blue(actual) == Color.blue(expected)
    private fun assertColor(x: Int, y: Int, expected: Int) = waitFor("exact RGB pixel $x,$y") { colorAt(x, y)?.let { sameColor(it, expected) } == true }
    private fun screenshot(name: String) {
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(evidence, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            // Compose's test frame clock is independent of Thread.sleep/real network time.
            // Pump recomposition/effects and then let Android perform the measured layout.
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            try { if (predicate()) return } catch (error: Throwable) { lastError = error }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $description; status=${RdpConnector.getStatus()}; diag=${RdpConnector.getDiag()}", lastError)
    }
}
