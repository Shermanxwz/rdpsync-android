# RdpSync

RdpSync 是一个面向 Android 手机/平板的 RDP 连接管理器。

当前版本已经接入真实 IronRDP 客户端引擎：

- 管理 RDP 设备列表（名称、主机、端口、用户名、密码、域、桌面尺寸）
- 通过 WebDAV 在多设备之间同步 `rdpsync_devices.json`
- Rust JNI 后台线程运行 IronRDP `RdpClient` 真实 TCP/TLS/CredSSP 会话
- 接收 IronRDP framebuffer 更新并输出到 Android `Bitmap`
- 移动端远程桌面操作界面：鼠标/触摸模式、点击/双击/长按、拖拽、双指缩放/平移、Ctrl+Alt+Del、文本输入
- Kotlin + Jetpack Compose + Room + OkHttp + Rust JNI + IronRDP

## WebDAV 同步

1. 打开应用右上角“设置”。
2. 填入 WebDAV 地址、用户名、密码，例如：`https://example.com/dav/rdpsync`。
3. 点击顶部云同步按钮：
   - 智能合并：按 `host:port:username:domain` 去重后合并本地与云端列表。
   - 上传本机列表：用本机列表覆盖云端文件。
   - 下载云端列表：用云端文件覆盖本机列表。

同步文件固定为：`rdpsync_devices.json`。

## RDP 操作

- 鼠标模式：拖动移动指针，点击左键，双指缩放/平移。
- 触摸模式：点哪里点哪里，拖动即拖拽，双指缩放/平移。
- 支持 Ctrl+Alt+Del。
- 文本输入通过 RDP Unicode key events 发送。

## 构建

环境：JDK 21、Android SDK 35、Gradle 8.13、NDK 28。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH=/opt/gradle/gradle-8.13/bin:$PATH:$HOME/.cargo/bin
gradle :app:assembleDebug
```

Native JNI 构建使用 Android API 26 linker，因为 IronRDP 客户端依赖的音频底层会链接 Android `aaudio`：

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

## 已验证

- `cargo check --target aarch64-linux-android` 成功
- `cargo build --release --target aarch64-linux-android` 成功
- `gradle :app:assembleDebug` 成功
- `gradle :app:testDebugUnitTest :app:lintDebug` 成功
- APK: `releases/rdpsync-real-rdp-1.0.0.apk`

注意：当前 APK 为 arm64-v8a，最低 Android 版本 API 26。
