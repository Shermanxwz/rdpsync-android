package com.rdp.sync.transfer

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

const val TRANSFER_SEND_BUDGET_BYTES: Long = 32L * 1024 * 1024

/** Initial protocol contract is regular FILES only, never implicit folders or archives. */
data class TransferLimits(
    val maxFiles: Int = 64,
    val maxFileBytes: Long = 128L * 1024 * 1024,
    val maxSessionBytes: Long = 256L * 1024 * 1024,
    val freeSpaceReserveBytes: Long = 64L * 1024 * 1024,
    val maxNameUtf16Units: Int = 240,
    val maxDirectoryEntries: Int = 4096
) {
    init {
        require(maxFiles in 1..64 && maxFileBytes in 1..128L * 1024 * 1024)
        require(maxSessionBytes in maxFileBytes..256L * 1024 * 1024)
        require(freeSpaceReserveBytes >= 0 && maxNameUtf16Units in 1..240 && maxDirectoryEntries in 1..4096)
    }
}

data class StagedTransferFile(val id: String, val name: String, val size: Long, val sha256: String)
data class RemoteTransferFile(val index: Int, val name: String, val size: Long)
data class RemoteTransferOffer(val token: Long, val files: List<RemoteTransferFile>)
data class TransferCapability(val available: Boolean, val reason: String = "")
data class TransferProgress(val fileName: String = "", val bytes: Long = 0, val totalBytes: Long? = null)
enum class TransferPhase { IDLE, STAGING, STAGED, OFFERED, READING_OFFER, AWAITING_RECEIVE, RECEIVING, RECEIVED, EXPORTING, CANCELLED, CLOSED }
data class TransferState(
    val phase: TransferPhase = TransferPhase.IDLE,
    val capability: TransferCapability = TransferCapability(false, "正在检查文件传输支持"),
    val outbound: List<StagedTransferFile> = emptyList(),
    val completed: List<StagedTransferFile> = emptyList(),
    val remoteOffer: RemoteTransferOffer? = null,
    val progress: TransferProgress = TransferProgress(),
    val error: String? = null,
    val notice: String? = null,
    val sendRemainingBytes: Long? = null,
    val cancellable: Boolean = false
)

class TransferFailure(val reason: Reason) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        INVALID_NAME("文件名不安全或无法兼容远程系统，请重命名后重试。"),
        DUPLICATE_NAME("文件名重复（含大小写或 Unicode 等价名称），请重命名后重试。"),
        LIMIT("超出文件传输限制：最多 64 个文件、单个 128 MiB、会话暂存共 256 MiB。"),
        SEND_BUDGET("本次 RDP 连接剩余发送额度不足（累计最多 32 MiB，重复读取也计入）；取消不会重置，请自行重新连接后重试。"),
        NO_SPACE("暂存空间不足，请释放空间后重试（保留至少 64 MiB）。"),
        SIZE_MISMATCH("文件实际大小与声明不一致；未提供或导出不完整文件。"),
        CANCELLED("文件传输已取消；原始文件未修改。"),
        CLOSED("会话已结束，请重新选择文件。"),
        BUSY("另一项文件操作尚未完成，请稍后重试。"),
        UNSUPPORTED("此连接不支持文件剪贴板传输。"),
        FILES_ONLY("目前仅支持普通文件；文件夹、快捷方式或虚拟文档不受支持。"),
        STALE("远程文件列表或会话已变化，请重新获取并确认。"),
        IO("文件读写失败，原始文件与完整暂存副本未修改，请检查权限或空间。"),
        UNSAFE_DESTINATION("文档提供方无法确认安全的新建目标；未覆盖现有文件，但提供方可能已创建一个空文件。"),
        EXPORT_INCOMPLETE("导出未完成；暂存原件仍保留，目标目录可能残留一个新建的不完整副本。")
    }
}

/** Cooperative cancellation also closes provider streams / cancels Android CancellationSignals. */
class TransferCancellation {
    private val cancelled = AtomicBoolean(false)
    private val hooks = linkedSetOf<() -> Unit>()
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw TransferFailure(TransferFailure.Reason.CANCELLED) }
    fun onCancel(action: () -> Unit): Closeable {
        synchronized(hooks) { if (!cancelled.get()) hooks += action else runCatching(action) }
        return Closeable { synchronized(hooks) { hooks.remove(action) } }
    }
    /** Nonblocking flag for UI callers; IO owner then invokes cancel() to close resources. */
    fun requestCancel() { cancelled.set(true) }
    fun cancel() {
        cancelled.set(true)
        val callbacks = synchronized(hooks) { hooks.toList().also { hooks.clear() } }
        callbacks.forEach { runCatching(it) }
    }
}
