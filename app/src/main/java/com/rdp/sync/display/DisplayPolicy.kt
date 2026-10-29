package com.rdp.sync.display

import kotlin.math.min
import kotlin.math.sqrt

/** Wire resolution and local presentation are separate: this policy never stretches a desktop. */
enum class DisplayQuality(val title: String, val maxFps: Int, val pixelScale: Double) {
    ECONOMY("流畅 / 省流", 30, 0.60),
    BALANCED("均衡", 60, 0.80),
    SHARP("清晰", 60, 1.0)
}

enum class ResolutionMode(val title: String) {
    FIT_WINDOW("适应窗口"), PROFILE("使用设备配置"), HD("1280 × 720"), FULL_HD("1920 × 1080")
}

data class SessionDisplaySettings(
    val quality: DisplayQuality = DisplayQuality.BALANCED,
    val resolution: ResolutionMode = ResolutionMode.FIT_WINDOW,
    val adaptive: Boolean = false
)

data class DesktopSize(val width: Int, val height: Int)

object DisplayPolicy {
    const val MIN_DIMENSION = 200
    const val MAX_DIMENSION = 4096
    const val MAX_PIXELS = 8_388_608

    /** Manual modes are exact when adaptive is off. Fit mode always preserves the view aspect. */
    fun resolve(
        viewportWidth: Int,
        viewportHeight: Int,
        settings: SessionDisplaySettings,
        adaptiveQuality: DisplayQuality = settings.quality,
        profileWidth: Int = 1280,
        profileHeight: Int = 720
    ): DesktopSize {
        val source = when (settings.resolution) {
            ResolutionMode.FIT_WINDOW -> DesktopSize(viewportWidth, viewportHeight)
            ResolutionMode.PROFILE -> DesktopSize(profileWidth, profileHeight)
            ResolutionMode.HD -> DesktopSize(1280, 720)
            ResolutionMode.FULL_HD -> DesktopSize(1920, 1080)
        }
        val quality = if (settings.adaptive) DisplayQuality.entries[minOf(adaptiveQuality.ordinal, settings.quality.ordinal)] else settings.quality
        val scale = when {
            settings.resolution == ResolutionMode.FIT_WINDOW -> quality.pixelScale
            settings.adaptive -> quality.pixelScale / settings.quality.pixelScale
            else -> 1.0
        }
        return boundedSize(source.width, source.height, scale.coerceAtMost(1.0))
    }

    /** Keep arithmetic in Double/Long so malicious/corrupt synced profile sizes cannot overflow. */
    fun boundedSize(width: Int, height: Int, requestedScale: Double = 1.0): DesktopSize {
        val w = width.coerceAtLeast(MIN_DIMENSION).toDouble()
        val h = height.coerceAtLeast(MIN_DIMENSION).toDouble()
        val scale = min(
            requestedScale.takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
            min(MAX_DIMENSION / maxOf(w, h), sqrt(MAX_PIXELS.toDouble() / (w * h)))
        )
        fun even(value: Double): Int = ((value + 1e-8).toInt().coerceIn(MIN_DIMENSION, MAX_DIMENSION) / 2) * 2
        var outWidth = even(w * scale)
        var outHeight = even(h * scale)
        // A floating-point epsilon must never weaken the strict native area cap.
        while (outWidth.toLong() * outHeight > MAX_PIXELS) {
            if (outWidth >= outHeight) outWidth -= 2 else outHeight -= 2
        }
        return DesktopSize(outWidth, outHeight)
    }
}

/** A server-reported measurement, not an inference from interface type or bytes received. */
data class QualitySample(
    val nowMillis: Long,
    val rttMillis: Long? = null,
    val bandwidthKbps: Long? = null,
    val networkSampleAgeMillis: Long? = null,
    val renderedFrames: Long = 0,
    val averageCopyMillis: Double = 0.0,
    val averageDrawMillis: Double = 0.0
)

data class QualityDecision(val quality: DisplayQuality, val reason: String, val changed: Boolean)

/** Conservative, monotonic-clock policy. Idle desktops, absent/stale telemetry do not downgrade. */
class AdaptiveQualityController(initial: DisplayQuality = DisplayQuality.BALANCED) {
    var quality: DisplayQuality = initial
        private set
    private var badWindows = 0
    private var goodWindows = 0
    private var lastDecisionAt = Long.MIN_VALUE
    private var lastSampleAt = Long.MIN_VALUE
    private var lastNetworkMeasurementAt = Long.MIN_VALUE
    private var ewmaRtt: Double? = null
    private var ewmaBandwidth: Double? = null

