package com.rdp.sync.transfer

import android.os.ParcelFileDescriptor
import com.rdp.sync.network.RdpConnector

/** API26-safe ownership transfer; no Java dup() window that would drop FD_CLOEXEC. */
internal object AndroidStagedFiles {
    fun openReadOnly(lease: TransferReadLease): ParcelFileDescriptor {
        // Native derives the ONLY allowed root from its initialized private cache. These two
        // identifiers are generated UUIDs, never a SAF URI, display name, or peer filesystem path.
        // It uses openat(O_NOFOLLOW|O_CLOEXEC), validates private owner/regular file/exact size,
        // and returns one owned fd. adoptFd transfers ownership without another dup().
        val fd = RdpConnector.nativeFileTransferOpenStaged(
            lease.path.parent.fileName.toString(), lease.file.id, lease.file.size)
        if (fd < 0) throw TransferFailure(if (fd == -5) TransferFailure.Reason.FILES_ONLY else TransferFailure.Reason.IO)
        return ParcelFileDescriptor.adoptFd(fd) // fd 0 is valid; caller closes exactly this owner.
    }
}
