package com.rdp.sync.transfer

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Provider identifiers are opaque handles, not filesystem paths. Implementations must never log them. */
data class TransferDocumentMetadata(val name: String, val size: Long?, val directory: Boolean = false, val virtual: Boolean = false)
data class TransferDirectorySnapshot(val ids: Set<String>, val names: Set<String>)
interface TransferDocumentGateway {
    fun metadata(document: String, cancellation: TransferCancellation): TransferDocumentMetadata
    fun openRead(document: String, cancellation: TransferCancellation): InputStream
    fun existingChildren(tree: String, maximum: Int, cancellation: TransferCancellation): TransferDirectorySnapshot
    fun createChild(tree: String, name: String, cancellation: TransferCancellation): String
    fun childId(tree: String, document: String, cancellation: TransferCancellation): String
    fun openWrite(document: String, cancellation: TransferCancellation): OutputStream
    fun deleteCreated(document: String): Boolean
}
data class TransferExportResult(val size: Long, val sha256: String, val readBackVerified: Boolean)

class TransferDocuments(private val store: TransferStore, private val gateway: TransferDocumentGateway,
                        private val checkSession: () -> Unit) {
    fun stageSelected(documents: List<String>, cancellation: TransferCancellation,
                      progress: (TransferProgress) -> Unit = {},
                      maximumBatchBytes: Long = store.limits.maxSessionBytes,
                      budgetReason: TransferFailure.Reason = TransferFailure.Reason.LIMIT): List<StagedTransferFile> {
        checkSession(); cancellation.check()
        if (documents.isEmpty() || documents.size > store.limits.maxFiles || documents.toSet().size != documents.size)
            throw TransferFailure(TransferFailure.Reason.LIMIT)
        val metadata = documents.map { document ->
            checkSession(); cancellation.check()
            gateway.metadata(document, cancellation).also {
                if (it.directory || it.virtual) throw TransferFailure(TransferFailure.Reason.FILES_ONLY)
                if (it.size != null && (it.size < 0 || it.size > store.limits.maxFileBytes)) throw TransferFailure(TransferFailure.Reason.LIMIT)
            }
        }
        TransferNames.validateDistinct(metadata.map { it.name }, store.limits)
        if (maximumBatchBytes < 0) throw TransferFailure(budgetReason)
        var known = 0L
        metadata.forEach { if (it.size != null) {
            if (it.size > store.limits.maxSessionBytes - known) throw TransferFailure(TransferFailure.Reason.LIMIT)
            if (it.size > maximumBatchBytes - known) throw TransferFailure(budgetReason)
            known += it.size
        } }
        val staged = mutableListOf<StagedTransferFile>()
        try {
            documents.zip(metadata).forEach { (document, info) ->
                checkSession(); cancellation.check()
                val remaining = maximumBatchBytes - staged.sumOf { it.size }
                staged += store.stage(info.name, info.size, {
                    checkSession(); cancellation.check()
                    boundedInput(gateway.openRead(document, cancellation), remaining, budgetReason)
                }, cancellation) { bytes -> progress(TransferProgress(info.name, bytes, info.size)) }
            }
            return staged.toList()
        } catch (error: Exception) {
            runCatching { store.discard(staged.map { it.id }) }
            if (error is TransferFailure) throw error
            cancellation.check(); throw TransferFailure(TransferFailure.Reason.IO)
        }
    }

    private fun boundedInput(input: InputStream, limit: Long, reason: TransferFailure.Reason): InputStream = object : java.io.FilterInputStream(input) {
        private var readBytes = 0L
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = `in`.read(bytes, offset, minOf(length.toLong(), limit - readBytes + 1).coerceAtLeast(1).toInt())
            if (count > 0) {
                if (count.toLong() > limit - readBytes) throw TransferFailure(reason)
                readBytes += count
            }
            return count
        }
    }

    /** Tree selection grants only an export destination. We CREATE a unique child, never write a selected existing URI. */
    fun exportNewChild(fileId: String, tree: String, cancellation: TransferCancellation,
                       progress: (TransferProgress) -> Unit = {}): TransferExportResult {
        checkSession(); cancellation.check()
        var created: String? = null
        var verifiedNew = false
        var existingReturned = false
        var creationAttempted = false
        try {
            store.lease(fileId).use { lease ->
                checkSession()
                val before = gateway.existingChildren(tree, store.limits.maxDirectoryEntries, cancellation)
                checkSession(); cancellation.check()
                val suffix = " (RdpSync-${UUID.randomUUID().toString().take(8)})"
                val name = lease.file.name
                val dot = name.lastIndexOf('.').takeIf { it > 0 }
                val extension = if (dot == null) "" else name.substring(dot).take(24)
                val stem = if (dot == null) name else name.substring(0, dot)
                val generated = safeTruncate(stem, store.limits.maxNameUtf16Units - suffix.length - extension.length) + suffix + extension
                TransferNames.validate(generated, store.limits)
                creationAttempted = true
                created = gateway.createChild(tree, generated, cancellation)
                val createdId = gateway.childId(tree, created!!, cancellation)
                if (createdId in before.ids) { existingReturned = true; throw TransferFailure(TransferFailure.Reason.UNSAFE_DESTINATION) }
                checkSession(); cancellation.check()
                val meta = gateway.metadata(created!!, cancellation)
                TransferNames.validate(meta.name, store.limits)
                if (TransferNames.collisionKey(meta.name) in before.names.map(TransferNames::collisionKey).toSet()) {
                    existingReturned = true; throw TransferFailure(TransferFailure.Reason.UNSAFE_DESTINATION)
                }
                if (meta.directory || meta.virtual || meta.size != 0L) throw TransferFailure(TransferFailure.Reason.UNSAFE_DESTINATION)
                verifiedNew = true
                checkSession(); cancellation.check()
                gateway.openWrite(created!!, cancellation).use { output ->
                    cancellation.onCancel { runCatching { output.close() } }.use {
                        val hash = copyExact(lease.input, output, lease.file.size, cancellation) { bytes ->
                            checkSession(); progress(TransferProgress(lease.file.name, bytes, lease.file.size))
                        }
                        if (hash != lease.file.sha256) throw TransferFailure(TransferFailure.Reason.IO)
                        output.flush()
                    }
                }
                checkSession(); cancellation.check()
                // Successful close is not proof of cloud durability. Read-back verifies only provider-visible bytes.
                gateway.openRead(created!!, cancellation).use { input ->
                    cancellation.onCancel { runCatching { input.close() } }.use {
                        val hash = copyExact(input, null, lease.file.size, cancellation) { checkSession() }
                        if (hash != lease.file.sha256) throw TransferFailure(TransferFailure.Reason.IO)
                    }
                }
                return TransferExportResult(lease.file.size, lease.file.sha256, readBackVerified = true)
            }
        } catch (error: Exception) {
            if (verifiedNew && created != null && !runCatching { gateway.deleteCreated(created!!) }.getOrDefault(false))
                throw TransferFailure(TransferFailure.Reason.EXPORT_INCOMPLETE)
            if (error is TransferFailure && error.reason == TransferFailure.Reason.UNSAFE_DESTINATION) throw error
            if (creationAttempted && !verifiedNew && !existingReturned) throw TransferFailure(TransferFailure.Reason.EXPORT_INCOMPLETE)
            if (error is TransferFailure) throw error
            cancellation.check(); throw TransferFailure(TransferFailure.Reason.IO)
        }
    }

    private fun safeTruncate(value: String, length: Int): String {
        var end = minOf(value.length, length)
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end).ifEmpty { "file" }
    }

    private fun copyExact(input: InputStream, output: OutputStream?, expected: Long, cancellation: TransferCancellation,
                          progress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L; var emptyReads = 0
        val buffer = ByteArray(64 * 1024)
        while (true) {
            cancellation.check()
            val count = input.read(buffer)
            cancellation.check()
            if (count < 0) break
            if (count == 0) { if (++emptyReads > 16) throw TransferFailure(TransferFailure.Reason.IO); continue }
            emptyReads = 0
            if (count.toLong() > expected - total) throw TransferFailure(TransferFailure.Reason.SIZE_MISMATCH)
            output?.write(buffer, 0, count); digest.update(buffer, 0, count); total += count
            progress(total)
        }
        if (total != expected) throw TransferFailure(TransferFailure.Reason.SIZE_MISMATCH)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
