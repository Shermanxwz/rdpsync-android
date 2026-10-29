package com.rdp.sync.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One ordered input stream, off the UI thread. Only adjacent motion packets are
 * coalesced. DOWN, UP, CANCEL, clicks and keys are barriers, so a delayed MOVE
 * cannot escape after release or be reused by the next gesture.
 */
class RdpInputDispatcher(
    private val touchSender: (Int, Int, Int, Int) -> Boolean,
    private val wheelSender: (Int, Int, Int) -> Int,
    private val hwheelSender: (Int, Int, Int) -> Int,
    private val pointerSender: (Int, Int, Int) -> Int,
    private val mouseButtonSender: (Int, Int, Int, Boolean) -> Int = { _, _, _, _ -> -1 },
    private val keySender: (Int, Int) -> Int = { _, _ -> -1 },
    private val textSender: (String) -> Int = { -1 },
    workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Contact(val x: Int, val y: Int)
    private sealed interface Packet {
        data class Motion(val id: Int?, val x: Int, val y: Int) : Packet
        data class Touch(val id: Int, val type: Int, val x: Int, val y: Int) : Packet
        data class Direct(val id: Int, val type: Int, val x: Int, val y: Int, val screenX: Float, val screenY: Float, val time: Long, val slop: Float) : Packet
        data class Fling(val generation: Long, val x: Int, val y: Int, val dx: Int, val dy: Int) : Packet
        data class Text(val value: String) : Packet
        data class Wheel(val horizontal: Boolean, val x: Int, val y: Int, val delta: Int) : Packet
        class Action(val action: () -> Unit) : Packet
        class Barrier(val completion: CompletableDeferred<Unit>) : Packet
        data object Release : Packet
        data object Stop : Packet
    }

    private val lock = Any()
    private val queue = ArrayDeque<Packet>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private var accepting = true
    private var backpressured = false
    @Volatile var overloadCount: Long = 0
        private set
    @Volatile var failedTextSends: Long = 0
        private set
    private data class DirectContact(val remoteStartX: Int, val remoteStartY: Int, val startX: Float, val startY: Float, val startedAt: Long, var screenX: Float, var screenY: Float, var native: Boolean, var moved: Boolean = false, var dragging: Boolean = false, var failedAfterDown: Boolean = false, var remainderX: Float = 0f, var remainderY: Float = 0f, var lastTime: Long = startedAt, var velocityX: Float = 0f, var velocityY: Float = 0f)
    private var flingJob: Job? = null
    private var flingGeneration = 0L
    private val directContacts = mutableMapOf<Int, DirectContact>()
    private val contacts = mutableMapOf<Int, Contact>()
    private val keys = mutableSetOf<Int>()
    private val buttons = mutableSetOf<Int>()
    private var pointer = Contact(0, 0)
    private val worker: Job = scope.launch {
        try {
            for (ignored in signal) {
                while (true) {
                    val packet = synchronized(lock) { queue.pollFirst() } ?: break
                    if (packet === Packet.Stop) return@launch
                    // A failed send must not kill the worker before releases.
                    try { dispatch(packet) } catch (_: Exception) { }
                }
            }
        } finally {
            releaseAll()
            signal.close()
        }
    }

    private fun offer(packet: Packet) {
        synchronized(lock) {
            if (!accepting || backpressured) return
            if (queue.size >= 512) {
                overloadCount++
                backpressured = true
                queue.filterIsInstance<Packet.Barrier>().forEach { it.completion.complete(Unit) }
                queue.clear()
                queue.addLast(Packet.Release)
                signal.trySend(Unit)
                return
            }
            val previous = queue.peekLast()
            if (packet is Packet.Motion && previous is Packet.Motion && packet.id == previous.id) {
                queue.removeLast()
            }
            if (packet is Packet.Direct && packet.type == 1 && previous is Packet.Direct && previous.type == 1 && packet.id == previous.id) queue.removeLast()
            if (packet is Packet.Fling && previous is Packet.Fling && packet.generation == previous.generation) {
                queue.removeLast()
                queue.addLast(packet.copy(dx = packet.dx + previous.dx, dy = packet.dy + previous.dy))
            } else if (packet is Packet.Wheel && previous is Packet.Wheel && packet.horizontal == previous.horizontal) {
                queue.removeLast()
                queue.addLast(packet.copy(delta = (packet.delta.toLong() + previous.delta).coerceIn(-32767, 32767).toInt()))
            } else {
                queue.addLast(packet)
            }
        }
        signal.trySend(Unit)
    }

    private fun dispatch(packet: Packet) {
        when (packet) {
            is Packet.Motion -> if (packet.id == null) {
                cancelFling()
                pointer = Contact(packet.x, packet.y)
                pointerSender(packet.x, packet.y, 0)
            } else if (contacts.containsKey(packet.id)) {
                contacts[packet.id] = Contact(packet.x, packet.y)
                touchSender(packet.id, 1, packet.x, packet.y)
            }
            is Packet.Touch -> {
                if (packet.type == 0) cancelFling()
                when (packet.type) {
                    0 -> if (touchSender(packet.id, 0, packet.x, packet.y)) contacts[packet.id] = Contact(packet.x, packet.y)
                    2, 3 -> if (contacts.remove(packet.id) != null) touchSender(packet.id, packet.type, packet.x, packet.y)
                }
            }
            is Packet.Text -> {
                var offset = 0
                while (offset < packet.value.length) {
                    if (!synchronized(lock) { accepting && !backpressured }) break
                    var end = (offset + 64).coerceAtMost(packet.value.length)
                    if (end < packet.value.length && packet.value[end - 1].isHighSurrogate()) end--
                    if (textSender(packet.value.substring(offset, end)) != 0) { failedTextSends++; break }
                    offset = end
                }
            }
            is Packet.Direct -> dispatchDirect(packet)
            is Packet.Wheel -> { cancelFling(); sendWheel(packet.horizontal, packet.x, packet.y, packet.delta) }
            is Packet.Fling -> if (packet.generation == flingGeneration) {
                sendWheel(true, packet.x, packet.y, packet.dx)
                sendWheel(false, packet.x, packet.y, packet.dy)
            }
            is Packet.Action -> packet.action()
            is Packet.Barrier -> packet.completion.complete(Unit)
            Packet.Release -> { releaseAll(); synchronized(lock) { backpressured = false } }
            Packet.Stop -> Unit
        }
    }

    private fun sendWheel(horizontal: Boolean, x: Int, y: Int, delta: Int) {
        var remaining = delta
        while (remaining != 0) {
            val part = remaining.coerceIn(-255, 255)
            if (horizontal) hwheelSender(x, y, part) else wheelSender(x, y, part)
            remaining -= part
        }
    }
    private fun cancelFling() {
        flingGeneration++
        flingJob?.cancel()
        flingJob = null
    }
    private fun startFling(x: Int, y: Int, velocityX: Float, velocityY: Float) {
        cancelFling()
        if (abs(velocityX) < 60f && abs(velocityY) < 60f) return
        val generation = flingGeneration
        flingJob = scope.launch {
            val fling = InertialScroll(velocityX, velocityY)
            var previous = clockMillis()
            while (isActive) {
                delay(16)
                val now = clockMillis()
                val step = fling.step(now - previous)
                previous = now
                if (step.x != 0 || step.y != 0) offer(Packet.Fling(generation, x, y, step.x, step.y))
                if (!step.active) break
            }
        }
    }

    fun enqueuePointerMove(x: Int, y: Int) = offer(Packet.Motion(null, x, y))
    fun enqueuePointerClick(x: Int, y: Int, button: Int) = offer(Packet.Action {
        cancelFling()
        pointer = Contact(x, y)
        pointerSender(x, y, button)
    })
    fun enqueueMouseButton(x: Int, y: Int, button: Int, down: Boolean) = offer(Packet.Action {
        cancelFling()
        pointer = Contact(x, y)
        if (down) buttons.add(button) else buttons.remove(button)
        mouseButtonSender(x, y, button, down)
    })
    fun enqueueWheel(x: Int, y: Int, delta: Int) = offer(Packet.Wheel(false, x, y, delta))
    fun enqueueHWheel(x: Int, y: Int, delta: Int) = offer(Packet.Wheel(true, x, y, delta))
    fun enqueueTouch(pointerId: Int, eventType: Int, x: Int, y: Int) {
        if (eventType == 1) offer(Packet.Motion(pointerId, x, y))
        else offer(Packet.Touch(pointerId, eventType, x, y))
    }
    fun enqueueDirectTouch(id: Int, type: Int, x: Int, y: Int, screenX: Float, screenY: Float, eventTime: Long, slop: Float) =
        offer(Packet.Direct(id, type, x, y, screenX, screenY, eventTime, slop))

    private fun dispatchDirect(packet: Packet.Direct) {
        if (packet.type == 0) {
            cancelFling()
            val native = touchSender(packet.id, 0, packet.x, packet.y)
            if (native) contacts[packet.id] = Contact(packet.x, packet.y)
            directContacts[packet.id] = DirectContact(packet.x, packet.y, packet.screenX, packet.screenY, packet.time, packet.screenX, packet.screenY, native)
            return
        }
        val state = directContacts[packet.id] ?: return
        pointer = Contact(packet.x, packet.y)
        if (state.native) {
            if (packet.type == 1) contacts[packet.id] = pointer
            val sent = touchSender(packet.id, packet.type, packet.x, packet.y)
            if (packet.type == 2 || packet.type == 3) {
                contacts.remove(packet.id)
                directContacts.remove(packet.id)
                return
            }
            if (sent) { state.screenX = packet.screenX; state.screenY = packet.screenY; state.lastTime = packet.time; return }
            touchSender(packet.id, 3, packet.x, packet.y)
            contacts.remove(packet.id)
            state.native = false
            state.failedAfterDown = true
            state.screenX = packet.screenX; state.screenY = packet.screenY; state.lastTime = packet.time
        }
        if (packet.type == 1) {
            val dx = packet.screenX - state.screenX
            val dy = packet.screenY - state.screenY
            state.screenX = packet.screenX
            state.screenY = packet.screenY
            val dt = (packet.time - state.lastTime).coerceIn(8, 80) / 1000f
            state.lastTime = packet.time
            state.velocityX = (state.velocityX * 0.65f - dx / dt * 0.35f).coerceIn(-2400f, 2400f)
            state.velocityY = (state.velocityY * 0.65f + dy / dt * 0.35f).coerceIn(-2400f, 2400f)
            val moved = abs(packet.screenX - state.startX) > packet.slop || abs(packet.screenY - state.startY) > packet.slop
            if (!state.moved && moved && packet.time - state.startedAt >= 500) {
                state.dragging = true
                buttons.add(1)
                mouseButtonSender(state.remoteStartX, state.remoteStartY, 1, true)
            }
            state.moved = state.moved || moved
            if (state.dragging) pointerSender(packet.x, packet.y, 0)
            else if (state.moved) {
                state.remainderX -= dx
                state.remainderY += dy
                val wx = state.remainderX.roundToInt()
                val wy = state.remainderY.roundToInt()
                state.remainderX -= wx
                state.remainderY -= wy
                if (wx != 0) sendWheel(true, state.remoteStartX, state.remoteStartY, wx)
                if (wy != 0) sendWheel(false, state.remoteStartX, state.remoteStartY, wy)
            }
        } else if (packet.type == 2 || packet.type == 3) {
            if (state.dragging) { mouseButtonSender(packet.x, packet.y, 1, false); buttons.remove(1) }
            else if (packet.type == 2 && !state.moved && !state.failedAfterDown) {
                pointerSender(packet.x, packet.y, if (packet.time - state.startedAt >= 500) 2 else 1)
            }
            if (packet.type == 2 && state.moved && !state.dragging && packet.time - state.lastTime <= 120) {
                startFling(state.remoteStartX, state.remoteStartY, state.velocityX, state.velocityY)
            }
            directContacts.remove(packet.id)
        }
    }

    fun enqueueKey(code: Int, down: Boolean) = offer(Packet.Action {
        if (down) keys.add(code) else keys.remove(code)
        keySender(code, if (down) 1 else 0)
    })
    fun enqueueKeyStroke(code: Int) = offer(Packet.Action {
        keySender(code, 1)
        keySender(code, 0)
    })
    fun enqueueText(text: String) { if (text.isNotEmpty()) offer(Packet.Text(text)) }
    fun enqueueShortcut(vararg codes: Int) = offer(Packet.Action {
        try { codes.forEach { keys.add(it); keySender(it, 1) } }
        finally { codes.reversed().forEach { keySender(it, 0); keys.remove(it) } }
    })

    /** For a stateful fallback recognizer. Its entire operation runs in order. */
    fun enqueueAction(action: () -> Unit) = offer(Packet.Action(action))
    fun releasePressedInputs() = offer(Packet.Release)

    private fun releaseAll() {
        cancelFling()
        contacts.toMap().forEach { (id, point) -> runCatching { touchSender(id, 3, point.x, point.y) } }
        contacts.clear()
        directContacts.clear()
        buttons.toSet().forEach { runCatching { mouseButtonSender(pointer.x, pointer.y, it, false) } }
        buttons.clear()
        keys.toSet().forEach { runCatching { keySender(it, 0) } }
        keys.clear()
    }

    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (!accepting) return
            queue.addLast(Packet.Barrier(done))
        }
        signal.trySend(Unit)
        done.await()
    }

    /** Reject old-session events immediately; release held state before exiting. */
    fun shutdown() {
        synchronized(lock) {
            if (!accepting) return
            accepting = false
            queue.filterIsInstance<Packet.Barrier>().forEach { it.completion.complete(Unit) }
            queue.clear()
            queue.addLast(Packet.Release)
            queue.addLast(Packet.Stop)
        }
        signal.trySend(Unit)
    }
    suspend fun shutdownAndJoin() {
        shutdown()
        worker.join()
        scope.cancel()
    }
}
