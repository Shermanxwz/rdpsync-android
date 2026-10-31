package com.rdp.sync.ui.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.rdp.sync.ui.components.UiMessages
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rdp.sync.R
import com.rdp.sync.transfer.TransferPhase
import com.rdp.sync.transfer.TransferState
import com.rdp.sync.ui.components.ProductNotice
import com.rdp.sync.ui.components.SectionHeading

internal enum class FileTransferDirection { SEND, RECEIVE }

/** Clipboard copy/paste stays explicitly separate from genuine redirected drives. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileTransferDialog(state: TransferState, direction: FileTransferDirection, pickerPending: Boolean, pickerMessage: String?,
    onDirection: (FileTransferDirection) -> Unit, onPickFiles: () -> Unit, onPublish: () -> Unit, onReadOffer: () -> Unit,
    onReceive: () -> Unit, onExport: (String) -> Unit, onCancel: () -> Unit, onReconnect: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val busy = state.phase in setOf(TransferPhase.STAGING, TransferPhase.READING_OFFER, TransferPhase.RECEIVING, TransferPhase.EXPORTING)
    val available = state.capability.available && state.phase != TransferPhase.CLOSED
    val canChoose = available && !busy && !pickerPending && state.phase != TransferPhase.OFFERED
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.SwapVert, null) },
        title = { Text(stringResource(R.string.file_transfer)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.clipboard_subtitle), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(direction == FileTransferDirection.SEND, onClick = { onDirection(FileTransferDirection.SEND) },
                        label = { Text(stringResource(R.string.send_files)) }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(direction == FileTransferDirection.RECEIVE, onClick = { onDirection(FileTransferDirection.RECEIVE) },
                        label = { Text(stringResource(R.string.receive_files)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
                ProductNotice(stringResource(phaseLabel(state.phase)))
                if (!available) ProductNotice(UiMessages.text(context, state.capability.reason.ifBlank { stringResource(R.string.transfer_unsupported) }), error = true)
                state.error?.let { ProductNotice(UiMessages.text(context, it), error = true) }
                state.notice?.let { ProductNotice(UiMessages.text(context, it)) }
                pickerMessage?.let { ProductNotice(it) }
                if (busy) {
                    val total = state.progress.totalBytes
                    if (total != null && total > 0) LinearProgressIndicator(progress = { (state.progress.bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.progress.fileName, style = MaterialTheme.typography.bodyMedium)
                    Text(if (total == null) stringResource(R.string.transfer_bytes, state.progress.bytes) else stringResource(R.string.transfer_bytes_total, state.progress.bytes, total), style = MaterialTheme.typography.bodySmall)
                }
                if (direction == FileTransferDirection.SEND) {
                    val remaining = state.sendRemainingBytes
                    Text(stringResource(R.string.transfer_pick_help), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onPickFiles, enabled = canChoose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.choose_files)) }
                    state.outbound.forEach { file -> Text(stringResource(R.string.file_size, file.name, file.size), style = MaterialTheme.typography.bodyMedium) }
                    if (state.phase == TransferPhase.STAGED && state.outbound.isNotEmpty() && remaining != null && !TransferSendBudget.fits(state.outbound.map { it.size }, remaining))
                        ProductNotice(stringResource(R.string.transfer_budget_exceeded), error = true)
                    Button(onClick = onPublish, enabled = available && !pickerPending && state.phase == TransferPhase.STAGED && TransferSendBudget.fits(state.outbound.map { it.size }, remaining),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.offer_clipboard)) }
                    Text(stringResource(R.string.transfer_offer_help), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SectionHeading(stringResource(R.string.transfer_allowance_title))
                    Text(if (remaining == null) stringResource(R.string.transfer_budget_loading) else stringResource(R.string.transfer_budget, remaining),
                        style = MaterialTheme.typography.bodyMedium, color = if (remaining == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.transfer_budget_help), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onReconnect, enabled = !busy && !pickerPending, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.transfer_reconnect)) }
                    Text(stringResource(R.string.transfer_reconnect_help), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.receive_help), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onReadOffer, enabled = canChoose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.read_offer)) }
                    state.remoteOffer?.files?.forEach { file -> Text(stringResource(R.string.file_size, file.name, file.size), style = MaterialTheme.typography.bodyMedium) }
                    Button(onClick = onReceive, enabled = available && !pickerPending && state.phase == TransferPhase.AWAITING_RECEIVE && state.remoteOffer?.files?.isNotEmpty() == true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.confirm_receive)) }
                    state.completed.forEach { file ->
                        val exportLabel = stringResource(R.string.export_file_a11y, file.name)
                        Column(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.file_size, file.name, file.size), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { onExport(file.id) }, enabled = !busy && !pickerPending && state.phase != TransferPhase.CLOSED,
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = exportLabel }) { Text(stringResource(R.string.export_action)) }
                        }
                    }
                    Text(stringResource(R.string.export_help), style = MaterialTheme.typography.bodySmall)
                }
                if (pickerPending) ProductNotice(stringResource(R.string.picker_pending))
                if (state.cancellable || busy || state.phase in setOf(TransferPhase.OFFERED, TransferPhase.AWAITING_RECEIVE, TransferPhase.STAGED))
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(if (state.phase == TransferPhase.OFFERED) R.string.stop_sharing else R.string.cancel_transfer))
                    }
                Text(stringResource(R.string.transfer_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.return_desktop)) } })
}

private fun phaseLabel(phase: TransferPhase): Int = when (phase) {
    TransferPhase.IDLE -> R.string.transfer_idle
    TransferPhase.STAGING -> R.string.transfer_staging
    TransferPhase.STAGED -> R.string.transfer_staged
    TransferPhase.OFFERED -> R.string.transfer_offered
    TransferPhase.READING_OFFER -> R.string.transfer_reading
    TransferPhase.AWAITING_RECEIVE -> R.string.transfer_awaiting
    TransferPhase.RECEIVING -> R.string.transfer_receiving
    TransferPhase.RECEIVED -> R.string.transfer_received
    TransferPhase.EXPORTING -> R.string.transfer_exporting
    TransferPhase.CANCELLED -> R.string.transfer_cancelled
    TransferPhase.CLOSED -> R.string.transfer_closed
}
