# RdpSync

RdpSync 是一个面向 Android 手机/平板的 RDP 连接管理器：

- 管理 RDP 设备列表（名称、主机、端口、用户名、密码、域、桌面尺寸）
- 通过 WebDAV 在多设备之间同步 `rdpsync_devices.json`
- 移动端远程桌面操作界面：鼠标/触摸模式、点击/双击/长按、拖拽、双指缩放/平移、Ctrl+Alt+Del、剪贴板文本发送
- Kotlin + Jetpack Compose + Room + OkHttp + Rust JNI

## WebDAV 同步

1. 打开应用右上角“设置”。
2. 填入 WebDAV 地址、用户名、密码，例如：`https://example.com/dav/rdpsync`。
3. 点击顶部云同步按钮：
   - 智能合并：按 `host:port:username:domain` 去重后合并本地与云端列表。
   - 上传本机列表：用本机列表覆盖云端文件。
   - 下载云端列表：用云端文件覆盖本机列表。

同步文件固定为：`rdpsync_devices.json`。

## 构建

环境：JDK 21、Android SDK 35、Gradle 8.13、NDK 28。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH=/opt/gradle/gradle-8.13/bin:$PATH:$HOME/.cargo/bin
gradle :app:assembleDebug
```

JNI 库已放在 `app/src/main/jniLibs/arm64-v8a/librdpsync.so`。

## 已验证

- `gradle :app:assembleDebug` 成功
- `gradle :app:testDebugUnitTest :app:lintDebug` 成功
- APK: `releases/rdpsync-debug-1.0.0.apk`
