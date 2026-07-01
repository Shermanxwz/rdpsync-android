package com.rdp.sync.network

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V1.0.9 Input event dispatcher.
 * Decouples Compose gesture callbacks from blocking native JNI calls.
 *
 * DOWN / UP / CANCEL / CLICK are enqueued as ordered individual jobs so they
 * are never merged. MOVE events are throttle-merged to reduce wire chatter.
 */
class RdpInputDispatcher(
    private val touchSender: (Int, Int, Int, Int) -> Boolean,
    private val wheelSender: (Int, Int, Int) -> Int,
    private val hwheelSender: (Int, Int, Int) -> Int,
    private val pointerSender: (Int, Int, Int) -> Int,
) {
    companion object {
        private const val TAG = "RdpInputDispatcher"
        private const val MOVE_THROTTLE_MS = 12L
        private const val WHEEL_FLUSH_MS = 20L
        private const val TOUCH_MOVE_THROTTLE_MS = 12L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val shutdown = AtomicBoolean(false)

    @Volatile private var latestMoveX: Int = 0
    @Volatile private var latestMoveY: Int = 0
    private var movePending = AtomicBoolean(false)

    @Volatile private var latestTouchX: Int = 0
    @Volatile private var latestTouchY: Int = 0
    private var touchMovePending = AtomicBoolean(false)

    @Volatile private var wheelDelta: Int = 0
    @Volatile private var wheelX: Int = 0
    @Volatile private var wheelY: Int = 0
    private var wheelPending = AtomicBoolean(false)

    @Volatile private var hwheelDelta: Int = 0
    @Volatile private var hwheelX: Int = 0
    @Volatile private var hwheelY: Int = 0
    private var hwheelPending = AtomicBoolean(false)

    fun enqueuePointerMove(x: Int, y: Int) {
        if (shutdown.get()) return
        latestMoveX = x; latestMoveY = y
        if (movePending.compareAndSet(false, true)) {
            scope.launch {
                delay(MOVE_THROTTLE_MS)
                movePending.set(false)
                val cx = latestMoveX; val cy = latestMoveY
                try { pointerSender(cx, cy, 0) } catch (_: Exception) {}
            }
        }
    }

    /** enqueue a click: native pointerSender(button != 0) already sends
     * down + release.  Do NOT send an extra button=0 (move) after the click. */
    fun enqueuePointerClick(x: Int, y: Int, button: Int) {
        if (shutdown.get()) return
        scope.launch {
            try {
                pointerSender(x, y, button)
            } catch (_: Exception) {}
        }
    }

    fun enqueueWheel(x: Int, y: Int, delta: Int) {
        if (shutdown.get()) return
        wheelX = x; wheelY = y; wheelDelta += delta
        if (wheelPending.compareAndSet(false, true)) {
            scope.launch {
                delay(WHEEL_FLUSH_MS)
                wheelPending.set(false)
                val d = wheelDelta; val wx = wheelX; val wy = wheelY
                wheelDelta = 0
                if (d != 0) {
                    try { wheelSender(wx, wy, d) } catch (_: Exception) {}
                }
            }
        }
    }

    fun enqueueHWheel(x: Int, y: Int, delta: Int) {
        if (shutdown.get()) return
        hwheelX = x; hwheelY = y; hwheelDelta += delta
        if (hwheelPending.compareAndSet(false, true)) {
            scope.launch {
                delay(WHEEL_FLUSH_MS)
                hwheelPending.set(false)
                val d = hwheelDelta; val hx = hwheelX; val hy = hwheelY
                hwheelDelta = 0
                if (d != 0) {
                    try { hwheelSender(hx, hy, d) } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Enqueue a touch event.
     *
     * - DOWN (0), UP (2), CANCEL (3) → dispatched immediately (never merged).
     * - MOVE (1) → throttle-merged like pointer moves.
     */
    fun enqueueTouch(pointerId: Int, eventType: Int, x: Int, y: Int) {
        if (shutdown.get()) return
        when (eventType) {
            0, 2, 3 -> {
                scope.launch {
                    try { touchSender(pointerId, eventType, x, y) } catch (_: Exception) {}
                }
            }
            1 -> {
                latestTouchX = x; latestTouchY = y
                if (touchMovePending.compareAndSet(false, true)) {
                    scope.launch {
                        delay(TOUCH_MOVE_THROTTLE_MS)
                        // Only one wins: this delay job or flushPendingTouchMoveNow()
                        if (touchMovePending.compareAndSet(true, false)) {
                            val cx = latestTouchX; val cy = latestTouchY
                            try { touchSender(0, 1, cx, cy) } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    /**
     * Immediately send the latest pending touch MOVE if any.
     * Used before UP/CANCEL to guarantee move-before-release ordering.
     *
     * Uses compareAndSet(true, false) so only one caller (this or the
     * delay job in enqueueTouch) actually sends; the other becomes a no-op.
     */
    fun flushPendingTouchMoveNow() {
        if (touchMovePending.compareAndSet(true, false)) {
            val cx = latestTouchX; val cy = latestTouchY
            try { touchSender(0, 1, cx, cy) } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            scope.cancel()
            Log.d(TAG, "shutdown")
        }
    }
}
