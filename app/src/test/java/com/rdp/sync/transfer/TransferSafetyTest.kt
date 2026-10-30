package com.rdp.sync.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TransferSafetyTest {
    private val cache = Files.createTempDirectory("rdpsync-transfer-test").toFile()
    private val stores = mutableListOf<TransferStore>()
    private fun store(maxFile: Long = 32, total: Long = 64, space: () -> Long = { 1000 }): TransferStore =
        TransferStore.create(cache, TransferLimits(maxFiles=4, maxFileBytes=maxFile, maxSessionBytes=total, freeSpaceReserveBytes=10), space).also { stores += it }
    @After fun cleanup() { stores.forEach { it.close() }; cache.deleteRecursively() }

    @Test fun rejectsTraversalReservedNamesControlsMalformedUtf16AndUnicodeAliases() {
        listOf("../x", "a/b", "a\\b", "C:x", ".", "..", "NUL.txt", "COM1", "LPT².log", "conout$", "a.", "a ", "x\u0000", "x\u202eexe", "\ud800", "\udc00", "e\u0301.txt", "x".repeat(241), "x\udb40\udc01").forEach {
            expect(TransferFailure.Reason.INVALID_NAME) { TransferNames.validate(it) }
        }
        assertEquals("你好😀.txt", TransferNames.validate("你好😀.txt"))
        listOf(listOf("A.txt", "a.txt"), listOf("ß.txt", "SS.txt")).forEach {
            expect(TransferFailure.Reason.DUPLICATE_NAME) { TransferNames.validateDistinct(it) }
        }
    }

    @Test fun immutableStageCopiesExactBytesComputesShaAndUsesOpaquePaths() {
        val storage = store(); val original = "synthetic-测试".toByteArray()
        val result = storage.stage("hello.txt", original.size.toLong(), { ByteArrayInputStream(original) }, TransferCancellation())
        assertEquals(hash(original), result.sha256)
        storage.lease(result.id).use { lease -> assertArrayEquals(original, lease.input.readBytes()) }
        val paths = cache.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf("${result.id}.bin"), paths)
        assertFalse(paths.any { it.contains("hello") })
    }

    @Test fun declaredSizeLiesAndUnknownOversizeRemoveAllPartialOutput() {
        val storage = store(maxFile=8,total=16)
        expect(TransferFailure.Reason.SIZE_MISMATCH) { storage.stage("a", 2, { ByteArrayInputStream(byteArrayOf(1,2,3)) }, TransferCancellation()) }
        expect(TransferFailure.Reason.SIZE_MISMATCH) { storage.stage("a", 4, { ByteArrayInputStream(byteArrayOf(1,2,3)) }, TransferCancellation()) }
        expect(TransferFailure.Reason.LIMIT) { storage.stage("a", null, { ByteArrayInputStream(ByteArray(9)) }, TransferCancellation()) }
        assertTrue(cache.walkTopDown().none { it.isFile })
    }

    @Test fun totalQuotaIncludesBothDirectionsAndReadonlyLeasedSnapshots() {
        val storage = store(maxFile=8,total=12)
        val outbound = storage.stage("out", 8, { ByteArrayInputStream(ByteArray(8)) }, TransferCancellation())
        storage.lease(outbound.id).use {
            expect(TransferFailure.Reason.LIMIT) { storage.stage("in", 5, { ByteArrayInputStream(ByteArray(5)) }, TransferCancellation()) }
            expect(TransferFailure.Reason.BUSY) { storage.discard(listOf(outbound.id)) }
        }
        storage.discard(listOf(outbound.id))
        assertEquals(8, storage.stage("in", 8, { ByteArrayInputStream(ByteArray(8)) }, TransferCancellation()).size)
    }

    @Test fun insufficientSpaceRejectsBeforeOpeningSourceAndRuntimeSpaceDropCleansPartial() {
        var opened = false
        val storage = store(space={9})
        expect(TransferFailure.Reason.NO_SPACE) { storage.stage("x", null, { opened=true; ByteArrayInputStream(byteArrayOf(1)) }, TransferCancellation()) }
        assertFalse(opened)
        var available = 100L
        val runtime = store(space={available})
        expect(TransferFailure.Reason.NO_SPACE) { runtime.stage("x", null, { available=10; ByteArrayInputStream(byteArrayOf(1)) }, TransferCancellation()) }
        assertTrue(cache.walkTopDown().none { it.isFile })
    }

    @Test fun cancellationAndProviderFailureNeverPromotePartialFiles() {
        val storage=store(); val cancellation=TransferCancellation()
        val stream=object : InputStream() { override fun read() = 1; override fun read(b:ByteArray,o:Int,l:Int):Int { cancellation.requestCancel(); b[o]=1; return 1 } }
        expect(TransferFailure.Reason.CANCELLED) { storage.stage("x", null, {stream}, cancellation) }
        expect(TransferFailure.Reason.IO) { storage.stage("x", null, { throw IOException("private-provider-location") }, TransferCancellation()) }
        assertTrue(cache.walkTopDown().none { it.isFile })
    }

    @Test fun closeDefersDeletionUntilLeaseReleaseAndDoesNotInvalidateOpenRead() {
        val storage=store(); val file=storage.stage("x", 3, {ByteArrayInputStream(byteArrayOf(1,2,3))}, TransferCancellation())
        val lease=storage.lease(file.id)
        storage.close()
        assertTrue(cache.walkTopDown().any { it.name == "${file.id}.bin" })
        assertArrayEquals(byteArrayOf(1,2,3),lease.input.readBytes())
        expect(TransferFailure.Reason.CLOSED) { storage.lease(file.id) }
        lease.close(); lease.close()
        assertTrue(cache.walkTopDown().none { it.isFile })
    }

    @Test fun symlinkStagedReplacementCannotExposeOutsideFile() {
        val storage=store(); val file=storage.stage("x", 3, {ByteArrayInputStream(byteArrayOf(1,2,3))}, TransferCancellation())
        val staged=cache.walkTopDown().single { it.name == "${file.id}.bin" }.toPath()
        val outside=Files.createTempFile(cache.parentFile.toPath(),"not-transfer-", ".txt")
        try {
            Files.write(outside,byteArrayOf(9,9,9)); Files.delete(staged); Files.createSymbolicLink(staged,outside)
            expect(TransferFailure.Reason.IO) { storage.lease(file.id) }
            storage.close(); assertArrayEquals(byteArrayOf(9,9,9),Files.readAllBytes(outside))
        } finally { Files.deleteIfExists(outside) }
    }

    @Test fun replacedRootSymlinkCannotRedirectCleanupOutsideSession() {
        val storage=store(); val file=storage.stage("x",1,{ByteArrayInputStream(byteArrayOf(1))},TransferCancellation())
        val root=cache.walkTopDown().single{it.name=="${file.id}.bin"}.parentFile.toPath()
        val outside=Files.createTempDirectory(cache.parentFile.toPath(),"outside-transfer-")
        val sentinel=outside.resolve("${file.id}.bin")
        try {
            Files.write(sentinel,byteArrayOf(9))
            Files.move(root,root.resolveSibling("moved-owned-root"))
            Files.createSymbolicLink(root,outside)
            storage.close()
            assertArrayEquals(byteArrayOf(9),Files.readAllBytes(sentinel))
            assertFalse(Files.exists(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally { Files.deleteIfExists(sentinel);Files.deleteIfExists(outside) }
    }

    @Test fun metadataOnlyValidationNeverOpensUnacceptedInputAndAtomicBatchRollback() {
        val gateway=FakeGateway(); gateway.put("one","same.txt",byteArrayOf(1)); gateway.put("two","SAME.txt",byteArrayOf(2))
        val documents=TransferDocuments(store(),gateway,{})
        expect(TransferFailure.Reason.DUPLICATE_NAME) { documents.stageSelected(listOf("one","two"),TransferCancellation()) }
        assertEquals(0,gateway.reads)
        gateway.put("two","other.txt",byteArrayOf(2),declared=2)
        expect(TransferFailure.Reason.SIZE_MISMATCH) { documents.stageSelected(listOf("one","two"),TransferCancellation()) }
        assertTrue(cache.walkTopDown().none { it.isFile })
    }

    @Test fun exportCreatesUniqueNewChildAndReadBackMatchesWithoutChangingOriginal() {
        val storage=store(); val bytes="synthetic".toByteArray(); val file=storage.stage("report.txt",bytes.size.toLong(),{ByteArrayInputStream(bytes)},TransferCancellation())
        val gateway=FakeGateway(); gateway.put("original","report.txt","original".toByteArray())
        val result=TransferDocuments(storage,gateway,{}).exportNewChild(file.id,"tree",TransferCancellation())
        assertTrue(result.readBackVerified); assertEquals(hash(bytes),result.sha256)
        assertArrayEquals("original".toByteArray(),gateway.values["original"]!!.bytes)
        assertTrue(gateway.values.values.any { it.name.contains("RdpSync-") && it.bytes.contentEquals(bytes) })
        storage.lease(file.id).use { assertArrayEquals(bytes,it.input.readBytes()) }
    }

    @Test fun existingUriOrNormalizedNameReturnedByProviderIsNeverOpenedOrDeleted() {
        val storage=store(); val file=storage.stage("a",1,{ByteArrayInputStream(byteArrayOf(1))},TransferCancellation())
        val gateway=FakeGateway(); gateway.put("original","existing.txt",byteArrayOf(9)); gateway.returnExisting=true
        expect(TransferFailure.Reason.UNSAFE_DESTINATION) { TransferDocuments(storage,gateway,{}).exportNewChild(file.id,"tree",TransferCancellation()) }
        assertEquals(0,gateway.writes); assertEquals(0,gateway.deletes)
        gateway.returnExisting=false; gateway.normalizeCreatedName="EXISTING.TXT"
        expect(TransferFailure.Reason.UNSAFE_DESTINATION) { TransferDocuments(storage,gateway,{}).exportNewChild(file.id,"tree",TransferCancellation()) }
        assertEquals(0,gateway.writes); assertEquals(0,gateway.deletes)
        assertArrayEquals(byteArrayOf(9),gateway.values["original"]!!.bytes)
    }

    @Test fun exportFailurePreservesStagedOriginalAndReportsUndeletablePartial() {
        val storage=store(); val file=storage.stage("a",3,{ByteArrayInputStream(byteArrayOf(1,2,3))},TransferCancellation())
        val gateway=FakeGateway(); gateway.failWrite=true; gateway.deleteAllowed=false
        expect(TransferFailure.Reason.EXPORT_INCOMPLETE) { TransferDocuments(storage,gateway,{}).exportNewChild(file.id,"tree",TransferCancellation()) }
        storage.lease(file.id).use { assertArrayEquals(byteArrayOf(1,2,3),it.input.readBytes()) }
    }

    @Test fun staleSessionPreventsAnyProviderReadOrExportCreation() {
        val gateway=FakeGateway(); gateway.put("source","a",byteArrayOf(1)); val storage=store()
        val docs=TransferDocuments(storage,gateway,{throw TransferFailure(TransferFailure.Reason.STALE)})
        expect(TransferFailure.Reason.STALE) { docs.stageSelected(listOf("source"),TransferCancellation()) }
        expect(TransferFailure.Reason.STALE) { docs.exportNewChild("missing","tree",TransferCancellation()) }
        assertEquals(0,gateway.reads); assertEquals(0,gateway.creates)
    }

    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun expect(reason:TransferFailure.Reason, block:()->Unit) {
        try { block(); fail("expected $reason") } catch(error:TransferFailure) { assertEquals(reason,error.reason); assertNull(error.cause) }
    }
}

internal class FakeGateway : TransferDocumentGateway {
    data class Value(val name:String,var bytes:ByteArray,val declared:Long?=bytes.size.toLong())
    val values=linkedMapOf<String,Value>(); var reads=0; var writes=0; var deletes=0; var creates=0
    var returnExisting=false; var normalizeCreatedName:String?=null; var failWrite=false; var deleteAllowed=true
    fun put(id:String,name:String,bytes:ByteArray,declared:Long?=bytes.size.toLong()) { values[id]=Value(name,bytes,declared) }
    override fun metadata(document:String,cancellation:TransferCancellation)=values.getValue(document).let {TransferDocumentMetadata(it.name,it.declared)}
    override fun openRead(document:String,cancellation:TransferCancellation):InputStream {reads++; return ByteArrayInputStream(values.getValue(document).bytes)}
    override fun existingChildren(tree:String,maximum:Int,cancellation:TransferCancellation)=TransferDirectorySnapshot(values.keys.toSet(),values.values.map{it.name}.toSet())
    override fun createChild(tree:String,name:String,cancellation:TransferCancellation):String {
        creates++; if(returnExisting)return "original"
        val id="new-$creates"; put(id,normalizeCreatedName?:name,byteArrayOf()); return id
    }
    override fun childId(tree:String,document:String,cancellation:TransferCancellation)=document
    override fun openWrite(document:String,cancellation:TransferCancellation):OutputStream {
        writes++
        return object:ByteArrayOutputStream() {
            override fun write(b:ByteArray,off:Int,len:Int) { if(failWrite)throw IOException("synthetic-provider-failure");super.write(b,off,len) }
            override fun close() {val old=values.getValue(document);values[document]=Value(old.name,toByteArray());super.close()}
        }
    }
    override fun deleteCreated(document:String):Boolean {deletes++;if(!deleteAllowed)return false;values.remove(document);return true}
}
