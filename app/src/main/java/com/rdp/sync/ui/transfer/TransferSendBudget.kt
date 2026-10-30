package com.rdp.sync.ui.transfer

import com.rdp.sync.transfer.TRANSFER_SEND_BUDGET_BYTES

/** UI preflight only; controller/native must check the live connection quota again. */
internal object TransferSendBudget {
    const val CONNECTION_BYTES = TRANSFER_SEND_BUDGET_BYTES

    fun fits(fileSizes: List<Long>, remainingBytes: Long?): Boolean {
        if (fileSizes.isEmpty() || remainingBytes == null || remainingBytes !in 0..CONNECTION_BYTES) return false
        var remaining = remainingBytes
        for (size in fileSizes) {
            if (size < 0 || size > remaining) return false
            remaining -= size
        }
        return true
    }
}
