package com.rdp.sync.network

import android.content.Context
import java.io.File
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.os.SystemClock
import com.rdp.sync.display.*
import com.rdp.sync.sharedspace.SharedSpaceListingResult
import com.rdp.sync.sharedspace.SharedSpaceReadResult
import kotlinx.coroutines.delay

object RdpConnector {
    init {
        System.loadLibrary("rdpsync")
    }

    external fun nativeConnect(host: String, port: Int, username: String, password: String, domain: String, width: Int, height: Int): Int
    external fun nativeConnect2(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int): Int
    external fun nativeConnect3(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int, enableTouchInput: Boolean): Int
    external fun nativeConnect4(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int, enableTouchInput: Boolean, compatMode: Boolean): Int
    external fun nativeConnect5(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int, enableTouchInput: Boolean, compatMode: Boolean, expectedFingerprint: String): Int
    external fun nativeConnect6(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int, enableTouchInput: Boolean, compatMode: Boolean, expectedFingerprint: String, fileTransferEnabled: Boolean): Int
    external fun nativeConnect7(host: String, port: Int, username: String, password: String, domain: String, serverName: String, width: Int, height: Int, enableTouchInput: Boolean, compatMode: Boolean, expectedFingerprint: String, fileTransferEnabled: Boolean, sharedSpaceMode: Int): Int
    external fun nativeSharedDriveState(): LongArray
    external fun nativeSharedDriveRevoke(expectedGeneration: Long): Int
    /** Fixed Shared-space staging ceiling; the clipboard accessor remains separately limited. */
    external fun nativeSharedSpaceOpenStaged(directoryId: String, fileId: String, expectedSize: Long): Int
    external fun nativeSharedSpaceList(path: String): SharedSpaceListingResult
    external fun nativeSharedSpaceOpenExport(path: String): LongArray
    external fun nativeSharedSpaceReadExport(handle: Long, offset: Long, length: Int): SharedSpaceReadResult
    external fun nativeSharedSpaceCloseExport(handle: Long): Int
    external fun nativeSharedSpaceImport(fd: Int, size: Long, parent: String, name: String, token: Long): Int
    external fun nativeSharedSpaceBeginImport(token: Long): Int
    external fun nativeSharedSpaceEndImport(token: Long): Int
    external fun nativeSharedSpaceCancelImport(token: Long)
    external fun nativeSharedSpaceSpace(): LongArray
    external fun nativeSharedSpaceMkdir(path: String): Int
    external fun nativeSharedSpaceRename(oldPath: String, newPath: String): Int
    external fun nativeSharedSpaceDelete(path: String): Int
    external fun nativeSharedSpaceOpenImportDirectory(path: String): LongArray
    external fun nativeSharedSpaceOpenMutation(path: String): LongArray
    external fun nativeSharedSpaceImportToDirectory(fd: Int, size: Long, parentHandle: Long, name: String, token: Long): Int
    external fun nativeSharedSpaceMkdirSelection(parentHandle: Long, name: String): Int
    external fun nativeSharedSpaceRenameSelection(handle: Long, newPath: String): Int
    external fun nativeSharedSpaceDeleteSelection(handle: Long): Int
    external fun nativeFileTransferState(): LongArray
    /** Owned readonly CLOEXEC fd; only app-private UUID staging IDs are accepted. */
    external fun nativeFileTransferOpenStaged(directoryId: String, fileId: String, expectedSize: Long): Int
    external fun nativeFileTransferOffer(expectedSessionGeneration: Long, names: Array<String>, sizes: LongArray, fds: IntArray): Long
    external fun nativeFileTransferOfferRetained(token: Long): Boolean
    external fun nativeFileTransferCancel(token: Long): Int
    external fun nativeFileTransferRequestList(expectedSessionGeneration: Long): Long
    external fun nativeFileTransferNames(token: Long): Array<String>?
    external fun nativeFileTransferSizes(token: Long): LongArray?
    external fun nativeFileTransferRequestChunk(token: Long, index: Int, offset: Long, length: Int): Int
    external fun nativeFileTransferTakeChunk(token: Long): ByteArray?
    external fun nativeFileTransferFinishReceive(token: Long): Int
    fun sessionGeneration(): Long = safeNative("sessionGeneration", 0L) { nativeGetTelemetry().getOrElse(11) { 0L } }
    external fun nativeGetCertificateChallenge(): Array<String>?
    external fun nativeDisconnect(): Int
    external fun nativeIsConnected(): Boolean
    external fun nativeGetStatus(): String
    external fun nativeGetDiag(): String
    external fun nativeSetLibDir(path: String)
    external fun nativeInitialize(homePath: String, cachePath: String): Boolean
    /** Allocates/frees a context without connecting; used by runtime diagnostics and instrumentation. */
    external fun nativeCheckRuntime(): Boolean
    external fun nativeGetFrameArgb(width: Int, height: Int): IntArray?
    external fun nativeGetFrameId(): Long
    external fun nativeCopyFrameArgb(buffer: IntArray): Long
    external fun nativeCopyFrameDirtyArgb(buffer: IntArray, dirty: IntArray): Long
    external fun nativeCopyFrameToBitmap(bitmap: Bitmap, dirty: IntArray, forceFull: Boolean): Long
    external fun nativeSendPointerEvent(x: Int, y: Int, button: Int): Int
    external fun nativeSendWheelEvent(x: Int, y: Int, delta: Int): Int
    external fun nativeSendHWheelEvent(x: Int, y: Int, delta: Int): Int
    external fun nativeSendTouchEvent(pointerId: Int, eventType: Int, x: Int, y: Int): Int
    external fun nativeSendKeyEvent(keyCode: Int, down: Int): Int
    external fun nativeSendUnicodeChar(code: Int): Int
    external fun nativeSendClipboardText(text: String): Int
    external fun nativeGetWidth(): Int
    external fun nativeGetHeight(): Int
    external fun nativeRequestResize(width: Int, height: Int, desktopScale: Int): Int
    external fun nativeGetDisplayControlState(): Int
    external fun nativeGetTelemetry(): LongArray
    external fun nativeSendMouseButton(x: Int, y: Int, button: Int, down: Boolean): Int

