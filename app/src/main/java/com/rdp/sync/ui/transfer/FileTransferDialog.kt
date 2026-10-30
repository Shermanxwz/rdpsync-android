package com.rdp.sync.ui.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rdp.sync.transfer.TransferPhase
import com.rdp.sync.transfer.TransferState

internal enum class FileTransferDirection { SEND, RECEIVE }

/** Consent/progress UI only. URI access and protocol work belong to the session controller. */
@Composable
internal fun FileTransferDialog(
    state: TransferState,
    direction: FileTransferDirection,
    pickerPending: Boolean,
    pickerMessage: String?,
    onDirection: (FileTransferDirection) -> Unit,
    onPickFiles: () -> Unit,
    onPublish: () -> Unit,
    onReadOffer: () -> Unit,
    onReceive: () -> Unit,
    onExport: (String) -> Unit,
    onCancel: () -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val busy = state.phase in setOf(TransferPhase.STAGING, TransferPhase.READING_OFFER,
        TransferPhase.RECEIVING, TransferPhase.EXPORTING)
    val available = state.capability.available && state.phase != TransferPhase.CLOSED
    val canChoose = available && !busy && !pickerPending && state.phase != TransferPhase.OFFERED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件传输") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FilterChip(selected = direction == FileTransferDirection.SEND,
                        onClick = { onDirection(FileTransferDirection.SEND) }, label = { Text("发送文件") })
                    FilterChip(selected = direction == FileTransferDirection.RECEIVE,
                        onClick = { onDirection(FileTransferDirection.RECEIVE) }, label = { Text("接收文件") })
                }
                Text("仅支持普通文件，暂不支持文件夹；不会将文件夹伪装成 ZIP。最多 64 个文件；接收单文件上限 128 MiB，私有暂存总上限 256 MiB。",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (!available) Text(state.capability.reason.ifBlank { "此会话暂不支持文件传输。" },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(phaseLabel(state.phase), style = MaterialTheme.typography.titleSmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                pickerMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (busy) {
                    val total = state.progress.totalBytes
                    if (total != null && total > 0) LinearProgressIndicator(
                        progress = { (state.progress.bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.progress.fileName, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    Text(if (total == null) "已处理 ${state.progress.bytes} 字节" else "已处理 ${state.progress.bytes} / $total 字节",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (direction == FileTransferDirection.SEND) {
                    val remaining = state.sendRemainingBytes
                    Text(if (remaining == null) "本次连接可发送剩余：正在查询（累计上限 32 MiB）"
                        else "本次连接可发送剩余：$remaining 字节；累计上限 32 MiB（33554432 字节）",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (remaining == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("重复读取、失败或取消的读取也可能计入额度；取消传输不会恢复额度。只有主动重新连接才会重置，不会自动重连或续传。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onReconnect, enabled = !busy && !pickerPending) { Text("重新连接以开始新的发送会话") }
                    Text("重连会短暂中断远程桌面并撤回当前文件共享；请先保存远程工作，重连后需重新选择并提供文件。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("只读取你随后明确选择的文件，并复制到本应用私有暂存区；不会共享手机目录或监听系统剪贴板。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onPickFiles, enabled = canChoose) { Text("选择文件") }
                    state.outbound.forEach { file ->
                        Text("${file.name} · ${file.size} 字节", maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.phase == TransferPhase.STAGED && state.outbound.isNotEmpty() && remaining != null &&
                        !TransferSendBudget.fits(state.outbound.map { it.size }, remaining)) {
                        Text("所选文件总大小超过本次连接剩余额度，尚未提供给远程。请减少所选文件，或明确重连后重新选择。",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onPublish, enabled = available && !pickerPending && state.phase == TransferPhase.STAGED &&
                        TransferSendBudget.fits(state.outbound.map { it.size }, remaining)) {
                        Text("提供给远程剪贴板")
                    }
                    Text("提供后，请返回远程桌面，在远程文件管理器中粘贴。已提供或已响应读取不等于远程已经保存；请在远程确认文件。关闭窗口不会停止已经确认的传输；停止共享或断开连接会撤回已提供的文件。",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("先在远程文件管理器复制文件，再主动读取文件列表；确认接收后才下载内容。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onReadOffer, enabled = canChoose) { Text("读取远程文件列表") }
                    state.remoteOffer?.files?.forEach { file ->
                        Text("${file.name} · ${file.size} 字节", maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onReceive, enabled = available && !pickerPending && state.phase == TransferPhase.AWAITING_RECEIVE &&
                        state.remoteOffer?.files?.isNotEmpty() == true) { Text("确认接收这些文件") }
                    state.completed.forEach { file ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${file.name} · ${file.size} 字节", modifier = Modifier.weight(1f), maxLines = 2,
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onExport(file.id) }, enabled = !busy && !pickerPending && state.phase != TransferPhase.CLOSED,
                                modifier = Modifier.semantics { contentDescription = "导出文件：${file.name}" }) { Text("导出") }
                        }
                    }
                    Text("导出时只在你选择的目录中新建文件，不覆盖已有文件。中断或提供方故障可能留下新建的不完整副本；完整暂存文件会保留至会话结束。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (pickerPending) Text("系统文件选择器尚未返回；取消选择不会发送或导出文件。", style = MaterialTheme.typography.bodySmall)
                if (state.cancellable || busy || state.phase in setOf(TransferPhase.OFFERED, TransferPhase.AWAITING_RECEIVE, TransferPhase.STAGED)) TextButton(onClick = onCancel) {
                    Text(if (state.phase == TransferPhase.OFFERED) "停止共享" else "取消传输")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回远程桌面") } }
    )
}

private fun phaseLabel(phase: TransferPhase): String = when (phase) {
    TransferPhase.IDLE -> "尚未选择或接收文件"
    TransferPhase.STAGING -> "正在私有暂存所选文件…"
    TransferPhase.STAGED -> "已暂存，尚未提供给远程"
    TransferPhase.OFFERED -> "已提供文件，请在远程粘贴"
    TransferPhase.READING_OFFER -> "正在读取远程文件列表…"
    TransferPhase.AWAITING_RECEIVE -> "等待你确认接收"
    TransferPhase.RECEIVING -> "正在接收至私有暂存区…"
    TransferPhase.RECEIVED -> "文件已接收，请选择目录导出"
    TransferPhase.EXPORTING -> "正在向所选目录新建文件…"
    TransferPhase.CANCELLED -> "文件传输已取消"
    TransferPhase.CLOSED -> "远程会话已结束"
}
