package com.rdp.sync.transfer

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Test-only provider: no user paths, no network, only generated fixture bytes in its own UUID cache. */
class TransferFixtureDocumentsProvider : DocumentsProvider() {
    lateinit var fixtureRoot: File
    private val files = linkedMapOf<String, Pair<String, File>>()
    var forceOutsideChild = false
    var forceExisting = false
    var writes = 0
    override fun onCreate(): Boolean {
        fixtureRoot = File(context!!.cacheDir, "transfer-provider-${UUID.randomUUID()}").apply { check(mkdir()) }
        return true
    }
    fun seed(id: String, name: String, bytes: ByteArray) {
        val file=File(fixtureRoot, UUID.randomUUID().toString()).apply { writeBytes(bytes) }
        files[id]=name to file
    }
    fun bytes(id: String) = files.getValue(id).second.readBytes()
    fun ids() = files.keys.toList()
    fun cleanup() { fixtureRoot.deleteRecursively() }

    override fun queryRoots(projection:Array<out String>?):Cursor {
        val columns=projection?:arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { addRow(columns.map { when(it) {
            Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID -> "root"
            Root.COLUMN_TITLE -> "RdpSync synthetic transfer fixture"
            Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE
            else -> null
        } }.toTypedArray()) }
    }
    override fun queryDocument(documentId:String,projection:Array<out String>?):Cursor = cursor(projection,listOf(documentId))
    override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?):Cursor {
        if(parentDocumentId!="root")throw FileNotFoundException()
        return cursor(projection,files.keys.filter { it!="outside" })
    }
    private fun cursor(projection:Array<out String>?,ids:List<String>):Cursor {
        val columns=projection?:arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_SIZE,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { ids.forEach { id ->
            val value=if(id=="root")null else files[id]?:throw FileNotFoundException()
            addRow(columns.map { column -> when(column) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> value?.first?:"RdpSync synthetic transfer fixture"
                Document.COLUMN_SIZE -> value?.second?.length()?:0L
                Document.COLUMN_MIME_TYPE -> if(id=="root")Document.MIME_TYPE_DIR else "application/octet-stream"
                Document.COLUMN_FLAGS -> if(id=="root")Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                else -> null
            } }.toTypedArray())
        } }
    }
    override fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {
        if(parentDocumentId!="root"||mimeType==Document.MIME_TYPE_DIR)throw FileNotFoundException()
        if(forceOutsideChild)return "outside"
        if(forceExisting)return "original"
        val id=UUID.randomUUID().toString();seed(id,displayName,byteArrayOf());return id
    }
    override fun isChildDocument(parentDocumentId:String,documentId:String)=parentDocumentId=="root"&&documentId!="outside"&&files.containsKey(documentId)
    override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file=files[documentId]?.second?:throw FileNotFoundException()
        if(mode.contains('w'))writes++
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
    }
    override fun deleteDocument(documentId:String) { val value=files.remove(documentId)?:throw FileNotFoundException();check(value.second.delete()) }
}
