package com.rdp.sync.sharedspace

import com.rdp.sync.transfer.TransferNames

/** One policy for the local browser/imports and all native remote operations.
 * Pure and bounded: the native broker calls nativeKey while holding its lock. */
object SharedSpaceNames {
    @JvmStatic fun nativeKey(name: String): String? {
        if (name.length > 240 || name.any { Character.getType(it) == Character.LINE_SEPARATOR.toInt() || Character.getType(it) == Character.PARAGRAPH_SEPARATOR.toInt() }) return null
        return try {
            TransferNames.validate(name)
            if (name.toByteArray(Charsets.UTF_8).size > 255) return null
            TransferNames.collisionKey(name).takeIf { it.toByteArray(Charsets.UTF_8).size <= 1024 }
        } catch (_: IllegalStateException) { null }
    }
    /** Query-directory pattern policy; only '*' and '?' are wildcards. */
    @JvmStatic fun nativePatternKey(pattern: String): String? {
        if (pattern.length > 240 || pattern.toByteArray(Charsets.UTF_8).size > 255) return null
        if (nativeKey(pattern.replace('*', 'a').replace('?', 'a')) == null) return null
        return TransferNames.collisionKey(pattern).takeIf { it.toByteArray(Charsets.UTF_8).size <= 1024 }
    }
    fun validate(name: String): String {
        if (nativeKey(name) == null) throw SharedSpaceFailure(SharedSpaceFailure.Reason.INVALID_NAME)
        return name
    }
    fun path(path: String): String {
        if (path.length > 4096 || path.toByteArray(Charsets.UTF_8).size > 4096)
            throw SharedSpaceFailure(SharedSpaceFailure.Reason.INVALID_NAME)
        if (path.isEmpty()) return ""
        val components = path.split('/')
        if (components.size > 16) throw SharedSpaceFailure(SharedSpaceFailure.Reason.INVALID_NAME)
        components.forEach(::validate)
        return path
    }
    fun child(parent: String, name: String): String = path(if (path(parent).isEmpty()) validate(name) else "$parent/${validate(name)}")
}
