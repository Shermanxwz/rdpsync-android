# Contributing to RdpSync

感谢参与 RdpSync。当前项目重点是 Android RDP 连接体验和 WebDAV 设备列表同步。

## 开发环境

- JDK 21
- Android SDK 35
- Android NDK 28.0.13004108
- Gradle 8.13
- Rust + `aarch64-linux-android` target

## 构建验证

提交前请至少运行：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK=/opt/android-sdk/ndk/28.0.13004108
export NDK_HOME=$ANDROID_NDK
export CC_aarch64_linux_android=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
export CXX_aarch64_linux_android=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++
export AR_aarch64_linux_android=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$CC_aarch64_linux_android

cd app/src/main/jni/rdpsync-connector
cargo check --target aarch64-linux-android
cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/librdpsync.so ../../../jniLibs/arm64-v8a/librdpsync.so
cd ../../../../..
./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## RDP 调试原则

- 先用 FreeRDP `/auth-only` 交叉验证账号、NLA/CredSSP 和服务端安全策略。
- 强制 NLA 的 Windows Server 需要严格 HYBRID/HYBRID_EX：CredSSP 开启时不要同时请求 TLS 图形登录降级。
- 发布前检查 APK 内 native ABI，不要提交伪 ABI。

## WebDAV 调试原则

- 设置页必须先通过“测试连接”。
- WebDAV 目录 URL 不是文件 URL；如果误填 `rdpsync_devices.json`，代码会自动转为父目录。
- 智能合并不能吞掉 WebDAV 错误，避免误覆盖云端。
