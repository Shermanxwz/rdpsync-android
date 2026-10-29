package com.rdp.sync.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rdp.sync.data.Device
import com.rdp.sync.display.DisplayQuality
import com.rdp.sync.display.ResolutionMode
import com.rdp.sync.display.SessionDisplaySettings
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.network.RdpInputDispatcher
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.rdp.SessionUiDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Prevent a canceled screen's asynchronous teardown from disconnecting its successor.
private val sessionOwnership = Mutex()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdpConnectionScreen(device: Device, onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("session_preferences", Context.MODE_PRIVATE) }
    var settings by remember { mutableStateOf(SessionUiDefaults.displaySettings(
        preferences.getString("display_quality", null), preferences.getString("resolution_mode", null),
        preferences.getBoolean("adaptive_quality", false)
    )) }
    // A temporary trackpad toggle never overrides touch-first on the next session.
    var pointerMode by remember(device.id) { mutableStateOf(SessionUiDefaults.POINTER_MODE) }
    var attempt by remember(device.id) { mutableIntStateOf(0) }
    var status by remember(device.id, attempt) { mutableStateOf("正在测量屏幕…") }
    var isConnected by remember(device.id, attempt) { mutableStateOf(false) }
    var connecting by remember(device.id, attempt) { mutableStateOf(true) }
    var viewportSize by remember(device.id) { mutableStateOf(IntSize.Zero) }
    var inputWarning by remember(device.id, attempt) { mutableStateOf("") }
    var displaySummary by remember(device.id, attempt) { mutableStateOf("") }
    var reconnectRequired by remember(device.id, attempt) { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf("") }
    var showReconnect by remember { mutableStateOf(false) }
    var desktopView by remember(device.id, attempt) { mutableStateOf<RdpDesktopView?>(null) }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val latestSettings by rememberUpdatedState(settings)
    val input = remember(device.id, attempt) { RdpInputDispatcher(
        touchSender = RdpConnector::sendTouchEvent,
        wheelSender = RdpConnector::sendWheelEvent,
        hwheelSender = RdpConnector::sendHWheelEvent,
        pointerSender = RdpConnector::sendPointerEvent,
        mouseButtonSender = RdpConnector::sendMouseButton,
        keySender = RdpConnector::sendKeyEvent,
        textSender = RdpConnector::sendTextInput
    ) }

    LaunchedEffect(settings) { RdpConnector.configureDisplay(settings) }
    LaunchedEffect(device.id, attempt) {
        while (viewportSize.width <= 0 || viewportSize.height <= 0) delay(50)
        sessionOwnership.withLock {
            try {
                RdpConnector.configureDisplay(latestSettings)
                val size = RdpConnector.resolveDisplay(viewportSize.width, viewportSize.height, device.width, device.height)
                status = "正在连接（${size.width} × ${size.height}）…"
                suspend fun connect(touch: Boolean, compatibility: Boolean) = withContext(Dispatchers.IO) {
                    RdpConnector.connectDevice(device.host, device.port, device.username, device.password,
                        device.domain, device.rdpServerName, size.width, size.height, touch, compatibility)
                }
                if (!connect(true, false)) {
                    status = withContext(Dispatchers.IO) { RdpConnector.getStatus() }
                    connecting = false
                    return@withLock
                }
                var compatibilityRetried = false
                var previouslyConnected = false
                while (isActive) {
                    val snapshot = withContext(Dispatchers.IO) { RdpConnector.getStatus() to RdpConnector.isConnected() }
                    status = snapshot.first
                    if (input.overloadCount > 0) inputWarning = "输入队列繁忙，已安全释放按键。请重新操作。"
                    if (input.failedTextSends > 0) inputWarning = "文字发送中断，可能只发送了部分内容，请检查远程文本。"
                    isConnected = snapshot.second
                    if (isConnected) {
                        previouslyConnected = true
                        connecting = false
                        val display = withContext(Dispatchers.IO) {
                            RdpConnector.updateDisplay(viewportSize.width, viewportSize.height, device.width, device.height)
                        }
                        displaySummary = display.summary
                        reconnectRequired = display.reconnectRequired
                    } else if (previouslyConnected || status.contains("失败")) connecting = false
                    // Retain the existing, narrowly scoped RDPEI activation compatibility retry.
                    if (!isConnected && !previouslyConnected && !compatibilityRetried &&
                        (status.contains("0x0002001c", ignoreCase = true) || status.contains("Timeout waiting for activation", ignoreCase = true))) {
                        compatibilityRetried = true
                        status = "触摸通道协商失败，尝试兼容连接…"
                        withContext(Dispatchers.IO) { RdpConnector.disconnect() }
                        connect(false, true)
                    }
                    delay(1000)
                }
            } finally {
                // Cancellation of the Compose scope must not cancel key releases/disconnect.
                withContext(NonCancellable + Dispatchers.IO) {
                    input.shutdownAndJoin()
                    RdpConnector.disconnect()
                }
            }
        }
    }
    DisposableEffect(input) { onDispose { input.shutdown() } }

    fun saveSettings(value: SessionDisplaySettings) {
        settings = value
        preferences.edit().putString("display_quality", value.quality.name)
            .putString("resolution_mode", value.resolution.name).putBoolean("adaptive_quality", value.adaptive).apply()
    }
    fun reconnect() {
        desktopView?.hideKeyboard()
        input.shutdown()
        isConnected = false
        attempt++
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        TopAppBar(
            title = { Column { Text(device.name, maxLines = 1); Text(if (isConnected) "已连接" else status, style = MaterialTheme.typography.labelSmall, maxLines = 1) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "显示与网络设置") }
                IconButton(onClick = onBack) { Icon(Icons.Default.Stop, "断开") }
            }
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF020617)).onSizeChanged {
            // The IME is a local occlusion, not a request to rearrange Windows.
            if (!imeVisible && it.width > 0 && it.height > 0) viewportSize = it
        }, contentAlignment = Alignment.Center) {
            if (isConnected) {
                AndroidView(
                    factory = { RdpDesktopView(it, input).also { view -> desktopView = view; view.setPointerMode(pointerMode) } },
                    update = { it.setPointerMode(pointerMode) },
                    modifier = Modifier.fillMaxSize()
                )
            } else Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (connecting) CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(status, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showReconnect = true }) { Text("重新连接") }
                TextButton(onClick = { diagnostics = RdpConnector.getDiag().ifBlank { "暂无诊断信息" }; showDiagnostics = true }) { Text("查看诊断") }
            }
        }
        Surface(tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { pointerMode = !pointerMode }, enabled = isConnected) {
                        Icon(if (pointerMode) Icons.Default.Mouse else Icons.Default.TouchApp, if (pointerMode) "切换到直接触摸" else "切换到触控板")
                    }
                    IconButton(onClick = { if (imeVisible) desktopView?.hideKeyboard() else desktopView?.showKeyboard() }, enabled = isConnected) { Icon(Icons.Default.Keyboard, "显示或隐藏键盘") }
                    IconButton(onClick = { showPaste = true }, enabled = isConnected) { Icon(Icons.Default.ContentPaste, "发送文字") }
                    TextButton(onClick = { desktopView?.resetZoom() }, enabled = isConnected) { Text("复位视图") }
                    TextButton(onClick = { showSettings = true }) { Text("画质") }
                }
                Text(if (pointerMode) "触控板：滑动移指针，轻点单击；长按右键，长按后拖动。" else "直接触摸；双指缩放/平移。兼容模式支持长按右键、长按后拖动。", style = MaterialTheme.typography.labelSmall, minLines = 2, maxLines = 2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                // Always reserve exactly two text lines. Status wrapping must not
                // change our viewport and trigger a resize/ack/resize feedback loop.
                Text(
                    text = inputWarning.ifBlank { if (reconnectRequired) "$displaySummary · 点此重连" else displaySummary.ifBlank { "等待显示协商…" } },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (inputWarning.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    minLines = 2, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = reconnectRequired) { showReconnect = true }
                )
                if (imeVisible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { input.enqueueKeyStroke(0x01) }) { Text("Esc") }
                    TextButton(onClick = { input.enqueueKeyStroke(0x0F) }) { Text("Tab") }
                    TextButton(onClick = { input.enqueueShortcut(0x1D, 0x2E) }) { Text("Ctrl+C") }
                    TextButton(onClick = { input.enqueueShortcut(0x1D, 0x2F) }) { Text("Ctrl+V") }
                    TextButton(onClick = { input.enqueueShortcut(0x1D, 0x38, 0xE053) }) { Text("Ctrl+Alt+Del") }
                }
            }
        }
    }
    if (showSettings) DisplaySettingsDialog(settings, device, onChange = ::saveSettings, onDismiss = { showSettings = false }, onDiagnostics = {
        diagnostics = RdpConnector.getDiag().ifBlank { "暂无诊断信息" }; showDiagnostics = true
    })
    if (showReconnect) AlertDialog(onDismissRequest = { showReconnect = false }, title = { Text("重新连接远程桌面？") }, text = { Text("这会短暂断开当前连接，并用所选分辨率重新连接。不会替你保存远程程序中的工作。") },
        confirmButton = { TextButton(onClick = { showReconnect = false; reconnect() }) { Text("重新连接") } }, dismissButton = { TextButton(onClick = { showReconnect = false }) { Text("取消") } })
    if (showPaste) {
        var text by remember { mutableStateOf(clipboard.getText()?.text.orEmpty()) }
        AlertDialog(onDismissRequest = { showPaste = false }, title = { Text("发送文字") }, text = {
            Column { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("文字") }); Text("文字将作为键盘输入发送；不覆盖远程剪贴板。", style = MaterialTheme.typography.bodySmall) }
        }, confirmButton = { TextButton(onClick = { input.enqueueText(text); showPaste = false }, enabled = isConnected) { Text("发送") } }, dismissButton = { TextButton(onClick = { showPaste = false }) { Text("取消") } })
    }
    if (showDiagnostics) AlertDialog(onDismissRequest = { showDiagnostics = false }, title = { Text("诊断信息") }, text = { Text(diagnostics, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(diagnostics)); showDiagnostics = false }) { Text("复制并关闭") } }, dismissButton = { TextButton(onClick = { showDiagnostics = false }) { Text("关闭") } })
}

