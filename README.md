# RdpSync

> Android RDP 远程桌面 + WebDAV 设备列表同步。一个面向手机/平板的轻量 RDP 连接管理器。

RdpSync 的目标是把“远程 Windows 桌面连接”和“多设备配置同步”做成一个顺手的移动端工具：本地管理 RDP 主机列表，通过 WebDAV 在多台设备间共享列表，连接时按手机屏幕尺寸请求 RDP 会话分辨率，不修改远程机器的物理显示器分辨率。

## 功能

- RDP 设备列表管理：名称、主机、端口、用户名、密码、域。
- WebDAV 同步：固定同步文件 `rdpsync_devices.json`。
- WebDAV 测试连接：设置页可直接验证账号、路径、读写权限，并自动创建缺失目录。
- 同步模式：智能合并、上传本机列表、下载云端列表。
- 真 RDP 引擎：Rust JNI 后台线程接入 IronRDP `RdpClient`。
- 移动端操作：触摸/鼠标模式、点击、双击、长按右键、拖拽、双指缩放/平移、Ctrl+Alt+Del、文本发送。
- 手机分辨率适配：连接时按当前手机屏幕像素请求远程会话尺寸。
- 自定义应用图标。

## 下载

当前调试 APK 已放在仓库：

- `releases/rdpsync-real-rdp-1.0.0.apk`

当前 APK：

- ABI：`arm64-v8a`
- 最低系统：Android 8.0 / API 26
- 类型：debug APK

## 使用流程

### 1. 添加 RDP 设备

1. 打开 App。
2. 点击右下角 `+`。
3. 填写主机、端口、用户名、密码、域。
4. 保存后进入详情页，点击连接。

### 2. WebDAV 同步

1. 在设备列表页点击右上角设置。
2. 填 WebDAV 目录地址，例如：

   ```text
   https://example.com/dav/rdpsync
   ```

3. 填用户名和密码。
4. 先点“测试连接”。测试会执行：
   - PROPFIND 检查目录。
   - MKCOL 自动创建缺失目录。
   - 检查账号和路径权限。
5. 保存后使用右上角云同步按钮：
   - 智能合并：推荐，按 `host:port:username:domain` 去重。
   - 上传本机列表：本机覆盖云端。
   - 下载云端列表：云端覆盖本机。

### 3. RDP 操作

- 鼠标模式：拖动移动鼠标，点击为左键，长按为右键。
- 触摸模式：点哪里点哪里，拖动即拖拽。
- 双指缩放/平移用于适配手机屏幕。
- 支持 Ctrl+Alt+Del。
- 支持发送文本到远程桌面。

## RDP 兼容性说明

当前版本默认使用 TLS + 图形登录兼容模式，避开部分 Windows/服务端上 CredSSP/NLA 报错问题。它适合：

- Windows 远程桌面关闭“仅允许运行使用网络级别身份验证的远程桌面的计算机连接”。
- xrdp/部分轻量 RDP 服务端。
- 局域网或自有可信网络环境。

如果远程 Windows 强制 NLA/CredSSP，后续版本会加入“认证模式：自动 / NLA / 兼容”的显式开关。

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
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
jar tf app/build/outputs/apk/debug/app-debug.apk | grep -E 'lib/.*/librdpsync.so|classes.dex|AndroidManifest.xml'
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

- 认证模式开关：自动 / CredSSP NLA / TLS 图形登录。
- 释放签名 APK。
- 多 ABI：arm64-v8a + x86_64。
- 更完整剪贴板通道。
- 屏幕旋转时动态重连/调整桌面尺寸。

## License

当前仓库尚未声明开源许可证。正式公开分发前建议补充 LICENSE。
