package com.rdp.sync.ui.rdp

/** Physical window insets, before any Compose child consumes them. */
internal data class SessionSafeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class SessionViewportGeometry(
    val left: Int,
    val top: Int,
    val width: Int,
    val logicalDesktopHeight: Int,
    val localDesktopHeight: Int,
    val keyboardShortcutsHeight: Int
) {
    companion object {
        /**
         * Remote geometry uses the full window and non-IME chrome only. Local geometry
         * also obeys the available content bounds (which adjustResize can already have
         * reduced) and the IME edge. Taking the minimum avoids subtracting the IME twice.
         * Origins handle platforms that already inset the Compose host for system bars.
         */
        fun calculate(
            windowWidth: Int, windowHeight: Int, availableWidth: Int, availableHeight: Int,
            originX: Int, originY: Int, safe: SessionSafeInsets, imeBottom: Int,
            headerHeight: Int, toolbarHeight: Int, keyboardShortcutsHeight: Int
        ): SessionViewportGeometry {
            val left = (safe.left - originX).coerceAtLeast(0)
            val top = (safe.top - originY).coerceAtLeast(0)
            val right = minOf(availableWidth, windowWidth - safe.right - originX).coerceAtLeast(left)
            val logicalBottom = windowHeight - safe.bottom - originY
            val localBottom = minOf(availableHeight, windowHeight - maxOf(safe.bottom, imeBottom) - originY)
            val logicalHeight = (logicalBottom - top - headerHeight - toolbarHeight).coerceAtLeast(0)
            val shortcuts = if (imeBottom > 0) keyboardShortcutsHeight.coerceAtLeast(0) else 0
            val localHeight = (localBottom - top - headerHeight - toolbarHeight - shortcuts).coerceIn(0, logicalHeight)
            return SessionViewportGeometry(left, top, right - left, logicalHeight, localHeight, shortcuts)
        }
    }
}
