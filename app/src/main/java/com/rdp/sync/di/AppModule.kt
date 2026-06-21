package com.rdp.sync.di

import com.rdp.sync.network.WebDavSyncService

object AppModule {
    val webDavSyncService = WebDavSyncService(
        webDavUrl = "http://clouddrive.230385.xyz",
        username = "rdpsync",
        password = "rdpsync"
    )
}
