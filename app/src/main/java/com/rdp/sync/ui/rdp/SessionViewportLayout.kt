package com.rdp.sync.ui.rdp

import android.view.View

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Full-window session layout. A keyboard changes only the locally visible desktop,
 * never the logical viewport sent to DISP. Read insets in measurement, not composition:
 * Compose's composition-phase inset values can be one frame behind the layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionViewportLayout(
    onLogicalViewport: (IntSize) -> Unit,
    header: @Composable () -> Unit,
    desktop: @Composable () -> Unit,
    toolbar: @Composable () -> Unit,
    imeControls: @Composable () -> Unit
) {
    val host = LocalView.current
    val composeSafe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val composeIme = WindowInsets.ime
    val root = host.rootView
    var rootSize by remember(root) { mutableStateOf(IntSize(root.width, root.height)) }
    var layoutOrigin by remember(root) { mutableStateOf(IntOffset.Zero) }
    // Presentation only: unplaced children still publish Compose semantics. This
    // state follows measurement, but must never feed back into viewport geometry.
    var imeChromeActive by remember { mutableStateOf(false) }
    val rootLocation = remember { IntArray(2) }
    DisposableEffect(root) {
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            rootSize = IntSize(right - left, bottom - top)
        }
        root.addOnLayoutChangeListener(listener)
        onDispose { root.removeOnLayoutChangeListener(listener) }
    }
    Layout(modifier = Modifier.fillMaxSize().onGloballyPositioned {
        val position = it.positionInWindow()
        root.getLocationInWindow(rootLocation)
        layoutOrigin = IntOffset(position.x.roundToInt() - rootLocation[0], position.y.roundToInt() - rootLocation[1])
    }, content = {
        Box(Modifier.inactiveChrome(imeChromeActive)) { header() }
        Box { desktop() }
        Box(Modifier.inactiveChrome(imeChromeActive)) { toolbar() }
        Box(Modifier.inactiveChrome(!imeChromeActive)) { imeControls() }
    }) { children, constraints ->
        val originX = layoutOrigin.x
        val originY = layoutOrigin.y
        // Read Compose state as well so inset animation invalidates measurement. The
        // root's unconsumed insets are authoritative for bars/cutouts; child consumption
        // must not put remote content beneath the Android status/navigation controls.
        val fallbackSafe = SessionSafeInsets(composeSafe.getLeft(this, layoutDirection), composeSafe.getTop(this),
            composeSafe.getRight(this, layoutDirection), composeSafe.getBottom(this))
        val insets = ViewCompat.getRootWindowInsets(host)
        val platformSafe = insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val safe = platformSafe?.let { SessionSafeInsets(it.left, it.top, it.right, it.bottom) } ?: fallbackSafe
        val animatedIme = composeIme.getBottom(this)
        val ime = if (animatedIme > 0) animatedIme else insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        // The full decor extent is independent of IME visibility/order. Older
        // adjustResize implementations can shrink content before reporting IME insets;
        // neither that smaller constraint nor a stale visibility flag may resize DISP.
        // The root layout listener invalidates measurement after rotation/window resize.
        val windowWidth = rootSize.width.takeIf { it > 0 } ?: (constraints.maxWidth + originX)
        val windowHeight = rootSize.height.takeIf { it > 0 } ?: (constraints.maxHeight + originY)
        val base = SessionViewportGeometry.calculate(windowWidth, windowHeight, constraints.maxWidth, constraints.maxHeight,
            originX, originY, safe, ime, 0, 0)
        val chromeConstraints = Constraints(minWidth = base.width, maxWidth = base.width, maxHeight = base.logicalDesktopHeight)
        val headerPlaceable = children[0].measure(chromeConstraints)
        val toolbarPlaceable = children[2].measure(chromeConstraints)
        val controlsPlaceable = children[3].measure(chromeConstraints.copy(minWidth = 0))
        val geometry = SessionViewportGeometry.calculate(windowWidth, windowHeight, constraints.maxWidth, constraints.maxHeight,
            originX, originY, safe, ime, headerPlaceable.height, toolbarPlaceable.height)
        imeChromeActive = geometry.imeOccluded
        val desktopPlaceable = children[1].measure(Constraints.fixed(geometry.width, geometry.localDesktopHeight))
        onLogicalViewport(IntSize(geometry.width, geometry.logicalDesktopHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            desktopPlaceable.place(geometry.left, geometry.desktopTop)
            if (geometry.imeOccluded) {
                // Only this small overlay owns hit targets; no full-screen gesture layer.
                controlsPlaceable.place(geometry.left + (geometry.width - controlsPlaceable.width).coerceAtLeast(0),
                    geometry.desktopTop + (geometry.localDesktopHeight - controlsPlaceable.height).coerceAtLeast(0))
            } else {
                headerPlaceable.place(geometry.left, geometry.top)
                toolbarPlaceable.place(geometry.left, geometry.desktopTop + geometry.localDesktopHeight)
            }
        }
    }
}

/** Keep inactive chrome measured without offering phantom actions or focus targets. */
private fun Modifier.inactiveChrome(inactive: Boolean): Modifier =
    if (inactive) clearAndSetSemantics { }.focusProperties { canFocus = false } else this
