package com.rdp.sync

import android.view.InputDevice
import android.view.MotionEvent

/** Real touchscreen pointer metadata, matching AndroidX UiAutomator's default finger. */
internal fun fixtureFingerEvent(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent {
    val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
    val coordinates = MotionEvent.PointerCoords().apply {
        this.x = x; this.y = y; pressure = 1f; size = 1f
    }
    // The short obtain(x,y,...) overload leaves TOOL_TYPE_UNKNOWN. DocumentsUI's
    // SelectionTracker dispatches gesture selection specifically to FINGER.
    return MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(pointer), arrayOf(coordinates),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
}
