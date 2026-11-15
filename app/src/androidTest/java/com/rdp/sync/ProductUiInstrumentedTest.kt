package com.rdp.sync

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import com.rdp.sync.data.Device
import com.rdp.sync.display.SessionDisplaySettings
import com.rdp.sync.manager.SyncDirection
import com.rdp.sync.network.RdpInputDispatcher
import com.rdp.sync.network.SharedDriveMode
import com.rdp.sync.network.SharedDriveState
import com.rdp.sync.network.SharedDriveStatus
import com.rdp.sync.security.*
import com.rdp.sync.ui.MainActivity
import com.rdp.sync.ui.screens.*
import com.rdp.sync.ui.sharedspace.SharedDriveStatusDialog
import com.rdp.sync.ui.theme.RdpSyncTheme
import com.rdp.sync.viewmodel.UiState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** UI-only evidence. Synthetic profiles, no network, real credentials, pins, or filesystem sharing. */
@RunWith(AndroidJUnit4::class)
class ProductUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence by lazy { File(instrumentation.targetContext.getExternalFilesDir(null), "product-ui-evidence").apply { mkdirs() } }
    private val capturedFiles = mutableListOf<JSONObject>()
    private var activePrefix = ""
    private var activeStateTag = ""
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
        val originalResourcesConfiguration = Configuration(base.resources.configuration)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language)); this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            constrainedWidth?.let { screenWidthDp = it }
        }
        val localized = base.createConfigurationContext(config)
        val label: (Int) -> String = { localized.getString(it) }
        val screen = mutableStateOf("profiles")
        val detailProfile = mutableStateOf(profile)
        val webDavCompletion = mutableLongStateOf(0)
        val syntheticInput = RdpInputDispatcher({ _, _, _, _ -> true }, { _, _, _ -> 1 }, { _, _, _ -> 1 }, { _, _, _ -> 1 })
        var driveStops = 0
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
            constrainedWidth == 320 -> setOf("profiles", "editor-validation", "error", "save-busy-action", "settings-options", "session-menu-open", "session-menu-shortcuts", "locked", "drive-stop-confirmation", "plaintext-warning", "previous-fingerprint")
            constrainedWidth == 600 -> setOf("profiles", "editor", "detail", "settings-options", "changed", "session-menu-open")
            language == "en" && !dark -> setOf("profiles", "empty", "editor", "detail", "webdav", "plaintext-warning", "trust", "changed", "drive_off", "drive_requested", "drive_accepted", "drive_accepted_rw", "drive_unsupported", "drive_revoked", "delete-confirmation")
            language == "en" -> setOf("profiles", "editor", "detail", "webdav", "changed", "settings", "session-menu-open")
            !dark -> setOf("profiles", "empty", "editor", "detail", "webdav", "trust", "drive_accepted")
            else -> setOf("profiles", "editor", "plaintext-warning", "webdav", "changed", "settings", "session-menu-open")
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
                val activityConfiguration = Configuration(originalResourcesConfiguration).apply {
                    setLocale(Locale.forLanguageTag(language))
                    this.fontScale = fontScale
                    uiMode = config.uiMode
                }
                @Suppress("DEPRECATION")
                base.resources.updateConfiguration(activityConfiguration, base.resources.displayMetrics)
                assertEquals(language, base.resources.configuration.locales[0].language)
                report.put("activity_locale", base.resources.configuration.locales[0].toLanguageTag())
                    .put("activity_font_scale", base.resources.configuration.fontScale)
                val bars = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                originalBarAppearance = bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
                bars.isAppearanceLightStatusBars = !dark
                bars.isAppearanceLightNavigationBars = !dark
                report.put("appearance_light_status_bars", bars.isAppearanceLightStatusBars)
                    .put("appearance_light_navigation_bars", bars.isAppearanceLightNavigationBars)
                compose.activity.setContent {
                    val actualDensity = LocalDensity.current.density
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                        LocalDensity provides Density(actualDensity, fontScale),
                        LocalActivityResultRegistryOwner provides base) {
                        RdpSyncTheme(darkTheme = dark) {
                            Surface(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().testTag("product-ui-${screen.value}"), contentAlignment = Alignment.TopCenter) {
                                    Box(if (constrainedWidth == null) Modifier.fillMaxSize() else Modifier.width(constrainedWidth.dp).fillMaxHeight()) {
                                        when (screen.value) {
                                            "profiles", "empty", "loading" -> DeviceListScreen(
                                                UiState(devices = if (screen.value == "profiles") listOf(profile, Device(id = 900082, name = "Lab / 实验室", host = "lab.example.test", username = "example", password = "").also { it.credentialUnavailable = true }) else emptyList(),
                                                    isLoading = screen.value == "loading", webDavSettingsLoaded = true), {}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {})
                                            "detail" -> DeviceDetailScreen(detailProfile.value, {}, {}, {}, { deleted++ })
                                            "editor" -> DeviceEditScreen(null, { saved++ }, {})
                                            "saving" -> DeviceEditScreen(profile, { saved++ }, {}, isSaving = true)
                                            "error" -> DeviceEditScreen(profile, { saved++ }, {}, errorMessage = label(R.string.credential_locked_help))
                                            "settings" -> DisplaySettingsDialog(SessionDisplaySettings(), profile, "1280 × 720 · 60 fps", label(R.string.waiting_measurement), "", false, false,
                                                {}, {}, {}, {}, {}, {})
                                            "menu" -> Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomEnd) {
                                                SessionFloatingControls(syntheticInput, true, false, false, true, {}, {}, {}, {}, {}, {}, {}, {}, {})
                                            }
                                            "locked" -> DeviceEditScreen(profile.copy(credentialUnavailable = true), { saved++ }, {})
                                            "drive_off", "drive_requested", "drive_capable", "drive_accepted", "drive_accepted_rw", "drive_used", "drive_unsupported", "drive_rejected", "drive_revoked", "drive_transport_unavailable", "drive_protocol_failed", "drive_unknown" -> {
                                                val status = SharedDriveStatus.valueOf(screen.value.removePrefix("drive_").removeSuffix("_rw").uppercase(Locale.ROOT))
                                                SharedDriveStatusDialog(SharedDriveState(generation = if (status == SharedDriveStatus.OFF || status == SharedDriveStatus.UNKNOWN) 0 else 5,
                                                    mode = if (status == SharedDriveStatus.OFF || status == SharedDriveStatus.UNKNOWN) SharedDriveMode.OFF else if (screen.value.endsWith("_rw")) SharedDriveMode.READ_WRITE else SharedDriveMode.READ_ONLY, status = status),
                                                    {}, { driveStops++ }, {}, {})
                                            }
                                            "webdav", "webdav_busy" -> WebDavSettingsDialog("https://example.test/dav/", "example", "", false, resultMessage = if (webDavCompletion.longValue > 0) label(R.string.status_connected) else null, onDismiss = {}, onSave = { _, _, _ -> webDavSaves++ }, onTest = { _, _, _ -> webDavTests++ }, isTesting = screen.value == "webdav_busy", completionId = webDavCompletion.longValue)
                                            "sync" -> SyncModeDialog(SyncDirection.UPLOAD, {}, {})
                                            "trust", "changed" -> CertificateTrustDialog(if (screen.value == "trust") firstTrust else changedTrust, false, "", {}, { approved++ })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            fun expectedState(name: String): SemanticsMatcher {
                val resource = when (name) {
                    "empty" -> R.string.empty_title
                    "loading" -> R.string.loading_profiles
                    "detail" -> R.string.connection_title
                    "editor" -> R.string.add_device
                    "saving" -> R.string.saving
                    "error", "locked" -> R.string.credential_locked_help
                    "settings" -> R.string.display_network
                    "drive_off" -> R.string.drive_status_off
                    "drive_requested" -> R.string.drive_status_requested
                    "drive_capable" -> R.string.drive_status_capable
                    "drive_accepted", "drive_accepted_rw" -> R.string.drive_status_accepted
                    "drive_used" -> R.string.drive_status_used
                    "drive_unsupported" -> R.string.drive_status_unsupported
                    "drive_rejected" -> R.string.drive_status_rejected
                    "drive_revoked" -> R.string.drive_status_revoked
                    "drive_transport_unavailable" -> R.string.drive_status_transport
                    "drive_protocol_failed" -> R.string.drive_status_failed
                    "drive_unknown" -> R.string.drive_status_unknown
                    "webdav" -> R.string.webdav_title
                    "webdav_busy" -> R.string.testing_connection
                    "sync" -> R.string.sync_file_title
                    "trust" -> R.string.certificate_confirm
                    "changed" -> R.string.certificate_changed
                    else -> null
                }
                return when (name) {
                    "profiles" -> hasText(profile.name)
                    "menu" -> hasContentDescription(label(R.string.session_menu))
                    else -> hasText(label(requireNotNull(resource) { name }))
                }
            }
            fun show(name: String, capture: Boolean = true) {
                compose.runOnIdle { screen.value = name }
                activeStateTag = "product-ui-$name"
                val expected = expectedState(name)
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag(activeStateTag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                        compose.onAllNodes(expected, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                if (capture) screenshot("$prefix-$name")
            }
            fun addAction(): SemanticsNodeInteraction = compose.onNode(
                hasClickAction() and hasAnyDescendant(hasText(label(R.string.add_device))), useUnmergedTree = true)
            show("profiles")
            addAction().assertIsDisplayed().assertIsEnabled().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            report.put("initial_merged_semantics", compose.onRoot().printToString().take(32768))
            show("empty")
            addAction().performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            show("loading")
            compose.onNodeWithText(label(R.string.loading_profiles)).assertExists()
            show("detail")
            compose.onNodeWithText(label(R.string.phone_storage_share_title)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(R.string.delete_confirm)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.delete_title)).assertIsDisplayed()
            screenshot("$prefix-delete-confirmation")
            compose.onNodeWithText(label(R.string.cancel)).performClick()
            assertEquals("Cancel must not delete a saved profile", 0, deleted)
            compose.runOnIdle { detailProfile.value = detailProfile.value.copy(credentialUnavailable = true) }
            compose.onNodeWithText(label(R.string.phone_storage_share_title)).assertDoesNotExist()
            compose.runOnIdle { detailProfile.value = profile }
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
            compose.onNodeWithText("Ctrl+Alt+Del").performScrollTo().assertIsDisplayed()
            screenshot("$prefix-session-menu-shortcuts")
            show("locked")
            compose.onNodeWithText(label(R.string.save_connection)).performScrollTo().assertIsNotEnabled()
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
            show("drive_accepted", capture = false)
            compose.onNodeWithText(label(R.string.stop_sharing)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.drive_stop_title)).assertIsDisplayed()
            screenshot("$prefix-drive-stop-confirmation")
            compose.onNodeWithText(label(R.string.cancel)).performClick()
            assertEquals("Canceling revoke confirmation must keep the grant unchanged", 0, driveStops)
            show("webdav")
            compose.onNodeWithContentDescription(label(R.string.show_password)).assertHasClickAction().performClick()
            compose.onNodeWithContentDescription(label(R.string.hide_password)).assertExists()
            val testAction = requireNotNull(compose.onNodeWithText(label(R.string.test_connection)).performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val saveAction = requireNotNull(compose.onNodeWithText(label(R.string.save)).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.runOnUiThread { assertTrue(testAction()); assertTrue(testAction()); assertTrue(saveAction()) }
            assertEquals("Same-frame repeated callback starts only one test", 1, webDavTests)
            assertEquals("Stale Save callback cannot save during connection test", 0, webDavSaves)
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
            assertEquals("Every planned image must be captured exactly once", capturePlan.map { "$prefix-$it.png" }.toSet(), capturedFiles.map { it.getString("filename") }.toSet())
            assertEquals("No duplicate evidence files", capturePlan.size, capturedFiles.size)
            report.put("success", true)
        } catch (failure: Throwable) {
            primaryFailure = failure
            report.put("success", false).put("failure", failure.stackTraceToString())
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                val count = roots.fetchSemanticsNodes().size
                report.put("failure_semantics", JSONArray((0 until minOf(count, 3)).map { roots[it].printToString().take(32768) }))
            }
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                syntheticInput.shutdown()
                if (primaryFailure != null) runCatching { screenshot("$prefix-failure", force = true) }
                report.put("captured_files", JSONArray(capturedFiles))
                File(evidence, "$prefix.json").writeText(report.toString(2))
                require(evidence.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
                shell("mkdir -p /sdcard/Download/rdpsync-product-ui")
                shell("cp -R ${evidence.absolutePath}/. /sdcard/Download/rdpsync-product-ui/")
                val exported = shell("cat /sdcard/Download/rdpsync-product-ui/$prefix.json")
                assertEquals("UI evidence must survive test APK uninstall", report.toString(2), exported)
            } catch (failure: Throwable) {
                cleanupFailure = failure
                if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
            } finally {
                try {
                    compose.runOnUiThread {
                        var restoreFailure: Throwable? = null
                        try {
                            @Suppress("DEPRECATION")
                            base.resources.updateConfiguration(originalResourcesConfiguration, base.resources.displayMetrics)
                            val restored = base.resources.configuration
                            assertEquals("Restore Activity locale", originalResourcesConfiguration.locales, restored.locales)
                            assertEquals("Restore Activity font scale", originalResourcesConfiguration.fontScale, restored.fontScale, 0f)
                            assertEquals("Restore Activity UI mode", originalResourcesConfiguration.uiMode, restored.uiMode)
                        } catch (failure: Throwable) {
                            restoreFailure = failure
                        }
                        try {
                            originalBarAppearance?.let { (status, navigation) ->
                                WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).apply {
                                    isAppearanceLightStatusBars = status
                                    isAppearanceLightNavigationBars = navigation
                                    assertEquals("Restore status bar appearance", status, isAppearanceLightStatusBars)
                                    assertEquals("Restore navigation bar appearance", navigation, isAppearanceLightNavigationBars)
                                }
                            }
                        } catch (failure: Throwable) {
                            val previous = restoreFailure
                            if (previous != null) previous.addSuppressed(failure) else restoreFailure = failure
                        }
                        restoreFailure?.let { throw it }
                    }
                } catch (failure: Throwable) {
                    val previous = primaryFailure ?: cleanupFailure
                    if (previous != null) previous.addSuppressed(failure) else throw failure
                }
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
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        var barriers = 0
        val bitmap = try {
            HardwareRendererCompat.setDrawingEnabled(true)
            if (!force) {
                compose.onNodeWithTag(activeStateTag, useUnmergedTree = true).assertIsDisplayed()
                    .captureToImage().asAndroidBitmap().recycle()
                barriers++
                for (kind in listOf(isDialog(), isPopup())) {
                    val windows = compose.onAllNodes(kind, useUnmergedTree = true)
                    val count = windows.fetchSemanticsNodes().size
                    check(count <= 2) { "Unexpected number of fixture windows" }
                    for (index in 0 until count) { windows[index].captureToImage().asAndroidBitmap().recycle(); barriers++ }
                }
            }
            requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        } finally { HardwareRendererCompat.setDrawingEnabled(drawingWasEnabled) }
        val file = File(evidence, "$name.png")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(file.length() > 0)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            capturedFiles += JSONObject().put("filename", file.name).put("draw_barriers", barriers).put("active_state_tag", activeStateTag).put("captured_at_epoch_ms", System.currentTimeMillis()).put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
        } finally { bitmap.recycle() }
    }
}