@Composable
private fun DisplaySettingsDialog(settings: SessionDisplaySettings, device: Device, onChange: (SessionDisplaySettings) -> Unit, onDismiss: () -> Unit, onDiagnostics: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("显示与网络") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("远程分辨率", style = MaterialTheme.typography.titleSmall)
            ResolutionMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.resolution == mode, onClick = { onChange(settings.copy(resolution = mode)) })
                    Text(if (mode == ResolutionMode.PROFILE) "设备配置（${device.width} × ${device.height}）" else mode.title)
                }
            }
            Text("画质上限", style = MaterialTheme.typography.titleSmall)
            DisplayQuality.entries.forEach { quality ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(settings.quality == quality, { onChange(settings.copy(quality = quality)) }); Text(quality.title) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.adaptive, { onChange(settings.copy(adaptive = it)) })
                Text("自适应网络与渲染负载", modifier = Modifier.padding(start = 8.dp))
            }
            Text("自适应基于实测延迟、带宽和渲染耗时，持续变差才降级，恢复后缓慢提高。缺少测量时不会猜测网速。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("双指缩放/平移只改变本地视图；分辨率设置通过服务器显示控制通道协商，不拉伸画面。旧服务器需手动重连。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("安全提示：当前项目仍跳过服务器证书验证，保存/同步的凭据未加密；仅连接你信任的测试主机。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDiagnostics) { Text("查看诊断信息") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
