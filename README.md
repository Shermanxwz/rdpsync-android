# RdpSync

> Android RDP 远程桌面客户端 · WebDAV 设备同步 · 针对移动端触摸操作深度优化
> 
> **已知唯一同时具备 WebDAV 同步 + 移动端触控优化的开源 Android RDP 客户端**

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org)
[![FreeRDP](https://img.shields.io/badge/FreeRDP-3.24.2-orange)](https://www.freerdp.com)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

## ✨ 核心特性

### 渲染性能（V1.0.7 经过完整流畅度优化）

- **Bitmap 复用** — 分辨率变化时创建一次，不再每帧分配新 Bitmap，消除 GC 压力
- **frameId 去重刷新** — 画面静止时 CPU 降至接近零，不再每 16ms 无效轮询
- **dirty rect 局部更新** — 只拷贝和绘制 FreeRDP GDI 报告的脏区域，而非整帧搬运
- **自定义 AndroidView 渲染** — 绕过 Compose Image 重组开销，直接 `onDraw(canvas.drawBitmap)`
- **RGBX32 → ARGB 向量化转换** — 零色彩偏差的像素格式转换

### 输入体验

- **双模式输入** — 触控板鼠标模式 / 直接触摸模式一键切换
- **精准触摸滚动** — 轴锁定 + 速度滤波 + fling 惯性衰减，接近原生滚动手感
- **60fps 滚轮调度** — Android 端对 Windows 端的平滑滚轮事件发送
- **键盘输入** — 完整的 Unicode 文本发送支持

### 连接管理

- **WebDAV 设备同步** — 连接配置（主机/端口/账号/分辨率）加密存储并通过 WebDAV 在多设备间共享
- **自适应分辨率** — 按手机屏幕比例自动计算远端桌面分辨率，折叠屏友好
- **屏幕常亮** — RDP 连接期间保持屏幕唤醒
- **硬件加速** — AndroidManifest 启用 GPU 渲染

### 隐私与安全

- 所有数据本地存储（Room 数据库 + DataStore）
- WebDAV 通信使用 Basic Auth over HTTPS
- 无遥测、无分析、无第三方数据收集

## 🏗️ 技术架构

| 层 | 技术栈 |
|---|--------|
| UI | Jetpack Compose + 自定义 AndroidView（`RdpBitmapView`） |
| 状态管理 | Compose `mutableStateOf` + ViewModel + Flow |
| 持久化 | Room (SQLite) + DataStore Preferences |
| 网络同步 | OkHttp + WebDAV (Basic Auth over HTTPS) |
| RDP 引擎 | FreeRDP 3.24.2（C JNI bridge，ARM64 交叉编译） |
| 加密 | OpenSSL 3.4.1（静态链接到 native `.so`） |
| 构建 | Gradle + CMake + NDK 26 |

### 渲染数据流

```
FreeRDP GDI EndPaint (dirty rects)
  → native C 桥接层记录脏区域
  → JNI: nativeGetFrameId() → frameId 去重判断
  → JNI: nativeCopyFrameArgb(IntArray) → 仅拷贝脏区域像素
  → Kotlin: Bitmap.setPixels(dirtyRect)
  → RdpBitmapView: postInvalidateOnAnimation(dirtyRect)
  → View.onDraw(canvas.drawBitmap)
```

## 📊 与同类客户端对比

| 能力 | RdpSync | Microsoft RD Client | aFreeRDP | bVNC | 1Remote |
|------|---------|-------------------|----------|------|---------|
| RDP 连接 | ✅ | ✅ | ✅ | ❌ VNC only | ✅ (PC) |
| 触控板鼠标模式 | ✅ | ✅ | ✅ | -- | ❌ |
| 直接触摸模式 | ✅ | ✅ | ❌ | -- | ❌ |
| 触摸滚动优化 | ✅✅ 超越 | ✅ | ❌ | ❌ | -- |
| 脏区域局部刷新 | ✅ | ❌ 整帧 | ✅ | ❌ | ❌ |
| frameId 去重 | ✅ | ❌ | ❌ | ❌ | ❌ |
| 自适应手机分辨率 | ✅ | ✅ | ❌ | ❌ | ❌ |
| WebDAV 设备同步 | ✅ | ❌ | ❌ | ❌ | ❌ |
| 硬件加速 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 开源 | ✅ MIT | ❌ | ✅ GPLv2 | ✅ GPLv3 | ✅ MIT |
| 移动端 | ✅ | ✅ | ✅ | ✅ | ❌ |

## 🚀 快速开始

### 下载

从 [Releases](https://github.com/Shermanxwz/rdpsync-android/releases) 获取最新 APK。

- **当前版本**：`1.0.7`
- **ABI**：`arm64-v8a`（64 位 ARM）
- **最低系统**：Android 8.0 (API 26)

### 使用

1. 添加设备 — 填写 Windows 主机地址、端口、凭据
2. 可选：配置 WebDAV 同步 — 输入 WebDAV 地址和凭据，设备列表自动云端备份
3. 点击连接 — 进入远程桌面
4. 工具栏按钮切换触摸/鼠标模式

### 构建

```bash
# 要求：Android NDK 26+、JDK 21+
git clone https://github.com/Shermanxwz/rdpsync-android.git
cd rdpsync-android
bash scripts/build-apk.sh
```

## 📂 项目结构

```
app/src/main/
├── cpp/                    # FreeRDP JNI 桥接层 (C)
│   └── freerdp_bridge.c    # nativeGetFrameId, nativeCopyFrameArgb, 脏区域管理
├── java/com/rdp/sync/
│   ├── data/               # Room Entity + DAO
│   ├── database/           # Room Database
│   ├── manager/            # 同步管理器
│   ├── network/            # RDP 连接器 + WebDAV 服务
│   ├── repository/         # 设备仓库
│   ├── ui/
│   │   ├── MainActivity.kt
│   │   ├── navigation/     # Compose 导航
│   │   ├── screens/        # 设备列表/编辑/详情/连接界面
│   │   └── theme/          # 主题 + 排版
│   └── viewmodel/          # DeviceViewModel
└── res/                    # Android 资源
```

## 📝 更新日志

见 [CHANGELOG.md](CHANGELOG.md)

## 📄 许可证

MIT License — 详见 [LICENSE](LICENSE)

## 🙏 致谢

- [FreeRDP](https://www.freerdp.com) — 开源 RDP 协议实现
- [OkHttp](https://square.github.io/okhttp/) — HTTP 客户端
- Jetpack Compose / Room / DataStore — Android 官方框架
