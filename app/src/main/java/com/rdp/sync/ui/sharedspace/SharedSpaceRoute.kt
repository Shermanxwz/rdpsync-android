package com.rdp.sync.ui.sharedspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.rdp.sync.R
import com.rdp.sync.sharedspace.*
import com.rdp.sync.transfer.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private enum class SpaceAction { IMPORT, EXPORT, NEW_FOLDER, RENAME, DELETE }
private data class PendingSpaceAction(val action: SpaceAction, val directory: String, val selection: SharedSpaceSelection)
private data class SpaceOperation(val selection: SharedSpaceSelection, val cancellation: SharedSpaceCancellation?)
private data class SpaceProgress(val cancellation: SharedSpaceCancellation, val progress: TransferProgress)

/** Picker/confirmation consent owns a pinned broker selection, never a path reopened later. */
@Composable
internal fun SharedSpaceRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context.applicationContext) { AndroidSharedSpace(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val alive = remember { AtomicBoolean(true) }
    var state by remember { mutableStateOf(SharedSpaceUiState()) }
    var picker by remember { mutableStateOf<PendingSpaceAction?>(null) }
    var edit by remember { mutableStateOf<PendingSpaceAction?>(null) }
    var operation by remember { mutableStateOf<SpaceOperation?>(null) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    val progress = remember { MutableStateFlow<SpaceProgress?>(null) }

    fun message(failure: Throwable): String = context.getString(when ((failure as? SharedSpaceFailure)?.reason) {
        SharedSpaceFailure.Reason.BUSY -> R.string.space_busy
        SharedSpaceFailure.Reason.INVALID_NAME -> R.string.space_bad_name
        SharedSpaceFailure.Reason.COLLISION -> R.string.space_collision
        SharedSpaceFailure.Reason.LIMIT -> R.string.space_limit
        SharedSpaceFailure.Reason.NO_SPACE -> R.string.space_no_space
        SharedSpaceFailure.Reason.CANCELLED -> R.string.space_cancelled
        SharedSpaceFailure.Reason.UNSUPPORTED -> R.string.space_unsupported
        SharedSpaceFailure.Reason.PERMISSION -> R.string.space_permission
        SharedSpaceFailure.Reason.NOT_FOUND -> R.string.space_not_found
        SharedSpaceFailure.Reason.DIRECTORY_NOT_EMPTY -> R.string.space_not_empty
        SharedSpaceFailure.Reason.UNSAFE_DESTINATION -> R.string.space_unsafe_destination
        SharedSpaceFailure.Reason.EXPORT_INCOMPLETE -> R.string.space_export_incomplete
        else -> R.string.space_io
    })
    fun closeSelection(selection: SharedSpaceSelection) {
        scope.launch(NonCancellable + Dispatchers.IO) { selection.close() }
    }
    fun cancelOperation() {
        val active = operation?.cancellation ?: return
        active.requestCancel()
        // Provider closure may cross Binder. Back/disposal must remain nonblocking.
        scope.launch(NonCancellable + Dispatchers.IO) { active.cancel() }
    }
    fun dismissEdit() { val pending = edit; edit = null; pending?.let { closeSelection(it.selection) } }
    fun dismissPicker() {
        val pending = picker
        picker = null
        state = state.copy(pickerPending = false)
        pending?.let { closeSelection(it.selection) }
    }
    DisposableEffect(alive) {
        onDispose {
            alive.set(false)
            dismissPicker(); dismissEdit(); cancelOperation()
            // The active operation closes its own pinned selection in finally.
        }
    }
    LaunchedEffect(progress) {
        progress.collect { update ->
            if (alive.get() && update != null && operation?.cancellation === update.cancellation)
                state = state.copy(progress = update.progress)
        }
    }
    LaunchedEffect(state.path, refreshEpoch) {
        val path = state.path
        state = state.copy(loading = true)
        try {
            val listing = withContext(Dispatchers.IO) { manager.list(path) }
            if (alive.get() && state.path == path) state = state.copy(listing = listing, loading = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (alive.get() && state.path == path) state = state.copy(loading = false, error = message(failure))
        }
    }
    fun consume(action: SpaceAction): PendingSpaceAction? {
        val pending = picker
        picker = null
        if (!alive.get() || pending == null || pending.action != action || state.working || pending.directory != state.path) {
            pending?.let { closeSelection(it.selection) }
            if (alive.get()) state = state.copy(pickerPending = false, error = context.getString(R.string.picker_stale))
            return null
        }
        state = state.copy(pickerPending = false)
        return pending
    }
    fun finish(active: SpaceOperation) {
        if (alive.get() && operation === active) {
            operation = null
            state = state.copy(working = false, cancellable = false, progress = null)
            refreshEpoch++
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val pending = consume(SpaceAction.IMPORT)
        if (pending != null) {
            if (uris.isEmpty()) {
                closeSelection(pending.selection)
                state = state.copy(notice = context.getString(R.string.space_picker_cancelled))
            } else {
                val cancellation = SharedSpaceCancellation()
                // Install the operation finally synchronously before yielding to IO.
                val active = SpaceOperation(pending.selection, cancellation)
                operation = active
                state = state.copy(working = true, cancellable = true, error = null, notice = null, progress = null)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            manager.importDocuments(uris.map { it.toString() }, pending.selection as SharedSpaceImportSelection, cancellation) {
                                progress.value = SpaceProgress(cancellation, it)
                            }
                        }
                        if (alive.get() && operation === active) state = state.copy(
                            notice = if (result.imported.isNotEmpty()) context.getString(R.string.space_imported, result.imported.size) else null,
                            error = result.failure?.let(::message))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (alive.get()) state = state.copy(error = message(failure)) }
                    finally {
                        withContext(NonCancellable + Dispatchers.IO) { try { cancellation.cancel() } finally { active.selection.close() } }
                        finish(active)
                    }
                }
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pending = consume(SpaceAction.EXPORT)
        if (pending != null) {
            if (uri == null) {
                closeSelection(pending.selection)
                state = state.copy(notice = context.getString(R.string.space_picker_cancelled))
            } else {
                val cancellation = SharedSpaceCancellation()
                // Install the operation finally synchronously before yielding to IO.
                val active = SpaceOperation(pending.selection, cancellation)
                operation = active
                state = state.copy(working = true, cancellable = true, error = null, notice = null, progress = null)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            manager.exportFile(pending.selection as SharedSpaceExportSelection, uri.toString(), cancellation) {
                                progress.value = SpaceProgress(cancellation, it)
                            }
                        }
                        if (alive.get() && operation === active) state = state.copy(
                            notice = if (result.readBackVerified) context.getString(R.string.space_exported) else null,
                            error = if (result.readBackVerified) null else context.getString(R.string.space_export_incomplete))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (alive.get()) state = state.copy(error = message(failure)) }
                    finally {
                        withContext(NonCancellable + Dispatchers.IO) { try { cancellation.cancel() } finally { active.selection.close() } }
                        finish(active)
                    }
                }
            }
        }
    }
    fun prepare(action: SpaceAction, path: String = state.path) {
        if (!alive.get() || state.working || picker != null || edit != null) return
        val directory = state.path
        state = state.copy(working = true, cancellable = false, error = null, notice = null, progress = null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Assign on IO before returning: a canceled withContext handoff must
            // not orphan a native selection that was successfully opened.
            var prepared: SharedSpaceSelection? = null
            try {
                withContext(Dispatchers.IO) {
                    prepared = when (action) {
                        SpaceAction.IMPORT, SpaceAction.NEW_FOLDER -> manager.prepareImport(path)
                        SpaceAction.EXPORT -> manager.prepareExport(path)
                        SpaceAction.RENAME, SpaceAction.DELETE -> manager.prepareMutation(path)
                    }
                }
                if (!alive.get() || directory != state.path) return@launch
                val request = PendingSpaceAction(action, directory, requireNotNull(prepared))
                if (action == SpaceAction.IMPORT || action == SpaceAction.EXPORT) {
                    picker = request
                    prepared = null // Ownership is now the pending picker, including launch failure.
                    state = state.copy(working = false, pickerPending = true)
                    try {
                        if (action == SpaceAction.IMPORT) importPicker.launch(arrayOf("*/*")) else exportPicker.launch(null)
                    } catch (_: Exception) {
                        dismissPicker()
                        state = state.copy(error = context.getString(R.string.space_picker_failed))
                    }
                } else { edit = request; prepared = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (alive.get()) state = state.copy(error = message(failure)) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { prepared?.close() }
                if (alive.get()) state = state.copy(working = false)
            }
        }
    }
    fun mutate(request: PendingSpaceAction, name: String? = null) {
        if (!alive.get() || edit !== request || state.working) return
        edit = null
        val active = SpaceOperation(request.selection, null)
        operation = active
        state = state.copy(working = true, cancellable = false, error = null, notice = null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val success = withContext(Dispatchers.IO) {
                    when (request.action) {
                        SpaceAction.NEW_FOLDER -> { manager.createDirectory(request.selection as SharedSpaceImportSelection, requireNotNull(name)); R.string.space_folder_created }
                        SpaceAction.RENAME -> { manager.rename(request.selection as SharedSpaceMutationSelection, requireNotNull(name)); R.string.space_renamed }
                        SpaceAction.DELETE -> { manager.delete(request.selection as SharedSpaceMutationSelection); R.string.space_deleted }
                        else -> throw SharedSpaceFailure(SharedSpaceFailure.Reason.UNSUPPORTED)
                    }
                }
                if (alive.get() && operation === active) state = state.copy(notice = context.getString(success))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (alive.get()) state = state.copy(error = message(failure)) }
            finally { withContext(NonCancellable + Dispatchers.IO) { active.selection.close() }; finish(active) }
        }
    }
    fun back() { dismissPicker(); dismissEdit(); cancelOperation(); onBack() }
    BackHandler(onBack = ::back)
    SharedSpaceScreen(state, onBack = ::back,
        onRefresh = { state = state.copy(error = null); refreshEpoch++ },
        onDirectory = { path -> if (!state.working && picker == null && edit == null) state = state.copy(path = path, listing = null, error = null, notice = null) },
        onImport = { prepare(SpaceAction.IMPORT) }, onExport = { prepare(SpaceAction.EXPORT, it) }, onCancel = ::cancelOperation,
        onCreateFolder = { prepare(SpaceAction.NEW_FOLDER) }, onRename = { prepare(SpaceAction.RENAME, it.relativePath) }, onDelete = { prepare(SpaceAction.DELETE, it.relativePath) })
    edit?.let { pending ->
        when (pending.action) {
            SpaceAction.NEW_FOLDER -> SharedSpaceNameDialog(null, pending.selection.relativePath, { mutate(pending, it) }, ::dismissEdit)
            SpaceAction.RENAME -> SharedSpaceNameDialog(pending.selection.relativePath.substringAfterLast('/'), pending.directory, { mutate(pending, it) }, ::dismissEdit)
            SpaceAction.DELETE -> SharedSpaceDeleteDialog(pending.selection.relativePath, { mutate(pending) }, ::dismissEdit)
            else -> Unit
        }
    }
}
