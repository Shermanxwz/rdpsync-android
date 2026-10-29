package com.rdp.sync.ui.rdp

import com.rdp.sync.display.DisplayQuality
import com.rdp.sync.display.ResolutionMode
import com.rdp.sync.display.SessionDisplaySettings

object SessionUiDefaults {
    const val POINTER_MODE = false // Direct touch on every new session, not a sticky temporary toggle.
    fun displaySettings(quality: String?, resolution: String?, adaptive: Boolean) = SessionDisplaySettings(
        DisplayQuality.entries.firstOrNull { it.name == quality } ?: DisplayQuality.BALANCED,
        ResolutionMode.entries.firstOrNull { it.name == resolution } ?: ResolutionMode.FIT_WINDOW,
        adaptive
    )
}
