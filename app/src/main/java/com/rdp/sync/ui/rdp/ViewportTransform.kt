package com.rdp.sync.ui.rdp

import kotlin.math.min

/** Shared drawing / hit-testing geometry; letterbox taps are not edge clicks. */
class ViewportTransform {
    data class Point(val x: Float, val y: Float)
    var viewWidth = 1
    var viewHeight = 1
    var remoteWidth = 1
    var remoteHeight = 1
    var zoom = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set
    val scale: Float get() = min(viewWidth.toFloat() / remoteWidth, viewHeight.toFloat() / remoteHeight) * zoom
    val left: Float get() = (viewWidth - remoteWidth * scale) / 2f + panX
    val top: Float get() = (viewHeight - remoteHeight * scale) / 2f + panY

    fun update(viewWidth: Int, viewHeight: Int, remoteWidth: Int, remoteHeight: Int) {
        this.viewWidth = viewWidth.coerceAtLeast(1)
        this.viewHeight = viewHeight.coerceAtLeast(1)
        this.remoteWidth = remoteWidth.coerceAtLeast(1)
        this.remoteHeight = remoteHeight.coerceAtLeast(1)
        constrain()
    }
    fun reset() { zoom = 1f; panX = 0f; panY = 0f }
    fun transform(factor: Float, dx: Float, dy: Float, focusX: Float, focusY: Float) {
        if (!factor.isFinite() || factor <= 0 || !dx.isFinite() || !dy.isFinite()) return
        val oldScale = scale
        val remoteX = (focusX - left) / oldScale
        val remoteY = (focusY - top) / oldScale
        zoom = (zoom * factor).coerceIn(1f, 4f)
        panX = focusX - remoteX * scale - (viewWidth - remoteWidth * scale) / 2f + dx
        panY = focusY - remoteY * scale - (viewHeight - remoteHeight * scale) / 2f + dy
        constrain()
    }
    private fun constrain() {
        val maxX = ((remoteWidth * scale - viewWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((remoteHeight * scale - viewHeight) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }
    fun toRemote(x: Float, y: Float, clamp: Boolean = false): Point? {
        val rx = (x - left) / scale
        val ry = (y - top) / scale
        if (!clamp && (rx < 0 || ry < 0 || rx >= remoteWidth || ry >= remoteHeight)) return null
        return Point(rx.coerceIn(0f, (remoteWidth - 1).toFloat()), ry.coerceIn(0f, (remoteHeight - 1).toFloat()))
    }
    fun toScreen(x: Float, y: Float) = Point(left + x * scale, top + y * scale)
}
