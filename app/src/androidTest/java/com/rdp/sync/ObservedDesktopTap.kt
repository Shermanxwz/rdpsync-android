package com.rdp.sync

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.rdp.ViewportTransform
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Only real desktop taps; IME keys, app controls and popup injectors are unchanged. */
internal fun injectObservedDesktopTap(
    instrumentation: Instrumentation,
    view: RdpDesktopView,
    screenX: Float,
    screenY: Float,
    remoteX: Float,
    remoteY: Float,
    attempt: JSONObject,
    revalidate: () -> Unit
): Long {
    require(screenX.isFinite() && screenY.isFinite() && screenX >= 0 && screenY >= 0)
    // Pinned AOSP15 InputShellCommand.sendSwipe(duration=0) creates one DOWN and
    // one UP with fresh uptime, and no MOVE. Unlike UiAutomation's public two-arg
    // injector it does not wait for window transactions after constructing DOWN.
    // Ordinary `input tap` reuses DOWN's timestamp for UP and is deliberately NOT used.
    // Android15 toybox device config enables timeout; its owned child/group bound
    // does NOT guarantee cancellation of an already accepted system-server call.
    val command = "/system/bin/toybox timeout -v -s KILL 5s /system/bin/input touchscreen -d 0 swipe $screenX $screenY $screenX $screenY 0"
    val lock = Any()
    val receipts = JSONArray()
    val commandTrace = JSONObject().put("command", command).put("client_timeout_ms", 5_000)
        .put("exit_code_available", false).put("source", "AOSP android-15.0.0_r1 zero-duration zero-distance swipe")
    attempt.put("received_events", receipts).put("input_command", commandTrace)
        .put("response_anchor", "actual desktop View ACTION_UP receipt; command completion consumes response budget")
    val generation = RdpConnector.nativeGetTelemetry()[11]
    lateinit var baseline: ViewportTransform.Snapshot
    val origin = IntArray(2)
    var receiptCount = 0
    var captureFailure: Throwable? = null
    fun clock() = JSONObject().put("wall_ms", System.currentTimeMillis())
        .put("elapsed_ms", SystemClock.elapsedRealtime()).put("uptime_ms", SystemClock.uptimeMillis())
    instrumentation.runOnMainSync {
        revalidate()
        assertTrue(view.isAttachedToWindow && view.isShown && view.hasWindowFocus())
        assertEquals(0, view.display?.displayId)
        baseline = view.viewportSnapshot()
        view.getLocationOnScreen(origin)
        val point = baseline.toScreen(remoteX, remoteY)
        assertEquals(screenX, origin[0] + point.x, 0f)
        assertEquals(screenY, origin[1] + point.y, 0f)
        commandTrace.put("view_identity", System.identityHashCode(view)).put("generation", generation)
            .put("installed", clock())
        // Exact production/Compose1.7.2 source audit: this owned AndroidView has
        // no existing OnTouchListener. Never consume/delegate around onTouchEvent.
        view.setOnTouchListener { actualView, event ->
            synchronized(lock) {
                receiptCount++
                try {
                    if (receiptCount <= 8) {
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        val record = clock().put("action", event.actionMasked).put("event_time_ms", event.eventTime)
                            .put("down_time_ms", event.downTime).put("pointer_count", event.pointerCount).put("pointer_id", event.getPointerId(0))
                            .put("tool_type", event.getToolType(0)).put("source", event.source).put("view_display_id", actualView.display?.displayId)
                            .put("raw_x", event.rawX).put("raw_y", event.rawY).put("local_x", event.x).put("local_y", event.y)
                            .put("same_view", actualView === view).put("attached", view.isAttachedToWindow)
                            .put("shown", view.isShown).put("window_focus", view.hasWindowFocus())
                            .put("same_origin", location.contentEquals(origin)).put("same_transform", view.viewportSnapshot() == baseline)
                        receipts.put(record)
                    }
                } catch (error: Throwable) { if (captureFailure == null) captureFailure = error else captureFailure!!.addSuppressed(error) }
            }
            false
        }
    }
    val descriptors = AtomicReference<Array<ParcelFileDescriptor>?>()
    val closing = AtomicBoolean(false)
    val listenerRemoved = AtomicBoolean(false)
    val executor = Executors.newFixedThreadPool(3) { task -> Thread(task, "fixture-desktop-input").apply { isDaemon = true } }
    var primaryFailure: Throwable? = null
    fun readBounded(fd: ParcelFileDescriptor): String = ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(512)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            require(output.size() + count <= 4096) { "Unexpected unbounded shell input output" }
            output.write(bytes, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
    try {
        synchronized(lock) { commandTrace.put("started", clock()) }
        val completed = executor.submit(Callable {
            check(!closing.get()) { "Input command scope already closed" }
            val pipes = instrumentation.uiAutomation.executeShellCommandRwe(command)
            var workerFailure: Throwable? = null
            try {
                descriptors.set(pipes)
                check(!closing.get()) { "Input command descriptors arrived after scope closed" }
                require(pipes.size == 3)
                pipes[1].close() // This fixed command accepts no stdin.
                val stdout = executor.submit(Callable { readBounded(pipes[0]) })
                val stderr = executor.submit(Callable { readBounded(pipes[2]) })
                val output = stdout.get()
                val error = stderr.get()
                synchronized(lock) {
                    commandTrace.put("stdout", output).put("stderr", error).put("completed", clock()).put("eof", true)
                }
                assertTrue("Fixed input command must produce no stdout/stderr (including timeout diagnostics)", output.isBlank() && error.isBlank())
            } catch (error: Throwable) { workerFailure = error; throw error }
            finally {
                // The worker owns every created pipe even if creation returns
                // after outer timeout/shutdown; no late FD handoff can be lost.
                var closeFailure: Throwable? = null
                pipes.forEach { fd ->
                    try { fd.close() } catch (error: Throwable) {
                        if (closeFailure == null) closeFailure = error else closeFailure!!.addSuppressed(error)
                    }
                }
                descriptors.compareAndSet(pipes, null)
                closeFailure?.let { if (workerFailure != null) workerFailure!!.addSuppressed(it) else throw it }
            }
        })
        // Reader bound includes the 5s owned-command lifetime; never rerun input.
        completed.get(7, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            revalidate()
            val location = IntArray(2); view.getLocationOnScreen(location)
            assertTrue(view.isAttachedToWindow && view.isShown && view.hasWindowFocus())
            assertTrue(location.contentEquals(origin)); assertEquals(baseline, view.viewportSnapshot())
            // Freeze the receipt stream on the UI thread before validating it.
            // An extra event queued before this point remains counted, never lost
            // between a successful count check and finally-based removal.
            view.setOnTouchListener(null)
            listenerRemoved.set(true)
            synchronized(lock) { commandTrace.put("listener_removed", true).put("receipts_frozen", clock()) }
        }
        assertEquals(generation, RdpConnector.nativeGetTelemetry()[11])
        synchronized(lock) {
            attempt.put("receipt_count", receiptCount)
            captureFailure?.let { throw AssertionError("Desktop receipt capture failed", it) }
            assertEquals("Exactly one actual DOWN/UP, no MOVE/CANCEL/extra input", 2, receiptCount)
            val down = receipts.getJSONObject(0); val up = receipts.getJSONObject(1)
            assertEquals(MotionEvent.ACTION_DOWN, down.getInt("action")); assertEquals(MotionEvent.ACTION_UP, up.getInt("action"))
            assertEquals(down.getLong("event_time_ms"), down.getLong("down_time_ms"))
            assertEquals(down.getLong("down_time_ms"), up.getLong("down_time_ms"))
            for (record in listOf(down, up)) {
                assertEquals(1, record.getInt("pointer_count")); assertEquals(0, record.getInt("pointer_id")); assertEquals(MotionEvent.TOOL_TYPE_FINGER, record.getInt("tool_type"))
                assertEquals(InputDevice.SOURCE_TOUCHSCREEN, record.getInt("source")); assertEquals(0, record.getInt("view_display_id"))
                for (flag in listOf("same_view", "attached", "shown", "window_focus", "same_origin", "same_transform")) assertTrue(flag, record.getBoolean(flag))
                assertTrue(abs(record.getDouble("raw_x") - screenX) <= 0.01); assertTrue(abs(record.getDouble("raw_y") - screenY) <= 0.01)
                assertTrue(abs(record.getDouble("local_x") - (screenX - origin[0])) <= 0.01)
                assertTrue(abs(record.getDouble("local_y") - (screenY - origin[1])) <= 0.01)
                assertTrue(record.getLong("event_time_ms") >= commandTrace.getJSONObject("started").getLong("uptime_ms"))
                assertTrue(record.getLong("uptime_ms") >= record.getLong("event_time_ms"))
            }
            val duration = up.getLong("event_time_ms") - down.getLong("event_time_ms")
            attempt.put("gesture_duration_ms", duration).put("up_received_elapsed_ms", up.getLong("elapsed_ms"))
            assertTrue("Actual desktop tap must remain below the 500ms long-press threshold", duration in 0L until 500L)
            assertTrue(up.getLong("elapsed_ms") >= down.getLong("elapsed_ms"))
            return up.getLong("elapsed_ms")
        }
    } catch (error: Throwable) {
        primaryFailure = error
        synchronized(lock) { attempt.put("input_error", error.toString()).put("input_error_cause", error.cause?.toString()).put("receipt_count", receiptCount) }
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) { if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error) }
        }
        // Closing the client descriptors is not described as cancelling in-flight
        // system-server injection. Any timeout/missing/late receipt fails this test;
        // the caller's existing session/activity teardown still runs on failure.
        closing.set(true)
        descriptors.get()?.forEach { fd -> cleanup { fd.close() } }
        executor.shutdownNow()
        cleanup { assertTrue("Owned input readers terminated", executor.awaitTermination(1, TimeUnit.SECONDS)) }
        if (!listenerRemoved.get()) cleanup {
            instrumentation.runOnMainSync { view.setOnTouchListener(null); listenerRemoved.set(true) }
            synchronized(lock) { commandTrace.put("listener_removed", true) }
        }
        synchronized(lock) { commandTrace.put("cleanup_completed", clock()) }
        cleanupFailure?.let { if (primaryFailure != null) primaryFailure!!.addSuppressed(it) else throw it }
    }
}
