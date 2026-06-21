package com.rdp.sync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rdp.sync.data.Device
import com.rdp.sync.viewmodel.DeviceViewModel

/**
 * 设备详情页面
 * 显示设备连接信息，提供连接/编辑/删除操作
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: DeviceViewModel = viewModel()
) {
    val devices by viewModel.devices.collectAsState()
    val device = devices.find { it.id == deviceId }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                }
            )
        }
    ) { padding ->
        device?.let { dev ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // Device info
                DeviceInfoCard(dev)
                Spacer(modifier = Modifier.height(16.dp))
                ConnectButton(dev)
            }
        } ?: run {
            Text("Device not found", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun DeviceInfoCard(device: Device) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoRow("名称", device.name)
            InfoRow("地址", "${device.host}:${device.port}")
            InfoRow("用户名", device.username)
            InfoRow("域", if (device.domain.isNotEmpty()) device.domain else "无")
            InfoRow("颜色深度", "${device.colorDepth}-bit")
            InfoRow("全屏", if (device.fullscreen) "是" else "否")
            InfoRow("剪贴板", if (device.clipboard) "是" else "否")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ConnectButton(device: Device) {
    Button(
        onClick = { /* TODO: Connect to RDP */ },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("连接远程桌面")
    }
}
