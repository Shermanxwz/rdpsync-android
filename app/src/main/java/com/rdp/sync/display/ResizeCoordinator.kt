package com.rdp.sync.display

/**
 * Tracks a request separately from the server's actual framebuffer. No implicit reconnects.
 * A settled new target is requested once, with a bounded acknowledgement wait.
 */
class ResizeCoordinator {
    private var candidate: DesktopSize? = null
    private var candidateAt = 0L
    private var requestedAt = 0L
    var requested: DesktopSize? = null
        private set
    var failedTarget: DesktopSize? = null
        private set
    var failure: String? = null
        private set

    fun reset() {
        candidate = null
        requested = null
        failedTarget = null
        failure = null
        candidateAt = 0L
        requestedAt = 0L
    }

    fun update(now: Long, desired: DesktopSize, actual: DesktopSize, capability: Int, send: (DesktopSize) -> Int) {
        if (requested == actual) {
            requested = null
            failure = null
        }
        if (requested != null && now - requestedAt >= 8_000) {
            failedTarget = requested
            requested = null
            failure = "服务器未确认新分辨率；保留当前画面，可手动重连应用设置"
        }
        if (candidate != desired) {
            candidate = desired
            candidateAt = now
        }
        if (desired == actual || requested != null || desired == failedTarget || capability != 2 || now - candidateAt < 750) return
        when (send(desired)) {
            0 -> { requested = desired; requestedAt = now; failure = null }
            -1 -> failure = "连接已断开"
            -2 -> failure = "服务器尚未协商动态分辨率"
            -4 -> { failedTarget = desired; failure = "目标分辨率超过服务器显示上限" }
            else -> { failedTarget = desired; failure = "动态分辨率请求失败；保留当前画面" }
        }
    }
}
