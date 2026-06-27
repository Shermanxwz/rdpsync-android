# RdpSync 1.0.3

本版本针对真实 RDP 机器 `161.118.228.26:22389` 完成 CredSSP/NLA 实测修复。

## 真实调试结论

- TCP `161.118.228.26:22389` 连通。
- FreeRDP `/sec:tls` 被服务端拒绝，服务端返回 `HYBRID_REQUIRED_BY_SERVER`。
- FreeRDP `/sec:nla` 能进入认证/激活阶段，说明该服务器强制 NLA/CredSSP。
- IronRDP probe 使用修复后的配置已连上并拿到首帧：`IMAGE 1280x720: connected`。

## 修复

- CredSSP/NLA 模式下关闭 TLS 图形登录降级：
  - `enable_credssp = true`
  - `enable_tls = false`
- 只协商严格 HYBRID/HYBRID_EX，适配强制 NLA 的 Windows Server。
- 保留 `DOMAIN\\user` 自动拆分为 domain + username。
- 保留中文可行动错误提示。
- 清理临时调试 probe，发布包只保留 Android JNI 库。

## APK

- 文件：`releases/rdpsync-real-rdp-1.0.3.apk`
- SHA256：`8ff3475e5e22810b3daf00ed25653ff9837a32bb4cf46e8f008e5059e615d44e`
- ABI：`arm64-v8a`
- 最低系统：Android 8.0 / API 26

## 验证

已运行：

```bash
cargo check --target aarch64-linux-android
cargo build --release --target aarch64-linux-android
./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
file app/src/main/jniLibs/*/*.so
jar tf releases/rdpsync-real-rdp-1.0.3.apk | grep -E 'lib/.*/librdpsync.so|classes.dex|AndroidManifest.xml|ic_launcher'
```
