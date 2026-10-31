package com.rdp.sync.sharedspace

import com.rdp.sync.transfer.TransferLimits
import org.junit.Assert.*
import org.junit.Test

class SharedSpaceNamesTest {
    @Test fun unicodePolicyIsStrictNfcCaseCollisionAndUtf8FilesystemBounded() {
        assertEquals("É.TXT", SharedSpaceNames.nativeKey("é.txt"))
        assertEquals("SS.TXT", SharedSpaceNames.nativeKey("ß.txt"))
        assertEquals("SS.TXT", SharedSpaceNames.nativeKey("ss.txt"))
        assertEquals("你好😀.BIN", SharedSpaceNames.nativeKey("你好😀.bin"))
        listOf("e\u0301.txt", "a/b", "a\\b", "C:ads", ".", "..", "NUL.txt", "COM¹", "x ", "x.",
            "\ud800", "\udc00", "x\u0000", "x\u202eexe", "x\u2028name", "x\u2029name", "x\udb40\udc01", "界".repeat(86), "a".repeat(241))
            .forEach { assertNull(it, SharedSpaceNames.nativeKey(it)) }
        assertNotNull(SharedSpaceNames.nativeKey("界".repeat(85)))
    }
    @Test fun wildcardPolicyUsesTheSameUnicodeKeysWithoutAllowingPathOrDosWildcards() {
        assertEquals("É?.*", SharedSpaceNames.nativePatternKey("é?.*"))
        assertEquals("*.*", SharedSpaceNames.nativePatternKey("*.*"))
        assertNull(SharedSpaceNames.nativePatternKey("../*"))
        assertNull(SharedSpaceNames.nativePatternKey("<\""))
        assertNull(SharedSpaceNames.nativePatternKey("e\u0301*"))
    }
    @Test fun pathsAreRelativeBoundedAndNeverProviderOrPhonePaths() {
        assertEquals("", SharedSpaceNames.path(""))
        assertEquals("folder/你好.bin", SharedSpaceNames.child("folder", "你好.bin"))
        listOf("/a", "a/", "a//b", "a/../b", "content://provider/document/id", "C:\\x", (1..17).joinToString("/") { "a" })
            .forEach { value -> try { SharedSpaceNames.path(value); fail(value) } catch (e: SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.INVALID_NAME,e.reason) } }
    }
    @Test fun largerExplicitSharedSpaceCeilingsDoNotChangeClipboardDefaults() {
        val clipboard = TransferLimits()
        assertEquals(128L*1024*1024, clipboard.maxFileBytes)
        assertEquals(256L*1024*1024, clipboard.maxSessionBytes)
        val shared = TransferLimits(maxFileBytes=SHARED_SPACE_MAX_FILE_BYTES,maxSessionBytes=SHARED_SPACE_QUOTA_BYTES)
        assertEquals(512L*1024*1024, shared.maxFileBytes)
        assertEquals(1024L*1024*1024, shared.maxSessionBytes)
        try { TransferLimits(maxFileBytes=SHARED_SPACE_MAX_FILE_BYTES+1,maxSessionBytes=SHARED_SPACE_QUOTA_BYTES); fail() } catch (_: IllegalArgumentException) { }
        try { TransferLimits(maxSessionBytes=SHARED_SPACE_QUOTA_BYTES+1); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun nativeStatusesRemainActionableWithoutLeakingNativeDetails() {
        listOf(0xC0000043L to SharedSpaceFailure.Reason.BUSY, 0xC0000035L to SharedSpaceFailure.Reason.COLLISION,
            0xC000007FL to SharedSpaceFailure.Reason.NO_SPACE, 0xC0000120L to SharedSpaceFailure.Reason.CANCELLED,
            0xC0000022L to SharedSpaceFailure.Reason.PERMISSION, 0xC0000101L to SharedSpaceFailure.Reason.DIRECTORY_NOT_EMPTY)
            .forEach { (status, reason) -> try { requireSharedSpaceStatus(status.toInt()); fail() } catch(e: SharedSpaceFailure) { assertEquals(reason,e.reason); assertEquals(reason.name,e.message) } }
    }
}
