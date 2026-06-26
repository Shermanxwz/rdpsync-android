# RdpSync 1.0.1

本版本重点修复首轮实机反馈：RDP CredSSP 报错、WebDAV 405/409、手机分辨率、应用图标、GitHub 项目完整度。

## 修复

- RDP 默认切换为 TLS + 图形登录兼容模式，避开部分服务端 CredSSP/NLA 握手失败。
- RDP 连接时按手机当前屏幕像素请求远程会话尺寸，不修改远程机器原本物理分辨率。
- WebDAV 设置页增加“测试连接”。
- WebDAV 测试会自动：
  - 检查目录是否存在。
  - 尝试逐级 MKCOL 创建缺失目录。
  - PUT/GET/DELETE 临时测试文件，确认目录可读写。
- WebDAV 地址如果误填到 `rdpsync_devices.json` 文件，会自动归一化为父目录。
- 智能合并不再吞掉 WebDAV 权限/路径错误，避免错误时误覆盖云端。
- 新增完整应用图标，多密度 mipmap 均已生成。
- 增加 Gradle Wrapper、GitHub Actions、.gitignore、完整 README。

## APK

- 文件：`releases/rdpsync-real-rdp-1.0.1.apk`
- SHA256：`b697ee2369f033f95584ba9e49da4ff1d40a71a817ece5d8bbf5ab34f3330333`
- ABI：`arm64-v8a`
- 最低系统：Android 8.0 / API 26

## 验证

已运行通过：

```bash
cargo check --target aarch64-linux-android
cargo build --release --target aarch64-linux-android
./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 包含：

- `classes.dex`
- `AndroidManifest.xml`
- `lib/arm64-v8a/librdpsync.so`
- `ic_launcher` 多密度图标资源
