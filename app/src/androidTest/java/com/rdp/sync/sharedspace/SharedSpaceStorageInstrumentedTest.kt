package com.rdp.sync.sharedspace

import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.network.RdpConnector
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

/** Real cross-process provider and platform URI grants, not ContentResolver.wrap.
 * These synthetic owner-issued grants test storage/Binder, NOT picker consent;
 * the separate system-DocumentsUI/RDPDR fixture proves actual user selection. */
@RunWith(AndroidJUnit4::class)
class SharedSpaceStorageInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val owner get() = instrumentation.context
    private val target get() = instrumentation.targetContext
    private lateinit var authority: String
    private lateinit var folder: String
    private lateinit var manager: AndroidSharedSpace
    private val providerUri get() = Uri.parse("content://${authority.removeSuffix(".documents")}.control")
    private val tree get() = DocumentsContract.buildTreeDocumentUri(authority,"root")
    private val source get() = DocumentsContract.buildDocumentUriUsingTree(tree,"source").toString()
    @Before fun setup() {
        @Suppress("DEPRECATION")
        val registered = owner.packageManager.getPackageInfo(owner.packageName, android.content.pm.PackageManager.GET_PROVIDERS).providers!!
            .single { it.name == SharedSpaceFixtureDocumentsProvider::class.java.name }
        authority = registered.authority
        val state = control("fixture.reset")
        assertNotEquals("Fixture must be an actual separate Binder process", Process.myPid(), state.getInt("pid"))
        control("fixture.revoke")
        val denied = runCatching { target.contentResolver.openFileDescriptor(Uri.parse(source), "r")!!.close() }.exceptionOrNull()
        assertTrue("Source must be denied before the real tree grant", denied is SecurityException)
        control("fixture.grant")
        manager = AndroidSharedSpace(target)
        folder = "fixture-${UUID.randomUUID()}"
        manager.createDirectory("",folder)
    }
    @After fun cleanup() {
        if (::manager.isInitialized && ::folder.isInitialized) {
            runCatching { manager.list(folder).items.forEach { manager.delete(it.relativePath) } }
            runCatching { manager.delete(folder) }
        }
        if (::authority.isInitialized) control("fixture.revoke")
    }
    private fun control(method: String, extras: Bundle? = null): Bundle {
        // The test-only UID-gated trampoline runs as the actual provider owner.
        // No shell permission adoption is used or assumed for these grants.
        return target.contentResolver.call(providerUri,method,null,extras) ?: error("No fixture result")
    }
    private fun import(): SharedSpaceItem {
        val result = manager.importDocuments(listOf(source),folder,SharedSpaceCancellation())
        assertNull(result.failure); assertEquals(1,result.imported.size)
        return result.imported.single()
    }
    @Test fun realBinderSafImportAndUniqueExportHaveExactHashesAndPersistAcrossManagerInstances() {
        val item = import()
        assertEquals(131073L,item.size)
        assertTrue(AndroidSharedSpace(target).list(folder).items.any { it.name=="synthetic.bin" })
        val before = control("fixture.inspect").getStringArrayList("ids")!!.toSet()
        val result = manager.exportFile(item.relativePath,tree.toString(),SharedSpaceCancellation())
        assertTrue(result.readBackVerified)
        assertEquals(hash(SharedSpaceFixtureDocumentsProvider.payload()),result.sha256)
        val state = control("fixture.inspect")
        val exported = (state.getStringArrayList("ids")!!.toSet()-before).single()
        val bytes = target.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree,exported))!!.use { it.readBytes() }
        assertEquals(result.sha256,hash(bytes)); assertArrayEquals(byteArrayOf(9,8,7),state.getByteArray("original"))
    }
    @Test fun existingAndOutsideProviderReturnsAreNotWrittenAndCommittedSourceSurvives() {
        val item=import()
        listOf("existing","outside").forEach { option ->
            control("fixture.configure",Bundle().apply { putBoolean(option,true) })
            try { manager.exportFile(item.relativePath,tree.toString(),SharedSpaceCancellation()); fail("unsafe provider must be rejected") }
            catch (e:SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.UNSAFE_DESTINATION,e.reason) }
            val state=control("fixture.inspect"); assertEquals(0,state.getInt("writes")); assertArrayEquals(byteArrayOf(9,8,7),state.getByteArray("original"))
        }
        assertEquals(131073L,manager.list(folder).items.single().size)
    }
    @Test fun unknownSizeIsBoundedVirtualFilesAndRevokedGrantsFailWithoutNewWorkspaceData() {
        control("fixture.configure",Bundle().apply { putBoolean("unknownSize",true) })
        val imported=import(); manager.delete(imported.relativePath)
        control("fixture.configure",Bundle().apply { putBoolean("virtual",true) })
        assertEquals(SharedSpaceFailure.Reason.UNSUPPORTED,manager.importDocuments(listOf(source),folder,SharedSpaceCancellation()).failure?.reason)
        assertTrue(manager.list(folder).items.isEmpty())
        control("fixture.configure",Bundle())
        control("fixture.revoke")
        val denied = runCatching { target.contentResolver.openFileDescriptor(Uri.parse(source), "r")!!.close() }.exceptionOrNull()
        assertTrue("Source must be denied after grant revocation", denied is SecurityException)
        assertNotNull(manager.importDocuments(listOf(source),folder,SharedSpaceCancellation()).failure)
        assertTrue(manager.list(folder).items.isEmpty())
    }
    @Test fun cancellationAndUnicodeCaseAliasesDoNotOverwritePublishedData() {
        val cancellation=SharedSpaceCancellation(); cancellation.requestCancel()
        assertEquals(SharedSpaceFailure.Reason.CANCELLED,manager.importDocuments(listOf(source),folder,cancellation).failure?.reason)
        assertTrue(manager.list(folder).items.isEmpty())
        val item=import()
        manager.rename(item.relativePath,"É.bin")
        try { manager.createDirectory(folder,"é.BIN"); fail("Unicode alias must collide through real JNI policy") }
        catch(e:SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.COLLISION,e.reason) }
        assertEquals("É.bin",manager.list(folder).items.single().name)
        try { manager.createDirectory(folder,"e\u0301.bin"); fail("Non-NFC name must be rejected") }
        catch(e:SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.INVALID_NAME,e.reason) }
    }
    @Test fun nativeReadLeaseBlocksLocalDeleteAndRenameWithoutDisconnectingSession() {
        val item=import()
        val opened=RdpConnector.nativeSharedSpaceOpenExport(item.relativePath)
        assertEquals(0L,opened[0]); val handle=opened[1]
        try {
            try { manager.delete(item.relativePath); fail("active export must deny delete") } catch(e:SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.BUSY,e.reason) }
            try { manager.rename(item.relativePath,"renamed.bin"); fail("active export must deny rename") } catch(e:SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.BUSY,e.reason) }
            val read=RdpConnector.nativeSharedSpaceReadExport(handle,65536,128)
            assertEquals(0,read.status)
            assertArrayEquals(SharedSpaceFixtureDocumentsProvider.payload().copyOfRange(65536,65664),read.data)
        } finally { assertEquals(0,RdpConnector.nativeSharedSpaceCloseExport(handle)) }
        manager.rename(item.relativePath,"renamed.bin")
        assertEquals("renamed.bin",manager.list(folder).items.single().name)
    }
    @Test fun pickerSelectionsPinTheObjectAndParentAcrossRemoteStyleNamespaceChanges() {
        val item=import()
        manager.prepareExport(item.relativePath).use {
            try { manager.rename(item.relativePath,"replacement.bin"); fail("export picker lease must pin the selected file") }
            catch(e:SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.BUSY,e.reason) }
            try { manager.rename(folder,"moved-${UUID.randomUUID()}"); fail("an ancestor cannot move while export selection is held") }
            catch(e:SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.BUSY,e.reason) }
        }
        manager.prepareImport(folder).use { selection ->
            try { manager.rename(folder,"moved-${UUID.randomUUID()}"); fail("import picker lease must pin the destination directory") }
            catch(e:SharedSpaceFailure) { assertEquals(SharedSpaceFailure.Reason.BUSY,e.reason) }
            manager.createDirectory(selection,"selected-child")
        }
        manager.delete("$folder/selected-child")
    }
    @Test fun closedPickerSelectionNeverReopensAReplacementAtTheSamePath() {
        val selection=manager.prepareImport(folder)
        selection.close()
        val previous="$folder-old"
        manager.rename(folder,previous)
        try {
            manager.createDirectory("",folder)
            val result=manager.importDocuments(listOf(source),selection,SharedSpaceCancellation())
            assertEquals(SharedSpaceFailure.Reason.CANCELLED,result.failure?.reason)
            assertTrue(manager.list(folder).items.isEmpty()); assertTrue(manager.list(previous).items.isEmpty())
        } finally { manager.delete(previous) }
    }
    @Test fun dedicatedSharedSpaceStagedAccessorAllowsLargeSyntheticFileWithoutChangingClipboardCap() {
        val size=129L*1024*1024
        val limits=com.rdp.sync.transfer.TransferLimits(maxFileBytes=SHARED_SPACE_MAX_FILE_BYTES,maxSessionBytes=SHARED_SPACE_QUOTA_BYTES,freeSpaceReserveBytes=SHARED_SPACE_RESERVE_BYTES)
        com.rdp.sync.transfer.TransferStore.create(target.cacheDir,limits).use { staging ->
            val file=staging.stage("large-synthetic.bin",size,{
                object:java.io.InputStream() {
                    var left=size
                    override fun read():Int { if(left==0L)return -1;left--;return 0 }
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                        if(length==0)return 0
                        if(left==0L)return -1
                        val count=minOf(left,length.toLong()).toInt();java.util.Arrays.fill(bytes,offset,offset+count,0.toByte());left-=count;return count
                    }
                }
            },com.rdp.sync.transfer.TransferCancellation())
            staging.lease(file.id).use { lease ->
                val directory=lease.path.parent.fileName.toString()
                assertTrue(RdpConnector.nativeFileTransferOpenStaged(directory,file.id,size)<0)
                val fd=RdpConnector.nativeSharedSpaceOpenStaged(directory,file.id,size)
                assertTrue(fd>=0)
                android.os.ParcelFileDescriptor.adoptFd(fd).use { descriptor ->
                    val one=ByteArray(1)
                    assertEquals(1,android.system.Os.pread(descriptor.fileDescriptor,one,0,1,size-1))
                    assertEquals(0,one[0].toInt())
                }
                assertTrue(RdpConnector.nativeSharedSpaceOpenStaged(directory,file.id,SHARED_SPACE_MAX_FILE_BYTES+1)<0)
            }
        }
    }
    private fun hash(bytes:ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
