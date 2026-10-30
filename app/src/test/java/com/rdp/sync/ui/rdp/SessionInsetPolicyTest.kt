package com.rdp.sync.ui.rdp

import org.junit.Assert.*
import org.junit.Test

class SessionInsetPolicyTest {
    private val none = SessionSafeInsets(0, 0, 0, 0)
    private val status = SessionSafeInsets(0, 42, 0, 0)
    private val nav = SessionSafeInsets(0, 0, 0, 84)
    private val stable = SessionSafeInsets(0, 42, 0, 84)
    private fun visible(api: Int, flags: Int, permanent: SessionSafeInsets = none) =
        SessionInsetPolicy.visibleInsets(api, flags, status, nav, permanent)

    @Test fun legacyHiddenBarsDoNotCreateBlankBandsFromRawInsets() {
        assertEquals(none, visible(29, 6))
        assertEquals(nav, visible(29, 4))
        assertEquals(status, visible(29, 2))
        assertEquals(stable, visible(29, 0))
    }
    @Test fun onlyHideBitsMatterAndCutoutsNeverDisappear() {
        val cutout = SessionSafeInsets(70, 24, 0, 0)
        assertEquals(cutout, visible(29, 0x1706, cutout))
        assertEquals(SessionSafeInsets(70, 42, 0, 84), visible(29, 0x1700, cutout))
    }
    @Test fun modernPlatformInsetsRemainAuthoritativeEvenWithLegacyFlags() {
        assertEquals(stable, visible(30, 6))
        assertEquals(stable, visible(35, 6))
        assertEquals(none, SessionInsetPolicy.visibleInsets(35, 0, none, none, none))
    }
    @Test fun initialLegacyVisibilityMasksApproximationForExitRestoration() {
        assertEquals(false to false, SessionInsetPolicy.barVisibility(29, 6, true, true))
        assertEquals(true to true, SessionInsetPolicy.barVisibility(29, 0, true, true))
        assertEquals(true to true, SessionInsetPolicy.barVisibility(35, 6, true, true))
        assertEquals(false to false, SessionInsetPolicy.barVisibility(29, 0, false, false))
    }
    @Test fun flagChangesNeverChangeFullscreenRemoteGeometry() {
        for (flags in listOf(0, 4, 6, 0x1706, 0)) {
            val g = SessionViewportGeometry.calculate(720, 1280, 720, 1280, 0, 0,
                none, 0, 112, 98, fullscreen = true, localSafe = visible(29, flags))
            assertEquals(720, g.logicalDesktopWidth)
            assertEquals(1280, g.logicalDesktopHeight)
        }
    }
    @Test fun legacyFloatingControlReservesBarsWithoutShrinkingDesktop() {
        val g = SessionViewportGeometry.calculate(720, 1280, 720, 1280, 0, 0,
            none, 0, 0, 0, fullscreen = true, localSafe = visible(29, 6))
        val controls = SessionInsetPolicy.controlInsets(29, true, stable, none)
        assertEquals(1280, g.localDesktopHeight)
        assertEquals(636 to 1112, SessionInsetPolicy.controlPosition(g, 84, 84, 720, 1280, 0, 0, controls, 0))
    }
    @Test fun keyboardControlEnvelopeDoesNotSubtractStableBottomTwice() {
        val g = SessionViewportGeometry.calculate(720, 1280, 720, 780, 0, 0,
            none, 500, 0, 0, fullscreen = true, localSafe = none)
        assertEquals(780, g.localDesktopHeight)
        assertEquals(1280, g.logicalDesktopHeight)
        assertEquals(636 to 696, SessionInsetPolicy.controlPosition(g, 84, 84, 720, 1280, 0, 0, stable, 500))
    }
    @Test fun sideNavigationAndCutoutKeepLegacyHandleAwayFromSystemGestures() {
        val cutout = SessionSafeInsets(70, 0, 0, 0)
        val sideSafe = SessionSafeInsets(70, 42, 84, 0)
        val g = SessionViewportGeometry.calculate(1280, 720, 1280, 720, 0, 0,
            cutout, 0, 0, 0, fullscreen = true, localSafe = cutout)
        assertEquals(1210, g.logicalDesktopWidth)
        assertEquals(1112 to 636, SessionInsetPolicy.controlPosition(g, 84, 84, 1280, 720, 0, 0, sideSafe, 0))
    }
    @Test fun modernAndBorderedControlsDoNotAcquireExtraStableInsets() {
        assertEquals(none, SessionInsetPolicy.controlInsets(35, true, stable, none))
        assertEquals(none, SessionInsetPolicy.controlInsets(29, false, stable, none))
    }
}
