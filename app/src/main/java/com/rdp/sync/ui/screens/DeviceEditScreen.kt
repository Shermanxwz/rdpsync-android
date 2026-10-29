package com.rdp.sync.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rdp.sync.data.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEditScreen(
    device: Device?,
    onSave: (Device) -> Unit,
    onCancel: () -> Unit,
    errorMessage: String? = null,
    isSaving: Boolean = false
) {
    var name by remember { mutableStateOf(device?.name ?: "") }
    var host by remember { mutableStateOf(device?.host ?: "") }
    var port by remember { mutableIntStateOf(device?.port ?: 3389) }
    var username by remember { mutableStateOf(device?.username ?: "") }
    var password by remember { mutableStateOf(device?.password ?: "") }
    var domain by remember { mutableStateOf(device?.domain ?: "") }
    var rdpServerName by remember { mutableStateOf(device?.rdpServerName ?: "") }
    var showPassword by remember { mutableStateOf(false) }

    BackHandler(enabled = isSaving) { /* Wait for the atomic credential write before leaving. */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (device != null) "编辑设备" else "添加设备") },
                navigationIcon = {
                    IconButton(enabled = !isSaving, onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(enabled = !isSaving && (device?.credentialUnavailable != true || password.isNotEmpty()), onClick = {
                        val d = Device(
                            id = device?.id ?: 0,
                            name = name, host = host, port = port,
                            username = username, password = password,
                            domain = domain, rdpServerName = rdpServerName,
                            width = device?.width ?: 1280, height = device?.height ?: 720
                        )
                        onSave(d)
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "保存")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!errorMessage.isNullOrBlank()) Text(errorMessage, color = MaterialTheme.colorScheme.error)
            if (device?.credentialUnavailable == true) Text(
                if (device.passwordFormat == 2) "此配置未包含密码。请明确输入密码后保存，才能连接。"
                else "此设备密码无法解密。原加密数据仍保留；输入新密码并保存将明确替换此设备的凭据。留空不能保存。",
                color = MaterialTheme.colorScheme.error)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("设备名称") },
                leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP地址") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = port.toString(),
                onValueChange = { port = it.toIntOrNull() ?: 3389 },
                label = { Text("端口") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("域 (可选)") },
                leadingIcon = { Icon(Icons.Default.Domain, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = rdpServerName,
                onValueChange = { rdpServerName = it },
                label = { Text("CredSSP主机名") },
                placeholder = { Text("Windows电脑名（可选）") },
                supportingText = { Text("用IP连接NLA失败时填此字段") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
