package com.rdp.sync

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.data.Device
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.MainActivity
import com.rdp.sync.ui.screens.RdpConnectionScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** Actual system SAF UI and native GNOME URI/FUSE/CLIPRDR bytes, not a mock peer. */
@RunWith(AndroidJUnit4::class)
class GnomeTransferInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val evidence by lazy { File(context.getExternalFilesDir(null), "gnome-transfer-evidence").apply { mkdirs() } }
    private val measurements = JSONObject()
    private val pickerEvents = JSONArray()
    private val folder = "rdpsync-transfer-${SystemClock.uptimeMillis()}"
    private val downloads get() = "/sdcard/Download/$folder"

    @Test fun explicitSafAndNativeClipboardFileRoundTrip() {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("gnomeFixture"))
        val fingerprint = requireNotNull(args.getString("gnomeFixtureFingerprint"))
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(RdpConnector.initialize(context))
        val originalService = automation.serviceInfo
        automation.serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = originalService.eventTypes; feedbackType = originalService.feedbackType
            notificationTimeout = originalService.notificationTimeout
            flags = originalService.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val showing = mutableStateOf(true)
        context.getSharedPreferences("session_preferences", Context.MODE_PRIVATE).edit().clear()
            .putString("resolution_mode", "PROFILE").putString("display_quality", "SHARP").commit()
        val device = Device(id = 910002, name = "GNOME synthetic file transfer", host = "10.0.2.2", port = 33891,
            username = "rdpfixture", password = "rdpsync-fixture-only", width = 1024, height = 768)
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme {
                BackHandler(enabled = showing.value) { showing.value = false }
                if (showing.value) RdpConnectionScreen(device) { showing.value = false }
            }
        } }
        try {
            waitFor("explicit certificate prompt") { compose.onAllNodesWithText("确认服务器证书").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(fingerprint).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("我已通过独立渠道核对 SHA-256 指纹").performScrollTo().performClick()
            compose.onNodeWithText("保存指纹并重连").performClick()
            waitFor("actual GNOME frame before transfer") { RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 }
            waitFor("actual Progressive frame before transfer") {
                val diag = RdpConnector.getDiag()
                diag.contains("gfx=software-ready") && diag.contains("gfx-last-codec=9") &&
                    (Regex("gfx-decoded-commands=([0-9]+)").find(diag)?.groupValues?.get(1)?.toLongOrNull() ?: 0) > 0
            }
            measurements.put("system_immersive_coachmark", acknowledgeSystemImmersiveCoachmark(compose.activity,
                { compose.mainClock.advanceTimeBy(32) }, { screenshot("00-system-immersive-coachmark") },
                { measurements.put("system_immersive_coachmark", it) }))
            focusRealWaylandSurface()
            val firstGeneration = RdpConnector.sessionGeneration()
            assertEquals("File channel must be opt-in", -2L, RdpConnector.nativeFileTransferState()[1])
            openFiles("发送文件")
            compose.onNodeWithText("启用文件传输并重新连接？").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            assertEquals(firstGeneration, RdpConnector.sessionGeneration())
            assertEquals(-2L, RdpConnector.nativeFileTransferState()[1])
            openFiles("发送文件")
            compose.onNodeWithText("启用并重新连接").performClick()
            waitFor("confirmed reconnect negotiates actual file streaming", 45_000) {
                val s = RdpConnector.nativeFileTransferState()
                RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 &&
                    RdpConnector.sessionGeneration() != firstGeneration && s.size == 10 && s[1] == 1L
            }
            assertEquals("Fresh opted-in connection has exact sending budget", 32L * 1024 * 1024, RdpConnector.nativeFileTransferState()[9])
            measurements.put("opt_in_reconnected", true).put("streaming_generation", RdpConnector.sessionGeneration())
            waitFor("file dialog after confirmed reconnect") { compose.onAllNodesWithText("选择文件").fetchSemanticsNodes().isNotEmpty() }
            seedOwnedDocuments()
            compose.onNodeWithText("选择文件").performScrollTo().performClick()
            navigateDocumentsFolder("source")
            val originals = samples("android")
            originals.keys.forEachIndexed { index, name -> tapDocumentLabel(name, longPress = index == 0) }
            screenshot("01-real-saf-multiselect")
            tapDocumentAction("confirm-multiselect") { node ->
                node.viewIdResourceName?.endsWith("/action_menu_select") == true ||
                    node.text?.toString() in setOf("Open", "OPEN", "Select", "SELECT") || node.contentDescription?.toString() in setOf("Open", "Select")
            }
            waitFor("selected files privately staged") { compose.onAllNodesWithText("已暂存，尚未提供给远程").fetchSemanticsNodes().isNotEmpty() }
            originals.keys.forEach { name -> compose.onNodeWithText("$name · ${originals.getValue(name).size} 字节").performScrollTo().assertExists() }
            compose.onNodeWithText("提供给远程剪贴板").performScrollTo().performClick()
            waitFor("explicit outbound offer retained") { RdpConnector.nativeFileTransferState()[2] > 0 }
            val offer = RdpConnector.nativeFileTransferState()[2]
            assertTrue(RdpConnector.nativeFileTransferOfferRetained(offer))
            compose.onNodeWithText("返回远程桌面").performClick()
            waitFor("dialog closes but remote offer remains") {
                compose.onAllNodesWithText("文件传输").fetchSemanticsNodes().isEmpty() && RdpConnector.nativeFileTransferOfferRetained(offer)
            }
            focusRealWaylandSurface()
            val servedBeforeRead = RdpConnector.nativeFileTransferState()[3]
            action("read-android-files")
            waitFor("real GNOME FUSE reads exact Android files", 45_000) {
                val state = transferState()
                if (state.optString("phase") == "failed") throw AssertionError(state.toString())
                state.optString("phase") == "received"
            }
            val uploaded = transferState().getJSONObject("received")
            assertHashes(originals, uploaded)
            val servedAfterRead = RdpConnector.nativeFileTransferState()[3]
            assertTrue("Current offer must actually serve peer bytes, not a stale clipboard copy", servedAfterRead > servedBeforeRead)
            measurements.put("android_to_gnome_fuse", uploaded).put("offer_survived_dialog_hide", true)
                .put("current_offer_token", offer).put("bytes_served_before_peer_read", servedBeforeRead).put("bytes_served_after_peer_read", servedAfterRead)
            val budgetAfterRead = RdpConnector.nativeFileTransferState()[9]
            assertTrue("Actual peer reads consume connection-wide budget", budgetAfterRead in 1 until 32L * 1024 * 1024)
            measurements.put("remaining_send_budget_after_peer_read", budgetAfterRead)
            assertTrue(RdpConnector.nativeFileTransferOfferRetained(offer))
            screenshot("02-remote-read-completed")
            openFiles("发送文件")
            compose.onNodeWithText("停止共享").performScrollTo().performClick()
            waitFor("explicit stop-sharing revokes offer") { !RdpConnector.nativeFileTransferOfferRetained(offer) }
            assertEquals("Cancel must not reset sending budget", budgetAfterRead, RdpConnector.nativeFileTransferState()[9])
            assertTrue("Cancel must preserve desktop session", RdpConnector.isConnected())
            compose.onNodeWithText("返回远程桌面").performClick()
            focusRealWaylandSurface()
            action("offer-server-files")
            waitFor("GTK publishes synthetic server URI list") { transferState().optString("phase") == "offered" }
            openFiles("接收文件")
            compose.onNodeWithText("读取远程文件列表").performScrollTo().performClick()
            waitFor("metadata awaits explicit receive confirmation") { compose.onAllNodesWithText("等待你确认接收").fetchSemanticsNodes().isNotEmpty() }
            val remote = samples("server")
            remote.keys.forEach { name -> compose.onNodeWithText("$name · ${remote.getValue(name).size} 字节").performScrollTo().assertExists() }
            screenshot("03-remote-metadata-before-consent")
            compose.onNodeWithText("确认接收这些文件").performScrollTo().performClick()
            waitFor("real streamed files received", 45_000) { compose.onAllNodesWithText("文件已接收，请选择目录导出").fetchSemanticsNodes().isNotEmpty() }
            for (name in remote.keys) {
                val beforeExportCount = directoryHashes("destination").size
                compose.onNodeWithContentDescription("导出文件：$name").performScrollTo().performClick()
                navigateDocumentsFolder("destination")
                screenshot("04-destination-${remote.keys.indexOf(name)}")
                tapDocumentAction("grant-owned-subfolder") { node -> node.text?.toString()?.uppercase() == "USE THIS FOLDER" }
                tapDocumentAction("confirm-tree-grant") { node -> node.text?.toString()?.uppercase() == "ALLOW" }
                waitFor("export returns with readback verification") {
                    compose.onAllNodesWithText("已创建新文件并回读校验 SHA-256；暂存原件仍保留。云端持久化由文档提供方负责。").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("文件已接收，请选择目录导出").fetchSemanticsNodes().isNotEmpty() &&
                        directoryHashes("destination").size == beforeExportCount + 1
                }
            }
            val actualHashes = directoryHashes("destination")
            val expectedHashes = remote.values.map(::sha256)
            val sentinel = "Original destination must remain unchanged\n".toByteArray()
            assertEquals("Three new children plus untouched collision sentinel", 4, actualHashes.size)
            assertEquals(sha256(sentinel), actualHashes.getValue("server-binary.bin"))
            assertEquals(expectedHashes.sorted(), actualHashes.filterKeys { it != "server-binary.bin" }.values.sorted())
            measurements.put("gnome_to_saf_export", JSONObject(actualHashes)).put("collision_original_unchanged", true)
            screenshot("05-real-transfer-roundtrip")

            // A new offer stays alive while its dialog is hidden, but real route
            // Back disposal must revoke it. Previously copied remote bytes remain
            // ordinary remote files; this does not claim retroactive deletion.
            compose.onNodeWithText("发送文件", useUnmergedTree = true).performClick()
            compose.onNodeWithText("选择文件").performScrollTo().performClick()
            navigateDocumentsFolder("source")
            originals.keys.forEachIndexed { index, name -> tapDocumentLabel(name, longPress = index == 0) }
            tapDocumentAction("confirm-reoffer-selection") { node -> node.viewIdResourceName?.endsWith("/action_menu_select") == true || node.text?.toString()?.uppercase() in setOf("OPEN", "SELECT") }
            waitFor("restaged explicit selection") { compose.onAllNodesWithText("已暂存，尚未提供给远程").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("提供给远程剪贴板").performScrollTo().performClick()
            waitFor("fresh offer") { RdpConnector.nativeFileTransferState()[2] > 0 }
            val routeOffer = RdpConnector.nativeFileTransferState()[2]
            assertEquals("Re-offering must not reset sending budget", budgetAfterRead, RdpConnector.nativeFileTransferState()[9])
            compose.onNodeWithText("返回远程桌面").performClick()
            assertTrue(RdpConnector.nativeFileTransferOfferRetained(routeOffer))
            sendSystemBack()
            waitFor("route disposal revokes actual native offer", 10_000) { !showing.value && !RdpConnector.nativeFileTransferOfferRetained(routeOffer) }
            waitFor("route teardown disconnects owned session") { !RdpConnector.isConnected() }
            measurements.put("route_back_revoked_offer", true).put("success", true)
        } finally {
            measurements.put("picker_events", pickerEvents).put("diagnostics", RdpConnector.getDiag())
                .put("native_transfer_state", RdpConnector.nativeFileTransferState().joinToString(","))
            screenshot("99-transfer-final")
            File(evidence, "measurements.json").writeText(measurements.toString(2))
            File(evidence, "accessibility-final.txt").writeText(documentNodes { true }.joinToString("\n") { node ->
                try { "${node.packageName} ${node.viewIdResourceName} ${node.text} ${node.contentDescription}" } finally { node.recycle() }
            })
            shell("mkdir -p /sdcard/Download/rdpsync-gnome-transfer-evidence")
            shell("cp -R ${evidence.absolutePath}/. /sdcard/Download/rdpsync-gnome-transfer-evidence/")
            compose.runOnUiThread { showing.value = false }
            RdpConnector.disconnect()
            automation.serviceInfo = originalService
        }
    }

    private fun samples(direction: String): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "$direction-binary.bin" to ByteArray(262149) { ((it * 31 + 7) and 255).toByte() },
        "$direction-empty.bin" to byteArrayOf(),
        (if (direction == "server") "远程文件.txt" else "安卓文件.txt") to "RdpSync synthetic 文件\n\u0000🙂\n".toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assertHashes(files: Map<String, ByteArray>, actual: JSONObject) {
        assertEquals(files.size, actual.length())
        files.forEach { (name, bytes) ->
            val value = actual.getJSONObject(name)
            assertEquals(bytes.size.toLong(), value.getLong("size")); assertEquals(sha256(bytes), value.getString("sha256"))
            assertTrue(value.getBoolean("via_gnome_fuse"))
            assertEquals("actual /proc/self/mountinfo", value.getString("mount_metadata_origin"))
            assertTrue(value.getJSONObject("mount").getString("filesystem_type").startsWith("fuse"))
        }
    }
    private fun seedOwnedDocuments() {
        shell("mkdir -p $downloads/source $downloads/destination")
        val local = File(evidence, "originals").apply { mkdirs() }
        samples("android").forEach { (name, bytes) ->
            File(local, name).writeBytes(bytes); shell("cp ${local.absolutePath}/$name $downloads/source/$name")
        }
        val sentinel = File(local, "sentinel.bin").apply { writeText("Original destination must remain unchanged\n") }
        shell("cp ${sentinel.absolutePath} $downloads/destination/server-binary.bin")
        assertEquals(samples("android").mapValues { sha256(it.value) }, directoryHashes("source"))
    }
    private fun directoryHashes(child: String): Map<String, String> {
        require(child in setOf("source", "destination"))
        val raw = shell("find $downloads/$child -maxdepth 1 -type f -exec sha256sum {} +")
        return raw.lineSequence().filter { it.isNotBlank() }.associate { line ->
            val match = requireNotNull(Regex("^([0-9a-f]{64})  (.+)$").matchEntire(line)) { raw }
            File(match.groupValues[2]).name to match.groupValues[1]
        }
    }
    private fun openFiles(label: String) {
        waitFor("focused fullscreen menu") { compose.onAllNodesWithContentDescription("会话菜单").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("会话菜单").performTouchInput { click() }
        compose.onNodeWithText(label).performScrollTo().performClick()
    }
    private fun focusRealWaylandSurface() {
        fun find(view: View): RdpDesktopView? {
            if (view is RdpDesktopView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        var desktop: RdpDesktopView? = null
        waitFor("attached focused desktop before real clipboard owner input") {
            compose.runOnUiThread { desktop = find(compose.activity.window.decorView) }
            var ready = false
            compose.runOnUiThread { ready = desktop?.let { it.isShown && it.hasWindowFocus() && it.width > 0 } == true }
            ready
        }
        val before = request("GET", "/state").getInt("clicks")
        var x = 0f; var y = 0f
        compose.runOnUiThread {
            val view = requireNotNull(desktop); val point = view.viewportSnapshot().toScreen(400f, 300f)
            val origin = IntArray(2); view.getLocationOnScreen(origin)
            assertTrue(point.x >= 0 && point.y >= 0 && point.x < view.width && point.y < view.height)
            x = origin[0] + point.x; y = origin[1] + point.y
        }
        physicalTap(x, y, 40)
        waitFor("real Wayland input establishes focused clipboard owner") {
            val s = request("GET", "/state"); val position = s.optJSONArray("last_release")
            s.getInt("clicks") == before + 1 && position != null &&
                kotlin.math.abs(position.getDouble(0) - 400) <= 1 && kotlin.math.abs(position.getDouble(1) - 300) <= 1
        }
    }
    private fun navigateDocumentsFolder(child: String) {
        waitFor("real system DocumentsUI") { documentNodes { true }.also { nodes -> nodes.forEach { it.recycle() } }.isNotEmpty() }
        tapDocumentAction("show-document-roots") { it.contentDescription?.toString() in setOf("Show roots", "Open navigation drawer") }
        tapDocumentLabel("Downloads")
        tapDocumentLabel(folder)
        tapDocumentLabel(child)
    }
    private fun tapDocumentLabel(label: String, longPress: Boolean = false) = tapDocumentAction(label, longPress) { it.text?.toString() == label }
    private fun tapDocumentAction(reason: String, longPress: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean) {
        var target: AccessibilityNodeInfo? = null
        waitFor("observed DocumentsUI target: $reason", 15_000) {
            val nodes = documentNodes(predicate)
            if (nodes.size == 1) { target = nodes.single(); true }
            else { nodes.forEach { it.recycle() }; false }
        }
        val node = requireNotNull(target)
        try {
            assertTrue(node.refresh()); assertTrue(node.isVisibleToUser && node.isEnabled); assertTrue(predicate(node))
            val bounds = Rect(); node.getBoundsInScreen(bounds); assertFalse(bounds.isEmpty)
            pickerEvents.put(JSONObject().put("reason", reason).put("package", node.packageName)
                .put("resource_id", node.viewIdResourceName).put("bounds", bounds.toShortString())
                .put("long_press", longPress).put("uptime", SystemClock.uptimeMillis()))
            physicalTap(bounds.exactCenterX(), bounds.exactCenterY(), if (longPress) 650 else 40)
        } finally { node.recycle() }
    }
    private fun documentNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.isEnabled && predicate(node) && found.none { it == node }) found += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> try { visit(child) } finally { child.recycle() } }
        }
        automation.windows.forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) window.root?.let { root ->
                try { if (root.packageName?.toString()?.contains("documentsui") == true) visit(root) } finally { root.recycle() }
            }
        }
        return found
    }
    private fun physicalTap(x: Float, y: Float, holdMs: Long) {
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("System picker physical injection accepted", automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        try { inject(MotionEvent.ACTION_DOWN); SystemClock.sleep(holdMs) } finally { inject(MotionEvent.ACTION_UP) }
    }
    private fun sendSystemBack() {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            assertTrue(automation.injectInputEvent(KeyEvent(time, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0), true))
    }
    private fun transferState() = request("GET", "/transfer")
    private fun action(action: String) = request("POST", "/transfer/$action")
    private fun request(method: String, path: String): JSONObject = Socket().use { socket ->
        socket.connect(InetSocketAddress("10.0.2.2", 8768), 2000); socket.soTimeout = 2000
        socket.getOutputStream().write("$method $path HTTP/1.0\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val response = socket.getInputStream().bufferedReader().readText()
        assertTrue(response.startsWith("HTTP/1.0 200") || response.startsWith("HTTP/1.0 202"))
        JSONObject(response.substringAfter("\r\n\r\n"))
    }
    private fun waitFor(reason: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeBy(32)
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("$reason: ${RdpConnector.getDiag()}")
    }
    private fun screenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap -> try { File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() } }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
}
