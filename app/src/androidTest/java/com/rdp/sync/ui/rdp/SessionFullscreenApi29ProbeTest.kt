package com.rdp.sync.ui.rdp

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.Insets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in API29 regression. Imports production layout; no copy, RDP connection, or network. */
@RunWith(AndroidJUnit4::class)
class SessionFullscreenApi29ProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "api29-fullscreen-probe").apply { mkdirs() }
    }
    private val report = JSONObject()
    private val logicalCallbacks = JSONArray()
    private val samples = JSONArray()
    @Volatile private var logical = IntSize.Zero
    @Volatile private var available = IntSize.Zero
    @Volatile private var phase = "initial"
    private var editor: EditText? = null
    private val fullscreen = mutableStateOf(true)
    @Volatile private var baseControlClicks = 0
    @Volatile private var imeControlClicks = 0

    @Test fun actualPre30FullscreenBarsAndImeKeepLogicalViewportStable() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Only run in the explicit isolated API29 gate", args.getString("api29FullscreenProbe") == "true")
        assertEquals("This diagnostic is specifically the pre-30 runtime boundary", 29, Build.VERSION.SDK_INT)
        val commit = requireNotNull(args.getString("productionCommit"))
        val tree = requireNotNull(args.getString("productionTree"))
        require(commit.matches(Regex("[a-f0-9]{40}")) && tree.matches(Regex("[a-f0-9]{40}")))
        report.put("production_commit", commit).put("production_tree", tree)
            .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
            .put("scope", "Production MainActivity, SessionSystemBars and SessionViewportLayout; local EditText only; no RDP connection/network/credentials; no claim about actual app transfer or session-menu UI")
            .put("logical_callbacks", logicalCallbacks).put("samples", samples)
        val previousSetting = shell("settings get secure show_ime_with_hard_keyboard").trim()
        require(previousSetting in setOf("0", "1", "null"))
        val originalOrientation = compose.activity.requestedOrientation
        val activity = compose.activity
        val originalBars = bars()
        report.put("original_bars", originalBars)
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose.runOnUiThread { activity.setContent { MaterialTheme { ProbeContent() } } }
            compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            acknowledgeFirstUseImmersiveCoachmark()
            awaitFullGeometry("portrait", landscape = false)
            val portrait = logical
            val editorIdentity = System.identityHashCode(editor)
            assertTrue("Fresh fullscreen must fill at least95% of cutout-free API29 decor",
                geometry().getInt("view_height").toDouble() / geometry().getInt("root_height") >= 0.95)
            exerciseKeyboard("portrait")
            exerciseChromeRoundtrip("portrait", landscape = false, editorIdentity = editorIdentity)
            observeOneLegacyEdgeGesture()
            phase = "rotation"
            compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitFullGeometry("landscape", landscape = true)
            assertSame("Manifest configChanges should retain the actual MainActivity", activity, compose.activity)
            assertEquals("Fullscreen rotation must retain actual editor/InputConnection owner", editorIdentity, System.identityHashCode(editor))
            assertNotEquals("Rotation must still change real logical geometry", portrait, logical)
            val landscape = geometry()
            assertSafeEdges(landscape)
            assertTrue("Fullscreen landscape local desktop must fill >=95% of cutout-free decor: $landscape",
                landscape.getInt("view_height").toDouble() / landscape.getInt("root_height") >= 0.95)
            exerciseKeyboard("landscape")
            exerciseChromeRoundtrip("landscape", landscape = true, editorIdentity = editorIdentity)
            phase = "dispose-fullscreen"
            compose.runOnUiThread { activity.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) } } }
            waitFor("disposing fullscreen restores original bars") {
                val current = bars()
                current.getBoolean("status") == originalBars.getBoolean("status") &&
                    current.getBoolean("navigation") == originalBars.getBoolean("navigation")
            }
            report.put("restored_bars", bars())
            screenshot("08-exit-restored-bars")
            report.put("success", true)
        } catch (error: Throwable) {
            report.put("success", false).put("failure", error.stackTraceToString())
            throw error
        } finally {
            runCatching { screenshot("99-final") }
            runCatching { if (editor?.isAttachedToWindow == true) samples.put(geometry()) }
            synchronized(logicalCallbacks) { File(evidence, "measurements.json").writeText(report.toString(2)) }
            runCatching {
                compose.runOnUiThread {
                    editor?.let { imm(it).hideSoftInputFromWindow(it.windowToken, 0) }
                    activity.requestedOrientation = originalOrientation
                }
            }
            shell(if (previousSetting == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $previousSetting")
            // UiAutomation shell owns the public evidence export; no storage permission
            // or production app changes are needed for this disposable runner.
            require(evidence.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
            shell("mkdir -p /sdcard/Download/rdpsync-api29-fullscreen")
            shell("cp -R ${evidence.absolutePath}/. /sdcard/Download/rdpsync-api29-fullscreen/")
        }
    }

    @Composable private fun ProbeContent() {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        SessionSystemBars(fullscreen.value)
        Box(Modifier.fillMaxSize().onSizeChanged { available = it }) {
            SessionViewportLayout(
                fullscreen = fullscreen.value,
                onLogicalViewport = {
                    logical = it
                    synchronized(logicalCallbacks) {
                        logicalCallbacks.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime())
                            .put("phase", phase).put("width", it.width).put("height", it.height))
                    }
                },
                header = {
                    if (!landscape) Box(Modifier.fillMaxWidth().height(64.dp).background(ComposeColor.DarkGray)) {
                        Text("API29 local layout probe")
                    }
                },
                desktop = {
                    AndroidView(factory = { context ->
                        EditText(context).apply {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                            setText("Local editor only — no remote desktop connection")
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.rgb(17, 34, 51))
                            setPadding(0, 0, 0, 0)
                            editor = this
                        }
                    }, modifier = Modifier.fillMaxSize())
                },
                toolbar = { Row(Modifier.fillMaxWidth().height(56.dp).background(ComposeColor.DarkGray)) {
                    Text("Fixed 56dp session tools")
                    IconButton(onClick = { baseControlClicks++ }) { Icon(Icons.Default.Keyboard, "Probe keyboard control") }
                } },
                imeControls = { Row(Modifier.width(144.dp).height(48.dp).background(ComposeColor.Gray)) {
                    IconButton(onClick = { imeControlClicks++ }) { Icon(Icons.Default.Keyboard, "Probe keyboard control") }
                    Text("IME tools only")
                } }
            )
        }
    }

    private fun acknowledgeFirstUseImmersiveCoachmark() {
        // Read only: never preseed/disable the OS education preference. On a fresh
        // API29 AVD the real first-use overlay owns focus until its observed button
        // is acknowledged. Subsequent runs may already have a user confirmation.
        val confirmed = shell("settings get secure immersive_mode_confirmations").trim() == "confirmed"
        report.put("immersive_coachmark_previously_confirmed", confirmed)
        if (confirmed) return
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val snapshots = JSONArray()
        val seenSnapshots = mutableSetOf<String>()
        report.put("coachmark_window_observations", snapshots)
        try {
            waitFor("observed Android first-use immersive coachmark", 15_000) {
                val windows = automation.windows
                try {
                    val metadata = boundedWindowMetadata(windows)
                    report.put("coachmark_latest_windows", metadata)
                    val signature = metadata.toString()
                    if (seenSnapshots.size < 8 && seenSnapshots.add(signature)) {
                        snapshots.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime()).put("windows", metadata))
                    }
                    for (window in windows) {
                        if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                        val root = window.root ?: continue
                        val nodes = mutableListOf<AccessibilityNodeInfo>()
                        fun visit(node: AccessibilityNodeInfo) {
                            if (nodes.size >= 512) { node.recycle(); return }
                            nodes += node
                            for (child in 0 until node.childCount) {
                                if (nodes.size >= 512) break
                                node.getChild(child)?.let(::visit)
                            }
                        }
                        visit(root)
                        try {
                            if (root.packageName?.toString() !in setOf("android", "com.android.systemui")) continue
                            val title = nodes.any { it.windowId == window.id && it.isVisibleToUser &&
                                it.text?.toString() == "Viewing full screen" }
                            if (!title) continue
                            val buttons = nodes.filter { it.windowId == window.id && it.isVisibleToUser && it.isClickable &&
                                it.packageName?.toString() == root.packageName?.toString() &&
                                it.className?.toString() == "android.widget.Button" &&
                                it.text?.toString()?.equals("Got it", ignoreCase = true) == true }
                            assertEquals("Exactly one observed system immersive acknowledgement", 1, buttons.size)
                            val button = buttons.single()
                            val bounds = Rect().also(button::getBoundsInScreen)
                            screenshot("00-observed-system-immersive-coachmark")
                            report.put("immersive_coachmark_acknowledgement", JSONObject()
                                .put("window_id", window.id).put("window_type", window.type)
                                .put("window_title", window.title?.toString()).put("package", button.packageName?.toString())
                                .put("view_id", button.viewIdResourceName).put("bounds", bounds.toShortString())
                                .put("elapsed_ms", SystemClock.elapsedRealtime()))
                            // One acknowledgement only, never a coordinate guess or retry.
                            assertTrue("Observed system GOT IT action must be accepted",
                                button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            return@waitFor true
                        } finally { nodes.forEach { it.recycle() } }
                    }
                    false
                } finally { windows.forEach { it.recycle() } }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    /** Read-only synthetic-AVD evidence, bounded to512 nodes /128 perwindow. */
    private fun boundedWindowMetadata(windows: List<AccessibilityWindowInfo>): JSONArray {
        val result = JSONArray()
        var totalNodes = 0
        for (window in windows.take(16)) {
            val bounds = Rect().also(window::getBoundsInScreen)
            val nodes = JSONArray()
            val item = JSONObject().put("id", window.id).put("type", window.type)
                .put("title", window.title?.toString()?.take(200)).put("focused", window.isFocused)
                .put("active", window.isActive).put("bounds", bounds.toShortString()).put("nodes", nodes)
            result.put(item)
            fun visit(node: AccessibilityNodeInfo, parent: Int) {
                try {
                    if (totalNodes >= 512 || nodes.length() >= 128) return
                    val index = nodes.length()
                    totalNodes++
                    val rect = Rect().also(node::getBoundsInScreen)
                    nodes.put(JSONObject().put("index", index).put("parent", parent)
                        .put("window_id", node.windowId).put("package", node.packageName?.toString())
                        .put("class", node.className?.toString()).put("view_id", node.viewIdResourceName)
                        .put("text", if (node.isPassword) "[redacted]" else node.text?.toString()?.take(200))
                        .put("description", if (node.isPassword) "[redacted]" else node.contentDescription?.toString()?.take(200))
                        .put("visible", node.isVisibleToUser).put("clickable", node.isClickable)
                        .put("enabled", node.isEnabled).put("bounds", rect.toShortString()))
                    for (child in 0 until node.childCount) {
                        if (totalNodes >= 512 || nodes.length() >= 128) break
                        node.getChild(child)?.let { visit(it, index) }
                    }
                } finally { node.recycle() }
            }
            try { window.root?.let { visit(it, -1) } }
            catch (error: Exception) { item.put("metadata_error", error.javaClass.simpleName) }
        }
        return result
    }

    private fun observeOneLegacyEdgeGesture() {
        // Diagnostic visual evidence only: sticky transient bars may retain hide bits,
        // and Core1.15 raw visibility is not a valid reveal/auto-hide oracle on API29.
        phase = "portrait-edge-observation"
        val baseline = geometry()
        val expected = logical
        val callbackStart = synchronized(logicalCallbacks) { logicalCallbacks.length() }
        val resources = compose.activity.resources
        // Pinned Android10 SystemGesturesPointerEventListener.onConfigurationChanged:
        // https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/wm/SystemGesturesPointerEventListener.java
        val thresholdId = resources.getIdentifier("status_bar_height", "dimen", "android")
        assertTrue("Actual API29 status-bar threshold resource must exist", thresholdId != 0)
        val statusBarThreshold = resources.getDimensionPixelSize(thresholdId)
        assertTrue("Actual API29 status-bar threshold must be positive", statusBarThreshold > 0)
        var topCutout = Rect()
        compose.runOnUiThread { topCutout = compose.activity.windowManager.defaultDisplay.cutout?.boundingRectTop ?: Rect() }
        val cutoutAddition = if (!topCutout.isEmpty) {
            val cutoutId = resources.getIdentifier("display_cutout_touchable_region_size", "dimen", "android")
            assertTrue("Actual API29 top-cutout touch region resource must exist", cutoutId != 0)
            resources.getDimensionPixelSize(cutoutId).also { assertTrue("Top-cutout addition must be nonnegative", it >= 0) }
        } else 0
        val threshold = statusBarThreshold + cutoutAddition
        val distance = minOf(200f, baseline.getInt("root_height") * 0.2f)
        assertTrue("One edge gesture must cross the actual platform threshold", distance > threshold)
        val x = baseline.getInt("root_left") + baseline.getInt("root_width") / 2f
        val top = baseline.getInt("root_top") + 1f
        val evidence = JSONObject().put("before", baseline).put("threshold_px", threshold)
            .put("threshold_source", "AOSP android-10.0.0_r47 SystemGesturesPointerEventListener.onConfigurationChanged")
            .put("threshold_resource", "android:dimen/status_bar_height").put("status_bar_threshold_px", statusBarThreshold)
            .put("top_cutout_bounds", topCutout.toShortString())
            .put("cutout_addition_resource", "android:dimen/display_cutout_touchable_region_size").put("cutout_addition_px", cutoutAddition)
            .put("distance_px", distance).put("visibility_asserted", false)
            .put("scope", "One real finger edge gesture; inspect screenshots/windows for reveal. No flag-based transient visibility claim.")
        val events = JSONArray()
        val observations = JSONArray()
        evidence.put("events", events).put("observations", observations)
        report.put("legacy_edge_gesture", evidence)
        val down = SystemClock.uptimeMillis()
        var crossed: Long? = null
        fun event(action: Int, y: Float) {
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            val item = JSONObject().put("action", action).put("x", x).put("y", y)
                .put("down_time_ms", e.downTime).put("event_time_ms", e.eventTime)
                .put("tool_type", e.getToolType(0)).put("source", e.source)
            events.put(item)
            if (action == MotionEvent.ACTION_MOVE && y > top + threshold && crossed == null) crossed = e.eventTime - down
            try {
                val accepted = instrumentation.uiAutomation.injectInputEvent(e, true)
                item.put("accepted", accepted).put("completed_uptime_ms", SystemClock.uptimeMillis())
                assertTrue("The single observed edge gesture must be injected", accepted)
            } finally { e.recycle() }
        }
        try {
            event(MotionEvent.ACTION_DOWN, top)
            for (i in 1..8) { SystemClock.sleep(5); event(MotionEvent.ACTION_MOVE, top + distance * i / 8) }
        } finally { event(MotionEvent.ACTION_UP, top + distance) }
        evidence.put("threshold_crossing_ms", crossed)
        assertTrue("Real edge gesture must cross within500ms; no repeated swipe", crossed?.let { it < 500 } == true)
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        try {
            // These are observation timestamps, not readiness sleeps or retry attempts.
            val start = SystemClock.elapsedRealtime()
            for (offset in listOf(0L, 500L, 1500L)) {
                val remaining = start + offset - SystemClock.elapsedRealtime()
                if (remaining > 0) SystemClock.sleep(remaining)
                screenshot("portrait-edge-${offset}ms")
                val windows = automation.windows
                try { observations.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("geometry", geometry()).put("windows", boundedWindowMetadata(windows))) }
                finally { windows.forEach { it.recycle() } }
            }
        } finally { automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags } }
        assertUniqueKeyboardControl()
        val callbacks = synchronized(logicalCallbacks) { JSONArray(logicalCallbacks.toString()) }
        for (i in callbackStart until callbacks.length()) {
            val callback = callbacks.getJSONObject(i)
            assertEquals("Edge gesture cannot change logical width: $callback", expected.width, callback.getInt("width"))
            assertEquals("Edge gesture cannot change logical height: $callback", expected.height, callback.getInt("height"))
        }
        assertEquals("Edge gesture retains actual editor", baseline.getInt("editor_identity"), System.identityHashCode(editor))
        evidence.put("stable_envelope_control_physically_reachable_after_gesture", true)
    }

    private fun bars(): JSONObject {
        var value = JSONObject()
        compose.runOnUiThread {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            val flags = compose.activity.window.decorView.windowSystemUiVisibility
            value = JSONObject().put("status", insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
                .put("navigation", insets.isVisible(WindowInsetsCompat.Type.navigationBars()) &&
                    flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0)
                .put("compat_status", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                .put("compat_navigation", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
                .put("window_system_ui_visibility", flags)
                .put("basis", "API29 requested hide flags plus raw Compat insets; not a transient-bar visibility oracle")
        }
        return value
    }

    private fun exerciseChromeRoundtrip(label: String, landscape: Boolean, editorIdentity: Int) {
        val full = logical
        phase = "$label-bordered-request"
        compose.runOnUiThread { fullscreen.value = false }
        awaitFullGeometry("$label-bordered", landscape)
        assertEquals(editorIdentity, System.identityHashCode(editor))
        assertTrue("Opt-in toolbar must reduce logical viewport, not overlap it", logical.height < full.height)
        assertUniqueKeyboardControl()
        assertSafeEdges(geometry())
        screenshot("$label-04-bordered")
        phase = "$label-fullscreen-request"
        compose.runOnUiThread { fullscreen.value = true }
        awaitFullGeometry("$label-fullscreen-restored", landscape)
        assertEquals("Return to fullscreen restores exact reference viewport", full, logical)
        assertEquals(editorIdentity, System.identityHashCode(editor))
        assertUniqueKeyboardControl()
        screenshot("$label-05-fullscreen-restored")
        report.put("${label}_chrome_roundtrip_same_editor", true)
    }

    private fun exerciseKeyboard(label: String) {
        val baseline = geometry()
        assertTrue("A real focused app window is required before requesting IME: $baseline", baseline.getBoolean("window_focus"))
        assertSafeEdges(baseline)
        assertUniqueKeyboardControl()
        screenshot("$label-01-hidden")
        val expected = logical
        val startIndex = synchronized(logicalCallbacks) { logicalCallbacks.length() }
        phase = "$label-show-request"
        compose.runOnUiThread {
            val view = requireNotNull(editor)
            view.requestFocus()
            imm(view).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        waitFor("$label real IME visible and local viewport smaller") {
            val g = geometry()
            g.getBoolean("ime_visible") && g.getInt("ime_bottom") > 0 &&
                g.getInt("view_height") < baseline.getInt("view_height") && uniqueKeyboardControlCount() == 1
        }
        assertUniqueKeyboardControl()
        phase = "$label-ime-visible"
        screenshot("$label-02-ime-visible")
        val visibleStarted = SystemClock.elapsedRealtime()
        var observations = 0
        waitFor("$label visible IME observed for at least3seconds/10samples", 10_000) {
            val g = geometry()
            samples.put(g)
            assertTrue("IME vanished during visible observation: $g", g.getBoolean("ime_visible"))
            assertTrue("Local viewport must stay occluded: $g", g.getInt("view_height") < baseline.getInt("view_height"))
            observations++
            observations >= 10 && SystemClock.elapsedRealtime() - visibleStarted >= 3_000
        }
        phase = "$label-hide-request"
        compose.runOnUiThread { requireNotNull(editor).let { imm(it).hideSoftInputFromWindow(it.windowToken, 0) } }
        waitFor("$label IME hidden and local viewport restored") {
            val g = geometry()
            !g.getBoolean("ime_visible") && g.getInt("ime_bottom") == 0 &&
                g.getInt("view_width") == baseline.getInt("view_width") &&
                g.getInt("view_height") == baseline.getInt("view_height") && uniqueKeyboardControlCount() == 1
        }
        assertUniqueKeyboardControl()
        report.put("${label}_exactly_one_actionable_keyboard_control", true)
        phase = "$label-restored"
        samples.put(geometry())
        screenshot("$label-03-restored")
        val callbackSnapshot = synchronized(logicalCallbacks) { JSONArray(logicalCallbacks.toString()) }
        assertTrue("Must capture real IME-driven layout callbacks", callbackSnapshot.length() > startIndex)
        for (i in startIndex until callbackSnapshot.length()) {
            val observation = callbackSnapshot.getJSONObject(i)
            assertEquals("Transient logical width change: $observation", expected.width, observation.getInt("width"))
            assertEquals("Transient logical height change: $observation", expected.height, observation.getInt("height"))
        }
        assertEquals(expected, logical)
        assertSafeEdges(geometry())
        report.put("${label}_ime_preserved_every_logical_callback", true)
            .put("${label}_visible_samples", observations)
            .put("${label}_ime_callback_count", callbackSnapshot.length() - startIndex)
    }

    private fun uniqueKeyboardControlCount(): Int = compose.onAllNodes(
        hasContentDescription("Probe keyboard control") and hasClickAction()).fetchSemanticsNodes().size

    private fun assertUniqueKeyboardControl() {
        val selector = hasContentDescription("Probe keyboard control") and hasClickAction()
        compose.onAllNodes(selector).assertCountEquals(1)
        compose.onNode(selector).assertIsDisplayed()
        val visibleIme = geometry().getBoolean("ime_visible")
        val floating = fullscreen.value || visibleIme
        if (floating) {
            compose.onNodeWithText("Fixed 56dp session tools").assertDoesNotExist()
            compose.onNodeWithText("API29 local layout probe").assertDoesNotExist()
        } else compose.onNodeWithText("IME tools only").assertDoesNotExist()
        val bounds = compose.onNode(selector).fetchSemanticsNode().boundsInWindow
        val screen = IntArray(2); val window = IntArray(2)
        compose.runOnUiThread {
            compose.activity.window.decorView.getLocationOnScreen(screen)
            compose.activity.window.decorView.getLocationInWindow(window)
        }
        if (fullscreen.value) {
            val g = geometry()
            val x = screen[0] - window[0]
            val y = screen[1] - window[1]
            assertTrue("Floating control crosses stable left bar/cutout envelope: $g", x + bounds.left >= g.getInt("root_left") + g.getInt("control_safe_left"))
            assertTrue("Floating control crosses stable top bar/cutout envelope: $g", y + bounds.top >= g.getInt("root_top") + g.getInt("control_safe_top"))
            assertTrue("Floating control crosses stable right bar/cutout envelope: $g", x + bounds.right <= g.getInt("root_left") + g.getInt("root_width") - g.getInt("control_safe_right"))
            assertTrue("Floating control crosses stable navigation/IME envelope: $g", y + bounds.bottom <= g.getInt("root_top") + g.getInt("root_height") - maxOf(g.getInt("control_safe_bottom"), g.getInt("ime_bottom")))
            samples.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime()).put("phase", phase).put("control_bounds_in_window", bounds.toString())
                .put("stable_envelope_checked", true))
        }
        val baseBefore = baseControlClicks; val imeBefore = imeControlClicks
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                screen[0] - window[0] + bounds.center.x, screen[1] - window[1] + bounds.center.y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
        waitFor("only the currently placed keyboard control receives the physical tap") {
            if (floating) imeControlClicks == imeBefore + 1 && baseControlClicks == baseBefore
            else baseControlClicks == baseBefore + 1 && imeControlClicks == imeBefore
        }
        report.put("base_control_physical_taps", baseControlClicks).put("ime_control_physical_taps", imeControlClicks)
    }

    private fun awaitFullGeometry(label: String, landscape: Boolean) {
        phase = "$label-settling"
        var last = ""
        var matching = 0
        waitFor("$label safe non-IME full geometry") {
            val g = geometry()
            if (!g.has("root_width")) return@waitFor false
            val correctOrientation = (g.getInt("root_width") > g.getInt("root_height")) == landscape
            val shape = "${g.getInt("root_width")},${g.getInt("root_height")},${g.getInt("view_width")},${g.getInt("view_height")},$logical"
            matching = if (shape == last) matching + 1 else 0
            last = shape
            val modeBarsReady = if (fullscreen.value) !g.getBoolean("effective_status_bar_visible") && !g.getBoolean("effective_navigation_bar_visible")
                else g.getBoolean("effective_status_bar_visible") && g.getBoolean("effective_navigation_bar_visible")
            correctOrientation && modeBarsReady && g.getBoolean("window_focus") && !g.getBoolean("ime_visible") && logical.width > 0 && logical.height > 0 &&
                g.getInt("view_width") == logical.width && g.getInt("view_height") == logical.height && matching >= 3
        }
        phase = "$label-stable"
        samples.put(geometry())
    }

    private fun geometry(): JSONObject {
        var result = JSONObject()
        compose.runOnUiThread {
            val view = editor
            if (view != null) {
                val root = view.rootView
                val p = IntArray(2); val rp = IntArray(2)
                view.getLocationOnScreen(p); root.getLocationOnScreen(rp)
                val insets = ViewCompat.getRootWindowInsets(view)
                val rawInsets = view.rootWindowInsets
                val visibleFrame = Rect().also(view::getWindowVisibleDisplayFrame)
                val windowUi = view.windowSystemUiVisibility
                val compatSafe = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
                // Independent expected pre-30 policy; do not call the production resolver.
                // Hide bits establish requested settled visibility, not sticky transient bars.
                val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) == true && windowUi and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
                val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true && windowUi and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
                val permanent = insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.captionBar()) ?: Insets.NONE
                val status = if (statusVisible) insets?.getInsets(WindowInsetsCompat.Type.statusBars()) ?: Insets.NONE else Insets.NONE
                val navigation = if (navigationVisible) insets?.getInsets(WindowInsetsCompat.Type.navigationBars()) ?: Insets.NONE else Insets.NONE
                val safe = Insets.of(maxOf(permanent.left, status.left, navigation.left), maxOf(permanent.top, status.top, navigation.top),
                    maxOf(permanent.right, status.right, navigation.right), maxOf(permanent.bottom, status.bottom, navigation.bottom))
                val controlSafe = insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
                result = JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime()).put("phase", phase)
                    .put("root_left", rp[0]).put("root_top", rp[1]).put("root_width", root.width).put("root_height", root.height)
                    .put("view_left", p[0]).put("view_top", p[1]).put("view_width", view.width).put("view_height", view.height)
                    .put("available_width", available.width).put("available_height", available.height)
                    .put("view_system_ui_visibility", view.systemUiVisibility)
                    .put("window_system_ui_visibility", windowUi).put("decor_system_ui_visibility", compose.activity.window.decorView.systemUiVisibility)
                    .put("window_flags", compose.activity.window.attributes.flags)
                    .put("fullscreen_flag", windowUi and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
                    .put("hide_navigation_flag", windowUi and android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
                    .put("window_visible_display_frame", visibleFrame.toShortString())
                    .put("raw_system_left", rawInsets?.systemWindowInsetLeft ?: -1)
                    .put("raw_system_top", rawInsets?.systemWindowInsetTop ?: -1)
                    .put("raw_system_right", rawInsets?.systemWindowInsetRight ?: -1)
                    .put("raw_system_bottom", rawInsets?.systemWindowInsetBottom ?: -1)
                    .put("raw_stable_left", rawInsets?.stableInsetLeft ?: -1)
                    .put("raw_stable_top", rawInsets?.stableInsetTop ?: -1)
                    .put("raw_stable_right", rawInsets?.stableInsetRight ?: -1)
                    .put("raw_stable_bottom", rawInsets?.stableInsetBottom ?: -1)
                    .put("logical_width", logical.width).put("logical_height", logical.height)
                    .put("safe_left", safe.left).put("safe_top", safe.top)
                    .put("safe_right", safe.right).put("safe_bottom", safe.bottom)
                    .put("compat_safe_left", compatSafe.left).put("compat_safe_top", compatSafe.top)
                    .put("compat_safe_right", compatSafe.right).put("compat_safe_bottom", compatSafe.bottom)
                    .put("control_safe_left", controlSafe.left).put("control_safe_top", controlSafe.top)
                    .put("control_safe_right", controlSafe.right).put("control_safe_bottom", controlSafe.bottom)
                    .put("fullscreen", fullscreen.value)
                    .put("compat_status_bar_visible", insets?.isVisible(WindowInsetsCompat.Type.statusBars()) == true)
                    .put("compat_navigation_bar_visible", insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true)
                    .put("effective_status_bar_visible", statusVisible)
                    .put("effective_navigation_bar_visible", navigationVisible)
                    .put("visibility_basis", "Legacy requested hide flags; raw Compat retained; no claim of sticky transient-bar visibility")
                    .put("editor_identity", System.identityHashCode(view))
                    .put("ime_visible", insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                    .put("ime_bottom", insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                    .put("window_focus", view.hasWindowFocus()).put("activity_identity", System.identityHashCode(compose.activity))
            }
        }
        return result
    }

    private fun assertSafeEdges(g: JSONObject) {
        assertTrue("View overlaps left system controls/cutout: $g", g.getInt("view_left") >= g.getInt("root_left") + g.getInt("safe_left"))
        assertTrue("View overlaps status controls/cutout: $g", g.getInt("view_top") >= g.getInt("root_top") + g.getInt("safe_top"))
        assertTrue("View overlaps right system controls/cutout: $g", g.getInt("view_left") + g.getInt("view_width") <= g.getInt("root_left") + g.getInt("root_width") - g.getInt("safe_right"))
        assertTrue("View overlaps navigation controls/cutout: $g", g.getInt("view_top") + g.getInt("view_height") <= g.getInt("root_top") + g.getInt("root_height") - g.getInt("safe_bottom"))
    }
    private fun imm(view: EditText) = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable: $name" }
        try { File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $description; latest=${geometry()}")
    }
}
