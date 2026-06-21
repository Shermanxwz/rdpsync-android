package com.rdp.sync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rdp.sync.data.Device
import com.rdp.sync.viewmodel.DeviceViewModel

/**
 * 设备编辑页面
 * 创建或编辑 RDP 设备连接配置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEditScreen(
    deviceId: Long? = null,
    onBack: () -> Unit,
    viewModel: DeviceViewModel = viewModel()
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableIntStateOf(3389) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var clipboard by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(true) }
    
    // TODO: Load existing device if editing
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (deviceId == null) "添加设备" else "编辑设备") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("设备名称") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP 地址 / 域名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = port.toString(),
                onValueChange = { 
                    port = it.toIntOrNull() ?: 3389
                },
                label = { Text("端口") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("域 (可选)") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("全屏模式")
                Switch(
                    checked = fullscreen,
                    onCheckedChange = { fullscreen = it }
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("剪贴板同步")
                Switch(
                    checked = clipboard,
                    onCheckedChange = { clipboard = it }
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("声音")
                Switch(
                    checked = sound,
                    onCheckedChange = { sound = it }
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = {
                    val device = Device(
                        id = deviceId ?: 0,
                        name = name,
                        host = host,
                        port = port,
                        username = username,
                        password = password,
                        domain = domain,
                        fullscreen = fullscreen,
                        clipboard = clipboard,
                        sound = sound
                    )
                    if (deviceId == null) {
                        viewModel.addDevice(device)
                    } else {
                        viewModel.updateDevice(device)
                    }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }
        }
    }
}
