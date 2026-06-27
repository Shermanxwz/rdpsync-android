# RdpSync

> Android RDP 远程桌面 + WebDAV 设备列表同步。一个面向手机/平板的轻量 RDP 连接管理器。

RdpSync 的目标是把“远程 Windows 桌面连接”和“多设备配置同步”做成一个顺手的移动端工具：本地管理 RDP 主机列表，通过 WebDAV 在多台设备间共享列表，连接时按手机屏幕尺寸请求 RDP 会话分辨率，不修改远程机器的物理显示器分辨率。

## 当前状态

- 最新版本：`1.0.3`
- 最新 APK：`releases/rdpsync-real-rdp-1.0.3.apk`
- ABI：`arm64-v8a`
- 最低系统：Android 8.0 / API 26
- 类型：debug APK
- RDP 引擎：IronRDP Rust JNI
- 配置同步：WebDAV `rdpsync_devices.json`

## 功能

- RDP 设备列表管理：名称、主机、端口、用户名、密码、域。
- WebDAV 同步：固定同步文件 `rdpsync_devices.json`。
- WebDAV 测试连接：设置页可直接验证账号、路径、读写权限，并自动创建缺失目录。
- 同步模式：智能合并、上传本机列表、下载云端列表。
- 真 RDP 引擎：Rust JNI 后台线程接入 IronRDP `RdpClient`。
- 移动端操作：触摸/鼠标模式、点击、双击、长按右键、拖拽、双指缩放/平移、Ctrl+Alt+Del、文本发送。
- 手机分辨率适配：连接时按当前手机屏幕像素请求远程会话尺寸。
- 自定义应用图标，多密度 mipmap 已生成。

## 实机 RDP 调试结论

针对真实 RDP 机器 `161.118.228.26:22389` 完成过端到端调试：

- TCP 端口连通。
- 服务端强制 NLA/CredSSP。
- FreeRDP `/sec:tls` 被服务端拒绝，返回 `HYBRID_REQUIRED_BY_SERVER`。
- FreeRDP `/sec:nla` 可进入认证/激活阶段。
- IronRDP probe 使用当前配置已连接成功并拿到首帧：`IMAGE 1280x720: connected`。

因此 1.0.3 的关键修复是：CredSSP/NLA 模式下只协商 HYBRID/HYBRID_EX，不再同时请求 TLS 图形登录降级。

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

当前 IronRDP 配置重点：

- `enable_credssp = true`
- `enable_tls = false` when CredSSP is enabled
- 只协商 HYBRID/HYBRID_EX，避免强制 NLA 服务端在 CredSSP 阶段失败。
- 自动把 `域\\用户` 拆成域和用户名，减少填错导致的 CredSSP 失败。
- 对 TCP 连接失败、CredSSP 认证失败、RDP 协商失败给出更明确的中文提示。

如果提示 TCP 连接失败，请先确认手机到目标机器网络可达、端口开放、Windows 防火墙允许远程桌面。

## 技术栈

- Kotlin
- Jetpack Compose
- Room
- OkHttp
- Rust JNI
- IronRDP
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

### Native RDP 引擎

Native JNI 构建使用 Android API 26 linker，因为 IronRDP 客户端依赖链会链接 Android `aaudio`：

```bash
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK=/opt/android-sdk/ndk/28.0.13004108
export NDK_HOME=$ANDROID_NDK
export CC_aarch64_linux_android=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
export CXX_aarch64_linux_android=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++
export AR_aarch64_linux_android=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$CC_aarch64_linux_android
cd app/src/main/jni/rdpsync-connector
cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/librdpsync.so ../../../jniLibs/arm64-v8a/librdpsync.so
```

## 验证清单

发布前至少运行：

```bash
cargo check --target aarch64-linux-android
cargo build --release --target aarch64-linux-android
./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
file app/src/main/jniLibs/*/*.so
jar tf app/build/outputs/apk/debug/app-debug.apk | grep -E 'lib/.*/librdpsync.so|classes.dex|AndroidManifest.xml|ic_launcher'
```

## 项目结构

```text
app/src/main/java/com/rdp/sync/
├── data/                 Room Entity / DAO
├── database/             Room Database
├── manager/              WebDAV 同步编排
├── network/              RDP JNI facade + WebDAV 客户端
├── ui/                   Compose 页面和导航
└── viewmodel/            设备列表和同步状态

app/src/main/jni/rdpsync-connector/
└── src/lib.rs            Rust JNI + IronRDP 客户端桥接
```

## Roadmap

- 释放签名 APK。
- 多 ABI：arm64-v8a + x86_64。
- 更完整剪贴板通道。
- 屏幕旋转时动态重连/调整桌面尺寸。
- 显式认证模式开关：自动 / CredSSP NLA / TLS 图形登录。

## License

MIT License. See `LICENSE`.
