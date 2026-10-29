package com.rdp.sync.display

import org.junit.Assert.*
import org.junit.Test

class ResizeCoordinatorTest {
    private val old = DesktopSize(1280, 720)
    private val rotated = DesktopSize(720, 1280)
    @Test fun debounceAndActualAcknowledgementAreSeparate() {
        val c = ResizeCoordinator(); var sent = 0
        val send: (DesktopSize) -> Int = { sent++; 0 }
        c.update(0, rotated, old, 2, send)
        c.update(500, rotated, old, 2, send)
        assertEquals(0, sent)
        c.update(800, rotated, old, 2, send)
        assertEquals(rotated, c.requested)
        c.update(1800, rotated, old, 2, send)
        assertEquals(1, sent)
        c.update(2000, rotated, rotated, 2, send)
        assertNull(c.requested)
    }
    @Test fun unsupportedDoesNotSendOrReconnect() {
        val c = ResizeCoordinator()
        repeat(100) { c.update(it * 1000L, rotated, old, 0) { error("must not send before caps") } }
        assertNull(c.requested)
    }
    @Test fun ignoredResizeTimesOutWithoutInfiniteRetries() {
        val c = ResizeCoordinator(); var sent = 0
        val send: (DesktopSize) -> Int = { sent++; 0 }
        repeat(30) { c.update(it * 1000L, rotated, old, 2, send) }
        assertEquals(1, sent)
        assertEquals(rotated, c.failedTarget)
        assertNotNull(c.failure)
        c.reset()
        c.update(31000, rotated, old, 2, send)
        c.update(32000, rotated, old, 2, send)
        assertEquals(2, sent)
    }
}
