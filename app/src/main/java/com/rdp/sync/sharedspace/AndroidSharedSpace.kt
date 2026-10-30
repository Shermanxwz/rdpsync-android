package com.rdp.sync.sharedspace

import android.content.Context
import com.rdp.sync.network.RdpConnector
import android.os.ParcelFileDescriptor
import com.rdp.sync.transfer.TransferReadLease
import com.rdp.sync.transfer.AndroidTransferDocuments
import com.rdp.sync.transfer.TransferDocuments
import com.rdp.sync.transfer.TransferExportResult
import com.rdp.sync.transfer.TransferFailure
import com.rdp.sync.transfer.TransferLimits
import com.rdp.sync.transfer.TransferProgress
import com.rdp.sync.transfer.TransferStore
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent app-private Shared space, not a selected-folder mirror.
 * All committed-file operations go through the one native broker/share table.
 * SAF is used only for explicit imports/exports, without persisted URI grants.
 * Methods are blocking and must be called on an IO dispatcher, never the UI. */
class AndroidSharedSpace(context: Context) {
    private val context = context.applicationContext
    private val limits = TransferLimits(maxFileBytes = SHARED_SPACE_MAX_FILE_BYTES,
        maxSessionBytes = SHARED_SPACE_QUOTA_BYTES, freeSpaceReserveBytes = SHARED_SPACE_RESERVE_BYTES)

    fun list(path: String = ""): SharedSpaceListing = safe {
        initialize(); SharedSpaceNames.path(path)
        val listing = RdpConnector.nativeSharedSpaceList(path)
        requireSharedSpaceStatus(listing.status)
        if (listing.entries.size > 4096) throw SharedSpaceFailure(SharedSpaceFailure.Reason.LIMIT)
        val used = hashSetOf<String>()
        val items = listing.entries.map { entry ->
            val key = SharedSpaceNames.nativeKey(entry.name) ?: throw SharedSpaceFailure(SharedSpaceFailure.Reason.INVALID_NAME)
            if (!used.add(key)) throw SharedSpaceFailure(SharedSpaceFailure.Reason.COLLISION)
            if (entry.size !in 0..SHARED_SPACE_MAX_FILE_BYTES) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
            SharedSpaceItem(entry.name, SharedSpaceNames.child(path, entry.name), entry.directory,
                entry.size, entry.modifiedMillis, entry.readOnly)
        }
        val space = RdpConnector.nativeSharedSpaceSpace()
        if (space.size != 4) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        requireSharedSpaceStatus(space[0].toInt())
        if (space[1] !in 0..SHARED_SPACE_QUOTA_BYTES || space[2] !in 0..SHARED_SPACE_QUOTA_BYTES || space[3] !in 0..4096)
            throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        SharedSpaceListing(path, items.sortedWith(compareBy<SharedSpaceItem> { !it.directory }.thenBy { it.name }), space[1], space[2])
    }

    fun prepareExport(relativePath: String): SharedSpaceExportSelection = safe {
        initialize(); SharedSpaceNames.path(relativePath)
        if (relativePath.isEmpty()) throw SharedSpaceFailure(SharedSpaceFailure.Reason.INVALID_NAME)
        val result = RdpConnector.nativeSharedSpaceOpenExport(relativePath)
        if (result.size != 3) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        requireSharedSpaceStatus(result[0].toInt())
        if (result[1] <= 0 || result[2] !in 0..SHARED_SPACE_MAX_FILE_BYTES) {
            if (result[1] > 0) RdpConnector.nativeSharedSpaceCloseExport(result[1])
            throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        }
        SharedSpaceExportSelection(relativePath, result[1], result[2])
    }
    fun prepareImport(parentPath: String): SharedSpaceImportSelection = safe {
        initialize(); SharedSpaceNames.path(parentPath)
        val result = RdpConnector.nativeSharedSpaceOpenImportDirectory(parentPath)
        if (result.size != 2) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        requireSharedSpaceStatus(result[0].toInt())
        if (result[1] <= 0) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        SharedSpaceImportSelection(parentPath, result[1])
    }
    fun prepareMutation(relativePath: String): SharedSpaceMutationSelection = safe {
        initialize(); SharedSpaceNames.path(relativePath)
        if (relativePath.isEmpty()) throw SharedSpaceFailure(SharedSpaceFailure.Reason.INVALID_NAME)
        val result = RdpConnector.nativeSharedSpaceOpenMutation(relativePath)
        if (result.size != 2) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        requireSharedSpaceStatus(result[0].toInt())
        if (result[1] <= 0) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        SharedSpaceMutationSelection(relativePath, result[1])
    }

