package com.rdp.sync.ui.rdp

import org.junit.Assert.*
import org.junit.Test

class RemoteImeBufferTest {
    @Test fun compositionStaysLocalUntilCommitAndDoesNotDuplicateOnFinish() {
        val text = mutableListOf<String>()
        val ime = RemoteImeBuffer(text::add) { fail("No keys") }
        ime.compose("ni"); ime.compose("你")
        assertTrue(text.isEmpty())
        ime.commit("你好"); ime.finish()
        assertEquals(listOf("你好"), text)
    }
    @Test fun backspaceWorksEvenWhenThereIsNoLocalEditableText() {
        val keys = mutableListOf<Int>()
        val ime = RemoteImeBuffer({}) { keys.add(it) }
        ime.deleteCodeUnits(2, 1)
        assertEquals(listOf(0x0E, 0x0E, 0xE053), keys)
    }
    @Test fun deletingUncommittedEmojiDoesNotSendRemoteBackspace() {
        val text = mutableListOf<String>()
        val ime = RemoteImeBuffer(text::add) { fail("No remote backspace before commit") }
        ime.compose("a😀"); ime.deleteCodePoints(1, 0); ime.finish()
        assertEquals(listOf("a"), text)
    }
    @Test fun utf16DeletionCountsEmojiAsTwoUnitsNotTwoCodePoints() {
        val text = mutableListOf<String>()
        val ime = RemoteImeBuffer(text::add) { fail("No remote backspace before commit") }
        ime.compose("a😀"); ime.deleteCodeUnits(2, 0); ime.finish()
        assertEquals(listOf("a"), text)
    }

}
