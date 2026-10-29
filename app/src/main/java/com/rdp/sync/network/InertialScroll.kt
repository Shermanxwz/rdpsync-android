package com.rdp.sync.network

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Bounded wheel velocity for legacy servers only; RDPEI owns its own inertia. */
internal class InertialScroll(velocityX: Float, velocityY: Float) {
    data class Step(val x: Int, val y: Int, val active: Boolean)
    private var vx = velocityX.coerceIn(-2400f, 2400f)
    private var vy = velocityY.coerceIn(-2400f, 2400f)
    private var remainderX = 0f
    private var remainderY = 0f
    fun step(elapsedMillis: Long): Step {
        val dt = elapsedMillis.coerceIn(1, 50) / 1000f
        val decay = exp(-6.4f * dt)
        remainderX += vx * (1f - decay) / 6.4f
        remainderY += vy * (1f - decay) / 6.4f
        vx *= decay; vy *= decay
        if (abs(vx) < 12f) vx = 0f
        if (abs(vy) < 12f) vy = 0f
        val x = remainderX.roundToInt(); val y = remainderY.roundToInt()
        remainderX -= x; remainderY -= y
        return Step(x, y, vx != 0f || vy != 0f)
    }
}
