package com.rdp.sync.ui.rdp

import org.junit.Assert.*
import org.junit.Test

class SessionViewportGeometryTest {
    private val safe = SessionSafeInsets(0, 63, 0, 63)

    private fun compact(ime: Int = 0, availableHeight: Int = 1080) = SessionViewportGeometry.calculate(
        1920, 1080, 1920, availableHeight, 0, 0, safe, ime, 0, 147, 126
    )

    @Test fun fullViewportReservesUnconsumedSystemBars() {
        val g = compact()
        assertEquals(63, g.top)
        assertEquals(1920, g.width)
        assertEquals(807, g.logicalDesktopHeight)
        assertEquals(807, g.localDesktopHeight)
        assertEquals(0, g.keyboardShortcutsHeight)
        assertTrue(g.localDesktopHeight / 1080.0 >= 0.72)
    }

    @Test fun keyboardShowAnimationAndHideNeverChangeLogicalViewport() {
        // Composition visibility can still say "hidden" on the first smaller layout.
        // No composition Boolean is part of the remote geometry calculation at all.
        for (ime in listOf(0, 1, 150, 400, 560, 623, 560, 400, 1, 0)) {
            val g = compact(ime)
            assertEquals("IME bottom=$ime", 807, g.logicalDesktopHeight)
            assertTrue(g.localDesktopHeight <= g.logicalDesktopHeight)
        }
        assertEquals(121, compact(623).localDesktopHeight)
        assertEquals(807, compact(0).localDesktopHeight)
    }

    @Test fun contentCanShrinkBeforeImeInsetsArriveWithoutResizingRemote() {
        val beforeInsets = compact(ime = 0, availableHeight = 247)
        assertEquals(807, beforeInsets.logicalDesktopHeight)
        assertEquals(37, beforeInsets.localDesktopHeight)
        assertEquals(807, compact(623).logicalDesktopHeight)
    }

    @Test fun adjustResizeAlreadyReducedContentDoesNotSubtractImeTwice() {
        val edgeToEdge = compact(560)
        val platformResized = compact(560, availableHeight = 1080 - 560)
        assertEquals(edgeToEdge, platformResized)
        assertEquals(184, platformResized.localDesktopHeight)
        assertEquals(807, platformResized.logicalDesktopHeight)
    }

    @Test fun keyboardShortcutRowIsOnlyLocalChrome() {
        val withShortcuts = compact(560)
        val withoutShortcuts = SessionViewportGeometry.calculate(
            1920, 1080, 1920, 1080, 0, 0, safe, 560, 0, 147, 0
        )
        assertEquals(withShortcuts.logicalDesktopHeight, withoutShortcuts.logicalDesktopHeight)
        assertEquals(126, withoutShortcuts.localDesktopHeight - withShortcuts.localDesktopHeight)
    }

    @Test fun rotationWhileKeyboardOpenUsesNewFullWindowGeometry() {
        val portrait = SessionViewportGeometry.calculate(
            1080, 1920, 1080, 1360, 0, 0, safe, 560, 168, 316, 126
        )
        val landscape = compact(560)
        assertEquals(1310, portrait.logicalDesktopHeight)
        assertEquals(807, landscape.logicalDesktopHeight)
        assertEquals(1080, portrait.width)
        assertEquals(1920, landscape.width)
    }

    @Test fun realWindowResizeAndExpandedChromeStillResizeRemoteDesktop() {
        val expanded = SessionViewportGeometry.calculate(
            1920, 1080, 1920, 1080, 0, 0, safe, 0, 168, 316, 126
        )
        val smallerWindow = SessionViewportGeometry.calculate(
            1600, 800, 1600, 800, 0, 0, safe, 0, 0, 147, 126
        )
        assertEquals(470, expanded.logicalDesktopHeight)
        assertEquals(470, expanded.localDesktopHeight)
        assertEquals(527, smallerWindow.logicalDesktopHeight)
        assertEquals(1600, smallerWindow.width)
        assertEquals(807, compact().logicalDesktopHeight)
    }

    @Test fun alreadyInsetHostDoesNotApplyStatusBarTwice() {
        val insetHost = SessionViewportGeometry.calculate(
            1920, 1080, 1920, 1017, 0, 63, safe, 0, 0, 147, 126
        )
        assertEquals(0, insetHost.top)
        assertEquals(807, insetHost.logicalDesktopHeight)
        assertEquals(807, insetHost.localDesktopHeight)
    }

    @Test fun lateralNavigationAndCutoutAreExcludedAndTinySpaceClampsSafely() {
        val lateral = SessionViewportGeometry.calculate(
            1920, 1080, 1920, 1080, 0, 0, SessionSafeInsets(100, 63, 80, 63), 0, 0, 147, 126
        )
        assertEquals(100, lateral.left)
        assertEquals(1740, lateral.width)
        val occluded = compact(1000)
        assertEquals(807, occluded.logicalDesktopHeight)
        assertEquals(0, occluded.localDesktopHeight)
    }
}