    private const val TAG = "RdpConnector"
    @Volatile private var lastKotlinError: String = ""
    @Volatile private var nativeInitialized = false

    /** FreeRDP/WinPR requires a writable HOME even on Android before context creation. */
    @Synchronized fun initialize(context: Context): Boolean {
        if (nativeInitialized) return true
        val app = context.applicationContext
        nativeInitialized = try {
            val home = File(app.noBackupFilesDir, "freerdp")
            val cache = File(app.cacheDir, "freerdp")
            (home.isDirectory || home.mkdirs()) && (cache.isDirectory || cache.mkdirs()) &&
                nativeInitialize(home.absolutePath, cache.absolutePath)
        } catch (_: Exception) { false }
        if (!nativeInitialized) lastKotlinError = "远程桌面运行目录初始化失败，请检查本机可用存储空间。"
        return nativeInitialized
    }
    private var cachedBitmap: Bitmap? = null
    private var cachedFrameId = 0L
    private val dirtyScratch = IntArray(4)
    private val frameLock = Any()

    @Volatile var rdpeiConnected = false
    @Volatile var rdpeiDisabled = false
    @Volatile var compatMode = false
    @Volatile var touchFailedCode = 0

    data class DisplayStatus(
        val summary: String,
        val reconnectRequired: Boolean,
        val actual: DesktopSize,
        val requested: DesktopSize?,
        val quality: DisplayQuality,
        val capability: Int,
        val adaptiveReason: String
    )

    private var displaySettings = SessionDisplaySettings()
    private val adaptiveController = AdaptiveQualityController()
    private val resizeCoordinator = ResizeCoordinator()
    private var renderFrames = 0L
    private var renderCopyNanos = 0L
    private var renderDrawNanos = 0L
    private var lastSampleFrames = 0L
    private var lastSampleCopyNanos = 0L
    private var lastSampleDrawNanos = 0L
    private var adaptiveReason = "自适应已关闭"
    private var connectedAtMillis = 0L
    private var lastDisplayStatus = DisplayStatus("等待连接", false, DesktopSize(1280, 720), null,
        DisplayQuality.BALANCED, 0, "自适应已关闭")

    @Synchronized fun configureDisplay(settings: SessionDisplaySettings) {
        if (displaySettings == settings) return
        displaySettings = settings
        adaptiveController.reset(settings.quality)
        resizeCoordinator.reset()
        adaptiveReason = if (settings.adaptive) "等待有效网络测量" else "自适应已关闭"
    }

