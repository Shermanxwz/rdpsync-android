package com.rdp.sync.manager

import com.rdp.sync.data.Device
import com.rdp.sync.network.SyncFile
import com.rdp.sync.network.WebDavSyncService
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SafeSyncTest {
    private val server = MockWebServer()
    private val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var getResponse = MockResponse().setResponseCode(404)
    private var uploaded = ""
    private val device = Device(id=7, name="Fixture", host="fixture.invalid", username="Alice", password="synthetic-secret")
    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += "${request.method} ${request.path}"
                return when (request.method) {
                    "PROPFIND" -> MockResponse().setResponseCode(207)
                    "GET" -> getResponse
                    "PUT" -> { uploaded = request.body.readUtf8(); MockResponse().setResponseCode(201) }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
        SyncManager.configureWebDav(server.url("/fixtures/").toString(), "fixture", "fake-webdav")
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun defaultUploadNeverSendsPasswordOrTouchesLegacyFile() {
        assertTrue(SyncManager.uploadDevices(listOf(device)).isSuccess)
        assertFalse(uploaded.contains("synthetic-secret"))
        assertFalse(JSONObject(uploaded).getJSONArray("devices").getJSONObject(0).has("password"))
        assertTrue(paths.contains("PUT /fixtures/rdpsync_profiles.json"))
        assertFalse(paths.any { it.contains("rdpsync_devices.json") })
    }
    @Test fun explicitLegacyUploadRemainsCompatibleAndOptIn() {
        assertTrue(SyncManager.uploadDevices(listOf(device), true).isSuccess)
        assertEquals("synthetic-secret", JSONObject(uploaded).getJSONArray("devices").getJSONObject(0).getString("password"))
        assertTrue(paths.contains("PUT /fixtures/rdpsync_devices.json"))
    }
    @Test fun missingProfileDownloadFailsWithoutFallbackOrEmptyReplacement() {
        val result = SyncManager.downloadDevices()
        assertTrue(result.isFailure); assertTrue(result.exceptionOrNull()!!.message!!.contains("旧版"))
        assertFalse(paths.any { it.contains("rdpsync_devices.json") || it.startsWith("PUT") })
    }
    @Test fun emptyAndMalformedFilesAreDistinguished() {
        getResponse = MockResponse().setBody("{\"devices\":[]}")
        assertEquals(emptyList<Device>(), SyncManager.downloadDevices().getOrThrow().devicesToStore)
        getResponse = MockResponse().setBody("{}")
        assertTrue(SyncManager.downloadDevices().isFailure)
        getResponse = MockResponse().setBody("not-json")
        assertTrue(SyncManager.downloadDevices().isFailure)
        assertFalse(paths.any { it.startsWith("PUT") })
    }
    @Test fun missingAndExplicitEmptyPasswordsRemainDifferentAndSafeModeIgnoresInjectedSecret() {
        val base = JSONObject().put("host", "fixture.invalid").put("username", "Alice")
        assertFalse(SyncManager.parseDevices(listOf(base), true).single().passwordProvided)
        val explicit = JSONObject(base.toString()).put("password", "")
        assertTrue(SyncManager.parseDevices(listOf(explicit), true).single().passwordProvided)
        val injected = JSONObject(base.toString()).put("password", "injected")
        val safe = SyncManager.parseDevices(listOf(injected), false).single()
        assertFalse(safe.passwordProvided); assertEquals("", safe.password)
    }
    @Test fun malformedMergeNeverOverwritesRemoteOrLocalAndMissingFileCreatesOnlySafeFile() {
        getResponse = MockResponse().setBody("{}")
        assertTrue(SyncManager.mergeDevices(listOf(device)).isFailure)
        assertFalse(paths.any { it.startsWith("PUT") })
        getResponse = MockResponse().setResponseCode(404)
        assertTrue(SyncManager.mergeDevices(listOf(device)).isSuccess)
        assertTrue(paths.contains("PUT /fixtures/rdpsync_profiles.json"))
        assertFalse(uploaded.contains("synthetic-secret"))
    }
    @Test fun lockedCredentialCannotBeExportedAsEmptyLegacyPassword() {
        val locked = device.copy(password="").apply { credentialUnavailable = true; passwordProvided = false }
        assertTrue(SyncManager.uploadDevices(listOf(locked), true).isFailure)
        assertFalse(paths.any { it.startsWith("PUT") })
        assertTrue(SyncManager.uploadDevices(listOf(locked), false).isSuccess)
    }
    @Test fun explicitLegacyDownloadDoesNotGetShadowedByProfilesFile() {
        getResponse = MockResponse().setBody(JSONArray().put(JSONObject().put("host", "fixture.invalid").put("username", "Alice").put("password", "new-secret")).toString())
        assertEquals("new-secret", SyncManager.downloadDevices(true).getOrThrow().devicesToStore!!.single().password)
        assertTrue(paths.contains("GET /fixtures/rdpsync_devices.json"))
        assertFalse(paths.any { it.contains("rdpsync_profiles.json") })
    }
    @Test fun fileSelectionNormalizesLegacyOrNewFileUrlsWithoutChangingRequestedMode() {
        assertEquals("https://fixture.invalid/x/rdpsync_profiles.json", WebDavSyncService.buildFileUrl("https://fixture.invalid/x/RDPSYNC_DEVICES.JSON", SyncFile.PROFILES))
        assertEquals("https://fixture.invalid/x/rdpsync_devices.json", WebDavSyncService.buildFileUrl("https://fixture.invalid/x/rdpsync_profiles.json", SyncFile.LEGACY_WITH_PASSWORDS))
    }
    @Test fun mergeIdentityDoesNotShareSecretsAcrossCaseSensitiveAccountsOrServerNames() {
        val variants = listOf(device.copy(username="alice"), device.copy(rdpServerName="other"))
        assertEquals(3, SyncPolicy.merge(listOf(device), variants).size)
        assertSame(device, SyncPolicy.merge(listOf(device), listOf(device.copy(password="remote"))).single())
    }
}
