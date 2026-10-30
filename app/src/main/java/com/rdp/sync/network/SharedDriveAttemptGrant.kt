package com.rdp.sync.network

import java.util.concurrent.atomic.AtomicBoolean

/** One ephemeral connection attempt's authority. Only requestRevocation is UI-safe;
 * connect/revoke run on IO. Their short handoff is serialized so Stop cannot miss
 * a native generation being published by the first connect call. Native teardown
 * remains generation-bound; this gate never authorizes a replacement session. */
internal class SharedDriveAttemptGrant(private val requestedMode: SharedDriveMode) {
    private val revoked = AtomicBoolean(false)
    private val lock = Any()
    private var issuedGeneration = 0L
    private var issuedDrive = false

    fun requestRevocation() { revoked.set(true) }

    fun connect(operation: (SharedDriveMode) -> Pair<Boolean, Long>): Boolean = synchronized(lock) {
        val mode = if (revoked.get()) SharedDriveMode.OFF else requestedMode
        // If JNI fails after publishing but before returning its generation, do
        // not misreport a later Stop as confirmed. Full teardown still disconnects.
        issuedDrive = mode != SharedDriveMode.OFF
        issuedGeneration = 0
        val (result, generation) = operation(mode)
        issuedGeneration = generation.coerceAtLeast(0)
        result
    }

    fun revoke(operation: (Long) -> Boolean): Boolean {
        requestRevocation()
        return synchronized(lock) {
            if (!issuedDrive) true else if (issuedGeneration <= 0) false else operation(issuedGeneration)
        }
    }
}
