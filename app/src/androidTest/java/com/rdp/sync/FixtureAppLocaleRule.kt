package com.rdp.sync

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Legacy network fixtures assert exact Chinese app labels. Set only the target app's
 * locale before ActivityScenario starts; the system picker and keyboard stay unchanged.
 * Public AOSP15 LocaleManagerShellCommand defines set/get-app-locales and an omitted
 * --locales restores the empty list. No localeconfig override or permission grant.
 */
class FixtureAppLocaleRule(private vararg val enabledArguments: String) : TestRule {
    val receipt = JSONObject()

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            check(Build.VERSION.SDK_INT >= 33) { "App-locale fixture requires API33+" }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(enabledArguments.any { InstrumentationRegistry.getArguments().getString(it) == "true" }) {
                "App-locale setup requires an explicit isolated fixture argument"
            }
            val packageName = instrumentation.targetContext.packageName
            check(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_.]+")))
            check(shell("am get-current-user").trim() == "0") { "Only owned CI emulator user0 is supported" }
            val original = readLocales(packageName)
            receipt.put("package", packageName).put("original", original).put("requested", "zh-CN")
                .put("setup_elapsed_ms", SystemClock.elapsedRealtime()).put("system_locale_changed", false)
            var failure: Throwable? = null
            try {
                setLocales(packageName, "zh-CN")
                check(readLocales(packageName) == "zh-CN") { "Target app locale readback mismatch" }
                receipt.put("verified_before_activity", true)
                Log.i("FixtureAppLocale", "setup $receipt")
                base.evaluate()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    setLocales(packageName, original)
                    check(readLocales(packageName) == original) { "Target app locale restore mismatch" }
                    receipt.put("restored", true).put("restore_elapsed_ms", SystemClock.elapsedRealtime())
                    Log.i("FixtureAppLocale", "restored $receipt")
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private fun readLocales(packageName: String): String {
        val output = shell("cmd locale get-app-locales $packageName --user 0").trim()
        val prefix = "Locales for $packageName for user 0 are ["
        check(output.startsWith(prefix) && output.endsWith("]") && !output.contains('\n')) {
            "Unexpected target app locale response: $output"
        }
        return output.removePrefix(prefix).removeSuffix("]").also {
            check(it.matches(Regex("[A-Za-z0-9,-]*"))) { "Unexpected locale tags" }
        }
    }

    private fun setLocales(packageName: String, locales: String) {
        val argument = if (locales.isEmpty()) "" else " --locales $locales"
        val output = shell("cmd locale set-app-locales $packageName --user 0$argument")
        check(output.isBlank()) { "Target app locale setter failed: $output" }
    }

    private fun shell(command: String): String {
        val closing = AtomicBoolean(false)
        val owned = AtomicReference<ParcelFileDescriptor?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<String> {
            var descriptor: ParcelFileDescriptor? = null
            try {
                check(!closing.get()) { "Locale command canceled before launch" }
                descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                owned.set(descriptor)
                check(!closing.get()) { "Locale command returned after deadline" }
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(512)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 4097 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        check(output.size() <= 4096) { "Unexpectedly large locale command response" }
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
            return future.get(5, TimeUnit.SECONDS)
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
                check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "Locale command worker did not terminate" }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            cleanupFailure?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }
}
