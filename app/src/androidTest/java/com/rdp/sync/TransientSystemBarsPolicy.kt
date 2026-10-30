package com.rdp.sync

/** Exact AOSP Android15 WindowManager dump contract, never app-requested Insets visibility. */
internal data class TransientSystemBarsPolicy(
    val status: String,
    val navigation: String,
    val showing: Set<String>,
    val section: String
) {
    companion object {
        /** Pinned-format synthetic mutations; called before the real instrumented observation. */
        fun verifyParserContract() {
            val base = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" +
                "  Display: mDisplayId=0\n  InsetsPolicy\n    status: WINDOW_STATE_HIDDEN\n    nav: WINDOW_STATE_HIDDEN\n\n"
            check(parse(base).showing.isEmpty())
            val visible = base.replace("    nav: WINDOW_STATE_HIDDEN\n", "    nav: WINDOW_STATE_SHOWING\n    mShowingTransientTypes=statusBars navigationBars\n")
            check(parse(visible).showing == setOf("statusBars", "navigationBars"))
            val malformed = listOf(
                base.substringAfter('\n'), base.replace("mDisplayId=0", "mDisplayId=1"),
                base.trimEnd(), base.replace("    nav: WINDOW_STATE_HIDDEN\n", ""),
                base.replace("    nav: WINDOW_STATE_HIDDEN", "    nav: WINDOW_STATE_UNKNOWN"),
                base.replace("    nav: WINDOW_STATE_HIDDEN", "    nav: WINDOW_STATE_HIDDEN\n    nav: WINDOW_STATE_HIDDEN"),
                visible.replace("statusBars navigationBars", "ime"),
                visible.replace("statusBars navigationBars", "0"),
                base + "  Display: mDisplayId=0\n  InsetsPolicy\n    status: WINDOW_STATE_HIDDEN\n    nav: WINDOW_STATE_HIDDEN\n\n"
            )
            malformed.forEach { check(runCatching { parse(it) }.isFailure) { "Malformed system policy accepted" } }
        }
        fun parse(dump: String): TransientSystemBarsPolicy {
            require(dump.startsWith("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n")) { "Missing complete WindowManager display dump" }
            val displays = Regex("(?m)^\\s*Display: mDisplayId=(\\d+)(?: \\(organized\\))?$").findAll(dump).toList()
            val index = displays.indices.singleOrNull { displays[it].groupValues[1] == "0" }
                ?: error("Missing or ambiguous display0")
            val start = displays[index].range.first
            val end = displays.getOrNull(index + 1)?.range?.first ?: dump.length
            val lines = dump.substring(start, end).lines()
            val policy = lines.indices.singleOrNull { lines[it].trim() == "InsetsPolicy" }
                ?: error("Missing or ambiguous display0 InsetsPolicy")
            val indent = lines[policy].takeWhile(Char::isWhitespace) + "  "
            val last = (policy + 1 until lines.size).firstOrNull { lines[it].isEmpty() }
                ?: error("Truncated InsetsPolicy section")
            val values = linkedMapOf<String, String>()
            for (line in lines.subList(policy + 1, last)) {
                require(line.startsWith(indent)) { "Malformed policy indentation" }
                val entry = line.removePrefix(indent)
                val parts = when {
                    entry.startsWith("status: ") -> "status" to entry.removePrefix("status: ")
                    entry.startsWith("nav: ") -> "nav" to entry.removePrefix("nav: ")
                    entry.startsWith("mShowingTransientTypes=") -> "showing" to entry.substringAfter('=')
                    entry.startsWith("mForcedShowingTypes=") -> "forced" to entry.substringAfter('=')
                    else -> error("Unknown InsetsPolicy field")
                }
                require(parts.second.isNotEmpty() && values.put(parts.first, parts.second) == null) { "Empty or duplicate policy field" }
            }
            val states = setOf("WINDOW_STATE_SHOWING", "WINDOW_STATE_HIDING", "WINDOW_STATE_HIDDEN")
            val status = requireNotNull(values["status"])
            val navigation = requireNotNull(values["nav"])
            require(status in states && navigation in states) { "Unknown policy window state" }
            val showing = values["showing"]?.split(' ')?.toSet() ?: emptySet()
            require(showing.all { it in setOf("statusBars", "navigationBars") }) { "Unknown transient type" }
            return TransientSystemBarsPolicy(status, navigation, showing,
                lines.subList(policy, last).joinToString("\n"))
        }
    }
}
