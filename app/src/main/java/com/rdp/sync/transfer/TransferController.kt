package com.rdp.sync.transfer

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** One RDP attempt, not one dialog. Hiding UI does NOT revoke an explicitly published file offer. */
class TransferController internal constructor(
    private val store: TransferStore,
    private val gateway: TransferDocumentGateway,
    private val transport: TransferTransport,
    attemptNonce: String,
    private val beforeOperationRegistration: (() -> Unit)? = null
) : Closeable {
    val generation: Long = transport.generation
    val sessionId: String = "$attemptNonce:${UUID.randomUUID()}"
    private val closed = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val operation = Mutex()
    private val registrationLock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentCancellation: TransferCancellation? = null
    private var offeredToken: Long = 0
    private var offeredLeases: List<TransferReadLease> = emptyList()
    private val documents = TransferDocuments(store, gateway, ::checkSession)
    private val mutableState = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = mutableState.asStateFlow()

    suspend fun refreshCapability() = withContext(Dispatchers.IO) {
        if (!closed.get()) {
            val capability = transport.capability()
            val remaining = transport.remainingSendBytes()
            update { it.copy(capability = capability, sendRemainingBytes = remaining) }
        }
    }

    suspend fun stageOutbound(uris: List<Uri>) = stageOutboundReferences(uris.map(Uri::toString))

    internal suspend fun stageOutboundReferences(references: List<String>) = perform(TransferPhase.STAGING) { cancellation ->
        val previous = mutableState.value.outbound
        // Keep the previous complete snapshot/offer until the entire new selection succeeds.
        // Both generations count against quotas during this transaction.
        val remaining = remainingBudget()
        val staged = documents.stageSelected(references, cancellation, ::progress,
            maximumBatchBytes = remaining, budgetReason = TransferFailure.Reason.SEND_BUDGET)
        try {
            checkSession(); cancellation.check()
            releaseOffer()
            store.discard(previous.map { it.id })
            update { it.copy(phase = TransferPhase.STAGED, outbound = staged, notice = "已生成本机只读副本；尚未提供给远程端") }
        } catch (error: Exception) { runCatching { store.discard(staged.map { it.id }) }; throw error }
    }

    suspend fun publishOutbound() = perform(TransferPhase.STAGED) { cancellation ->
        checkCapability()
        val files = mutableState.value.outbound
        if (files.isEmpty()) throw TransferFailure(TransferFailure.Reason.FILES_ONLY)
        if (files.sumOf { it.size } > remainingBudget()) throw TransferFailure(TransferFailure.Reason.SEND_BUDGET)
        releaseOffer()
        val leases = mutableListOf<TransferReadLease>()
        try {
            files.forEach { leases += store.lease(it.id) }
            offeredLeases = leases.toList()
            offeredToken = transport.publish(offeredLeases, cancellation)
            checkSession(); cancellation.check()
            update { it.copy(phase = TransferPhase.OFFERED, notice = "已提供文件列表；请回到远程文件管理器粘贴。读取字节数不代表远程已保存。") }
        } catch (error: Exception) {
            if (offeredToken > 0) releaseOffer() else { leases.forEach { it.close() }; offeredLeases = emptyList() }
            throw error
        }
    }

    suspend fun requestReceiveOffer() = perform(TransferPhase.READING_OFFER) { cancellation ->
        checkCapability()
        mutableState.value.remoteOffer?.let { transport.finishReceive(it.token) }
        update { it.copy(remoteOffer = null) }
        val offer = transport.requestOffer(cancellation)
        try {
            validateOffer(offer)
            checkSession(); cancellation.check()
            update { it.copy(phase = TransferPhase.AWAITING_RECEIVE, remoteOffer = offer,
                notice = "仅获取文件列表，尚未读取内容；确认后才下载到本机暂存区") }
        } catch (error: Exception) { transport.finishReceive(offer.token); throw error }
    }

    suspend fun acceptReceive() = perform(TransferPhase.RECEIVING) { cancellation ->
        val offer = mutableState.value.remoteOffer ?: throw TransferFailure(TransferFailure.Reason.STALE)
        validateOffer(offer)
        val staged = mutableListOf<StagedTransferFile>()
        try {
            offer.files.forEach { file ->
                checkSession(); cancellation.check()
                staged += store.stage(file.name, file.size, { checkSession(); transport.openRemote(offer, file, cancellation) }, cancellation) {
                    progress(TransferProgress(file.name, it, file.size))
                }
            }
            checkSession(); cancellation.check()
            update { it.copy(phase = TransferPhase.RECEIVED, completed = it.completed + staged,
                remoteOffer = null, notice = "已下载并计算本机 SHA-256；请选择目标目录导出。远程校验声明不作为可信完整性证明。") }
        } catch (error: Exception) {
            runCatching { store.discard(staged.map { it.id }) }
            throw error
        } finally { transport.finishReceive(offer.token); update { it.copy(remoteOffer = null) } }
    }

    suspend fun exportCompleted(entryId: String, treeUri: Uri) = perform(TransferPhase.EXPORTING) { cancellation ->
        if (mutableState.value.completed.none { it.id == entryId }) throw TransferFailure(TransferFailure.Reason.STALE)
        documents.exportNewChild(entryId, treeUri.toString(), cancellation, ::progress)
        update { it.copy(phase = TransferPhase.RECEIVED, notice = "已创建新文件并回读校验 SHA-256；暂存原件仍保留。云端持久化由文档提供方负责。") }
    }

    /** Explicit stop-sharing/cancel; does not erase successfully received staged originals. */
    fun cancel() {
        if (!stopping.compareAndSet(false, true)) return
        val current = synchronized(registrationLock) { currentCancellation.also { it?.requestCancel() } }
        cleanupScope.launch {
            current?.cancel()
            operation.lock()
            try {
                releaseOffer()
                mutableState.value.remoteOffer?.let { transport.finishReceive(it.token) }
                update { it.copy(phase = TransferPhase.CANCELLED, remoteOffer = null, cancellable = false, notice = "已停止共享或取消传输；完整暂存文件保留至会话结束") }
            } finally { stopping.set(false); operation.unlock() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val current = currentCancellation
        current?.requestCancel()
        mutableState.update { it.copy(phase = TransferPhase.CLOSED, cancellable = false) }
        cleanupScope.launch {
            current?.cancel()
            operation.lock()
            try {
                releaseOffer()
                mutableState.value.remoteOffer?.let { transport.finishReceive(it.token) }
            } finally { store.close(); operation.unlock() }
        }
    }

    private suspend fun perform(phase: TransferPhase, block: suspend (TransferCancellation) -> Unit) = withContext(Dispatchers.IO) {
        if (stopping.get() || !operation.tryLock()) { update { it.copy(error = TransferFailure.Reason.BUSY.message) }; return@withContext }
        val cancellation = TransferCancellation()
        try {
            beforeOperationRegistration?.invoke()
            synchronized(registrationLock) {
                currentCancellation = cancellation
                if (stopping.get() || closed.get()) cancellation.requestCancel()
            }
            cancellation.check()
            checkSession()
            update { it.copy(phase = phase, error = null, notice = null, cancellable = true, progress = TransferProgress()) }
            block(cancellation)
        } catch (error: Exception) {
            val failure = (error as? TransferFailure)?.reason ?: TransferFailure.Reason.IO
            update { it.copy(phase = when {
                offeredToken > 0 -> TransferPhase.OFFERED
                it.remoteOffer != null -> TransferPhase.AWAITING_RECEIVE
                it.outbound.isNotEmpty() -> TransferPhase.STAGED
                it.completed.isNotEmpty() -> TransferPhase.RECEIVED
                failure == TransferFailure.Reason.CANCELLED -> TransferPhase.CANCELLED
                else -> TransferPhase.IDLE
            }, error = failure.message) }
        } finally {
            synchronized(registrationLock) { currentCancellation = null }
            update { it.copy(cancellable = offeredToken > 0 || it.remoteOffer != null) }
            operation.unlock()
        }
    }

    private suspend fun releaseOffer() {
        if (offeredToken > 0) {
            transport.revoke(offeredToken)
            // No timeout-based deletion: retained native FDs must be positively acknowledged closed.
            while (transport.offerRetained(offeredToken)) delay(25)
            offeredToken = 0
        }
        offeredLeases.forEach { it.close() }; offeredLeases = emptyList()
    }
    private fun validateOffer(offer: RemoteTransferOffer) {
        if (offer.token <= 0 || offer.files.isEmpty()) throw TransferFailure(TransferFailure.Reason.FILES_ONLY)
        TransferNames.validateDistinct(offer.files.map { it.name }, store.limits)
        var total = 0L
        offer.files.forEachIndexed { index, file ->
            if (file.index != index || file.size < 0 || file.size > store.limits.maxFileBytes || file.size > store.limits.maxSessionBytes - total)
                throw TransferFailure(TransferFailure.Reason.LIMIT)
            total += file.size
        }
    }
    private fun remainingBudget(): Long {
        checkSession()
        val remaining = transport.remainingSendBytes() ?: throw TransferFailure(TransferFailure.Reason.UNSUPPORTED)
        update { it.copy(sendRemainingBytes = remaining) }
        return remaining
    }
    private fun checkCapability() { checkSession(); if (!transport.capability().available) throw TransferFailure(TransferFailure.Reason.UNSUPPORTED) }
    private fun checkSession() {
        if (closed.get()) throw TransferFailure(TransferFailure.Reason.CLOSED)
        if (!transport.isCurrent()) throw TransferFailure(TransferFailure.Reason.STALE)
    }
    private var lastProgressNanos = 0L
    private fun progress(value: TransferProgress) {
        checkSession()
        val now = System.nanoTime()
        if (now - lastProgressNanos >= 100_000_000L || value.bytes == value.totalBytes || mutableState.value.progress.fileName != value.fileName) {
            lastProgressNanos = now
            update { it.copy(progress = value) }
        }
    }
    private fun update(block: (TransferState) -> TransferState) { if (!closed.get()) mutableState.update(block) }

    companion object {
        fun create(context: Context, generation: Long, attemptNonce: String): TransferController {
            require(generation > 0)
            return TransferController(TransferStore.create(context.applicationContext.cacheDir),
                AndroidTransferDocuments(context), RdpFileTransferTransport(generation), attemptNonce)
        }
    }
}
