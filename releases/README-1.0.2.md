# RdpSync 1.0.2

本版本针对实机反馈的两个 RDP 错误继续修复：

1. `negotiation failure: server requires Enhanced RDP Security with CredSSP`
2. `TCP connect ... custom error`

## 修复

- 默认恢复为 Windows 标准 NLA/CredSSP 认证，适配强制 NLA 的 Windows 远程桌面。
- 客户端名改为短 NetBIOS 风格 `RDPSYNC`，降低服务端兼容问题。
- 支持自动拆分 `域\\用户`：如果域输入框为空、用户名里包含反斜杠，会自动把前半段作为域。
- 改善错误提示：
  - CredSSP/NLA 失败会提示检查用户名、密码、域。
  - TCP connect 失败会提示检查 IP/端口/防火墙/VPN/远程桌面开关。
  - 协商失败会提示服务端安全策略不匹配。

## APK

- 文件：`releases/rdpsync-real-rdp-1.0.2.apk`
- SHA256：`66f31ec4df3dbc7db6f13b0e7ed7076f1ef0fa52eaf54ee751d38b40daf20c58`
- ABI：`arm64-v8a`
- 最低系统：Android 8.0 / API 26

## 验证

已运行：

```bash
cargo check --target aarch64-linux-android
cargo build --release --target aarch64-linux-android
./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
