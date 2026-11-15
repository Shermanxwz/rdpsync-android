package com.rdp.sync.network

/**
 * Cross-platform direct text delivery strategy.
 * SMART_AUTO automatically uses native UnicodeKeyboardEvent on Windows peers and
 * atomic CLIPRDR (CF_UNICODETEXT + UTF8_STRING) + Ctrl+V with ISO-14755 hex fallback
 * on Ubuntu / xrdp (OSMAJORTYPE_UNIX) peers so mobile Chinese IMEs work identically.
 */
enum class LinuxTextInputMode(val nativeCode: Int) {
    SMART_AUTO(0),
    UBUNTU_GUI_PASTE(1),
    UBUNTU_TERM_PASTE(2),
    UBUNTU_IBUS_HEX(3),
    UNICODE_ONLY(4);

    fun next(): LinuxTextInputMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        fun fromName(name: String?): LinuxTextInputMode =
            entries.firstOrNull { it.name == name } ?: SMART_AUTO
    }
}
