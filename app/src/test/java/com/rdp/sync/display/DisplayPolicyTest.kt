package com.rdp.sync.display

import org.junit.Assert.*
import org.junit.Test

class DisplayPolicyTest {
    @Test fun landscapeAndPortraitPreserveAspectRatherThanIndependentMinimums() {
        val settings = SessionDisplaySettings(quality = DisplayQuality.SHARP)
        assertEquals(DesktopSize(1920, 1080), DisplayPolicy.resolve(1920, 1080, settings))
        assertEquals(DesktopSize(1080, 1920), DisplayPolicy.resolve(1080, 1920, settings))
    }
    @Test fun explicitProfileIsHonoredWithoutDatabaseMigration() {
        assertEquals(DesktopSize(1600, 900), DisplayPolicy.resolve(100, 100,
            SessionDisplaySettings(resolution = ResolutionMode.PROFILE), profileWidth = 1600, profileHeight = 900))
    }
    @Test fun maliciousDimensionsCannotOverflowMemoryBudget() {
        for ((w, h) in listOf(Int.MAX_VALUE to Int.MAX_VALUE, -1 to 0, 10000 to 5000, 4000 to 3000, 3483 to 4258)) {
            val size = DisplayPolicy.boundedSize(w, h)
            assertTrue(size.width in 200..4096 && size.height in 200..4096)
            assertEquals(0, size.width % 2)
            assertEquals(0, size.height % 2)
            assertTrue(size.width.toLong() * size.height <= DisplayPolicy.MAX_PIXELS)
        }
    }
    @Test fun manyRandomDimensionsStayWithinNativeContract() {
        val random = java.util.Random(1)
        repeat(10000) {
            val size = DisplayPolicy.boundedSize(random.nextInt(20000), random.nextInt(20000))
            assertTrue(size.width.toLong() * size.height <= DisplayPolicy.MAX_PIXELS)
            assertTrue(size.width in 200..4096 && size.height in 200..4096)
            assertEquals(0, size.width % 2)
        }
    }
    @Test fun manualModeDoesNotSilentlyReduceConfiguredResolution() {
        assertEquals(DesktopSize(1920, 1080), DisplayPolicy.resolve(900, 600,
            SessionDisplaySettings(DisplayQuality.ECONOMY, ResolutionMode.FULL_HD)))
    }
    @Test fun adaptiveNeverExceedsSelectedQuality() {
        val s = SessionDisplaySettings(DisplayQuality.BALANCED, ResolutionMode.HD, adaptive = true)
        assertEquals(DesktopSize(1280, 720), DisplayPolicy.resolve(900, 600, s, DisplayQuality.SHARP))
        assertEquals(DesktopSize(960, 540), DisplayPolicy.resolve(900, 600, s, DisplayQuality.ECONOMY))
    }
}
