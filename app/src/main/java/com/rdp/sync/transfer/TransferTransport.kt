package com.rdp.sync.transfer

import java.io.InputStream

/** All methods are called on IO workers. Implementations must enforce native generation under their lock. */
interface TransferTransport {
    val generation: Long
    fun isCurrent(): Boolean
    fun capability(): TransferCapability
    fun remainingSendBytes(): Long?
    /** Native duplicates readonly FDs; leases remain retained until offerRetained proves every duplicate closed. */
    fun publish(files: List<TransferReadLease>, cancellation: TransferCancellation): Long
    fun revoke(token: Long)
    fun offerRetained(token: Long): Boolean
    /** Explicit metadata request only. No body reads until openRemote after separate consent. */
    fun requestOffer(cancellation: TransferCancellation): RemoteTransferOffer
    fun openRemote(offer: RemoteTransferOffer, file: RemoteTransferFile, cancellation: TransferCancellation): InputStream
    fun finishReceive(token: Long)
}
