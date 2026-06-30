# RdpSync

> Android RDP 远程桌面 + WebDAV 设备列表同步 — **首个支持多设备 RDP 配置同步的移动客户端**。

RdpSync 是目前唯一一个将 **RDP 远程桌面** 与 **WebDAV 云端同步** 整合到一体的 Android 应用：本地管理 RDP 主机列表（IP、端口、凭据），通过 WebDAV 在多台手机/平板之间自动同步设备配置，连接时按手机屏幕尺寸请求最佳 RDP 分辨率，不修改远程机器的物理显示器。

**核心定位**：如果你有多台 Windows 机器需要远程管理，同时希望手机和平板上的 RDP 列表始终保持一致——RdpSync 是唯一的选择。

## 与同类 App 对比

| 功能 | RdpSync | Microsoft RD Client | aFreeRDP | 1Remote (PC) |
|------|---------|-------------------|----------|-------------|
| RDP 连接 | ✅ | ✅ | ✅ | ✅ |
| 移动端触摸优化 | ✅ | ✅ | ✅ | ❌ PC only |
| 多设备配置同步 | ✅ WebDAV | ❌ | ❌ | ✅ MySQL |
| 列表云端共享 | ✅ 任意 WebDAV | ❌ | ❌ | ❌ 需自建 |
| 屏幕尺寸自适应 | ✅ 按手机像素 | ❌ 固定分辨率 | ❌ 固定 | ❌ |
| 开源 | ✅ MIT | ❌ | ✅ GPLv2 | ✅ MIT |

**WebDAV 同步的优势**：无需自建服务器，任何支持 WebDAV 的云盘（群晖、NextCloud、ownCloud、IIS、Caddy 等）都可作为同步后端。同步文件为明文 JSON，可手动编辑或通过其他工具读取。

## 当前状态

- 最新版本：`1.0.6`
- APK：从 GitHub Releases 获取
- ABI：`arm64-v8a`
- 最低系统：Android 8.0 / API 26
- RDP 引擎：FreeRDP 3.24.2（C JNI bridge）
- 加密：OpenSSL 3.4.1（静态链接）
- 配置同步：WebDAV `rdpsync_devices.json`

## 功能

- RDP 设备列表管理：名称、主机、端口、用户名、密码、域、CredSSP主机名。
- WebDAV 同步：固定同步文件 `rdpsync_devices.json`。
- WebDAV 测试连接：设置页可直接验证账号、路径、读写权限，并自动创建缺失目录。
- 同步模式：智能合并、上传本机列表、下载云端列表。
- RDP 引擎：FreeRDP 3.24.2 C JNI bridge。
- 移动端操作：触摸/鼠标模式、点击、双击、长按右键、拖拽、双指缩放/平移、Ctrl+Alt+Del、文本发送。
- 手机分辨率适配：连接时按当前手机屏幕像素请求远程会话尺寸。
- 自定义应用图标，多密度 mipmap 已生成。

## 实机 RDP 调试结论

针对真实 RDP 机器（NLA/CredSSP 强制服务端）完成过端到端调试。当前版本通过 FreeRDP 引擎直接连接，支持 NLA/CredSSP 和 TLS 两种安全协议。

## 使用流程

### 1. 添加 RDP 设备

1. 打开 App。
2. 点击右下角 `+`。
3. 填写主机、端口、用户名、密码、域。
4. 保存后进入详情页，点击连接。

### 2. RDP 登录填写建议

Windows 本地账号：

```text
用户名: Administrator
域: 留空
密码: 你的密码
```

域账号：

```text
用户名: 只填用户名
域: 填域名
密码: 域账号密码
```

如果用户名误填成 `DOMAIN\user` 且域为空，App 会自动拆分成 `domain=DOMAIN`、`username=user`。

### 3. WebDAV 同步

1. 在设备列表页点击右上角设置。
2. 填 WebDAV 目录地址，例如：

   ```text
   https://example.com/dav/rdpsync
   ```

3. 填用户名和密码。
4. 先点“测试连接”。测试会执行：
   - URL 归一化；如果误填到 `rdpsync_devices.json` 文件，会自动转父目录。
   - PROPFIND 检查目录。
   - 逐级 MKCOL 自动创建缺失目录。
   - PUT/GET/DELETE 临时文件，确认目录可读写。
5. 保存后使用右上角云同步按钮：
   - 智能合并：推荐，按 `host:port:username:domain` 去重。
   - 上传本机列表：本机覆盖云端。
   - 下载云端列表：云端覆盖本机。

### 4. RDP 操作

- 鼠标模式：拖动移动鼠标，点击为左键，长按为右键。
- 触摸模式：点哪里点哪里，拖动即拖拽。
- 双指缩放/平移用于适配手机屏幕。
- 支持 Ctrl+Alt+Del。
- 支持发送文本到远程桌面。

## RDP 兼容性说明

当前版本默认使用 Windows 标准 NLA/CredSSP，适合大多数默认开启远程桌面的 Windows 10/11/Server。

如果提示 TCP 连接失败，请先确认手机到目标机器网络可达、端口开放、Windows 防火墙允许远程桌面。

## 技术栈

- Kotlin
- Jetpack Compose
- Room
- OkHttp
- C JNI bridge
- FreeRDP 3.24.2
- OpenSSL 3.4.1
- Android SDK 35
- NDK 28
- Gradle 8.13

## 构建

### Android APK

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleDebug
```

### RDP 引擎（FreeRDP）

FreeRDP 3.24.2 和 OpenSSL 已预编译为 arm64-v8a `.so` 文件（见 `app/src/main/jniLibs/`）。

如需重新编译：

```bash
# 1. 编译 OpenSSL
bash scripts/build-openssl-android.sh

# 2. 编译 FreeRDP
bash scripts/build-freerdp-android.sh

# 3. 编译 JNI bridge
export ANDROID_NDK=/opt/android-sdk/ndk/28.0.13004108
export CC=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
$CC -shared -o app/src/main/jniLibs/arm64-v8a/librdpsync.so \
    -O2 -fPIC \
    -I build/freerdp-android/freerdp-src/include \
    -I build/freerdp-android/freerdp-src/winpr/include \
    -I build/freerdp-android/openssl-arm64/include \
    -I build/freerdp-android/build-arm64/include \
    -L app/src/main/jniLibs/arm64-v8a/ \
    app/src/main/cpp/freerdp_bridge.c \
    -lfreerdp3 -lwinpr3 -llog -landroid
```

### 构建 APK

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew clean :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```text
app/src/main/java/com/rdp/sync/
├── data/                 Room Entity / DAO
├── database/             Room Database
├── manager/              WebDAV 同步编排
├── network/              RDP JNI facade + WebDAV 客户端
├── ui/                   Compose 页面和导航
└── viewmodel/            设备列表和同步状态

app/src/main/cpp/         C JNI bridge (FreeRDP)
```

## Roadmap

- 释放签名 APK。
- 动态屏幕旋转/分辨率适配。

## License

MIT License. See `LICENSE`.
