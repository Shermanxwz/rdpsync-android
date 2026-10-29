package com.rdp.sync.manager

import com.rdp.sync.data.Device
import com.rdp.sync.security.stableIdentity

object SyncPolicy {
    /** Local edits win in a merge. Download is a separate explicit remote-edit import. */
    fun merge(local: List<Device>, remote: List<Device>): List<Device> {
        val result = linkedMapOf<String, Device>()
        remote.forEach { result[it.stableIdentity()] = it }
        local.forEach { result[it.stableIdentity()] = it }
        return result.values.sortedWith(compareBy<Device> { it.name.lowercase() }.thenBy { it.host })
    }
}
