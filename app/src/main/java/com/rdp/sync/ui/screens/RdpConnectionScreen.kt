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
import com.rdp.sync.network.CertificateChallenge
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.security.AndroidCertificatePinStorage
import com.rdp.sync.security.CertificateAttemptGuard
import com.rdp.sync.security.CertificateChallengeSnapshot
import com.rdp.sync.security.CertificateEndpoint
import com.rdp.sync.security.CertificateTrustPolicy
import com.rdp.sync.security.CertificateTrustStore
import com.rdp.sync.security.PendingCertificateTrust
import com.rdp.sync.network.RdpInputDispatcher
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.rdp.SessionUiDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
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
    val endpoint = remember(device.id, device.host, device.port, device.rdpServerName) {
        CertificateEndpoint.of(device.id, device.host, device.port, device.rdpServerName)
    }
    val credentialsUnavailable = device.credentialUnavailable
    val attemptNonce = remember(endpoint, attempt, credentialsUnavailable) { UUID.randomUUID().toString() }
    val trustGuard = remember(endpoint, attempt, credentialsUnavailable) { CertificateAttemptGuard() }
    val trustStore = remember(context.applicationContext) { CertificateTrustStore(AndroidCertificatePinStorage(context.applicationContext)) }
    var pendingTrust by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf<PendingCertificateTrust?>(null) }
    var dismissedCertificateGeneration by remember(endpoint, attempt, credentialsUnavailable) { mutableLongStateOf(-1L) }
    var savingTrust by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(false) }
    var trustError by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf("") }
    val uiScope = rememberCoroutineScope()
    var status by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf("正在测量屏幕…") }
    var isConnected by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(false) }
    var connecting by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(true) }
    var viewportSize by remember(device.id) { mutableStateOf(IntSize.Zero) }
    var inputWarning by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf("") }
    var displaySummary by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf("") }
    var reconnectRequired by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf("") }
    var showReconnect by remember { mutableStateOf(false) }
    var desktopView by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf<RdpDesktopView?>(null) }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val latestSettings by rememberUpdatedState(settings)
    val input = remember(endpoint, attempt, credentialsUnavailable) { RdpInputDispatcher(
        touchSender = RdpConnector::sendTouchEvent,
        wheelSender = RdpConnector::sendWheelEvent,
        hwheelSender = RdpConnector::sendHWheelEvent,
        pointerSender = RdpConnector::sendPointerEvent,
        mouseButtonSender = RdpConnector::sendMouseButton,
        keySender = RdpConnector::sendKeyEvent,
        textSender = RdpConnector::sendTextInput
    ) }

    LaunchedEffect(settings) { RdpConnector.configureDisplay(settings) }
    LaunchedEffect(endpoint, attempt, credentialsUnavailable) {
        if (credentialsUnavailable) {
            status = if (device.passwordFormat == 2) "此配置未包含密码。请返回编辑设备，输入密码后再连接。"
                else "保存的凭据无法解密。请返回编辑设备，重新输入密码后再连接。"
            connecting = false
            return@LaunchedEffect
        }
        val connectionEndpoint = endpoint
        if (connectionEndpoint == null) {
            status = "连接地址或服务器名称无效，无法安全验证证书。"
            connecting = false
            return@LaunchedEffect
        }
        while (viewportSize.width <= 0 || viewportSize.height <= 0) delay(50)
        sessionOwnership.withLock {
            try {
                RdpConnector.configureDisplay(latestSettings)
                val size = RdpConnector.resolveDisplay(viewportSize.width, viewportSize.height, device.width, device.height)
                status = "正在连接（${size.width} × ${size.height}）…"
                val expectedFingerprint = withContext(Dispatchers.IO) { trustStore.fingerprint(connectionEndpoint) }
                val connectionUsername = device.username
                val connectionPassword = device.password
                val connectionDomain = device.domain
                suspend fun connect(touch: Boolean, compatibility: Boolean) = withContext(Dispatchers.IO) {
                    RdpConnector.connectDevice(connectionEndpoint.host, connectionEndpoint.port, connectionUsername, connectionPassword,
                        connectionDomain, connectionEndpoint.effectiveServerName, size.width, size.height, touch, compatibility, expectedFingerprint.orEmpty())
                }
                if (!connect(true, false)) {
                    status = withContext(Dispatchers.IO) { RdpConnector.getStatus() }
                    connecting = false
                    // A rejected TLS handshake may carry a certificate challenge.
                    // Keep its generation alive until an explicit decision/retry.
                }
                var compatibilityRetried = false
                var previouslyConnected = false
                while (isActive) {
                    val snapshot = withContext(Dispatchers.IO) { Triple(RdpConnector.getStatus(), RdpConnector.isConnected(), RdpConnector.certificateChallenge()) }
                    status = snapshot.first
                    if (input.overloadCount > 0) inputWarning = "输入队列繁忙，已安全释放按键。请重新操作。"
                    if (input.failedTextSends > 0) inputWarning = "文字发送中断，可能只发送了部分内容，请检查远程文本。"
                    isConnected = snapshot.second
                    val challenge = snapshot.third
                    if (challenge != null) {
                        connecting = false
                        val proposal = CertificateTrustPolicy.propose(connectionEndpoint, attemptNonce, challenge.snapshot(), expectedFingerprint)
                        if (proposal == null) status = "证书响应与当前连接不匹配，已拒绝连接。"
                        else if (pendingTrust == null && challenge.generation != dismissedCertificateGeneration && trustGuard.isActive()) pendingTrust = proposal
                    }
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
                    if (challenge == null && !isConnected && !previouslyConnected && !compatibilityRetried &&
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
    DisposableEffect(input, trustGuard) { onDispose { trustGuard.invalidate(); input.shutdown() } }

    fun saveSettings(value: SessionDisplaySettings) {
        settings = value
        preferences.edit().putString("display_quality", value.quality.name)
            .putString("resolution_mode", value.resolution.name).putBoolean("adaptive_quality", value.adaptive).apply()
    }
    fun reconnect() {
        trustGuard.invalidate()
        pendingTrust = null
        desktopView?.hideKeyboard()
        input.shutdown()
        isConnected = false
        attempt++
    }

    fun exitSession() {
        trustGuard.invalidate()
        pendingTrust = null
        input.shutdown()
        onBack()
    }
    fun dismissCertificate() {
        dismissedCertificateGeneration = pendingTrust?.challenge?.generation ?: -1L
        trustGuard.invalidate()
        pendingTrust = null
        status = "已取消证书信任；未发送登录凭据。"
        connecting = false
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        TopAppBar(
            title = { Column { Text(device.name, maxLines = 1); Text(if (isConnected) "已连接" else status, style = MaterialTheme.typography.labelSmall, maxLines = 1) } },
            navigationIcon = { IconButton(onClick = ::exitSession) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "显示与网络设置") }
                IconButton(onClick = ::exitSession) { Icon(Icons.Default.Stop, "断开") }
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
    pendingTrust?.let { pending ->
        CertificateTrustDialog(pending, savingTrust, trustError, onCancel = ::dismissCertificate, onTrust = {
            if (!savingTrust && pendingTrust == pending && endpoint == pending.endpoint && trustGuard.isActive()) {
                savingTrust = true
                trustError = ""
                uiScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        trustGuard.approveIfActive {
                            trustStore.approve(pending, pending.endpoint, attemptNonce, RdpConnector.certificateChallenge()?.snapshot())
                        }
                    }
                    if (pendingTrust == pending && trustGuard.isActive()) {
                        savingTrust = false
                        if (saved) reconnect()
                        else trustError = "无法保存信任，或证书请求已变化。未重新连接，请取消后重试并重新核对。"
                    }
                }
            }
        })
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
            Text("固定分辨率且关闭自适应时，画质仅改变本地帧率上限，不会暗中降低远程分辨率。", style = MaterialTheme.typography.bodySmall)
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
            Text("安全提示：首次连接或证书变化时，必须通过独立渠道核对指纹后授权。这是手动指纹固定（TOFU），不是 CA 身份验证。同步数据可能含凭据，请仅使用受信任的 HTTPS WebDAV。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDiagnostics) { Text("查看诊断信息") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}


private fun CertificateChallenge.snapshot() = CertificateChallengeSnapshot(
    generation, fingerprint, subject, issuer, host, port, effectiveServerName, reason
)

@Composable
private fun CertificateTrustDialog(pending: PendingCertificateTrust, saving: Boolean, error: String, onCancel: () -> Unit, onTrust: () -> Unit) {
    var independentlyChecked by remember(pending) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (pending.changed) "服务器证书已改变" else "确认服务器证书") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (pending.changed) "证书与原信任指纹不一致。可能是合法更新，也可能是中间人攻击；未经独立核对，不要继续。" else "尚未信任此服务器。连接已在发送登录凭据前停止。请向服务器管理员或通过其他可信渠道核对完整 SHA-256 指纹。",
                    color = if (pending.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text("地址：${pending.endpoint.host}:${pending.endpoint.port}")
                Text("证书服务器名称：${pending.endpoint.effectiveServerName}")
                Text("主体：${pending.challenge.subject.take(512)}", style = MaterialTheme.typography.bodySmall)
                Text("签发者：${pending.challenge.issuer.take(512)}", style = MaterialTheme.typography.bodySmall)
                pending.previousFingerprint?.let { old ->
                    Spacer(Modifier.height(8.dp))
                    Text("原信任 SHA-256", style = MaterialTheme.typography.labelMedium)
                    Text(old, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("新证书 SHA-256", style = MaterialTheme.typography.labelMedium)
                Text(pending.challenge.fingerprint, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { independentlyChecked = !independentlyChecked }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(independentlyChecked, onCheckedChange = { independentlyChecked = it }, enabled = !saving)
                    Text("我已通过独立渠道核对 SHA-256 指纹", style = MaterialTheme.typography.bodySmall)
                }
                Text("仅为此设备配置、地址、端口和服务器名称保存指纹；不会为其他主机自动授权。", style = MaterialTheme.typography.bodySmall)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onTrust, enabled = independentlyChecked && !saving) { Text(if (saving) "正在保存…" else if (pending.changed) "替换指纹并重连" else "保存指纹并重连") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("取消连接") } }
    )
}
