package com.rdp.sync.ui.rdp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.network.RdpInputDispatcher
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Vsync-driven desktop and one gesture owner. Bitmap updates never recompose the
 * surrounding screen and an idle remote desktop does not redraw every vsync.
 */
// This view is constructed by AndroidView with a session-scoped input dependency, never XML.
@SuppressLint("ViewConstructor")
class RdpDesktopView(context: Context, private val input: RdpInputDispatcher) : View(context), Choreographer.FrameCallback {
    /** Opt-in fixture diagnostics: numeric lengths/lifecycle only, never input content.
     * No observer is installed by production callers. Observer failures cannot alter input. */
    data class ImeDiagnostic(val connection: Long, val operation: String, val argumentLength: Int,
                             val composingBefore: Int, val composingAfter: Int)
    private var imeConnectionSequence = 0L
    private var imeDiagnosticCount = 0
    var imeDiagnosticDropped = 0
        private set
    var imeDiagnosticObserver: ((ImeDiagnostic) -> Unit)? = null
        set(value) { field = value; imeDiagnosticCount = 0; imeDiagnosticDropped = 0 }
    private fun traceIme(connection: Long, operation: String, argumentLength: Int, before: Int, after: Int) {
        val observer = imeDiagnosticObserver ?: return
        if (imeDiagnosticCount >= 128) { imeDiagnosticDropped++; return }
        imeDiagnosticCount++
        runCatching { observer(ImeDiagnostic(connection, operation, argumentLength, before, after)) }
    }

