package com.rdp.sync.network

/** A connection-scoped explicit grant. Merely opening Shared space grants nothing. */
enum class SharedDriveMode(val nativeValue: Int) {
    OFF(0), READ_ONLY(1), READ_WRITE(2)
}

enum class SharedDriveStatus(val nativeValue: Int) {
    OFF(0), REQUESTED(1), CAPABLE(2), ACCEPTED(3), USED(4),
    UNSUPPORTED(-1), REJECTED(-2), REVOKED(-3), TRANSPORT_UNAVAILABLE(-4),
    PROTOCOL_FAILED(-5), UNKNOWN(Int.MIN_VALUE)
}

/** Native schema v1. Caps/registration are NOT proof the server accepted the device. */
data class SharedDriveState(
    val generation: Long = 0,
    val mode: SharedDriveMode = SharedDriveMode.OFF,
    val status: SharedDriveStatus = SharedDriveStatus.UNKNOWN,
    val deviceId: Long = 0,
    val replyStatus: Long = 0,
    val readBytes: Long = 0,
    val writtenBytes: Long = 0,
    val pendingCount: Long = 0,
    val pendingInputBytes: Long = 0,
    /** -1 = closed/not connected; -2 = unsupported stack. Neither means drained. */
    val transportPending: Long = -1,
    val completedIrps: Long = 0,
    val peakTransportPending: Long = 0
) {
    val accepted: Boolean get() = status == SharedDriveStatus.ACCEPTED || status == SharedDriveStatus.USED
    val mayRevoke: Boolean get() = generation > 0 && mode != SharedDriveMode.OFF && status in setOf(
        SharedDriveStatus.REQUESTED, SharedDriveStatus.CAPABLE, SharedDriveStatus.ACCEPTED, SharedDriveStatus.USED)

    companion object {
        fun fromNative(value: LongArray): SharedDriveState {
            if (value.size != 13 || value[0] != 1L || value[1] < 0) return SharedDriveState()
            val mode = SharedDriveMode.entries.firstOrNull { it.nativeValue.toLong() == value[2] } ?: return SharedDriveState()
            val status = SharedDriveStatus.entries.firstOrNull { it != SharedDriveStatus.UNKNOWN && it.nativeValue.toLong() == value[3] }
                ?: return SharedDriveState()
            if (value[4] !in 0..0xffffffffL || value[5] !in 0..0xffffffffL ||
                value.sliceArray(6..9).any { it < 0 } || value[10] < -2 || value[11] < 0 || value[12] < 0 || value[8] > 32 || value[9] > 4L * 1024 * 1024)
                return SharedDriveState()
            if ((status == SharedDriveStatus.ACCEPTED || status == SharedDriveStatus.USED) &&
                (value[4] == 0L || value[5] != 0L)) return SharedDriveState()
            if ((mode == SharedDriveMode.OFF && status != SharedDriveStatus.OFF) ||
                (mode != SharedDriveMode.OFF && value[1] == 0L)) return SharedDriveState()
            return SharedDriveState(value[1], mode, status, value[4], value[5], value[6], value[7],
                value[8], value[9], value[10], value[11], value[12])
        }
    }
}

/** Never reuse an opted-in drive grant in an automatic new generation. A stop
 * can race an activation failure; only a fresh explicit reconnect may re-grant. */
internal fun permitsAutomaticActivationRetry(attemptDriveMode: SharedDriveMode): Boolean =
    attemptDriveMode == SharedDriveMode.OFF
