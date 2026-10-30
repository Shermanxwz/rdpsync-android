package com.rdp.sync.ui.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.transfer.TransferController
import kotlinx.coroutines.launch

private data class PendingTransferPicker(val guard: TransferPickerGuard, val ticket: TransferPickerGuard.Ticket)

/** Kept composed while its RDP session lives, even when the progress dialog is closed. */
@Composable
internal fun SessionFileTransferUi(controller: TransferController, visible: Boolean,
    requestedDirection: FileTransferDirection, onReconnect: () -> Unit, onDismiss: () -> Unit) {
    // Route Back/pop must invalidate streams immediately, not wait for the RDP
    // loop's in-flight IO to unwind. Hiding the dialog keeps this owner composed.
    DisposableEffect(controller) { onDispose { controller.close() } }
    // A newly connected session must never reuse an old ActivityResult registration.
    key(controller.sessionId) {
        TransferPickerContent(controller, visible, requestedDirection, onReconnect, onDismiss)
    }
}

@Composable
private fun TransferPickerContent(controller: TransferController, visible: Boolean,
    requestedDirection: FileTransferDirection, onReconnect: () -> Unit, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pickerEpoch by remember { mutableIntStateOf(0) }
    val guard = remember(visible, pickerEpoch) { TransferPickerGuard(controller.sessionId).also { if (!visible) it.invalidate() } }
    DisposableEffect(guard) { onDispose { guard.invalidate() } }
    var pending by remember { mutableStateOf<PendingTransferPicker?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var direction by remember(visible, requestedDirection) { mutableStateOf(requestedDirection) }
    val currentlyVisible by rememberUpdatedState(visible)
    LaunchedEffect(visible) { if (visible) { message = null; controller.refreshCapability() } }

    fun consume(kind: TransferPickerGuard.Kind): TransferPickerGuard.Ticket? {
        val operation = pending
        pending = null
        if (operation == null || operation.ticket.kind != kind ||
            !operation.guard.consume(operation.ticket, controller.sessionId, RdpConnector.sessionGeneration()) || !currentlyVisible) {
            message = "文件选择已失效或连接已变化；未读取或导出所选内容，请重新选择。"
            return null
        }
        return operation.ticket
    }

    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (consume(TransferPickerGuard.Kind.IMPORT_FILES) != null) {
            if (uris.isEmpty()) message = "已取消选择文件；没有发送文件。"
            else scope.launch { controller.stageOutbound(uris) }
        }
    }
    val exportDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val ticket = consume(TransferPickerGuard.Kind.EXPORT_DIRECTORY)
        if (ticket != null) {
            if (uri == null) message = "已取消选择导出目录；暂存文件仍保留。"
            else scope.launch { controller.exportCompleted(requireNotNull(ticket.entryId), uri) }
        }
    }

    fun begin(kind: TransferPickerGuard.Kind, entryId: String? = null): Boolean {
        if (!visible || pending != null || RdpConnector.sessionGeneration() != controller.generation) {
            message = "文件选择器尚未返回，或连接已变化；请稍后重新操作。"
            return false
        }
        val ticket = guard.begin(controller.generation, kind, entryId) ?: return false
        pending = PendingTransferPicker(guard, ticket)
        message = null
        return true
    }
    fun pickerLaunchFailed() {
        pending?.let { it.guard.consume(it.ticket, controller.sessionId, controller.generation) }
        pending = null
        message = "无法打开系统文件选择器；未读取或导出任何文件。"
    }
    fun dismiss() {
        // Revokes only unconsumed picker consent. Already-confirmed protocol work is
        // session-owned and stays available for remote paste until Cancel/disconnect.
        guard.invalidate()
        onDismiss()
    }

    if (visible) FileTransferDialog(state, direction, pending != null, message,
        onDirection = { direction = it },
        onPickFiles = {
            if (begin(TransferPickerGuard.Kind.IMPORT_FILES)) {
                try { importFiles.launch(arrayOf("*/*")) } catch (_: Exception) { pickerLaunchFailed() }
            }
        },
        onPublish = { scope.launch { controller.publishOutbound() } },
        onReadOffer = { scope.launch { controller.requestReceiveOffer() } },
        onReceive = { scope.launch { controller.acceptReceive() } },
        onExport = { entryId ->
            if (begin(TransferPickerGuard.Kind.EXPORT_DIRECTORY, entryId)) {
                try { exportDirectory.launch(null) } catch (_: Exception) { pickerLaunchFailed() }
            }
        },
        onCancel = { guard.invalidate(); pickerEpoch++; controller.cancel() },
        onReconnect = { guard.invalidate(); onReconnect() },
        onDismiss = ::dismiss)
}