    private val viewport = ViewportTransform()
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density }
    private var bitmap: Bitmap? = null
    private var logicalViewportWidth = 0
    private var logicalViewportHeight = 0
    private var lastInputTarget: ViewportTransform.Point? = null
    private val framePacer = FramePacer()
    private var pendingCopyNanos = 0L
    private var pendingFrame = false
    private var running = false
    private var pointerMode = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var primaryId = -1
    private var activeTouch = false
    private var multiGesture = false
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var mouseDragging = false
    private var mouseButtons = 0
    private val unicodeKeys = mutableSetOf<Int>()
    private var deadAccent = 0
    private var previousSpan = 0f
    private var previousCenterX = 0f
    private var previousCenterY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "远程桌面，单指触摸，双指缩放和平移"
        setBackgroundColor(Color.rgb(2, 6, 23))
        keepScreenOn = true
    }

    fun setPointerMode(enabled: Boolean) {
        if (pointerMode == enabled) return
        cancelGesture()
        pointerMode = enabled
        contentDescription = if (enabled) "远程桌面，触控板模式" else "远程桌面，直接触摸模式"
        invalidate()
    }
    fun resetZoom() { viewport.reset(); invalidate() }
    /** Use the non-IME layout for fit; a smaller local View crops instead of shrinking text. */
    fun setLogicalViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == logicalViewportWidth && height == logicalViewportHeight)) return
        logicalViewportWidth = width
        logicalViewportHeight = height
        cancelGesture()
        updateViewport(keepInputTargetVisible = true)
        invalidate()
    }
    /** Read on the UI thread; exactly the geometry used for drawing and input mapping. */
    fun viewportSnapshot(): ViewportTransform.Snapshot = viewport.snapshot()
    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        cancelGesture()
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelGesture()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        updateViewport(keepInputTargetVisible = true)
    }
    private fun updateViewport(keepInputTargetVisible: Boolean = false) {
        val frame = bitmap ?: return
        val fitWidth = logicalViewportWidth.takeIf { it > 0 } ?: width
        val fitHeight = logicalViewportHeight.takeIf { it > 0 } ?: height
        viewport.update(width, height, frame.width, frame.height, fitWidth, fitHeight)
        if (keepInputTargetVisible && (height < fitHeight || width < fitWidth)) {
            lastInputTarget?.let { viewport.ensureVisible(it, 16f * resources.displayMetrics.density) }
        }
        cursorX = cursorX.coerceIn(0f, (frame.width - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (frame.height - 1).toFloat())
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (isShown && windowVisibility == VISIBLE && framePacer.shouldPoll(frameTimeNanos, RdpConnector.targetFrameRate())) {
            val started = System.nanoTime()
            val frame = RdpConnector.pollFrameBitmap()
            if (frame != null) {
                pendingCopyNanos = System.nanoTime() - started
                pendingFrame = true
                val previous = bitmap
                if (previous == null) { cursorX = frame.bitmap.width / 2f; cursorY = frame.bitmap.height / 2f }
                val resized = previous != null && (previous.width != frame.bitmap.width || previous.height != frame.bitmap.height)
                if (resized) {
                    cancelGesture()
                    viewport.reset()
                    // Keep the last real RDP pixel coordinate, not a guessed caret
                    // or a proportional remap. A new desktop frame can arrive after
                    // local rotation/IME layout; discarding this anchor centers the
                    // cropped viewport and hides the actual input region.
                }
                bitmap = frame.bitmap
                // ensureVisible clamps that known point against the NEW frame's
                // bounds, and pans only when the local viewport is cropped.
                updateViewport(keepInputTargetVisible = resized)
                invalidate()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = bitmap ?: return
        val started = System.nanoTime()
        destination.set(viewport.left, viewport.top, viewport.left + frame.width * viewport.scale, viewport.top + frame.height * viewport.scale)
        RdpConnector.withFrameLock { canvas.drawBitmap(frame, null, destination, paint) }
        if (pointerMode) {
            canvas.drawCircle(viewport.left + cursorX * viewport.scale, viewport.top + cursorY * viewport.scale, 9f * resources.displayMetrics.density, cursorPaint)
        }
        if (pendingFrame) {
            RdpConnector.recordRenderedFrame(pendingCopyNanos, System.nanoTime() - started)
            pendingFrame = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return handleMouse(event)
        if (bitmap == null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
                primaryId = event.getPointerId(0)
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                downTime = event.eventTime; moved = false; multiGesture = false; mouseDragging = false
                val remote = viewport.toRemote(event.x, event.y)
                activeTouch = remote != null
                if (!pointerMode && remote != null) direct(event, 0, remote, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multiGesture) cancelRemoteTouch(event)
                multiGesture = true
                if (event.pointerCount >= 2) updateMultiOrigin(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiGesture) {
                    if (event.pointerCount >= 2) {
                        val centerX = (event.getX(0) + event.getX(1)) / 2f
                        val centerY = (event.getY(0) + event.getY(1)) / 2f
                        val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                        if (previousSpan > 0f) {
                            val factor = if (previousSpan > touchSlop) span / previousSpan else 1f
                            viewport.transform(factor, centerX - previousCenterX, centerY - previousCenterY, previousCenterX, previousCenterY)
                        }
                        updateMultiOrigin(event)
                        invalidate()
                    }
                } else if (activeTouch) {
                    val index = event.findPointerIndex(primaryId)
                    if (index >= 0) {
                        val x = event.getX(index); val y = event.getY(index)
                        val movement = abs(x - downX) > touchSlop || abs(y - downY) > touchSlop
                        if (pointerMode) {
                            if (!moved && movement && event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout()) {
                                mouseDragging = true
                                input.enqueueMouseButton(cursorX.toInt(), cursorY.toInt(), 1, true)
                            }
                            cursorX = (cursorX + (x - lastX) / viewport.scale).coerceIn(0f, (viewport.remoteWidth - 1).toFloat())
                            cursorY = (cursorY + (y - lastY) / viewport.scale).coerceIn(0f, (viewport.remoteHeight - 1).toFloat())
                            lastInputTarget = ViewportTransform.Point(cursorX, cursorY)
                            input.enqueuePointerMove(cursorX.toInt(), cursorY.toInt())
                            invalidate()
                        } else viewport.toRemote(x, y, clamp = true)?.let { direct(event, 1, it, index) }
                        moved = moved || movement
                        lastX = x; lastY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Never reinterpret the remaining finger as a fresh click.
                multiGesture = true
                if (event.getPointerId(event.actionIndex) == primaryId) cancelRemoteTouch(event)
                previousSpan = 0f
            }
            MotionEvent.ACTION_UP -> {
                if (activeTouch && !multiGesture) {
                    if (pointerMode) {
                        lastInputTarget = ViewportTransform.Point(cursorX, cursorY)
                        if (mouseDragging) input.enqueueMouseButton(cursorX.toInt(), cursorY.toInt(), 1, false)
                        else if (!moved) input.enqueuePointerClick(cursorX.toInt(), cursorY.toInt(), if (event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout()) 2 else 1)
                    } else viewport.toRemote(event.x, event.y, clamp = true)?.let { direct(event, 2, it, 0) }
                    if (!moved) performClick()
                }
                activeTouch = false; primaryId = -1; multiGesture = false; mouseDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun direct(event: MotionEvent, type: Int, point: ViewportTransform.Point, index: Int) {
        if (type != 3) lastInputTarget = point
        input.enqueueDirectTouch(primaryId, type, point.x.toInt(), point.y.toInt(), event.getX(index), event.getY(index), event.eventTime, touchSlop)
    }
    private fun updateMultiOrigin(event: MotionEvent) {
        previousCenterX = (event.getX(0) + event.getX(1)) / 2f
        previousCenterY = (event.getY(0) + event.getY(1)) / 2f
        previousSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }
    private fun cancelRemoteTouch(event: MotionEvent) {
        if (activeTouch && !pointerMode) {
            val index = event.findPointerIndex(primaryId)
            if (index >= 0) viewport.toRemote(event.getX(index), event.getY(index), clamp = true)?.let { direct(event, 3, it, index) }
        }
        if (mouseDragging) input.enqueueMouseButton(cursorX.toInt(), cursorY.toInt(), 1, false)
        activeTouch = false; mouseDragging = false
    }
    private fun cancelGesture() {
        input.releasePressedInputs()
        activeTouch = false; mouseDragging = false; primaryId = -1; mouseButtons = 0; multiGesture = false
        unicodeKeys.clear(); deadAccent = 0
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun handleMouse(event: MotionEvent): Boolean {
        val remote = viewport.toRemote(event.x, event.y, clamp = mouseButtons != 0) ?: return true
        requestFocus()
        cursorX = remote.x; cursorY = remote.y
        if (event.actionMasked != MotionEvent.ACTION_CANCEL) lastInputTarget = remote
        input.enqueuePointerMove(remote.x.toInt(), remote.y.toInt())
        val current = if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) 0 else event.buttonState
        listOf(MotionEvent.BUTTON_PRIMARY to 1, MotionEvent.BUTTON_SECONDARY to 2, MotionEvent.BUTTON_TERTIARY to 3).forEach { (mask, button) ->
            if ((current and mask) != (mouseButtons and mask)) input.enqueueMouseButton(remote.x.toInt(), remote.y.toInt(), button, current and mask != 0)
        }
        mouseButtons = current
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            input.enqueueWheel(remote.x.toInt(), remote.y.toInt(), (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).roundToInt())
            input.enqueueHWheel(remote.x.toInt(), remote.y.toInt(), (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).roundToInt())
        }
        invalidate()
        return true
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) handleMouse(event) else super.onGenericMotionEvent(event)

    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        val connection = ++imeConnectionSequence
        traceIme(connection, "create", 0, 0, 0)
        return object : BaseInputConnection(this, false) {
            private val buffer = RemoteImeBuffer(input::enqueueText, input::enqueueKeyStroke)
            private inline fun <T> observed(operation: String, argumentLength: Int = 0, block: () -> T): T {
                val before = buffer.composing.length
                try { return block() }
                finally { traceIme(connection, operation, argumentLength, before, buffer.composing.length) }
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = observed("compose", text?.length ?: -1) { buffer.compose(text?.toString().orEmpty()); true }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = observed("commit", text?.length ?: -1) { buffer.commit(text?.toString().orEmpty()); true }
            override fun finishComposingText(): Boolean = observed("finish") { buffer.finish(); true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = observed("delete-code-units", beforeLength) { buffer.deleteCodeUnits(beforeLength, afterLength); true }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = observed("delete-code-points", beforeLength) { buffer.deleteCodePoints(beforeLength, afterLength); true }
            override fun closeConnection() = observed("close") { buffer.clear(); super.closeConnection() }
            override fun performEditorAction(editorAction: Int): Boolean = observed("editor-action") { input.enqueueKeyStroke(0x1C); true }
            override fun sendKeyEvent(event: KeyEvent): Boolean = observed(if (event.action == KeyEvent.ACTION_DOWN) "key-down" else if (event.action == KeyEvent.ACTION_UP) "key-up" else "key-other") { this@RdpDesktopView.dispatchKeyEvent(event) }
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.composing.takeLast(n.coerceAtLeast(0))
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence = ""
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val unicode = event.unicodeChar
        // Android's keyboard layout determines printable characters. Fixed US scan
        // codes remain correct for shortcuts, not for e.g. AZERTY text entry.
        if ((unicode >= 0x20 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) && unicode != 0x7F && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            unicodeKeys.add(keyCode)
            if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
                deadAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            } else {
                val combined = if (deadAccent != 0) KeyCharacterMap.getDeadChar(deadAccent, unicode) else unicode
                if (combined != 0) input.enqueueText(String(Character.toChars(combined)))
                else { input.enqueueText(String(Character.toChars(deadAccent))); input.enqueueText(String(Character.toChars(unicode))) }
                deadAccent = 0
            }
            return true
        }
        val scan = keyScanCode(keyCode)
        if (scan != null) { input.enqueueKey(scan, true); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (unicodeKeys.remove(keyCode)) return true
        val scan = keyScanCode(keyCode)
        if (scan != null) { input.enqueueKey(scan, false); return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun keyScanCode(key: Int): Int? = when (key) {
        KeyEvent.KEYCODE_ESCAPE -> 0x01
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x3A
        KeyEvent.KEYCODE_NUM_LOCK -> 0x45
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0x46
        KeyEvent.KEYCODE_MENU -> 0xE05D
        KeyEvent.KEYCODE_DEL -> 0x0E
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xE053
        KeyEvent.KEYCODE_TAB -> 0x0F
        KeyEvent.KEYCODE_ENTER -> 0x1C
        KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xE01C
        KeyEvent.KEYCODE_CTRL_LEFT -> 0x1D
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xE01D
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0x2A
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0x36
        KeyEvent.KEYCODE_ALT_LEFT -> 0x38
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xE038
        KeyEvent.KEYCODE_META_LEFT -> 0xE05B
        KeyEvent.KEYCODE_META_RIGHT -> 0xE05C
        KeyEvent.KEYCODE_DPAD_UP -> 0xE048
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xE050
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xE04B
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xE04D
        KeyEvent.KEYCODE_MOVE_HOME -> 0xE047
        KeyEvent.KEYCODE_MOVE_END -> 0xE04F
        KeyEvent.KEYCODE_PAGE_UP -> 0xE049
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xE051
        KeyEvent.KEYCODE_INSERT -> 0xE052
        KeyEvent.KEYCODE_F1 -> 0x3B
        KeyEvent.KEYCODE_F2 -> 0x3C
        KeyEvent.KEYCODE_F3 -> 0x3D
        KeyEvent.KEYCODE_F4 -> 0x3E
        KeyEvent.KEYCODE_F5 -> 0x3F
        KeyEvent.KEYCODE_F6 -> 0x40
        KeyEvent.KEYCODE_F7 -> 0x41
        KeyEvent.KEYCODE_F8 -> 0x42
        KeyEvent.KEYCODE_F9 -> 0x43
        KeyEvent.KEYCODE_F10 -> 0x44
        KeyEvent.KEYCODE_F11 -> 0x57
        KeyEvent.KEYCODE_F12 -> 0x58
        else -> LETTER_SCAN_CODES[key]
    }
    companion object {
        private val LETTER_SCAN_CODES = mapOf(
            KeyEvent.KEYCODE_A to 0x1E, KeyEvent.KEYCODE_B to 0x30, KeyEvent.KEYCODE_C to 0x2E,
            KeyEvent.KEYCODE_D to 0x20, KeyEvent.KEYCODE_E to 0x12, KeyEvent.KEYCODE_F to 0x21,
            KeyEvent.KEYCODE_G to 0x22, KeyEvent.KEYCODE_H to 0x23, KeyEvent.KEYCODE_I to 0x17,
            KeyEvent.KEYCODE_J to 0x24, KeyEvent.KEYCODE_K to 0x25, KeyEvent.KEYCODE_L to 0x26,
            KeyEvent.KEYCODE_M to 0x32, KeyEvent.KEYCODE_N to 0x31, KeyEvent.KEYCODE_O to 0x18,
            KeyEvent.KEYCODE_P to 0x19, KeyEvent.KEYCODE_Q to 0x10, KeyEvent.KEYCODE_R to 0x13,
            KeyEvent.KEYCODE_S to 0x1F, KeyEvent.KEYCODE_T to 0x14, KeyEvent.KEYCODE_U to 0x16,
            KeyEvent.KEYCODE_V to 0x2F, KeyEvent.KEYCODE_W to 0x11, KeyEvent.KEYCODE_X to 0x2D,
            KeyEvent.KEYCODE_Y to 0x15, KeyEvent.KEYCODE_Z to 0x2C,
            KeyEvent.KEYCODE_1 to 0x02, KeyEvent.KEYCODE_2 to 0x03, KeyEvent.KEYCODE_3 to 0x04,
            KeyEvent.KEYCODE_4 to 0x05, KeyEvent.KEYCODE_5 to 0x06, KeyEvent.KEYCODE_6 to 0x07,
            KeyEvent.KEYCODE_7 to 0x08, KeyEvent.KEYCODE_8 to 0x09, KeyEvent.KEYCODE_9 to 0x0A,
            KeyEvent.KEYCODE_0 to 0x0B, KeyEvent.KEYCODE_SPACE to 0x39
        )
    }
}
