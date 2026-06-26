package com.rdp.sync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rdp.sync.data.Device
import com.rdp.sync.network.RdpConnector
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdpConnectionScreen(
    device: Device,
    onBack: () -> Unit
) {
    var status by remember { mutableStateOf("正在连接...") }
    var isConnected by remember { mutableStateOf(false) }
    var pointerMode by remember { mutableStateOf(true) } // true=触控板鼠标；false=直接触摸
    var showKeyboard by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(device.id) {
        status = "正在连接 ${device.host}:${device.port}..."
        val started = RdpConnector.connectDevice(
            host = device.host,
            port = device.port,
            username = device.username,
            password = device.password,
            domain = device.domain,
            width = device.width,
            height = device.height
        )
        if (!started) {
            status = "连接启动失败"
            isConnected = false
            return@LaunchedEffect
        }
        while (true) {
            status = RdpConnector.getStatus()
            isConnected = RdpConnector.isConnected()
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { RdpConnector.disconnect() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("远程桌面 - ${device.name}") },
            navigationIcon = {
                IconButton(onClick = {
                    RdpConnector.disconnect()
                    onBack()
                }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
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
                .background(Color(0xFF020617)),
            contentAlignment = Alignment.Center
        ) {
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
                    IconButton(onClick = { showKeyboard = !showKeyboard }) {
                        Icon(Icons.Default.Keyboard, contentDescription = "键盘")
                    }
                    IconButton(onClick = { showPasteDialog = true }) {
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
                        RdpConnector.disconnect()
                        onBack()
                    }) { Icon(Icons.Default.Stop, contentDescription = "断开") }
                }
                Text(
                    text = if (pointerMode) "鼠标模式：拖动移动指针，点击左键，双指缩放/平移" else "触摸模式：点哪里点哪里，拖动即拖拽，双指缩放/平移",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showKeyboard) {
        KeyboardDialog(onDismiss = { showKeyboard = false })
    }
    if (showPasteDialog) {
        ClipboardDialog(onDismiss = { showPasteDialog = false })
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
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastPointer by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        while (true) {
            bitmap = RdpConnector.getFrameBitmap()?.asImageBitmap()
            delay(33)
        }
    }

    fun toRemote(offset: Offset): Offset {
        val remoteWidth = RdpConnector.getWidth().toFloat().coerceAtLeast(1f)
        val remoteHeight = RdpConnector.getHeight().toFloat().coerceAtLeast(1f)
        val viewWidth = canvasSize.width.toFloat().coerceAtLeast(1f)
        val viewHeight = canvasSize.height.toFloat().coerceAtLeast(1f)
        val fitScale = minOf(viewWidth / remoteWidth, viewHeight / remoteHeight)
        val drawWidth = remoteWidth * fitScale * scale
        val drawHeight = remoteHeight * fitScale * scale
        val left = (viewWidth - drawWidth) / 2f + pan.x
        val top = (viewHeight - drawHeight) / 2f + pan.y
        val x = ((offset.x - left) / (fitScale * scale)).coerceIn(0f, remoteWidth - 1f)
        val y = ((offset.y - top) / (fitScale * scale)).coerceIn(0f, remoteHeight - 1f)
        return Offset(x, y)
    }

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(pointerMode, scale, pan) {
                detectTransformGestures { _, panChange, zoom, _ -> onTransform(zoom, panChange) }
            }
            .pointerInput(pointerMode, scale, pan) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val remote = toRemote(offset)
                        lastPointer = remote
                        if (!pointerMode) RdpConnector.sendPointerEvent(remote.x.toInt(), remote.y.toInt(), 1)
                    },
                    onDrag = { change, _ ->
                        val remote = toRemote(change.position)
                        lastPointer = remote
                        RdpConnector.sendPointerEvent(remote.x.toInt(), remote.y.toInt(), if (pointerMode) 0 else 1)
                    },
                    onDragEnd = {
                        RdpConnector.sendPointerEvent(lastPointer.x.toInt(), lastPointer.y.toInt(), 0)
                    }
                )
            }
            .pointerInput(pointerMode, scale, pan) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        val remote = toRemote(tapOffset)
                        RdpConnector.sendPointerEvent(remote.x.toInt(), remote.y.toInt(), 1)
                        RdpConnector.sendPointerEvent(remote.x.toInt(), remote.y.toInt(), 0)
                    },
                    onDoubleTap = { tapOffset ->
                        val remote = toRemote(tapOffset)
                        repeat(2) {
                            RdpConnector.sendPointerEvent(remote.x.toInt(), remote.y.toInt(), 1)
                            RdpConnector.sendPointerEvent(remote.x.toInt(), remote.y.toInt(), 0)
                        }
                    },
                    onLongPress = { tapOffset ->
                        val remote = toRemote(tapOffset)
                        RdpConnector.sendPointerEvent(remote.x.toInt(), remote.y.toInt(), 2)
                        RdpConnector.sendPointerEvent(remote.x.toInt(), remote.y.toInt(), 0)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val frame = bitmap
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = "RDP frame",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = pan.x,
                        translationY = pan.y
                    ),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("等待远程桌面画面...", color = Color.White)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (pointerMode) {
                drawCircle(Color.White, radius = 11f, center = Offset(size.width / 2f, size.height / 2f), style = Stroke(width = 2f))
                drawCircle(Color(0xAA60A5FA), radius = 4f, center = Offset(size.width / 2f, size.height / 2f))
            }
        }
    }
}

@Composable
private fun KeyboardDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发送文字") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("文字") })
                Spacer(Modifier.height(8.dp))
                Text("会通过剪贴板通道发送，适合手机输入长文本。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { RdpConnector.sendClipboardText(text); onDismiss() }) { Text("发送") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ClipboardDialog(onDismiss: () -> Unit) = KeyboardDialog(onDismiss)
