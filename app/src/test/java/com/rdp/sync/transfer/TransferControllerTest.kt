package com.rdp.sync.transfer

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TransferControllerTest {
    private val cache=Files.createTempDirectory("transfer-controller-test").toFile()
    private val gateway=FakeGateway()
    private val transport=FakeTransport()
    private val store=TransferStore.create(cache,TransferLimits(maxFiles=4,maxFileBytes=32,maxSessionBytes=64,freeSpaceReserveBytes=0)){1000}
    private val controller=TransferController(store,gateway,transport,"synthetic-attempt")
    @After fun cleanup()=runBlocking {
        transport.retained=false;controller.close()
        withTimeout(3000){while(cache.walkTopDown().any{it.isFile})delay(10)}
        cache.deleteRecursively()
        Unit
    }

    @Test fun stageRequiresSeparatePublishAndCopiesImmutableSnapshot()=runBlocking {
        gateway.put("source","a.bin",byteArrayOf(1,2,3))
        controller.stageOutboundReferences(listOf("source"))
        assertEquals(TransferPhase.STAGED,controller.state.value.phase);assertEquals(0,transport.publishes)
        gateway.values["source"]!!.bytes=byteArrayOf(9,9,9)
        controller.publishOutbound()
        assertEquals(1,transport.publishes);assertArrayEquals(byteArrayOf(1,2,3),transport.offeredBytes.single())
        assertEquals(TransferPhase.OFFERED,controller.state.value.phase);assertTrue(controller.state.value.cancellable)
    }

    @Test fun failedNewSelectionRetainsPriorCompleteSnapshotAndOffer()=runBlocking {
        gateway.put("good","a.bin",byteArrayOf(1,2))
        controller.stageOutboundReferences(listOf("good"));controller.publishOutbound()
        val previous=controller.state.value.outbound.single()
        gateway.put("bad","b.bin",byteArrayOf(1),declared=2)
        controller.stageOutboundReferences(listOf("bad"))
        assertEquals(previous,controller.state.value.outbound.single());assertTrue(transport.retained)
        assertEquals(0,transport.revocations);assertEquals(TransferPhase.OFFERED,controller.state.value.phase)
        assertNotNull(controller.state.value.error)
    }

    @Test fun failedNewBatchCleansOnlyItsFilesWhilePreviousOfferLeaseIsRetained()=runBlocking {
        gateway.put("good","a.bin",byteArrayOf(1,2))
        controller.stageOutboundReferences(listOf("good"));controller.publishOutbound()
        val previous=controller.state.value.outbound.single()
        gateway.put("new-first","b.bin",byteArrayOf(3))
        gateway.put("new-failed","c.bin",byteArrayOf(4),declared=2)
        controller.stageOutboundReferences(listOf("new-first","new-failed"))
        assertEquals(previous,controller.state.value.outbound.single());assertTrue(transport.retained)
        assertEquals(listOf("${previous.id}.bin"),cache.walkTopDown().filter{it.isFile}.map{it.name}.toList())
    }

    @Test fun receiveOfferIsMetadataOnlyUntilSeparateAcceptance()=runBlocking {
        controller.requestReceiveOffer()
        assertEquals(1,transport.listRequests);assertEquals(0,transport.bodyReads)
        assertEquals(TransferPhase.AWAITING_RECEIVE,controller.state.value.phase)
        controller.acceptReceive()
        assertEquals(1,transport.bodyReads);assertEquals(TransferPhase.RECEIVED,controller.state.value.phase)
        assertEquals(3L,controller.state.value.completed.single().size)
        assertEquals(1,transport.finished);assertNull(controller.state.value.remoteOffer)
    }

    @Test fun untrustedOfferNamesAndUnknownSizesRejectedBeforeAnyBodyRead()=runBlocking {
        transport.offer=RemoteTransferOffer(77,listOf(RemoteTransferFile(0,"../bad",3)))
        controller.requestReceiveOffer();assertEquals(0,transport.bodyReads);assertNull(controller.state.value.remoteOffer)
        transport.offer=RemoteTransferOffer(78,listOf(RemoteTransferFile(0,"okay",-1)))
        controller.requestReceiveOffer();assertEquals(0,transport.bodyReads);assertNull(controller.state.value.remoteOffer)
        assertEquals(2,transport.finished)
    }

    @Test fun shortReceiveNeverBecomesCompletedAndReleasesRemoteLock()=runBlocking {
        transport.offer=RemoteTransferOffer(77,listOf(RemoteTransferFile(0,"a",4)))
        controller.requestReceiveOffer();controller.acceptReceive()
        assertTrue(controller.state.value.completed.isEmpty());assertNotNull(controller.state.value.error)
        assertEquals(1,transport.finished);assertTrue(cache.walkTopDown().none{it.isFile})
    }

    @Test fun staleGenerationBlocksProviderReadAndRemoteMetadataRequest()=runBlocking {
        gateway.put("source","a",byteArrayOf(1));transport.current=false
        controller.stageOutboundReferences(listOf("source"));controller.requestReceiveOffer()
        assertEquals(0,gateway.reads);assertEquals(0,transport.listRequests);assertEquals(0,transport.publishes)
    }

    @Test fun closeRetainsSnapshotUntilNativeDuplicateReleaseAcknowledged()=runBlocking {
        gateway.put("source","a",byteArrayOf(1,2,3))
        controller.stageOutboundReferences(listOf("source"));controller.publishOutbound()
        transport.autoRelease=false
        controller.close()
        withTimeout(3000){while(transport.revocations==0)delay(10)}
        assertEquals(TransferPhase.CLOSED,controller.state.value.phase)
        assertTrue(cache.walkTopDown().any{it.isFile})
        transport.retained=false
        withTimeout(3000){while(cache.walkTopDown().any{it.isFile})delay(10)}
        assertTrue(cache.walkTopDown().none{it.isFile})
    }

    @Test fun outboundBudgetCheckedBeforeProviderReadAndAgainBeforePublishWithoutCancelReset()=runBlocking {
        gateway.put("source","a",byteArrayOf(1,2,3))
        transport.remaining=2
        controller.stageOutboundReferences(listOf("source"))
        assertEquals(0,gateway.reads);assertTrue(controller.state.value.outbound.isEmpty())
        transport.remaining=4
        controller.stageOutboundReferences(listOf("source"))
        assertEquals(1,gateway.reads)
        transport.remaining=2 // Remote repeated reads consumed budget after staging.
        controller.publishOutbound()
        assertEquals(0,transport.publishes);assertEquals(2L,controller.state.value.sendRemainingBytes)
        controller.cancel()
        withTimeout(3000){while(controller.state.value.phase!=TransferPhase.CANCELLED)delay(10)}
        controller.refreshCapability()
        assertEquals(2L,controller.state.value.sendRemainingBytes)
    }

    @Test fun unknownSizedOutboundInputCannotExceedRemainingConnectionBudget()=runBlocking {
        gateway.put("source","a",byteArrayOf(1,2,3),declared=null)
        transport.remaining=2
        controller.stageOutboundReferences(listOf("source"))
        assertEquals(1,gateway.reads);assertTrue(controller.state.value.outbound.isEmpty())
        assertEquals(TransferFailure.Reason.SEND_BUDGET.message,controller.state.value.error)
        assertTrue(cache.walkTopDown().none{it.isFile})
    }

    @Test fun cancelBetweenMutexAcquisitionAndRegistrationPreventsProviderRead()=runBlocking {
        val entered=CountDownLatch(1);val resume=CountDownLatch(1)
        val otherStore=TransferStore.create(cache,TransferLimits(maxFiles=4,maxFileBytes=32,maxSessionBytes=64,freeSpaceReserveBytes=0)){1000}
        val guarded=TransferController(otherStore,gateway,transport,"registration-race") {
            entered.countDown();check(resume.await(3,TimeUnit.SECONDS))
        }
        gateway.put("source","a",byteArrayOf(1))
        try {
            val task=launch(kotlinx.coroutines.Dispatchers.Default){guarded.stageOutboundReferences(listOf("source"))}
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            guarded.cancel();resume.countDown();task.join()
            withTimeout(3000){while(guarded.state.value.phase!=TransferPhase.CANCELLED)delay(10)}
            assertEquals(0,gateway.reads);assertEquals(0,transport.publishes)
        } finally {resume.countDown();guarded.close()}
    }

    private class FakeTransport:TransferTransport {
        override val generation=101L
        @Volatile var current=true
        @Volatile var retained=false
        @Volatile var autoRelease=true
        @Volatile var revocations=0
        var publishes=0;var listRequests=0;var bodyReads=0;var finished=0
        var offeredBytes=listOf<ByteArray>()
        var offer=RemoteTransferOffer(77,listOf(RemoteTransferFile(0,"synthetic.bin",3)))
        override fun isCurrent()=current
        override fun capability()=TransferCapability(true)
        var remaining=TRANSFER_SEND_BUDGET_BYTES
        override fun remainingSendBytes()=remaining
        override fun publish(files:List<TransferReadLease>,cancellation:TransferCancellation):Long {
            publishes++;offeredBytes=files.map{it.input.readBytes()};retained=true;return 55
        }
        override fun revoke(token:Long){revocations++;if(autoRelease)retained=false}
        override fun offerRetained(token:Long)=retained
        override fun requestOffer(cancellation:TransferCancellation):RemoteTransferOffer {listRequests++;return offer}
        override fun openRemote(offer:RemoteTransferOffer,file:RemoteTransferFile,cancellation:TransferCancellation):InputStream {bodyReads++;return ByteArrayInputStream(byteArrayOf(1,2,3))}
        override fun finishReceive(token:Long){finished++}
    }
}
