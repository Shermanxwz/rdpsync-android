package com.rdp.sync.security

import com.rdp.sync.data.Device
import java.util.Locale

/** Length-delimited identity prevents ':' collisions. Display names are deliberately excluded. */
fun Device.stableIdentity(): String = listOf(host.trim().lowercase(Locale.ROOT), port.toString(),
    username.trim(), domain.trim(),
    rdpServerName.trim().lowercase(Locale.ROOT)).joinToString("") { "${it.length}:$it" }
fun Device.credentialContext(): String = "rdpsync/device/v1/" + stableIdentity()
