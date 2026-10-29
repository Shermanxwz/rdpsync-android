package com.rdp.sync.ui.rdp

/** Fractional-vsync cadence (e.g. 60fps on a 90Hz panel), without catch-up bursts. */
class FramePacer {
    private var nextDue = -1L
    private var fps = 0
    fun shouldPoll(frameTimeNanos: Long, targetFps: Int): Boolean {
        val requested = targetFps.coerceIn(15, 120)
        if (fps != requested) { fps = requested; nextDue = -1 }
        val period = 1_000_000_000L / fps
        if (nextDue >= 0 && frameTimeNanos + 500_000 < nextDue) return false
        nextDue = if (nextDue < 0 || frameTimeNanos - nextDue >= period) frameTimeNanos + period else nextDue + period
        return true
    }
}
