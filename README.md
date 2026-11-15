# RDPsync

Android RDP remote desktop client with WebDAV device synchronization and mobile-first touch controls.

RDPsync is an open Android RDP client that combines remote desktop connections, WebDAV profile portability and mobile touch controls.

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org)
[![FreeRDP](https://img.shields.io/badge/FreeRDP-3.x-orange)](https://www.freerdp.com)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

## Release 1.3.0: Official `com.rdp.sync` package, zero-occlusion HUD, MediaCodec H.264 & Ubuntu Chinese IME

- **Official Package & Version**: Restores the canonical package identity **`com.rdp.sync`** (`versionCode = 17`, `versionName = "1.3.0"`), shipping verified 16 KiB page-aligned `arm64-v8a` release APKs.
- **Zero Bottom-Right Occlusion (`MagneticEdgeHud`)**: Replaces the bottom-right floating session button with a translucent edge-magnetic capsule (`MAX_FRACTION = 0.78f`) that auto-tucks after idle and never blocks the remote desktop's bottom-right taskbar, input indicator, or system tray.
- **Hardware MediaCodec H.264 & High-Refresh Pacing**: Enables FreeRDP 3.32.2 Android NDK `MediaCodec` H.264 hardware decoding (`WITH_MEDIACODEC=ON` with API 26 weak-symbol availability guards), 64-bit aligned scanline batch copying, `FramePacer` adaptive `60/90/120/144Hz` vsync pacing, and sub-pixel 1:1 trackpad ballistics.
- **Ubuntu RDP Native-Equivalent Chinese IME**: Automatically detects non-Windows peers (`xrdp` / GNOME Remote Desktop) where `TS_UNICODE_KEYBOARD_EVENT` is ignored and delivers mobile Chinese/CJK commits via isolated text-only outbound `CLIPRDR` (`CF_UNICODETEXT` / `UTF8_STRING` + atomic paste) with automatic zero-channel ISO-14755 (`Ctrl+Shift+U`) fallback, strictly isolated from file-transfer consent.

## Preview 4: Phone rotation and RDPsync naming (historical notes)

Open the remote session menu and choose **Rotate screen / 旋转屏幕** to switch
between phone portrait and landscape without moving the handset. This is an
Android orientation request, not a rotated remote bitmap. The session keeps its
existing desktop view; leaving restores the prior orientation policy. Platform
or device restrictions (such as some multi-window modes) can refuse the request.
The launcher and app title now display **RDPsync** in both languages.

### Shared space and refreshed UI from Preview 3

The current development work adds a **real RDPDR filesystem drive**, not a renamed
clipboard transfer. The persistent, app-private **Shared space** is the only
directory exposed: remote and local file operations use the same confined broker.
Sharing starts **off**; consent applies to the explicitly confirmed target session
and defaults to **read-only**, with a separate warning and acknowledgment for
remote create/write/rename/delete access. Android SAF
imports and exports are deliberate copies, not automatic synchronization with
phone folders. Opening the Shared space browser alone grants no remote access.

- The workspace is not purged on disconnect; accepted delete-on-close requests
  can still complete as handles close. The app browser remains usable while
  connected; conflicts are reported rather than bypassing active file handles.
- Changing access requires an explicit reconnect. Stopping sharing revokes future
  access without purging the workspace or undoing accepted writes/deletions; a
  remote drive icon may remain visible but
  unavailable until the next connection.
- Enforced limits are 1 GiB workspace, 512 MiB per file, 4096 entries and a 128 MiB
  free-space reserve. The drive’s transport backpressure is separate from the
  existing clipboard transfer’s 32 MiB connection allowance.
- The current GNOME Remote Desktop 46 fixture leaves drive access at
  **REQUESTED / unconfirmed**: no mounted drive or explicit server rejection was
  confirmed. Unconfirmed, unsupported, rejected and actually accepted states stay
  distinct. Explicit **CLIPRDR clipboard file transfer** remains a separate
  fallback where negotiated; it does not establish filesystem-drive support.
- Main screens, forms, status/consent dialogs and navigation use shared accessible
  design tokens, English/Chinese resources, and light/dark layouts. The established
  fullscreen desktop, touch/IME mapping and certificate enforcement are retained.

**Verification is commit-specific**; feature descriptions alone are not acceptance. See the
[implementation contract](docs/SHARED_DRIVE_DESIGN.md) and
[verification notes](docs/VERIFICATION.md); definitions and host tests are not a
substitute for an actual mounted drive, stalled-peer recovery or Android UI tests.

The official release package is **RDPsync** (`com.rdp.sync`, `versionName = "1.3.0"`, `versionCode = 17`). Published historical releases ([Preview 4](https://github.com/Shermanxwz/rdpsync-android/releases/tag/v1.2.0-preview.4), [Preview 3](https://github.com/Shermanxwz/rdpsync-android/releases/tag/v1.2.0-preview.3), and earlier) remain preserved unchanged on the Releases page.

## Published Preview 2 baseline: display, input and compatibility

The GNOME/software-GFX, default-fullscreen and explicit file-transfer follow-up below is **not included in the existing `v1.1.0-preview` APK**. Its exact-commit runtime checks are tracked separately in [verification notes](docs/VERIFICATION.md).

The preview starts every session in **direct-touch, fullscreen mode**. The small **会话菜单** handle opens keyboard, disconnect, input and display controls; choose **显示工具栏** for bordered tools and **进入全屏** to return. Android system bars can be revealed with an edge swipe. Fullscreen is a fresh-session default, not a saved override. Touchpad mode remains a temporary session toggle. Display settings offer **fit window**, saved profile dimensions, 1280×720 and 1920×1080, with economy/balanced/sharp quality ceilings and opt-in adaptive quality.

- Live resolution changes use the RDP Display Control channel, after server capability negotiation. The displayed size is the **actual received framebuffer**, not a stretched approximation. Unsupported or unacknowledged requests retain the current session and offer an explicit reconnect.
- Fit-window sizing preserves aspect ratio and safely bounds memory; rotation/window changes are debounced. Opening the Android keyboard does not request a remote desktop resize.
- Adaptive mode uses genuine server-reported RTT/bandwidth plus local bitmap-copy/draw-submission timings. It uses sustained samples and asymmetric cooldowns; idle, absent and stale network reports are not guessed from Wi-Fi/cellular or low frame rate. Some servers do not report network metrics.
- Quality changes adjust **remote pixel count and local presentation FPS**, not an invented live codec/colour-depth setting. Fixed manual resolutions remain exact when adaptive mode is off.
- Normal connections include the RDP Graphics Pipeline (RDPGFX), including Android NDK `MediaCodec` H.264 hardware decoding when supported by the device/server and RemoteFX Progressive fallback. Compatibility mode remains classic GDI.
- Native rendering unions all damage until consumed, respects both row strides, and removes redundant channel conversions. A lifecycle-bound Android View polls on vsync without per-frame Compose recomposition.
- Input is an ordered, bounded worker stream with held mouse buttons, extended keys, IME composition and cancellation/release handling.
- New/changed TLS certificates stop before login and require explicit fingerprint trust. Saved passwords migrate to device-bound Android Keystore AES-GCM; unavailable credentials remain visible and require deliberate re-entry.
- Default WebDAV sync uses password-free `rdpsync_profiles.json`; explicit legacy sync uses the separate secret-bearing `rdpsync_devices.json`, without silently overwriting old backups.

## Highlights

- RDP desktop connections powered by FreeRDP through a small JNI bridge.
- Hardware-accelerated H.264 `MediaCodec` decoding on supported Android devices with software RDPGFX / GDI fallback.
- Direct mobile Chinese / CJK IME input parity across both Windows and Ubuntu (`xrdp` / GNOME Remote Desktop) hosts.
- Edge-magnetic `MagneticEdgeHud` session controls with zero bottom-right taskbar occlusion.
- WebDAV synchronization for saved device profiles across Android devices.
- Mobile-oriented pointer mode and direct-touch mode.
- RDPEI native touch input is attempted first in direct-touch drags, with wheel/HWheel fallback if the channel is unavailable.
- Smooth touch scrolling with wheel batching and fling decay.
- Android `View` based renderer paced by Android vsync (`60/90/120/144Hz`).
- Reusable bitmap/frame buffer path to reduce allocations during long sessions.
- Native dirty-rectangle tracking and `frameId` de-duplication to avoid unnecessary redraws.
- Server-negotiated dynamic remote resolution, with honest capability fallback and optional measured adaptive quality.
- No analytics SDK, telemetry SDK, advertising SDK, or bundled third-party tracking.

## Use Cases

- Keep the same RDP server list on several Android phones or tablets.
- Connect to Windows desktops, Windows Server hosts, or Ubuntu / `xrdp` / GNOME Remote Desktop endpoints from a touch screen.
- Use a self-hosted WebDAV service such as Nextcloud, Alist, CloudDrive2, or another standards-compatible provider for profile backup.
- Test or operate remote Windows and Linux tools from a mobile device without relying on a proprietary profile sync service.

## Feature Overview

### RDP Connection

- Host, port, username, password, domain, server name, and resolution fields.
- CredSSP/NLA oriented connection path through FreeRDP.
- Mandatory TLS with explicit per-profile endpoint-bound SHA-256 certificate trust; changed certificates are rejected until deliberately approved.
- Screen keep-awake behavior during an active remote desktop session.
- Hardware accelerated Android activity rendering.

### Mobile Input

- Pointer mode for trackpad-like cursor control with sub-pixel ballistics.
- Direct-touch mode for touch-to-coordinate interaction.
- RDPEI native touch down/move/up/cancel is used first for direct-touch drags when the server negotiates the channel.
- Unicode text input on Windows plus automatic outbound text-only `CLIPRDR` / ISO-14755 Chinese & CJK commit support on Ubuntu (`xrdp` / GNOME Remote Desktop).
- Axis-aware scroll handling and high-refresh wheel event scheduling remain as fallback for servers or builds without RDPEI.
- Fling velocity smoothing to avoid overly aggressive remote scrolling.

### Rendering

- FreeRDP classic GDI, `MediaCodec` H.264, and software RDPGFX render into the unified native primary buffer.
- Graphics surface/cache allocations and output mappings are bounded before delegated decoding; diagnostic codec counters reflect successfully processed commands, not only a requested capability.
- JNI copies 64-bit aligned RGBA dirty rows directly into an Android-compatible ARGB_8888 bitmap.
- A single reusable Android `Bitmap` is retained across frames when size is stable.
- `frameId` only advances when the native side has a changed frame.
- Dirty rectangles are tracked by the native bridge and used to reduce pixel copying work.
- `RdpDesktopView` draws the reusable bitmap through Android `Canvas` invalidation.

### WebDAV Sync

- Default profile-only sync uses `rdpsync_profiles.json`; explicit legacy/password-inclusive sync uses `rdpsync_devices.json`. These are independent files; the safe default never erases the legacy backup.
- WebDAV upload, download, merge, and connection-test flows are implemented with OkHttp.
- The connection test validates collection creation and read/write/delete behavior.
- URLs are normalized so a mistakenly entered file URL can be treated as its parent collection.
- Merge uses a stable connection/account key including host, port, username, domain and server name. Omitted passwords preserve matching local secrets; imports retain unmatched local profiles.

### Explicit File Transfer

- Open **会话菜单 → 发送文件 / 接收文件**. File transfer is off by default. Its first activation requires an explicit reconnect confirmation because the file-only clipboard channel is negotiated at connection start. This is strictly isolated from text IME clipboard commits and does not expose phone directories.
- **Send:** select regular files using Android's system picker, stage private snapshots, then explicitly choose **提供给远程剪贴板**. Paste in the remote file manager. Serving bytes is not proof that the remote application saved them; confirm the remote files yourself.
- **Receive:** copy files in the remote file manager, choose **读取远程文件列表**, then confirm receipt. Contents are downloaded only after confirmation. Export each complete file by selecting a destination directory; the app creates a new name and does not overwrite existing files.
- The initial send allowance is **32 MiB cumulatively per RDP connection**, including repeated range requests. Incoming files are limited to 128 MiB each, 64 files and 256 MiB total private staging, with a 64 MiB free-space reserve.
- Only ordinary files are supported; folders, shortcuts and virtual documents are rejected. Closing the file dialog does not withdraw an explicitly published snapshot; **停止共享**, leaving the session or disconnecting does.

## Architecture

| Area | Implementation |
| --- | --- |
| UI | Jetpack Compose screens plus `RdpDesktopView` & `MagneticEdgeHud` for the live desktop |
| State | ViewModel, Kotlin Flow, Compose state |
| Local data | Room database for device profiles, SharedPreferences for WebDAV settings |
| Sync | OkHttp WebDAV client, JSON payload format |
| RDP engine | FreeRDP 3.32.2 native libraries (`WITH_MEDIACODEC=ON`) |
| Native bridge | C/JNI bridge in `app/src/main/cpp/freerdp_bridge.c` + `rdpsync_ubuntu_ime.h` |
| Crypto/TLS dependency | OpenSSL 3.5.9 LTS built for Android and linked with FreeRDP |
| Build | Gradle, Android Gradle Plugin, CMake, Android NDK |

## Privacy Model

RdpSync is designed to avoid project-owned cloud services.

- No analytics SDK or uploaded telemetry is included. Local session counters are used for diagnostics and optional adaptive quality.
- No advertising SDK is included.
- RDP passwords are stored locally using device-bound Android Keystore authenticated encryption.
- The saved WebDAV password uses the same encrypted storage; endpoint and account metadata are not encrypted.
- Default WebDAV JSON includes connection metadata but omits passwords. Explicit legacy sync includes plaintext passwords.

For more detail, see [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## Quick Start

### Download

Get the latest `RDPsync-v1.3.0-arm64-v8a.apk` (`com.rdp.sync`, `versionCode 17`) from the project's [GitHub Releases](https://github.com/Shermanxwz/rdpsync-android/releases) page.

- Minimum Android version: Android 8.0, API 26
- Packaged ABI target: `arm64-v8a` (16 KiB ELF page-aligned)

### Build Commands

```bash
git clone https://github.com/Shermanxwz/rdpsync-android.git
cd rdpsync-android

export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
bash scripts/build-apk.sh
```

`scripts/build-apk.sh` builds pinned FreeRDP 3.32.2 and OpenSSL 3.5.9 LTS from verified source, runs unit tests and Lint, packages and verifies `RDPsync-v1.3.0-arm64-v8a.apk` (`com.rdp.sync`), and outputs its SHA-256 digest.

## Contributing

Issues and pull requests are welcome. Keep reports free of private server addresses, production credentials, personal tokens, or screenshots containing sensitive desktop content. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.

## License

MIT License. See [LICENSE](LICENSE).

## Acknowledgements

- [FreeRDP](https://www.freerdp.com) for the RDP implementation.
- [OpenSSL](https://www.openssl.org) for TLS and cryptographic primitives used by the native stack.
- [OkHttp](https://square.github.io/okhttp/) for WebDAV HTTP requests.
- Android Jetpack libraries including Compose, Room, and lifecycle components.
