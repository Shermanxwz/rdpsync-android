package com.rdp.sync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rdp.sync.R
import com.rdp.sync.data.Device
import com.rdp.sync.manager.SyncDirection
import com.rdp.sync.ui.components.*
import com.rdp.sync.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(uiState: UiState, onAddDevice: () -> Unit, onDeviceClick: (Long) -> Unit,
    onSync: (SyncDirection) -> Unit, onSaveWebDavSettings: (String, String, String) -> Unit,
    onTestWebDavSettings: (String, String, String) -> Unit, onMessageShown: () -> Unit, onSharedSpace: () -> Unit = {}) {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.screenWidthDp <= 400
    val syncDescription = stringResource(R.string.sync)
    val syncingDescription = stringResource(R.string.sync_in_progress)
    var showSettings by remember { mutableStateOf(false) }
    var showSyncMenu by remember { mutableStateOf(false) }
    var pendingSync by remember { mutableStateOf<SyncDirection?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error, uiState.message, showSettings) {
        val text = uiState.error ?: uiState.message
        if (!text.isNullOrBlank() && !showSettings) { snackbar.showSnackbar(UiMessages.text(context, text)); onMessageShown() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
            IconButton(onClick = onSharedSpace) { Icon(Icons.Default.FolderShared, stringResource(R.string.space_title)) }
            Box {
                IconButton(enabled = uiState.webDavSettingsLoaded && !uiState.isSyncing, onClick = { showSyncMenu = true },
                    modifier = Modifier.semantics { contentDescription = syncDescription; if (uiState.isSyncing) stateDescription = syncingDescription }) {
                    if (uiState.isSyncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.CloudSync, null)
                }
                DropdownMenu(showSyncMenu, onDismissRequest = { showSyncMenu = false }) {
                    listOf(Triple(SyncDirection.MERGE, R.string.sync_merge, Icons.Default.CloudSync),
                        Triple(SyncDirection.UPLOAD, R.string.sync_upload, Icons.Default.CloudUpload),
                        Triple(SyncDirection.DOWNLOAD, R.string.sync_download, Icons.Default.CloudDownload)).forEach { (mode, label, icon) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) },
                            onClick = { showSyncMenu = false; pendingSync = mode })
                    }
                }
            }
            IconButton(enabled = uiState.webDavSettingsLoaded, onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, stringResource(R.string.settings))
            }
        })
    }, bottomBar = {
        // Reserve the action's measured height rather than overlaying a profile's
        // credential warning. Text scaling and navigation insets remain intact.
        if (uiState.devices.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.widthIn(max = 840.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ExtendedFloatingActionButton(onClick = onAddDevice,
                        modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
                        icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.add_device)) })
                }
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(if (compact) 16.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.headlineMedium)
                        if (!compact) Text(stringResource(R.string.profiles_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (uiState.devices.isNotEmpty()) Text(stringResource(R.string.profiles_count, uiState.devices.size),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (uiState.isLoading) item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.loading_profiles))
                    }
                } else if (uiState.devices.isEmpty()) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ProductIcon(Icons.Default.Computer)
                            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onAddDevice, modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth()) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_device))
                            }
                        }
                    }
                } else {
                    if (!compact) item { SectionHeading(stringResource(R.string.profiles_section), stringResource(R.string.profiles_count, uiState.devices.size)) }
                    items(uiState.devices, key = { it.id }) { device -> DeviceCard(device, compact = compact) { onDeviceClick(device.id) } }
                }
                item { ProductNotice(stringResource(R.string.sync_hint)) }
            }
        }
    }
    pendingSync?.let { direction -> SyncModeDialog(direction, { pendingSync = null }) { pendingSync = null; onSync(it) } }
    if (showSettings) WebDavSettingsDialog(uiState.webDavUrl, uiState.webDavUsername, uiState.webDavPassword,
        uiState.webDavCredentialUnavailable, uiState.error ?: uiState.message, uiState.error != null,
        onDismiss = { showSettings = false }, onSave = { url, user, password -> onSaveWebDavSettings(url, user, password); showSettings = false }, onTest = onTestWebDavSettings, isTesting = uiState.isSyncing, completionId = uiState.webDavTestCompletion)
}

@Composable
internal fun WebDavSettingsDialog(initialUrl: String, initialUsername: String, initialPassword: String, passwordUnavailable: Boolean,
    resultMessage: String? = null, resultError: Boolean = false, onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit, onTest: (String, String, String) -> Unit, isTesting: Boolean = false, completionId: Long = 0) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    var showPassword by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var submittedAtCompletion by remember { mutableLongStateOf(completionId) }
    val busy = isTesting || submitted
    LaunchedEffect(isTesting, completionId) {
        // A completion identity also changes for repeated identical fast results.
        // An old nonblank message is never evidence that this submission ended.
        if (isTesting || completionId != submittedAtCompletion) submitted = false
    }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.CloudSync, null) },
        title = { Text(stringResource(R.string.webdav_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.webdav_subtitle))
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.webdav_url)) },
                    placeholder = { Text("https://example.com/dav/") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }, enabled = !busy) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                    } }, modifier = Modifier.fillMaxWidth())
                if (passwordUnavailable) ProductNotice(stringResource(R.string.webdav_locked), error = true)
                if (!resultMessage.isNullOrBlank()) ProductNotice(UiMessages.text(context, resultMessage), error = resultError)
                OutlinedButton(onClick = { if (!isTesting && !submitted) { submittedAtCompletion = completionId; submitted = true; onTest(url, username, password) } }, enabled = !busy && url.isNotBlank() && username.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(stringResource(if (busy) R.string.testing_connection else R.string.test_connection))
                }
                if (busy) Text(stringResource(R.string.testing_connection_close), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.webdav_security), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = {
            TextButton(enabled = !busy && url.isNotBlank() && username.isNotBlank() && (!passwordUnavailable || password.isNotEmpty()),
                onClick = { if (!isTesting && !submitted) { submittedAtCompletion = completionId; submitted = true; onSave(url, username, password) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
        }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (busy) R.string.close else R.string.cancel)) } })
}

@Composable
fun DeviceCard(device: Device, compact: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(if (compact) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(if (compact) 32.dp else 44.dp), shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Computer, null, modifier = Modifier.size(if (compact) 20.dp else 26.dp)) }
                }
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            // A port is an indivisible unit. Full identifiers remain in semantics
            // and on the detail/edit route; visual ellipsis never rewrites them.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(device.host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(":${device.port}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false)
            }
            if (device.username.isNotEmpty()) Text(device.username, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (device.credentialUnavailable) Text(stringResource(if (device.passwordFormat == 2) R.string.password_missing else R.string.password_locked),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun SyncModeDialog(direction: SyncDirection, onDismiss: () -> Unit, onConfirm: (SyncDirection) -> Unit) {
    var includePasswords by remember(direction) { mutableStateOf(false) }
    val download = direction == SyncDirection.DOWNLOAD
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.CloudSync, null) }, title = { Text(stringResource(R.string.sync_file_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sync_profile_info))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(includePasswords, role = Role.Checkbox, onValueChange = { includePasswords = it }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includePasswords, onCheckedChange = null)
                Text(stringResource(R.string.sync_legacy), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            if (includePasswords) ProductNotice(stringResource(if (download) R.string.sync_legacy_download else R.string.sync_legacy_warning), error = true)
            Text(stringResource(R.string.sync_merge_info), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { onConfirm(if (includePasswords) direction.withPasswords() else direction) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (includePasswords && !download) R.string.sync_plaintext_confirm else R.string.continue_action))
        }
    }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}
