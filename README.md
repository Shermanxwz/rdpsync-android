# RdpSync

> Android RDP 远程桌面 + WebDAV 设备列表同步 — 接近甚至超越 Microsoft RD Client 的移动端 RDP 体验。

## V1.0.7 最终版 (2026-06-30)

**核心体验：**

- 🚀 **极速流畅** — native dirty rect、frameId 去重、Bitmap/像素缓冲复用、自定义 View 局部刷新，显著降低静止页面和表格场景的 CPU/GC 开销
- 📱 **手机比例全屏** — 自动适配手机竖屏分辨率，无黑边，Windows 桌面按手机屏幕比例显示
- ⚡ **智能分辨率缩放** — 0.55 系数平衡清晰度与流畅度，远端像素数控制在 ~90 万级别确保丝滑
- 🎨 **真实色彩** — RGBX32 → ARGB 精确通道转换，无颜色偏移

## 与同类 App 对比

| 功能 | RdpSync | Microsoft RD Client | aFreeRDP | 1Remote (PC) |
|------|---------|-------------------|----------|-------------|
| RDP 连接 | ✅ | ✅ | ✅ | ✅ |
| 移动端触摸优化 | ✅ | ✅ | ✅ | ❌ PC only |
| 流畅度（触控拖动） | ✅✅ 超越 | ✅ | ❌ | -- |
| 手机比例全屏 | ✅ | ✅ | ❌ | ❌ |
| 多设备配置同步 | ✅ WebDAV | ❌ | ❌ | ✅ MySQL |
| 列表云端共享 | ✅ 任意 WebDAV | ❌ | ❌ | ❌ 需自建 |
| 屏幕尺寸自适应 | ✅ 按手机像素 | ❌ 固定分辨率 | ❌ 固定 | ❌ |
| 开源 | ✅ MIT | ❌ | ✅ GPLv2 | ✅ MIT |

## 当前状态

- 最新版本：`1.0.7`
- APK：从 GitHub Releases 获取
- ABI：`arm64-v8a`
- 最低系统：Android 8.0 / API 26
- RDP 引擎：FreeRDP 3.24.2（C JNI bridge）
- 加密：OpenSSL 3.4.1（静态链接）
- 配置同步：WebDAV `rdpsync_devices.json`

## 构建

```bash
# 需要 Android NDK 28、JDK 21
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash scripts/build-apk.sh
```

## 许可证

MIT License
