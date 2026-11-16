package com.rdp.sync

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.data.Device
import com.rdp.sync.network.LinuxTextInputMode
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.ui.MainActivity
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.screens.RdpConnectionScreen
import com.rdp.sync.ui.theme.RdpSyncTheme
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
class UbuntuLiveChineseImeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

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

    private fun findDesktopView(root: View): RdpDesktopView? {
        if (root is RdpDesktopView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findDesktopView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun awaitRemoteEntryText(statePort: Int, expected: String, timeoutMs: Long = 15_000L): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            runCatching { httpJson(statePort, "GET", "/state") }.onSuccess { s ->
                last = s
                val txt = s.optString("entry_text", "")
                assertFalse(
                    "Stale Ubuntu clipboard trap was pasted into desktop! state=$s",
                    txt.contains("OLD_CLIPBOARD_TRAP")
                )
                if (txt == expected) return s
            }
            SystemClock.sleep(150)
        }
        throw AssertionError(
            "Timed out waiting for remote Ubuntu GTK3 TextView == '$expected'; last state=$last; diag=${RdpConnector.getDiag()}"
        )
    }

    @Test
    fun liveUbuntuDesktopDirectChineseAndSendText() {
        val args = InstrumentationRegistry.getArguments()
        val backend = requireNotNull(args.getString("ubuntuLiveBackend")) { "Missing ubuntuLiveBackend" }
        val rdpPort = requireNotNull(args.getString("ubuntuLivePort")).toInt()
        val statePort = requireNotNull(args.getString("ubuntuLiveStatePort")).toInt()
        val expectedFp = requireNotNull(args.getString("ubuntuLiveFingerprint")).lowercase()
        require(expectedFp.matches(Regex("[0-9a-f]{64}"))) { "Invalid fingerprint: $expectedFp" }

        val ctx = instrumentation.targetContext
        assertTrue("RdpConnector.initialize failed", RdpConnector.initialize(ctx))

        ctx.getSharedPreferences("certificate_trust_v1", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("session_preferences", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("resolution_mode", "PROFILE")
            .putString("display_quality", "BALANCED")
            .putString("linux_ime_mode", LinuxTextInputMode.SMART_AUTO.name)
            .commit()
        RdpConnector.linuxTextInputMode = LinuxTextInputMode.SMART_AUTO

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

        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                RdpSyncTheme {
                    RdpConnectionScreen(device = device, onBack = {})
                }
            }
        }

        try {
            // 1. Complete first-time TOFU certificate verification through the real UI
            val verifyLabel = ctx.getString(R.string.fingerprint_verified)
            val savePinLabel = ctx.getString(R.string.fingerprint_save)
            compose.waitUntil(timeoutMillis = 25_000L) {
                runCatching {
                    compose.onNodeWithText(verifyLabel).fetchSemanticsNode()
                    true
                }.getOrDefault(false)
            }
            runCatching { compose.onNodeWithText(verifyLabel).performScrollTo() }
            compose.onNodeWithText(verifyLabel).performClick()
            runCatching { compose.onNodeWithText(savePinLabel).performScrollTo() }
            compose.onNodeWithText(savePinLabel).performClick()

            // 2. Wait for live RDP connection + decoded desktop frame + Unix peer auto-detection
            val connDeadline = SystemClock.elapsedRealtime() + 30_000L
            while (SystemClock.elapsedRealtime() < connDeadline) {
                if (RdpConnector.isConnected() && RdpConnector.getFrameBitmap() != null) break
                SystemClock.sleep(150)
            }
            assertTrue("RDP session did not connect: ${RdpConnector.getStatus()}\n${RdpConnector.getDiag()}", RdpConnector.isConnected())

            // 3. Wait for the live Ubuntu GTK3 graphical window (/state) to map & become ready
            val gtkDeadline = SystemClock.elapsedRealtime() + 25_000L
            var initialState: JSONObject? = null
            while (SystemClock.elapsedRealtime() < gtkDeadline) {
                val st = runCatching { httpJson(statePort, "GET", "/state") }.getOrNull()
                if (st != null && st.optBoolean("ready", false)) {
                    initialState = st
                    break
                }
                SystemClock.sleep(200)
            }
            assertNotNull("Ubuntu GTK3 desktop window never became ready", initialState)

            // Wait briefly for CLIPRDR MonitorReady / capabilities handshake to finish
            SystemClock.sleep(600)
            assertTrue(
                "Expected isPeerUnix() == true on Ubuntu ($backend), diag=${RdpConnector.getDiag()}",
                RdpConnector.isPeerUnix()
            )

            // 4. Arm the stale clipboard trap ("OLD_CLIPBOARD_TRAP_旧剪贴板污染测试") ON UBUNTU WHILE CONNECTED!
            val armedState = httpJson(statePort, "POST", "/arm-trap")
            assertTrue("Failed to arm stale clipboard trap on Ubuntu: $armedState", armedState.optBoolean("trap_armed", false))
            assertEquals("OLD_CLIPBOARD_TRAP_旧剪贴板污染测试", armedState.optString("trap_verified_text"))

            // Click dead-center of the GTK3 TextView (640, 360) and confirm active X11/Wayland input focus
            for (attempt in 1..6) {
                assertEquals(0, RdpConnector.sendPointerEvent(640, 360, 1))
                SystemClock.sleep(250)
                val focusCheck = runCatching { httpJson(statePort, "GET", "/state") }.getOrNull()
                if (focusCheck != null && focusCheck.optBoolean("entry_focused", false)) break
            }

            var desktopView: RdpDesktopView? = null
            compose.activityRule.scenario.onActivity { activity ->
                desktopView = findDesktopView(activity.window.decorView)
            }
            val view = requireNotNull(desktopView) { "RdpDesktopView not found in view hierarchy" }

            // 5. Direct mobile Chinese IME commit #1 (with Pinyin composing pre-edit first!)
            var ic: android.view.inputmethod.InputConnection? = null
            instrumentation.runOnMainSync {
                view.requestFocus()
                ic = view.onCreateInputConnection(EditorInfo())
                assertNotNull("onCreateInputConnection returned null", ic)
                ic!!.setComposingText("ni hao ubuntu", 1)
            }
            SystemClock.sleep(200)
            val duringCompose = httpJson(statePort, "GET", "/state")
            assertEquals("Composing pinyin must remain local until committed", "", duringCompose.optString("entry_text"))

            val commit1 = "你好Ubuntu桌面"
            instrumentation.runOnMainSync {
                ic!!.commitText(commit1, 1)
            }
            val afterCommit1 = awaitRemoteEntryText(statePort, commit1)

            // 6. Immediate consecutive Chinese IME commit #2 (verifies initialFormatListSent never drops 2nd+ commits!)
            val commit2 = "，连续输入不丢字"
            instrumentation.runOnMainSync {
                ic!!.commitText(commit2, 1)
            }
            val afterCommit2 = awaitRemoteEntryText(statePort, commit1 + commit2)

            // 7. Exercise the real UI "发送文字" (Send text) dialog via MagneticEdgeHud!
            val menuDesc = ctx.getString(R.string.session_menu)
            val sendTextLabel = ctx.getString(R.string.send_text)
            val textFieldLabel = ctx.getString(R.string.text_field)
            val sendBtnLabel = ctx.getString(R.string.send)

            compose.onNodeWithContentDescription(menuDesc).performClick()
            compose.waitUntil(timeoutMillis = 10_000L) {
                runCatching {
                    compose.onNodeWithText(sendTextLabel).fetchSemanticsNode()
                    true
                }.getOrDefault(false)
            }
            runCatching { compose.onNodeWithText(sendTextLabel).performScrollTo() }
            compose.onNodeWithText(sendTextLabel).performClick()

            val sendDialogPayload = " [发送文字100%正常]"
            compose.waitUntil(timeoutMillis = 10_000L) {
                runCatching {
                    compose.onNodeWithText(textFieldLabel).fetchSemanticsNode()
                    true
                }.getOrDefault(false)
            }
            compose.onNodeWithText(textFieldLabel).performTextReplacement(sendDialogPayload)
            compose.onNodeWithText(sendBtnLabel).performClick()

            val expectedFull = commit1 + commit2 + sendDialogPayload
            val finalDesktopState = awaitRemoteEntryText(statePort, expectedFull)
            assertTrue(
                "Expected >= 3 real GTK paste-clipboard signals on Ubuntu, got $finalDesktopState",
                finalDesktopState.optInt("paste_signals", 0) >= 3
            )

            // Wait 500ms for remote GTK frame update and capture rendered Android desktop bitmap + JSON receipt
            SystemClock.sleep(500)
            val outDir = File(requireNotNull(ctx.getExternalFilesDir(null)) { "externalFilesDir null" }, "rdpsync-ubuntu-live-ime")
            outDir.mkdirs()
            val frameBmp = RdpConnector.getFrameBitmap()
            if (frameBmp != null) {
                FileOutputStream(File(outDir, "$backend-remote-frame.png")).use { fos ->
                    frameBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
            }
            val receipt = JSONObject()
                .put("backend", backend)
                .put("success", true)
                .put("expected_text", expectedFull)
                .put("observed_text", finalDesktopState.getString("entry_text"))
                .put("paste_signals", finalDesktopState.getInt("paste_signals"))
                .put("display_backend", finalDesktopState.getString("backend"))
                .put("trap_armed_before_input", armedState.getBoolean("trap_armed"))
                .put("trap_text_before_input", armedState.getString("trap_verified_text"))
                .put("after_commit_1", afterCommit1.getString("entry_text"))
                .put("after_commit_2", afterCommit2.getString("entry_text"))
                .put("rdp_diagnostics", RdpConnector.getDiag())
            File(outDir, "$backend-receipt.json").writeText(receipt.toString(2), Charsets.UTF_8)
        } finally {
            RdpConnector.disconnect()
        }
    }
}
