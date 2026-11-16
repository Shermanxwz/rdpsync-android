package com.rdp.sync

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.data.Device
import com.rdp.sync.network.LinuxTextInputMode
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.ui.MainActivity
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.screens.RdpConnectionScreen
import com.rdp.sync.ui.theme.RdpSyncTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real live Ubuntu graphical desktop E2E verification (GNOME Remote Desktop 46 Wayland & xrdp X11).
 * Pre-populates Ubuntu's clipboard with a stale trap ("OLD_CLIPBOARD_TRAP_旧剪贴板污染测试") while connected,
 * then exercises:
 *   1) Direct mobile Chinese IME composition + commit #1 ("你好Ubuntu桌面")
 *   2) Consecutive mobile Chinese IME commit #2 ("，连续输入不丢字")
 *   3) Session menu -> "发送文字" (Send text) dialog (" [发送文字100%正常]")
 * Asserts the remote GTK3 TextView receives the exact combined UTF-8 Chinese text and NEVER pastes
 * the pre-existing stale Ubuntu clipboard trap.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UbuntuLiveChineseImeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(effectContext = StandardTestDispatcher())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val measurements = JSONObject()
    private val boundedScroll by lazy { BoundedFixtureScroll(compose, measurements) }

    private fun httpJson(port: Int, method: String, path: String): JSONObject {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("10.0.2.2", port), 3000)
            sock.soTimeout = 5000
            val req = "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
            val raw = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
            val bodyIdx = raw.indexOf("\r\n\r\n")
            require(bodyIdx >= 0) { "Malformed HTTP response from fixture: $raw" }
            val statusLine = raw.substring(0, raw.indexOf("\r\n"))
            require(statusLine.contains(" 200 ")) { "Non-200 HTTP status: $statusLine" }
            return JSONObject(raw.substring(bodyIdx + 4))
        }
    }

    private fun desktopOrNull(): RdpDesktopView? {
        fun find(v: View): RdpDesktopView? {
            if (v is RdpDesktopView) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    find(v.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        return find(compose.activity.window.decorView)
    }

    private fun desktop(): RdpDesktopView = requireNotNull(desktopOrNull()) { "RdpDesktopView not found" }

    private fun waitFor(reason: () -> String, timeoutMs: Long = 20_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            try {
                if (predicate()) return
            } catch (e: IOException) {
                lastError = e
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("${reason()}; status=${RdpConnector.getStatus()}; diag=${RdpConnector.getDiag()}", lastError)
    }

    private fun awaitRemoteEntryText(statePort: Int, expected: String, timeoutMs: Long = 20_000L): JSONObject {
        var last = JSONObject()
        waitFor({ "remote Ubuntu GTK3 TextView == '$expected' (last=$last)" }, timeoutMs) {
            val s = runCatching { httpJson(statePort, "GET", "/state") }.getOrNull()
            if (s != null) {
                last = s
                val txt = s.optString("entry_text", "")
                assertFalse(
                    "Stale Ubuntu clipboard trap was pasted into desktop! state=$s",
                    txt.contains("OLD_CLIPBOARD_TRAP")
                )
                txt == expected
            } else {
                false
            }
        }
        return last
    }

    @Test
    fun liveUbuntuDesktopDirectChineseAndSendText() {
        val args = InstrumentationRegistry.getArguments()
        val backend = requireNotNull(args.getString("ubuntuLiveBackend")) { "Missing ubuntuLiveBackend" }
        val rdpPort = requireNotNull(args.getString("ubuntuLivePort")).toInt()
        val statePort = requireNotNull(args.getString("ubuntuLiveStatePort")).toInt()
        val expectedFp = requireNotNull(args.getString("ubuntuLiveFingerprint")).lowercase()
        require(expectedFp.matches(Regex("[0-9a-f]{64}"))) { "Invalid fingerprint: $expectedFp" }

        val targetCtx = instrumentation.targetContext
        val actCtx = compose.activity
        assertTrue("RdpConnector.initialize failed", RdpConnector.initialize(targetCtx))

        targetCtx.getSharedPreferences("certificate_trust_v1", Context.MODE_PRIVATE).edit().clear().commit()
        targetCtx.getSharedPreferences("session_preferences", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("resolution_mode", "PROFILE")
            .putString("display_quality", "BALANCED")
            .putString("linux_ime_mode", LinuxTextInputMode.SMART_AUTO.name)
            .commit()
        RdpConnector.linuxTextInputMode = LinuxTextInputMode.SMART_AUTO

        val showing = mutableStateOf(true)
        val device = Device(
            id = 920033,
            name = "Ubuntu Live $backend",
            host = "10.0.2.2",
            port = rdpPort,
            username = "rdpfixture",
            password = "rdpsync-fixture-only",
            width = 1280,
            height = 720
        )

        compose.runOnUiThread {
            compose.activity.setContent {
                boundedScroll.Bind()
                RdpSyncTheme {
                    if (showing.value) {
                        RdpConnectionScreen(device = device, onBack = { showing.value = false })
                    }
                }
            }
        }

        try {
            // 1. Complete first-time TOFU certificate verification through BoundedFixtureScroll + mainClock pump
            val confirmTitle = actCtx.getString(R.string.certificate_confirm)
            val verifyLabel = actCtx.getString(R.string.fingerprint_verified)
            val savePinLabel = actCtx.getString(R.string.fingerprint_save)
            waitFor({ "Ubuntu live RDP certificate challenge" }, 45_000L) {
                RdpConnector.certificateChallenge()?.fingerprint == expectedFp &&
                    compose.onAllNodesWithText(confirmTitle).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(savePinLabel).assertIsNotEnabled()
            boundedScroll.to(compose.onNodeWithText(verifyLabel)).performClick()
            compose.onNodeWithText(savePinLabel).assertIsEnabled().performClick()

            // 2. Wait for live RDP connection + decoded native desktop frame + dismiss Android 15 fullscreen coachmark
            waitFor({ "Ubuntu live RDP connected frame (frameId=${RdpConnector.nativeGetFrameId()})" }, 45_000L) {
                RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0
            }
            acknowledgeSystemImmersiveCoachmark(
                compose.activity,
                { compose.mainClock.advanceTimeBy(32) },
                {},
                { measurements.put("system_immersive_coachmark", it) }
            )

            // 3. Wait for the live Ubuntu GTK3 graphical window (/state) to map & CLIPRDR Unix peer handshake
            var initialState: JSONObject? = null
            waitFor({ "Ubuntu GTK3 desktop window ready (last=$initialState)" }, 30_000L) {
                val st = runCatching { httpJson(statePort, "GET", "/state") }.getOrNull()
                if (st != null) initialState = st
                st != null && st.optBoolean("ready", false)
            }
            assertNotNull(initialState)
            waitFor({ "Ubuntu Unix peer auto-detection" }, 10_000L) {
                RdpConnector.isPeerUnix()
            }

            // 4. Focus the GTK3 TextView at (640, 360) first (provides a real Wayland/X11 user-input serial),
            //    then arm the stale clipboard trap ("OLD_CLIPBOARD_TRAP_旧剪贴板污染测试") ON UBUNTU WHILE CONNECTED!
            var lastFocusState: JSONObject? = null
            waitFor({ "GTK3 TextView focus at (640, 360) (last=$lastFocusState)" }, 10_000L) {
                assertEquals(0, RdpConnector.sendPointerEvent(640, 360, 1))
                SystemClock.sleep(180)
                val focusCheck = runCatching { httpJson(statePort, "GET", "/state") }.getOrNull()
                if (focusCheck != null) lastFocusState = focusCheck
                focusCheck != null && focusCheck.optBoolean("entry_focused", false) && focusCheck.optInt("clicks", 0) >= 1
            }

            val armedState = httpJson(statePort, "POST", "/arm-trap")
            assertTrue("Failed to arm stale clipboard trap on Ubuntu: $armedState", armedState.optBoolean("trap_armed", false))
            assertEquals("OLD_CLIPBOARD_TRAP_旧剪贴板污染测试", armedState.optString("trap_verified_text"))

            // Ensure focus remains inside Gtk.TextView after arming clipboard
            waitFor({ "GTK3 TextView retains focus after arm-trap" }, 8_000L) {
                val s = runCatching { httpJson(statePort, "GET", "/state") }.getOrNull()
                if (s != null && s.optBoolean("entry_focused", false)) {
                    true
                } else {
                    RdpConnector.sendPointerEvent(640, 360, 1)
                    SystemClock.sleep(150)
                    false
                }
            }

            // 5. Direct mobile Chinese IME commit #1 (with Pinyin composing pre-edit first!)
            var ic: android.view.inputmethod.InputConnection? = null
            compose.runOnUiThread {
                val v = desktop()
                v.requestFocus()
                ic = v.onCreateInputConnection(EditorInfo())
                assertNotNull("onCreateInputConnection returned null", ic)
                ic!!.setComposingText("ni hao ubuntu", 1)
            }
            compose.mainClock.advanceTimeBy(200)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
            val duringCompose = httpJson(statePort, "GET", "/state")
            assertEquals("Composing pinyin must remain local until committed", "", duringCompose.optString("entry_text"))

            val commit1 = "你好Ubuntu桌面"
            compose.runOnUiThread {
                ic!!.commitText(commit1, 1)
            }
            val afterCommit1 = awaitRemoteEntryText(statePort, commit1)

            // 6. Immediate consecutive Chinese IME commit #2 (verifies initialFormatListSent never drops 2nd+ commits!)
            val commit2 = "，连续输入不丢字"
            compose.runOnUiThread {
                ic!!.commitText(commit2, 1)
            }
            val afterCommit2 = awaitRemoteEntryText(statePort, commit1 + commit2)
            compose.runOnUiThread {
                ic?.closeConnection()
            }

            // 7. Exercise the real UI "发送文字" (Send text) dialog via MagneticEdgeHud!
            val menuDesc = actCtx.getString(R.string.session_menu)
            val sendTextLabel = actCtx.getString(R.string.send_text)
            val sendBtnLabel = actCtx.getString(R.string.send)
            val sendDialogPayload = " [发送文字100%正常]"

            compose.onNodeWithContentDescription(menuDesc).performTouchInput { click() }
            waitFor({ "session menu popup with send_text" }, 10_000L) {
                compose.onAllNodesWithText(sendTextLabel).fetchSemanticsNodes().size == 1
            }
            boundedScroll.to(compose.onNodeWithText(sendTextLabel)).assertIsEnabled().performClick()

            waitFor({ "send text dialog input field" }, 10_000L) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasSetTextAction()).performTextReplacement(sendDialogPayload)
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            compose.onNodeWithText(sendBtnLabel).assertIsEnabled().performClick()

            val expectedFull = commit1 + commit2 + sendDialogPayload
            val finalDesktopState = awaitRemoteEntryText(statePort, expectedFull)
            assertTrue(
                "Expected >= 3 real GTK paste-clipboard signals on Ubuntu, got $finalDesktopState",
                finalDesktopState.optInt("paste_signals", 0) >= 3
            )

            // Wait 500ms for remote GTK frame update and capture rendered Android desktop bitmap + JSON receipt
            compose.mainClock.advanceTimeBy(500)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            val outDir = File(requireNotNull(targetCtx.getExternalFilesDir(null)) { "externalFilesDir null" }, "rdpsync-ubuntu-live-ime")
            outDir.mkdirs()
            val frameBmp = RdpConnector.getFrameBitmap() ?: runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
            if (frameBmp != null) {
                FileOutputStream(File(outDir, "$backend-remote-frame.png")).use { fos ->
                    frameBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
            }
            val receipt = JSONObject()
                .put("backend", backend)
                .put("expected_text", expectedFull)
                .put("observed_text", finalDesktopState.getString("entry_text"))
                .put("paste_signals", finalDesktopState.getInt("paste_signals"))
                .put("owner_changes", finalDesktopState.optInt("owner_changes", 0))
                .put("display_backend", finalDesktopState.getString("backend"))
                .put("trap_armed_before_input", armedState.getBoolean("trap_armed"))
                .put("trap_text_before_input", armedState.getString("trap_verified_text"))
                .put("after_commit_1", afterCommit1.getString("entry_text"))
                .put("after_commit_2", afterCommit2.getString("entry_text"))
                .put("rdp_diagnostics", RdpConnector.getDiag())
                .put("success", true)
            File(outDir, "$backend-receipt.json").writeText(receipt.toString(2), Charsets.UTF_8)
        } finally {
            compose.runOnUiThread { showing.value = false }
            RdpConnector.disconnect()
        }
    }
}
