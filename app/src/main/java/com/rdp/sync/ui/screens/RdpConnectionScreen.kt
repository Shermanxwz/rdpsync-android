package com.rdp.sync.ui.screens

import com.rdp.sync.R
import com.rdp.sync.ui.components.UiMessages
import com.rdp.sync.ui.sharedspace.SharedSpaceRoute
import com.rdp.sync.ui.sharedspace.SharedDriveConsentDialog
import com.rdp.sync.ui.sharedspace.SharedDriveStatusDialog
import com.rdp.sync.network.SharedDriveAttemptGrant
import com.rdp.sync.network.SharedDriveMode
import com.rdp.sync.network.SharedDriveState
import com.rdp.sync.network.SharedDriveStatus
import com.rdp.sync.network.permitsAutomaticActivationRetry
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rdp.sync.ui.components.ProductNotice
import com.rdp.sync.ui.components.SectionHeading
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
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
import com.rdp.sync.ui.rdp.rememberSessionOrientation
import com.rdp.sync.ui.rdp.SessionSystemBars
import com.rdp.sync.ui.rdp.SessionUiDefaults
import com.rdp.sync.ui.rdp.SessionViewportLayout
import com.rdp.sync.transfer.TransferController
import com.rdp.sync.ui.transfer.FileTransferDirection
import com.rdp.sync.ui.transfer.SessionFileTransferUi
import kotlinx.coroutines.CancellationException
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
fun RdpConnectionScreen(device: Device, initialSharedDriveMode: SharedDriveMode = SharedDriveMode.OFF, onBack: () -> Unit) {
    val context = LocalContext.current
    // Configuration orientation is stable while the IME animates; viewport height is not.
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val wideLandscapeToolbar = configuration.screenWidthDp >= 520
    var fullscreen by remember(device.id) { mutableStateOf(SessionUiDefaults.FULLSCREEN) }
    SessionSystemBars(fullscreen)
    val rotateScreen = rememberSessionOrientation(device.id)
    var expandedLandscapeTools by remember(device.id) { mutableStateOf(false) }
    val compactLandscape = landscape && !expandedLandscapeTools
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
    // Static CLIPRDR is opt-in for this route only. Protocol activation grants no
    // access to phone files; every picker, publication and receive is still explicit.
    var fileTransferEnabled by remember(endpoint) { mutableStateOf(false) }
    // Ephemeral consent only: never persisted in the profile or restored from a route argument.
    var sharedDriveMode by remember(endpoint) { mutableStateOf(initialSharedDriveMode) }
    var showSharedSpace by remember(endpoint) { mutableStateOf(false) }
    var showDriveConsent by remember(endpoint) { mutableStateOf(false) }
    var showDriveStatus by remember(endpoint) { mutableStateOf(false) }
    var sharedDriveState by remember(endpoint, attempt) { mutableStateOf(SharedDriveState(
        mode = sharedDriveMode, status = if (sharedDriveMode == SharedDriveMode.OFF) SharedDriveStatus.OFF else SharedDriveStatus.REQUESTED)) }
    var driveActionError by remember(endpoint, attempt) { mutableStateOf("") }
    var driveActionBusy by remember(endpoint, attempt) { mutableStateOf(false) }
    var showFileEnable by remember(endpoint) { mutableStateOf(false) }
    var showFileTransfer by remember(endpoint) { mutableStateOf(false) }
    var fileDirection by remember(endpoint) { mutableStateOf(FileTransferDirection.SEND) }
    val attemptDriveGrant = remember(endpoint, attempt) { SharedDriveAttemptGrant(sharedDriveMode) }
    val credentialsUnavailable = device.credentialUnavailable
    val attemptNonce = remember(endpoint, attempt, credentialsUnavailable) { UUID.randomUUID().toString() }
    val trustGuard = remember(endpoint, attempt, credentialsUnavailable) { CertificateAttemptGuard() }
    val trustStore = remember(context.applicationContext) { CertificateTrustStore(AndroidCertificatePinStorage(context.applicationContext)) }
    var pendingTrust by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf<PendingCertificateTrust?>(null) }
    var dismissedCertificateGeneration by remember(endpoint, attempt, credentialsUnavailable) { mutableLongStateOf(-1L) }
    var savingTrust by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(false) }
    var trustError by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf("") }
    val uiScope = rememberCoroutineScope()
    var status by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(context.getString(R.string.measuring_screen)) }
    var isConnected by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(false) }
    var connecting by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(true) }
    var viewportSize by remember(device.id) { mutableStateOf(IntSize.Zero) }
    var inputWarning by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf("") }
    var displaySummary by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf("") }
    var adaptiveReason by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(context.getString(R.string.waiting_measurement)) }
    var reconnectRequired by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf("") }
    var showReconnect by remember { mutableStateOf(false) }
    var desktopView by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf<RdpDesktopView?>(null) }
    var transferController by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf<TransferController?>(null) }
    var transferSetupError by remember(endpoint, attempt, credentialsUnavailable) { mutableStateOf("") }
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
        val connectionFileTransferEnabled = fileTransferEnabled
        val connectionSharedDriveMode = sharedDriveMode
        if (credentialsUnavailable) {
            status = if (device.passwordFormat == 2) context.getString(R.string.connect_missing_password)
                else context.getString(R.string.connect_locked_password)
            connecting = false
            return@LaunchedEffect
        }
        val connectionEndpoint = endpoint
        if (connectionEndpoint == null) {
            status = context.getString(R.string.connect_invalid_endpoint)
            connecting = false
            return@LaunchedEffect
        }
        while (viewportSize.width <= 0 || viewportSize.height <= 0) delay(50)
        sessionOwnership.withLock {
            try {
                RdpConnector.configureDisplay(latestSettings)
                val size = RdpConnector.resolveDisplay(viewportSize.width, viewportSize.height, device.width, device.height)
                status = context.getString(R.string.connecting_size, size.width, size.height)
                val expectedFingerprint = withContext(Dispatchers.IO) { trustStore.fingerprint(connectionEndpoint) }
                val connectionUsername = device.username
                val connectionPassword = device.password
                val connectionDomain = device.domain
                suspend fun connect(touch: Boolean, compatibility: Boolean) = withContext(Dispatchers.IO) {
                    attemptDriveGrant.connect { grantedMode ->
                        val connected = RdpConnector.connectDevice(connectionEndpoint.host, connectionEndpoint.port, connectionUsername, connectionPassword,
                            connectionDomain, connectionEndpoint.effectiveServerName, size.width, size.height, touch, compatibility, expectedFingerprint.orEmpty(), fileTransferEnabled = connectionFileTransferEnabled, sharedDriveMode = grantedMode)
                        connected to RdpConnector.sessionGeneration()
                    }
                }
                if (!connect(true, false)) {
                    status = withContext(Dispatchers.IO) { RdpConnector.getStatus() }
                    connecting = false
                    // A rejected TLS handshake may carry a certificate challenge.
                    // Keep its generation alive until an explicit decision/retry.
                }
                var compatibilityRetried = false
                var previouslyConnected = false
                var transferSetupTried = false
                while (isActive) {
                    val snapshot = withContext(Dispatchers.IO) { Triple(RdpConnector.getStatus(), RdpConnector.isConnected(), RdpConnector.certificateChallenge()) }
                    status = snapshot.first
                    if (input.overloadCount > 0) inputWarning = context.getString(R.string.input_busy)
                    if (input.failedTextSends > 0) inputWarning = context.getString(R.string.text_interrupted)
                    isConnected = snapshot.second
                    sharedDriveState = withContext(Dispatchers.IO) { RdpConnector.sharedDriveState() }
                    val challenge = snapshot.third
                    if (challenge != null) {
                        connecting = false
                        val proposal = CertificateTrustPolicy.propose(connectionEndpoint, attemptNonce, challenge.snapshot(), expectedFingerprint)
                        if (proposal == null) status = context.getString(R.string.certificate_mismatch)
                        else if (pendingTrust == null && challenge.generation != dismissedCertificateGeneration && trustGuard.isActive()) pendingTrust = proposal
                    }
                    if (isConnected) {
                        previouslyConnected = true
                        connecting = false
                        val display = withContext(Dispatchers.IO) {
                            RdpConnector.updateDisplay(viewportSize.width, viewportSize.height, device.width, device.height)
                        }
                        displaySummary = display.summary
                        adaptiveReason = display.adaptiveReason
                        reconnectRequired = display.reconnectRequired
                        if (connectionFileTransferEnabled && !transferSetupTried) {
                            transferSetupTried = true
                            try {
                                // Assign inside IO so cancellation on returning to the UI
                                // cannot orphan a private staging root before finally closes it.
                                withContext(Dispatchers.IO) {
                                    transferController = TransferController.create(context.applicationContext,
                                        RdpConnector.sessionGeneration(), attemptNonce)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { transferSetupError = context.getString(R.string.transfer_init_error) }
                        }
                        try { transferController?.refreshCapability() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            transferController?.close()
                            transferController = null
                            transferSetupError = context.getString(R.string.transfer_check_error)
                        }
                    } else {
                        transferController?.close()
                        transferController = null
                        if (previouslyConnected || status.contains("失败")) connecting = false
                    }
                    // Retain the existing, narrowly scoped RDPEI activation compatibility retry.
                    if (challenge == null && !isConnected && !previouslyConnected && !compatibilityRetried &&
                        (status.contains("0x0002001c", ignoreCase = true) || status.contains("Timeout waiting for activation", ignoreCase = true))) {
                        if (permitsAutomaticActivationRetry(connectionSharedDriveMode)) {
                            compatibilityRetried = true
                            status = context.getString(R.string.touch_compat_retry)
                            withContext(Dispatchers.IO) { RdpConnector.disconnect() }
                            connect(false, true)
                        } else {
                            // Reusing a captured grant here could resurrect drive access after a
                            // concurrent Stop sharing. No automatic new drive-enabled generation.
                            connecting = false
                            status = context.getString(R.string.drive_compatibility_manual)
                        }
                    }
                    delay(1000)
                }
            } finally {
                // Cancellation of the Compose scope must not cancel key releases/disconnect.
                attemptDriveGrant.requestRevocation()
                withContext(NonCancellable + Dispatchers.IO) {
                    attemptDriveGrant.revoke(RdpConnector::revokeSharedDrive)
                    transferController?.close()
                    transferController = null
                    input.shutdownAndJoin()
                    RdpConnector.disconnect()
                }
            }
        }
    }
    DisposableEffect(input, trustGuard, attemptDriveGrant) { onDispose { attemptDriveGrant.requestRevocation(); trustGuard.invalidate(); input.shutdown() } }

    fun saveSettings(value: SessionDisplaySettings) {
        settings = value
        preferences.edit().putString("display_quality", value.quality.name)
            .putString("resolution_mode", value.resolution.name).putBoolean("adaptive_quality", value.adaptive).apply()
    }
    fun reconnect() {
        transferController?.close()
        transferController = null
        trustGuard.invalidate()
        pendingTrust = null
        desktopView?.hideKeyboard()
        input.shutdown()
        isConnected = false
        attempt++
    }

    fun exitSession() {
        attemptDriveGrant.requestRevocation()
        transferController?.close()
        transferController = null
        trustGuard.invalidate()
        pendingTrust = null
        input.shutdown()
        onBack()
    }
    fun openFiles(direction: FileTransferDirection) {
        desktopView?.hideKeyboard()
        fileDirection = direction
        if (fileTransferEnabled) showFileTransfer = true else showFileEnable = true
    }
    fun openSharedSpace() {
        desktopView?.hideKeyboard()
        input.releasePressedInputs()
        showSharedSpace = true
    }
    fun openSharedDrive() {
        desktopView?.hideKeyboard()
        input.releasePressedInputs()
        driveActionError = ""
        showDriveStatus = true
    }
    fun revokeSharedDrive() {
        if (driveActionBusy) return
        driveActionBusy = true
        driveActionError = ""
        attemptDriveGrant.requestRevocation() // Nonblocking even while connect is publishing its generation.
        sharedDriveMode = SharedDriveMode.OFF
        uiScope.launch {
            val stopped = withContext(Dispatchers.IO) { attemptDriveGrant.revoke(RdpConnector::revokeSharedDrive) }
            if (trustGuard.isActive()) {
                sharedDriveState = withContext(Dispatchers.IO) { RdpConnector.sharedDriveState() }
                driveActionBusy = false
                if (!stopped) driveActionError = context.getString(R.string.drive_stop_failed)
            }
        }
    }
    fun dismissCertificate() {
        attemptDriveGrant.requestRevocation()
        sharedDriveMode = SharedDriveMode.OFF
        dismissedCertificateGeneration = pendingTrust?.challenge?.generation ?: -1L
        trustGuard.invalidate()
        pendingTrust = null
        status = context.getString(R.string.trust_cancelled)
        connecting = false
    }

    SessionViewportLayout(
        fullscreen = fullscreen,
        onLogicalViewport = {
            if (it.width > 0 && it.height > 0) {
                viewportSize = it
                desktopView?.setLogicalViewport(it.width, it.height)
            }
        },
        header = {
            if (!compactLandscape) TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Column { Text(device.name, maxLines = 1); Text(if (isConnected) context.getString(R.string.connected) else UiMessages.text(context, status), style = MaterialTheme.typography.labelSmall, maxLines = 1) } },
                navigationIcon = { IconButton(onClick = ::exitSession) { Icon(Icons.AutoMirrored.Filled.ArrowBack, context.getString(R.string.back)) } },
                actions = {
                    IconButton(onClick = { fullscreen = true }) { Icon(Icons.Default.Fullscreen, context.getString(R.string.fullscreen)) }
                    if (landscape) IconButton(onClick = { expandedLandscapeTools = false }) { Icon(Icons.Default.Fullscreen, context.getString(R.string.collapse_tools)) }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, context.getString(R.string.display_settings)) }
                    IconButton(onClick = ::exitSession) { Icon(Icons.Default.Stop, context.getString(R.string.disconnect)) }
                }
            )
        },
        desktop = {
            Box(Modifier.fillMaxSize().background(Color(0xFF020617)), contentAlignment = Alignment.Center) {
                if (isConnected) {
                    AndroidView(
                        factory = { RdpDesktopView(it, input).also { view ->
                            desktopView = view
                            view.setPointerMode(pointerMode)
                            view.setLogicalViewport(viewportSize.width, viewportSize.height)
                        } },
                        update = { it.setPointerMode(pointerMode); it.setLogicalViewport(viewportSize.width, viewportSize.height) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (connecting) CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(UiMessages.text(context, status), color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showReconnect = true }) { Text(context.getString(R.string.reconnect)) }
                    TextButton(onClick = { diagnostics = RdpConnector.getDiag().ifBlank { context.getString(R.string.no_diagnostics) }; showDiagnostics = true }) { Text(context.getString(R.string.view_diagnostics)) }
                }
            }
        },
        toolbar = {
            Surface(tonalElevation = 6.dp) {
                if (compactLandscape) Column(Modifier.fillMaxWidth()) {
                    // Reserve only 56dp for tools, not a header plus static hints.
                    // Telemetry text never changes this geometry.
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::exitSession) { Icon(Icons.Default.Stop, context.getString(R.string.disconnect)) }
                        if (wideLandscapeToolbar) Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            Text(when {
                                inputWarning.isNotBlank() -> context.getString(R.string.input_warning)
                                reconnectRequired -> context.getString(R.string.display_reconnect_required)
                                isConnected -> context.getString(R.string.connected)
                                else -> UiMessages.text(context, status)
                            }, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = if (inputWarning.isNotBlank()) MaterialTheme.colorScheme.error else LocalContentColor.current)
                        }
                        IconButton(onClick = { pointerMode = !pointerMode }, enabled = isConnected) {
                            Icon(if (pointerMode) Icons.Default.Mouse else Icons.Default.TouchApp, if (pointerMode) context.getString(R.string.direct_touch) else context.getString(R.string.trackpad))
                        }
                        IconButton(onClick = { if (imeVisible) desktopView?.hideKeyboard() else desktopView?.showKeyboard() }, enabled = isConnected) { Icon(Icons.Default.Keyboard, context.getString(R.string.toggle_keyboard)) }
                        if (wideLandscapeToolbar) {
                            IconButton(onClick = { showPaste = true }, enabled = isConnected) { Icon(Icons.Default.ContentPaste, context.getString(R.string.send_text)) }
                            IconButton(onClick = { desktopView?.resetZoom() }, enabled = isConnected) { Icon(Icons.Default.ZoomOutMap, context.getString(R.string.reset_view)) }
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, context.getString(R.string.display_settings)) }
                        IconButton(onClick = { fullscreen = true }) { Icon(Icons.Default.Fullscreen, context.getString(R.string.fullscreen)) }
                        IconButton(onClick = { expandedLandscapeTools = true }) { Icon(Icons.Default.FullscreenExit, context.getString(R.string.expand_tools)) }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { pointerMode = !pointerMode }, enabled = isConnected) {
                                Icon(if (pointerMode) Icons.Default.Mouse else Icons.Default.TouchApp, if (pointerMode) context.getString(R.string.direct_touch) else context.getString(R.string.trackpad))
                            }
                            IconButton(onClick = { if (imeVisible) desktopView?.hideKeyboard() else desktopView?.showKeyboard() }, enabled = isConnected) { Icon(Icons.Default.Keyboard, context.getString(R.string.toggle_keyboard)) }
                            IconButton(onClick = { showPaste = true }, enabled = isConnected) { Icon(Icons.Default.ContentPaste, context.getString(R.string.send_text)) }
                            TextButton(onClick = { desktopView?.resetZoom() }, enabled = isConnected) { Text(context.getString(R.string.reset_view)) }
                            TextButton(onClick = { showSettings = true }) { Text(context.getString(R.string.quality_short)) }
                        }
                        Text(if (pointerMode) context.getString(R.string.trackpad_hint) else context.getString(R.string.touch_hint), style = MaterialTheme.typography.labelSmall, minLines = 2, maxLines = 2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        // Always reserve exactly two text lines. Status wrapping must not
                        // change our viewport and trigger a resize/ack/resize feedback loop.
                        Text(
                            text = UiMessages.text(context, inputWarning).ifBlank { if (reconnectRequired) context.getString(R.string.display_reconnect_summary, UiMessages.text(context, displaySummary)) else UiMessages.text(context, displaySummary).ifBlank { context.getString(R.string.waiting_display) } },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (inputWarning.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            minLines = 2, maxLines = 2,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = reconnectRequired) { showReconnect = true }
                        )
                    }
                }
            }
        },
        imeControls = {
            SessionFloatingControls(input, fullscreen, imeVisible, pointerMode, isConnected,
                onKeyboard = { if (imeVisible) desktopView?.hideKeyboard() else desktopView?.showKeyboard() },
                onPointerMode = { pointerMode = !pointerMode },
                onChromeMode = { fullscreen = !fullscreen }, onSettings = { showSettings = true },
                onPaste = { showPaste = true }, onResetZoom = { desktopView?.resetZoom() },
                onSendFiles = { openFiles(FileTransferDirection.SEND) },
                onReceiveFiles = { openFiles(FileTransferDirection.RECEIVE) }, onExit = ::exitSession,
                onSharedSpace = ::openSharedSpace, onSharedDrive = ::openSharedDrive, onRotateScreen = rotateScreen)
        }
    )
    if (showSharedSpace) Dialog(onDismissRequest = { showSharedSpace = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Keep the underlying desktop/IME owner composed. This is not navigation away from the session.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SharedSpaceRoute(onBack = { showSharedSpace = false })
        }
    }
    if (showDriveStatus) SharedDriveStatusDialog(sharedDriveState,
        onOpenSpace = { showDriveStatus = false; openSharedSpace() },
        onStopSharing = ::revokeSharedDrive,
        onReconnect = { showDriveStatus = false; showDriveConsent = true },
        onDismiss = { showDriveStatus = false }, actionError = driveActionError, busy = driveActionBusy,
        canStop = sharedDriveMode != SharedDriveMode.OFF || sharedDriveState.mayRevoke)
    if (showDriveConsent) SharedDriveConsentDialog(
        serverLabel = "${device.name} · ${device.host}:${device.port}", reconnectRequired = true,
        onConfirm = { readOnly ->
            showDriveConsent = false
            sharedDriveMode = if (readOnly) SharedDriveMode.READ_ONLY else SharedDriveMode.READ_WRITE
            reconnect()
        }, onDismiss = { showDriveConsent = false })
    transferController?.let { controller ->
        SessionFileTransferUi(controller, showFileTransfer, fileDirection,
            onReconnect = { showFileTransfer = false; showReconnect = true },
            onDismiss = { showFileTransfer = false })
    }
    if (showFileTransfer && isConnected && transferController == null && transferSetupError.isNotBlank()) {
        AlertDialog(onDismissRequest = { showFileTransfer = false }, title = { Text(context.getString(R.string.transfer_unavailable)) },
            text = { Text(transferSetupError) }, confirmButton = { TextButton(onClick = { showFileTransfer = false }) { Text(context.getString(R.string.close)) } })
    }
    if (showFileEnable) AlertDialog(onDismissRequest = { showFileEnable = false },
        title = { Text(context.getString(R.string.transfer_enable_title)) },
        text = { Text(context.getString(R.string.transfer_enable_body)) },
        confirmButton = { TextButton(onClick = {
            showFileEnable = false
            fileTransferEnabled = true
            showFileTransfer = true
            reconnect()
        }) { Text(context.getString(R.string.enable_reconnect)) } },
        dismissButton = { TextButton(onClick = { showFileEnable = false }) { Text(context.getString(R.string.cancel)) } })
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
                        else trustError = context.getString(R.string.trust_save_error)
                    }
                }
            }
        })
    }
    if (showSettings) DisplaySettingsDialog(settings, device,
        sessionInfo = UiMessages.text(context, displaySummary.ifBlank { status }), adaptiveInfo = UiMessages.text(context, adaptiveReason),
        inputWarning = UiMessages.text(context, inputWarning), reconnectRequired = reconnectRequired,
        fileTransferConnected = isConnected,
        onSendFiles = { showSettings = false; openFiles(FileTransferDirection.SEND) },
        onReceiveFiles = { showSettings = false; openFiles(FileTransferDirection.RECEIVE) },
        onReconnect = { showSettings = false; showReconnect = true },
        onSharedSpace = { showSettings = false; openSharedSpace() },
        onSharedDrive = { showSettings = false; openSharedDrive() },
        onChange = ::saveSettings, onDismiss = { showSettings = false }, onDiagnostics = {
        diagnostics = RdpConnector.getDiag().ifBlank { context.getString(R.string.no_diagnostics) }; showDiagnostics = true
    })
    if (showReconnect) AlertDialog(onDismissRequest = { showReconnect = false }, title = { Text(context.getString(R.string.reconnect_title)) }, text = { Text(context.getString(R.string.reconnect_body)) },
        confirmButton = { TextButton(onClick = { showReconnect = false; reconnect() }) { Text(context.getString(R.string.reconnect)) } }, dismissButton = { TextButton(onClick = { showReconnect = false }) { Text(context.getString(R.string.cancel)) } })
    if (showPaste) {
        var text by remember { mutableStateOf(clipboard.getText()?.text.orEmpty()) }
        AlertDialog(onDismissRequest = { showPaste = false }, title = { Text(context.getString(R.string.send_text)) }, text = {
            Column { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text(context.getString(R.string.text_field)) }); Text(context.getString(R.string.text_input_help), style = MaterialTheme.typography.bodySmall) }
        }, confirmButton = { TextButton(onClick = { input.enqueueText(text); showPaste = false }, enabled = isConnected) { Text(context.getString(R.string.send)) } }, dismissButton = { TextButton(onClick = { showPaste = false }) { Text(context.getString(R.string.cancel)) } })
    }
    if (showDiagnostics) AlertDialog(onDismissRequest = { showDiagnostics = false }, title = { Text(context.getString(R.string.diagnostics)) }, text = { Text(diagnostics, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(diagnostics)); showDiagnostics = false }) { Text(context.getString(R.string.copy_close)) } }, dismissButton = { TextButton(onClick = { showDiagnostics = false }) { Text(context.getString(R.string.close)) } })
}


