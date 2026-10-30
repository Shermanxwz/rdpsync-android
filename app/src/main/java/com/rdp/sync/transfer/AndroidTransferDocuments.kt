package com.rdp.sync.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream

/** SAF only. No filesystem-path conversion, storage permission, persisted grants, or broad traversal. */
class AndroidTransferDocuments(context: Context) : TransferDocumentGateway {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override fun metadata(document: String, cancellation: TransferCancellation): TransferDocumentMetadata = withSignal(cancellation) { signal ->
        val uri = documentUri(document)
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS), null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst()) throw TransferFailure(TransferFailure.Reason.IO)
            val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: throw TransferFailure(TransferFailure.Reason.INVALID_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
            val size = if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn).takeIf { it >= 0 }
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS))
            TransferDocumentMetadata(name, size, mime == DocumentsContract.Document.MIME_TYPE_DIR,
                flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0)
        } ?: throw TransferFailure(TransferFailure.Reason.IO)
    }

    override fun openRead(document: String, cancellation: TransferCancellation): InputStream = withSignal(cancellation) { signal ->
        val fd = resolver.openFileDescriptor(documentUri(document), "r", signal) ?: throw TransferFailure(TransferFailure.Reason.IO)
        ParcelFileDescriptor.AutoCloseInputStream(fd)
    }

    override fun existingChildren(tree: String, maximum: Int, cancellation: TransferCancellation): TransferDirectorySnapshot = withSignal(cancellation) { signal ->
        val uri = treeUri(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val result = hashSetOf<String>()
        val names = hashSetOf<String>()
        var count = 0
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
            while (cursor.moveToNext()) {
                cancellation.check()
                if (++count > maximum) throw TransferFailure(TransferFailure.Reason.LIMIT)
                result += cursor.getString(0) ?: throw TransferFailure(TransferFailure.Reason.IO)
                names += cursor.getString(1) ?: throw TransferFailure(TransferFailure.Reason.IO)
            }
        } ?: throw TransferFailure(TransferFailure.Reason.IO)
        TransferDirectorySnapshot(result, names)
    }

    override fun createChild(tree: String, name: String, cancellation: TransferCancellation): String {
        cancellation.check()
        val uri = treeUri(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        return DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)?.toString()
            ?: throw TransferFailure(TransferFailure.Reason.IO)
    }

    override fun childId(tree: String, document: String, cancellation: TransferCancellation): String {
        val parent = treeUri(tree); val child = documentUri(document)
        if (parent.authority != child.authority) throw TransferFailure(TransferFailure.Reason.UNSAFE_DESTINATION)
        val parentId = DocumentsContract.getTreeDocumentId(parent)
        val childId = DocumentsContract.getDocumentId(child)
        val parentDocument = DocumentsContract.buildDocumentUriUsingTree(parent, parentId)
        if (childId == parentId) throw TransferFailure(TransferFailure.Reason.UNSAFE_DESTINATION)
        // We only create immediate children. Re-enumeration proves membership on API26-28 too;
        // isChildDocument became public in API29 and is an additional fail-closed check there.
        if (childId !in existingChildren(tree, 4096, cancellation).ids) throw TransferFailure(TransferFailure.Reason.UNSAFE_DESTINATION)
        if (Build.VERSION.SDK_INT >= 29 && !runCatching { DocumentsContract.isChildDocument(resolver, parentDocument, child) }.getOrDefault(false))
            throw TransferFailure(TransferFailure.Reason.UNSAFE_DESTINATION)
        cancellation.check()
        return childId
    }

    override fun openWrite(document: String, cancellation: TransferCancellation): OutputStream = withSignal(cancellation) { signal ->
        val fd = resolver.openFileDescriptor(documentUri(document), "w", signal) ?: throw TransferFailure(TransferFailure.Reason.IO)
        ParcelFileDescriptor.AutoCloseOutputStream(fd)
    }
    override fun deleteCreated(document: String): Boolean = DocumentsContract.deleteDocument(resolver, documentUri(document))

    private fun documentUri(value: String): Uri = Uri.parse(value).also {
        if (it.scheme != "content" || !DocumentsContract.isDocumentUri(context, it)) throw TransferFailure(TransferFailure.Reason.FILES_ONLY)
    }
    private fun treeUri(value: String): Uri = Uri.parse(value).also {
        if (it.scheme != "content" || !DocumentsContract.isTreeUri(it)) throw TransferFailure(TransferFailure.Reason.UNSAFE_DESTINATION)
    }
    private inline fun <T> withSignal(cancellation: TransferCancellation, block: (CancellationSignal) -> T): T {
        cancellation.check()
        val signal = CancellationSignal()
        return cancellation.onCancel { signal.cancel() }.use {
            val value = block(signal)
            try { cancellation.check(); value } catch (error: Exception) {
                if (value is java.io.Closeable) runCatching { value.close() }
                throw error
            }
        }
    }
}
