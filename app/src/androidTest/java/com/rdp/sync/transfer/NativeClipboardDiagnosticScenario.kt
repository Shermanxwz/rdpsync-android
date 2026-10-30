package com.rdp.sync.transfer

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.ClipboardFixturePeer
import com.rdp.sync.network.RdpConnector
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ConnectException
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest

/**
 * DIAGNOSTIC ONLY: real staged-file/JNI/CLIPRDR/FUSE bytes, without Android UI or SAF.
 * The separately required ClipboardTransferScenario remains the physical picker acceptance test.
 * XRDP uses its windowless X11 selection peer; this diagnostic does not cover input.
 * GNOME retains its Wayland clipboard-owner focus setup, not a user-input acceptance claim.
 */
abstract class NativeClipboardDiagnosticScenario(private val peer: ClipboardFixturePeer) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidenceName = "${peer.name.lowercase()}-transport-diagnostic"
    private val evidence by lazy { File(context.getExternalFilesDir(null), evidenceName).apply { check(mkdirs() || isDirectory) } }
    private val measurements = JSONObject().put("diagnostic_only", true).put("saf_ui_covered", false)
        .put("peer", peer.name).put("scope", peer.scope)

    @Test fun actualOwnedSnapshotAndNativePeerBytesDiagnosticOnly() {
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals("Fixed synthetic peer must be explicitly enabled", "true", arguments.getString(peer.enabledArgument))
        val fingerprint = requireNotNull(arguments.getString(peer.fingerprintArgument))
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(RdpConnector.initialize(context))
        RdpConnector.disconnect()
        val store = TransferStore.create(context.cacheDir)
        val leases = mutableListOf<TransferReadLease>()
        var transport: RdpFileTransferTransport? = null
        var outboundToken = 0L
        var receiveToken = 0L
        var privateRoot: java.nio.file.Path? = null
        try {
            assertTrue("Synthetic exact-pin opt-in connect must start", RdpConnector.connectDevice(
                "10.0.2.2", peer.rdpPort, "rdpfixture", "rdpsync-fixture-only", "", "10.0.2.2",
                width = 1024, height = 768, enableTouchInput = false,
                expectedFingerprint = fingerprint, fileTransferEnabled = true))
            await("actual frame and negotiated file stream", 45_000) {
                val state = RdpConnector.nativeFileTransferState()
                RdpConnector.isConnected() && RdpConnector.nativeGetFrameId() > 0 && state.size == 10 && state[1] == 1L
            }
            assertNull("Exact fixture pin must not create trust prompt", RdpConnector.certificateChallenge())
            assertTrue(RdpConnector.getDiag().contains("certificate=pin-matched"))
            assertFalse(RdpConnector.getDiag().contains("connection=compatibility-retry"))
            val generation = RdpConnector.sessionGeneration()
            val active = RdpFileTransferTransport(generation).also { transport = it }
            assertTrue(active.capability().available)
            assertEquals(TRANSFER_SEND_BUDGET_BYTES, active.remainingSendBytes())
            measurements.put("generation", generation).put("exact_pin_matched", true).put("explicit_file_opt_in", true)
            if (peer == ClipboardFixturePeer.XRDP) {
                measurements.put("input_coverage", false)
                awaitWindowlessXrdpPeer()
            } else {
                focusFixedPeerSurface()
            }
            assertGeneration(generation)

            val sentinelBytes = "Original private snapshot must remain unchanged\n".toByteArray()
            val sentinel = store.stage("server-binary.bin", sentinelBytes.size.toLong(),
                { ByteArrayInputStream(sentinelBytes) }, TransferCancellation())
            store.lease(sentinel.id).use { privateRoot = it.path.parent }
            val originals = samples("android")
            val outbound = originals.map { (name, bytes) ->
                store.stage(name, bytes.size.toLong(), { ByteArrayInputStream(bytes) }, TransferCancellation())
            }
            outbound.forEach { leases += store.lease(it.id) }
            assertGeneration(generation)
            outboundToken = active.publish(leases, TransferCancellation())
            assertGeneration(generation)
            assertTrue(active.offerRetained(outboundToken))
            val servedBefore = RdpConnector.nativeFileTransferState()[3]
            val readOperation = action("read-android-files").getLong("operation")
            val uploaded = awaitOperation(readOperation, "received").getJSONObject("received")
            assertGeneration(generation)
            assertPeerHashes(originals, uploaded)
            val servedAfter = RdpConnector.nativeFileTransferState()[3]
            assertTrue("Peer must read current native FDs, not stale clipboard data", servedAfter > servedBefore)
            val remaining = requireNotNull(active.remainingSendBytes())
            assertTrue(remaining in 1 until TRANSFER_SEND_BUDGET_BYTES)
            measurements.put("outbound_operation", readOperation).put("android_to_peer_fuse", uploaded)
                .put("bytes_served_before", servedBefore).put("bytes_served_after", servedAfter)
                .put("remaining_send_budget", remaining)
            active.revoke(outboundToken)
            await("native outbound FD revocation") { !active.offerRetained(outboundToken) }
            assertGeneration(generation)
            assertEquals("Cancel cannot replenish connection-wide quota", remaining, active.remainingSendBytes())
            leases.forEach { it.close() }; leases.clear()
            assertTrue(RdpConnector.isConnected())
            measurements.put("outbound_revoke_acknowledged", true).put("cancel_preserved_connection_and_budget", true)

            val offeredOperation = action("offer-server-files").getLong("operation")
            awaitOperation(offeredOperation, "offered")
            val expectedRemote = samples("server")
            var offer = awaitRemoteOffer(active)
            receiveToken = offer.token
            assertGeneration(generation)
            validateRemote(offer, expectedRemote)
            val beforePartial = ownedFileCount(requireNotNull(privateRoot))
            val cancelled = TransferCancellation()
            val binary = offer.files.single { it.name == "server-binary.bin" }
            assertTrue("Cancellation fixture must exceed one full chunk", binary.size > 65536L)
            var partialBytes = 0L
            try {
                store.stage(binary.name, binary.size, { active.openRemote(offer, binary, cancelled) }, cancelled) {
                    partialBytes = it
                    cancelled.requestCancel()
                }
                fail("Explicit cancellation after a real chunk must abort staging")
            } catch (failure: TransferFailure) { assertEquals(TransferFailure.Reason.CANCELLED, failure.reason) }
            assertTrue("Cancellation must exercise actual remote bytes", partialBytes in 1..65536)
            assertTrue("Cancellation must precede EOF", partialBytes < binary.size)
            assertEquals("Partial receive must not leave a staged or .part file", beforePartial, ownedFileCount(requireNotNull(privateRoot)))
            assertGeneration(generation)
            measurements.put("cancelled_remote_bytes", partialBytes).put("cancelled_declared_bytes", binary.size)
                .put("cancelled_receive_token", receiveToken).put("partial_receive_removed", true)
            active.finishReceive(receiveToken)
            assertEquals(0L, RdpConnector.nativeFileTransferState()[4])
            val cancelledToken = receiveToken
            receiveToken = 0
            offer = awaitRemoteOffer(active)
            receiveToken = offer.token
            assertGeneration(generation)
            assertNotEquals("A fresh metadata request needs a fresh token", cancelledToken, receiveToken)
            validateRemote(offer, expectedRemote)
            val received = JSONObject()
            offer.files.forEach { remote ->
                val expected = expectedRemote.getValue(remote.name)
                val copyCancellation = TransferCancellation()
                val local = store.stage(remote.name, remote.size,
                    { active.openRemote(offer, remote, copyCancellation) }, copyCancellation)
                assertGeneration(generation)
                assertEquals(sha256(expected), local.sha256)
                assertEquals(expected.size.toLong(), local.size)
                store.lease(local.id).use { assertArrayEquals(expected, it.input.readBytes()) }
                received.put(remote.name, JSONObject().put("size", local.size).put("sha256", local.sha256))
            }
            active.finishReceive(receiveToken); receiveToken = 0
            assertEquals(0L, RdpConnector.nativeFileTransferState()[4])
            store.lease(sentinel.id).use { assertArrayEquals(sentinelBytes, it.input.readBytes()) }
            outbound.forEach { file -> store.lease(file.id).use { assertArrayEquals(originals.getValue(file.name), it.input.readBytes()) } }
            assertEquals(generation, RdpConnector.sessionGeneration())
            measurements.put("inbound_operation", offeredOperation).put("peer_to_private_store", received)
                .put("cancelled_remote_bytes", partialBytes).put("partial_receive_removed", true)
                .put("private_snapshot_collision_sentinel_unchanged", true).put("success", true)
        } finally {
            measurements.put("diagnostics_before_close", RdpConnector.getDiag())
            try {
                if (receiveToken > 0) transport?.finishReceive(receiveToken)
                if (outboundToken > 0) transport?.revoke(outboundToken)
            } finally {
                RdpConnector.disconnect()
                val noRetained = outboundToken <= 0 || !RdpConnector.nativeFileTransferOfferRetained(outboundToken)
                leases.forEach { it.close() }
                store.close()
                val removed = privateRoot?.let { !Files.exists(it) } ?: true
                measurements.put("disconnected", !RdpConnector.isConnected()).put("no_retained_native_offer", noRetained)
                    .put("private_snapshot_removed_after_close", removed).put("diagnostics", RdpConnector.getDiag())
                saveEvidence()
                assertTrue("Disconnect must release owned native FDs", noRetained)
                assertTrue("Closed staging snapshot must be removed", removed)
                assertFalse(RdpConnector.isConnected())
            }
        }
    }

    private fun assertGeneration(expected: Long) {
        assertTrue("Diagnostic connection must remain live", RdpConnector.isConnected())
        assertEquals("No hidden reconnect is permitted", expected, RdpConnector.sessionGeneration())
    }

    private fun validateRemote(offer: RemoteTransferOffer, expected: Map<String, ByteArray>) {
        TransferNames.validateDistinct(offer.files.map { it.name })
        assertEquals(expected.keys, offer.files.map { it.name }.toSet())
        assertEquals(expected.size, offer.files.size)
        offer.files.forEach { assertEquals(expected.getValue(it.name).size.toLong(), it.size) }
    }

    private fun awaitRemoteOffer(transport: RdpFileTransferTransport): RemoteTransferOffer {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (true) {
            try { return transport.requestOffer(TransferCancellation()) }
            catch (failure: TransferFailure) {
                // -2 before token allocation means the real peer format-list has not arrived.
                // Never repeat a request that started a transaction or returned busy/stale/error.
                if (failure.reason != TransferFailure.Reason.UNSUPPORTED || !transport.capability().available ||
                    SystemClock.elapsedRealtime() >= deadline) throw failure
                SystemClock.sleep(25)
            }
        }
    }

    private fun awaitWindowlessXrdpPeer() {
        // xrdp_file_session.py runs this GTK/X11 selection owner separately from
        // the Tk input desktop. X11 selections are server-global: neither fixed
        // /transfer action depends on focusing or clicking the unrelated window.
        // Observe only readiness here; preserve the ONE POST per transaction below.
        val readiness = JSONObject().put("started_elapsed_ms", SystemClock.elapsedRealtime())
            .put("fixed_transfer_port", peer.transferPort)
        measurements.put("windowless_xrdp_peer_readiness", readiness)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var attempts = 0
        var refused = 0
        while (true) {
            attempts++
            try {
                val observed = request("GET", "/transfer")
                check(SystemClock.elapsedRealtime() <= deadline) { "Fixed transfer peer readiness deadline exceeded" }
                val identity = observed.getString("peer")
                val phase = observed.getString("phase")
                val operation = observed.getLong("operation")
                val pid = observed.getLong("pid")
                readiness.put("peer", identity).put("phase", phase).put("operation", operation).put("pid", pid)
                assertEquals("Fixed native X11 transfer peer identity", "xrdp", identity)
                assertTrue("Windowless transfer peer must have a live process identity", pid > 0)
                assertEquals("Diagnostic requires a fresh transfer peer, not an old transaction", "idle", phase)
                assertEquals("Diagnostic must precede any peer action", 0L, operation)
                readiness.put("ready_elapsed_ms", SystemClock.elapsedRealtime()).put("refused_before_ready", refused)
                return
            } catch (failure: ConnectException) {
                val errno = generateSequence(failure as Throwable) { it.cause }
                    .filterIsInstance<ErrnoException>().firstOrNull()?.errno
                if (errno != OsConstants.ECONNREFUSED || SystemClock.elapsedRealtime() >= deadline) throw failure
                refused++
                readiness.put("refused_before_ready", refused).put("last_refused_elapsed_ms", SystemClock.elapsedRealtime())
                SystemClock.sleep(50)
            } finally { readiness.put("get_attempts", attempts) }
        }
    }

    private fun focusFixedPeerSurface() {
        // The first native frame may precede startup of the isolated peer's HTTP
        // observation process. Wait for READ-ONLY readiness before the one click;
        // never repeat a pointer event, POST, connection, or timed-out transaction.
        val readiness = JSONObject().put("started_elapsed_ms", SystemClock.elapsedRealtime())
            .put("fixed_state_port", peer.statePort)
        measurements.put("focus_observer_readiness", readiness)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var attempts = 0
        var refused = 0
        var notReady = 0
        var initial: JSONObject? = null
        while (initial == null) {
            attempts++
            try {
                val observed = request("GET", "/state")
                check(SystemClock.elapsedRealtime() <= deadline) { "Fixed peer observer readiness deadline exceeded" }
                val ready = observed.getBoolean("ready")
                val width = observed.getInt("width")
                val height = observed.getInt("height")
                readiness.put("last_ready", ready).put("last_width", width).put("last_height", height)
                if (ready && width == 1024 && height == 768) initial = observed
                else {
                    notReady++
                    readiness.put("not_ready_observations", notReady)
                        .put("last_not_ready_elapsed_ms", SystemClock.elapsedRealtime())
                    SystemClock.sleep(50)
                }
            } catch (failure: ConnectException) {
                val errno = generateSequence(failure as Throwable) { it.cause }
                    .filterIsInstance<ErrnoException>().firstOrNull()?.errno
                if (errno != OsConstants.ECONNREFUSED || SystemClock.elapsedRealtime() >= deadline) throw failure
                refused++
                readiness.put("refused_before_ready", refused).put("last_refused_elapsed_ms", SystemClock.elapsedRealtime())
                SystemClock.sleep(50)
            } finally { readiness.put("get_attempts", attempts) }
        }
        readiness.put("ready_elapsed_ms", SystemClock.elapsedRealtime()).put("refused_before_ready", refused)
            .put("not_ready_observations", notReady)
        val before = initial.getInt("clicks")
        assertEquals(0, RdpConnector.sendMouseButton(400, 300, 1, true))
        assertEquals(0, RdpConnector.sendMouseButton(400, 300, 1, false))
        await("native pointer focuses only fixed synthetic fixture surface") {
            val state = request("GET", "/state")
            val position = state.optJSONArray("last_release")
            state.getInt("clicks") == before + 1 && position != null &&
                kotlin.math.abs(position.getDouble(0) - 400) <= 1 && kotlin.math.abs(position.getDouble(1) - 300) <= 1
        }
        measurements.put("native_pointer_focus_acknowledged", true)
    }

    private fun awaitOperation(operation: Long, phase: String): JSONObject {
        var observed = JSONObject()
        await("matching synthetic peer transaction reaches $phase", 45_000) {
            observed = request("GET", "/transfer")
            assertEquals("Peer transaction changed unexpectedly", operation, observed.getLong("operation"))
            assertNotEquals("Synthetic peer reported failure", "failed", observed.getString("phase"))
            observed.getString("phase") == phase
        }
        return observed
    }

    private fun assertPeerHashes(expected: Map<String, ByteArray>, actual: JSONObject) {
        assertEquals(expected.size, actual.length())
        expected.forEach { (name, bytes) ->
            val file = actual.getJSONObject(name)
            assertEquals(bytes.size.toLong(), file.getLong("size")); assertEquals(sha256(bytes), file.getString("sha256"))
            assertTrue(file.getBoolean(peer.fuseEvidenceField))
            assertEquals("actual /proc/self/mountinfo", file.getString("mount_metadata_origin"))
            assertTrue(file.getJSONObject("mount").getString("filesystem_type").startsWith("fuse"))
        }
    }

    private fun samples(direction: String) = linkedMapOf(
        "$direction-binary.bin" to ByteArray(262149) { ((it * 31 + 7) and 255).toByte() },
        "$direction-empty.bin" to byteArrayOf(),
        (if (direction == "server") "远程文件.txt" else "安卓文件.txt") to "RdpSync synthetic 文件\n\u0000🙂\n".toByteArray())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun ownedFileCount(root: java.nio.file.Path) = Files.newDirectoryStream(root).use { it.count() }
    private fun action(action: String) = request("POST", "/transfer/$action")
    private fun request(method: String, path: String): JSONObject = Socket().use { socket ->
        socket.connect(InetSocketAddress("10.0.2.2", if (path == "/state") peer.statePort else peer.transferPort), 2_000)
        socket.soTimeout = 5_000
        socket.getOutputStream().write("$method $path HTTP/1.0\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = socket.getInputStream().read(buffer)
            if (count < 0) break
            check(bytes.size() + count <= 65536) { "Synthetic fixture response exceeded bound" }
            bytes.write(buffer, 0, count)
        }
        val response = bytes.toString("UTF-8")
        assertTrue("Fixed fixture HTTP response", response.startsWith("HTTP/1.0 200") || response.startsWith("HTTP/1.0 202"))
        JSONObject(response.substringAfter("\r\n\r\n"))
    }
    private fun await(label: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        fail("Diagnostic boundary timed out: $label; ${RdpConnector.getDiag()}")
    }
    private fun saveEvidence() {
        File(evidence, "measurements.json").writeText(measurements.toString(2))
        // Only generated evidence under this test's fixed folder, never provider/user paths.
        val destination = "/sdcard/Download/rdpsync-$evidenceName"
        for (command in listOf("mkdir -p $destination", "cp -R ${evidence.absolutePath}/. $destination/")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }
}
