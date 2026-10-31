package com.rdp.sync

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real DocumentsUI only. No grant injection, first-match fallback, or repeated gesture. */
internal class SharedSpaceDocumentsUi(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    private val evidence: File,
    private val measurements: JSONObject
) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val documentPackages = setOf("com.android.documentsui", "com.google.android.documentsui")
    val folder = "rdpsync-drive-${SystemClock.uptimeMillis()}"
    private val downloads get() = "/sdcard/Download/$folder"
    private val pickerEvents = JSONArray().also { measurements.put("shared_space_picker_events", it) }
    private val seededMediaUris = linkedMapOf<String, Pair<String, String>>()
    private val expectedSourceHashes = linkedMapOf<String, String>()
    private val originalService = automation.serviceInfo
    private var cleaned = false

    init {
        check(Build.VERSION.SDK_INT >= 34) { "The isolated API35 SAF fixture records both shell streams" }
        require(folder.matches(Regex("rdpsync-drive-[0-9]+")))
        require(evidence.isDirectory || evidence.mkdirs())
        automation.serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = originalService.eventTypes; feedbackType = originalService.feedbackType
            notificationTimeout = originalService.notificationTimeout
            flags = originalService.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        measurements.put("shared_space_documents_folder", folder)
    }

    fun seedSources(sources: Map<String, File>) {
        check(expectedSourceHashes.isEmpty()) { "Seed once per owned fixture folder" }
        require(sources.size in 1..8)
        sources.forEach { (name, file) ->
            requireSafeName(name)
            // The scenario creates synthetic fixtures beneath its own evidence directory.
            require(file.canonicalPath.startsWith(evidence.canonicalPath + File.separator) && file.isFile)
            require(file.length() <= 64L * 1024 * 1024)
            expectedSourceHashes[name] = file.inputStream().use { sha256(it) }
            seed("source", name, file)
        }
        val sentinel = File(evidence, "shared-space-export-sentinel.bin").apply {
            writeText("RdpSync synthetic export collision sentinel; never replace this file.\n")
        }
        seed("destination", "remote-roundtrip.bin", sentinel)
        assertEquals("Owned sources published without mutation", expectedSourceHashes, sourceHashes())
        assertEquals(sentinel.inputStream().use { sha256(it) }, destinationHashes().getValue("remote-roundtrip.bin"))
    }

    private fun seed(child: String, name: String, file: File) {
        require(child in setOf("source", "destination")); requireSafeName(name)
        val relative = "Download/$folder/$child/"
        seedShell("content insert --uri content://media/external_primary/downloads --bind relative_path:s:$relative --bind _display_name:s:$name --bind mime_type:s:application/octet-stream --bind owner_package_name:s:com.android.shell --bind is_pending:i:1")
        val query = seedShell("content query --uri content://media/external_primary/downloads?includePending=1 --projection _id:_display_name:relative_path:owner_package_name:is_pending --where relative_path='$relative'")
        val row = query.lineSequence().single { it.contains("_display_name=$name,") }
        val id = requireNotNull(Regex("(?:^|[ ,])_id=([0-9]+)(?:,|$)").find(row)).groupValues[1]
        val uri = "content://media/external_primary/downloads/$id"
        seededMediaUris[uri] = relative to name
        assertTrue(row.contains("relative_path=$relative,") && row.contains("owner_package_name=com.android.shell") && row.contains("is_pending=1"))
        assertTrue(seedShell("content write --uri $uri", file).isBlank())
        seedShell("content update --uri $uri --bind is_pending:i:0")
        val published = seedShell("content query --uri $uri --projection _display_name:relative_path:owner_package_name:is_pending:_size")
        assertTrue(published.contains("_display_name=$name,") && published.contains("relative_path=$relative,") &&
            published.contains("owner_package_name=com.android.shell") && published.contains("is_pending=0"))
        val actualHash = boundedCommand("content read --uri $uri", timeoutMs = 90_000) { sha256(it, file.length()) }
        assertEquals("Published shell-owned source bytes", file.inputStream().use { sha256(it) }, actualHash)
        var denied = false
        try { context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.read() } }
        catch (_: SecurityException) { denied = true }
        assertTrue("App must need actual SAF consent for shell-owned source", denied)
        val entries = measurements.optJSONArray("synthetic_seed_media_store") ?: JSONArray().also { measurements.put("synthetic_seed_media_store", it) }
        entries.put(JSONObject().put("uri", uri).put("relative_path", relative).put("name", name).put("size", file.length())
            .put("sha256", actualHash).put("owner", "com.android.shell").put("published", true).put("target_read_denied_before_saf", true))
    }

    fun selectImports(names: List<String>) {
        require(names.isNotEmpty() && names.distinct().size == names.size && names.all { it in expectedSourceHashes })
        awaitPicker()
        tapDocumentAction("show-document-roots") { it.contentDescription?.toString() in setOf("Show roots", "Open navigation drawer") }
        tapDocumentLabel("Downloads", ancestorResourceSuffix = "/roots_list")
        tapDocumentLabel(folder, ancestorResourceSuffix = "/dir_list")
        tapDocumentLabel("source", ancestorResourceSuffix = "/dir_list")
        names.forEachIndexed { index, name -> tapDocumentLabel(name, longPress = index == 0, ancestorResourceSuffix = "/dir_list") }
        tapDocumentAction("confirm-owned-import-multiselect") { node ->
            node.viewIdResourceName?.endsWith("/action_menu_select") == true ||
                node.text?.toString() in setOf("Open", "OPEN", "Select", "SELECT") || node.contentDescription?.toString() in setOf("Open", "Select")
        }
        awaitAppReturn()
    }

    fun chooseOwnedExportTree() {
        awaitPicker()
        navigateObservedTreeDestination()
        tapDocumentAction("grant-exact-owned-export-folder") { it.text?.toString()?.uppercase() == "USE THIS FOLDER" }
        tapDocumentAction("confirm-exact-owned-tree-grant") { it.text?.toString()?.uppercase() == "ALLOW" }
        awaitAppReturn()
    }

    fun cancelImportPicker() = cancelPicker(tree = false)

    fun cancelExportTreePicker() = cancelPicker(tree = true)

    private fun cancelPicker(tree: Boolean) {
        awaitPicker()
        val record = JSONObject().put("started_elapsed_ms", SystemClock.elapsedRealtime())
        val records = measurements.optJSONArray("shared_space_picker_back") ?: JSONArray().also { measurements.put("shared_space_picker_back", it) }
        records.put(record)
        // DocumentsUI restores the previous directory across picker invocations.
        // Back there navigates to its parent; it is not a cancellation. Select
        // the observed Downloads root first, then prove we are at that root
        // before issuing the one cancellation gesture. No picker data is reset.
        record.put("initial_breadcrumbs", JSONArray(documentBreadcrumbs()))
        if (tree) {
            navigateToTreeRoot(record)
        } else {
            tapDocumentAction("cancel-picker-show-roots") {
                it.contentDescription?.toString() in setOf("Show roots", "Open navigation drawer")
            }
            tapDocumentLabel("Downloads", ancestorResourceSuffix = "/roots_list")
            waitFor("Downloads root before one picker cancellation", 15_000) {
                documentBreadcrumbs() == listOf("Downloads")
            }
        }
        record.put("cancel_root_breadcrumbs", JSONArray(documentBreadcrumbs()))
        sendSystemBack(record) // Exactly one physical down/up pair; no retry.
        awaitAppReturn()
        record.put("observed_app_return_elapsed_ms", SystemClock.elapsedRealtime()).put("documentsui_absent", true)
    }

    fun awaitPicker() = waitFor("foreground real system DocumentsUI", 15_000) {
        val active = automation.rootInActiveWindow
        try { active?.packageName?.toString() in documentPackages }
        finally { active?.recycle() }
    }

    private fun awaitAppReturn() = waitFor("picker closed and target app is active", 15_000) {
        val nodes = documentNodes { true }
        val active = automation.rootInActiveWindow
        try { nodes.isEmpty() && active?.packageName?.toString() == context.packageName }
        finally { nodes.forEach { it.recycle() }; active?.recycle() }
    }

    fun sourceHashes(): Map<String, String> = directoryHashes("source")
    fun destinationHashes(): Map<String, String> = directoryHashes("destination")
    private fun directoryHashes(child: String): Map<String, String> {
        require(child in setOf("source", "destination"))
        val raw = shell("find $downloads/$child -maxdepth 1 -type f -exec sha256sum {} +")
        return raw.lineSequence().filter { it.isNotBlank() }.associate { line ->
            val match = requireNotNull(Regex("^([0-9a-f]{64})  (.+)$").matchEntire(line)) { raw }
            val path = match.groupValues[2]
            require(path.startsWith("$downloads/$child/") && File(path).parent == "$downloads/$child")
            File(path).name to match.groupValues[1]
        }
    }

    /** Delete only original shell-owned rows after checking exact name/path/owner again. */
    fun cleanup(): JSONArray {
        check(!cleaned); cleaned = true
        return JSONArray().also { results ->
            try {
                seededMediaUris.forEach { (uri, expected) ->
                    require(uri.matches(Regex("content://media/external_primary/downloads/[0-9]+")))
                    val item = JSONObject().put("uri", uri); results.put(item)
                    try {
                        val current = seedShell("content query --uri $uri --projection _display_name:relative_path:owner_package_name")
                        require(current.lineSequence().count { it.startsWith("Row:") } == 1)
                        require(current.contains("_display_name=${expected.second},") && current.contains("relative_path=${expected.first},") && current.contains("owner_package_name=com.android.shell"))
                        item.put("verified_owned_path", expected.first).put("verified_owned_name", expected.second)
                        seedShell("content delete --uri $uri")
                        item.put("removed", seedShell("content query --uri $uri --projection _id") == "No result found.")
                    } catch (failure: Exception) { item.put("removed", false).put("error", failure.javaClass.simpleName) }
                }
            } finally { automation.serviceInfo = originalService }
        }
    }

    private fun requireSafeName(name: String) {
        require(name.isNotEmpty() && name.length <= 120 && name !in setOf(".", "..") &&
            name.none { it.isWhitespace() || it in "/\\:'\",;&|<>?*" || it.code < 32 })
    }

    /** Stream large files. The bound includes Binder launch, readers, and writes. */
    private fun seedShell(command: String, input: File? = null): String {
        require(command.startsWith("content ") && command.contains("--uri content://media/external_primary/downloads"))
        return boundedCommand(command, input, if (input == null) 20_000 else 90_000) { readBounded(it, 16384) }.trim()
    }

    private fun <T> boundedCommand(command: String, input: File? = null, timeoutMs: Long = 20_000, read: (InputStream) -> T): T {
        val records = measurements.optJSONArray("synthetic_seed_shell") ?: JSONArray().also { measurements.put("synthetic_seed_shell", it) }
        check(records.length() < 160)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + timeoutMs
        val record = JSONObject().put("command", command).put("started_elapsed_ms", started).put("deadline_elapsed_ms", deadline)
            .put("stdin_bytes", input?.length() ?: 0)
        records.put(record)
        val closing = AtomicBoolean(false)
        val owned = AtomicReference<Array<ParcelFileDescriptor>?>()
        val workers = Executors.newFixedThreadPool(4) { task -> Thread(task, "shared-space-documents-command").apply { isDaemon = true } }
        val completed = workers.submit<T> {
            check(!closing.get()) { "Command scope closed before launch" }
            val pipes = automation.executeShellCommandRwe(command)
            var workerFailure: Throwable? = null
            try {
                owned.set(pipes)
                check(!closing.get()) { "Command descriptors arrived after deadline" }
                require(pipes.size == 3)
                val stdout = workers.submit<T> { ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use(read) }
                val stderr = workers.submit<String> { ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).use { readBounded(it, 16384) } }
                val stdin = workers.submit { ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output -> input?.inputStream()?.use { it.copyTo(output, 65536) } } }
                stdin.get()
                val out = stdout.get()
                val err = stderr.get()
                synchronized(record) { record.put("stdout", out).put("stderr", err).put("completed_elapsed_ms", SystemClock.elapsedRealtime()) }
                assertTrue("Synthetic command failed: $err", err.isBlank())
                out
            } catch (failure: Throwable) { workerFailure = failure; throw failure }
            finally {
                // A late Binder return still owns and closes every descriptor.
                var closeFailure: Throwable? = null
                pipes.forEach { pipe -> try { pipe.close() } catch (failure: Throwable) {
                    if (closeFailure == null) closeFailure = failure else closeFailure!!.addSuppressed(failure)
                } }
                owned.compareAndSet(pipes, null)
                closeFailure?.let { if (workerFailure != null) workerFailure!!.addSuppressed(it) else throw it }
            }
        }
        var primaryFailure: Throwable? = null
        try { return completed.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS) }
        catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            primaryFailure = failure
            synchronized(record) { record.put("failure", failure.javaClass.name) }
            throw failure
        } finally {
            closing.set(true)
            var cleanupFailure: Throwable? = null
            fun clean(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
            } }
            owned.getAndSet(null)?.forEach { pipe -> clean { pipe.close() } }
            completed.cancel(true)
            workers.shutdownNow()
            clean { assertTrue("Documents command workers terminated", workers.awaitTermination(2, TimeUnit.SECONDS)) }
            synchronized(record) { record.put("cleanup_elapsed_ms", SystemClock.elapsedRealtime()) }
            cleanupFailure?.let { if (primaryFailure != null) primaryFailure!!.addSuppressed(it) else throw it }
        }
    }

    private fun readBounded(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= limit) { "Synthetic command output exceeded bound" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
    private fun documentBreadcrumbs(): List<String> {
        // AOSP15 HorizontalBreadcrumb enables only the last title for styling.
        // Read visible disabled ancestors too; all gesture lookups still demand
        // enabled targets. The exact owned parent+destination guard is unchanged.
        val nodes = documentNodes(requireEnabled = false) { it.viewIdResourceName?.endsWith("/breadcrumb_text") == true }
        return try {
            val ordered = nodes.sortedBy { Rect().also(it::getBoundsInScreen).left }
            val observations = measurements.optJSONArray("tree_breadcrumb_observations") ?: JSONArray().also { measurements.put("tree_breadcrumb_observations", it) }
            if (observations.length() < 64) observations.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime())
                .put("nodes", JSONArray().also { values -> ordered.forEach { node ->
                    values.put(JSONObject().put("text", node.text).put("enabled", node.isEnabled)
                        .put("window_id", node.windowId).put("node_identity_hash", node.hashCode())
                        .put("bounds", Rect().also(node::getBoundsInScreen).toShortString()))
                } }))
            ordered.map { requireNotNull(it.text).toString() }
        } finally { nodes.forEach { it.recycle() } }
    }

    private fun navigateToTreeRoot(record: JSONObject) {
        // ACTION_OPEN_DOCUMENT_TREE has no roots drawer on this pinned image.
        // Navigate only observed ancestors, one Back per proven depth change;
        // these are navigation gestures, never retries of a failed cancellation.
        val rootTitle = shell("settings get global device_name")
        require(rootTitle.isNotBlank() && rootTitle != "null")
        var crumbs = emptyList<String>()
        fun relative(path: List<String>) = if (path.firstOrNull() == rootTitle) path.drop(1) else path
        waitFor("unambiguous tree-picker path before cancellation", 15_000) {
            crumbs = documentBreadcrumbs()
            crumbs == listOf(rootTitle) || (relative(crumbs).let { it.size in 1..3 && it.first() == "Download" && it.distinct().size == it.size })
        }
        val navigation = JSONArray().also { record.put("tree_root_navigation", it) }
        repeat(3) {
            if (crumbs == listOf(rootTitle)) return
            val before = relative(crumbs)
            val expectedParent = before.dropLast(1)
            val step = JSONObject().put("before_breadcrumbs", JSONArray(crumbs))
            navigation.put(step)
            sendSystemBack(step)
            waitFor("tree picker moved to the exact observed parent", 15_000) {
                val current = documentBreadcrumbs()
                val matched = if (expectedParent.isEmpty()) current == listOf(rootTitle)
                    else current == expectedParent || current == listOf(rootTitle) + expectedParent
                if (matched) crumbs = current
                matched
            }
            step.put("after_breadcrumbs", JSONArray(crumbs))
        }
        assertEquals("Tree cancellation only at verified storage root", listOf(rootTitle), crumbs)
    }

    private fun navigateObservedTreeDestination() {
        // Actual API35 ACTION_OPEN_DOCUMENT_TREE has no roots drawer in this
        // single-volume view. Navigate only its observed internal-storage root
        // or the already selected exact owned destination, never grant the root.
        // Pinned AOSP15 ExternalStorageProvider uses Settings.Global.DEVICE_NAME
        // for the primary volume title; do not guess it from Build.MODEL.
        val rootTitle = shell("settings get global device_name")
        require(rootTitle.isNotBlank() && rootTitle != "null")
        var crumbs = emptyList<String>()
        // A newly opened picker may briefly expose duplicate breadcrumb nodes.
        // They remain raw evidence and NOT ready: never dedupe/choose the first
        // node or branch on a merely nonempty transition snapshot.
        val ownedPaths = setOf(listOf("Download", folder, "destination"), listOf(rootTitle, "Download", folder, "destination"))
        waitFor("observed unambiguous exact tree-picker path", 15_000) {
            crumbs = documentBreadcrumbs()
            crumbs == listOf(rootTitle) || crumbs in ownedPaths
        }
        val navigation = JSONObject().put("initial_breadcrumbs", JSONArray(crumbs))
            .put("expected_internal_root_title", rootTitle)
            .put("expected_owned_folder", folder).put("elapsed_ms", SystemClock.elapsedRealtime())
        val records = measurements.optJSONArray("tree_picker_navigation") ?: JSONArray().also { measurements.put("tree_picker_navigation", it) }
        records.put(navigation)
        if (crumbs.takeLast(2) == listOf(folder, "destination")) {
            navigation.put("already_at_exact_owned_destination", true)
        } else {
            assertEquals("Only the observed fixture internal-storage root is accepted", listOf(rootTitle), crumbs)
            tapDocumentLabel("Download", ancestorResourceSuffix = "/dir_list")
            tapDocumentLabel(folder, ancestorResourceSuffix = "/dir_list")
            tapDocumentLabel("destination", ancestorResourceSuffix = "/dir_list")
        }
        waitFor("actual exact owned destination breadcrumb before grant", 15_000) {
            documentBreadcrumbs().takeLast(2) == listOf(folder, "destination")
        }
        navigation.put("final_breadcrumbs", JSONArray(documentBreadcrumbs()))
            .put("root_grant_attempted", false)
    }

    private fun tapDocumentLabel(label: String, longPress: Boolean = false, ancestorResourceSuffix: String? = null) =
        tapDocumentAction(label, longPress) { it.text?.toString() == label &&
            (ancestorResourceSuffix == null || ancestorResourceId(it, ancestorResourceSuffix) != null) }

    private fun ancestorResourceId(node: AccessibilityNodeInfo, suffix: String): String? {
        var ancestor = node.parent
        try {
            repeat(32) {
                val current = ancestor ?: return null
                val resource = current.viewIdResourceName
                if (resource?.endsWith(suffix) == true) return resource
                ancestor = current.parent
                current.recycle()
            }
            return null
        } finally { ancestor?.recycle() }
    }
    private fun tapDocumentAction(reason: String, longPress: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val attempt = JSONObject().put("reason", reason).put("long_press", longPress)
            .put("readiness_start_ms", SystemClock.elapsedRealtime())
        pickerEvents.put(attempt)
        val observations = JSONArray()
        attempt.put("readiness_observations", observations)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var target: AccessibilityNodeInfo? = null
        while (SystemClock.elapsedRealtime() < deadline && target == null) {
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            val candidates = documentNodes(predicate = predicate)
            if (candidates.size != 1) {
                candidates.forEach { it.recycle() }; SystemClock.sleep(100); continue
            }
            val first = candidates.single()
            try {
                val before = Rect().also(first::getBoundsInScreen)
                val observation = JSONObject().put("before_idle_ms", SystemClock.elapsedRealtime())
                    .put("window_id", first.windowId).put("package", first.packageName)
                    .put("bounds", before.toShortString())
                assertTrue("Picker readiness observations must stay bounded", observations.length() < 32)
                observations.put(observation)
                val remaining = deadline - SystemClock.elapsedRealtime()
                assertTrue("Picker readiness deadline expired", remaining > 0)
                // Wait for actual accessibility-event quiet, then requery the same
                // live node. Never resend a gesture if navigation does not occur.
                automation.waitForIdle(500, minOf(5_000, remaining))
                val freshCandidates = documentNodes(predicate = predicate)
                if (freshCandidates.size == 1) {
                    val fresh = freshCandidates.single()
                    val after = Rect().also(fresh::getBoundsInScreen)
                    val stable = first == fresh && first.windowId == fresh.windowId &&
                        first.packageName?.toString() == fresh.packageName?.toString() && before == after && !after.isEmpty
                    observation.put("after_idle_ms", SystemClock.elapsedRealtime())
                        .put("after_bounds", after.toShortString()).put("same_live_node_and_geometry", stable)
                    if (stable) target = fresh else fresh.recycle()
                } else freshCandidates.forEach { it.recycle() }
            } finally { first.recycle() }
        }
        val node = requireNotNull(target) { "No stable unique observed DocumentsUI target: $reason" }
        try {
            assertTrue("Picker readiness exceeded original deadline before gesture", SystemClock.elapsedRealtime() < deadline)
            val expectedBounds = Rect().also(node::getBoundsInScreen)
            val expectedWindow = node.windowId
            val expectedPackage = node.packageName?.toString()
            assertTrue(node.refresh()); assertTrue(node.isVisibleToUser && node.isEnabled); assertTrue(predicate(node))
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            assertTrue("Picker target changed before physical gesture", !bounds.isEmpty && bounds == expectedBounds &&
                node.windowId == expectedWindow && node.packageName?.toString() == expectedPackage)
            attempt.put("package", node.packageName).put("resource_id", node.viewIdResourceName)
                .put("window_id", node.windowId).put("bounds", bounds.toShortString())
                .put("roots_list_ancestor", ancestorResourceId(node, "/roots_list"))
                .put("uptime_before_down", SystemClock.uptimeMillis())
            val injections = JSONArray()
            attempt.put("injections", injections)
            physicalTap(bounds.exactCenterX(), bounds.exactCenterY(), if (longPress) 650 else 40, injections)
            attempt.put("uptime_after_up", SystemClock.uptimeMillis())
            screenshot("documentsui-${pickerEvents.length()}-after-single-gesture")
        } finally { node.recycle() }
    }
    private fun documentNodes(requireEnabled: Boolean = true, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && (!requireEnabled || node.isEnabled) && predicate(node) && found.none { it == node }) found += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> try { visit(child) } finally { child.recycle() } }
        }
        automation.windows.forEach { window ->
            try {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) window.root?.let { root ->
                    try { if (root.packageName?.toString() in documentPackages) visit(root) } finally { root.recycle() }
                }
            } finally { window.recycle() }
        }
        return found
    }
    private fun physicalTap(x: Float, y: Float, holdMs: Long, trace: JSONArray? = null) {
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = fixtureFingerEvent(down, SystemClock.uptimeMillis(), action, x, y)
            val observation = JSONObject().put("action", action).put("x", x).put("y", y)
                .put("down_time_ms", event.downTime).put("event_time_ms", event.eventTime)
                .put("wall_ms", System.currentTimeMillis()).put("source", event.source)
                .put("tool_type", event.getToolType(0)).put("device_id", event.deviceId)
            trace?.put(observation)
            try {
                val accepted = automation.injectInputEvent(event, true)
                observation.put("accepted", accepted).put("completed_uptime_ms", SystemClock.uptimeMillis())
                assertTrue("System picker physical injection accepted", accepted)
            } catch (failure: Throwable) {
                observation.put("injection_exception", failure.javaClass.name)
                throw failure
            } finally { event.recycle() }
        }
        var primaryFailure: Throwable? = null
        try { inject(MotionEvent.ACTION_DOWN); SystemClock.sleep(holdMs) }
        catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally { try { inject(MotionEvent.ACTION_UP) } catch (failure: Throwable) {
            if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
        } }
    }
    private fun sendSystemBack(evidence: JSONObject) {
        val events = JSONArray(); evidence.put("events", events)
        val time = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = KeyEvent(time, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0)
            val record = JSONObject().put("action", action).put("wall_ms", System.currentTimeMillis())
                .put("elapsed_ms", SystemClock.elapsedRealtime()).put("event_time_ms", event.eventTime)
                .put("down_time_ms", event.downTime).put("source", event.source).put("flags", event.flags)
            events.put(record)
            try {
                val accepted = automation.injectInputEvent(event, true)
                record.put("accepted", accepted).put("completed_elapsed_ms", SystemClock.elapsedRealtime())
                assertTrue("Single actual route Back injection accepted", accepted)
            } catch (failure: Throwable) { record.put("exception", failure.javaClass.name); throw failure }
        }
        var primaryFailure: Throwable? = null
        try { inject(KeyEvent.ACTION_DOWN) }
        catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally { try { inject(KeyEvent.ACTION_UP) } catch (failure: Throwable) {
            if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
        } }
        evidence.put("up_completed_elapsed_ms", SystemClock.elapsedRealtime())
    }
    private fun waitFor(reason: String, timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeBy(100)
            instrumentation.waitForIdleSync()
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("DocumentsUI readiness deadline: $reason")
    }
    private fun sha256(input: InputStream, expectedSize: Long? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var bytes = 0L
        while (true) { val count = input.read(buffer); if (count < 0) break
            bytes += count; require(bytes <= (expectedSize ?: 64L * 1024 * 1024)); digest.update(buffer, 0, count)
        }
        expectedSize?.let { assertEquals("Bounded synthetic readback size", it, bytes) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun screenshot(name: String) {
        require(name.matches(Regex("documentsui-[0-9]+-after-single-gesture")))
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun shell(command: String): String = boundedCommand(command) { readBounded(it, 65536) }.trim()
}
