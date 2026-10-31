package com.rdp.sync

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
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
import com.rdp.sync.database.DeviceDatabase
import com.rdp.sync.ui.navigation.AppNavigation
import kotlinx.coroutines.runBlocking
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
    @get:Rule(order = 0) val appLocale = FixtureAppLocaleRule("rdpFixture")
    private val imeSetting = FixtureImeSettingRule()
    // Only the real keyboard test owns its prerequisite files. The no-network
    // navigation test never opens the IME and must not overwrite those receipts.
    @get:Rule(order = 1) val keyboardPrerequisite = org.junit.rules.TestRule { base, description ->
        if (description.methodName == "realDesktopDisplayInputResizeAndWeakLink")
            imeSetting.apply(base, description) else base
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence by lazy { File(instrumentation.targetContext.getExternalFilesDir(null), "rdp-evidence").apply { mkdirs() } }
    private val measurements = JSONObject()
    private val boundedScroll by lazy { BoundedFixtureScroll(compose, measurements) }
    private val latencySamples = mutableListOf<Long>()
    private var systemCoachmarkAcknowledged = false
    private var keyboardScreenshotPrefix = ""

    @Test fun actualNavigationBackRestoresSessionOrientationWithoutConnecting() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("rdpFixture"))
        val activity = compose.activity
        val original = activity.requestedOrientation
        val dao = DeviceDatabase.getDatabase(instrumentation.targetContext).deviceDao()
        // Empty host is rejected by production CertificateEndpoint before network
        // connect. The normal repository encrypts this fixture-only empty password.
        val device = Device(id = 900003, name = "Rotation navigation fixture", host = "", username = "", password = "")
        assertNull("Dedicated fixture row must not replace existing data", runBlocking { dao.getDeviceById(device.id) })
        val receipt = JSONObject().put("original_orientation", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            .put("scope", "Real AppNavigation list/detail/session route and system Back; invalid empty endpoint; no remote connection")
        var primaryFailure: Throwable? = null
        try {
            compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor("portrait before real route entry") { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            runBlocking { dao.insertDevice(device) }
            compose.runOnUiThread { activity.setContent { MaterialTheme { boundedScroll.Bind(); AppNavigation() } } }
            waitFor("fixture profile appears in actual device list") {
                compose.onAllNodesWithText(device.name).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText(device.name).performClick()
            compose.onNodeWithText("连接远程桌面").performClick()
            waitFor("invalid endpoint is rejected before network") {
                compose.onAllNodesWithText("连接地址或服务器名称无效，无法安全验证证书。").fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(RdpConnector.isConnected())
            // Even a not-connected session exposes the phone rotation action.
            tapAccessibleControl("会话菜单")
            boundedScroll.to(compose.onNodeWithText("旋转屏幕")).performClick()
            waitFor("actual invalid-endpoint route rotates phone") {
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            screenshot("navigation-rotation-before-back")
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            waitFor("real NavHost Back restores detail and previous requested orientation") {
                activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    compose.onAllNodesWithText("连接远程桌面").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithContentDescription("会话菜单").fetchSemanticsNodes().isEmpty()
            }
            assertSame(activity, compose.activity)
            assertFalse(RdpConnector.isConnected())
            receipt.put("restored_orientation", activity.requestedOrientation).put("passed", true)
            screenshot("navigation-rotation-back-restored")
        } catch (failure: Throwable) {
            primaryFailure = failure
            receipt.put("passed", false).put("failure", failure.stackTraceToString())
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(block: () -> Unit) {
                try { block() } catch (failure: Throwable) {
                    if (primaryFailure != null) primaryFailure!!.addSuppressed(failure)
                    else if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                }
            }
            cleanup { File(evidence, "navigation-back-rotation.json").writeText(receipt.toString(2)) }
            cleanup { shellOutput("mkdir -p /sdcard/Download/rdpsync-evidence") }
            cleanup { shellOutput("cp -R ${evidence.absolutePath}/. /sdcard/Download/rdpsync-evidence/") }
            // Dispose the route's owner before restoring the outer test policy,
            // including failure midway through an overridden session.
            cleanup {
                compose.runOnUiThread { activity.setContent {} }
                compose.waitForIdle()
            }
            cleanup { runBlocking { dao.deleteDevice(device) } }
            cleanup { compose.runOnUiThread { activity.requestedOrientation = original } }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    @Test fun realDesktopDisplayInputResizeAndWeakLink() {
        measurements.put("fixture_app_locale", appLocale.receipt)
        measurements.put("fixture_ime_setting", imeSetting.receipt)
        assertEquals("Explicit legacy fixture app language", "zh", compose.activity.resources.configuration.locales[0].language)
        measurements.put("fixture_activity_locales", compose.activity.resources.configuration.locales.toLanguageTags())
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
        val originalOrientation = compose.activity.requestedOrientation
        measurements.put("original_requested_orientation", originalOrientation)
        val originalBars = systemBarVisibility()
        measurements.put("original_system_bars", originalBars)
        val connectStarted = SystemClock.elapsedRealtime()
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { boundedScroll.Bind(); if (showing.value) RdpConnectionScreen(device.value) { showing.value = false } } } }
        try {
            approveCertificate(expectedFingerprint, changed = false, authRequestsBefore = authBeforeFirstTrust)
            waitFor("first real RDP frame", 60_000) { RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && fixtureReady() }
            assertTrue("auth counter must observe a real login after explicit trust", controlState().getInt("auth_requests") > authBeforeFirstTrust)
            measurements.put("no_auth_before_first_trust", true)
            measurements.put("connect_to_first_frame_ms", SystemClock.elapsedRealtime() - connectStarted)
            measurements.put("initial_display_control_state", RdpConnector.nativeGetDisplayControlState())
            assertFalse("normal fixture session must not silently downgrade to compatibility mode: ${RdpConnector.getDiag()}",
                RdpConnector.getDiag().contains("connection=compatibility-retry"))
            measurements.put("latency_method", "One public zero-duration stationary FINGER swipe (actual View DOWN/UP event-time span recorded and required below 500ms) to exact synthetic response color in UiAutomation screen capture; 100ms polling + screenshot overhead included. Not click-to-photon or physical handset/radio latency.")
            waitFor("configured profile resolution") { RdpConnector.getWidth() == 1024 && RdpConnector.getHeight() == 768 }
            measurements.put("system_immersive_coachmark", acknowledgeSystemImmersiveCoachmark(compose.activity,
                { compose.mainClock.advanceTimeBy(32) }, { screenshot("00-system-immersive-coachmark") },
                { measurements.put("system_immersive_coachmark", it) },
                allowAcknowledgement = !systemCoachmarkAcknowledged).also {
                    systemCoachmarkAcknowledged = systemCoachmarkAcknowledged || it.getBoolean("acknowledged")
                })
            assertFullscreenDefault("initial_fullscreen")
            assertClassicFixtureCodec()
            measurements.put("renderer_scope", "Explicit classic xrdp server build; normal production client auto-GFX unchanged; separate GNOME Progressive gate required")
            assertColor(40, 40, Color.RED)
            assertColor(136, 40, Color.GREEN)
            assertColor(232, 40, Color.BLUE)
            screenshot("01-direct-touch-fullscreen-profile")
            useBorderedToolbar()
            compose.onNodeWithContentDescription("切换到触控板").assertExists()

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
            tapAccessibleControl("进入全屏")
            rotateThroughSessionMenu(landscape = true, fullscreen = true, requireIme = false)
            val fullscreenRotationFrame = RdpConnector.nativeGetFrameId()
            val fullscreenRotationHeight = RdpConnector.getHeight()
            useBorderedToolbar()
            waitFor("live landscape resize without disconnect", 25_000) {
                RdpConnector.isConnected() && RdpConnector.getWidth() > RdpConnector.getHeight() &&
                    RdpConnector.getHeight() < fullscreenRotationHeight &&
                    RdpConnector.nativeGetFrameId() > fullscreenRotationFrame &&
                    fixtureSize(RdpConnector.getWidth(), RdpConnector.getHeight())
            }
            assertTrue("rotation produces new remote frame", RdpConnector.nativeGetFrameId() > beforeRotateFrame)
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
            tapAccessibleControl("进入全屏")
            waitFor("fullscreen increases actual remote viewport without reconnect", 20_000) {
                RdpConnector.getHeight() > compactHeight && fixtureSize(RdpConnector.getWidth(), RdpConnector.getHeight())
            }
            assertFullscreenDefault("landscape_fullscreen")
            assertEquals(beforeRotateGeneration, RdpConnector.nativeGetTelemetry()[11])
            assertEquals(beforeRotateSessionId, fixtureState().getString("session_id"))
            assertEquals(beforeRotateDesktopPid, fixtureState().getInt("desktop_pid"))
            val fullscreenWidth = RdpConnector.getWidth()
            val fullscreenHeight = RdpConnector.getHeight()
            // The bottom-right now intentionally contains the floating handle.
            // Prove pixels in the newly exposed bottom strip, not the overlay.
            val newRegionX = fullscreenWidth / 2
            val newRegionY = fullscreenHeight - 80
            assertTrue("Sample must be below the prior compact remote framebuffer", newRegionY >= compactHeight)
            measurements.put("fullscreen_new_region_exact_rgb", assertFullscreenBackgroundOutsideHandle(newRegionX, newRegionY)
                .put("prior_remote_height", compactHeight))
            screenshot("04f-landscape-true-fullscreen")
            repeat(2) {
                rotateThroughSessionMenu(landscape = false, fullscreen = true, requireIme = false)
                rotateThroughSessionMenu(landscape = true, fullscreen = true, requireIme = false)
            }
            exerciseFullscreenMenuBack(beforeRotateGeneration, beforeRotateSessionId, beforeRotateDesktopPid)
            exerciseTransientSystemBars(fullscreenWidth, fullscreenHeight, beforeRotateGeneration,
                beforeRotateSessionId, beforeRotateDesktopPid)
            exerciseImePreservingRemoteSession(fullscreenWidth, fullscreenHeight, beforeRotateGeneration,
                beforeRotateSessionId, beforeRotateDesktopPid, desktopBounds().getInt("height"))
            useBorderedToolbar()
            waitFor("opt-in toolbar restores actual compact resolution", 20_000) {
                actualSize(compactWidth, compactHeight) && fixtureSize(compactWidth, compactHeight)
            }
            assertEquals(beforeRotateGeneration, RdpConnector.nativeGetTelemetry()[11])
            assertEquals(beforeRotateSessionId, fixtureState().getString("session_id"))
            assertEquals(beforeRotateDesktopPid, fixtureState().getInt("desktop_pid"))
            measurements.put("fullscreen_toolbar_roundtrip_preserved_session", true)
            tapAndObserve()
            measurements.put("native_telemetry_normal", RdpConnector.nativeGetTelemetry().joinToString(","))
            measurements.put("display_control_state", RdpConnector.nativeGetDisplayControlState())
            measurements.put("rdpei_status", RdpConnector.getDiag())

            // A changed session must return to touch-first even after trackpad use.
            compose.onNodeWithContentDescription("切换到触控板").performClick()
            compose.onNodeWithContentDescription("切换到直接触摸").assertExists()
            tapAccessibleControl("断开")
            waitFor("disconnect restores previous orientation policy") {
                !showing.value && compose.activity.requestedOrientation == originalOrientation
            }
            measurements.put("disconnect_restored_requested_orientation", originalOrientation)
            waitFor("clean teardown") { !RdpConnector.isConnected() }
            waitFor("disposable X session terminated", 90_000) { !fixtureReady() }
            preferences.edit().putString("resolution_mode", "PROFILE").commit()
            // Reenter the same profile/authority: the explicitly approved pin must be reused.
            compose.runOnUiThread { showing.value = true }
            waitFor("same-pin reconnect without a new trust prompt", 60_000) { RdpConnector.isConnected() && fixtureReady() }
            compose.onNodeWithText("确认服务器证书").assertDoesNotExist()
            compose.onNodeWithText("服务器证书已改变").assertDoesNotExist()
            assertFullscreenDefault("same_pin_reentry_fullscreen")
            useBorderedToolbar()
            compose.onNodeWithContentDescription("切换到触控板").assertExists()
            measurements.put("same_pin_reconnect_without_prompt", true)
            compose.runOnUiThread { showing.value = false }
            waitFor("same-pin teardown") { !RdpConnector.isConnected() }
            waitFor("same-pin X session terminated", 90_000) { !fixtureReady() }
            val beforeWeakTrust = controlState().getInt("auth_requests")
            compose.runOnUiThread { device.value = device.value.copy(id = 900002, port = 3390); showing.value = true }
            approveCertificate(expectedFingerprint, changed = false, authRequestsBefore = beforeWeakTrust)
            waitFor("weak-link session frame", 90_000) { RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && fixtureReady() }
            assertFullscreenDefault("weak_reentry_fullscreen")
            useBorderedToolbar()
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
            assertClassicFixtureCodec()
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
            waitFor("leaving fullscreen restores original system bar visibility") {
                val now = systemBarVisibility()
                now.getBoolean("status") == originalBars.getBoolean("status") &&
                    now.getBoolean("navigation") == originalBars.getBoolean("navigation")
            }
            measurements.put("exit_restored_system_bars", systemBarVisibility())
            screenshot("08-exit-restored-system-bars")
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
        if (changed) {
            waitFor("certificate challenge before authentication", 45_000) { RdpConnector.certificateChallenge()?.fingerprint == fingerprint && dialogExists(title) }
        } else {
            // First-use SYSTEM education may arrive after setContent and cover the
            // trust dialog. Observe it throughout the ORIGINAL 45s trust budget,
            // without approving a certificate or requiring the dialog's parent decor to focus.
            val deadline = SystemClock.elapsedRealtime() + 45_000
            val phases = measurements.optJSONArray("untrusted_certificate_phases") ?: JSONArray().also {
                measurements.put("untrusted_certificate_phases", it)
            }
            assertTrue("Only the initial and deliberate weak-link untrusted phases are expected", phases.length() < 2)
            val phase = JSONObject().put("ordinal", phases.length()).put("phase", if (phases.length() == 0) "initial" else "weak_link")
                .put("auth_requests_before", authRequestsBefore).put("expected_fingerprint", fingerprint)
                .put("started_elapsed_ms", SystemClock.elapsedRealtime()).put("acknowledged_before_phase", systemCoachmarkAcknowledged)
            phases.put(phase)
            val readiness = JSONArray()
            phase.put("readiness", readiness)
            var nextObservation = 0L
            fun observeReadiness(phase: String): Boolean {
                val challenge = RdpConnector.certificateChallenge()
                val titleCount = compose.onAllNodesWithText(title).fetchSemanticsNodes().size
                val displayed = titleCount == 1 && runCatching { compose.onNodeWithText(title).assertIsDisplayed() }.isSuccess
                val focusedDialogs = focusedApplicationTitleWindows(title)
                val now = SystemClock.elapsedRealtime()
                val ready = challenge?.fingerprint == fingerprint && displayed && focusedDialogs.length() == 1
                if (readiness.length() < 100 && (now >= nextObservation || ready)) {
                    nextObservation = now + 500
                    readiness.put(JSONObject().put("phase", phase).put("elapsed_ms", now).put("wall_ms", System.currentTimeMillis())
                        .put("compose_clock_ms", compose.mainClock.currentTime).put("challenge_fingerprint", challenge?.fingerprint)
                        .put("challenge_generation", challenge?.generation).put("title_count", titleCount)
                        .put("title_displayed", displayed).put("focused_application_title_windows", focusedDialogs).put("ready", ready))
                }
                return ready
            }
            val result = acknowledgeSystemImmersiveCoachmark(compose.activity,
                { compose.mainClock.advanceTimeBy(100); observeReadiness("before_system_overlay_observation") },
                { screenshot("00-pretrust-${phase.getString("phase")}-system-immersive-coachmark") },
                { phase.put("system_immersive_coachmark", it) }, deadlineElapsedMs = deadline,
                readyWhenSystemOverlayAbsent = { observeReadiness("system_overlay_absent") },
                allowAcknowledgement = !systemCoachmarkAcknowledged)
            systemCoachmarkAcknowledged = systemCoachmarkAcknowledged || result.getBoolean("acknowledged")
            phase.put("completed_elapsed_ms", SystemClock.elapsedRealtime()).put("acknowledged_after_phase", systemCoachmarkAcknowledged)
        }
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(fingerprint).performScrollTo().assertIsDisplayed()
        assertEquals("credentials must not reach the session manager before approval", authRequestsBefore, controlState().getInt("auth_requests"))
        assertFalse("no authenticated desktop may exist before approval", fixtureReady())
        val button = if (changed) "替换指纹并重连" else "保存指纹并重连"
        compose.onNodeWithText(button).assertIsNotEnabled()
        compose.onNodeWithText("我已通过独立渠道核对 SHA-256 指纹").performScrollTo().performClick()
        compose.onNodeWithText(button).assertIsEnabled().performClick()
    }

    /** Read-only focus proof: a certificate Dialog, not the Activity decor, owns input. */
    private fun focusedApplicationTitleWindows(title: String): JSONArray {
        val matches = JSONArray()
        val windows = instrumentation.uiAutomation.windows
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused) continue
                val root = window.root ?: continue
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo) {
                    nodes += node
                    for (i in 0 until node.childCount) {
                        if (nodes.size >= 512) break
                        node.getChild(i)?.let(::visit)
                    }
                }
                visit(root)
                try {
                    if (root.packageName?.toString() != instrumentation.targetContext.packageName) continue
                    val titles = nodes.filter { it.windowId == window.id && it.isVisibleToUser && it.text?.toString() == title }
                    if (titles.size == 1) matches.put(JSONObject().put("window_id", window.id)
                        .put("window_type", window.type).put("root_package", root.packageName?.toString())
                        .put("focused", window.isFocused).put("title_bounds", Rect().also(titles.single()::getBoundsInScreen).toShortString()))
                } finally { nodes.forEach { it.recycle() } }
            }
        } finally { windows.forEach { it.recycle() } }
        return matches
    }

    private fun dialogExists(title: String): Boolean = compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    private fun controlState(): JSONObject = controlRequest("/state")
    private fun controlRequest(path: String, method: String = "GET"): JSONObject = Socket().use { socket ->
        socket.connect(InetSocketAddress("10.0.2.2", 8766), 2000)
        socket.soTimeout = 10_000
        socket.getOutputStream().write("$method $path HTTP/1.0\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".toByteArray())
        JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
    }

    private fun systemBarVisibility(): JSONObject {
        val result = JSONObject()
        compose.runOnUiThread {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            result.put("status", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                .put("navigation", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
                .put("window_system_ui_visibility", compose.activity.window.decorView.windowSystemUiVisibility)
                .put("decor_system_ui_visibility", compose.activity.window.decorView.systemUiVisibility)
                .put("window_flags", compose.activity.window.attributes.flags)
                .put("window_focus", compose.activity.window.decorView.hasWindowFocus())
        }
        return result
    }

    private fun exerciseFullscreenMenuBack(generation: Long, sessionId: String, pid: Int) {
        val before = fixtureState()
        tapAccessibleControl("会话菜单")
        compose.onNodeWithText("显示或隐藏键盘").assertIsDisplayed()
        compose.onNodeWithText("断开").assertIsDisplayed()
        assertTrue("Real system Back must be accepted", instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        waitFor("Back dismisses only the fullscreen popup") {
            compose.onAllNodesWithText("显示工具栏").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("会话菜单").fetchSemanticsNodes().size == 1 && desktopHasWindowFocus()
        }
        val previous = RdpConnector.pollDisplayStatus()
        waitFor("session loop remains active after Back") { RdpConnector.pollDisplayStatus() !== previous }
        val after = fixtureState()
        assertTrue(RdpConnector.isConnected())
        assertEquals(generation, RdpConnector.nativeGetTelemetry()[11])
        assertEquals(sessionId, after.getString("session_id"))
        assertEquals(pid, after.getInt("desktop_pid"))
        assertEquals("Menu/Back must not leak remote pointer input", before.getInt("clicks"), after.getInt("clicks"))
        assertEquals("Back must not become a remote key", before.getJSONArray("keys").toString(), after.getJSONArray("keys").toString())
        measurements.put("fullscreen_menu_back_dismissed_without_session_or_input_change", true)
    }

    private fun exerciseTransientSystemBars(width: Int, height: Int, generation: Long, sessionId: String, pid: Int) {
        val bounds = desktopBounds()
        val x = bounds.getInt("window_left") + bounds.getInt("window_width") / 2f
        val top = bounds.getInt("window_top") + 1f
        val distance = minOf(200f, bounds.getInt("window_height") * 0.2f)
        val resources = compose.activity.resources
        val thresholdId = resources.getIdentifier("system_gestures_distance_threshold", "dimen", "android")
        assertTrue("Actual platform swipe threshold must be observable", thresholdId != 0)
        val threshold = resources.getDimensionPixelSize(thresholdId)
        assertTrue("One existing edge gesture must exceed the actual platform threshold", distance > threshold)
        measurements.put("transient_system_bar_gesture_contract", JSONObject()
            .put("distance_px", distance).put("platform_threshold_px", threshold)
            .put("density", resources.displayMetrics.density).put("maximum_crossing_ms_exclusive", 500)
            .put("step_delay_ms", 5).put("cadence_source", "AndroidX UiAutomator 2.3.0 InteractionController")
            .put("before", systemBarVisibility()))
        TransientSystemBarsPolicy.verifyParserContract()
        measurements.put("transient_policy_parser_mutations_checked", true)
        val policyBaseline = systemTransientPolicy()
        assertTrue("One swipe must start with no already-visible transient policy", policyBaseline.showing.isEmpty())
        var statusRegion = Rect()
        var navigationRegions = emptyList<Rect>()
        compose.runOnUiThread {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            val status = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
            val navigation = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            val left = bounds.getInt("window_left"); val topEdge = bounds.getInt("window_top")
            val right = left + bounds.getInt("window_width"); val bottom = topEdge + bounds.getInt("window_height")
            statusRegion = Rect(left, topEdge, right, topEdge + status.top)
            navigationRegions = listOf(Rect(left, topEdge, left + navigation.left, bottom),
                Rect(right - navigation.right, topEdge, right, bottom),
                Rect(left, bottom - navigation.bottom, right, bottom)).filterNot { it.isEmpty }
        }
        assertFalse("Actual top system bar region must be measurable", statusRegion.isEmpty)
        val baselineShot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val baselineStatus = pixelsIn(baselineShot, statusRegion)
        val baselineNavigation = navigationRegions.map { pixelsIn(baselineShot, it) }
        File(evidence, "04g-before-system-edge.png").outputStream().use { baselineShot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        baselineShot.recycle()
        measurements.put("transient_system_bar_regions", JSONObject().put("status", statusRegion.toShortString())
            .put("navigation", JSONArray(navigationRegions.map { it.toShortString() }))
            .put("policy_before", policyBaseline.section))
        val handleOrigin = IntArray(2)
        val handleWindowOrigin = IntArray(2)
        compose.runOnUiThread {
            compose.activity.window.decorView.getLocationOnScreen(handleOrigin)
            compose.activity.window.decorView.getLocationInWindow(handleWindowOrigin)
        }
        val handleBounds = compose.onNodeWithContentDescription("会话菜单").fetchSemanticsNode().boundsInWindow
        val handleScreen = Rect((handleOrigin[0] - handleWindowOrigin[0] + handleBounds.left).toInt(),
            (handleOrigin[1] - handleWindowOrigin[1] + handleBounds.top).toInt(),
            (handleOrigin[0] - handleWindowOrigin[0] + handleBounds.right).toInt(),
            (handleOrigin[1] - handleWindowOrigin[1] + handleBounds.bottom).toInt())
        measurements.put("transient_floating_handle", JSONObject().put("bounds_in_window", handleBounds.toString())
            .put("bounds_on_screen", handleScreen.toShortString())
            .put("intersects_status_region", Rect.intersects(handleScreen, statusRegion))
            .put("intersects_navigation_region", navigationRegions.any { Rect.intersects(handleScreen, it) }))
        val exclusions = mutableListOf(handleScreen)
        compose.runOnUiThread {
            val view = desktop(); val origin = IntArray(2); view.getLocationOnScreen(origin)
            val transform = view.viewportSnapshot()
            fun remoteRect(left: Float, top: Float, right: Float, bottom: Float): Rect {
                val a = transform.toScreen(left, top); val b = transform.toScreen(right, bottom)
                return Rect((origin[0] + a.x).toInt(), (origin[1] + a.y).toInt(),
                    kotlin.math.ceil(origin[0] + b.x).toInt(), kotlin.math.ceil(origin[1] + b.y).toInt())
            }
            // Exact synthetic desktop dynamic feedback regions (rdp_desktop.py),
            // not color tolerance: only stable RGB/background pixels are compared.
            exclusions += remoteRect(304f, 16f, 369f, 81f)
            exclusions += remoteRect(0f, 96f, transform.remoteWidth.toFloat(), 240f)
        }
        val pointerMargin = 32 * resources.displayMetrics.density
        exclusions += Rect((x - pointerMargin).toInt(), top.toInt(),
            kotlin.math.ceil(x + pointerMargin).toInt(), kotlin.math.ceil(top + distance + pointerMargin).toInt())
        val statusEligible = eligiblePixels(statusRegion, exclusions)
        val navigationEligible = navigationRegions.map { eligiblePixels(it, exclusions) }
        assertTrue("Stable system bar tiles must not be empty", statusEligible > 0 && navigationEligible.all { it > 0 })
        measurements.put("transient_pixel_mask", JSONObject()
            .put("excluded_screen_rectangles", JSONArray(exclusions.map { it.toShortString() }))
            .put("status_pixels_compared", statusEligible).put("navigation_pixels_compared", JSONArray(navigationEligible))
            .put("reason", "Exclude mapped remote text/feedback, actual floating control/ripple bounds and pointer corridor; exact stable RGB/background comparison"))
        val events = JSONArray()
        measurements.put("transient_system_bar_swipe", events)
        val down = SystemClock.uptimeMillis()
        var thresholdCrossedAt: Long? = null
        fun event(action: Int, y: Float) {
            val e = fixtureFingerEvent(down, SystemClock.uptimeMillis(), action, x, y)
            val observation = JSONObject().put("action", action).put("x", x).put("y", y)
                .put("event_time_ms", e.eventTime).put("down_time_ms", e.downTime)
                .put("wall_ms", System.currentTimeMillis()).put("tool_type", e.getToolType(0))
                .put("source", e.source).put("device_id", e.deviceId)
            events.put(observation)
            if (action == MotionEvent.ACTION_MOVE && y > top + threshold && thresholdCrossedAt == null)
                thresholdCrossedAt = e.eventTime - e.downTime
            try {
                val accepted = instrumentation.uiAutomation.injectInputEvent(e, true)
                observation.put("accepted", accepted).put("completed_uptime_ms", SystemClock.uptimeMillis())
                assertTrue("System edge swipe injection must be accepted", accepted)
            } catch (failure: Throwable) {
                observation.put("injection_exception", failure.javaClass.name)
                throw failure
            } finally { e.recycle() }
        }
        try {
            event(MotionEvent.ACTION_DOWN, top)
            // UiAutomator 2.3.0's documented injection cadence, not backdated
            // event timestamps. The platform rejects edge crossings >=500ms.
            for (i in 1..8) { SystemClock.sleep(5); event(MotionEvent.ACTION_MOVE, top + distance * i / 8) }
        } finally { event(MotionEvent.ACTION_UP, top + distance) }
        measurements.put("transient_system_bar_threshold_crossing_ms", thresholdCrossedAt)
        assertTrue("Harness edge gesture must cross the real platform threshold within500ms; no retry: $events",
            thresholdCrossedAt?.let { it < 500 } == true)
        val observations = JSONArray()
        measurements.put("transient_system_bar_observations", observations)
        var everChanged = false
        var screenshotIndex = 0
        fun observe(saveAs: String? = null): Triple<TransientSystemBarsPolicy, Int, Int> {
            val started = SystemClock.elapsedRealtime()
            val policy = systemTransientPolicy()
            val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val screenshotAt = SystemClock.elapsedRealtime()
            val statusDiff = changedPixels(pixelsIn(shot, statusRegion), baselineStatus, statusRegion, exclusions)
            val navigationDiff = navigationRegions.indices.sumOf { i ->
                changedPixels(pixelsIn(shot, navigationRegions[i]), baselineNavigation[i], navigationRegions[i], exclusions)
            }
            // Preserve the actual contemporaneous image, not a second delayed capture.
            if (screenshotIndex < 4 || (policy.showing.isNotEmpty() && screenshotIndex < 16) || saveAs != null) {
                val name = saveAs ?: "04g-system-edge-observation-${screenshotIndex++}"
                File(evidence, "$keyboardScreenshotPrefix$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            shot.recycle()
            val server = fixtureState()
            val requested = RdpConnector.pollDisplayStatus().requested
            everChanged = everChanged || !RdpConnector.isConnected() || !actualSize(width, height) ||
                server.getInt("width") != width || server.getInt("height") != height ||
                server.getString("session_id") != sessionId || server.getInt("desktop_pid") != pid ||
                RdpConnector.nativeGetTelemetry()[11] != generation ||
                requested?.let { it.width != width || it.height != height } == true
            observations.put(systemBarVisibility().put("observation_started_ms", started)
                .put("screenshot_elapsed_ms", screenshotAt).put("policy_section", policy.section)
                .put("policy_transient_types", JSONArray(policy.showing.toList()))
                .put("status_region_changed_pixels", statusDiff).put("navigation_region_changed_pixels", navigationDiff)
                .put("client_width", RdpConnector.getWidth()).put("client_height", RdpConnector.getHeight())
                .put("server_width", server.getInt("width")).put("server_height", server.getInt("height"))
                .put("native_generation", RdpConnector.nativeGetTelemetry()[11]).put("ever_changed", everChanged))
            return Triple(policy, statusDiff, navigationDiff)
        }
        val revealDeadline = SystemClock.elapsedRealtime() + 5_000
        waitFor("one physical edge swipe reveals real SystemUI policy and actual top-bar pixels", 5_000) {
            val (policy, statusDiff) = observe()
            SystemClock.elapsedRealtime() <= revealDeadline && "statusBars" in policy.showing && statusDiff > 0
        }
        measurements.put("transient_system_bar_reveal_verified", true)
        val hideDeadline = SystemClock.elapsedRealtime() + 12_000
        waitFor("transient system policy clears and exact original bar-region pixels return", 12_000) {
            val (policy, statusDiff, navigationDiff) = observe()
            SystemClock.elapsedRealtime() <= hideDeadline && policy.showing.isEmpty() && statusDiff == 0 && navigationDiff == 0
        }
        observe("04h-system-bars-hidden-restored")
        assertFalse("Transient bars must not resize or reconnect the remote desktop: $observations", everChanged)
        assertFullscreenDefault("after_transient_bars_fullscreen")
        assertColor(40, 40, Color.RED)
        measurements.put("transient_bars_preserved_remote_session", true)
    }

    private fun systemTransientPolicy(): TransientSystemBarsPolicy {
        // AOSP15 fake control deliberately masks transient visibility from app
        // Insets. Parse complete display0 system policy instead, never assume a
        // missing/truncated dump means hidden. Owned synthetic emulator only.
        val dump = shellOutput("dumpsys -t 2 window displays")
        return try { TransientSystemBarsPolicy.parse(dump) } catch (failure: Throwable) {
            File(evidence, "system-policy-parse-failure.txt").writeText(dump.take(256_000))
            throw failure
        }
    }

    private fun changedPixels(actual: IntArray, baseline: IntArray, region: Rect, exclusions: List<Rect>): Int {
        require(actual.size == baseline.size)
        return actual.indices.count { i -> actual[i] != baseline[i] && exclusions.none {
            it.contains(region.left + i % region.width(), region.top + i / region.width())
        } }
    }

    private fun eligiblePixels(region: Rect, exclusions: List<Rect>): Int =
        (0 until region.width() * region.height()).count { i -> exclusions.none {
            it.contains(region.left + i % region.width(), region.top + i / region.width())
        } }


    private fun pixelsIn(bitmap: Bitmap, region: Rect): IntArray {
        require(region.left >= 0 && region.top >= 0 && region.right <= bitmap.width && region.bottom <= bitmap.height)
        return IntArray(region.width() * region.height()).also {
            bitmap.getPixels(it, 0, region.width(), region.left, region.top, region.width(), region.height())
        }
    }

    private fun assertFullscreenDefault(key: String) {
        waitFor("fullscreen hides system bars and fills the cutout-safe window") {
            val b = desktopBounds()
            !b.getBoolean("status_bar_visible") && !b.getBoolean("navigation_bar_visible") &&
                b.getInt("left") == b.getInt("window_left") + b.getInt("cutout_left") &&
                b.getInt("top") == b.getInt("window_top") + b.getInt("cutout_top") &&
                b.getInt("width") == b.getInt("window_width") - b.getInt("cutout_left") - b.getInt("cutout_right") &&
                b.getInt("height") == b.getInt("window_height") - b.getInt("cutout_top") - b.getInt("cutout_bottom")
        }
        compose.onAllNodes(hasContentDescription("会话菜单") and hasClickAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription("会话菜单").assertIsDisplayed()
        listOf("显示或隐藏键盘", "切换到触控板", "显示与网络设置", "断开", "进入全屏").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
        measurements.put(key, desktopBounds())
    }

    private fun useBorderedToolbar() {
        tapAccessibleControl("会话菜单")
        // New sessions still use direct touch; its toggle is in the hidden menu.
        compose.onNodeWithText("切换到触控板").assertExists()
        compose.onNodeWithText("显示工具栏").performScrollTo().performClick()
        waitFor("explicit toolbar opt-in restores bordered controls") {
            compose.onAllNodesWithContentDescription("进入全屏").fetchSemanticsNodes().size == 1 &&
                desktopBounds().getBoolean("status_bar_visible")
        }
    }

    /** Actual product menu tap, physical display geometry and live RDP evidence. */
    private fun rotateThroughSessionMenu(landscape: Boolean, fullscreen: Boolean, requireIme: Boolean) {
        val activity = compose.activity
        var originalView: RdpDesktopView? = null
        compose.runOnUiThread { originalView = desktop() }
        val generation = RdpConnector.nativeGetTelemetry()[11]
        val before = fixtureState()
        val frame = RdpConnector.nativeGetFrameId()
        val rotations = measurements.optJSONArray("phone_screen_rotations") ?: JSONArray().also {
            measurements.put("phone_screen_rotations", it)
        }
        val receipt = JSONObject().put("target_landscape", landscape).put("fullscreen", fullscreen)
            .put("ime_open", requireIme).put("before_frame", frame).put("native_generation", generation)
        rotations.put(receipt)
        tapAccessibleControl(if (fullscreen) "会话菜单" else "会话工具")
        boundedScroll.to(compose.onNodeWithText("旋转屏幕")).assertIsDisplayed()
        tapObservedAppPopupItem("旋转屏幕")
        waitFor("menu rotates actual phone and live framebuffer", 25_000) {
            val configuration = activity.resources.configuration
            configuration.orientation == (if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT) &&
                RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > frame &&
                (RdpConnector.getWidth() > RdpConnector.getHeight()) == landscape &&
                fixtureSize(RdpConnector.getWidth(), RdpConnector.getHeight()) && desktopHasWindowFocus() &&
                (!requireIme || imeVisible())
        }
        assertSame("Phone rotation retains MainActivity", activity, compose.activity)
        compose.runOnUiThread { assertSame("Phone rotation retains desktop editor owner", originalView, desktop()) }
        assertEquals("Phone rotation retains RDP generation", generation, RdpConnector.nativeGetTelemetry()[11])
        val after = fixtureState()
        assertEquals(before.getString("session_id"), after.getString("session_id"))
        assertEquals(before.getInt("desktop_pid"), after.getInt("desktop_pid"))
        assertEquals("Rotation menu must not leak remote clicks", before.getInt("clicks"), after.getInt("clicks"))
        assertEquals(if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation)
        if (fullscreen && !requireIme) assertFullscreenDefault("rotation_${rotations.length()}_fullscreen")
        if (fullscreen && requireIme) {
            compose.onNodeWithContentDescription("会话菜单").assertIsDisplayed()
            compose.onNodeWithContentDescription("进入全屏").assertDoesNotExist()
            val bounds = desktopBounds()
            val unobscuredHeight = bounds.getInt("window_height") - bounds.getInt("safe_top") -
                max(bounds.getInt("safe_bottom"), bounds.getInt("ime_bottom"))
            assertTrue("Rotated IME leaves usable desktop", unobscuredHeight > 0)
            assertTrue("No bordered toolbar consumes rotated IME viewport", bounds.getInt("height").toDouble() / unobscuredHeight >= 0.90)
        }
        assertColor(40, 40, Color.RED)
        tapAndObserve(remoteX = 400f, remoteY = 40f)
        if (requireIme) assertTrue("Touch after phone rotation retains visible IME", imeVisible())
        receipt.put("after_frame", RdpConnector.nativeGetFrameId()).put("width", RdpConnector.getWidth())
            .put("height", RdpConnector.getHeight()).put("desktop_view_identity", System.identityHashCode(originalView))
            .put("actual_pixels_and_touch_passed", true)
        screenshot("rotation-${rotations.length()}-${if (landscape) "landscape" else "portrait"}${if (requireIme) "-ime" else ""}")
    }

    private fun tapSessionKeyboard() {
        tapAccessibleControl("会话菜单")
        compose.onNodeWithText("显示或隐藏键盘").assertIsDisplayed()
        compose.onNodeWithText("断开").assertIsDisplayed()
        tapObservedAppPopupItem("显示或隐藏键盘")
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
                .put("status_bar_visible", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                .put("navigation_bar_visible", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
                .put("cutout_left", insets.getInsets(WindowInsetsCompat.Type.displayCutout()).left)
                .put("cutout_top", insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top)
                .put("cutout_right", insets.getInsets(WindowInsetsCompat.Type.displayCutout()).right)
                .put("cutout_bottom", insets.getInsets(WindowInsetsCompat.Type.displayCutout()).bottom)
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
        try {
            // Establish a real recent input target near the probes before local IME cropping.
            tapAndObserve(remoteX = 400f, remoteY = 40f)
            val beforeViewport = viewportSnapshot()
            val beforePatchSpan = observedPatchSpan(40, 40, Color.RED)
            measurements.put("ime_pre_scale", beforeViewport.scale).put("ime_pre_red_patch_span_px", beforePatchSpan)
            tapSessionKeyboard()
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
            val composeStarted = compose.mainClock.currentTime
            val iterations = JSONArray()
            val clockEvidence = JSONObject().put("wall_start_ms", started).put("compose_start_ms", composeStarted)
                .put("wall_budget_ms", 10_000).put("iterations", iterations)
            measurements.put("ime_display_poll_clock", clockEvidence)
            try { waitFor("IME preserves remote geometry across three actual display polls", 10_000) {
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed >= 10_000) return@waitFor false
                val iteration = JSONObject().put("wall_start_ms", SystemClock.elapsedRealtime())
                    .put("compose_before_ms", compose.mainClock.currentTime)
                if (iterations.length() < 128) iterations.put(iteration)
                // The production loop delays 1000ms on Compose's test clock.
                // Fixed 100ms steps per expensive HTTP/UI observation can leave
                // that clock behind wall time. Align only this observation to
                // real elapsed time; never invoke updateDisplay or extend10s.
                val target = composeStarted + elapsed
                val advance = (target - compose.mainClock.currentTime).coerceAtLeast(0)
                iteration.put("compose_target_ms", target).put("alignment_advance_ms", advance)
                if (advance > 0) compose.mainClock.advanceTimeBy(advance)
                instrumentation.waitForIdleSync()
                iteration.put("compose_after_ms", compose.mainClock.currentTime)
                try {
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
                        .put("compose_ms", compose.mainClock.currentTime)
                        .put("client_width", RdpConnector.getWidth()).put("client_height", RdpConnector.getHeight())
                        .put("server_width", state.getInt("width")).put("server_height", state.getInt("height"))
                        .put("native_generation", nativeGeneration).put("requested", status.requested?.toString())
                        .put("ever_changed", changed))
                }
                iteration.put("fresh_polls", observedPolls).put("ever_changed", changed)
                val observedElapsed = SystemClock.elapsedRealtime() - started
                observedPolls >= 3 && observedElapsed in 2_000..10_000
                } finally { iteration.put("wall_end_ms", SystemClock.elapsedRealtime()).put("compose_end_ms", compose.mainClock.currentTime) }
            } } finally {
                clockEvidence.put("wall_end_ms", SystemClock.elapsedRealtime()).put("compose_end_ms", compose.mainClock.currentTime)
                    .put("fresh_polls", observedPolls).put("ever_changed", changed)
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
            compose.onAllNodes(hasContentDescription("会话菜单") and hasClickAction()).assertCountEquals(1)
            compose.onNodeWithContentDescription("会话菜单").assertIsDisplayed()
            listOf("显示或隐藏键盘", "会话工具", "断开").forEach { label ->
                compose.onNodeWithContentDescription(label).assertDoesNotExist()
            }
            val clicksBeforeTools = fixtureState().getInt("clicks")
            tapAccessibleControl("会话菜单")
            // Both essential actions must fit together even in the short IME-free
            // region; the later shortcut check must scroll a real popup.
            compose.onNodeWithText("显示或隐藏键盘").assertIsDisplayed()
            compose.onNodeWithText("断开").assertIsDisplayed()
            screenshot("04d0-fullscreen-ime-menu-essential-actions")
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
            rotateThroughSessionMenu(landscape = false, fullscreen = true, requireIme = true)
            rotateThroughSessionMenu(landscape = true, fullscreen = true, requireIme = true)
            // The real software keyboard still routes input after the roundtrip;
            // this repeats the original composition/commit/newline assertions.
            exerciseActualSoftwareKeyboard()
            waitFor("IME rotation roundtrip restores original logical frame", 25_000) {
                actualSize(width, height) && fixtureSize(width, height)
            }
            measurements.put("ime_tools_touch_and_software_typing_passed", true)
            screenshot("04d-landscape-ime-visible")
            val clicksBeforeHide = fixtureState().getInt("clicks")
            tapSessionKeyboard()
            waitFor("IME hidden and compact local viewport restored") {
                !imeVisible() && desktopBounds().getInt("height") == fullViewHeight
            }
            assertEquals("Overlay hide touch must not leak to the desktop", clicksBeforeHide, fixtureState().getInt("clicks"))
            assertTrue(actualSize(width, height))
            assertTrue(fixtureSize(width, height))
            assertEquals(generation, RdpConnector.nativeGetTelemetry()[11])
            assertEquals(sessionId, fixtureState().getString("session_id"))
            assertEquals(desktopPid, fixtureState().getInt("desktop_pid"))
            // Fullscreen keeps the intentional bottom-right menu handle after IME
            // dismissal. Sample real restored background, not that local overlay.
            measurements.put("ime_restored_background_exact_rgb", assertFullscreenBackgroundOutsideHandle(width / 2, height - 80))
            measurements.put("ime_preserved_remote_geometry_and_session", true)
            screenshot("04e-landscape-ime-restored")
        } finally {
            runCatching { compose.runOnUiThread { desktop().hideKeyboard() } }
        }
    }

    private fun assertFullscreenBackgroundOutsideHandle(x: Int, y: Int): JSONObject {
        val screen = requireNotNull(remoteScreenPoint(x.toFloat(), y.toFloat()))
        val handle = compose.onNode(hasContentDescription("会话菜单") and hasClickAction())
            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val rootScreen = IntArray(2); val rootWindow = IntArray(2)
        compose.runOnUiThread {
            compose.activity.window.decorView.getLocationOnScreen(rootScreen)
            compose.activity.window.decorView.getLocationInWindow(rootWindow)
        }
        val handleScreen = android.graphics.RectF(handle.left + rootScreen[0] - rootWindow[0],
            handle.top + rootScreen[1] - rootWindow[1], handle.right + rootScreen[0] - rootWindow[0],
            handle.bottom + rootScreen[1] - rootWindow[1])
        assertFalse("Background pixel must not sample intentional session handle", handleScreen.contains(screen.x, screen.y))
        assertColor(x, y, Color.rgb(17, 34, 51))
        return JSONObject().put("remote_x", x).put("remote_y", y)
            .put("screen_x", screen.x).put("screen_y", screen.y)
            .put("handle_screen_bounds", handleScreen.toShortString()).put("outside_handle", true)
    }

    private fun tapAndObserve(timeout: Long = 10_000, remoteX: Float = 400f, remoteY: Float = 300f): Long {
        val attempt = JSONObject()
        val attempts = measurements.optJSONArray("touch_attempts") ?: JSONArray().also { measurements.put("touch_attempts", it) }
        attempts.put(attempt)
        measurements.put("rgb_assertion", "exact 8-bit RGB channels at uniform interior pixels")
        var observedView: RdpDesktopView? = null
        // Compose semantics can disappear before Android disposes the dialog's
        // input window. Do not inject a measured gesture into that transition.
        // A native frame counter also does not establish a drawn View bitmap.
        waitFor("visible, attached and focused remote desktop before touch") {
            var viewReady = false
            compose.runOnUiThread {
                val view = desktop()
                observedView = view
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
        val beforeState = fixtureState()
        val before = beforeState.getInt("clicks")
        val point = requireNotNull(remoteScreenPoint(remoteX, remoteY)) { "Touch target is outside the actual cropped viewport" }
        attempt.put("screen_x", point.x).put("screen_y", point.y).put("remote_x", remoteX).put("remote_y", remoteY)
            .put("server_clicks_before", before).put("server_before", beforeState)
        val revalidate = {
            val view = desktop(); val location = IntArray(2); view.getLocationOnScreen(location)
            assertTrue("The same observed desktop must receive this one tap", view === observedView)
            assertTrue(view.isAttachedToWindow && view.isShown && view.hasWindowFocus())
            assertEquals(attempt.getInt("view_left"), location[0]); assertEquals(attempt.getInt("view_top"), location[1])
            assertEquals(attempt.getInt("view_width"), view.width); assertEquals(attempt.getInt("view_height"), view.height)
            val mapped = view.viewportSnapshot().toScreen(remoteX, remoteY)
            assertEquals(point.x, location[0] + mapped.x, 0f); assertEquals(point.y, location[1] + mapped.y, 0f)
            attempt.put("view_identity", System.identityHashCode(view)).put("revalidated_elapsed_ms", SystemClock.elapsedRealtime())
            Unit
        }
        val start = SystemClock.elapsedRealtime()
        val upReceived = injectObservedDesktopTap(instrumentation, requireNotNull(observedView), point.x, point.y,
            remoteX, remoteY, attempt, revalidate)
        val deadline = upReceived + timeout
        attempt.put("response_deadline_elapsed_ms", deadline)
        val samples = JSONArray(); attempt.put("response_samples", samples)
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            val sample = JSONObject().put("started_elapsed_ms", SystemClock.elapsedRealtime())
            var matched = false
            try {
                val state = fixtureState()
                val actual = colorAt(40, 140)
                val expected = if ((before + 1) % 2 == 1) Color.rgb(68, 221, 136) else Color.rgb(221, 68, 136)
                sample.put("server", state).put("actual_rgb", actual?.and(0xffffff)).put("source_rgb", expected and 0xffffff)
                    .put("native_frame_id", RdpConnector.nativeGetFrameId()).put("native_generation", RdpConnector.nativeGetTelemetry()[11])
                matched = state.getInt("clicks") == before + 1 && state.getInt("right_clicks") == beforeState.getInt("right_clicks") &&
                    receivedPointMatches(state.optJSONArray("last_press"), remoteX, remoteY) &&
                    receivedPointMatches(state.optJSONArray("last_release"), remoteX, remoteY) && actual?.let { sameColor(it, expected) } == true
                sample.put("outcome_matched", matched)
            } catch (error: java.io.IOException) { sample.put("read_error", error.toString()) }
            val completed = SystemClock.elapsedRealtime()
            sample.put("completed_elapsed_ms", completed)
            if (samples.length() < 64) samples.put(sample)
            attempt.put("last_response", sample)
            if (matched && completed <= deadline) return (completed - start).also {
                latencySamples += it
                val state = sample.getJSONObject("server")
                attempt.put("passed", true).put("touch_to_observed_response_ms", it).put("server_clicks_after", state.getInt("clicks"))
                    .put("server_last_press", state.getJSONArray("last_press")).put("server_last_release", state.getJSONArray("last_release"))
            }
            SystemClock.sleep(100)
        }
        attempt.put("passed", false)
        throw AssertionError("Android touch reaches server and returns painted response within ${timeout}ms after the single UP: ${RdpConnector.getDiag()}")
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
            val hit = JSONObject().put("label", label).put("screen_bounds", rect.toShortString())
            val hits = measurements.optJSONArray("popup_physical_hits") ?: JSONArray().also { measurements.put("popup_physical_hits", it) }
            hits.put(hit)
            if (label == "Esc") {
                measurements.put("ime_tools_popup_hit", hit)
                screenshot("04d1-ime-tools-popup")
            }
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

    private fun injectSingleTap(x: Float, y: Float, inputMethodWindow: Boolean = false, trace: JSONArray? = null): Long {
        val downTime = SystemClock.uptimeMillis()
        var allAccepted = true
        var injectionFailure: Throwable? = null
        var upCompletedElapsed = 0L
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val record = clockSnapshot().put("action", action).put("x", x).put("y", y)
                .put("source", event.source).put("device_id", event.deviceId)
            trace?.put(record)
            try {
                if (inputMethodWindow) {
                    // The real IME belongs to a different UID. UiAutomation's
                    // system input injection can target its observed window.
                    Log.i("RdpImeFixture", "inject-start action=$action elapsed=${SystemClock.elapsedRealtime()} uptime=${SystemClock.uptimeMillis()}")
                    try {
                        val accepted = instrumentation.uiAutomation.injectInputEvent(event, true)
                        allAccepted = allAccepted && accepted
                        val completed = clockSnapshot()
                        record.put("accepted", accepted).put("completed", completed)
                        if (action == MotionEvent.ACTION_UP) upCompletedElapsed = completed.getLong("elapsed_ms")
                        Log.i("RdpImeFixture", "inject-end action=$action accepted=$accepted elapsed=${SystemClock.elapsedRealtime()} uptime=${SystemClock.uptimeMillis()}")
                    } catch (failure: Throwable) {
                        record.put("exception_type", failure.javaClass.simpleName).put("completed", clockSnapshot())
                        if (injectionFailure == null) injectionFailure = failure
                    }
                } else {
                    instrumentation.sendPointerSync(event)
                    if (action == MotionEvent.ACTION_UP) upCompletedElapsed = SystemClock.elapsedRealtime()
                }
            } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
        // Complete exactly one UP even if DOWN failed or the IME changed mid-gesture.
        injectionFailure?.let { throw it }
        if (inputMethodWindow) assertTrue("System must accept both observed IME key events", allAccepted)
        return upCompletedElapsed
    }

    private fun exerciseActualSoftwareKeyboard() {
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val before = fixtureState().getString("text")
        val keyEvidence = JSONArray()
        val keyboardRuns = measurements.optJSONArray("software_keyboard_runs") ?: JSONArray().also {
            measurements.put("software_keyboard_runs", it)
        }
        val keyboardRun = JSONObject().put("keys", keyEvidence).put("before_text", before)
        keyboardRuns.put(keyboardRun)
        keyboardScreenshotPrefix = if (keyboardRuns.length() == 1) "" else "post-rotation-"
        measurements.put("actual_software_keyboard_keys", keyEvidence)
        val receipts = JSONArray()
        var receiptOverflow = 0
        measurements.put("software_keyboard_view_key_receipts", receipts)
            .put("key_receipt_limitation", "Nonconsuming OnKeyListener observes dispatched KeyEvents only; commitText/editor actions bypass it")
        var observedView: RdpDesktopView? = null
        val connectionReceipts = JSONArray()
        keyboardRun.put("view_key_receipts", receipts).put("connection_receipts", connectionReceipts)
        measurements.put("software_keyboard_connection_receipts", connectionReceipts)
            .put("software_keyboard_connection_trace_limit", 128)
        var previousObserver: ((RdpDesktopView.ImeDiagnostic) -> Unit)? = null
        try {
            compose.runOnUiThread {
                // This fresh fixture-owned View and pinned Compose1.7.2 install no
                // OnKeyListener. Restore that known null state on this same View.
                observedView = desktop().also { view ->
                    previousObserver = view.imeDiagnosticObserver
                    view.imeDiagnosticObserver = { event ->
                        synchronized(connectionReceipts) {
                            connectionReceipts.put(clockSnapshot().put("connection", event.connection)
                                .put("operation", event.operation).put("argument_length", event.argumentLength)
                                .put("composing_before", event.composingBefore).put("composing_after", event.composingAfter)
                                .put("view_identity", System.identityHashCode(view)))
                        }
                    }
                    view.setOnKeyListener { actualView, code, event ->
                        synchronized(receipts) {
                            if (receipts.length() >= 128) receiptOverflow++
                            else receipts.put(clockSnapshot().put("action", event.action)
                                .put("is_enter", code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)
                                .put("event_uptime_ms", event.eventTime).put("view_identity", System.identityHashCode(actualView))
                                .put("view_focused", actualView.isFocused).put("window_focused", actualView.hasWindowFocus()))
                        }
                        Log.i("RdpImeFixture", "view-key action=${event.action} enter=${code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER} elapsed=${SystemClock.elapsedRealtime()} uptime=${SystemClock.uptimeMillis()}")
                        false
                    }
                }
            }
            // Declare the gesture plan before typing. Never choose another Enter
            // after a failed final-text assertion or infer a layout from its outcome.
            val initialLabels = observedImeLabels("before-input")
            val subtype = (instrumentation.targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager).currentInputMethodSubtype
            val subtypeLocale = subtype?.languageTag?.ifEmpty { subtype?.locale.orEmpty() }.orEmpty()
            val pinyin = initialLabels.containsAll(setOf("全角逗号", "全角句号", "下一种语言", "Enter 键"))
            val latin = !pinyin && subtypeLocale.replace('_', '-').startsWith("en", ignoreCase = true) &&
                initialLabels.any { it.equals("Enter", true) } &&
                initialLabels.any { it == "," || it.equals("Comma", true) } &&
                initialLabels.any { it == "." || it.equals("Period", true) }
            assertTrue("Unknown observed IME layout; no typing or fallback is permitted", pinyin || latin)
            assertFalse("Fixture must start without a Pinyin candidate composition", initialLabels.contains("其他候选键"))
            measurements.put("software_keyboard_declared_plan", JSONObject()
                .put("mode", if (pinyin) "pinyin-compose-commit-newline" else "latin-direct-newline")
                .put("subtype_locale_diagnostic", subtypeLocale)
                .put("gestures", JSONArray(if (pinyin) listOf("a", "commit-Enter", "newline-Enter") else listOf("a", "newline-Enter"))))
            keyboardRun.put("declared_plan", measurements.getJSONObject("software_keyboard_declared_plan"))
            screenshot("04d0-ime-declared-layout")
            tapObservedImeKey("a", keyEvidence, "type-a")
            if (pinyin) {
                waitFor("declared Pinyin composition is visible without premature remote commit") {
                    val labels = observedImeLabels("composing-a")
                    labels.containsAll(setOf("啊", "阿", "其他候选键")) && fixtureState().getString("text") == before
                }
                keyboardRun.put("composition_kept_local", true)
                measurements.put("software_keyboard_composition_kept_local", true)
                screenshot("04d2a-ime-composing-a")
                tapObservedImeKey("Enter", keyEvidence, "commit-composition")
                waitFor("one declared composition Enter commits exactly a and collapses candidates") {
                    val labels = observedImeLabels("composition-committed")
                    !labels.contains("其他候选键") && !labels.contains("啊") &&
                        fixtureState().getString("text") == before + "a"
                }
                measurements.put("software_keyboard_composition_committed_text", before + "a")
                screenshot("04d2b-ime-composition-committed")
            }
            measurements.put("software_keyboard_interim_text", fixtureState().getString("text"))
            val enterUpCompleted = tapObservedImeKey("Enter", keyEvidence, "newline")
            val expected = before + "a\n"
            val length = expected.codePointCount(0, expected.length)
            val colors = intArrayOf(Color.rgb(85, 204, 204), Color.rgb(204, 170, 85), Color.rgb(153, 85, 221))
            val responseWaitStarted = SystemClock.elapsedRealtime()
            val remainingResponseBudget = 15_000 - (responseWaitStarted - enterUpCompleted)
            measurements.put("software_keyboard_response_budget", JSONObject()
                .put("enter_up_completed_elapsed_ms", enterUpCompleted).put("wait_started_elapsed_ms", responseWaitStarted)
                .put("remaining_ms", remainingResponseBudget).put("total_budget_ms", 15_000))
            assertTrue("Post-gesture diagnostics exhausted the unchanged15s response budget", remainingResponseBudget > 0)
            waitFor("real software keyboard commits a/newline and paints distinct remote text feedback", remainingResponseBudget) {
                fixtureState().getString("text") == expected &&
                    colorAt(328, 40)?.let { sameColor(it, colors[length % 3]) } == true
            }
            synchronized(receipts) { assertEquals("Key receipt diagnostic must not truncate", 0, receiptOverflow) }
            keyboardRun.put("expected_text", expected).put("actual_text", fixtureState().getString("text"))
                .put("exact_remote_text_and_pixels_passed", true)
            measurements.put("software_keyboard_committed_text", expected)
                .put("software_keyboard_text_patch_exact_rgb", true)
        } catch (failure: Throwable) {
            // Capture the live IME before the outer cleanup hides it. Keep the
            // original failure and never repair missing input with another tap.
            runCatching { measurements.put("software_keyboard_failure_state", fixtureState()) }
            runCatching { measurements.put("software_keyboard_failure_view", desktopBounds()) }
            runCatching { screenshot("04d4-ime-key-failure-before-hide") }
            throw failure
        } finally {
            compose.runOnUiThread {
                observedView?.let { view ->
                    measurements.put("software_keyboard_connection_trace_dropped", view.imeDiagnosticDropped)
                    view.imeDiagnosticObserver = previousObserver
                    view.setOnKeyListener(null)
                }
            }
            synchronized(receipts) { measurements.put("software_keyboard_key_receipt_overflow", receiptOverflow) }
            keyboardScreenshotPrefix = ""
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private data class ObservedImeKey(
        val node: AccessibilityNodeInfo, val label: String, val windowId: Int,
        val windowBounds: Rect, val keyBounds: Rect, val rootIdentity: Int, val rootPackage: String
    ) {
        fun sameTarget(other: ObservedImeKey): Boolean = windowId == other.windowId &&
            windowBounds == other.windowBounds && keyBounds == other.keyBounds && label == other.label &&
            rootPackage == other.rootPackage && rootIdentity == other.rootIdentity && node == other.node
        fun snapshot(): JSONObject = JSONObject().put("observed", label).put("window_id", windowId)
            .put("ime_window_bounds", windowBounds.toShortString()).put("key_bounds", keyBounds.toShortString())
            .put("root_identity", rootIdentity).put("root_package", rootPackage).put("node_identity", node.hashCode())
            .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled)
    }

    private fun clockSnapshot(): JSONObject = JSONObject().put("wall_ms", System.currentTimeMillis())
        .put("elapsed_ms", SystemClock.elapsedRealtime()).put("uptime_ms", SystemClock.uptimeMillis())

    private fun inputViewSnapshot(): JSONObject {
        val snapshot = JSONObject()
        compose.runOnUiThread {
            val view = desktop()
            snapshot.put("view_identity", System.identityHashCode(view)).put("focused", view.isFocused)
                .put("window_focused", view.hasWindowFocus()).put("attached", view.isAttachedToWindow)
                .put("shown", view.isShown).put("width", view.width).put("height", view.height)
        }
        return snapshot
    }

    private fun matchesImeKey(node: AccessibilityNodeInfo, label: String): Boolean {
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        // Actual API35 Gboard may follow the target app locale indirectly. These
        // are the two observed labels for the same Enter key, never an action fallback.
        fun matches(value: String): Boolean = if (label == "Enter")
            value.equals("Enter", true) || value == "Enter 键" else value == label
        return matches(text) || matches(description)
    }

    /** No global accessibility listener: UiAutomation has no getter to restore its previous owner. */
    private fun findFreshImeKey(label: String): ObservedImeKey? {
        val matches = linkedMapOf<String, ObservedImeKey>()
        val observed = JSONArray()
        for (window in instrumentation.uiAutomation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val bounds = Rect().also(window::getBoundsInScreen)
            val root = window.root ?: continue
            try {
                if (!root.refresh()) continue
                fun visit(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 30) return
                    val text = node.text?.toString()?.trim().orEmpty()
                    val description = node.contentDescription?.toString()?.trim().orEmpty()
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (observed.length() < 150 && (text.isNotBlank() || description.isNotBlank())) {
                        // Only public synthetic fixture IME key labels, never input text.
                        observed.put(JSONObject().put("text", text.take(80)).put("description", description.take(80))
                            .put("bounds", rect.toShortString()))
                    }
                    val matchesLabel = matchesImeKey(node, label)
                    if (matchesLabel && node.isVisibleToUser && node.isEnabled && rect.width() > 0 &&
                        rect.height() > 0 && bounds.contains(rect)) {
                        val copy = AccessibilityNodeInfo.obtain(node)
                        if (copy.refresh() && copy.isVisibleToUser && copy.isEnabled) {
                            val freshBounds = Rect().also(copy::getBoundsInScreen)
                            val observedLabel = if (text.isNotEmpty()) text else description
                            val freshLabel = copy.text?.toString()?.trim().orEmpty().ifEmpty {
                                copy.contentDescription?.toString()?.trim().orEmpty()
                            }
                            if (freshBounds == rect && matchesImeKey(copy, label) && freshLabel == observedLabel) {
                                val key = "${window.id}:${copy.hashCode()}:${rect.toShortString()}"
                                matches.put(key, ObservedImeKey(copy, if (text.isNotEmpty()) text else description,
                                    window.id, bounds, rect, root.hashCode(), root.packageName?.toString().orEmpty()))?.node?.recycle()
                            } else copy.recycle()
                        } else copy.recycle()
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { visit(child, depth + 1) } finally { child.recycle() }
                    }
                }
                visit(root, 0)
            } finally { root.recycle() }
        }
        measurements.put("ime_accessibility_observed_nodes", observed)
        if (matches.size == 1) return matches.values.single()
        matches.values.forEach { it.node.recycle() }
        return null
    }

    private fun observedImeLabels(stage: String): Set<String> {
        val key = requireNotNull(findFreshImeKey("Enter")) { "No unique live IME Enter at $stage" }
        try { assertEquals("Pinned fixture requires actual Gboard", "com.google.android.inputmethod.latin", key.rootPackage) }
        finally { key.node.recycle() }
        val nodes = measurements.getJSONArray("ime_accessibility_observed_nodes")
        val labels = mutableSetOf<String>()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            labels.add(node.getString("text")); labels.add(node.getString("description"))
        }
        val stages = measurements.optJSONObject("software_keyboard_layout_receipts") ?: JSONObject().also {
            measurements.put("software_keyboard_layout_receipts", it)
        }
        stages.put(stage, JSONObject().put("clock", clockSnapshot()).put("nodes", nodes))
        return labels
    }

    private fun tapObservedImeKey(label: String, evidence: JSONArray, stage: String): Long {
        val attempt = JSONObject().put("requested", label).put("stage", stage).put("readiness_started", clockSnapshot())
        evidence.put(attempt)
        val readiness = JSONArray()
        attempt.put("readiness_observations", readiness)
        if (label == "Enter") screenshot("04d2-ime-before-$stage-enter")
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var target: ObservedImeKey? = null
        while (SystemClock.elapsedRealtime() < deadline && target == null) {
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            val first = findFreshImeKey(label)
            if (first == null) { SystemClock.sleep(100); continue }
            try {
                val observation = clockSnapshot().put("before_idle", first.snapshot())
                assertTrue("Key readiness diagnostic must remain bounded", readiness.length() < 32)
                readiness.put(observation)
                // A supported accessibility-event quiet condition, not a guessed
                // fixed delay. A timeout fails rather than tapping during churn.
                val remaining = deadline - SystemClock.elapsedRealtime()
                assertTrue("IME key readiness deadline expired", remaining > 0)
                instrumentation.uiAutomation.waitForIdle(500, minOf(10_000, remaining))
                observation.put("idle_completed", clockSnapshot())
                val focused = desktopHasWindowFocus() && imeVisible()
                observation.put("desktop_window_focused_and_ime_visible", focused).put("view", inputViewSnapshot())
                val fresh = findFreshImeKey(label)
                if (fresh != null) {
                    observation.put("after_idle", fresh.snapshot())
                    if (focused && first.sameTarget(fresh)) target = fresh else fresh.node.recycle()
                }
            } finally { first.node.recycle() }
        }
        val key = requireNotNull(target) { "No stable unique enabled real IME key $label within readiness budget" }
        try {
            // Revalidate the exact live target immediately before the one gesture.
            assertTrue("Observed IME key became stale before injection", key.node.refresh())
            val liveBounds = Rect().also(key.node::getBoundsInScreen)
            assertTrue("Observed IME key changed before injection", key.node.isVisibleToUser && key.node.isEnabled &&
                matchesImeKey(key.node, label) && liveBounds == key.keyBounds && key.node.windowId == key.windowId &&
                key.node.packageName?.toString() == key.rootPackage &&
                key.node.text?.toString()?.trim().orEmpty().ifEmpty {
                    key.node.contentDescription?.toString()?.trim().orEmpty()
                } == key.label)
            attempt.put("before_gesture", key.snapshot()).put("injected_at_ms", SystemClock.elapsedRealtime())
                .put("injection", "UiAutomation.injectInputEvent(sync=true)")
            val events = JSONArray()
            attempt.put("motion_events", events)
            val upCompleted = injectSingleTap(key.keyBounds.exactCenterX(), key.keyBounds.exactCenterY(), inputMethodWindow = true, trace = events)
            attempt.put("original_node_refresh_after_gesture", key.node.refresh()).put("gesture_completed", clockSnapshot())
            val after = findFreshImeKey(label)
            try { attempt.put("after_gesture", after?.snapshot() ?: JSONObject.NULL) }
            finally { after?.node?.recycle() }
            attempt.put("view_after_gesture", inputViewSnapshot()).put("ime_visible_after_gesture", imeVisible())
            if (label == "Enter") screenshot(if (stage == "newline") "04d3-ime-after-enter" else "04d2c-ime-after-$stage-enter")
            return upCompleted
        } finally { key.node.recycle() }
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
    private fun assertClassicFixtureCodec() {
        val diag = RdpConnector.getDiag()
        assertTrue("Classic fixture must not negotiate GFX: $diag", diag.contains("gfx=not-negotiated\n"))
        assertTrue("Classic fixture must decode no GFX codec: $diag", diag.contains("gfx-last-codec=0\n"))
        assertTrue("Classic fixture must decode no GFX commands: $diag", diag.contains("gfx-decoded-commands=0\n"))
    }
    private fun assertColor(x: Int, y: Int, expected: Int) = waitFor("exact RGB pixel $x,$y") { colorAt(x, y)?.let { sameColor(it, expected) } == true }
    private fun screenshot(name: String) {
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(evidence, "$keyboardScreenshotPrefix$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
