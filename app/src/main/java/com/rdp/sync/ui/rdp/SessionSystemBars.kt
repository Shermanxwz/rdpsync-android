package com.rdp.sync.ui.rdp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Session-only immersive ownership; leaving the screen restores its predecessor. */
@Composable
internal fun SessionSystemBars(fullscreen: Boolean) {
    val activity = LocalContext.current.activity() ?: return
    val window = activity.window
    val decor = window.decorView
    val controller = remember(window) { WindowCompat.getInsetsController(window, decor) }
    val original = remember(window) {
        val insets = ViewCompat.getRootWindowInsets(decor)
        OriginalBars(insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true,
            insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true,
            insets?.isVisible(WindowInsetsCompat.Type.captionBar()) ?: false, controller.systemBarsBehavior)
    }
    DisposableEffect(window) {
        onDispose {
            controller.systemBarsBehavior = original.behavior
            if (original.status) controller.show(WindowInsetsCompat.Type.statusBars())
            else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (original.navigation) controller.show(WindowInsetsCompat.Type.navigationBars())
            else controller.hide(WindowInsetsCompat.Type.navigationBars())
            if (original.caption) controller.show(WindowInsetsCompat.Type.captionBar())
            else controller.hide(WindowInsetsCompat.Type.captionBar())
        }
    }
    DisposableEffect(window, fullscreen) {
        fun applyMode() {
            if (fullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.systemBarsBehavior = original.behavior
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        applyMode()
        // Dialogs and app switching may restore bars. No insets listener is replaced,
        // and ordinary edge swipes still reveal Android's transient navigation.
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) applyMode() }
        decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
}

private data class OriginalBars(val status: Boolean, val navigation: Boolean, val caption: Boolean, val behavior: Int)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}