    @Synchronized fun getDisplaySettings(): SessionDisplaySettings = displaySettings
    @Synchronized fun targetFrameRate(): Int = if (displaySettings.adaptive) adaptiveController.quality.maxFps else displaySettings.quality.maxFps
    @Synchronized fun recordRenderedFrame(copyNanos: Long, drawNanos: Long) {
        renderFrames++
        renderCopyNanos += copyNanos.coerceAtLeast(0)
        renderDrawNanos += drawNanos.coerceAtLeast(0)
    }

    @Synchronized fun resolveDisplay(viewWidth: Int, viewHeight: Int, profileWidth: Int = 1280, profileHeight: Int = 720): DesktopSize =
        DisplayPolicy.resolve(viewWidth, viewHeight, displaySettings, adaptiveController.quality, profileWidth, profileHeight)

    @Synchronized fun updateDisplay(viewWidth: Int, viewHeight: Int, profileWidth: Int = 1280, profileHeight: Int = 720): DisplayStatus {
        val now = SystemClock.elapsedRealtime()
        val stats = safeNative("telemetry", longArrayOf()) { nativeGetTelemetry() }
        val frames = (renderFrames - lastSampleFrames).coerceAtLeast(0)
        val sample = QualitySample(now,
            stats.getOrNull(5)?.takeIf { it > 0 }, stats.getOrNull(6)?.takeIf { it > 0 },
            stats.getOrNull(7)?.takeIf { it >= 0 }, frames,
            if (frames > 0) (renderCopyNanos - lastSampleCopyNanos) / frames / 1_000_000.0 else 0.0,
            if (frames > 0) (renderDrawNanos - lastSampleDrawNanos) / frames / 1_000_000.0 else 0.0)
        lastSampleFrames = renderFrames; lastSampleCopyNanos = renderCopyNanos; lastSampleDrawNanos = renderDrawNanos
        if (displaySettings.adaptive) adaptiveReason = adaptiveController.observe(sample, displaySettings.quality).reason
        val quality = if (displaySettings.adaptive) adaptiveController.quality else displaySettings.quality
        val actual = if (stats.size >= 10 && stats[8] in 1..4096 && stats[9] in 1..4096)
            DesktopSize(stats[8].toInt(), stats[9].toInt()) else lastDisplayStatus.actual
        val desired = resolveDisplay(viewWidth, viewHeight, profileWidth, profileHeight)
        val capability = safeNative("displayControl", 0) { nativeGetDisplayControlState() }
        resizeCoordinator.update(now, desired, actual, capability) { nativeRequestResize(it.width, it.height, 100) }
        val unsupported = capability < 0 || (capability != 2 && now - connectedAtMillis > 8_000)
        val network = if (sample.networkSampleAgeMillis?.let { it <= 10_000 } == true && sample.rttMillis != null)
            " · RTT ${sample.rttMillis} ms" else if (displaySettings.adaptive) " · 网络测量不可用 / 等待中" else ""
        val resolution = "${actual.width} × ${actual.height}"
        val summary = when {
            resizeCoordinator.failure != null -> "$resolution · ${resizeCoordinator.failure}"
            resizeCoordinator.requested != null -> "$resolution → ${resizeCoordinator.requested!!.width} × ${resizeCoordinator.requested!!.height}（等待服务器）"
            unsupported && desired != actual -> "$resolution · 服务器未启用动态分辨率；重连应用设置"
            else -> "$resolution · ${quality.title}$network" + if (displaySettings.adaptive) " · $adaptiveReason" else ""
        }
        lastDisplayStatus = DisplayStatus(summary, (unsupported && desired != actual) || resizeCoordinator.failure != null,
            actual, resizeCoordinator.requested, quality, capability, adaptiveReason)
        return lastDisplayStatus
    }

    @Synchronized fun pollDisplayStatus(): DisplayStatus = lastDisplayStatus

    data class FrameBitmap(val bitmap: Bitmap, val frameId: Long, val dirtyRect: Rect)

