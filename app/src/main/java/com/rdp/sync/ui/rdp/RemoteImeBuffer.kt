package com.rdp.sync.ui.rdp

/** Keep unfinished IME composition local; only committed text reaches Windows. */
class RemoteImeBuffer(private val sendText: (String) -> Unit, private val sendKey: (Int) -> Unit) {
    var composing = ""
        private set
    fun compose(text: String) { composing = text }
    fun commit(text: String) { composing = ""; if (text.isNotEmpty()) sendText(text) }
    fun finish() { val text = composing; composing = ""; if (text.isNotEmpty()) sendText(text) }
    fun clear() { composing = "" }
    fun deleteCodeUnits(before: Int, after: Int) = delete(before, after, false)
    fun deleteCodePoints(before: Int, after: Int) = delete(before, after, true)
    private fun delete(before: Int, after: Int, codePoints: Boolean) {
        if (composing.isNotEmpty()) {
            var end = if (codePoints) {
                val points = composing.codePointCount(0, composing.length)
                composing.offsetByCodePoints(0, (points - before.coerceAtLeast(0)).coerceAtLeast(0))
            } else (composing.length - before.coerceAtLeast(0)).coerceAtLeast(0)
            // Do not retain half a surrogate even if a broken IME splits it.
            if (end > 0 && end < composing.length && composing[end - 1].isHighSurrogate() && composing[end].isLowSurrogate()) end--
            composing = composing.substring(0, end)
        } else repeat(before.coerceIn(0, 1000)) { sendKey(0x0E) }
        repeat(after.coerceIn(0, 1000)) { sendKey(0xE053) }
    }
}
