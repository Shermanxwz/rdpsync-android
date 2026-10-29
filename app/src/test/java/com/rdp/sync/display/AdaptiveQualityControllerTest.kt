package com.rdp.sync.display

import org.junit.Assert.*
import org.junit.Test

class AdaptiveQualityControllerTest {
    private fun sample(t: Long, rtt: Long? = 40, bandwidth: Long? = 8000, age: Long? = 0,
                       frames: Long = 20, copy: Double = 2.0, draw: Double = 2.0) =
        QualitySample(t, rtt, bandwidth, age, frames, copy, draw)

    @Test fun absentZeroStaleAndIdleDataNeverForceLowestQuality() {
        val c = AdaptiveQualityController(DisplayQuality.SHARP)
        for (i in 1..100) {
            c.observe(sample(i * 1000L, null, null, null, 0), DisplayQuality.SHARP)
            c.observe(sample(i * 1000L + 500, 0, 0, 20000, 0), DisplayQuality.SHARP)
        }
        assertEquals(DisplayQuality.SHARP, c.quality)
    }
    @Test fun sustainedCongestionDownshiftsOneTierWithCooldown() {
        val c = AdaptiveQualityController(DisplayQuality.SHARP)
        repeat(2) { c.observe(sample(it * 1000L, 250, 900), DisplayQuality.SHARP) }
        assertEquals(DisplayQuality.SHARP, c.quality)
        c.observe(sample(2000, 250, 900), DisplayQuality.SHARP)
        assertEquals(DisplayQuality.BALANCED, c.quality)
        for (t in 3000L..13000L step 1000) c.observe(sample(t, 250, 900), DisplayQuality.SHARP)
        assertEquals(DisplayQuality.BALANCED, c.quality)
        c.observe(sample(14000, 250, 900), DisplayQuality.SHARP)
        assertEquals(DisplayQuality.ECONOMY, c.quality)
    }
    @Test fun recoveryNeedsFifteenHealthyWindowsAndThirtySecondCooldown() {
        val c = AdaptiveQualityController(DisplayQuality.SHARP)
        repeat(3) { c.observe(sample(it * 1000L, 250, 900), DisplayQuality.SHARP) }
        for (t in 3000L..31000L step 1000) c.observe(sample(t), DisplayQuality.SHARP)
        assertEquals(DisplayQuality.BALANCED, c.quality)
        c.observe(sample(32000), DisplayQuality.SHARP)
        assertEquals(DisplayQuality.SHARP, c.quality)
    }
    @Test fun burstAndRepeatedSameTickDoNotCountAsSustainedPressure() {
        val c = AdaptiveQualityController(DisplayQuality.SHARP)
        repeat(100) { c.observe(sample(0, 250, 900), DisplayQuality.SHARP) }
        assertEquals(DisplayQuality.SHARP, c.quality)
        c.reset(DisplayQuality.BALANCED)
        c.observe(sample(1000, 250, 900), DisplayQuality.SHARP)
        for (t in 2000L..12000L step 1000) c.observe(sample(t), DisplayQuality.SHARP)
        assertEquals(DisplayQuality.BALANCED, c.quality)
    }
    @Test fun repeatedAgingServerReportIsNotThreeCongestionReports() {
        val c = AdaptiveQualityController(DisplayQuality.SHARP)
        repeat(9) { c.observe(sample(it * 1000L, 250, 900, it * 1000L), DisplayQuality.SHARP) }
        assertEquals(DisplayQuality.SHARP, c.quality)
    }
    @Test fun measuredRenderPressureCanDowngradeButIdleCannot() {
        val c = AdaptiveQualityController(DisplayQuality.BALANCED)
        repeat(3) {
            val decision = c.observe(sample(it * 1000L, null, null, null, 20, 30.0), DisplayQuality.BALANCED)
            assertTrue(decision.reason.contains("本机渲染"))
            assertFalse(decision.reason.contains("网络延迟"))
        }
        assertEquals(DisplayQuality.ECONOMY, c.quality)
        repeat(100) { c.observe(sample(5000L + it * 1000, null, null, null, 0, 30.0), DisplayQuality.BALANCED) }
        assertEquals(DisplayQuality.ECONOMY, c.quality)
    }
}
