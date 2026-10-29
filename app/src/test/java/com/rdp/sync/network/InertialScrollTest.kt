package com.rdp.sync.network

import org.junit.Assert.*
import org.junit.Test

class InertialScrollTest {
    @Test fun integratedDistanceIsStableAcrossFrameDurations() {
        fun distance(dt: Long): Int {
            val fling = InertialScroll(0f, 1200f)
            var sum = 0
            repeat(2000) { val step = fling.step(dt); sum += step.y; if (!step.active) return sum }
            fail("Fling did not stop"); return sum
        }
        assertEquals(distance(8).toDouble(), distance(16).toDouble(), 2.0)
        assertEquals(distance(16).toDouble(), distance(33).toDouble(), 2.0)
    }
    @Test fun hugeVelocityAndDelayedTickRemainBounded() {
        val fling = InertialScroll(1_000_000f, -1_000_000f)
        val step = fling.step(60_000)
        assertTrue(step.x in 0..120)
        assertTrue(step.y in -120..0)
    }
}