@Composable
internal fun SessionFloatingControls(input: RdpInputDispatcher, fullscreen: Boolean, imeVisible: Boolean,
    pointerMode: Boolean, connected: Boolean, onKeyboard: () -> Unit, onPointerMode: () -> Unit,
    onChromeMode: () -> Unit, onSettings: () -> Unit, onPaste: () -> Unit, onResetZoom: () -> Unit,
    onSendFiles: () -> Unit, onReceiveFiles: () -> Unit, onExit: () -> Unit,
    onSharedSpace: () -> Unit = {}, onSharedDrive: () -> Unit = {}, onRotateScreen: () -> Unit = {}) {
    val context = LocalContext.current
    var showTools by remember { mutableStateOf(false) }
    val menuScroll = rememberScrollState()
    val menuDensity = LocalDensity.current
    val menuInsets = WindowInsets.safeDrawing
    val menuViewportHeight = (LocalConfiguration.current.screenHeightDp.dp - with(menuDensity) {
        (menuInsets.getTop(menuDensity) + menuInsets.getBottom(menuDensity)).toDp()
    } - 48.dp).coerceAtLeast(48.dp)
    val menuScope = rememberCoroutineScope()
    // Nonfocusable popup keeps the IME owner; handle Back locally instead of
    // letting an open session menu accidentally navigate away from the desktop.
    BackHandler(enabled = showTools) { showTools = false }
    LaunchedEffect(fullscreen, imeVisible) { showTools = false }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        tonalElevation = 6.dp, shadowElevation = 4.dp) {
        // Fullscreen leaves only one recoverable 48dp handle. The nonfocusable menu
        // preserves the desktop's InputConnection; no full-screen gesture layer exists.
        Row(Modifier.padding(if (fullscreen) 0.dp else 4.dp)) {
            if (!fullscreen) IconButton(onClick = { showTools = false; onKeyboard() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Keyboard, context.getString(R.string.toggle_keyboard))
            }
            Box {
                IconButton(onClick = { menuScope.launch {
                    // Every opening starts with keyboard/exit, not the last shortcut.
                    menuScroll.scrollTo(0)
                    showTools = true
                } }, modifier = Modifier.size(48.dp)) {
                    Icon(if (fullscreen) Icons.Default.MoreVert else Icons.Default.Settings,
                        if (fullscreen) context.getString(R.string.session_menu) else context.getString(R.string.session_tools))
                }
                DropdownMenu(expanded = showTools, onDismissRequest = { showTools = false },
                    // Keep the popup inside the safe viewport. Remaining commands
                    // stay reachable through its existing scroll state, including
                    // compact windows, large text and the on-screen keyboard.
                    modifier = Modifier.heightIn(max = menuViewportHeight),
                    scrollState = menuScroll, properties = PopupProperties(focusable = false)) {
                    if (fullscreen) DropdownMenuItem(text = { Text(context.getString(R.string.toggle_keyboard)) }, enabled = connected,
                        onClick = { showTools = false; onKeyboard() })
                    if (fullscreen) DropdownMenuItem(text = { Text(context.getString(R.string.disconnect)) }, onClick = { showTools = false; onExit() })
                    DropdownMenuItem(text = { Text(if (fullscreen) context.getString(R.string.show_toolbar) else context.getString(R.string.fullscreen)) },
                        onClick = { showTools = false; onChromeMode() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.rotate_screen)) },
                        onClick = { showTools = false; onRotateScreen() })
                    DropdownMenuItem(text = { Text(if (pointerMode) context.getString(R.string.direct_touch) else context.getString(R.string.trackpad)) }, enabled = connected,
                        onClick = { showTools = false; onPointerMode() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.display_settings)) }, onClick = { showTools = false; onSettings() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.space_title)) }, onClick = { showTools = false; onSharedSpace() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.drive_status_title)) }, onClick = { showTools = false; onSharedDrive() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.send_files)) }, enabled = connected, onClick = { showTools = false; onSendFiles() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.receive_files)) }, enabled = connected, onClick = { showTools = false; onReceiveFiles() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.send_text)) }, enabled = connected, onClick = { showTools = false; onPaste() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.reset_view)) }, enabled = connected, onClick = { showTools = false; onResetZoom() })
                    DropdownMenuItem(text = { Text("Esc") }, enabled = connected, onClick = { showTools = false; input.enqueueKeyStroke(0x01) })
                    DropdownMenuItem(text = { Text("Tab") }, enabled = connected, onClick = { showTools = false; input.enqueueKeyStroke(0x0F) })
                    DropdownMenuItem(text = { Text("Ctrl+C") }, enabled = connected, onClick = { showTools = false; input.enqueueShortcut(0x1D, 0x2E) })
                    DropdownMenuItem(text = { Text("Ctrl+V") }, enabled = connected, onClick = { showTools = false; input.enqueueShortcut(0x1D, 0x2F) })
                    DropdownMenuItem(text = { Text("Ctrl+Alt+Del") }, enabled = connected, onClick = { showTools = false; input.enqueueShortcut(0x1D, 0x38, 0xE053) })
                }
            }
            if (!fullscreen) IconButton(onClick = onExit, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Stop, context.getString(R.string.disconnect)) }
        }
    }
}


