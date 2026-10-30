package com.rdp.sync.ui.rdp

/** Core1.15 approximates pre-30 bar visibility from raw insets even when flags hide them.
 * Keep full desktop content separate from a conservative transient-bar-safe control envelope.
 */
internal object SessionInsetPolicy {
    // Values of the public View.SYSTEM_UI_FLAG_* API; no Android dependency in tests.
    private const val FULLSCREEN = 0x00000004
    private const val HIDE_NAVIGATION = 0x00000002
    private val none = SessionSafeInsets(0, 0, 0, 0)

    fun visibleInsets(api: Int, windowUi: Int, status: SessionSafeInsets,
        navigation: SessionSafeInsets, permanent: SessionSafeInsets): SessionSafeInsets {
        val visibleStatus = if (api < 30 && windowUi and FULLSCREEN != 0) none else status
        val visibleNavigation = if (api < 30 && windowUi and HIDE_NAVIGATION != 0) none else navigation
        return union(permanent, union(visibleStatus, visibleNavigation))
    }

    fun barVisibility(api: Int, windowUi: Int, status: Boolean, navigation: Boolean): Pair<Boolean, Boolean> =
        (status && !(api < 30 && windowUi and FULLSCREEN != 0)) to
            (navigation && !(api < 30 && windowUi and HIDE_NAVIGATION != 0))

    fun controlInsets(api: Int, fullscreen: Boolean, stable: SessionSafeInsets,
        visible: SessionSafeInsets): SessionSafeInsets = if (api < 30 && fullscreen) union(stable, visible) else visible

    fun controlPosition(geometry: SessionViewportGeometry, controlWidth: Int, controlHeight: Int,
        windowWidth: Int, windowHeight: Int, originX: Int, originY: Int,
        controlSafe: SessionSafeInsets, imeBottom: Int): Pair<Int, Int> {
        val left = maxOf(geometry.left, controlSafe.left - originX)
        val top = maxOf(geometry.desktopTop, controlSafe.top - originY)
        val right = minOf(geometry.left + geometry.width, windowWidth - controlSafe.right - originX)
        val bottom = minOf(geometry.desktopTop + geometry.localDesktopHeight,
            windowHeight - maxOf(controlSafe.bottom, imeBottom) - originY)
        return (right - controlWidth).coerceAtLeast(left) to (bottom - controlHeight).coerceAtLeast(top)
    }

    private fun union(a: SessionSafeInsets, b: SessionSafeInsets) = SessionSafeInsets(
        maxOf(a.left, b.left), maxOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
}
