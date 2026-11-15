package com.rdp.sync.ui.rdp

/**
 * High-refresh LTPO-aware fractional-vsync pacer (supports 60Hz, 90Hz, 120Hz, 144Hz panels)
 * with instantaneous input-boost promotion for sub-frame touch/cursor responsiveness.
 */
class FramePacer {
    private var nextDue = -1L
    private var fps = 0
    private var boostUntilNanos = 0L

    fun notifyUserInteraction(nowNanos: Long, panelMaxFps: Int) {
        if (panelMaxFps > 60) {
            boostUntilNanos = nowNanos + 650_000_000L // 650ms high-refresh boost window
        }
    }

    fun shouldPoll(frameTimeNanos: Long, targetFps: Int, panelMaxFps: Int = 60): Boolean {
        val boostedTarget = if (frameTimeNanos < boostUntilNanos) maxOf(targetFps, panelMaxFps) else targetFps
        val requested = boostedTarget.coerceIn(15, 144)
        if (fps != requested) {
            fps = requested
            nextDue = -1L
        }
        val period = 1_000_000_000L / fps
        if (nextDue >= 0 && frameTimeNanos + 500_000L < nextDue) return false
        nextDue = if (nextDue < 0 || frameTimeNanos - nextDue >= period) {
            frameTimeNanos + period
        } else {
            nextDue + period
        }
        return true
    }
}
