package com.rdp.sync.ui.rdp

import kotlin.math.min

/** Shared drawing / hit-testing geometry; letterbox taps are not edge clicks. */
class ViewportTransform {
    data class Point(val x: Float, val y: Float)
    /** Read-only geometry shared by diagnostics and pixel/input verification. */
    data class Snapshot(
        val viewWidth: Int, val viewHeight: Int, val remoteWidth: Int, val remoteHeight: Int,
        val fitWidth: Int, val fitHeight: Int, val scale: Float, val left: Float, val top: Float,
        val zoom: Float, val panX: Float, val panY: Float
    ) {
        fun toScreen(x: Float, y: Float) = Point(left + x * scale, top + y * scale)
        fun toRemote(x: Float, y: Float, clamp: Boolean = false): Point? =
            mapToRemote(x, y, left, top, scale, remoteWidth, remoteHeight, clamp)
    }
    var viewWidth = 1
    var viewHeight = 1
    private var fitWidth = 1
    private var fitHeight = 1
    var remoteWidth = 1
    var remoteHeight = 1
    var zoom = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set
    val scale: Float get() = min(fitWidth.toFloat() / remoteWidth, fitHeight.toFloat() / remoteHeight) * zoom
    val left: Float get() = (viewWidth - remoteWidth * scale) / 2f + panX
    val top: Float get() = (viewHeight - remoteHeight * scale) / 2f + panY

    fun update(viewWidth: Int, viewHeight: Int, remoteWidth: Int, remoteHeight: Int,
               fitWidth: Int = viewWidth, fitHeight: Int = viewHeight) {
        this.viewWidth = viewWidth.coerceAtLeast(1)
        this.viewHeight = viewHeight.coerceAtLeast(1)
        this.fitWidth = fitWidth.coerceAtLeast(1)
        this.fitHeight = fitHeight.coerceAtLeast(1)
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
    /** Pan only enough to reveal a real recent input target; never infer a remote caret. */
    fun ensureVisible(target: Point, margin: Float) {
        if (!target.x.isFinite() || !target.y.isFinite()) return
        val xMargin = margin.coerceIn(0f, viewWidth / 4f)
        val yMargin = margin.coerceIn(0f, viewHeight / 4f)
        val point = toScreen(target.x.coerceIn(0f, (remoteWidth - 1).toFloat()), target.y.coerceIn(0f, (remoteHeight - 1).toFloat()))
        panX += point.x.coerceIn(xMargin, viewWidth - xMargin) - point.x
        panY += point.y.coerceIn(yMargin, viewHeight - yMargin) - point.y
        constrain()
    }
    fun snapshot() = Snapshot(viewWidth, viewHeight, remoteWidth, remoteHeight, fitWidth, fitHeight,
        scale, left, top, zoom, panX, panY)
    fun toRemote(x: Float, y: Float, clamp: Boolean = false): Point? =
        mapToRemote(x, y, left, top, scale, remoteWidth, remoteHeight, clamp)
    fun toScreen(x: Float, y: Float) = Point(left + x * scale, top + y * scale)

    companion object {
        private fun mapToRemote(x: Float, y: Float, left: Float, top: Float, scale: Float,
                                remoteWidth: Int, remoteHeight: Int, clamp: Boolean): Point? {
            val rx = (x - left) / scale
            val ry = (y - top) / scale
            if (!clamp && (rx < 0 || ry < 0 || rx >= remoteWidth || ry >= remoteHeight)) return null
            return Point(rx.coerceIn(0f, (remoteWidth - 1).toFloat()), ry.coerceIn(0f, (remoteHeight - 1).toFloat()))
        }
    }
}
