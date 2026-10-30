package com.rdp.sync

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*

/** Acknowledges only the observed first-use Android system education overlay. */
internal fun acknowledgeSystemImmersiveCoachmark(activity: Activity, pump: () -> Unit, screenshot: () -> Unit,
    retainEvidence: (JSONObject) -> Unit = {}, deadlineElapsedMs: Long? = null,
    readyWhenSystemOverlayAbsent: (() -> Boolean)? = null,
    allowAcknowledgement: Boolean = true): JSONObject {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val oldFlags = automation.serviceInfo.flags
    automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    val result = JSONObject().put("acknowledged", false)
    val observations = JSONArray()
    result.put("accessibility_observations", observations)
    retainEvidence(result) // Attached to caller evidence even if readiness fails.
    var injected = false
    val deadline = deadlineElapsedMs ?: (SystemClock.elapsedRealtime() + 10_000)
    result.put("deadline_elapsed_ms", deadline).put("allow_acknowledgement", allowAcknowledgement)
        .put("custom_readiness", readyWhenSystemOverlayAbsent != null)
    var nextObservation = 0L
    try {
        while (SystemClock.elapsedRealtime() < deadline) {
            pump()
            var observed = false
            val snapshot = if (observations.length() < 20 && SystemClock.elapsedRealtime() >= nextObservation) {
                nextObservation = SystemClock.elapsedRealtime() + 500
                JSONArray().also { observations.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime()).put("windows", it)) }
            } else null
            val windows = automation.windows
            try {
                for (window in windows) {
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
                        val packageName = root.packageName?.toString()
                        if (snapshot != null) {
                            val matches = JSONArray()
                            nodes.filter { it.text?.toString() == "Viewing full screen" || it.text?.toString()?.equals("Got it", true) == true }
                                .take(10).forEach { node ->
                                    fun describe(n: AccessibilityNodeInfo): JSONObject = JSONObject()
                                        .put("package", n.packageName?.toString()).put("class", n.className?.toString())
                                        .put("view_id", n.viewIdResourceName).put("window_id", n.windowId)
                                        .put("clickable", n.isClickable).put("enabled", n.isEnabled).put("visible", n.isVisibleToUser)
                                        .put("bounds", Rect().also(n::getBoundsInScreen).toShortString())
                                    val info = describe(node).put("text", node.text?.toString())
                                    node.parent?.let { parent -> try { info.put("parent", describe(parent)) } finally { parent.recycle() } }
                                    matches.put(info)
                                }
                            snapshot.put(JSONObject().put("window_id", window.id).put("window_type", window.type)
                                .put("window_title", window.title?.toString()).put("root_package", packageName)
                                .put("root_class", root.className?.toString()).put("node_count", nodes.size).put("matching_nodes", matches))
                        }
                        if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                        if (packageName !in setOf("android", "com.android.systemui")) continue
                        if (nodes.none { it.windowId == window.id && it.isVisibleToUser && it.text?.toString() == "Viewing full screen" }) continue
                        observed = true
                        if (injected) continue
                        assertTrue("A previous phase already acknowledged the one system coachmark", allowAcknowledgement)
                        val buttons = nodes.filter { it.windowId == window.id && it.isVisibleToUser && it.isEnabled && it.isClickable &&
                            it.packageName?.toString() == packageName && it.className?.toString() == "android.widget.Button" &&
                            it.text?.toString()?.equals("Got it", ignoreCase = true) == true }
                        assertEquals("Unique observed system immersive acknowledgement", 1, buttons.size)
                        val button = buttons.single()
                        assertTrue(button.refresh()); assertTrue(button.isVisibleToUser && button.isEnabled)
                        assertEquals(packageName, button.packageName?.toString()); assertEquals(window.id, button.windowId)
                        assertTrue(button.text?.toString()?.equals("Got it", ignoreCase = true) == true)
                        val bounds = Rect().also(button::getBoundsInScreen); assertFalse(bounds.isEmpty)
                        screenshot()
                        assertTrue("Coachmark acknowledgement must begin inside the original readiness deadline",
                            SystemClock.elapsedRealtime() < deadline)
                        result.put("package", packageName).put("window_id", window.id).put("window_type", window.type)
                            .put("bounds", bounds.toShortString()).put("uptime_before_down", SystemClock.uptimeMillis())
                        val down = SystemClock.uptimeMillis()
                        fun inject(action: Int) {
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                            try { assertTrue("Observed system coachmark physical injection accepted", automation.injectInputEvent(event, true)) }
                            finally { event.recycle() }
                        }
                        injected = true // Never repeat this input, even if a later check fails.
                        try { inject(MotionEvent.ACTION_DOWN); SystemClock.sleep(40) } finally { inject(MotionEvent.ACTION_UP) }
                        result.put("uptime_after_up", SystemClock.uptimeMillis()).put("acknowledged", true)
                    } finally { nodes.forEach { it.recycle() } }
                }
            } finally { windows.forEach { it.recycle() } }
            var focused = false
            instrumentation.runOnMainSync { focused = activity.window.decorView.hasWindowFocus() }
            // A legitimate application Dialog owns focus instead of the Activity decor.
            // A caller may supply its narrower dialog readiness, but only after the
            // matching SYSTEM overlay is absent, under this same absolute deadline.
            val ready = !observed && (readyWhenSystemOverlayAbsent?.invoke() ?: focused)
            if (ready && SystemClock.elapsedRealtime() <= deadline) return result
                .put("system_overlay_absent", true).put("activity_decor_focused", focused)
                .put("readiness_satisfied", true).put("completed_elapsed_ms", SystemClock.elapsedRealtime())
            SystemClock.sleep(100)
        }
        throw AssertionError("Observed immersive coachmark/readiness did not complete within the original deadline: $result")
    } finally { automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags } }
}
