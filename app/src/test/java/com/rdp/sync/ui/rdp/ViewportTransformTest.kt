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
}
