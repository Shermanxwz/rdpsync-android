package com.rdp.sync.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rdp.sync.network.RdpConnector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RdpConnectionScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdpConnectionScreen(
    onBack: () -> Unit
) {
    var status by remember { mutableStateOf("正在连接...") }
    var isConnecting by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()

    // Connect on launch
    LaunchedEffect(Unit) {
        status = "正在连接远程桌面..."
        isConnecting = true
        connected = false

        // Simulate connection (replace with actual device connection)
        delay(1500)

        try {
            // Try to connect to a real RDP device
            val success = RdpConnector.connectDevice(
                host = "192.168.1.100",
                port = 3389,
                username = "Administrator",
                password = "password",
                domain = ""
            )
            if (success) {
                status = "已连接"
                connected = true
            } else {
                status = "连接失败，请检查设备信息"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            status = "连接失败: ${e.message}"
        }

        isConnecting = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("远程桌面连接") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { /* Share */ }) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = { /* Keyboard */ }) {
                        Icon(Icons.Default.Keyboard, contentDescription = "键盘")
                    }
                    IconButton(onClick = { /* Volume */ }) {
                        Icon(Icons.Default.VolumeOff, contentDescription = "音量")
                    }
                    IconButton(onClick = { /* Power */ }) {
                        Icon(Icons.Default.Power, contentDescription = "关机")
                    }
                }
            )
        },
        bottomBar = {
            if (showControls && !isConnecting) {
                Surface(tonalElevation = 8.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { /* Left click */ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "左键")
                        }
                        IconButton(onClick = { /* Middle click */ }) {
                            Icon(Icons.Default.Wifi, contentDescription = "中键")
                        }
                        IconButton(onClick = { /* Right click */ }) {
                            Icon(Icons.Default.Stop, contentDescription = "右键")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            when {
                isConnecting -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(status, color = Color.White)
                    }
                }

                connected -> {
                    // Remote desktop rendering area
                    RdpSurface(modifier = Modifier.fillMaxSize())

                    // Status overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color.Black.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = "已连接 | 1280x720",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "连接失败",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onBack) {
                            Text("返回")
                        }
                    }
                }
            }

            // Toggle controls
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .clickable { showControls = !showControls }
                    .size(32.dp)
            ) {
                Icon(
                    if (showControls) Icons.Default.ArrowBack else Icons.Default.Keyboard,
                    contentDescription = "切换控制栏",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun RdpSurface(modifier: Modifier = Modifier) {
    val bitmap = remember { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        Log.d(TAG, "Tap at $offset")
                    },
                    onLongPress = { offset ->
                        Log.d(TAG, "Long press at $offset")
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        Log.d(TAG, "Drag ended")
                    }
                ) { _, _ ->
                    Log.d(TAG, "Drag")
                }
            }
    ) {
        // Draw the bitmap
        val width = size.width.toInt()
        val height = size.height.toInt()
        translate(width / 2f, height / 2f) {
            drawImage(bitmap.asImageBitmap(), Offset(-width / 2f, -height / 2f))
        }
    }
}