    /** A batch may partially commit. Returned successes remain user data even if
     * a later file collides, storage fills, the UI leaves, or cancellation occurs. */
    fun importDocuments(documents: List<String>, parentPath: String, cancellation: SharedSpaceCancellation,
                        progress: (TransferProgress) -> Unit = {}): SharedSpaceImportResult =
        try { prepareImport(parentPath).use { importDocuments(documents, it, cancellation, progress) } }
        catch (failure: SharedSpaceFailure) { SharedSpaceImportResult(emptyList(), failure) }

    fun importDocuments(documents: List<String>, selection: SharedSpaceImportSelection, cancellation: SharedSpaceCancellation,
                        progress: (TransferProgress) -> Unit = {}): SharedSpaceImportResult {
        val parentPath = selection.relativePath
        val imported = mutableListOf<SharedSpaceItem>()
        if (!ioBusy.compareAndSet(false, true)) return SharedSpaceImportResult(emptyList(), SharedSpaceFailure(SharedSpaceFailure.Reason.BUSY))
        try {
            safe {
                initialize(); cancellation.check(); selection.requireOpen(); SharedSpaceNames.path(parentPath)
                registeredSharedSpaceImport(
                    begin = { RdpConnector.nativeSharedSpaceBeginImport(cancellation.token) },
                    checkCancelled = cancellation::check,
                    end = { RdpConnector.nativeSharedSpaceEndImport(cancellation.token) }
                ) {
                    TransferStore.create(context.cacheDir, limits).use { staging ->
                        val transfer = TransferDocuments(staging, AndroidTransferDocuments(context), cancellation::check)
                        val files = transfer.stageSelected(documents, cancellation.transfer, progress)
                        files.forEach { file ->
                            cancellation.check(); SharedSpaceNames.child(parentPath, file.name)
                            staging.lease(file.id).use { lease ->
                                openSharedSpaceStaged(lease).use { descriptor ->
                                    cancellation.check()
                                    requireSharedSpaceStatus(RdpConnector.nativeSharedSpaceImportToDirectory(
                                        descriptor.fd, file.size, selection.requireOpen(), file.name, cancellation.token))
                                }
                            }
                            // This records a successful publication, not a promise that a concurrent
                            // remote writer cannot subsequently modify/rename/delete the file.
                            imported += SharedSpaceItem(file.name, SharedSpaceNames.child(parentPath, file.name), false, file.size, 0)
                        }
                    }
                }
            }
            return SharedSpaceImportResult(imported.toList())
        } catch (failure: SharedSpaceFailure) {
            return SharedSpaceImportResult(imported.toList(), failure)
        } finally { ioBusy.set(false) }
    }

    /** Takes a broker read lease (denying writes/deletes), stages an immutable
     * snapshot, then exports a uniquely named new SAF child. Never writes back
     * to an import source; existing provider documents are never overwritten. */
    fun exportFile(relativePath: String, destinationTree: String, cancellation: SharedSpaceCancellation,
                   progress: (TransferProgress) -> Unit = {}): TransferExportResult =
        prepareExport(relativePath).use { exportFile(it, destinationTree, cancellation, progress) }

    fun exportFile(selection: SharedSpaceExportSelection, destinationTree: String, cancellation: SharedSpaceCancellation,
                   progress: (TransferProgress) -> Unit = {}): TransferExportResult = exclusive {
        initialize(); cancellation.check(); selection.requireOpen()
        val relativePath = selection.relativePath; val size = selection.size
        TransferStore.create(context.cacheDir, limits).use { staging ->
            val input = ExportInput(selection, cancellation)
            val file = input.use {
                staging.stage(relativePath.substringAfterLast('/'), size, { input }, cancellation.transfer) { count ->
                    cancellation.check(); progress(TransferProgress(relativePath.substringAfterLast('/'), count, size))
                }
            }
            cancellation.check()
            TransferDocuments(staging, AndroidTransferDocuments(context), cancellation::check)
                .exportNewChild(file.id, destinationTree, cancellation.transfer, progress)
        }
    }

