package com.rdp.sync.ui.transfer

import org.junit.Assert.*
import org.junit.Test

class TransferPickerGuardTest {
    @Test fun matchingExplicitSelectionIsConsumedOnlyOnce() {
        val guard = TransferPickerGuard("attempt-a")
        val ticket = requireNotNull(guard.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
        assertTrue(guard.consume(ticket, "attempt-a", 7))
        assertFalse(guard.consume(ticket, "attempt-a", 7))
    }

    @Test fun newNativeSessionCannotUseOldSelectionEvenForSameProfile() {
        val guard = TransferPickerGuard("attempt-a")
        val ticket = requireNotNull(guard.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
        assertFalse(guard.consume(ticket, "attempt-a", 8))
        assertFalse(guard.consume(ticket, "attempt-a", 7))
    }

    @Test fun newAttemptCannotUseOldSelectionEvenWithMatchingGeneration() {
        val guard = TransferPickerGuard("attempt-a")
        val ticket = requireNotNull(guard.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
        assertFalse(guard.consume(ticket, "attempt-b", 7))
    }

    @Test fun dialogCloseInvalidatesInFlightPickerBeforeBackgroundWork() {
        val guard = TransferPickerGuard("attempt-a")
        val ticket = requireNotNull(guard.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
        guard.invalidate()
        assertFalse(guard.consume(ticket, "attempt-a", 7))
        assertNull(guard.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
    }

    @Test fun onlyOnePickerCanBeOutstandingAndDisconnectedCannotLaunch() {
        val guard = TransferPickerGuard("attempt-a")
        assertNull(guard.begin(0, TransferPickerGuard.Kind.IMPORT_FILES))
        val first = requireNotNull(guard.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
        assertNull(guard.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
        assertTrue(guard.consume(first, "attempt-a", 7))
        assertNotNull(guard.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
    }

    @Test fun exportTicketBindsExactCompletedEntry() {
        val guard = TransferPickerGuard("attempt-a")
        assertNull(guard.begin(7, TransferPickerGuard.Kind.EXPORT_DIRECTORY))
        val ticket = requireNotNull(guard.begin(7, TransferPickerGuard.Kind.EXPORT_DIRECTORY, "entry-3"))
        assertEquals("entry-3", ticket.entryId)
        assertEquals(TransferPickerGuard.Kind.EXPORT_DIRECTORY, ticket.kind)
        assertTrue(guard.consume(ticket, "attempt-a", 7))
    }

    @Test fun reopeningDialogCannotConfuseTwoOtherwiseIdenticalTickets() {
        val old = TransferPickerGuard("attempt-a")
        val current = TransferPickerGuard("attempt-a")
        val oldTicket = requireNotNull(old.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
        val currentTicket = requireNotNull(current.begin(7, TransferPickerGuard.Kind.IMPORT_FILES))
        assertFalse(current.consume(oldTicket, "attempt-a", 7))
        assertTrue(current.consume(currentTicket, "attempt-a", 7))
    }

}
