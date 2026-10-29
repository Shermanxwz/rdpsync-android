package com.rdp.sync.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RdpInputDispatcherTest {
    @Test fun motionCoalescesOnlyBetweenBarriersAndPreservesContactIds() = runTest {
        val events = mutableListOf<String>()
        val input = RdpInputDispatcher({ id, type, x, _ -> events.add("$id:$type:$x"); true }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, workerDispatcher = StandardTestDispatcher(testScheduler))
        input.enqueueTouch(7, 0, 10, 10)
        input.enqueueTouch(7, 1, 11, 10)
        input.enqueueTouch(7, 1, 12, 10)
        input.enqueueTouch(9, 0, 90, 10)
        input.enqueueTouch(7, 1, 13, 10)
        input.enqueueTouch(9, 1, 91, 10)
        input.enqueueTouch(7, 2, 13, 10)
        input.enqueueTouch(7, 1, 14, 10) // stale motion is ignored after UP
        input.enqueueTouch(7, 0, 30, 10)
        input.enqueueTouch(7, 2, 30, 10)
        runCurrent()
        assertEquals(listOf("7:0:10", "7:1:12", "9:0:90", "7:1:13", "9:1:91", "7:2:13", "7:0:30", "7:2:30"), events)
        input.shutdownAndJoin()
        assertEquals("9:3:91", events.last())
    }

    @Test fun pointerClickIsABarrierAndWheelDeltaIsNeverTruncated() = runTest {
        val events = mutableListOf<String>()
        val wheels = mutableListOf<Int>()
        val input = RdpInputDispatcher({ _, _, _, _ -> true }, { _, _, d -> wheels.add(d); 0 }, { _, _, _ -> 0 }, { x, _, b -> events.add("$x:$b"); 0 }, workerDispatcher = StandardTestDispatcher(testScheduler))
        input.enqueuePointerMove(1, 1); input.enqueuePointerMove(2, 2)
        input.enqueuePointerClick(3, 3, 1)
        input.enqueuePointerMove(4, 4)
        input.enqueueWheel(1, 1, 120); input.enqueueWheel(1, 1, 500)
        runCurrent()
        assertEquals(listOf("2:0", "3:1", "4:0"), events)
        assertEquals(620, wheels.sum())
        assertTrue(wheels.all { it in -255..255 })
        input.shutdownAndJoin()
    }

    @Test fun shutdownReleasesHeldInputsAndRejectsOldSessionEvents() = runTest {
        val events = mutableListOf<String>()
        val input = RdpInputDispatcher({ id, type, _, _ -> events.add("touch:$id:$type"); true }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, { _, _, b -> events.add("click:$b"); 0 }, { _, _, b, d -> events.add("button:$b:$d"); 0 }, { c, d -> events.add("key:$c:$d"); 0 }, workerDispatcher = StandardTestDispatcher(testScheduler))
        input.enqueueTouch(3, 0, 5, 5); input.enqueueKey(29, true); input.enqueueMouseButton(5, 5, 1, true)
        runCurrent()
        input.enqueuePointerClick(5, 5, 1)
        input.shutdown()
        input.enqueueKey(30, true)
        input.shutdownAndJoin()
        assertEquals(listOf("touch:3:0", "key:29:1", "button:1:true", "touch:3:3", "button:1:false", "key:29:0"), events)
    }

    @Test fun unsupportedTouchFallsBackWithoutClicksOnCancelOrScroll() = runTest {
        val clicks = mutableListOf<Int>()
        val wheels = mutableListOf<Int>()
        val input = RdpInputDispatcher({ _, _, _, _ -> false }, { _, _, d -> wheels.add(d); 0 }, { _, _, _ -> 0 }, { _, _, b -> if (b != 0) clicks.add(b); 0 }, workerDispatcher = StandardTestDispatcher(testScheduler))
        fun touch(type: Int, y: Int, time: Long) = input.enqueueDirectTouch(8, type, 10, y, 10f, y.toFloat(), time, 8f)
        touch(0, 10, 0); touch(2, 10, 50)
        touch(0, 10, 100); touch(3, 10, 200)
        touch(0, 10, 300); touch(1, 40, 350); touch(2, 40, 400)
        touch(0, 10, 500); touch(2, 10, 1100)
        runCurrent()
        assertEquals(listOf(1, 2), clicks)
        assertEquals(30, wheels.sum())
        input.shutdownAndJoin()
    }

    @Test fun supportedTouchHasNoSyntheticClicksAndCancelsOnFocusLoss() = runTest {
        val events = mutableListOf<String>()
        val input = RdpInputDispatcher({ id, t, x, _ -> events.add("$id:$t:$x"); true }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, { _, _, _ -> fail("No mouse fallback for supported touch"); 0 }, workerDispatcher = StandardTestDispatcher(testScheduler))
        input.enqueueDirectTouch(5, 0, 1, 1, 1f, 1f, 0, 8f)
        input.enqueueDirectTouch(5, 1, 10, 10, 10f, 10f, 20, 8f)
        input.enqueueDirectTouch(5, 1, 20, 20, 20f, 20f, 30, 8f)
        input.releasePressedInputs()
        input.enqueueDirectTouch(5, 2, 20, 20, 20f, 20f, 40, 8f)
        runCurrent()
        assertEquals(listOf("5:0:1", "5:1:20", "5:3:20"), events)
        input.shutdownAndJoin()
    }

    @Test fun fallbackLongPressDragUsesInitialAnchorAndReleases() = runTest {
        val events = mutableListOf<String>()
        val input = RdpInputDispatcher({ _, _, _, _ -> false }, { _, _, _ -> fail("drag must not scroll"); 0 }, { _, _, _ -> 0 }, { x, y, _ -> events.add("move:$x:$y"); 0 }, { x, y, _, d -> events.add("button:$x:$y:$d"); 0 }, workerDispatcher = StandardTestDispatcher(testScheduler))
        input.enqueueDirectTouch(1, 0, 10, 10, 10f, 10f, 0, 8f)
        input.enqueueDirectTouch(1, 1, 30, 40, 30f, 40f, 600, 8f)
        input.enqueueDirectTouch(1, 2, 30, 40, 30f, 40f, 650, 8f)
        runCurrent()
        assertEquals(listOf("button:10:10:true", "move:30:40", "button:30:40:false"), events)
        input.shutdownAndJoin()
    }
    @Test fun boundedQueueFailsClosedAndRecoversAfterReleaseBarrier() = runTest {
        val keys = mutableListOf<Pair<Int, Int>>()
        val input = RdpInputDispatcher({ _, _, _, _ -> true }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, keySender = { c, d -> keys.add(c to d); 0 }, workerDispatcher = StandardTestDispatcher(testScheduler))
        input.enqueueKey(29, true)
        runCurrent()
        repeat(600) { input.enqueueKeyStroke(30) }
        runCurrent()
        assertEquals(1L, input.overloadCount)
        assertEquals(listOf(29 to 1, 29 to 0), keys)
        input.enqueueKeyStroke(31)
        runCurrent()
        assertEquals(listOf(31 to 1, 31 to 0), keys.takeLast(2))
        input.shutdownAndJoin()
    }

    @Test fun failedTextStopsInsteadOfSilentlyContinuingWithLaterChunks() = runTest {
        val chunks = mutableListOf<String>()
        val input = RdpInputDispatcher({ _, _, _, _ -> true }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, textSender = { chunks.add(it); -1 }, workerDispatcher = StandardTestDispatcher(testScheduler))
        input.enqueueText("a".repeat(200))
        runCurrent()
        assertEquals(1, chunks.size)
        assertEquals(64, chunks.single().length)
        assertEquals(1L, input.failedTextSends)
        input.shutdownAndJoin()
    }

    @Test fun fallbackFlingIsBoundedAndCanceledByNewGestureAndFocusLoss() = runTest {
        val wheels = mutableListOf<Int>()
        val input = RdpInputDispatcher({ _, _, _, _ -> false }, { _, _, d -> wheels.add(d); 0 }, { _, _, _ -> 0 }, { _, _, _ -> 0 }, workerDispatcher = StandardTestDispatcher(testScheduler), clockMillis = { testScheduler.currentTime })
        fun gesture(time: Long) {
            input.enqueueDirectTouch(1, 0, 10, 10, 10f, 10f, time, 8f)
            input.enqueueDirectTouch(1, 1, 10, 110, 10f, 110f, time + 50, 8f)
            input.enqueueDirectTouch(1, 2, 10, 110, 10f, 110f, time + 60, 8f)
        }
        gesture(0); runCurrent()
        val dragTotal = wheels.sum()
        advanceTimeBy(100); runCurrent()
        assertTrue(wheels.sum() > dragTotal)
        input.enqueueDirectTouch(1, 0, 10, 10, 10f, 10f, 200, 8f)
        runCurrent(); val stopped = wheels.sum()
        advanceTimeBy(1000); runCurrent()
        assertEquals(stopped, wheels.sum())
        input.releasePressedInputs(); runCurrent()
        gesture(1500); runCurrent(); advanceTimeBy(50); runCurrent()
        input.releasePressedInputs(); runCurrent(); val focusStopped = wheels.sum()
        advanceTimeBy(1000); runCurrent()
        assertEquals(focusStopped, wheels.sum())
        gesture(3000); runCurrent(); advanceUntilIdle()
        val completed = wheels.sum()
        advanceTimeBy(5000); runCurrent()
        assertEquals(completed, wheels.sum())
        assertTrue(wheels.size < 160)
        input.shutdownAndJoin()
    }

    @Test fun supportedRdpeiNeverGetsSyntheticWheelInertia() = runTest {
        val input = RdpInputDispatcher({ _, _, _, _ -> true }, { _, _, _ -> fail("RDPEI must not also fling wheels"); 0 }, { _, _, _ -> fail("RDPEI must not also fling wheels"); 0 }, { _, _, _ -> 0 }, workerDispatcher = StandardTestDispatcher(testScheduler), clockMillis = { testScheduler.currentTime })
        input.enqueueDirectTouch(1, 0, 0, 0, 0f, 0f, 0, 8f)
        input.enqueueDirectTouch(1, 1, 0, 200, 0f, 200f, 40, 8f)
        input.enqueueDirectTouch(1, 2, 0, 200, 0f, 200f, 50, 8f)
        advanceUntilIdle()
        input.shutdownAndJoin()
    }

}
