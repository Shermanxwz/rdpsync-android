package com.rdp.sync.ui.rdp

import org.junit.Assert.*
import org.junit.Test

class FramePacerTest {
    @Test fun fractionalVsyncKeepsSixtyFpsOnNinetyAnd144HzPanels() {
        for (hz in listOf(60, 90, 120, 144)) {
            val pacer = FramePacer()
            val polls = (0 until hz * 5).count { pacer.shouldPoll(it * 1_000_000_000L / hz, 60) }
            assertTrue("$hz Hz yielded $polls frames", polls in 299..301)
        }
    }
    @Test fun pauseDoesNotCauseCatchUpBurst() {
        val pacer = FramePacer()
        assertTrue(pacer.shouldPoll(0, 60))
        assertTrue(pacer.shouldPoll(5_000_000_000, 60))
        assertFalse(pacer.shouldPoll(5_001_000_000, 60))
    }
    @Test fun changingToEconomyTakesEffectImmediately() {
        val pacer = FramePacer()
        assertTrue(pacer.shouldPoll(0, 60))
        assertTrue(pacer.shouldPoll(10_000_000, 30))
        assertFalse(pacer.shouldPoll(30_000_000, 30))
        assertTrue(pacer.shouldPoll(44_000_000, 30))
    }
}
