package com.rdp.sync.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rdp.sync.data.Device
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.network.RdpInputDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RdpConnectionScreen(
    device: Device,
    onBack: () -> Unit
) {
    var status by remember { mutableStateOf("正在连接...") }
    var isConnected by remember { mutableStateOf(false) }
    var pointerMode by remember { mutableStateOf(true) } // true=触控板鼠标；false=直接触摸
    var showPasteDialog by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var diagText by remember { mutableStateOf("") }
    var scale by remember { mutableFloatStateOf(1f) }
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardFocusRequester = remember { FocusRequester() }
    var keyboardValue by remember { mutableStateOf(TextFieldValue("")) }
    var sentKeyboardText by remember { mutableStateOf("") }
    var keyboardActive by remember { mutableStateOf(false) }
    var clipboardDraft by remember { mutableStateOf("") }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var connectStarted by remember(device.id) { mutableStateOf(false) }
    var rdpeiRetryStarted by remember(device.id) { mutableStateOf(false) }
    var userDisconnected by remember(device.id) { mutableStateOf(false) }

    fun handleKeyboardTextChange(newValue: TextFieldValue) {
        keyboardValue = newValue
        if (newValue.composition != null) return

        val oldValue = sentKeyboardText
        val newText = newValue.text
        when {
            newText.length > oldValue.length && newText.startsWith(oldValue) -> {
                RdpConnector.sendTextInput(newText.substring(oldValue.length))
            }
            newText.length < oldValue.length && oldValue.startsWith(newText) -> {
                repeat(oldValue.length - newText.length) {
                    RdpConnector.sendKeyStroke(0x0E)
                }
            }
            newText != oldValue -> {
                val commonPrefix = oldValue.commonPrefixWith(newText).length
                repeat(oldValue.length - commonPrefix) {
                    RdpConnector.sendKeyStroke(0x0E)
                }
                RdpConnector.sendTextInput(newText.substring(commonPrefix))
            }
        }
        if (newText.length > 200) {
            keyboardValue = TextFieldValue("")
            sentKeyboardText = ""
        } else {
            sentKeyboardText = newText
        }
    }

    fun mobileDesktopDimension(value: Int, min: Int): Int {
        val clamped = value.coerceIn(min, 4096)
        return if (clamped % 2 == 0) clamped else clamped - 1
    }

    val remoteWidth = mobileDesktopDimension((viewportSize.width * 0.55f).toInt(), 640)
    val remoteHeight = mobileDesktopDimension((viewportSize.height * 0.55f).toInt(), 960)

    LaunchedEffect(device.id) {
        while (viewportSize.width <= 0 || viewportSize.height <= 0) {
            status = "正在测量屏幕..."
            delay(100)
        }
        if (connectStarted) {
            while (!userDisconnected) {
                status = RdpConnector.getStatus()
                isConnected = RdpConnector.isConnected()
                delay(500)
            }
            return@LaunchedEffect
        }
        connectStarted = true

        val connectWidth = mobileDesktopDimension((viewportSize.width * 0.55f).toInt(), 640)
        val connectHeight = mobileDesktopDimension((viewportSize.height * 0.55f).toInt(), 960)

        fun startConnection(enableTouchInput: Boolean, compatibilityMode: Boolean): Boolean {
            status = if (enableTouchInput) {
                "正在连接 ${device.host}:${device.port} (${connectWidth}x${connectHeight})..."
            } else {
                "正在兼容模式重连 ${device.host}:${device.port}..."
            }
            return RdpConnector.connectDevice(
                host = device.host,
                port = device.port,
                username = device.username,
                password = device.password,
                domain = device.domain,
                rdpServerName = device.rdpServerName,
                width = connectWidth,
                height = connectHeight,
                enableTouchInput = enableTouchInput,
                compatibilityMode = compatibilityMode
            )
        }

        val started = startConnection(enableTouchInput = true, compatibilityMode = false)
        if (!started) {
            val nativeStatus = RdpConnector.getStatus()
            status = if (nativeStatus.isNotBlank() && nativeStatus != "未连接") {
                nativeStatus
            } else {
                "RDP native 启动失败：nativeConnect3 返回 0。请查看诊断信息和 logcat。"
            }
            isConnected = false
            return@LaunchedEffect
        }
        while (true) {
            status = RdpConnector.getStatus()
            isConnected = RdpConnector.isConnected()
            if (!isConnected && !rdpeiRetryStarted &&
                (status.contains("0x0002001c", ignoreCase = true) ||
                    status.contains("Timeout waiting for activation", ignoreCase = true))
            ) {
                rdpeiRetryStarted = true
                RdpConnector.disconnect()
                delay(250)
                startConnection(enableTouchInput = false, compatibilityMode = true)
            }
            delay(500)
        }
    }

    val disposeScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose {
            userDisconnected = true
            disposeScope.launch(Dispatchers.IO) {
                RdpConnector.disconnect()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("远程桌面 - ${device.name}") },
            navigationIcon = {
                IconButton(onClick = {
                    userDisconnected = true
                    onBack()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            },
            actions = { Text(status, style = MaterialTheme.typography.bodySmall) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            )
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0 && size != viewportSize) {
                        viewportSize = size
                    }
                }
                .background(Color(0xFF020617)),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = keyboardValue,
                onValueChange = ::handleKeyboardTextChange,
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(keyboardFocusRequester),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text
                ),
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.Transparent),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent)
            )
            if (isConnected) {
                RdpCanvas(
                    modifier = Modifier.fillMaxSize(),
                    pointerMode = pointerMode,
                    scale = scale,
                    pan = pan,
                    onTransform = { zoom, offset ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                        pan += offset
                    }
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(status, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
                        diagText = RdpConnector.getDiag().ifBlank { "暂无诊断信息" }
                        showDiag = true
                    }) { Text("查看诊断") }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = {
                        val info = RdpConnector.getDiag()
                        if (info.isNotBlank()) {
                            clipboardManager.setText(AnnotatedString(info))
                        }
                    }) { Text("复制诊断信息") }
                }
            }
        }

        Surface(tonalElevation = 8.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { pointerMode = !pointerMode }) {
                        Icon(if (pointerMode) Icons.Default.Mouse else Icons.Default.TouchApp, contentDescription = "切换操作模式")
                    }
                    IconButton(onClick = {
                        keyboardActive = !keyboardActive
                        if (keyboardActive) {
                            keyboardFocusRequester.requestFocus()
                            keyboardController?.show()
                        } else {
                            keyboardController?.hide()
                        }
                    }) {
                        Icon(Icons.Default.Keyboard, contentDescription = "键盘")
                    }
                    IconButton(onClick = {
                        clipboardDraft = clipboardManager.getText()?.text.orEmpty()
                        showPasteDialog = true
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "发送剪贴板")
                    }
                    Button(onClick = {
                        RdpConnector.sendKeyEvent(0x1D, 1)
                        RdpConnector.sendKeyEvent(0x38, 1)
                        RdpConnector.sendKeyEvent(0x53, 1)
                        RdpConnector.sendKeyEvent(0x53, 0)
                        RdpConnector.sendKeyEvent(0x38, 0)
                        RdpConnector.sendKeyEvent(0x1D, 0)
                    }) { Text("Ctrl Alt Del") }
                    IconButton(onClick = {
                        userDisconnected = true
                        onBack()
                    }) { Icon(Icons.Default.Stop, contentDescription = "断开") }
                }
                Text(
                    text = if (pointerMode) {
                        "鼠标模式：拖动移动指针，点击左键，双指缩放/平移"
                    } else {
                        "触摸模式：点哪里点哪里，单指上下/左右滑动滚动"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showPasteDialog) {
        ClipboardDialog(
            initialText = clipboardDraft,
            onDismiss = { showPasteDialog = false }
        )
    }
    if (showDiag) {
        AlertDialog(
            onDismissRequest = { showDiag = false },
            title = { Text("诊断信息") },
            text = {
                Text(
                    text = diagText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(diagText))
                    showDiag = false
                }) { Text("复制并关闭") }
            },
            dismissButton = {
                TextButton(onClick = { showDiag = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
fun RdpCanvas(
    modifier: Modifier = Modifier,
    pointerMode: Boolean,
    scale: Float,
    pan: Offset,
    onTransform: (Float, Offset) -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var dirtyRect by remember { mutableStateOf<AndroidRect?>(null) }
    var frameVersion by remember { mutableStateOf(0L) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastPointer by remember { mutableStateOf(Offset.Zero) }
    var remoteCursor by remember { mutableStateOf(Offset.Zero) }
    var cursorReady by remember { mutableStateOf(false) }
    var touchScrollRemainderX by remember { mutableFloatStateOf(0f) }
    var touchScrollRemainderY by remember { mutableFloatStateOf(0f) }
    var pendingScrollX by remember { mutableFloatStateOf(0f) }
    var pendingScrollY by remember { mutableFloatStateOf(0f) }
    var flingVelocityX by remember { mutableFloatStateOf(0f) }
    var flingVelocityY by remember { mutableFloatStateOf(0f) }
    var touchScrollAxis by remember { mutableStateOf(0) } // 0=undecided, 1=vertical, 2=horizontal
    var touchScrollAnchor by remember { mutableStateOf(Offset.Zero) }
    var touchScrollDrag by remember { mutableStateOf(Offset.Zero) }
    var touchScrollDragging by remember { mutableStateOf(false) }
    var rdpeiTouchActive by remember { mutableStateOf(false) }
    var rdpeiTouchFailed by remember { mutableStateOf(false) }
    var lastDragNanos by remember { mutableStateOf(0L) }

    val dispatcher = remember {
        RdpInputDispatcher(
            touchSender = { pid, et, x, y -> RdpConnector.sendTouchEvent(pid, et, x, y) },
            wheelSender = { x, y, d -> RdpConnector.sendWheelEvent(x, y, d) },
            hwheelSender = { x, y, d -> RdpConnector.sendHWheelEvent(x, y, d) },
            pointerSender = { x, y, btn -> RdpConnector.sendPointerEvent(x, y, btn) }
        )
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (isActive) {
            val frame = withContext(Dispatchers.Default) {
                RdpConnector.pollFrameBitmap()
            }
            if (frame != null) {
                bitmap = frame.bitmap
                dirtyRect = AndroidRect(frame.dirtyRect)
                frameVersion = frame.frameId
            }
            if (!cursorReady && frame != null) {
                remoteCursor = Offset(
                    RdpConnector.getWidth().toFloat() / 2f,
                    RdpConnector.getHeight().toFloat() / 2f
                )
                lastPointer = remoteCursor
                cursorReady = true
            }
            delay(16)
        }
    }

    fun displayMetrics(): Triple<Float, Float, Offset> {
        val remoteWidth = RdpConnector.getWidth().toFloat().coerceAtLeast(1f)
        val remoteHeight = RdpConnector.getHeight().toFloat().coerceAtLeast(1f)
        val viewWidth = canvasSize.width.toFloat().coerceAtLeast(1f)
        val viewHeight = canvasSize.height.toFloat().coerceAtLeast(1f)
        val fitScale = minOf(viewWidth / remoteWidth, viewHeight / remoteHeight)
        val drawWidth = remoteWidth * fitScale * scale
        val drawHeight = remoteHeight * fitScale * scale
        return Triple(
            fitScale,
            fitScale * scale,
            Offset((viewWidth - drawWidth) / 2f + pan.x, (viewHeight - drawHeight) / 2f + pan.y)
        )
    }

    fun clampRemote(offset: Offset): Offset {
        val remoteWidth = RdpConnector.getWidth().toFloat().coerceAtLeast(1f)
        val remoteHeight = RdpConnector.getHeight().toFloat().coerceAtLeast(1f)
        return Offset(
            offset.x.coerceIn(0f, remoteWidth - 1f),
            offset.y.coerceIn(0f, remoteHeight - 1f)
        )
    }

    fun toRemote(offset: Offset): Offset {
        val remoteWidth = RdpConnector.getWidth().toFloat().coerceAtLeast(1f)
        val remoteHeight = RdpConnector.getHeight().toFloat().coerceAtLeast(1f)
        val (_, effectiveScale, origin) = displayMetrics()
        val left = origin.x
        val top = origin.y
        val x = ((offset.x - left) / effectiveScale).coerceIn(0f, remoteWidth - 1f)
        val y = ((offset.y - top) / effectiveScale).coerceIn(0f, remoteHeight - 1f)
        return Offset(x, y)
    }

    fun remoteDeltaFromScreen(delta: Offset): Offset {
        val (_, effectiveScale, _) = displayMetrics()
        val speed = if (scale < 1f) 1.15f else 1f
        return Offset(delta.x / effectiveScale * speed, delta.y / effectiveScale * speed)
    }

    fun toScreen(remote: Offset): Offset {
        val (_, effectiveScale, origin) = displayMetrics()
        return Offset(origin.x + remote.x * effectiveScale, origin.y + remote.y * effectiveScale)
    }

    fun sendMove(remote: Offset) {
        val clamped = clampRemote(remote)
        remoteCursor = clamped
        lastPointer = clamped
        dispatcher.enqueuePointerMove(clamped.x.toInt(), clamped.y.toInt())
    }

    fun clickAt(remote: Offset, button: Int = 1) {
        val clamped = clampRemote(remote)
        remoteCursor = clamped
        lastPointer = clamped
        dispatcher.enqueuePointerClick(clamped.x.toInt(), clamped.y.toInt(), button)
    }

    fun sendRdpeiTouch(pointerId: Int, eventType: Int, remote: Offset): Boolean {
        val clamped = clampRemote(remote)
        remoteCursor = clamped
        lastPointer = clamped
        return if (eventType == 1) {
            // MOVE → throttle-merged via dispatcher, fire-and-forget
            dispatcher.enqueueTouch(pointerId, eventType, clamped.x.toInt(), clamped.y.toInt())
            true
        } else {
            // DOWN/UP/CANCEL → synchronous, never merged
            RdpConnector.sendTouchEvent(pointerId, eventType, clamped.x.toInt(), clamped.y.toInt())
        }
    }

    fun sendScrollFrame(maxStep: Float = 18f) {
        val clamped = clampRemote(touchScrollAnchor)
        remoteCursor = clamped
        lastPointer = clamped

        // Do not send a mouse move before every wheel frame: it adds latency and
        // makes Windows/apps treat a scroll as cursor jitter. Wheel events carry
        // coordinates already, so keep the stream small and regular.
        if (touchScrollAxis == 1) {
            touchScrollRemainderY += pendingScrollY
            pendingScrollY = 0f
            val delta = touchScrollRemainderY.coerceIn(-maxStep, maxStep).roundToInt()
            if (delta != 0) {
                touchScrollRemainderY -= delta
                dispatcher.enqueueWheel(clamped.x.toInt(), clamped.y.toInt(), delta)
            }
        } else if (touchScrollAxis == 2) {
            touchScrollRemainderX += pendingScrollX
            pendingScrollX = 0f
            val delta = touchScrollRemainderX.coerceIn(-maxStep, maxStep).roundToInt()
            if (delta != 0) {
                touchScrollRemainderX -= delta
                dispatcher.enqueueHWheel(clamped.x.toInt(), clamped.y.toInt(), delta)
            }
        }
    }

    LaunchedEffect(pointerMode) {
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (!pointerMode && touchScrollAxis != 0) {
                    val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                    lastFrameNanos = now

                    if (!touchScrollDragging) {
                        pendingScrollX += flingVelocityX * dt
                        pendingScrollY += flingVelocityY * dt
                    }
                    if (!rdpeiTouchActive || rdpeiTouchFailed) {
                        sendScrollFrame()
                    }

                    if (!touchScrollDragging) {
                        // Frame-rate independent decay: close to 0.90 per 60 Hz
                        // frame, but stable if the device drops/duplicates frames.
                        val decay = 0.90f.pow(dt * 60f)
                        flingVelocityX *= decay
                        flingVelocityY *= decay
                        if (abs(flingVelocityX) < 12f) flingVelocityX = 0f
                        if (abs(flingVelocityY) < 12f) flingVelocityY = 0f
                    }
                    if (flingVelocityX == 0f && flingVelocityY == 0f &&
                        abs(pendingScrollX) < 0.5f && abs(pendingScrollY) < 0.5f &&
                        abs(touchScrollRemainderX) < 0.5f && abs(touchScrollRemainderY) < 0.5f
                    ) {
                        touchScrollRemainderX = 0f
                        touchScrollRemainderY = 0f
                    }
                } else {
                    lastFrameNanos = now
                }
            }
        }
    }

    fun queueTouchScroll(remote: Offset, screenDelta: Offset) {
        val clamped = clampRemote(remote)
        remoteCursor = clamped
        lastPointer = clamped
        touchScrollAnchor = clamped

        if (rdpeiTouchActive && !rdpeiTouchFailed) {
            if (sendRdpeiTouch(0, 1, clamped)) {
                return
            }
            rdpeiTouchFailed = true
        }

        touchScrollDrag += screenDelta
        if (touchScrollAxis == 0) {
            val x = abs(touchScrollDrag.x)
            val y = abs(touchScrollDrag.y)
            if (max(x, y) < 8f) return
            // Lock to the dominant axis, but require a meaningful lead so small
            // diagonal finger jitter does not flip vertical lists into horizontal
            // scrolling. This mirrors mobile remote-desktop clients better than a
            // simple y>=x check.
            touchScrollAxis = if (y >= x * 1.25f) 1 else 2
        }

        val now = System.nanoTime()
        val dt = if (lastDragNanos == 0L) 1f / 60f else ((now - lastDragNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastDragNanos = now

        // Conservative scale + smoothed velocity. The old implementation fed
        // instantaneous velocity directly into fling, which made some drags feel
        // like they accelerated twice. Here drag displacement is sent once, and
        // fling velocity is a low-pass filtered estimate for release only.
        val scrollScale = 1.22f
        val velocityBlend = 0.28f
        if (touchScrollAxis == 1) {
            val delta = screenDelta.y * scrollScale
            pendingScrollY += delta
            val instant = (delta / dt).coerceIn(-3200f, 3200f)
            flingVelocityY = flingVelocityY * (1f - velocityBlend) + instant * velocityBlend
            flingVelocityX = 0f
        } else {
            val delta = -screenDelta.x * scrollScale
            pendingScrollX += delta
            val instant = (delta / dt).coerceIn(-3200f, 3200f)
            flingVelocityX = flingVelocityX * (1f - velocityBlend) + instant * velocityBlend
            flingVelocityY = 0f
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(pointerMode, scale, pan) {
                if (pointerMode) {
                    detectTransformGestures { _, panChange, zoom, _ -> onTransform(zoom, panChange) }
                }
            }
            .pointerInput(pointerMode, scale, pan) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (pointerMode) {
                            if (!cursorReady) {
                                remoteCursor = toRemote(offset)
                                cursorReady = true
                            }
                            sendMove(remoteCursor)
                        } else {
                            val remote = toRemote(offset)
                            remoteCursor = remote
                            lastPointer = remote
                            touchScrollAnchor = remote
                            touchScrollAxis = 0
                            touchScrollDrag = Offset.Zero
                            touchScrollDragging = true
                            touchScrollRemainderX = 0f
                            touchScrollRemainderY = 0f
                            pendingScrollX = 0f
                            pendingScrollY = 0f
                            flingVelocityX = 0f
                            flingVelocityY = 0f
                            rdpeiTouchFailed = false
                            rdpeiTouchActive = sendRdpeiTouch(0, 0, remote)
                            lastDragNanos = 0L
                            if (!rdpeiTouchActive) {
                                dispatcher.enqueuePointerMove(remote.x.toInt(), remote.y.toInt())
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (pointerMode) {
                            sendMove(remoteCursor + remoteDeltaFromScreen(dragAmount))
                        } else {
                            val remote = toRemote(change.position)
                            queueTouchScroll(remote, dragAmount)
                        }
                    },
                    onDragEnd = {
                        if (!pointerMode) {
                            touchScrollDragging = false
                            if (rdpeiTouchActive && !rdpeiTouchFailed) {
                                if (!sendRdpeiTouch(0, 2, lastPointer)) rdpeiTouchFailed = true
                            } else {
                                dispatcher.enqueuePointerMove(lastPointer.x.toInt(), lastPointer.y.toInt())
                            }
                            rdpeiTouchActive = false
                        }
                    },
                    onDragCancel = {
                        if (!pointerMode) {
                            touchScrollDragging = false
                            if (rdpeiTouchActive && !rdpeiTouchFailed) {
                                sendRdpeiTouch(0, 3, lastPointer)
                            } else {
                                dispatcher.enqueuePointerMove(lastPointer.x.toInt(), lastPointer.y.toInt())
                            }
                            rdpeiTouchActive = false
                            rdpeiTouchFailed = false
                            touchScrollAxis = 0
                            touchScrollRemainderX = 0f
                            touchScrollRemainderY = 0f
                            pendingScrollX = 0f
                            pendingScrollY = 0f
                            flingVelocityX = 0f
                            flingVelocityY = 0f
                        }
                    }
                )
            }
            .pointerInput(pointerMode, scale, pan) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        if (pointerMode) {
                            clickAt(remoteCursor)
                        } else {
                            val remote = toRemote(tapOffset)
                            coroutineScope.launch {
                                if (!RdpConnector.sendRdpeiTap(remote.x.toInt(), remote.y.toInt())) {
                                    clickAt(remote)
                                }
                            }
                        }
                    },
                    onDoubleTap = { tapOffset ->
if (pointerMode) {
                            val remote = remoteCursor
                            repeat(2) { clickAt(remote) }
                        } else {
                            val remote = toRemote(tapOffset)
                            coroutineScope.launch {
                                if (!RdpConnector.sendRdpeiDoubleTap(remote.x.toInt(), remote.y.toInt())) {
                                    repeat(2) { clickAt(remote) }
                                }
                            }
                        }
                    },
                    onLongPress = { tapOffset ->
                        if (pointerMode) {
                            clickAt(remoteCursor, 2)
                        } else {
                            val remote = toRemote(tapOffset)
                            coroutineScope.launch {
                                if (!RdpConnector.sendRdpeiLongPress(remote.x.toInt(), remote.y.toInt())) {
                                    clickAt(remote, 2)
                                }
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val frame = bitmap
        if (frame != null) {
            val dirty = dirtyRect
            val version = frameVersion
            AndroidView(
                factory = { context -> RdpBitmapView(context) },
                update = { view ->
                    view.setViewportTransform(scale, pan.x, pan.y)
                    view.setFrame(frame, dirty, version)
                },
                modifier = Modifier
                    .fillMaxSize()
            )
        } else {
            Text("等待远程桌面画面...", color = Color.White)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (pointerMode) {
                val cursor = toScreen(remoteCursor)
                drawCircle(Color.White, radius = 11f, center = cursor, style = Stroke(width = 2f))
                drawCircle(Color(0xAA60A5FA), radius = 4f, center = cursor)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            dispatcher.shutdown()
        }
    }
}

private class RdpBitmapView(context: Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var bitmap: Bitmap? = null
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private var frameVersion = 0L

    fun setViewportTransform(newScale: Float, newPanX: Float, newPanY: Float) {
        if (scale == newScale && panX == newPanX && panY == newPanY) return
        scale = newScale
        panX = newPanX
        panY = newPanY
        invalidate()
    }

    fun setFrame(newBitmap: Bitmap, dirtyRect: AndroidRect?, newFrameVersion: Long) {
        if (bitmap !== newBitmap) {
            bitmap = newBitmap
            frameVersion = newFrameVersion
            invalidate()
            return
        }
        if (frameVersion == newFrameVersion) return
        frameVersion = newFrameVersion
        val dirty = dirtyRect
        if (dirty == null || dirty.isEmpty) {
            invalidate()
        } else {
            invalidateRemoteRect(dirty)
        }
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        val frame = bitmap ?: return
        val rect = destinationRect(frame)
        RdpConnector.withFrameLock {
            canvas.drawBitmap(frame, null, rect, paint)
        }
    }

    private fun destinationRect(frame: Bitmap): RectF {
        val viewWidth = width.toFloat().coerceAtLeast(1f)
        val viewHeight = height.toFloat().coerceAtLeast(1f)
        val fitScale = minOf(viewWidth / frame.width, viewHeight / frame.height)
        val effectiveScale = fitScale * scale
        val drawWidth = frame.width * effectiveScale
        val drawHeight = frame.height * effectiveScale
        val left = (viewWidth - drawWidth) / 2f + panX
        val top = (viewHeight - drawHeight) / 2f + panY
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private fun invalidateRemoteRect(remote: AndroidRect) {
        val frame = bitmap ?: return
        val dst = destinationRect(frame)
        val sx = dst.width() / frame.width.toFloat().coerceAtLeast(1f)
        val sy = dst.height() / frame.height.toFloat().coerceAtLeast(1f)
        val left = (dst.left + remote.left * sx).toInt() - 2
        val top = (dst.top + remote.top * sy).toInt() - 2
        val right = (dst.left + remote.right * sx).toInt() + 2
        val bottom = (dst.top + remote.bottom * sy).toInt() + 2
        postInvalidateOnAnimation(
            left.coerceIn(0, width),
            top.coerceIn(0, height),
            right.coerceIn(0, width),
            bottom.coerceIn(0, height)
        )
    }
}

@Composable
private fun ClipboardDialog(initialText: String, onDismiss: () -> Unit) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发送文本") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("文字") })
                Spacer(Modifier.height(8.dp))
                Text("适合一次发送较长文本；实时打字请点键盘图标。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { RdpConnector.sendTextInput(text); onDismiss() }) { Text("发送") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