    fun createDirectory(parentPath: String, name: String) = prepareImport(parentPath).use { createDirectory(it, name) }
    fun createDirectory(selection: SharedSpaceImportSelection, name: String) = safe {
        initialize(); SharedSpaceNames.child(selection.relativePath, name)
        requireSharedSpaceStatus(RdpConnector.nativeSharedSpaceMkdirSelection(selection.requireOpen(), name))
    }
    fun rename(relativePath: String, newName: String) = prepareMutation(relativePath).use { rename(it, newName) }
    fun rename(selection: SharedSpaceMutationSelection, newName: String) = safe {
        initialize()
        val parent = selection.relativePath.substringBeforeLast('/', "")
        requireSharedSpaceStatus(RdpConnector.nativeSharedSpaceRenameSelection(selection.requireOpen(), SharedSpaceNames.child(parent, newName)))
    }
    /** Explicit UI confirmation is required by the caller. Directories must be empty. */
    fun delete(relativePath: String) = prepareMutation(relativePath).use { delete(it) }
    fun delete(selection: SharedSpaceMutationSelection) = safe {
        initialize(); requireSharedSpaceStatus(RdpConnector.nativeSharedSpaceDeleteSelection(selection.requireOpen()))
        selection.closeChecked()
    }

    private fun openSharedSpaceStaged(lease: TransferReadLease): ParcelFileDescriptor {
        // Separate fixed 512 MiB accessor; the clipboard accessor remains 128 MiB.
        // Both use only native-anchored cache UUIDs, never provider/phone paths.
        val fd = RdpConnector.nativeSharedSpaceOpenStaged(lease.path.parent.fileName.toString(), lease.file.id, lease.file.size)
        if (fd < 0) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
        return ParcelFileDescriptor.adoptFd(fd)
    }

    private fun initialize() { if (!RdpConnector.initialize(context)) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO) }
    private inline fun <T> exclusive(block: () -> T): T {
        if (!ioBusy.compareAndSet(false, true)) throw SharedSpaceFailure(SharedSpaceFailure.Reason.BUSY)
        return try { safe(block) } finally { ioBusy.set(false) }
    }
    private inline fun <T> safe(block: () -> T): T = try { block() } catch (error: SharedSpaceFailure) { throw error }
        catch (error: TransferFailure) {
            throw SharedSpaceFailure(when (error.reason) {
                TransferFailure.Reason.INVALID_NAME -> SharedSpaceFailure.Reason.INVALID_NAME
                TransferFailure.Reason.FILES_ONLY -> SharedSpaceFailure.Reason.UNSUPPORTED
                TransferFailure.Reason.DUPLICATE_NAME -> SharedSpaceFailure.Reason.COLLISION
                TransferFailure.Reason.LIMIT -> SharedSpaceFailure.Reason.LIMIT
                TransferFailure.Reason.NO_SPACE -> SharedSpaceFailure.Reason.NO_SPACE
                TransferFailure.Reason.CANCELLED, TransferFailure.Reason.CLOSED -> SharedSpaceFailure.Reason.CANCELLED
                TransferFailure.Reason.BUSY -> SharedSpaceFailure.Reason.BUSY
                TransferFailure.Reason.UNSAFE_DESTINATION -> SharedSpaceFailure.Reason.UNSAFE_DESTINATION
                TransferFailure.Reason.EXPORT_INCOMPLETE -> SharedSpaceFailure.Reason.EXPORT_INCOMPLETE
                else -> SharedSpaceFailure.Reason.IO
            })
        } catch (_: SecurityException) { throw SharedSpaceFailure(SharedSpaceFailure.Reason.PERMISSION) }
        catch (_: Exception) { throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO) }

    private class ExportInput(private val selection: SharedSpaceExportSelection,
                              private val cancellation: SharedSpaceCancellation) : InputStream() {
        private val handle = selection.requireOpen()
        private val size = selection.size
        private val closed = AtomicBoolean(false)
        private var offset = 0L
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255 }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (start < 0 || length < 0 || start > buffer.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            cancellation.check()
            if (closed.get()) throw SharedSpaceFailure(SharedSpaceFailure.Reason.CANCELLED)
            if (offset == size) return -1
            val wanted = minOf(length.toLong(), size - offset, 1024L * 1024).toInt()
            val result = RdpConnector.nativeSharedSpaceReadExport(handle, offset, wanted)
            requireSharedSpaceStatus(result.status)
            if (result.data.isEmpty() || result.data.size > wanted) throw SharedSpaceFailure(SharedSpaceFailure.Reason.IO)
            cancellation.check()
            result.data.copyInto(buffer, start); offset += result.data.size
            return result.data.size
        }
        override fun close() { if (closed.compareAndSet(false, true)) selection.close() }
    }
    private companion object { val ioBusy = AtomicBoolean(false) }
}
