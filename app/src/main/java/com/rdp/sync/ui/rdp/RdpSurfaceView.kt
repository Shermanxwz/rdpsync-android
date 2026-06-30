package com.rdp.sync.ui.rdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.rdp.sync.network.RdpConnector
import kotlin.math.abs

/**
 * Vsync-paced RDP renderer.
 *
 * This view replaces the old Compose Image frame loop. It keeps one reusable
 * Bitmap, copies native framebuffer only when frameId changes, and draws on a
 * SurfaceView aligned to Choreographer vsync. During touch scrolling it applies
 * a small visual prediction offset so the desktop follows the finger before the
 * remote server returns a new frame.
 */
class RdpSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Choreographer.FrameCallback {
    private var running = false
    private var bitmap: Bitmap? = null
    private var lastFrameId = -1L
    private var lastDrawNanos = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()

    @Volatile var predictedOffsetX: Float = 0f
        private set
    @Volatile var predictedOffsetY: Float = 0f
        private set

    @Volatile private var scale = 1f
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var pointerMode = true
    @Volatile private var cursorX = -1f
    @Volatile private var cursorY = -1f

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setTransform(scale: Float, panX: Float, panY: Float) {
        this.scale = scale.coerceIn(0.5f, 4f)
        this.panX = panX
        this.panY = panY
    }

    fun setPointerMode(enabled: Boolean) {
        pointerMode = enabled
    }

    fun setCursor(x: Float, y: Float) {
        cursorX = x
        cursorY = y
    }

    fun addPredictedOffset(dx: Float, dy: Float) {
        // Disabled for 1.0.6 stability: moving the full Surface during a drag
        // looks like screen shake in real RDP sessions. Smoothness now comes
        // from SurfaceView/vsync, frameId-gated Bitmap reuse, 1ms FreeRDP loop,
        // and wheel batching instead of visual whole-frame translation.
        predictedOffsetX = 0f
        predictedOffsetY = 0f
    }

    fun resetPrediction() {
        predictedOffsetX = 0f
        predictedOffsetY = 0f
    }

    fun screenToRemote(x: Float, y: Float): Pair<Float, Float> {
        val w = RdpConnector.getWidth().toFloat().coerceAtLeast(1f)
        val h = RdpConnector.getHeight().toFloat().coerceAtLeast(1f)
        val rect = calculateFitRect(width, height, w.toInt(), h.toInt(), includePrediction = false)
        val rx = ((x - rect.left) / rect.width() * w).coerceIn(0f, w - 1f)
        val ry = ((y - rect.top) / rect.height() * h).coerceIn(0f, h - 1f)
        return rx to ry
    }

    fun screenDeltaToRemote(dx: Float, dy: Float): Pair<Float, Float> {
        val remoteW = RdpConnector.getWidth().toFloat().coerceAtLeast(1f)
        val remoteH = RdpConnector.getHeight().toFloat().coerceAtLeast(1f)
        val rect = calculateFitRect(width, height, remoteW.toInt(), remoteH.toInt(), includePrediction = false)
        return (dx / rect.width() * remoteW) to (dy / rect.height() * remoteH)
    }

    fun remoteToScreen(x: Float, y: Float): Pair<Float, Float> {
        val remoteW = RdpConnector.getWidth().toFloat().coerceAtLeast(1f)
        val remoteH = RdpConnector.getHeight().toFloat().coerceAtLeast(1f)
        val rect = calculateFitRect(width, height, remoteW.toInt(), remoteH.toInt(), includePrediction = true)
        return (rect.left + x / remoteW * rect.width()) to (rect.top + y / remoteH * rect.height())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        Choreographer.getInstance().postFrameCallback(this)
        Log.i(TAG, "surfaceCreated: vsync renderer started")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        bitmap?.recycle()
        bitmap = null
        Log.i(TAG, "surfaceDestroyed: renderer stopped")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        drawLatestFrame(frameTimeNanos)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun ensureBitmap(): Bitmap? {
        val w = RdpConnector.getWidth()
        val h = RdpConnector.getHeight()
        if (w <= 0 || h <= 0) return null
        val current = bitmap
        if (current == null || current.width != w || current.height != h || current.isRecycled) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            lastFrameId = -1L
            Log.i(TAG, "create reusable bitmap ${w}x${h}")
        }
        return bitmap
    }

    private fun drawLatestFrame(frameTimeNanos: Long) {
        val bmp = ensureBitmap() ?: return
        val frameId = RdpConnector.getFrameId()
        var copied = false
        if (frameId > 0 && frameId != lastFrameId) {
            if (RdpConnector.copyFrameToBitmap(bmp)) {
                lastFrameId = frameId
                copied = true
                predictedOffsetX *= 0.65f
                predictedOffsetY *= 0.65f
                if (abs(predictedOffsetX) < 1f) predictedOffsetX = 0f
                if (abs(predictedOffsetY) < 1f) predictedOffsetY = 0f
            }
        }

        // Avoid continuously redrawing old frames when there is no local prediction.
        if (!copied && predictedOffsetX == 0f && predictedOffsetY == 0f && lastDrawNanos != 0L) return

        val canvas = try {
            holder.lockCanvas()
        } catch (t: Throwable) {
            Log.e(TAG, "lockCanvas failed", t)
            null
        } ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val src = Rect(0, 0, bmp.width, bmp.height)
            val target = calculateFitRect(canvas.width, canvas.height, bmp.width, bmp.height, includePrediction = true)
            canvas.drawBitmap(bmp, src, target, paint)
            if (pointerMode && cursorX >= 0f && cursorY >= 0f) drawCursor(canvas)
            lastDrawNanos = frameTimeNanos
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawCursor(canvas: Canvas) {
        val (sx, sy) = remoteToScreen(cursorX, cursorY)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.WHITE
        canvas.drawCircle(sx, sy, 11f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xAA60A5FA.toInt()
        canvas.drawCircle(sx, sy, 4.5f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun calculateFitRect(viewW: Int, viewH: Int, bmpW: Int, bmpH: Int, includePrediction: Boolean): RectF {
        val vw = viewW.toFloat().coerceAtLeast(1f)
        val vh = viewH.toFloat().coerceAtLeast(1f)
        val bw = bmpW.toFloat().coerceAtLeast(1f)
        val bh = bmpH.toFloat().coerceAtLeast(1f)
        val base = minOf(vw / bw, vh / bh)
        val drawW = bw * base * scale
        val drawH = bh * base * scale
        val px = if (includePrediction) predictedOffsetX else 0f
        val py = if (includePrediction) predictedOffsetY else 0f
        dst.set(
            (vw - drawW) / 2f + panX + px,
            (vh - drawH) / 2f + panY + py,
            (vw + drawW) / 2f + panX + px,
            (vh + drawH) / 2f + panY + py
        )
        return RectF(dst)
    }

    private companion object {
        const val TAG = "RdpSurface"
    }
}