    fun connectDevice(host: String, port: Int, username: String, password: String, domain: String, rdpServerName: String = "", width: Int = 1280, height: Int = 720, enableTouchInput: Boolean = true, compatibilityMode: Boolean = false, expectedFingerprint: String = "", fileTransferEnabled: Boolean = false, sharedDriveMode: SharedDriveMode = SharedDriveMode.OFF): Boolean {
        if (!nativeInitialized) {
            lastKotlinError = "远程桌面运行目录未初始化，请重新打开应用并检查本机存储空间。"
            return false
        }
        return try {
            lastKotlinError = ""
            resetFrameCache()
            synchronized(this) {
                resizeCoordinator.reset()
                adaptiveController.reset(displaySettings.quality)
                connectedAtMillis = SystemClock.elapsedRealtime()
                renderFrames = 0; renderCopyNanos = 0; renderDrawNanos = 0
                lastSampleFrames = 0; lastSampleCopyNanos = 0; lastSampleDrawNanos = 0
            }
            compatMode = compatibilityMode
            rdpeiConnected = false
            rdpeiDisabled = !enableTouchInput
            touchFailedCode = 0
            val result = nativeConnect7(
                host.trim(), port, username.trim(), password, domain.trim(), rdpServerName.trim(),
                width, height, enableTouchInput, compatibilityMode, expectedFingerprint, fileTransferEnabled, sharedDriveMode.nativeValue
            )
            Log.d(TAG, "Connect result: $result")
            result == 1
        } catch (e: Throwable) {
            lastKotlinError = "Native ${e::class.java.simpleName}: ${e.message}"
            Log.e(TAG, lastKotlinError, e)
            false
        }
    }

    fun sharedDriveState(): SharedDriveState =
        SharedDriveState.fromNative(safeNative("sharedDriveState", longArrayOf()) { nativeSharedDriveState() })

    fun revokeSharedDrive(expectedGeneration: Long): Boolean = expectedGeneration > 0 &&
        safeNative("revokeSharedDrive", -1) { nativeSharedDriveRevoke(expectedGeneration) } == 0

    fun certificateChallenge(): CertificateChallenge? =
        CertificateChallenge.fromNative(safeNative("certificateChallenge", null) { nativeGetCertificateChallenge() })

    fun disconnect(): Int {
        rdpeiConnected = false
        return safeNative("disconnect", -1) { nativeDisconnect() }
    }

    fun isConnected(): Boolean = safeNative("isConnected", false) { nativeIsConnected() }

    fun sendPointerEvent(x: Int, y: Int, button: Int): Int = safeNative("sendPointerEvent", -1) { nativeSendPointerEvent(x, y, button) }
    fun sendMouseButton(x: Int, y: Int, button: Int, down: Boolean): Int = safeNative("sendMouseButton", -1) { nativeSendMouseButton(x, y, button, down) }
    fun sendWheelEvent(x: Int, y: Int, delta: Int): Int = safeNative("sendWheelEvent", -1) { nativeSendWheelEvent(x, y, delta) }
    fun sendHWheelEvent(x: Int, y: Int, delta: Int): Int = safeNative("sendHWheelEvent", -1) { nativeSendHWheelEvent(x, y, delta) }

    fun sendTouchEvent(pointerId: Int, eventType: Int, x: Int, y: Int): Boolean {
        val rc = safeNative("sendTouchEvent", -1) { nativeSendTouchEvent(pointerId, eventType, x, y) }
        if (rc == 0) {
            if (eventType == 0) rdpeiConnected = true
            return true
        }
        touchFailedCode = rc
        return false
    }

    suspend fun sendRdpeiTap(x: Int, y: Int): Boolean {
        if (!sendTouchEvent(0, 0, x, y)) return false
        delay(30)
        return sendTouchEvent(0, 2, x, y)
    }
    suspend fun sendRdpeiDoubleTap(x: Int, y: Int): Boolean {
        if (!sendTouchEvent(0, 0, x, y)) return false
        delay(25)
        if (!sendTouchEvent(0, 2, x, y)) return false
        delay(80)
        if (!sendTouchEvent(0, 0, x, y)) return false
        delay(25)
        return sendTouchEvent(0, 2, x, y)
    }
    suspend fun sendRdpeiLongPress(x: Int, y: Int): Boolean {
        if (!sendTouchEvent(0, 0, x, y)) return false
        delay(550)
        return sendTouchEvent(0, 2, x, y)
    }

    fun getStatus(): String {
        if (lastKotlinError.isNotBlank()) return lastKotlinError
        return safeNative("getStatus", "not connected") { nativeGetStatus() }
    }

    fun getDiag(): String {
        val native = safeNative("getDiag", "") { nativeGetDiag() }
        val kotlin = buildString {
            // Only add kotlin-side rdpei status when native does not report it,
            // so we never show conflicting lines like "rdpei=not-negotiated" and
            // native "rdpei=connected" simultaneously.
            if (!native.contains("rdpei=", ignoreCase = true)) {
                if (rdpeiConnected) append("rdpei=connected\n")
                else if (rdpeiDisabled) append("rdpei=disabled\n")
                else append("rdpei=not-negotiated\n")
            }
            if (touchFailedCode != 0) append("rdpei=touch-send-failed:$touchFailedCode\n")
            if (compatMode) append("connection=compatibility-retry\n")
        }
        return "$kotlin$native"
    }

