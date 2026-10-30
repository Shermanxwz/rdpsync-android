package com.rdp.sync

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.rdp.sync.data.Device
import com.rdp.sync.manager.SyncDirection
import com.rdp.sync.display.SessionDisplaySettings
import com.rdp.sync.network.RdpInputDispatcher
import com.rdp.sync.network.SharedDriveState
import com.rdp.sync.network.SharedDriveMode
import com.rdp.sync.network.SharedDriveStatus
import com.rdp.sync.security.*
import com.rdp.sync.transfer.*
import com.rdp.sync.ui.MainActivity
import com.rdp.sync.ui.screens.*
import com.rdp.sync.ui.sharedspace.*
import com.rdp.sync.sharedspace.SharedSpaceItem
import com.rdp.sync.sharedspace.SharedSpaceListing
import com.rdp.sync.ui.theme.RdpSyncTheme
import com.rdp.sync.ui.transfer.FileTransferDialog
import com.rdp.sync.ui.transfer.FileTransferDirection
import com.rdp.sync.viewmodel.UiState
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** UI-only evidence. Synthetic profiles, no network, real credentials, pins, or filesystem sharing. */
@RunWith(AndroidJUnit4::class)
class ProductUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence by lazy { File(instrumentation.targetContext.getExternalFilesDir(null), "product-ui-evidence").apply { mkdirs() } }
    private val capturedFiles = mutableListOf<JSONObject>()
    private var activePrefix = ""
    private var capturePlan: Set<String> = emptySet()
    private val profile = Device(id = 900081, name = "Design studio · 设计工作室", host = "workstation-long-name.example.test", username = "example-user-with-long-name", password = "")

    @Test fun englishLight() = matrix("en", false, 1f)
    @Test fun englishDark() = matrix("en", true, 1f)
    @Test fun chineseLight() = matrix("zh", false, 1f)
    @Test fun chineseDark() = matrix("zh", true, 1f)
    @Test fun narrowLargeText() = matrix("en", false, 1.5f, 320)
    @Test fun expandedWidth() {
        val actual = instrumentation.targetContext.resources.configuration.screenWidthDp
        assumeTrue("Run on an emulator window at least 600dp wide; not evidence of physical foldable behavior", actual >= 600)
        matrix("zh", false, 1.5f, 600)
    }

    private fun matrix(language: String, dark: Boolean, fontScale: Float, constrainedWidth: Int? = null) {
        val base = compose.activity
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language)); this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            constrainedWidth?.let { screenWidthDp = it }
        }
        val localized = base.createConfigurationContext(config)
        val label: (Int) -> String = { localized.getString(it) }
        val screen = mutableStateOf("profiles")
        val webDavCompletion = mutableLongStateOf(0)
        val syntheticInput = RdpInputDispatcher({ _, _, _, _ -> true }, { _, _, _ -> 1 }, { _, _, _ -> 1 }, { _, _, _ -> 1 })
        var driveStops = 0
        var localMutations = 0
        var driveApprovals = 0
        var approved = 0
        var deleted = 0
        var saved = 0
        var webDavTests = 0
        var webDavSaves = 0
        val endpoint = requireNotNull(CertificateEndpoint.of(profile.id, profile.host, 3389, ""))
        val challenge = CertificateChallengeSnapshot(1, "a1".repeat(32), "CN=synthetic.example.test", "CN=Isolated UI fixture", endpoint.host, endpoint.port, endpoint.effectiveServerName, "untrusted")
        val firstTrust = requireNotNull(CertificateTrustPolicy.propose(endpoint, "ui-fixture", challenge, null))
        val changedTrust = requireNotNull(CertificateTrustPolicy.propose(endpoint, "ui-fixture", challenge.copy(reason = "changed"), "b2".repeat(32)))
        val prefix = "$language-${if (dark) "dark" else "light"}-${constrainedWidth ?: config.screenWidthDp}dp-${fontScale}x"
        activePrefix = prefix
        capturePlan = when {
            constrainedWidth == 320 -> setOf("profiles", "editor-validation", "error", "save-busy-action", "settings-options", "session-menu-open", "session-menu-shortcuts", "locked", "space-busy-error", "space-invalid-name", "space_delete_dialog", "drive-write-consent", "drive-stop-confirmation", "plaintext-warning", "previous-fingerprint", "progress")
            constrainedWidth == 600 -> setOf("profiles", "editor", "detail", "settings-options", "space", "preconnect-drive-consent", "drive_consent", "changed", "staged", "session-menu-open", "space_name_rename")
            language == "en" && !dark -> setOf("profiles", "empty", "editor", "detail", "webdav", "plaintext-warning", "trust", "changed", "staged", "receive", "space", "space_progress", "drive_consent", "drive-write-consent", "drive_off", "drive_requested", "drive_accepted", "drive_accepted_rw", "drive_unsupported", "drive_revoked", "delete-confirmation")
            language == "en" -> setOf("profiles", "editor", "detail", "webdav", "changed", "staged", "space", "drive-write-consent", "settings", "session-menu-open")
            !dark -> setOf("profiles", "empty", "editor", "detail", "webdav", "trust", "receive", "space", "drive_consent", "drive_accepted")
            else -> setOf("profiles", "editor", "plaintext-warning", "webdav", "changed", "staged", "space", "drive-write-consent", "settings", "session-menu-open")
        }
        val methodName = when {
            constrainedWidth == 320 -> "narrowLargeText"
            constrainedWidth == 600 -> "expandedWidth"
            language == "en" && !dark -> "englishLight"
            language == "en" -> "englishDark"
            !dark -> "chineseLight"
            else -> "chineseDark"
        }
        val report = JSONObject().put("method", methodName).put("started_at_epoch_ms", System.currentTimeMillis()).put("locale", language).put("dark", dark).put("font_scale", fontScale)
            .put("constrained_width_dp", constrainedWidth ?: JSONObject.NULL).put("device_width_dp", base.resources.configuration.screenWidthDp)
            .put("physical_foldable_test", false).put("synthetic_ui_only", true).put("credential_screenshots", false)
            .put("device_ui_mode", base.resources.configuration.uiMode).put("test_ui_mode", config.uiMode)
            .put("sdk", Build.VERSION.SDK_INT).put("build_fingerprint", Build.FINGERPRINT)
            .put("device_density_dpi", base.resources.displayMetrics.densityDpi)
            .put("source_commit", InstrumentationRegistry.getArguments().getString("sourceCommit") ?: "not supplied")
            .put("package", instrumentation.targetContext.packageName)
            .put("expected_files", JSONArray(capturePlan.sorted().map { "$prefix-$it.png" }))
        var originalBarAppearance: Pair<Boolean, Boolean>? = null
        var primaryFailure: Throwable? = null
        try {
        compose.runOnUiThread {
            val bars = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            originalBarAppearance = bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
            // Match the explicit UI-only theme variant. Record actual flags and restore
            // them afterward; production theme/inset behavior is not modified.
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
            report.put("appearance_light_status_bars", bars.isAppearanceLightStatusBars)
                .put("appearance_light_navigation_bars", bars.isAppearanceLightNavigationBars)
            compose.activity.setContent {
                val actualDensity = LocalDensity.current.density
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                    LocalDensity provides Density(actualDensity, fontScale)) {
                    RdpSyncTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(if (constrainedWidth == null) Modifier.fillMaxSize() else Modifier.width(constrainedWidth.dp).fillMaxHeight()) {
                                    when (screen.value) {
                                        "profiles", "empty", "loading" -> DeviceListScreen(
                                            UiState(devices = if (screen.value == "profiles") listOf(profile, Device(id = 900082, name = "Lab / 实验室", host = "lab.example.test", username = "example", password = "").also { it.credentialUnavailable = true }) else emptyList(),
                                                isLoading = screen.value == "loading", webDavSettingsLoaded = true), {}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {})
                                        "detail" -> DeviceDetailScreen(profile, {}, {}, {}, { deleted++ }, onConnectWithSharedDrive = { driveApprovals++ })
                                        "editor" -> DeviceEditScreen(null, { saved++ }, {})
                                        "saving" -> DeviceEditScreen(profile, { saved++ }, {}, isSaving = true)
                                        "error" -> DeviceEditScreen(profile, { saved++ }, {}, errorMessage = label(R.string.credential_locked_help))
                                        "settings" -> DisplaySettingsDialog(SessionDisplaySettings(), profile, "1280 × 720 · 60 fps", label(R.string.waiting_measurement), "", false, false,
                                            {}, {}, {}, {}, {}, {})
                                        "menu" -> Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomEnd) {
                                            SessionFloatingControls(syntheticInput, true, false, false, true, {}, {}, {}, {}, {}, {}, {}, {}, {})
                                        }
                                        "locked" -> DeviceEditScreen(profile.copy(credentialUnavailable = true), { saved++ }, {})
                                        "space", "space_empty", "space_busy", "space_progress" -> SharedSpaceScreen(
                                            SharedSpaceUiState(listing = SharedSpaceListing("", if (screen.value == "space_empty") emptyList() else listOf(
                                                SharedSpaceItem("Reports · 报告", "Reports · 报告", true, 0, 0),
                                                SharedSpaceItem("quarterly-report-long-filename.txt", "quarterly-report-long-filename.txt", false, 524288, 0)),
                                                524288, 1073217536), loading = false, working = screen.value == "space_progress", cancellable = screen.value == "space_progress",
                                                progress = if (screen.value == "space_progress") TransferProgress("report.txt", 8192, 32768) else null,
                                                error = if (screen.value == "space_busy") label(R.string.space_busy) else null), {}, {}, {}, {}, {}, {})
                                        "space_name_new" -> SharedSpaceNameDialog(null, "Reports", { localMutations++ }, {})
                                        "space_name_rename" -> SharedSpaceNameDialog("report.txt", "Reports", { localMutations++ }, {})
                                        "space_delete_dialog" -> SharedSpaceDeleteDialog("Reports/report.txt", { localMutations++ }, {})
                                        "drive_consent" -> SharedDriveConsentDialog({ driveApprovals++ }, {}, serverLabel = "${profile.name} · ${profile.host}:${profile.port}")
                                        "drive_off", "drive_requested", "drive_capable", "drive_accepted", "drive_accepted_rw", "drive_used", "drive_unsupported", "drive_rejected", "drive_revoked", "drive_transport_unavailable", "drive_protocol_failed", "drive_unknown" -> {
                                            val status = SharedDriveStatus.valueOf(screen.value.removePrefix("drive_").removeSuffix("_rw").uppercase(Locale.ROOT))
                                            SharedDriveStatusDialog(SharedDriveState(generation = if (status == SharedDriveStatus.OFF || status == SharedDriveStatus.UNKNOWN) 0 else 5,
                                                mode = if (status == SharedDriveStatus.OFF || status == SharedDriveStatus.UNKNOWN) SharedDriveMode.OFF else if (screen.value.endsWith("_rw")) SharedDriveMode.READ_WRITE else SharedDriveMode.READ_ONLY, status = status),
                                                {}, { driveStops++ }, {}, {})
                                        }
                                        "webdav", "webdav_busy" -> WebDavSettingsDialog("https://example.test/dav/", "example", "", false, resultMessage = if (webDavCompletion.longValue > 0) label(R.string.status_connected) else null, onDismiss = {}, onSave = { _, _, _ -> webDavSaves++ }, onTest = { _, _, _ -> webDavTests++ }, isTesting = screen.value == "webdav_busy", completionId = webDavCompletion.longValue)
                                        "sync" -> SyncModeDialog(SyncDirection.UPLOAD, {}, {})
                                        "trust", "changed" -> CertificateTrustDialog(if (screen.value == "trust") firstTrust else changedTrust, false, "", {}, { approved++ })
                                        else -> {
                                            val state = when (screen.value) {
                                                "staged" -> TransferState(phase = TransferPhase.STAGED, capability = TransferCapability(true), outbound = listOf(StagedTransferFile("1", "presentation-with-a-long-filename.txt", 16384, "0".repeat(64))), sendRemainingBytes = 33554432)
                                                "receive" -> TransferState(phase = TransferPhase.AWAITING_RECEIVE, capability = TransferCapability(true), remoteOffer = RemoteTransferOffer(1, listOf(RemoteTransferFile(0, "report.txt", 32768))))
                                                "progress" -> TransferState(phase = TransferPhase.RECEIVING, capability = TransferCapability(true), progress = TransferProgress("report.txt", 8192, 32768), cancellable = true)
                                                else -> TransferState(phase = TransferPhase.IDLE, capability = TransferCapability(false, label(R.string.transfer_unsupported)))
                                            }
                                            FileTransferDialog(state, if (screen.value in setOf("receive", "progress")) FileTransferDirection.RECEIVE else FileTransferDirection.SEND,
                                                false, null, {}, {}, {}, {}, {}, {}, {}, {}, {})
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        fun show(name: String, capture: Boolean = true) { compose.runOnIdle { screen.value = name }; compose.waitForIdle(); if (capture) screenshot("$prefix-$name") }
        fun scrollSpaceTo(matcher: SemanticsMatcher) {
            // Lazy rows do not exist until the unique list composes them.
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher)
        }
        show("profiles")
        compose.onNodeWithText(label(R.string.add_device)).assertHeightIsAtLeast(48.dp)
        show("empty")
        compose.onNodeWithText(label(R.string.add_device)).assertHasClickAction().assertHeightIsAtLeast(48.dp)
        show("loading")
        compose.onNodeWithText(label(R.string.loading_profiles)).assertExists()
        show("detail")
        compose.onNodeWithText(label(R.string.delete_confirm)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.delete_title)).assertIsDisplayed()
        screenshot("$prefix-delete-confirmation")
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertEquals("Cancel must not delete a saved profile", 0, deleted)
        compose.onNodeWithText(label(R.string.drive_connect_action)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.drive_connect_ro)).assertIsEnabled()
        compose.onNodeWithText(label(R.string.drive_reconnect_warning)).assertDoesNotExist()
        screenshot("$prefix-preconnect-drive-consent")
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertEquals("Canceling preconnect consent must not connect", 0, driveApprovals)
        show("editor")
        compose.onNodeWithText(label(R.string.save_connection)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.validation_required)).performScrollTo().assertIsDisplayed()
        assertEquals("Invalid fields must not save", 0, saved)
        screenshot("$prefix-editor-validation")
        show("error")
        compose.onNodeWithText(label(R.string.credential_locked_help)).assertExists()
        show("saving")
        compose.onNodeWithText(label(R.string.saving)).performScrollTo().assertIsNotEnabled()
        screenshot("$prefix-save-busy-action")
        show("settings")
        compose.onNodeWithText(label(R.string.done)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.adaptive_quality)).performScrollTo().assertExists()
        screenshot("$prefix-settings-options")
        show("menu")
        compose.onNodeWithContentDescription(label(R.string.session_menu)).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(label(R.string.toggle_keyboard)).assertIsDisplayed()
        screenshot("$prefix-session-menu-open")
        compose.onNodeWithText("Ctrl+Alt+Del").performScrollTo().assertExists()
        screenshot("$prefix-session-menu-shortcuts")
        show("locked")
        compose.onNodeWithText(label(R.string.save_connection)).performScrollTo().assertIsNotEnabled()
        show("space_empty")
        scrollSpaceTo(hasText(label(R.string.space_import)))
        compose.onNodeWithText(label(R.string.space_import)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        show("space", capture = false)
        val fileActions = localized.getString(R.string.space_file_actions, "quarterly-report-long-filename.txt")
        scrollSpaceTo(hasContentDescription(fileActions))
        compose.onNodeWithContentDescription(fileActions).assertIsDisplayed().assertIsEnabled()
        screenshot("$prefix-space")
        compose.onNodeWithContentDescription(fileActions).performClick()
        compose.onNodeWithText(label(R.string.export_action)).assertIsEnabled().performClick()
        show("space_name_new")
        compose.onNodeWithText(label(R.string.space_item_name)).performTextInput("reports/2026")
        compose.onNodeWithText(label(R.string.save)).assertIsNotEnabled()
        screenshot("$prefix-space-invalid-name")
        show("space_name_rename")
        compose.onNodeWithText(label(R.string.save)).assertIsNotEnabled()
        show("space_delete_dialog")
        compose.onNodeWithText(label(R.string.space_delete_warning)).performScrollTo().assertExists()
        assertEquals("No local mutation merely by opening a dialog", 0, localMutations)
        show("space_busy")
        scrollSpaceTo(hasText(label(R.string.space_busy)))
        compose.onNodeWithText(label(R.string.space_busy)).assertIsDisplayed()
        screenshot("$prefix-space-busy-error")
        show("space_progress", capture = false)
        scrollSpaceTo(hasText("report.txt"))
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        screenshot("$prefix-space_progress")
        scrollSpaceTo(hasText(label(R.string.space_import)))
        compose.onNodeWithText(label(R.string.space_import)).assertIsDisplayed().assertIsNotEnabled()
        show("drive_consent")
        compose.onNodeWithText(label(R.string.drive_enable_ro)).assertIsEnabled()
        compose.onNodeWithText(label(R.string.drive_allow_write)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.drive_enable_rw)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.drive_write_ack)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.drive_enable_rw)).assertIsEnabled()
        screenshot("$prefix-drive-write-consent")
        assertEquals("No mount request before the explicit confirm action", 0, driveApprovals)
        mapOf("off" to R.string.drive_status_off, "requested" to R.string.drive_status_requested, "capable" to R.string.drive_status_capable,
            "accepted" to R.string.drive_status_accepted, "used" to R.string.drive_status_used, "unsupported" to R.string.drive_status_unsupported,
            "rejected" to R.string.drive_status_rejected, "revoked" to R.string.drive_status_revoked, "transport_unavailable" to R.string.drive_status_transport,
            "protocol_failed" to R.string.drive_status_failed, "unknown" to R.string.drive_status_unknown).forEach { (name, resource) ->
            show("drive_$name")
            compose.onNodeWithText(label(resource)).assertExists()
            if (name in setOf("requested", "capable")) compose.onNodeWithText(label(R.string.drive_status_accepted)).assertDoesNotExist()
        }
        show("drive_accepted_rw")
        compose.onNodeWithText(label(R.string.drive_mode_rw)).assertExists()
        compose.runOnIdle { screen.value = "drive_accepted" }; compose.waitForIdle()
        compose.onNodeWithText(label(R.string.stop_sharing)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.drive_stop_title)).assertIsDisplayed()
        screenshot("$prefix-drive-stop-confirmation")
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertEquals("Canceling revoke confirmation must keep the grant unchanged", 0, driveStops)
        show("webdav")
        compose.onNodeWithContentDescription(label(R.string.show_password)).assertHasClickAction().performClick()
        compose.onNodeWithContentDescription(label(R.string.hide_password)).assertExists()
        // Invoke the same rendered callbacks before another composition: stale
        // enabled semantics must not submit twice or save during the new test.
        val testAction = requireNotNull(compose.onNodeWithText(label(R.string.test_connection)).performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val saveAction = requireNotNull(compose.onNodeWithText(label(R.string.save)).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(testAction()); assertTrue(testAction()); assertTrue(saveAction()) }
        assertEquals("Same-frame repeated callback starts only one test", 1, webDavTests)
        assertEquals("Stale Save callback cannot save during connection test", 0, webDavSaves)
        // Neither completion exposes an intermediate busy frame. The second has
        // exactly the same result message, so message equality cannot unlock it.
        compose.runOnIdle { webDavCompletion.longValue = 1 }; compose.waitForIdle()
        compose.onNodeWithText(label(R.string.test_connection)).performScrollTo().assertIsEnabled().performClick()
        assertEquals(2, webDavTests)
        compose.runOnIdle { webDavCompletion.longValue = 2 }; compose.waitForIdle()
        compose.onNodeWithText(label(R.string.test_connection)).performScrollTo().assertIsEnabled()
        val completedSave = requireNotNull(compose.onNodeWithText(label(R.string.save)).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(completedSave()); assertTrue(completedSave()) }
        assertEquals("Same-frame Save callback submits only once", 1, webDavSaves)
        show("webdav_busy")
        compose.onNodeWithText(label(R.string.testing_connection)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.save)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.close)).assertIsEnabled()
        show("sync")
        compose.onNodeWithText(label(R.string.sync_legacy)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.sync_plaintext_confirm)).assertIsDisplayed()
        screenshot("$prefix-plaintext-warning")
        show("trust")
        compose.onNodeWithText(label(R.string.fingerprint_save)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.fingerprint_verified)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.fingerprint_save)).assertIsEnabled()
        assertEquals("Showing and checking the certificate must not itself approve it", 0, approved)
        show("changed")
        compose.onNodeWithText(label(R.string.fingerprint_replace)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.fingerprint_previous)).performScrollTo().assertIsDisplayed()
        screenshot("$prefix-previous-fingerprint")
        show("staged")
        compose.onNodeWithText(label(R.string.offer_clipboard)).performScrollTo().assertIsEnabled()
        show("receive")
        compose.onNodeWithText(label(R.string.confirm_receive)).performScrollTo().assertIsEnabled()
        show("progress")
        compose.onNodeWithText(label(R.string.confirm_receive)).performScrollTo().assertIsNotEnabled()
        show("unsupported")
        compose.onNodeWithText(label(R.string.choose_files)).performScrollTo().assertIsNotEnabled()
        assertEquals("Every planned image must be captured exactly once", capturePlan.map { "$prefix-$it.png" }.toSet(), capturedFiles.map { it.getString("filename") }.toSet())
        assertEquals("No duplicate evidence files", capturePlan.size, capturedFiles.size)
        report.put("success", true)
        } catch (failure: Throwable) {
            primaryFailure = failure
            report.put("success", false).put("failure", failure.stackTraceToString())
            throw failure
        } finally {
            syntheticInput.shutdown()
            if (primaryFailure != null) runCatching { screenshot("$prefix-failure", force = true) }
            report.put("captured_files", JSONArray(capturedFiles))
            File(evidence, "$prefix.json").writeText(report.toString(2))
            // UTP may uninstall the app and erase externalFiles. Export only synthetic
            // fixture evidence using the shell identity; no production storage permission.
            require(evidence.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
            try {
                // executeShellCommand is argv-based, not a shell parser: no &&/pipes.
                shell("mkdir -p /sdcard/Download/rdpsync-product-ui")
                shell("cp -R ${evidence.absolutePath}/. /sdcard/Download/rdpsync-product-ui/")
                val exported = shell("cat /sdcard/Download/rdpsync-product-ui/$prefix.json")
                assertEquals("UI evidence must survive test APK uninstall", report.toString(2), exported)
            } catch (exportFailure: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(exportFailure) else throw exportFailure
            } finally {
                runCatching { compose.runOnUiThread {
                    originalBarAppearance?.let { (status, navigation) ->
                        WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).apply {
                            isAppearanceLightStatusBars = status
                            isAppearanceLightNavigationBars = navigation
                        }
                    }
                } }
            }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun screenshot(name: String, force: Boolean = false) {
        if (!force && name.removePrefix("$activePrefix-") !in capturePlan) return
        compose.waitForIdle()
        require(name.startsWith("$activePrefix-") && name.matches(Regex("[A-Za-z0-9_.-]+")))
        check(capturedFiles.size < 64) { "Finite UI evidence frame budget exceeded" }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(evidence, "$name.png")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(file.length() > 0)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            capturedFiles += JSONObject().put("filename", file.name).put("captured_at_epoch_ms", System.currentTimeMillis()).put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
        } finally { bitmap.recycle() }
    }
}
