package com.rdp.sync.ui.components

/** Exact vocabulary or anchored app-owned templates only. Never replace inside opaque details. */
internal fun translateUiMessage(value: String, fixed: (String) -> String?): String {
    fixed(value)?.let { return it }
    fun translated(source: String) = fixed(source) ?: source
    // The outer app-owned failure envelope can be translated. Unknown inner
    // server details are retained verbatim, not recursively searched for words.
    for (prefix in listOf("同步失败: ", "WebDAV 测试失败: ")) {
        if (value.startsWith(prefix)) {
            val detail = value.removePrefix(prefix)
            return translated(prefix) + translateUiFailureDetail(detail, fixed)
        }
    }
    translateUiFailureDetail(value, fixed).takeIf { it != value }?.let { return it }
    Regex("^FreeRDP 连接失败 \\[(0x[0-9a-fA-F]{8})\\]: (.*)$").matchEntire(value)?.let {
        return "FreeRDP ${translated("连接失败")} [${it.groupValues[1]}]: ${it.groupValues[2]}"
    }
    Regex("^已上传 ([0-9]+) 台设备至 (.+)（(含明文密码|不含密码，旧版文件未改动)）$").matchEntire(value)?.let {
        return translated("已上传 ") + it.groupValues[1] + translated(" 台设备至 ") + it.groupValues[2] +
            " (" + translated(it.groupValues[3]) + ")"
    }
    Regex("^已导入 ([0-9]+) 台设备；未匹配的本机设备保留，缺失密码不覆盖本机密码$").matchEntire(value)?.let {
        return translated("已导入 ") + it.groupValues[1] + translated(" 台设备；未匹配的本机设备保留，缺失密码不覆盖本机密码")
    }
    Regex("^已合并 ([0-9]+) 台设备（相同设备以本机为准）；文件：(.+)$").matchEntire(value)?.let {
        return translated("已合并 ") + it.groupValues[1] + translated(" 台设备（相同设备以本机为准）；文件：") + it.groupValues[2]
    }
    Regex("^([0-9]+ × [0-9]+) → ([0-9]+ × [0-9]+)（等待服务器）$").matchEntire(value)?.let {
        return "${it.groupValues[1]} → ${it.groupValues[2]}" + translated("（等待服务器）")
    }
    Regex("^([0-9]+ × [0-9]+) · (.+)$").matchEntire(value)?.let { dimensions ->
        val tail = dimensions.groupValues[2]
        fixed(tail)?.let { return "${dimensions.groupValues[1]} · $it" }
        Regex("^(流畅 / 省流|均衡|清晰)( · RTT [0-9]+ ms| · 网络测量不可用 / 等待中)?(?: · (.+))?$").matchEntire(tail)?.let { quality ->
            val reason = quality.groupValues[3]
            // A reason not in the finite vocabulary is not interpreted.
            if (reason.isNotEmpty() && fixed(reason) == null) return value
            val network = quality.groupValues[2]
            val localizedNetwork = if (network == " · 网络测量不可用 / 等待中") " · " + translated("网络测量不可用 / 等待中") else network
            return dimensions.groupValues[1] + " · " + translated(quality.groupValues[1]) + localizedNetwork +
                if (reason.isEmpty()) "" else " · " + translated(reason)
        }
    }
    for (prefix in listOf("远程桌面运行检查失败（阶段：", "无法创建远程桌面会话（阶段：")) {
        if (value.startsWith(prefix) && value.endsWith("）")) {
            return translated(prefix) + value.removePrefix(prefix).removeSuffix("）") + ")"
        }
    }
    return value
}

private fun translateUiFailureDetail(value: String, fixed: (String) -> String?): String {
    fixed(value)?.let { return it }
    Regex("^HTTP ([0-9]{3}): (.+)$").matchEntire(value)?.let {
        val message = fixed(it.groupValues[2]) ?: return value
        return "HTTP ${it.groupValues[1]}: $message"
    }
    val prefix = "云端没有 "
    val suffix = "。旧版备份请明确选择「旧版含密码文件」；本机设备未改动。"
    if (value.startsWith(prefix) && value.endsWith(suffix) && value.length > prefix.length + suffix.length) {
        // Filename is an opaque capture, including Chinese status words.
        val filename = value.removePrefix(prefix).removeSuffix(suffix)
        return (fixed(prefix) ?: prefix) + filename + (fixed(suffix) ?: suffix)
    }
    return value
}
