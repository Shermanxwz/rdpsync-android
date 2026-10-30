package com.rdp.sync.ui.rdp

import com.rdp.sync.display.DisplayPolicy
import com.rdp.sync.display.DisplayQuality
import com.rdp.sync.display.ResolutionMode
import org.junit.Assert.*
import org.junit.Test

class SessionUiDefaultsTest {
    @Test fun newSessionStartsInDirectTouchWithBalancedFitWindow() {
        assertTrue(SessionUiDefaults.FULLSCREEN)
        assertFalse(SessionUiDefaults.POINTER_MODE)
        val settings = SessionUiDefaults.displaySettings(null, null, false)
        assertEquals(DisplayQuality.BALANCED, settings.quality)
        assertEquals(ResolutionMode.FIT_WINDOW, settings.resolution)
        assertFalse(settings.adaptive)
    }
    @Test fun explicitSavedProfileChoiceUsesItsRealDimensionsWithoutChangingProfile() {
        val settings = SessionUiDefaults.displaySettings("SHARP", "PROFILE", false)
        val size = DisplayPolicy.resolve(1080, 2400, settings, profileWidth = 1600, profileHeight = 1000)
        assertEquals(1600, size.width)
        assertEquals(1000, size.height)
    }
    @Test fun obsoleteSavedEnumValuesRecoverWithoutCrashingOrEnablingAdaptive() {
        val settings = SessionUiDefaults.displaySettings("obsolete", "old-fixed", false)
        assertEquals(DisplayQuality.BALANCED, settings.quality)
        assertEquals(ResolutionMode.FIT_WINDOW, settings.resolution)
        assertFalse(settings.adaptive)
    }
}