    fun getWidth(): Int = safeNative("getWidth", 1280) { nativeGetWidth().coerceIn(1, DisplayPolicy.MAX_DIMENSION) }
    fun getHeight(): Int = safeNative("getHeight", 720) { nativeGetHeight().coerceIn(1, DisplayPolicy.MAX_DIMENSION) }

    fun getFrameBitmap(): Bitmap? = pollFrameBitmap()?.bitmap ?: cachedBitmap

    fun pollFrameBitmap(): FrameBitmap? = synchronized(frameLock) {
        try {
            // One native lock supplies an atomic frame generation + dimensions.
            // Separate width/height calls can form an oversized hybrid during rotation.
            val frameInfo = nativeGetTelemetry()
            if (frameInfo.size < 10) return@synchronized null
            val frameId = frameInfo[0]
            if (frameId <= 0L || frameId == cachedFrameId) return@synchronized null
            val width = frameInfo[8].toInt()
            val height = frameInfo[9].toInt()
            if (width !in 1..DisplayPolicy.MAX_DIMENSION || height !in 1..DisplayPolicy.MAX_DIMENSION ||
                width.toLong() * height > DisplayPolicy.MAX_PIXELS) return@synchronized null
            val current = cachedBitmap?.takeIf { it.width == width && it.height == height }
            val bitmap = current ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val copiedFrameId = nativeCopyFrameToBitmap(bitmap, dirtyScratch, current == null)
            // Native size may advance again after metadata. Retain the previous good
            // bitmap and try the new generation next vsync; never stretch mismatched rows.
            if (copiedFrameId <= 0L) return@synchronized null
            val x = dirtyScratch[0].coerceIn(0, width - 1)
            val y = dirtyScratch[1].coerceIn(0, height - 1)
            val dirtyWidth = dirtyScratch[2].coerceIn(1, width - x)
            val dirtyHeight = dirtyScratch[3].coerceIn(1, height - y)
            cachedBitmap = bitmap
            cachedFrameId = copiedFrameId
            FrameBitmap(bitmap, copiedFrameId, Rect(x, y, x + dirtyWidth, y + dirtyHeight))
        } catch (e: Throwable) {
            Log.e(TAG, "getFrameBitmap failed", e); null
        }
    }

    fun sendKeyEvent(keyCode: Int, down: Int): Int = safeNative("sendKeyEvent", -1) { nativeSendKeyEvent(keyCode, down) }

    fun sendTextInput(text: String): Int {
        var result = 0
        text.forEachIndexed { index, char ->
            result = when (char) {
                '\n', '\r' -> sendKeyStroke(0x1C)
                '\b' -> sendKeyStroke(0x0E)
                else -> {
                    val deadline = SystemClock.elapsedRealtime() + 2_000
                    var code: Int
                    do {
                        code = safeNative("sendUnicodeChar", -1) { nativeSendUnicodeChar(char.code) }
                        if (code == -5) Thread.sleep(4) // queue backpressure, never UI-thread JNI blocking
                    } while (code == -5 && SystemClock.elapsedRealtime() < deadline)
                    code
                }
            }
            if (result != 0) return result
            // This API is called by the IO input actor; pace large commits so native
            // input cannot starve network decode or fill the bounded event queue.
            if (index > 0 && index % 32 == 0) Thread.sleep(4)
        }
        return result
    }

    fun sendKeyStroke(keyCode: Int): Int {
        val down = sendKeyEvent(keyCode, 1)
        val up = sendKeyEvent(keyCode, 0)
        return if (down == 0 && up == 0) 0 else -1
    }

    fun sendClipboardText(text: String): Int = safeNative("sendClipboardText", -1) { nativeSendClipboardText(text) }

    fun <T> withFrameLock(block: () -> T): T = synchronized(frameLock) { block() }

    private fun resetFrameCache() = synchronized(frameLock) { cachedBitmap = null; cachedFrameId = 0L }

    private inline fun <T> safeNative(name: String, fallback: T, block: () -> T): T {
        return try { block() } catch (e: Throwable) { Log.e(TAG, "$name failed", e); fallback }
    }
}
