package com.rdp.sync.ui.transfer

import java.util.UUID

/** One external picker result belongs to one explicit dialog/connection attempt. */
internal class TransferPickerGuard(private val ownerId: String) {
    enum class Kind { IMPORT_FILES, EXPORT_DIRECTORY }
    data class Ticket internal constructor(
        val ownerId: String, val generation: Long, val sequence: Long, val kind: Kind, val entryId: String?, val scopeId: String
    )
    private val scopeId = UUID.randomUUID().toString()
    private var active = true
    private var sequence = 0L
    private var pending: Ticket? = null

    fun begin(generation: Long, kind: Kind, entryId: String? = null): Ticket? {
        if (!active || generation <= 0 || pending != null ||
            (kind == Kind.EXPORT_DIRECTORY && entryId.isNullOrBlank())) return null
        return Ticket(ownerId, generation, ++sequence, kind, entryId, scopeId).also { pending = it }
    }

    /** Consume once, even on mismatch. The controller also revalidates native identity. */
    fun consume(ticket: Ticket, currentOwnerId: String, currentGeneration: Long): Boolean {
        if (pending != ticket) return false
        pending = null
        return active && ticket.ownerId == currentOwnerId && ticket.generation == currentGeneration
    }

    fun invalidate() { active = false }
}