@Composable
internal fun DisplaySettingsDialog(settings: SessionDisplaySettings, device: Device, sessionInfo: String, adaptiveInfo: String, inputWarning: String,
    reconnectRequired: Boolean, fileTransferConnected: Boolean, onSendFiles: () -> Unit, onReceiveFiles: () -> Unit,
    onReconnect: () -> Unit, onChange: (SessionDisplaySettings) -> Unit, onDismiss: () -> Unit, onDiagnostics: () -> Unit,
    onSharedSpace: () -> Unit = {}, onSharedDrive: () -> Unit = {}) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(context.getString(R.string.display_network)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(context.getString(R.string.session_status), style = MaterialTheme.typography.titleSmall)
            Text(sessionInfo, style = MaterialTheme.typography.bodySmall)
            Text(if (settings.adaptive) context.getString(R.string.adaptive_status, adaptiveInfo) else context.getString(R.string.adaptive_off), style = MaterialTheme.typography.bodySmall)
            if (inputWarning.isNotBlank()) Text(inputWarning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (reconnectRequired) TextButton(onClick = onReconnect) { Text(context.getString(R.string.reconnect_settings)) }
            Spacer(Modifier.height(8.dp))
            Text(context.getString(R.string.drive_status_title), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onSharedSpace, modifier = Modifier.heightIn(min = 48.dp)) { Text(context.getString(R.string.space_title)) }
            TextButton(onClick = onSharedDrive, modifier = Modifier.heightIn(min = 48.dp)) { Text(context.getString(R.string.drive_status_title)) }
            HorizontalDivider()
            Text(context.getString(R.string.file_transfer), style = MaterialTheme.typography.titleSmall)
            Row {
                TextButton(onClick = onSendFiles, enabled = fileTransferConnected) { Text(context.getString(R.string.send_files)) }
                TextButton(onClick = onReceiveFiles, enabled = fileTransferConnected) { Text(context.getString(R.string.receive_files)) }
            }
            Text(context.getString(R.string.clipboard_transfer_help), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(context.getString(R.string.remote_resolution), style = MaterialTheme.typography.titleSmall)
            ResolutionMode.entries.forEach { mode ->
                val label = if (mode == ResolutionMode.PROFILE) context.getString(R.string.profile_resolution, device.width, device.height) else when (mode) { ResolutionMode.FIT_WINDOW -> context.getString(R.string.resolution_fit); ResolutionMode.PROFILE -> context.getString(R.string.resolution_saved); else -> mode.title }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = settings.resolution == mode, role = Role.RadioButton,
                    onClick = { onChange(settings.copy(resolution = mode)) }
                ).semantics { contentDescription = context.getString(R.string.remote_resolution_a11y, label) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.resolution == mode, onClick = null)
                    Text(label, modifier = Modifier.weight(1f))
                }
            }
            Text(context.getString(R.string.quality_limit), style = MaterialTheme.typography.titleSmall)
            Text(context.getString(R.string.quality_help), style = MaterialTheme.typography.bodySmall)
            DisplayQuality.entries.forEach { quality ->
                val qualityLabel = context.getString(when (quality) { DisplayQuality.ECONOMY -> R.string.quality_economy; DisplayQuality.BALANCED -> R.string.quality_balanced; DisplayQuality.SHARP -> R.string.quality_sharp })
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = settings.quality == quality, role = Role.RadioButton,
                    onClick = { onChange(settings.copy(quality = quality)) }
                ).semantics { contentDescription = context.getString(R.string.quality_a11y, qualityLabel) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.quality == quality, onClick = null)
                    Text(qualityLabel, modifier = Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = settings.adaptive, role = Role.Switch,
                onValueChange = { onChange(settings.copy(adaptive = it)) }
            ).semantics { contentDescription = context.getString(R.string.adaptive_quality) }, verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.adaptive, onCheckedChange = null)
                Text(context.getString(R.string.adaptive_quality), modifier = Modifier.padding(start = 8.dp))
            }
            Text(context.getString(R.string.adaptive_help), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(context.getString(R.string.gesture_help), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(context.getString(R.string.security_help), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDiagnostics) { Text(context.getString(R.string.view_diagnostics_full)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.done)) } })
}


