package com.rdp.sync.ui.rdp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Own only this route's manual orientation override, never the user's system setting. */
@Composable
internal fun rememberSessionOrientation(sessionKey: Any): () -> Unit {
    val activity = LocalContext.current.orientationActivity()
    val activeOwner = remember(activity, sessionKey) { arrayOfNulls<SessionOrientationOwner>(1) }
    DisposableEffect(activity, activeOwner) {
        // Capture during effect setup, after the previous route owner's disposal.
        // Capturing during composition would inherit its still-active override
        // if one device replaces another in this same composition slot.
        val owner = activity?.let { SessionOrientationOwner(it.requestedOrientation) }
        activeOwner[0] = owner
        onDispose {
            activeOwner[0] = null
            if (activity != null && owner != null && owner.hasOverride) {
                activity.requestedOrientation = owner.originalOrientation
            }
        }
    }
    return remember(activity, activeOwner) {
        {
            val owner = activeOwner[0]
            if (activity != null && owner != null) {
                activity.requestedOrientation = owner.rotate(activity.resources.configuration.orientation)
            }
        }
    }
}

/** Last requested target makes rapid taps deterministic before configuration delivery. */
internal class SessionOrientationOwner(val originalOrientation: Int) {
    private var target: Int? = null
    val hasOverride: Boolean get() = target != null

    fun rotate(configurationOrientation: Int): Int {
        val landscape = target?.let { it == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ?: (configurationOrientation == Configuration.ORIENTATION_LANDSCAPE)
        return (if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE).also { target = it }
    }
}

private tailrec fun Context.orientationActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.orientationActivity() else null
    else -> null
}
