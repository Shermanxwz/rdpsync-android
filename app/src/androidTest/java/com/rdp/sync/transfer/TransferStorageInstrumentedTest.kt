package com.rdp.sync.transfer

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.os.Build
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import com.rdp.sync.network.RdpConnector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransferStorageInstrumentedTest {
    private lateinit var authority:String
    private lateinit var provider:TransferFixtureDocumentsProvider
    private lateinit var store:TransferStore
    private lateinit var documents:TransferDocuments
    private lateinit var cache:java.io.File
    private val tree get()=DocumentsContract.buildTreeDocumentUri(authority,"root").toString()
    @Before fun setup() {
        assumeTrue(Build.VERSION.SDK_INT>=29) // ContentResolver.wrap is a framework test seam, not a production requirement.
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val base=instrumentation.targetContext
        @Suppress("DEPRECATION")
        val registered=base.packageManager.getPackageInfo(instrumentation.context.packageName,android.content.pm.PackageManager.GET_PROVIDERS)
            .providers!!.single {it.name==TransferFixtureDocumentsProvider::class.java.name}
        authority=registered.authority
        provider=TransferFixtureDocumentsProvider()
        provider.attachInfo(base,ProviderInfo().apply {
            authority=this@TransferStorageInstrumentedTest.authority;exported=true;grantUriPermissions=true
            readPermission="android.permission.MANAGE_DOCUMENTS";writePermission="android.permission.MANAGE_DOCUMENTS"
        })
        val resolver=ContentResolver.wrap(provider)
        val wrapper=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getContentResolver():ContentResolver=resolver
        }
        cache=java.io.File(base.cacheDir,"transfer-instrumented-${UUID.randomUUID()}").apply { check(mkdir()) }
        store=TransferStore.create(cache)
        documents=TransferDocuments(store,AndroidTransferDocuments(wrapper),{})
    }
    @After fun cleanup() {
        if(::store.isInitialized)store.close()
        if(::provider.isInitialized)provider.cleanup()
        if(::cache.isInitialized)cache.deleteRecursively()
    }
    private fun uri(id:String)=DocumentsContract.buildDocumentUri(authority,id).toString()

    @Test fun realFrameworkProviderImportExportExactSyntheticBytesAndHashes()=runBlocking {
        withContext(Dispatchers.IO) {
            val bytes=ByteArray(262_149){index->((index * 31 + 7) and 255).toByte()}
            provider.seed("source","synthetic.bin",bytes)
            provider.seed("original","keep.bin",byteArrayOf(9,8,7))
            val file=documents.stageSelected(listOf(uri("source")),TransferCancellation()).single()
            assertEquals(bytes.size.toLong(),file.size)
            val before=provider.ids().toSet()
            val result=documents.exportNewChild(file.id,tree,TransferCancellation())
            assertTrue(result.readBackVerified);assertEquals(file.sha256,result.sha256)
            val exported=(provider.ids().toSet()-before).single()
            assertArrayEquals(bytes,provider.bytes(exported));assertArrayEquals(byteArrayOf(9,8,7),provider.bytes("original"))
        }
    }
    @Test fun sameAuthorityOutsideTreeAndExistingDocumentAreNeverTruncated()=runBlocking {
        withContext(Dispatchers.IO) {
            provider.seed("source","a.bin",byteArrayOf(1,2));provider.seed("outside","outside.bin",byteArrayOf())
            provider.seed("original","keep.bin",byteArrayOf(9))
            val file=documents.stageSelected(listOf(uri("source")),TransferCancellation()).single()
            provider.forceOutsideChild=true
            expectUnsafe {documents.exportNewChild(file.id,tree,TransferCancellation())}
            assertEquals(0,provider.writes)
            provider.forceOutsideChild=false;provider.forceExisting=true
            expectUnsafe {documents.exportNewChild(file.id,tree,TransferCancellation())}
            assertEquals(0,provider.writes);assertArrayEquals(byteArrayOf(9),provider.bytes("original"))
            store.lease(file.id).use {assertArrayEquals(byteArrayOf(1,2),it.input.readBytes())}
        }
    }
    @Test fun nativeOwnedStagedFdIsReadOnlyCloseOnExecAndRejectsSymlinks()=runBlocking {
        withContext(Dispatchers.IO) {
            val base=InstrumentationRegistry.getInstrumentation().targetContext
            assertTrue(RdpConnector.initialize(base)) // Runtime only: no socket or RDP host connection.
            val direct=TransferStore.create(base.cacheDir)
            val outside=java.io.File(base.cacheDir,"transfer-fd-outside-${UUID.randomUUID()}").apply {writeBytes(byteArrayOf(9,9,9))}
            try {
                val file=direct.stage("fd-fixture.bin",3,{java.io.ByteArrayInputStream(byteArrayOf(1,2,3))},TransferCancellation())
                direct.lease(file.id).use { lease ->
                    val descriptor=AndroidStagedFiles.openReadOnly(lease)
                    val owned=descriptor.fileDescriptor
                    descriptor.use {
                        val flags=java.io.File("/proc/self/fdinfo/${it.fd}").readLines().single{line->line.startsWith("flags:")}
                            .substringAfter(':').trim().toLong(8)
                        // Linux UAPI O_CLOEXEC=02000000 is supported on Android8/API26 kernels;
                        // checking proc avoids Os.fcntlInt, whose PUBLIC Java API starts at30.
                        assertTrue(flags and 0x80000L != 0L)
                        assertEquals(OsConstants.O_RDONLY.toLong(),flags and OsConstants.O_ACCMODE.toLong())
                        val bytes=ByteArray(3)
                        assertEquals(3,Os.read(owned,bytes,0,bytes.size));assertArrayEquals(byteArrayOf(1,2,3),bytes)
                    }
                    assertFalse(owned.valid())
                    java.nio.file.Files.delete(lease.path)
                    java.nio.file.Files.createSymbolicLink(lease.path,outside.toPath())
                    try {AndroidStagedFiles.openReadOnly(lease).close();fail("symlink must not open")}catch(_:TransferFailure){ }
                    assertArrayEquals(byteArrayOf(9,9,9),outside.readBytes())
                }
            } finally {direct.close();outside.delete()}
        }
    }

    private fun expectUnsafe(block:()->Unit) {
        try {block();fail("unsafe destination must be refused")}catch(error:TransferFailure){assertEquals(TransferFailure.Reason.UNSAFE_DESTINATION,error.reason)}
    }
}
