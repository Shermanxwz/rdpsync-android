package com.rdp.sync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rdp.sync.R
import com.rdp.sync.data.Device
import com.rdp.sync.network.SharedDriveMode
import com.rdp.sync.ui.sharedspace.SharedDriveConsentDialog
import com.rdp.sync.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(device: Device, onBack: () -> Unit, onEdit: () -> Unit, onConnect: () -> Unit, onDelete: () -> Unit, onConnectWithSharedDrive: ((SharedDriveMode) -> Unit)? = null) {
    var showDriveConsent by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.connection_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) } })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ProductIcon(Icons.Default.Computer)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.name, style = MaterialTheme.typography.headlineMedium)
                    Text("${device.host}:${device.port}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (device.credentialUnavailable) ProductNotice(stringResource(
                    if (device.passwordFormat == 2) R.string.credential_missing_help else R.string.credential_locked_help), error = true)
                Button(onClick = if (device.credentialUnavailable) onEdit else onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(if (device.credentialUnavailable) Icons.Default.Edit else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (device.credentialUnavailable) R.string.edit_device else R.string.connect))
                }
                if (!device.credentialUnavailable && onConnectWithSharedDrive != null) OutlinedButton(
                    onClick = { showDriveConsent = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Default.FolderShared, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.drive_connect_action))
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeading(stringResource(R.string.connection_info))
                        InfoRow(stringResource(R.string.username), device.username.ifEmpty { stringResource(R.string.not_set) })
                        InfoRow(stringResource(R.string.domain), device.domain.ifEmpty { stringResource(R.string.not_set) })
                        if (device.rdpServerName.isNotEmpty()) InfoRow(stringResource(R.string.server_name), device.rdpServerName)
                        InfoRow(stringResource(R.string.resolution), "${device.width} × ${device.height}")
                    }
                }
                ProductNotice(stringResource(R.string.connection_security))
                TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.delete_confirm))
                }
            }
        }
    }
    if (showDriveConsent && onConnectWithSharedDrive != null) SharedDriveConsentDialog(
        onConfirm = { readOnly -> showDriveConsent = false; onConnectWithSharedDrive(if (readOnly) SharedDriveMode.READ_ONLY else SharedDriveMode.READ_WRITE) },
        onDismiss = { showDriveConsent = false }, reconnectRequired = false, serverLabel = "${device.name} · ${device.host}:${device.port}")
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, icon = { Icon(Icons.Default.DeleteOutline, null) },
        title = { Text(stringResource(R.string.delete_title)) }, text = { Text(stringResource(R.string.delete_body, device.name), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun InfoRow(label: String, value: String) {
    // Stacked values remain legible for long hostnames and 200% text; no truncation of identifiers.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