private fun CertificateChallenge.snapshot() = CertificateChallengeSnapshot(
    generation, fingerprint, subject, issuer, host, port, effectiveServerName, reason
)

@Composable
internal fun CertificateTrustDialog(pending: PendingCertificateTrust, saving: Boolean, error: String, onCancel: () -> Unit, onTrust: () -> Unit) {
    val context = LocalContext.current
    var independentlyChecked by remember(pending) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        icon = { Icon(if (pending.changed) Icons.Default.WarningAmber else Icons.Default.VerifiedUser, null, tint = if (pending.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(if (pending.changed) context.getString(R.string.certificate_changed) else context.getString(R.string.certificate_confirm)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductNotice(if (pending.changed) context.getString(R.string.certificate_changed_warning) else context.getString(R.string.certificate_first_warning), error = pending.changed)
                Spacer(Modifier.height(8.dp))
                Text(context.getString(R.string.certificate_address, pending.endpoint.host, pending.endpoint.port))
                Text(context.getString(R.string.certificate_server, pending.endpoint.effectiveServerName))
                Text(context.getString(R.string.certificate_subject, pending.challenge.subject.take(512)), style = MaterialTheme.typography.bodySmall)
                Text(context.getString(R.string.certificate_issuer, pending.challenge.issuer.take(512)), style = MaterialTheme.typography.bodySmall)
                pending.previousFingerprint?.let { old ->
                    Spacer(Modifier.height(8.dp))
                    Text(context.getString(R.string.fingerprint_previous), style = MaterialTheme.typography.labelMedium)
                    Text(old, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(context.getString(R.string.fingerprint_new), style = MaterialTheme.typography.labelMedium)
                Text(pending.challenge.fingerprint, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = independentlyChecked, enabled = !saving, role = Role.Checkbox, onValueChange = { independentlyChecked = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(independentlyChecked, onCheckedChange = null, enabled = !saving)
                    Text(context.getString(R.string.fingerprint_verified), style = MaterialTheme.typography.bodySmall)
                }
                Text(context.getString(R.string.fingerprint_scope), style = MaterialTheme.typography.bodySmall)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onTrust, enabled = independentlyChecked && !saving) { Text(if (saving) context.getString(R.string.saving) else if (pending.changed) context.getString(R.string.fingerprint_replace) else context.getString(R.string.fingerprint_save)) } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text(context.getString(R.string.cancel_connection)) } }
    )
}
