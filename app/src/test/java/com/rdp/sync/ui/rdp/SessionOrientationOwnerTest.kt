package com.rdp.sync.ui.rdp

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test

class SessionOrientationOwnerTest {
    @Test fun initialTargetUsesActualOrientationAndPreservesOriginalPolicy() {
        val portrait = SessionOrientationOwner(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        assertFalse(portrait.hasOverride)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, portrait.rotate(Configuration.ORIENTATION_PORTRAIT))
        assertTrue(portrait.hasOverride)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, portrait.originalOrientation)
        val landscape = SessionOrientationOwner(ActivityInfo.SCREEN_ORIENTATION_SENSOR)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, landscape.rotate(Configuration.ORIENTATION_LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, landscape.originalOrientation)
    }

    @Test fun repeatedTapsAlternateEvenBeforeConfigurationDelivery() {
        val owner = SessionOrientationOwner(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        repeat(10) { i ->
            assertEquals(if (i % 2 == 0) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                owner.rotate(Configuration.ORIENTATION_PORTRAIT))
        }
    }
}
