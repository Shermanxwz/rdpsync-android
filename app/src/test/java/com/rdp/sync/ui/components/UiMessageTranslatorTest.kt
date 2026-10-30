package com.rdp.sync.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class UiMessageTranslatorTest {
    private val vocabulary = mapOf(
        "已连接" to "Connected", "已断开" to "Disconnected", "连接失败" to "Connection failed",
        "同步失败: " to "Sync failed: ", "WebDAV 测试失败: " to "WebDAV test failed: ",
        "云端没有 " to "Cloud file missing: ",
        "。旧版备份请明确选择「旧版含密码文件」；本机设备未改动。" to ". Local profiles unchanged.",
        "已上传 " to "Uploaded ", " 台设备至 " to " profiles to ", "含明文密码" to "plain-text passwords",
        "已合并 " to "Merged ", " 台设备（相同设备以本机为准）；文件：" to " profiles; file: ",
        "均衡" to "Balanced", "网络稳定" to "Network stable", "（等待服务器）" to " (waiting for server)",
        "网络测量不可用 / 等待中" to "Network measurements pending",
        "认证失败或无权限，请检查用户名、密码和目录权限。" to "Authentication failed."
    )
    private fun translate(value: String) = translateUiMessage(value, vocabulary::get)

    @Test fun translatesOnlyExactFixedMessages() {
        assertEquals("Connected", translate("已连接"))
        assertEquals("x 已断开 y", translate("x 已断开 y"))
        assertEquals("已连接.json", translate("已连接.json"))
        assertEquals("https://已连接.example/已断开", translate("https://已连接.example/已断开"))
    }

    @Test fun filenamesContainingEveryKnownWordRemainByteForByte() {
        vocabulary.keys.forEach { word ->
            val name = "prefix-$word-suffix.json"
            assertEquals("Cloud file missing: $name. Local profiles unchanged.", translate("云端没有 $name。旧版备份请明确选择「旧版含密码文件」；本机设备未改动。"))
            assertEquals("Uploaded 2 profiles to $name (plain-text passwords)", translate("已上传 2 台设备至 $name（含明文密码）"))
            assertEquals("Merged 2 profiles; file: $name", translate("已合并 2 台设备（相同设备以本机为准）；文件：$name"))
        }
    }

    @Test fun unknownFailureDetailsAndNativeErrorTailRemainVerbatim() {
        assertEquals("Sync failed: x 已断开 y", translate("同步失败: x 已断开 y"))
        assertEquals("FreeRDP Connection failed [0x0002001c]: host 已连接.json", translate("FreeRDP 连接失败 [0x0002001c]: host 已连接.json"))
        assertEquals("HTTP 500: x 已连接 y", translate("HTTP 500: x 已连接 y"))
        assertEquals("HTTP 401: Authentication failed.", translate("HTTP 401: 认证失败或无权限，请检查用户名、密码和目录权限。"))
    }

    @Test fun translatesOnlyRecognizedWholeDisplayTemplates() {
        assertEquals("1280 × 720 → 1920 × 1080 (waiting for server)", translate("1280 × 720 → 1920 × 1080（等待服务器）"))
        assertEquals("1280 × 720 · Balanced · RTT 20 ms · Network stable", translate("1280 × 720 · 均衡 · RTT 20 ms · 网络稳定"))
        assertEquals("1280 × 720 · 均衡 · x 已连接 y", translate("1280 × 720 · 均衡 · x 已连接 y"))
        assertEquals("1280 × 720 · Balanced · Network measurements pending", translate("1280 × 720 · 均衡 · 网络测量不可用 / 等待中"))
    }
}
