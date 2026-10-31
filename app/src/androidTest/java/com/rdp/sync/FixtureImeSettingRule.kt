package com.rdp.sync

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Disposable API35 emulator prerequisite, completed before the Activity or any real input.
 * AOSP15 ContentService delays background observers by 10s. InputMethodService's observer
 * resets its input view on SHOW_IME_WITH_HARD_KEYBOARD changes, even without a config change.
 * Provider readback alone is not readiness: require the actual selected IME's consumed cache.
 * No IME/subtype selection, key injection, app retry or post-input recovery is performed.
 */
class FixtureImeSettingRule : TestRule {
    val receipt = JSONObject()
    private var initialDump = ""
    private var latestDump = ""
    private val setting = "show_ime_with_hard_keyboard"
    private val gboard = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            check(Build.VERSION.SDK_INT == 35) { "IME prerequisite is pinned to API35" }
            check(InstrumentationRegistry.getArguments().getString("rdpFixture") == "true") {
                "IME prerequisite requires the isolated fixture"
            }
            check(shell("am get-current-user").trim() == "0") { "Only disposable emulator user0 is supported" }
            val original = readSetting()
            check(original in setOf("null", "0", "1")) { "Unexpected original IME setting" }
            check(shell("settings get secure default_input_method").trim() == gboard) { "Pinned actual Gboard required" }
            receipt.put("original", original).put("requested", "1").put("ime", gboard)
                .put("setup_elapsed_ms", SystemClock.elapsedRealtime()).put("wrote_setting", false)
            var failure: Throwable? = null
            var writeAttempted = false
            try {
                check(original != "1") {
                    "Already-enabled IME setting has no consumed-notification provenance; unsupported initial state"
                }
                val initial = observeIme(3_000)
                initialDump = latestDump
                receipt.put("initial_service", initial.json())
                check(initial.readyBinding) { "No unique live Gboard service before prerequisite" }
                check(initial.cache == "1") {
                    "Initial Gboard cache must match original provider value, not UNKNOWN or a pending transition"
                }
                if (original != "1") {
                    writeAttempted = true
                    receipt.put("write_started_elapsed_ms", SystemClock.elapsedRealtime())
                    check(shell("settings put secure $setting 1").isBlank()) { "IME setting write failed" }
                    receipt.put("wrote_setting", true)
                }
                check(readSetting() == "1") { "IME provider readback mismatch" }
                val deadline = SystemClock.elapsedRealtime() + 15_000
                val samples = JSONArray()
                receipt.put("consumption_deadline_elapsed_ms", deadline).put("consumption_samples", samples)
                var consumed = false
                while (!consumed && SystemClock.elapsedRealtime() < deadline) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    val current = observeIme(minOf(3_000, remaining))
                    check(current.readyBinding && current.instance == initial.instance && current.method == initial.method) {
                        "Actual IME service changed or dump incomplete during prerequisite; consumption is unproven"
                    }
                    // Once FALSE is observed, shouldShowImeWithHardKeyboard cannot reload provider:
                    // AOSP15 only lazily reads while UNKNOWN. On this same live service, TRUE
                    // therefore proves its SettingsObserver callback consumed the write.
                    consumed = current.cache == "2"
                    samples.put(current.json().put("elapsed_ms", SystemClock.elapsedRealtime()).put("consumed", consumed))
                    check(samples.length() <= 80) { "IME readiness evidence overflow" }
                    if (!consumed) SystemClock.sleep(minOf(200, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)))
                }
                check(consumed && SystemClock.elapsedRealtime() <= deadline) {
                    "Actual Gboard did not consume hardware-keyboard setting within pretest 15s"
                }
                receipt.put("verified_before_activity", true).put("ready_elapsed_ms", SystemClock.elapsedRealtime())
                persist()
                base.evaluate()
            } catch (error: Throwable) {
                failure = error
                receipt.put("primary_failure_type", error.javaClass.name)
                throw error
            } finally {
                try {
                    if (writeAttempted) {
                        val command = if (original == "null") "settings delete secure $setting"
                            else "settings put secure $setting $original"
                        shell(command)
                    }
                    check(readSetting() == original) { "IME setting restore mismatch" }
                    receipt.put("restored", true).put("restore_elapsed_ms", SystemClock.elapsedRealtime())
                } catch (cleanup: Throwable) {
                    receipt.put("restore_failure_type", cleanup.javaClass.name)
                    if (failure != null) failure.addSuppressed(cleanup) else failure = cleanup
                }
                try { persist() } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else failure = cleanup
                }
                failure?.let { throw it }
            }
        }
    }

    private data class ImeState(val selected: List<String>, val bound: List<String>, val caches: List<String>,
                                val instances: List<String>, val methods: List<String>, val complete: Boolean) {
        val cache get() = caches.singleOrNull()
        val instance get() = instances.singleOrNull()
        val method get() = methods.singleOrNull()
        val readyBinding get() = complete && selected.size == 1 && bound == selected &&
            selected.single() == "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME" &&
            caches.size == 1 && instances.size == 1 && methods.size == 1 &&
            instances.single().matches(Regex("[A-Za-z0-9_.$]+@[0-9a-fA-F]+")) &&
            methods.single().matches(Regex("[A-Za-z0-9_.$]+@[0-9a-fA-F]+"))
        fun json() = JSONObject().put("selected_ime", JSONArray(selected)).put("bound_ime", JSONArray(bound))
            .put("observer_cache", JSONArray(caches)).put("service_instance", JSONArray(instances))
            .put("method_identity", JSONArray(methods)).put("dump_complete", complete)
    }

    private fun observeIme(timeoutMs: Long): ImeState {
        val dump = shell("dumpsys -t 2 input_method", timeoutMs)
        latestDump = dump
        fun matches(pattern: String) = Regex(pattern).findAll(dump).map { it.groupValues[1] }.toList()
        return ImeState(
            matches("(?m)^\\s*mCurMethodId=(\\S+)\\s*$"),
            matches("(?m)^\\s*mCurId=(\\S+) mHaveConnection=true\\b"),
            matches("mSettingsObserver=SettingsObserver\\{mShowImeWithHardKeyboard=([012])\\}"),
            matches("(?m)^\\s*Input method service state for ([^\\r\\n]+):\\s*$"),
            matches("(?m)^\\s*mCurMethod=(\\S+)\\s*$"),
            dump.contains("mNavigationBarController=") && !dump.contains("DUMP TIMEOUT") &&
                !dump.contains("Failed to dump") && !dump.contains("Permission Denial"))
    }

    private fun readSetting() = shell("settings get secure $setting").trim()

    private fun persist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "rdp-evidence").apply { mkdirs() }
        // Disposable synthetic fixture only. Bounded full dumps retain parser failures;
        // ordinary production sessions never enable this rule or produce these records.
        val dumps = listOf("ime-setting-initial-dump.txt" to initialDump, "ime-setting-latest-dump.txt" to latestDump)
        val hashes = JSONObject()
        for ((name, content) in dumps) {
            File(directory, name).writeText(content)
            val bytes = content.toByteArray(Charsets.UTF_8)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            hashes.put(name, JSONObject().put("bytes", bytes.size).put("sha256", hash))
        }
        receipt.put("dump_files", hashes)
        Log.i("FixtureImeSetting", receipt.toString())
        val file = File(directory, "ime-setting-receipt.json")
        file.writeText(receipt.toString(2))
        shell("mkdir -p /sdcard/Download/rdpsync-evidence")
        for (name in dumps.map { it.first } + file.name) {
            shell("cp ${File(directory, name).absolutePath} /sdcard/Download/rdpsync-evidence/$name")
        }
    }
    private fun shell(command: String, timeoutMs: Long = 5_000): String {
        check(timeoutMs > 0) { "IME prerequisite command deadline expired" }
        val closing = AtomicBoolean(false)
        val owned = AtomicReference<ParcelFileDescriptor?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<String> {
            var descriptor: ParcelFileDescriptor? = null
            try {
                check(!closing.get()) { "IME prerequisite command canceled before launch" }
                descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                owned.set(descriptor)
                check(!closing.get()) { "IME prerequisite command returned after deadline" }
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(512)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 262145 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        check(output.size() <= 262144) { "Unexpectedly large IME prerequisite command response" }
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            } finally {
                // Worker also owns closure if Binder returns after the caller's deadline.
                descriptor?.close()
                owned.compareAndSet(descriptor, null)
            }
        }
        var failure: Throwable? = null
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            closing.set(true)
            var cleanupFailure: Throwable? = null
            try { owned.getAndSet(null)?.close() } catch (error: Throwable) { cleanupFailure = error }
            future.cancel(true)
            executor.shutdownNow()
            try {
                check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "IME prerequisite command worker did not terminate" }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            cleanupFailure?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }
}
