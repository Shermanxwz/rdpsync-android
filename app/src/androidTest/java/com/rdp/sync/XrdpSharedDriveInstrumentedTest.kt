package com.rdp.sync

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.data.Device
import com.rdp.sync.network.RdpConnector
import com.rdp.sync.network.SharedDriveMode
import com.rdp.sync.network.SharedDriveStatus
import com.rdp.sync.ui.MainActivity
import com.rdp.sync.ui.rdp.RdpDesktopView
import com.rdp.sync.ui.screens.RdpConnectionScreen
import com.rdp.sync.ui.theme.RdpSyncTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Actual RDPDR filesystem + product consent/SAF, independent of CLIPRDR and Windows UI. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class XrdpSharedDriveInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = StandardTestDispatcher())
    private val boundedScroll by lazy { BoundedFixtureScroll(compose, measurements) }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val evidence by lazy { File(context.getExternalFilesDir(null), "shared-drive-evidence").apply { mkdirs() } }
    private val measurements = JSONObject()
    private val operations = JSONArray()
    private val nativeStates = JSONArray()
    private var coachmarkAcknowledged = false
    private var paused = false
    private var browserView: RdpDesktopView? = null
    private var browserServer: JSONObject? = null
    private lateinit var documents: SharedSpaceDocumentsUi
    private fun label(id: Int, vararg values: Any) = context.getString(id, *values)

    @Test fun actualMappedDriveProductConsentSafBytesAndBoundedTransport() {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("xrdpFileFixture"))
        val fingerprint = requireNotNull(args.getString("fixtureFingerprint"))
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(RdpConnector.initialize(context))
        measurements.put("source_commit", args.getString("sourceCommit"))
            .put("scope", "Owned xrdp actual RDPDR/FUSE; not Windows Explorer/Office or CLIPRDR")
            .put("started_epoch_ms", System.currentTimeMillis()).put("operations", operations).put("native_states", nativeStates)
            .put("clipboard_used", false).put("windows_os_covered", false).put("saf_ui_covered", true)
        val oldService = automation.serviceInfo
        val showing = mutableStateOf(true)
        var originals: Map<String,File> = emptyMap()
        var primary: Throwable? = null
        try {
            documents = SharedSpaceDocumentsUi(compose, evidence, measurements)
            originals = prepareOriginalFiles()
            automation.serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes = oldService.eventTypes; feedbackType = oldService.feedbackType; notificationTimeout = oldService.notificationTimeout
                flags = oldService.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            context.getSharedPreferences("session_preferences", Context.MODE_PRIVATE).edit().clear()
                .putString("resolution_mode", "PROFILE").putString("display_quality", "SHARP").commit()
            val device = Device(id = 940001L, name = "Owned RDPDR synthetic drive", host = "10.0.2.2", port = 3391,
                username = "rdpfixture", password = "rdpsync-fixture-only", width = 1024, height = 768)
            compose.runOnUiThread { compose.activity.setContent { boundedScroll.Bind(); RdpSyncTheme { if (showing.value) RdpConnectionScreen(device) { showing.value = false } } } }
            trustFirstCertificate(fingerprint)
            awaitFrame()
            val offGeneration = RdpConnector.sessionGeneration()
            assertEquals(SharedDriveMode.OFF, RdpConnector.sharedDriveState().mode)
            assertClipboardOff()
            documents.seedSources(originals)
            openMenu(R.string.space_title)
            scrollBrowserTo(hasText(label(R.string.space_import))); click(R.string.space_import)
            documents.cancelImportPicker()
            browserIdle(); scrollBrowserTo(hasText(label(R.string.space_picker_cancelled))); compose.onNodeWithText(label(R.string.space_picker_cancelled)).assertIsDisplayed()
            assertEquals(offGeneration, RdpConnector.sessionGeneration())
            originals.keys.forEach { assertTrue(compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty()) }
            measurements.put("actual_import_picker_cancel_no_import", true)
            scrollBrowserTo(hasText(label(R.string.space_import))); click(R.string.space_import)
            documents.selectImports(originals.keys.toList())
            browserIdle(90_000); scrollBrowserTo(hasText(label(R.string.space_imported,originals.size))); compose.onNodeWithText(label(R.string.space_imported,originals.size)).assertIsDisplayed()
            originals.keys.forEach { scrollBrowserTo(hasText(it)); compose.onNodeWithText(it).assertIsDisplayed() }
            assertEquals(originals.mapValues { sha(it.value) }, documents.sourceHashes())
            screenshot("01-real-saf-imported-browser")
            closeBrowser(offGeneration)

            openMenu(R.string.drive_status_title)
            click(R.string.drive_enable_action)
            consentDefaultsReadOnly()
            click(R.string.cancel)
            assertEquals(offGeneration, RdpConnector.sessionGeneration())
            assertEquals(SharedDriveMode.OFF, RdpConnector.sharedDriveState().mode)
            measurements.put("consent_cancel_kept_off", true)
            openMenu(R.string.drive_status_title)
            click(R.string.drive_enable_action)
            consentDefaultsReadOnly()
            click(R.string.drive_enable_ro)
            awaitNewDrive(offGeneration, SharedDriveMode.READ_ONLY)
            val ro = recordNative("read_only_accepted")
            perform("inspect-imports", 120_000).also { result ->
                val received = result.getJSONObject("received")
                originals.forEach { (name, file) -> assertEquals(file.length(), received.getJSONObject(name).getLong("size")); assertEquals(sha(file), received.getJSONObject(name).getString("sha256")) }
                assertTrue(result.getBoolean("read_more_than_32mib"))
            }
            perform("check-readonly")
            assertTrue(RdpConnector.sharedDriveState().readBytes > 32L * 1024 * 1024)
            assertEquals(SharedDriveStatus.USED, RdpConnector.sharedDriveState().status)
            measurements.put("actual_ro_denied_write", true).put("actual_read_over_32mib", true)
            screenshot("02-read-only-mounted")

            openMenu(R.string.drive_status_title)
            click(R.string.drive_change_access)
            consentDefaultsReadOnly()
            click(R.string.drive_allow_write)
            compose.onNodeWithText(label(R.string.drive_enable_rw)).assertIsNotEnabled()
            click(R.string.drive_write_ack)
            click(R.string.drive_enable_rw)
            awaitNewDrive(ro.getLong("generation"), SharedDriveMode.READ_WRITE)
            val rwGeneration = RdpConnector.sessionGeneration()
            perform("exercise-writable").also { measurements.put("actual_rw_operations", it) }
            recordNative("read_write_used")
            assertTrue(RdpConnector.sharedDriveState().writtenBytes > 0)
            screenshot("03-read-write-mounted")
            openMenu(R.string.space_title)
            scrollBrowserTo(hasText("remote-created")); compose.onNodeWithText("remote-created").performClick()
            browserIdle()
            scrollBrowserTo(hasText("second")); compose.onNodeWithText("second").performClick()
            browserIdle()
            scrollBrowserTo(hasText("remote-roundtrip.bin")); compose.onNodeWithText("remote-roundtrip.bin").assertIsDisplayed()
            val action = label(R.string.space_file_actions, "remote-roundtrip.bin")
            scrollBrowserTo(hasContentDescription(action)); compose.onNodeWithContentDescription(action).performClick()
            click(R.string.export_action)
            documents.cancelExportTreePicker()
            browserIdle(); scrollBrowserTo(hasText(label(R.string.space_picker_cancelled))); compose.onNodeWithText(label(R.string.space_picker_cancelled)).assertIsDisplayed()
            assertEquals(1, documents.destinationHashes().size)
            measurements.put("actual_export_picker_cancel_no_copy", true)
            scrollBrowserTo(hasContentDescription(action)); compose.onNodeWithContentDescription(action).performClick()
            click(R.string.export_action)
            documents.chooseOwnedExportTree()
            browserIdle(45_000); scrollBrowserTo(hasText(label(R.string.space_exported))); compose.onNodeWithText(label(R.string.space_exported)).assertIsDisplayed()
            val exported = documents.destinationHashes()
            val expectedRemote = ByteArray(131109) { index -> if (index < 131101) ((index * 13 + 91) and 255).toByte() else "append\u0000\u00ff".map { it.code.toByte() }[index - 131101] }
            val expectedHash = sha(expectedRemote)
            assertEquals(2, exported.size)
            assertEquals(1, exported.values.count { it == expectedHash })
            assertEquals(sha("RdpSync synthetic export collision sentinel; never replace this file.\n".toByteArray()), exported.getValue("remote-roundtrip.bin"))
            measurements.put("peer_to_saf_export", JSONObject(exported)).put("export_expected_sha256", expectedHash).put("collision_original_unchanged", true)
            screenshot("04-real-saf-exported")
            closeBrowser(rwGeneration)

            stallAndResume(rwGeneration)
            assertResumedInput(rwGeneration)
            // Prepare both actual descriptors and the real confirmation UI BEFORE
            // pausing; no OPEN response is awaited on an intentionally blocked link.
            perform("hold-stale-handle")
            perform("prepare-stall-handle")
            openMenu(R.string.drive_status_title)
            click(R.string.stop_sharing)
            waitFor("real stop confirmation before cancellation stall") { textVisible(R.string.drive_stop_title) }
            pauseRelay(2)
            val canceledOperation = begin("read-cancel-batch")
            observePlateau("cancel", rwGeneration)
            val revokeStarted = SystemClock.elapsedRealtime()
            // Confirmation was already observed before pausing. One actual action,
            // and its completion consumes the same 3s local-revocation budget.
            compose.onNodeWithText(label(R.string.drive_stop_confirm)).assertIsDisplayed().assertIsEnabled().performClick()
            waitFor("local revoke removes broker authority while peer cannot drain", (revokeStarted+3_000-SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
                val s = RdpConnector.sharedDriveState()
                s.status == SharedDriveStatus.REVOKED && s.pendingCount in 1..32 &&
                    s.pendingInputBytes<=4L*1024*1024 && diagNumber("shared-space-pending-response-bytes") in 1..8L*1024*1024
            }
            measurements.put("paused_revoke_ms", SystemClock.elapsedRealtime() - revokeStarted)
            val revoked=recordNative("paused_revoked_reservations_retained_until_completion")
            SystemClock.sleep(100)
            assertEquals(revoked.getLong("read_bytes"),RdpConnector.sharedDriveState().readBytes)
            assertEquals(revoked.getLong("written_bytes"),RdpConnector.sharedDriveState().writtenBytes)
            assertEquals(rwGeneration, RdpConnector.sessionGeneration()); assertTrue(RdpConnector.isConnected())
            resumeRelay(2)
            val canceled=finish(canceledOperation,30_000)
            assertTrue(canceled.getInt("denied_readers")+canceled.getInt("incomplete_readers")>0)
            measurements.put("paused_cancel_results",canceled)
            perform("read-revoked-handle")
            waitFor("canceled completions drain every retained reservation", 3_000) {
                val s=RdpConnector.sharedDriveState()
                s.status==SharedDriveStatus.REVOKED && s.pendingCount==0L && s.pendingInputBytes==0L && diagNumber("shared-space-pending-response-bytes")==0L
            }
            recordNative("revoked_completions_drained")
            measurements.put("paused_revoke_and_stale_handle_denied", true)
            screenshot("05-revoked-session-live")
            click(R.string.return_desktop)
            assertResumedInput(rwGeneration)
            openMenu(R.string.drive_status_title)
            // A new grant must go through the actual user reconnect consent.
            click(R.string.reconnect)
            consentDefaultsReadOnly()
            click(R.string.drive_enable_ro)
            awaitNewDrive(rwGeneration, SharedDriveMode.READ_ONLY)
            perform("verify-persistence", 120_000)
            assertEquals(originals.mapValues { sha(it.value) }, documents.sourceHashes())
            measurements.put("source_originals_unchanged", true).put("persistence_after_explicit_reconnect", true)
            screenshot("06-persistent-after-reconnect")
            recordNative("final_read_only")
            assertClipboardOff()
            measurements.put("success", true)
        } catch (failure: Throwable) { primary = failure; measurements.put("failure", failure.toString()); throw failure }
        finally {
            fun cleanup(block: () -> Unit) { try { block() } catch (failure: Throwable) { measurements.put("cleanup_failure", failure.toString()); if (primary != null) primary!!.addSuppressed(failure) else { primary = failure } } }
            cleanup {
                // Resolve actual owned relay state before cleanup; a lost POST
                // response never justifies replaying an uncertain control action.
                val relay=request(8771,"/relay")
                if(relay.getBoolean("paused")) request(8771,"/relay/resume-client-read","POST")
                paused=false
            }
            cleanup { recordNative("before_cleanup") }
            cleanup { screenshot("99-shared-drive-final") }
            cleanup { if(::documents.isInitialized) {
                val removed=documents.cleanup();measurements.put("synthetic_seed_cleanup",removed)
                for(i in 0 until removed.length()) assertTrue("Every captured synthetic seed must be removed",removed.getJSONObject(i).getBoolean("removed"))
                if(measurements.optBoolean("success")) assertEquals(5,removed.length())
            } }
            cleanup { compose.runOnUiThread { showing.value = false }; RdpConnector.disconnect() }
            cleanup { automation.serviceInfo = oldService }
            cleanup {
                val root=File(evidence,"originals")
                root.listFiles()?.forEach { file -> require(file.isFile && file.name in setOf("android-empty.txt","android-二进制.bin","android-说明.txt","android-large.bin"));check(file.delete()) }
                check(root.delete() || !root.exists())
            }
            cleanup {
                measurements.put("completed_epoch_ms", System.currentTimeMillis()).put("disconnected", !RdpConnector.isConnected())
                File(evidence, "measurements.json").writeText(measurements.toString(2))
                shell("mkdir -p /sdcard/Download/rdpsync-shared-drive-evidence")
                shell("cp -R ${evidence.absolutePath}/. /sdcard/Download/rdpsync-shared-drive-evidence/")
                assertEquals(measurements.toString(2), shell("cat /sdcard/Download/rdpsync-shared-drive-evidence/measurements.json").trim())
            }
            if (primary != null && !measurements.has("failure")) throw primary!!
        }
    }

    private fun consentDefaultsReadOnly() {
        waitFor("read-only default consent") { textVisible(R.string.drive_consent_title) }
        compose.onNodeWithText(label(R.string.drive_allow_write)).assertIsOff()
        compose.onNodeWithText(label(R.string.drive_enable_ro)).assertIsEnabled()
        assertFalse(textExists(label(R.string.drive_write_ack)))
    }
    private fun assertClipboardOff() = assertEquals("True drive must not use clipboard file channel", -2L, RdpConnector.nativeFileTransferState()[1])
    private fun awaitNewDrive(previous: Long, mode: SharedDriveMode) {
        waitFor("explicit reconnect accepted actual RDPDR device", 45_000) {
            val s = RdpConnector.sharedDriveState()
            RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && s.generation != previous && s.mode == mode && s.accepted && s.deviceId > 0 && s.replyStatus == 0L
        }
        awaitFrame(); assertClipboardOff()
        waitFor("sole active owned relay connection") { request(8771, "/relay").getInt("active_connections") == 1 }
    }
    private fun awaitFrame() {
        waitFor("actual classic decoded pixels", 45_000) {
            RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && RdpConnector.getDiag().contains("gfx-last-codec=0\n") && colorAt(40, 40) == 0xff0000
        }
        val result = acknowledgeSystemImmersiveCoachmark(compose.activity, { pump() }, { screenshot("00-system-immersive-coachmark") },
            { retainCoachmark(it) }, allowAcknowledgement = !coachmarkAcknowledged)
        coachmarkAcknowledged = coachmarkAcknowledged || result.getBoolean("acknowledged")
    }
    private fun openMenu(id: Int) {
        waitFor("focused live desktop") { var ready=false; compose.runOnUiThread { ready=desktopOrNull()?.hasWindowFocus() == true }; ready }
        if(id==R.string.space_title) { compose.runOnUiThread { browserView=desktopOrNull() }; browserServer=request(8765,"/state") }
        compose.onNodeWithContentDescription(label(R.string.session_menu)).assertIsDisplayed().performTouchInput { click() }
        waitFor("unique enabled menu item") { val n=compose.onAllNodesWithText(label(id)).fetchSemanticsNodes(); n.size==1 && isEnabled().matches(n.single()) }
        boundedScroll.to(compose.onNodeWithText(label(id))).assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun click(id: Int) {
        waitFor("enabled app action ${context.resources.getResourceEntryName(id)}") { val n=compose.onAllNodesWithText(label(id)).fetchSemanticsNodes(); n.size==1 && isEnabled().matches(n.single()) }
        val node=boundedScroll.to(compose.onNodeWithText(label(id))); node.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun browserIdle(timeout: Long=15_000) {
        waitFor("actual Shared space loading/picker/operation completed",timeout) {
            val nodes=compose.onAllNodesWithContentDescription(label(R.string.space_refresh)).fetchSemanticsNodes()
            nodes.size==1 && isEnabled().matches(nodes.single())
        }
    }
    private fun scrollBrowserTo(matcher: SemanticsMatcher) {
        boundedScroll.toNode(hasScrollAction(), matcher)
    }
    private fun closeBrowser(generation: Long) {
        compose.onNodeWithContentDescription(label(R.string.back)).assertIsDisplayed().performClick()
        waitFor("same desktop after browser") { var ready=false; compose.runOnUiThread { ready=desktopOrNull()?.hasWindowFocus()==true }; ready }
        assertEquals(generation, RdpConnector.sessionGeneration()); assertTrue(RdpConnector.isConnected())
        compose.runOnUiThread { assertTrue("Browser keeps the same live desktop View",browserView === desktopOrNull()) }
        val before=requireNotNull(browserServer); val after=request(8765,"/state")
        assertEquals(before.getString("session_id"),after.getString("session_id"));assertEquals(before.getInt("desktop_pid"),after.getInt("desktop_pid"))
        val observations=measurements.optJSONArray("browser_identity") ?: JSONArray().also { measurements.put("browser_identity",it) }
        observations.put(JSONObject().put("generation",generation).put("same_view",true).put("before",before).put("after",after))
        browserView=null;browserServer=null
    }
    private fun textExists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1
    private fun textVisible(id: Int) = textExists(label(id)) && compose.onNodeWithText(label(id)).isDisplayed()
    private fun pump() { compose.mainClock.advanceTimeBy(100); instrumentation.waitForIdleSync() }
    private fun waitFor(reason: String, timeout: Long=15_000, predicate: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while (SystemClock.elapsedRealtime()<deadline) { pump(); if(predicate() && SystemClock.elapsedRealtime()<=deadline) return; SystemClock.sleep(50) }
        throw AssertionError("$reason: ${RdpConnector.getDiag()}")
    }
    private fun recordNative(phase: String): JSONObject {
        val s=RdpConnector.sharedDriveState()
        val value=JSONObject().put("phase",phase).put("elapsed_ms",SystemClock.elapsedRealtime()).put("generation",s.generation)
            .put("mode",s.mode.name).put("status",s.status.name).put("device_id",s.deviceId).put("reply_status",s.replyStatus)
            .put("read_bytes",s.readBytes).put("written_bytes",s.writtenBytes).put("pending_count",s.pendingCount)
            .put("pending_input_bytes",s.pendingInputBytes).put("transport_pending",s.transportPending)
            .put("completed_irps",s.completedIrps).put("peak_transport_pending",s.peakTransportPending).put("diag",RdpConnector.getDiag())
        assertTrue(nativeStates.length()<128); nativeStates.put(value); return value
    }
    private fun diagNumber(key: String): Long = requireNotNull(Regex("(?:^|\\n)${Regex.escape(key)}=([0-9]+)(?:\\n|$)").find(RdpConnector.getDiag())).groupValues[1].toLong()
    private fun request(port: Int, path: String, method: String="GET"): JSONObject = Socket().use { socket ->
        require(port in setOf(8765,8770,8771)); require(path.matches(Regex("/(state|drive(?:/[a-z-]+)?|relay(?:/[a-z-]+)?)")))
        socket.connect(InetSocketAddress("10.0.2.2",port),1500); socket.soTimeout=2000
        socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        val raw=socket.getInputStream().bufferedReader().use { reader -> val value=StringBuilder(); val b=CharArray(4096); while(true){ val n=reader.read(b); if(n<0)break; require(value.length+n<=131072);value.append(b,0,n) };value.toString() }
        require(Regex("HTTP/1.[01] (200|202) ").containsMatchIn(raw)) { raw.take(512) }
        JSONObject(raw.substringAfter("\r\n\r\n"))
    }
    private fun begin(action: String): JSONObject {
        val accepted=request(8770,"/drive/$action","POST")
        val record=JSONObject().put("action",action).put("operation",accepted.getInt("operation")).put("uuid",accepted.getString("uuid")).put("started_elapsed_ms",SystemClock.elapsedRealtime())
        operations.put(record); return record
    }
    private fun finish(record: JSONObject, timeout: Long=30_000): JSONObject {
        var completed: JSONObject?=null
        waitFor("actual mounted operation ${record.getString("action")}",timeout) {
            val s=request(8770,"/drive"); record.put("last_state",s)
            assertEquals(record.getString("uuid"),s.getString("uuid")); assertEquals(record.getInt("operation"),s.getInt("operation"))
            if(s.getString("phase")=="failed") throw AssertionError(s.toString())
            if(s.getString("phase")=="completed") { completed=s;true } else false
        }
        val s=requireNotNull(completed); val mount=s.getJSONObject("mount")
        assertTrue(mount.getBoolean("via_actual_rdpdr_fuse")); assertFalse(mount.getBoolean("clipboard_path_used")); assertEquals("RdpSync",mount.getString("drive_name")); assertEquals("actual /proc/self/mountinfo",mount.getString("mount_metadata_origin"))
        record.put("completed_elapsed_ms",SystemClock.elapsedRealtime()).put("completed",s)
        val result = s.getJSONObject("result")
        if (record.getString("action") in setOf("read-stall-batch", "read-cancel-batch")) {
            assertEquals(32, result.getInt("concurrent_readers"))
            assertEquals(32, result.getJSONArray("resumed_ranges").length())
            val pressure = result.getJSONObject("pressure")
            assertTrue(pressure.getBoolean("configured"))
            assertFalse(pressure.getBoolean("global_settings_changed"))
            assertEquals(32, pressure.getInt("readers"))
            assertEquals("restored_same_connection", pressure.getString("restoration"))
            for (name in listOf("max_background", "congestion_threshold")) {
                assertEquals(32, pressure.getJSONObject("after").getInt(name))
                assertEquals(pressure.getJSONObject("before").getInt(name), pressure.getJSONObject("restored").getInt(name))
            }
        }
        return result
    }
    private fun perform(action: String, timeout: Long=30_000)=finish(begin(action),timeout)
    private fun pauseRelay(ordinal: Int) {
        val s=request(8771,"/relay/pause-client-read","POST"); paused=true
        assertEquals(ordinal,s.getInt("pause_count")); assertTrue(s.getBoolean("paused")); assertFalse(s.getBoolean("pause_expired"))
    }
    private fun resumeRelay(ordinal: Int) {
        val s=request(8771,"/relay/resume-client-read","POST"); paused=false
        assertEquals(ordinal,s.getInt("pause_count")); assertFalse(s.getBoolean("paused")); assertFalse(s.getBoolean("pause_expired"))
    }
    private fun observePlateau(phase: String, generation: Long) {
        val samples=JSONArray(); val entry=JSONObject().put("phase",phase).put("samples",samples)
        val phases=measurements.optJSONArray("stalls") ?: JSONArray().also { measurements.put("stalls",it) }; phases.put(entry)
        val deadline=SystemClock.elapsedRealtime()+7_000
        var stableStart=0L; var lastCompleted=-1L; var lastBytes=-1L; var stableSamples=0
        while(SystemClock.elapsedRealtime()<deadline) {
            pump()
            val native=recordNative("stall-$phase"); val s=RdpConnector.sharedDriveState(); val relay=request(8771,"/relay")
            val reserved=diagNumber("shared-space-pending-response-bytes"); val peak=diagNumber("shared-space-peak-response-bytes")
            val sample=JSONObject().put("native",native).put("relay",relay).put("reserved_response_bytes",reserved).put("peak_response_bytes",peak)
            samples.put(sample)
            assertEquals(generation,s.generation); assertTrue(RdpConnector.isConnected()); assertFalse(relay.getBoolean("pause_expired")); assertTrue(relay.getBoolean("paused"))
            assertTrue(s.pendingCount<=32 && s.pendingInputBytes<=4L*1024*1024)
            assertTrue(reserved<=8L*1024*1024 && peak<=8L*1024*1024)
            assertTrue("Bounded drive ciphertext after initialized handshake",s.transportPending in 0..1_376_256L)
            val bytes=relay.getLong("client_to_server_bytes")
            val blocked=s.transportPending>=256L*1024 && s.pendingCount>0 && reserved>0
            if(blocked && lastCompleted==s.completedIrps && lastBytes==bytes) { stableSamples++ } else { stableStart=SystemClock.elapsedRealtime(); stableSamples=0 }
            lastCompleted=s.completedIrps; lastBytes=bytes
            if(blocked && stableSamples>=3 && SystemClock.elapsedRealtime()-stableStart>=600 && SystemClock.elapsedRealtime()<=deadline) {
                entry.put("actual_buffered_plateau",true).put("client_read_bytes_stable",true).put("literal_zero_window_claimed",false)
                return
            }
            SystemClock.sleep(200)
        }
        throw AssertionError("Pause must produce actual buffered native responses and bounded plateau, not a synthetic pause flag")
    }
    private fun stallAndResume(generation: Long) {
        perform("prepare-stall-handle"); pauseRelay(1)
        val action=begin("read-stall-batch")
        observePlateau("resume",generation)
        resumeRelay(1)
        val result=finish(action,30_000)
        assertEquals(32,result.getJSONArray("resumed_ranges").length()); assertEquals(0,result.getInt("denied_readers")); assertEquals(0,result.getInt("incomplete_readers"))
        measurements.put("stalled_read_resumed_hashes",result)
    }
    private fun assertResumedInput(generation: Long) {
        waitFor("focused actual desktop after transport resumes") { var ready=false;compose.runOnUiThread { ready=desktopOrNull()?.hasWindowFocus()==true };ready && colorAt(40,40)==0xff0000 }
        val before=request(8765,"/state"); val attempt=JSONObject().put("before",before)
        val taps=measurements.optJSONArray("touch_attempts") ?: JSONArray().also { measurements.put("touch_attempts",it) };taps.put(attempt)
        lateinit var view: RdpDesktopView; var sx=0f;var sy=0f
        compose.runOnUiThread { view=requireNotNull(desktopOrNull());val p=view.viewportSnapshot().toScreen(400f,300f);val o=IntArray(2);view.getLocationOnScreen(o);sx=o[0]+p.x;sy=o[1]+p.y }
        val up=injectObservedDesktopTap(instrumentation,view,sx,sy,400f,300f,attempt) { assertTrue(view===desktopOrNull()); assertEquals(generation,RdpConnector.sessionGeneration()) }
        val deadline=up+10_000;val samples=JSONArray();attempt.put("response_samples",samples).put("response_deadline_elapsed_ms",deadline)
        while(SystemClock.elapsedRealtime()<deadline) {
            pump();val s=request(8765,"/state");val rgb=colorAt(40,140);val expected=if((before.getInt("clicks")+1)%2==1)0x44dd88 else 0xdd4488
            val sample=JSONObject().put("server",s).put("rgb",rgb).put("expected",expected).put("elapsed_ms",SystemClock.elapsedRealtime());if(samples.length()<64)samples.put(sample)
            val position=s.optJSONArray("last_release")
            val good=s.getInt("clicks")==before.getInt("clicks")+1 && s.getInt("right_clicks")==before.getInt("right_clicks") &&
                s.getInt("presses")==before.getInt("presses")+1 && s.getInt("releases")==before.getInt("releases")+1 &&
                position!=null && abs(position.getDouble(0)-400)<=1 && abs(position.getDouble(1)-300)<=1 && rgb==expected &&
                s.getString("session_id")==before.getString("session_id") && s.getInt("desktop_pid")==before.getInt("desktop_pid")
            if(good && SystemClock.elapsedRealtime()<=deadline) { attempt.put("passed",true);return };SystemClock.sleep(100)
        }
        throw AssertionError("Actual left input/pixel response after resumed transport")
    }
    private fun desktopOrNull(): RdpDesktopView? {
        fun find(v: View): RdpDesktopView? { if(v is RdpDesktopView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null }
        return find(compose.activity.window.decorView)
    }
    private fun colorAt(x: Int,y: Int): Int? {
        var point: Pair<Int,Int>?=null
        compose.runOnUiThread { desktopOrNull()?.let { v -> val p=v.viewportSnapshot().toScreen(x.toFloat(),y.toFloat());val o=IntArray(2);v.getLocationOnScreen(o);if(p.x>=0&&p.y>=0&&p.x<v.width&&p.y<v.height)point=Pair(o[0]+p.x.toInt(),o[1]+p.y.toInt()) } }
        val p=point?:return null;val b=automation.takeScreenshot()?:return null
        return try { b.getPixel(p.first,p.second) and 0xffffff } finally { b.recycle() }
    }
    private fun retainCoachmark(value: JSONObject) {
        val phases=measurements.optJSONArray("coachmark_phases") ?: JSONArray().also { measurements.put("coachmark_phases",it) }
        if(phases.length()<32) phases.put(value)
        if(value.optBoolean("acknowledged") || !measurements.has("system_immersive_coachmark")) measurements.put("system_immersive_coachmark",value)
    }
    private fun trustFirstCertificate(fingerprint: String) {
        val title=label(R.string.certificate_confirm)
        val result=acknowledgeSystemImmersiveCoachmark(compose.activity,{pump()},{screenshot("00-system-immersive-coachmark")},
            {retainCoachmark(it)},deadlineElapsedMs=SystemClock.elapsedRealtime()+45_000,
            readyWhenSystemOverlayAbsent={RdpConnector.certificateChallenge()?.fingerprint==fingerprint && textExists(title) && focusedTitle(title)},allowAcknowledgement=true)
        coachmarkAcknowledged=result.getBoolean("acknowledged")
        assertEquals(0L,RdpConnector.nativeGetFrameId())
        boundedScroll.to(compose.onNodeWithText(fingerprint)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.fingerprint_save)).assertIsNotEnabled()
        click(R.string.fingerprint_verified)
        click(R.string.fingerprint_save)
    }
    private fun focusedTitle(title: String): Boolean {
        var count=0;val windows=automation.windows
        try { for(w in windows) { if(w.type!=AccessibilityWindowInfo.TYPE_APPLICATION||!w.isFocused)continue;val root=w.root?:continue;val nodes=mutableListOf<AccessibilityNodeInfo>()
            fun visit(n: AccessibilityNodeInfo){nodes+=n;for(i in 0 until n.childCount){if(nodes.size>=512)break;n.getChild(i)?.let(::visit)}}
            visit(root);try { if(root.packageName?.toString()==context.packageName&&nodes.count{it.isVisibleToUser&&it.text?.toString()==title}==1)count++ } finally { nodes.forEach{it.recycle()} }
        } } finally { windows.forEach{it.recycle()} };return count==1
    }
    private fun prepareOriginalFiles(): Map<String,File> {
        val root=File(evidence,"originals").apply{check(!exists());check(mkdirs())}
        val small=linkedMapOf("android-empty.txt" to byteArrayOf(),"android-二进制.bin" to ByteArray(262149){((it*29+17) and 255).toByte()},"android-说明.txt" to "Owned Android import · 共享空间\n".toByteArray())
        val result=linkedMapOf<String,File>();small.forEach{(name,bytes)->result[name]=File(root,name).apply{writeBytes(bytes)}}
        val block=ByteArray(65536){((it*37+11) and 255).toByte()}
        result["android-large.bin"]=File(root,"android-large.bin").apply{outputStream().use{out->var remaining=40L*1024*1024+19;while(remaining>0){val n=minOf(remaining,block.size.toLong()).toInt();out.write(block,0,n);remaining-=n}}}
        return result
    }
    private fun sha(file: File): String { val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use { input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)} };return digest.digest().joinToString(""){"%02x".format(it)} }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun screenshot(name: String) { val b=requireNotNull(automation.takeScreenshot());try{File(evidence,"$name.png").outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{b.recycle()} }
    private fun shell(command: String): String {
        // Fixed owned evidence export only. EOF, allocation, late FD ownership and
        // worker cleanup are bounded; timeout never claims Binder cancellation.
        val closing=AtomicBoolean(false);val pipe=AtomicReference<ParcelFileDescriptor?>()
        val executor=Executors.newSingleThreadExecutor { task -> Thread(task,"drive-evidence-read").apply { isDaemon=true } }
        var primary: Throwable?=null
        try {
            return executor.submit<String> {
                check(!closing.get())
                val fd=automation.executeShellCommand(command)
                try {
                    pipe.set(fd);check(!closing.get())
                    ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { reader ->
                        val value=StringBuilder();val buffer=CharArray(4096)
                        while(true) { val n=reader.read(buffer);if(n<0)break;check(value.length+n<=4*1024*1024);value.append(buffer,0,n) }
                        value.toString()
                    }
                } finally { pipe.compareAndSet(fd,null);fd.close() }
            }.get(15,TimeUnit.SECONDS)
        } catch(failure: Throwable) { primary=failure;throw failure }
        finally {
            closing.set(true);var cleanupFailure: Throwable?=null
            try { pipe.getAndSet(null)?.close() } catch(failure: Throwable) { cleanupFailure=failure }
            executor.shutdownNow()
            try { check(executor.awaitTermination(2,TimeUnit.SECONDS)) } catch(failure: Throwable) {
                if(failure is InterruptedException) Thread.currentThread().interrupt()
                if(cleanupFailure==null)cleanupFailure=failure else cleanupFailure!!.addSuppressed(failure)
            }
            cleanupFailure?.let { if(primary!=null)primary!!.addSuppressed(it) else throw it }
        }
    }
}
