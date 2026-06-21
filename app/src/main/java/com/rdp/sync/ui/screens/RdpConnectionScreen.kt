package com.rdp.sync.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rdp.sync.data.Device
import com.rdp.sync.network.RdpConnector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RdpConnectionScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdpConnectionScreen(
    device: Device,
    onBack: () -> Unit
) {
    var status by remember { mutableStateOf("正在连接...") }
    var isConnected by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(false) }
    var pointerMode by remember { mutableStateOf(true) } // true=mouse, false=touch
    var pointerPos by remember { mutableStateOf(Offset(0f, 0f)) }

    val scope = rememberCoroutineScope()

    // Connect on launch
    LaunchedEffect(device.id) {
        status = "正在连接 ${device.host}:${device.port}..."
        isConnected = false

        val connected = RdpConnector.connectDevice(
            host = device.host,
            port = device.port,
            username = device.username,
            password = device.password,
            domain = device.domain
        )

        if (connected) {
            status = "已连接"
            isConnected = true
        } else {
            status = "连接失败"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        TopAppBar(
            title = { Text("远程桌面 - ${device.name}") },
            navigationIcon = {
                IconButton(onClick = {
                    RdpConnector.disconnect()
                    onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "断开")
                }
            },
            actions = {
                // Status indicator
                Text(status, style = MaterialTheme.typography.bodySmall)
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // RDP canvas area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (isConnected) {
                RdpCanvas(
                    modifier = Modifier.fillMaxSize(),
                    pointerPos = pointerPos,
                    onPointerMove = { x, y ->
                        RdpConnector.sendPointerEvent(x, y, 0)
                    },
                    onPointerDown = { x, y ->
                        RdpConnector.sendPointerEvent(x, y, 1)
                    },
                    onPointerUp = { x, y ->
                        RdpConnector.sendPointerEvent(x, y, 0)
                    }
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(status, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        // Bottom toolbar
        if (showControls) {
            Surface(tonalElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { pointerMode = !pointerMode }) {
                        Icon(
                            if (pointerMode) Icons.Default.Mouse else Icons.Default.Keyboard,
                            contentDescription = if (pointerMode) "键盘模式" else "鼠标模式"
                        )
                    }
                    IconButton(onClick = {
                        // Send Ctrl+Alt+Del
                        RdpConnector.sendKeyEvent(0x1D, 1) // Ctrl
                        RdpConnector.sendKeyEvent(0x38, 1) // Alt
                        RdpConnector.sendKeyEvent(0x53, 1) // Del
                        RdpConnector.sendKeyEvent(0x53, 0) // Del
                        RdpConnector.sendKeyEvent(0x38, 0) // Alt
                        RdpConnector.sendKeyEvent(0x1D, 0) // Ctrl
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Ctrl+Alt+Del")
                    }
                    IconButton(onClick = {
                        RdpConnector.disconnect()
                        onBack()
                    }) {
                        Icon(Icons.Default.Stop, contentDescription = "断开")
                    }
                }
            }
        }
    }
}

@Composable
fun RdpCanvas(
    modifier: Modifier = Modifier,
    pointerPos: Offset,
    onPointerMove: (Int, Int) -> Unit,
    onPointerDown: (Int, Int) -> Unit,
    onPointerUp: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Render loop
    LaunchedEffect(Unit) {
        while (true) {
            val bytes = RdpConnector.getSurfaceBytes()
            if (bytes > 0) {
                // Convert bytes to bitmap (BGR_8888 format)
                val width = RdpConnector.getWidth()
                val height = RdpConnector.getHeight()
                if (width > 0 && height > 0) {
                    // TODO: Actually read the surface bytes from native
                    bitmap = ImageBitmap(width, height)
                }
            }
            delay(33) // ~30 FPS
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val x = offset.x.toInt()
                        val y = offset.y.toInt()
                        onPointerDown(x, y)
                    },
                    onDrag = { change, dragAmount ->
                        val x = change.position.x.toInt()
                        val y = change.position.y.toInt()
                        onPointerMove(x, y)
                    },
                    onDragEnd = {
                        val x = pointerPos.x.toInt()
                        val y = pointerPos.y.toInt()
                        onPointerUp(x, y)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        val x = tapOffset.x.toInt()
                        val y = tapOffset.y.toInt()
                        onPointerDown(x, y)
                        // Simple tap - release immediately
                        onPointerUp(x, y)
                    }
                )
            }
    ) {
        bitmap?.let {
            drawImage(it, topLeft = Offset(0f, 0f))
        }
    }
}
