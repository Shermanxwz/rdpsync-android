package com.rdp.sync.ui.rdp

import org.junit.Assert.*
import org.junit.Test

class ViewportTransformTest {
    @Test fun letterboxIsNotClampedIntoRemoteClicks() {
        val t = ViewportTransform().apply { update(1000, 1000, 1600, 900) }
        assertNull(t.toRemote(500f, 100f))
        assertNotNull(t.toRemote(500f, 500f))
        assertEquals(800f, t.toRemote(500f, 500f)!!.x, 0.01f)
        assertEquals(450f, t.toRemote(500f, 500f)!!.y, 0.01f)
    }
    @Test fun zoomIsBoundedAndPanCannotLoseDesktop() {
        val t = ViewportTransform().apply { update(1000, 1000, 1000, 1000) }
        t.transform(100f, 100000f, -100000f, 500f, 500f)
        assertEquals(4f, t.zoom, 0f)
        assertEquals(1500f, t.panX, 0f)
        assertEquals(-1500f, t.panY, 0f)
        t.transform(0.0001f, 0f, 0f, 500f, 500f)
        assertEquals(1f, t.zoom, 0f)
        assertEquals(0f, t.panX, 0f)
        assertEquals(0f, t.panY, 0f)
    }
    @Test fun pinchKeepsFocusAtSameRemotePoint() {
        val t = ViewportTransform().apply { update(1000, 1000, 1000, 1000) }
        val before = t.toRemote(300f, 400f)!!
        t.transform(2f, 0f, 0f, 300f, 400f)
        val after = t.toRemote(300f, 400f)!!
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }
    @Test fun badScaleCannotPoisonGeometry() {
        val t = ViewportTransform()
        t.update(0, 0, 0, 0)
        t.transform(Float.NaN, 0f, 0f, 0f, 0f)
        assertEquals(1f, t.scale, 0f)
    }
    @Test fun keyboardCropPreservesFitScaleAndActualTopTouchTarget() {
        val t = ViewportTransform().apply { update(1920, 807, 1920, 806) }
        val scaleBefore = t.scale
        for (localHeight in listOf(700, 500, 331, 58)) {
            t.update(1920, localHeight, 1920, 806, fitWidth = 1920, fitHeight = 807)
            t.ensureVisible(ViewportTransform.Point(400f, 40f), 42f)
            assertEquals(scaleBefore, t.scale, 0f)
            val target = t.toScreen(400f, 40f)
            assertTrue(target.y in 0f..localHeight.toFloat())
            assertEquals(400f, t.toRemote(target.x, target.y)!!.x, 0.001f)
            assertEquals(40f, t.toRemote(target.x, target.y)!!.y, 0.001f)
        }
    }
    @Test fun keyboardCropPreservesExistingUserZoomAndSnapshotHitMapping() {
        val t = ViewportTransform().apply { update(1920, 807, 1920, 806) }
        t.transform(2f, -50f, 30f, 960f, 400f)
        val scaleBefore = t.scale
        t.update(1920, 331, 1920, 806, fitWidth = 1920, fitHeight = 807)
        t.ensureVisible(ViewportTransform.Point(1600f, 760f), 42f)
        assertEquals(2f, t.zoom, 0f)
        assertEquals(scaleBefore, t.scale, 0f)
        val snapshot = t.snapshot()
        val target = snapshot.toScreen(1600f, 760f)
        assertTrue(target.x in 0f..1920f)
        assertTrue(target.y in 0f..331f)
        assertEquals(t.toRemote(target.x, target.y), snapshot.toRemote(target.x, target.y))
        assertEquals(1600f, snapshot.toRemote(target.x, target.y)!!.x, 0.001f)
        assertEquals(760f, snapshot.toRemote(target.x, target.y)!!.y, 0.001f)
        t.update(1920, 807, 1920, 806, fitWidth = 1920, fitHeight = 807)
        assertEquals(scaleBefore, t.scale, 0f)
        assertEquals(2f, t.zoom, 0f)
    }
    @Test fun croppedPanIsBoundedAndKeyboardHideRestoresFullFit() {
        val t = ViewportTransform().apply { update(1920, 807, 1920, 806) }
        t.update(1920, 331, 1920, 806, fitWidth = 1920, fitHeight = 807)
        // No recent input target means a centered crop, not an invented caret.
        assertEquals(-237.5f, t.top, 0.001f)
        t.transform(1f, 100_000f, -100_000f, 960f, 165f)
        assertEquals(0f, t.left, 0f)
        assertEquals(-475f, t.top, 0f)
        t.transform(1f, -100_000f, 100_000f, 960f, 165f)
        assertEquals(0f, t.top, 0f)
        t.update(1920, 807, 1920, 806, fitWidth = 1920, fitHeight = 807)
        assertEquals(1f, t.scale, 0f)
        assertEquals(0.5f, t.top, 0f)
        assertEquals(0f, t.panY, 0f)
    }

}
