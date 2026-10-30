package com.rdp.sync.ui.sharedspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rdp.sync.R
import com.rdp.sync.ui.components.ProductNotice

/** Every opening starts read-only. Closing, Back, or declining grants no access. */
@Composable
internal fun SharedDriveConsentDialog(onConfirm: (readOnly: Boolean) -> Unit, onDismiss: () -> Unit, reconnectRequired: Boolean = true, serverLabel: String? = null) {
    var writable by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.FolderShared, null) },
        title = { Text(stringResource(R.string.drive_consent_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!serverLabel.isNullOrBlank()) Text(serverLabel, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.drive_consent_scope), style = MaterialTheme.typography.bodyLarge)
                ProductNotice(stringResource(if (reconnectRequired) R.string.drive_reconnect_warning else R.string.drive_connect_warning))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(writable, role = Role.Switch, onValueChange = { writable = it; acknowledged = false }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(writable, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.drive_allow_write), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(if (writable) R.string.drive_mode_rw else R.string.drive_mode_ro), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (writable) {
                    ProductNotice(stringResource(R.string.drive_write_warning), error = true)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(acknowledged, role = Role.Checkbox, onValueChange = { acknowledged = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(acknowledged, onCheckedChange = null)
                        Text(stringResource(R.string.drive_write_ack), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.drive_no_autosync), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(onClick = { onConfirm(!writable) }, enabled = !writable || acknowledged, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (reconnectRequired) { if (writable) R.string.drive_enable_rw else R.string.drive_enable_ro } else { if (writable) R.string.drive_connect_rw else R.string.drive_connect_ro }))
            }
        }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}
