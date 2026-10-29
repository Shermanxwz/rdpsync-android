package com.rdp.sync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rdp.sync.data.Device
import com.rdp.sync.manager.SyncDirection
import com.rdp.sync.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    uiState: UiState,
    onAddDevice: () -> Unit,
    onDeviceClick: (Long) -> Unit,
    onSync: (SyncDirection) -> Unit,
    onSaveWebDavSettings: (String, String, String) -> Unit,
    onTestWebDavSettings: (String, String, String) -> Unit,
    onMessageShown: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var showSyncMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSync by remember { mutableStateOf<SyncDirection?>(null) }

    LaunchedEffect(uiState.error, uiState.message) {
        val text = uiState.error ?: uiState.message
        if (!text.isNullOrBlank()) {
            snackbarHostState.showSnackbar(text)
            onMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("RdpSync - 设备列表") },
                actions = {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(enabled = uiState.webDavSettingsLoaded, onClick = { showSyncMenu = true }) {
                            Icon(Icons.Default.CloudSync, contentDescription = "同步")
                        }
                        DropdownMenu(expanded = showSyncMenu, onDismissRequest = { showSyncMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("智能合并（推荐）") },
                                leadingIcon = { Icon(Icons.Default.CloudSync, null) },
                                onClick = { showSyncMenu = false; pendingSync = SyncDirection.MERGE }
                            )
                            DropdownMenuItem(
                                text = { Text("上传本机列表") },
                                leadingIcon = { Icon(Icons.Default.CloudUpload, null) },
                                onClick = { showSyncMenu = false; pendingSync = SyncDirection.UPLOAD }
                            )
                            DropdownMenuItem(
                                text = { Text("下载云端列表") },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                onClick = { showSyncMenu = false; pendingSync = SyncDirection.DOWNLOAD }
                            )
                        }
                    }
                    IconButton(enabled = uiState.webDavSettingsLoaded, onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddDevice) {
                Icon(Icons.Default.Add, contentDescription = "添加设备")
            }
        }
    ) { paddingValues ->
        if (uiState.devices.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("暂无设备，点击 + 添加", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text("设置 WebDAV 后可在多台手机之间同步 RDP 列表", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.devices, key = { it.id }) { device ->
                    DeviceCard(device, onClick = { onDeviceClick(device.id) })
                }
            }
        }
    }

    pendingSync?.let { direction ->
        SyncModeDialog(direction, onDismiss = { pendingSync = null }, onConfirm = { selected ->
            pendingSync = null
            onSync(selected)
        })
    }

    if (showSettings) {
        WebDavSettingsDialog(
            initialUrl = uiState.webDavUrl,
            initialUsername = uiState.webDavUsername,
            initialPassword = uiState.webDavPassword,
            passwordUnavailable = uiState.webDavCredentialUnavailable,
            onDismiss = { showSettings = false },
            onSave = { url, username, password ->
                onSaveWebDavSettings(url, username, password)
                showSettings = false
            },
            onTest = { url, username, password ->
                onTestWebDavSettings(url, username, password)
            }
        )
    }
}

@Composable
private fun WebDavSettingsDialog(
    initialUrl: String,
    initialUsername: String,
    initialPassword: String,
    passwordUnavailable: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV 同步设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("WebDAV 地址") },
                    placeholder = { Text("https://example.com/dav/rdpsync") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (passwordUnavailable) Text("已保存的密码无法解密。请明确重新输入；留空不会覆盖旧数据。", color = MaterialTheme.colorScheme.error)
                Text("默认仅同步设备配置（rdpsync_profiles.json），不发送 RDP 密码。旧客户端只识别旧版含密码文件。WebDAV 备份并非端到端加密；请使用 HTTPS。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && username.isNotBlank() && (!passwordUnavailable || password.isNotEmpty()),
                onClick = { onSave(url, username, password) }
            ) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = url.isNotBlank() && username.isNotBlank(),
                    onClick = { onTest(url, username, password) }
                ) { Text("测试连接") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
fun DeviceCard(device: Device, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text("${device.host}:${device.port}", style = MaterialTheme.typography.bodyMedium)
                Text("用户: ${device.username}", style = MaterialTheme.typography.bodySmall)
                if (device.credentialUnavailable) Text(if (device.passwordFormat == 2) "未保存密码 · 编辑后输入" else "密码无法解密 · 编辑后重新输入，原数据保留", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = "连接", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SyncModeDialog(direction: SyncDirection, onDismiss: () -> Unit, onConfirm: (SyncDirection) -> Unit) {
    var includePasswords by remember(direction) { mutableStateOf(false) }
    val download = direction == SyncDirection.DOWNLOAD
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择同步文件") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("默认：仅设备配置 · rdpsync_profiles.json\n不发送 RDP 密码，不改动旧版云端文件；导入时保留已有本机密码和设备。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includePasswords, onCheckedChange = { includePasswords = it })
                Text("使用旧版含密码文件 rdpsync_devices.json")
            }
            if (includePasswords) Text(if (download)
                "读取旧客户端的备份。文件中的密码将导入本机加密存储；明确的空密码会更新本机密码。"
                else "警告：继续会将 RDP 密码以可读明文上传到旧版文件！仅 HTTPS 传输加密，不是端到端加密。服务器和有文件权限的人可读取密码。",
                color = MaterialTheme.colorScheme.error)
            Text("合并时同一设备以本机为准；要获取旧客户端更新，请选下载并勾选旧版文件。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(if (includePasswords) direction.withPasswords() else direction) }) {
        Text(if (includePasswords && !download) "确认上传明文密码" else "继续")
    } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
