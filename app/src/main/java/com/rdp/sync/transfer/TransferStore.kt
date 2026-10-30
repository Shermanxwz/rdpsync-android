package com.rdp.sync.transfer

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Private, opaque, regular-file-only session staging. No arbitrary path or credential directory APIs. */
class TransferStore private constructor(
    private val root: Path,
    val limits: TransferLimits,
    private val usableBytes: () -> Long
) : Closeable {
    private val lock = Any()
    private val records = linkedMapOf<String, StagedTransferFile>()
    private var bytesStored = 0L
    private var writer: TransferCancellation? = null
    private var readers = 0
    private val readersById = mutableMapOf<String, Int>()
    private var closed = false

    fun stage(name: String, declaredSize: Long?, openInput: () -> InputStream, cancellation: TransferCancellation,
              progress: (Long) -> Unit = {}): StagedTransferFile {
        TransferNames.validate(name, limits)
        if (declaredSize != null && (declaredSize < 0 || declaredSize > limits.maxFileBytes)) fail(TransferFailure.Reason.LIMIT)
        val id = UUID.randomUUID().toString()
        val part = root.resolve("$id.part")
        val completed = root.resolve("$id.bin")
        synchronized(lock) {
            ensureOpen(); verifyRoot()
            if (writer != null) fail(TransferFailure.Reason.BUSY)
            if (records.size >= limits.maxFiles || (declaredSize ?: 0) > limits.maxSessionBytes - bytesStored) fail(TransferFailure.Reason.LIMIT)
            requireSpace(0)
            writer = cancellation
        }
        try {
            cancellation.check()
            val digest = MessageDigest.getInstance("SHA-256")
            var actual = 0L
            Files.newByteChannel(part, setOf<java.nio.file.OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { output ->
                openInput().use { input ->
                    cancellation.onCancel { runCatching { input.close() } }.use {
                        val buffer = ByteArray(64 * 1024)
                        var emptyReads = 0
                        while (true) {
                            cancellation.check(); synchronized(lock) { ensureOpen() }
                            val count = input.read(buffer)
                            cancellation.check()
                            if (count < 0) break
                            if (count == 0) { if (++emptyReads > 16) fail(TransferFailure.Reason.IO); continue }
                            emptyReads = 0
                            if (count.toLong() > limits.maxFileBytes - actual) fail(TransferFailure.Reason.LIMIT)
                            if (declaredSize != null && count.toLong() > declaredSize - actual) fail(TransferFailure.Reason.SIZE_MISMATCH)
                            synchronized(lock) {
                                ensureOpen(); verifyRoot()
                                if (count.toLong() > limits.maxSessionBytes - bytesStored - actual) fail(TransferFailure.Reason.LIMIT)
                                requireSpace(count.toLong())
                            }
                            val chunk = java.nio.ByteBuffer.wrap(buffer, 0, count)
                            while (chunk.hasRemaining()) { cancellation.check(); output.write(chunk) }
                            digest.update(buffer, 0, count); actual += count
                            progress(actual)
                        }
                    }
                }
                if (declaredSize != null && actual != declaredSize) fail(TransferFailure.Reason.SIZE_MISMATCH)
                if (output is java.nio.channels.FileChannel) output.force(true)
            }
            cancellation.check()
            val result = StagedTransferFile(id, name, actual, digest.digest().joinToString("") { "%02x".format(it) })
            synchronized(lock) {
                ensureOpen(); verifyRoot()
                if (!Files.isRegularFile(part, NOFOLLOW_LINKS) || Files.exists(completed, NOFOLLOW_LINKS)) fail(TransferFailure.Reason.IO)
                Files.move(part, completed, ATOMIC_MOVE)
                records[id] = result; bytesStored += actual
            }
            return result
        } catch (error: Exception) {
            if (error is TransferFailure) throw error
            cancellation.check()
            throw TransferFailure(TransferFailure.Reason.IO)
        } finally {
            runCatching { if (Files.isDirectory(root, NOFOLLOW_LINKS)) Files.deleteIfExists(part) }
            synchronized(lock) { writer = null; cleanupIfClosed() }
        }
    }

    fun lease(id: String): TransferReadLease = synchronized(lock) {
        ensureOpen(); verifyRoot()
        val record = records[id] ?: fail(TransferFailure.Reason.STALE)
        val path = root.resolve("${record.id}.bin")
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != record.size) fail(TransferFailure.Reason.IO)
        val channel = try { Files.newByteChannel(path, setOf<java.nio.file.OpenOption>(READ, NOFOLLOW_LINKS)) }
            catch (_: Exception) { fail(TransferFailure.Reason.IO) }
        readers++
        readersById[id] = (readersById[id] ?: 0) + 1
        TransferReadLease(record, path, Channels.newInputStream(channel)) {
            synchronized(lock) {
                readers--
                val remaining = (readersById[id] ?: 1) - 1
                if (remaining == 0) readersById.remove(id) else readersById[id] = remaining
                cleanupIfClosed()
            }
        }
    }

    /** Only discard after native offer-retention acknowledgement and all related leases close. */
    fun discard(ids: Collection<String>) = synchronized(lock) {
        ensureOpen()
        if (writer != null || ids.any { (readersById[it] ?: 0) != 0 }) fail(TransferFailure.Reason.BUSY)
        verifyRoot()
        ids.forEach { id -> records[id]?.let { record ->
            Files.deleteIfExists(root.resolve("${record.id}.bin"))
            records.remove(id); bytesStored -= record.size
        } }
    }

    override fun close() {
        val active = synchronized(lock) { closed = true; writer }
        active?.cancel()
        synchronized(lock) { cleanupIfClosed() }
    }

    private fun requireSpace(next: Long) {
        val available = usableBytes()
        if (available < limits.freeSpaceReserveBytes || next > available - limits.freeSpaceReserveBytes) fail(TransferFailure.Reason.NO_SPACE)
    }
    private fun ensureOpen() { if (closed) fail(TransferFailure.Reason.CLOSED) }
    private fun verifyRoot() { if (!Files.isDirectory(root, NOFOLLOW_LINKS)) fail(TransferFailure.Reason.IO) }
    private fun cleanupIfClosed() {
        if (!closed || readers != 0 || writer != null) return
        // Even a same-UID replacement of our private root must not redirect cleanup outside it.
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
            runCatching { Files.deleteIfExists(root) } // Deletes a root symlink itself, never its target.
            records.clear(); bytesStored = 0
            synchronized(registryLock) { activeRoots.remove(root) }
            return
        }
        // Flat owned filenames only. Never recurse into a provider path or follow a symlink.
        records.keys.forEach { runCatching { Files.deleteIfExists(root.resolve("$it.bin")) } }
        records.clear(); bytesStored = 0
        runCatching { Files.deleteIfExists(root) }
        synchronized(registryLock) { activeRoots.remove(root) }
    }

    companion object {
        private val registryLock = Any()
        private val activeRoots = hashSetOf<Path>()
        private val rootPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val filePattern = Regex("[0-9a-f-]{36}\\.(bin|part)")
        fun create(cacheDirectory: File, limits: TransferLimits = TransferLimits(), usableBytes: (() -> Long)? = null): TransferStore = synchronized(registryLock) {
            val base = cacheDirectory.toPath().resolve("rdpsync-transfer")
            if (!Files.exists(base, NOFOLLOW_LINKS)) Files.createDirectory(base)
            if (!Files.isDirectory(base, NOFOLLOW_LINKS)) fail(TransferFailure.Reason.IO)
            // Only our flat UUID cache roots; all native handles from a previous process are already gone.
            Files.newDirectoryStream(base).use { dirs -> dirs.take(256).forEach { stale ->
                if (stale !in activeRoots && rootPattern.matches(stale.fileName.toString()) && Files.isDirectory(stale, NOFOLLOW_LINKS)) {
                    runCatching { Files.newDirectoryStream(stale).use { files -> files.take(128).forEach { file ->
                        if (filePattern.matches(file.fileName.toString()) && !Files.isDirectory(file, NOFOLLOW_LINKS)) Files.deleteIfExists(file)
                    } }; Files.deleteIfExists(stale) }
                }
            } }
            val root = Files.createDirectory(base.resolve(UUID.randomUUID().toString()))
            activeRoots.add(root)
            TransferStore(root, limits, usableBytes ?: { root.toFile().usableSpace })
        }
        private fun fail(reason: TransferFailure.Reason): Nothing = throw TransferFailure(reason)
    }
}

class TransferReadLease internal constructor(val file: StagedTransferFile, internal val path: Path,
                                            val input: InputStream, private val release: () -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) try { input.close() } finally { release() }
    }
}
