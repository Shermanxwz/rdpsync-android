package com.rdp.sync.ui.sharedspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rdp.sync.R
import com.rdp.sync.network.SharedDriveMode
import com.rdp.sync.network.SharedDriveState
import com.rdp.sync.network.SharedDriveStatus
import com.rdp.sync.ui.components.ProductNotice
import com.rdp.sync.ui.components.SectionHeading

/** Uses actual native milestones; capabilities alone are never presented as an accepted drive. */
@Composable
internal fun SharedDriveStatusDialog(state: SharedDriveState, onOpenSpace: () -> Unit, onStopSharing: () -> Unit,
    onReconnect: () -> Unit, onDismiss: () -> Unit, actionError: String = "", busy: Boolean = false, canStop: Boolean = state.mayRevoke) {
    var confirmStop by remember(state.generation) { mutableStateOf(false) }
    val status = when (state.status) {
        SharedDriveStatus.OFF -> R.string.drive_status_off
        SharedDriveStatus.REQUESTED -> R.string.drive_status_requested
        SharedDriveStatus.CAPABLE -> R.string.drive_status_capable
        SharedDriveStatus.ACCEPTED -> R.string.drive_status_accepted
        SharedDriveStatus.USED -> R.string.drive_status_used
        SharedDriveStatus.UNSUPPORTED -> R.string.drive_status_unsupported
        SharedDriveStatus.REJECTED -> R.string.drive_status_rejected
        SharedDriveStatus.REVOKED -> R.string.drive_status_revoked
        SharedDriveStatus.TRANSPORT_UNAVAILABLE -> R.string.drive_status_transport
        SharedDriveStatus.PROTOCOL_FAILED -> R.string.drive_status_failed
        SharedDriveStatus.UNKNOWN -> R.string.drive_status_unknown
    }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.FolderShared, null) },
        title = { Text(stringResource(R.string.drive_status_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProductNotice(stringResource(status), error = state.status.nativeValue < 0)
                if (actionError.isNotBlank()) ProductNotice(actionError, error = true)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.mode != SharedDriveMode.OFF) {
                    SectionHeading(stringResource(R.string.drive_scope_title), stringResource(R.string.drive_scope_value))
                    Text(stringResource(if (state.mode == SharedDriveMode.READ_WRITE) R.string.drive_mode_rw else R.string.drive_mode_ro),
                        style = MaterialTheme.typography.titleSmall)
                }
                if (state.accepted) Text(stringResource(R.string.drive_explorer_help), style = MaterialTheme.typography.bodyMedium)
                if (state.status in setOf(SharedDriveStatus.REVOKED, SharedDriveStatus.TRANSPORT_UNAVAILABLE, SharedDriveStatus.PROTOCOL_FAILED, SharedDriveStatus.UNKNOWN))
                    Text(stringResource(R.string.drive_stale_icon), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.drive_status_lifetime), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenSpace, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.space_title)) }
                if (canStop) TextButton(onClick = { confirmStop = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.stop_sharing)) }
                if (state.accepted) TextButton(onClick = onReconnect, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.drive_change_access))
                }
                if (!state.accepted && !state.mayRevoke) TextButton(onClick = onReconnect, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.mode == SharedDriveMode.OFF) R.string.drive_enable_action else R.string.reconnect))
                }
                Text(stringResource(R.string.drive_clipboard_fallback), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.return_desktop)) } })
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text(stringResource(R.string.drive_stop_title)) },
        text = { Text(stringResource(R.string.drive_stop_warning), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmStop = false; onStopSharing() }, enabled = canStop && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_stop_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}
