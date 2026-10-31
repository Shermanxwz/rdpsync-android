package com.rdp.sync

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sign

/**
 * Pinned Compose1.7.2 performScrollTo loops without a bound while ScrollBy queues
 * a coroutine. Under StandardTestDispatcher that loop does not advance its clock.
 * Use the public suspend ScrollByOffset semantic instead: one request, await its
 * actual completion while pumping the same test clock, then remeasure. No touch,
 * app state mutation, dispatcher replacement, or retry of an activation action.
 */
internal class BoundedFixtureScroll(private val compose: ComposeTestRule, private val ledger: JSONObject) {
    private var scope: CoroutineScope? = null

    @Composable fun Bind() {
        val owned = rememberCoroutineScope()
        SideEffect { scope = owned }
    }

    fun to(target: SemanticsNodeInteraction, deadlineElapsedMs: Long = Long.MAX_VALUE): SemanticsNodeInteraction =
        observe("node", deadlineElapsedMs) { deadline, trace -> to(target, deadline, trace) }

    fun toNode(container: SemanticsMatcher, matcher: SemanticsMatcher) = observe("lazy-node", Long.MAX_VALUE) { deadline, trace ->
        val selected = matcher and hasAnyAncestor(container)
        var searchingForward = false
        while (true) {
            checkBudget(deadline, trace)
            val matches = compose.onAllNodes(selected).fetchSemanticsNodes()
            check(matches.size <= 1) { "Ambiguous lazy scroll target" }
            if (matches.size == 1) return@observe to(compose.onNode(selected), deadline, trace)
            val list = compose.onNode(container).fetchSemanticsNode()
            val axis = list.config[SemanticsProperties.VerticalScrollAxisRange]
            // Search from the observed beginning, not an assumed item index. All
            // fixture lists are finite; no unbounded performScrollToNode loop.
            if (axis.value() == 0f) searchingForward = true
            val viewport = list.layoutInfo.coordinates.boundsInRoot()
            check(viewport.height > 0f) { "Empty lazy viewport" }
            val direction = if (searchingForward) 1f else -1f
            val logical = direction * viewport.height
            scroll(list, if (axis.reverseScrolling) -logical else logical, deadline, trace)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun to(target: SemanticsNodeInteraction, deadline: Long, trace: JSONArray): SemanticsNodeInteraction {
        while (true) {
            checkBudget(deadline, trace)
            val node = target.fetchSemanticsNode()
            val parent = generateSequence(node.parent) { it.parent }.firstOrNull {
                it.config.contains(SemanticsActions.ScrollByOffset) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            }
            if (parent == null) return target.assertIsDisplayed()
            val viewport = parent.layoutInfo.coordinates.boundsInRoot()
            val bounds = Rect(node.positionInRoot, node.size.toSize())
            check(viewport.height > 0f) { "Empty scroll viewport" }
            val top = bounds.top - viewport.top
            val bottom = bounds.bottom - viewport.bottom
            // Same minimal-edge geometry as pinned Actions.scrollDelta; a target
            // larger than the viewport cannot fit wholly and must intersect it.
            val delta = if (sign(top) == sign(bottom)) if (abs(top) < abs(bottom)) top else bottom else 0f
            if (delta == 0f) return target.assertIsDisplayed()
            val axis = parent.config[SemanticsProperties.VerticalScrollAxisRange]
            scroll(parent, if (axis.reverseScrolling) -delta else delta, deadline, trace)
        }
    }

    private fun scroll(node: SemanticsNode, dy: Float, deadline: Long, trace: JSONArray) {
        checkBudget(deadline, trace)
        check(dy.isFinite() && dy != 0f)
        val action = node.config[SemanticsActions.ScrollByOffset]
        val result = AtomicReference<Result<Offset>?>(null)
        val row = JSONObject().put("node_id", node.id).put("requested_y", dy)
            .put("started_elapsed_ms", SystemClock.elapsedRealtime()).put("clock_start_ms", compose.mainClock.currentTime)
            .put("viewport_before", node.layoutInfo.coordinates.boundsInRoot().toString())
            .put("axis_before", node.config[SemanticsProperties.VerticalScrollAxisRange].value())
        trace.put(row)
        var job: Job? = null
        var primary: Throwable? = null
        try {
            compose.runOnUiThread {
                job = requireNotNull(scope) { "Test scroll scope is not mounted" }.launch {
                    try { result.set(Result.success(action(Offset(0f, dy)))) }
                    catch (failure: Throwable) { result.set(Result.failure(failure)) }
                }
            }
            while (job?.isCompleted != true && SystemClock.elapsedRealtime() < deadline) pump()
            check(job?.isCompleted == true && SystemClock.elapsedRealtime() < deadline) { "Scroll did not complete inside original UI budget" }
            val consumed = requireNotNull(result.get()).getOrThrow()
            row.put("consumed_y", consumed.y).put("completed_elapsed_ms", SystemClock.elapsedRealtime())
                .put("clock_end_ms", compose.mainClock.currentTime)
                .put("axis_after", node.config[SemanticsProperties.VerticalScrollAxisRange].value())
            check(consumed.y.isFinite() && consumed.y != 0f) { "Scroll completed without progress; no repeated queued request" }
            check(SystemClock.elapsedRealtime() < deadline)
        } catch (failure: Throwable) {
            primary = failure; row.put("failure", failure.toString().take(512)); throw failure
        } finally {
            // At most one owned coroutine exists. Cancel it on failure before any
            // next operation; disposal of the test composition is the final guard.
            if (job?.isCompleted == false) {
                try {
                    row.put("cancel_requested", true)
                    job?.cancel()
                    pump()
                    check(job?.isCompleted == true) { "Owned scroll coroutine did not stop" }
                    row.put("cancel_completed", true)
                } catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
            }
            row.put("job_completed_after_cleanup", job?.isCompleted == true)
        }
    }

    private fun pump() { compose.mainClock.advanceTimeBy(32); compose.waitForIdle(); SystemClock.sleep(10) }
    private fun checkBudget(deadline: Long, trace: JSONArray) {
        check(SystemClock.elapsedRealtime() < deadline) { "Bounded scroll exceeded 15-second UI budget" }
        check(trace.length() < 32) { "Bounded scroll exceeded 32 completed actions" }
    }
    private fun observe(kind: String, callerDeadline: Long, block: (Long, JSONArray) -> SemanticsNodeInteraction): SemanticsNodeInteraction {
        val started = SystemClock.elapsedRealtime()
        val deadline = minOf(started + 15_000, callerDeadline)
        val row = JSONObject().put("kind", kind).put("deadline_elapsed_ms", deadline).put("started_elapsed_ms", started).put("clock_start_ms", compose.mainClock.currentTime)
        val actions = JSONArray(); row.put("actions", actions)
        val rows = ledger.optJSONArray("bounded_scrolls") ?: JSONArray().also { ledger.put("bounded_scrolls", it) }
        check(rows.length() < 128) { "Finite scroll ledger exhausted" }; rows.put(row)
        try { return block(deadline, actions).also {
            it.assertIsDisplayed()
            row.put("target_visible", true).put("target_bounds", it.fetchSemanticsNode().boundsInRoot.toString())
            check(SystemClock.elapsedRealtime() < deadline) { "Scroll completed after UI deadline" }
            row.put("success", true)
        } }
        catch (failure: Throwable) { row.put("failure", failure.toString().take(512)); throw failure }
        finally { row.put("completed_elapsed_ms", SystemClock.elapsedRealtime()).put("clock_end_ms", compose.mainClock.currentTime) }
    }
}
