package com.rdp.sync.transfer

import java.text.Normalizer
import java.util.Locale

object TransferNames {
    private val reserved = setOf("CON", "PRN", "AUX", "NUL", "CLOCK$", "CONIN$", "CONOUT$") +
        (1..9).flatMap { listOf("COM$it", "LPT$it") } + listOf("COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³")

    /** Reject, don't silently rewrite, peer names. All physical staged paths use independent UUIDs. */
    fun validate(name: String, limits: TransferLimits = TransferLimits()): String {
        if (name.isEmpty() || name.length > limits.maxNameUtf16Units || name == "." || name == ".." ||
            name.last() == '.' || name.last() == ' ' || name != Normalizer.normalize(name, Normalizer.Form.NFC) ||
            name.any { it in "/\\:<>\"|?*" || it.code < 32 || it.code in 127..159 || Character.getType(it) == Character.FORMAT.toInt() } ||
            name.substringBefore('.').uppercase(Locale.ROOT) in reserved) invalid()
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == name.length || !Character.isLowSurrogate(name[i + 1])) invalid()
                if (Character.getType(Character.toCodePoint(c, name[i + 1])) == Character.FORMAT.toInt()) invalid()
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) invalid()
                i++
            }
        }
        return name
    }

    fun collisionKey(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).uppercase(Locale.ROOT)
    fun validateDistinct(names: List<String>, limits: TransferLimits = TransferLimits()) {
        if (names.size > limits.maxFiles) throw TransferFailure(TransferFailure.Reason.LIMIT)
        val used = hashSetOf<String>()
        names.forEach { if (!used.add(collisionKey(validate(it, limits)))) throw TransferFailure(TransferFailure.Reason.DUPLICATE_NAME) }
    }
    private fun invalid(): Nothing = throw TransferFailure(TransferFailure.Reason.INVALID_NAME)
}