    fun reset(initial: DisplayQuality) {
        quality = initial
        badWindows = 0
        goodWindows = 0
        lastDecisionAt = Long.MIN_VALUE
        lastSampleAt = Long.MIN_VALUE
        lastNetworkMeasurementAt = Long.MIN_VALUE
        ewmaRtt = null
        ewmaBandwidth = null
    }

    fun observe(sample: QualitySample, maximum: DisplayQuality): QualityDecision {
        // Sampling repeatedly in one render tick must not manufacture sustained evidence.
        if (lastSampleAt != Long.MIN_VALUE && sample.nowMillis - lastSampleAt < 900)
            return QualityDecision(quality, "采样中", false)
        lastSampleAt = sample.nowMillis
        val fresh = sample.networkSampleAgeMillis?.let { it in 0..10_000 } == true
        val measuredAt = sample.nowMillis - (sample.networkSampleAgeMillis ?: 0)
        val newNetworkSample = fresh && (lastNetworkMeasurementAt == Long.MIN_VALUE || measuredAt - lastNetworkMeasurementAt >= 500)
        val rtt = sample.rttMillis?.takeIf { newNetworkSample && it > 0 }
        val bandwidth = sample.bandwidthKbps?.takeIf { newNetworkSample && it > 0 }
        if (rtt != null || bandwidth != null) lastNetworkMeasurementAt = measuredAt
        fun smooth(previous: Double?, value: Long): Double = previous?.let { it * 0.65 + value * 0.35 } ?: value.toDouble()
        if (rtt != null) ewmaRtt = smooth(ewmaRtt, rtt)
        if (bandwidth != null) ewmaBandwidth = smooth(ewmaBandwidth, bandwidth)
        val hasNetwork = rtt != null || bandwidth != null
        val active = sample.renderedFrames >= 3
        val renderPressure = active && (sample.averageCopyMillis > 20.0 || sample.averageDrawMillis > 20.0)
        if (!hasNetwork && fresh && !renderPressure)
            return QualityDecision(quality, "等待新的网络测量", false)
        val badNetwork = hasNetwork && ((rtt != null && (ewmaRtt ?: 0.0) >= 180.0) ||
            (bandwidth != null && (ewmaBandwidth ?: Double.MAX_VALUE) < 1_500))
        // Upgrades need actual network evidence. Unknown latency is not a healthy sample.
        val healthyNetwork = hasNetwork && (rtt == null || (ewmaRtt ?: Double.MAX_VALUE) < 100.0) &&
            (bandwidth == null || (ewmaBandwidth ?: 0.0) > 4_000)
        val healthyRender = !active || (sample.averageCopyMillis < 10.0 && sample.averageDrawMillis < 10.0)
        when {
            badNetwork || renderPressure -> { badWindows++; goodWindows = 0 }
            healthyNetwork && healthyRender -> { goodWindows++; badWindows = 0 }
            else -> { goodWindows = 0; badWindows = 0 }
        }
        val elapsed = if (lastDecisionAt == Long.MIN_VALUE) Long.MAX_VALUE else sample.nowMillis - lastDecisionAt
        val next = when {
            quality.ordinal > maximum.ordinal -> maximum
            badWindows >= 3 && elapsed >= 12_000 && quality != DisplayQuality.ECONOMY -> DisplayQuality.entries[quality.ordinal - 1]
            goodWindows >= 15 && elapsed >= 30_000 && quality.ordinal < maximum.ordinal -> DisplayQuality.entries[quality.ordinal + 1]
            else -> quality
        }
        val changed = next != quality
        val reason = when {
            renderPressure -> "持续渲染压力"
            badNetwork -> "网络延迟 / 带宽压力"
            healthyNetwork -> "网络稳定"
            else -> "等待有效网络测量（静止画面不会降级）"
        }
        if (changed) {
            quality = next
            badWindows = 0
            goodWindows = 0
            lastDecisionAt = sample.nowMillis
        }
        return QualityDecision(quality, reason, changed)
    }
}
