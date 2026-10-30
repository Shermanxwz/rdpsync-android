package com.rdp.sync.ui.rdp

/** Physical window insets, before any Compose child consumes them. */
internal data class SessionSafeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class SessionViewportGeometry(
    val left: Int,
    val top: Int,
    val width: Int,
    val logicalDesktopWidth: Int,
    val logicalDesktopHeight: Int,
    val localDesktopHeight: Int,
    val desktopTop: Int,
    val imeOccluded: Boolean
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
            headerHeight: Int, toolbarHeight: Int,
            fullscreen: Boolean = false, localSafe: SessionSafeInsets = safe
        ): SessionViewportGeometry {
            // `safe` is the stable logical inset policy: system bars in bordered
            // mode, only cutout/non-hideable caption in fullscreen. Visible transient
            // bars affect local cropping, never remote size or the reference fit.
            val logicalLeft = (safe.left - originX).coerceAtLeast(0)
            val logicalTop = (safe.top - originY).coerceAtLeast(0)
            val logicalRight = (windowWidth - safe.right - originX).coerceAtLeast(logicalLeft)
            val logicalBottom = windowHeight - safe.bottom - originY
            val left = (maxOf(safe.left, localSafe.left) - originX).coerceAtLeast(0)
            val top = (maxOf(safe.top, localSafe.top) - originY).coerceAtLeast(0)
            val right = minOf(availableWidth, windowWidth - maxOf(safe.right, localSafe.right) - originX).coerceAtLeast(left)
            val localBottom = minOf(availableHeight,
                windowHeight - maxOf(safe.bottom, localSafe.bottom, imeBottom) - originY)
            val logicalHeader = if (fullscreen) 0 else headerHeight
            val logicalToolbar = if (fullscreen) 0 else toolbarHeight
            val logicalHeight = (logicalBottom - logicalTop - logicalHeader - logicalToolbar).coerceAtLeast(0)
            val imeOccluded = imeBottom > 0
            // Inactive base chrome stays measured so an IME never changes DISP.
            val floatingControls = fullscreen || imeOccluded
            val desktopTop = top + if (floatingControls) 0 else headerHeight
            val localToolbar = if (floatingControls) 0 else toolbarHeight
            val localHeight = (localBottom - desktopTop - localToolbar).coerceAtLeast(0)
            return SessionViewportGeometry(left, top, right - left, logicalRight - logicalLeft,
                logicalHeight, localHeight, desktopTop, imeOccluded)
        }
    }
}
