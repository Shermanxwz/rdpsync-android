package com.rdp.sync.ui.transfer

import org.junit.Assert.*
import org.junit.Test

class TransferSendBudgetTest {
    @Test fun cumulativeBatchMustFitActualRemainingNotPerFileMaximum() {
        assertTrue(TransferSendBudget.fits(listOf(10, 20), 30))
        assertFalse(TransferSendBudget.fits(listOf(10, 20), 29))
        assertFalse(TransferSendBudget.fits(listOf(33L * 1024 * 1024), TransferSendBudget.CONNECTION_BYTES))
    }
    @Test fun unknownNegativeOrImpossibleBudgetFailsClosed() {
        assertFalse(TransferSendBudget.fits(listOf(1), null))
        assertFalse(TransferSendBudget.fits(listOf(1), -1))
        assertFalse(TransferSendBudget.fits(listOf(1), TransferSendBudget.CONNECTION_BYTES + 1))
    }
    @Test fun EmptyAndMalformedListsCannotWrapIntoAnAllowedTotal() {
        assertFalse(TransferSendBudget.fits(emptyList(), 10))
        assertFalse(TransferSendBudget.fits(listOf(-1, 2), 10))
        assertFalse(TransferSendBudget.fits(listOf(Long.MAX_VALUE, Long.MAX_VALUE), 10))
    }
    @Test fun ZeroLengthFilesCostNoPayloadButNonemptyPayloadNeedsBudget() {
        assertTrue(TransferSendBudget.fits(listOf(0), 0))
        assertFalse(TransferSendBudget.fits(listOf(1), 0))
    }
}
