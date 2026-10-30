package com.rdp.sync.ui.rdp

import org.junit.Assert.*
import org.junit.Test

class SessionViewportGeometryTest {
    private val safe = SessionSafeInsets(0, 63, 0, 63)

    private fun compact(ime: Int = 0, availableHeight: Int = 1080) = SessionViewportGeometry.calculate(
        1920, 1080, 1920, availableHeight, 0, 0, safe, ime, 0, 147
    )

    @Test fun fullViewportReservesUnconsumedSystemBars() {
        val g = compact()
        assertEquals(63, g.top)
        assertEquals(1920, g.width)
        assertEquals(807, g.logicalDesktopHeight)
        assertEquals(807, g.localDesktopHeight)
        assertFalse(g.imeOccluded)
        assertEquals(63, g.desktopTop)
        assertTrue(g.localDesktopHeight / 1080.0 >= 0.72)
    }

    @Test fun keyboardShowAnimationAndHideNeverChangeLogicalViewport() {
        // Composition visibility can still say "hidden" on the first smaller layout.
        // No composition Boolean is part of the remote geometry calculation at all.
        for (ime in listOf(0, 1, 150, 400, 560, 623, 560, 400, 1, 0)) {
            val g = compact(ime)
            assertEquals("IME bottom=$ime", 807, g.logicalDesktopHeight)
            assertTrue(g.localDesktopHeight <= 1080 - safe.top - safe.bottom)
        }
        assertEquals(394, compact(623).localDesktopHeight)
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
        assertEquals(457, platformResized.localDesktopHeight)
        assertEquals(807, platformResized.logicalDesktopHeight)
    }

    @Test fun typingUsesAllSafeSpaceWithoutChangingMeasuredBaseChrome() {
        val compactTyping = compact(560)
        val expandedTyping = SessionViewportGeometry.calculate(
            1920, 1080, 1920, 1080, 0, 0, safe, 560, 168, 316
        )
        assertEquals(807, compactTyping.logicalDesktopHeight)
        assertEquals(470, expandedTyping.logicalDesktopHeight)
        assertEquals(457, compactTyping.localDesktopHeight)
        assertEquals(compactTyping.localDesktopHeight, expandedTyping.localDesktopHeight)
        assertEquals(63, compactTyping.desktopTop)
        assertEquals(63, expandedTyping.desktopTop)
        assertTrue(compactTyping.imeOccluded)
    }

    @Test fun rotationWhileKeyboardOpenUsesNewFullWindowGeometry() {
        val portrait = SessionViewportGeometry.calculate(
            1080, 1920, 1080, 1360, 0, 0, safe, 560, 168, 316
        )
        val landscape = compact(560)
        assertEquals(1310, portrait.logicalDesktopHeight)
        assertEquals(807, landscape.logicalDesktopHeight)
        assertEquals(1080, portrait.width)
        assertEquals(1920, landscape.width)
    }

    @Test fun realWindowResizeAndExpandedChromeStillResizeRemoteDesktop() {
        val expanded = SessionViewportGeometry.calculate(
            1920, 1080, 1920, 1080, 0, 0, safe, 0, 168, 316
        )
        val smallerWindow = SessionViewportGeometry.calculate(
            1600, 800, 1600, 800, 0, 0, safe, 0, 0, 147
        )
        assertEquals(470, expanded.logicalDesktopHeight)
        assertEquals(470, expanded.localDesktopHeight)
        assertEquals(527, smallerWindow.logicalDesktopHeight)
        assertEquals(1600, smallerWindow.width)
        assertEquals(807, compact().logicalDesktopHeight)
    }

    @Test fun alreadyInsetHostDoesNotApplyStatusBarTwice() {
        val insetHost = SessionViewportGeometry.calculate(
            1920, 1080, 1920, 1017, 0, 63, safe, 0, 0, 147
        )
        assertEquals(0, insetHost.top)
        assertEquals(807, insetHost.logicalDesktopHeight)
        assertEquals(807, insetHost.localDesktopHeight)
    }

    @Test fun lateralNavigationAndCutoutAreExcludedAndTinySpaceClampsSafely() {
        val lateral = SessionViewportGeometry.calculate(
            1920, 1080, 1920, 1080, 0, 0, SessionSafeInsets(100, 63, 80, 63), 0, 0, 147
        )
        assertEquals(100, lateral.left)
        assertEquals(1740, lateral.width)
        val occluded = compact(1080)
        assertEquals(807, occluded.logicalDesktopHeight)
        assertEquals(0, occluded.localDesktopHeight)
    }
}
