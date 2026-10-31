# RdpSync

Android RDP remote desktop client with WebDAV device synchronization and mobile-first touch controls.

RdpSync is an open Android RDP client that combines remote desktop connections, WebDAV profile portability and mobile touch controls.

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org)
[![FreeRDP](https://img.shields.io/badge/FreeRDP-3.x-orange)](https://www.freerdp.com)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

## Preview 3: Shared space and refreshed UI (development preview)

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

The delivery package is **RdpSync Preview 3** (`com.rdp.sync.preview3`), using a
development signer. It installs alongside Preview 2; it does not overwrite or
migrate its profiles/files, and no uninstall is required. Persistent signing-key
setup was declined for this iteration. Future differently signed development
builds cannot be promised to update the same package in place. The published
[Preview 2 release](https://github.com/Shermanxwz/rdpsync-android/releases/tag/v1.1.0-preview.2)
is preserved unchanged. No physical Windows-server/phone or Windows App parity
claim is made by the new fixture plan.

## Published Preview 2 baseline: display, input and compatibility

The GNOME/software-GFX, default-fullscreen and explicit file-transfer follow-up below is **not included in the existing `v1.1.0-preview` APK**. Its exact-commit runtime checks are tracked separately in [verification notes](docs/VERIFICATION.md).

The preview starts every session in **direct-touch, fullscreen mode**. The small **会话菜单** handle opens keyboard, disconnect, input and display controls; choose **显示工具栏** for bordered tools and **进入全屏** to return. Android system bars can be revealed with an edge swipe. Fullscreen is a fresh-session default, not a saved override. Touchpad mode remains a temporary session toggle. Display settings offer **fit window**, saved profile dimensions, 1280×720 and 1920×1080, with economy/balanced/sharp quality ceilings and opt-in adaptive quality.

- Live resolution changes use the RDP Display Control channel, after server capability negotiation. The displayed size is the **actual received framebuffer**, not a stretched approximation. Unsupported or unacknowledged requests retain the current session and offer an explicit reconnect.
- Fit-window sizing preserves aspect ratio and safely bounds memory; rotation/window changes are debounced. Opening the Android keyboard does not request a remote desktop resize.
- Adaptive mode uses genuine server-reported RTT/bandwidth plus local bitmap-copy/draw-submission timings. It uses sustained samples and asymmetric cooldowns; idle, absent and stale network reports are not guessed from Wi-Fi/cellular or low frame rate. Some servers do not report network metrics.
- Quality changes adjust **remote pixel count and local presentation FPS**, not an invented live codec/colour-depth setting. Fixed manual resolutions remain exact when adaptive mode is off.
- Normal connections include the software RDP Graphics Pipeline (RDPGFX), including RemoteFX Progressive, for servers such as GNOME Remote Desktop that require it. Compatibility mode remains classic GDI; no H.264 capability is advertised without a decoder.
- Native rendering unions all damage until consumed, respects both row strides, and removes redundant channel conversions. A lifecycle-bound Android View polls on vsync without per-frame Compose recomposition.
- Input is an ordered, bounded worker stream with held mouse buttons, extended keys, IME composition and cancellation/release handling.
- New/changed TLS certificates stop before login and require explicit fingerprint trust. Saved passwords migrate to device-bound Android Keystore AES-GCM; unavailable credentials remain visible and require deliberate re-entry.
- Default WebDAV sync uses password-free `rdpsync_profiles.json`; explicit legacy sync uses the separate secret-bearing `rdpsync_devices.json`, without silently overwriting old backups.

**Previous release:** The published Preview 2 APK is `RdpSync Preview 2` (`com.rdp.sync.preview2`), signed with a development key. It installs alongside both the original app and the previously released `com.rdp.sync.preview`; it does **not** overwrite or automatically migrate the original app's saved profiles. Do not uninstall the original app to work around a signature mismatch. Production package identity and signing are unchanged. Development keys can differ between CI runs. The separate Preview 2 identity avoids replacing the old preview, but future Preview 2 APKs are not guaranteed to update each other unless their signing certificates match. Do not uninstall either app as a workaround; preserve its saved profiles and credentials.

**Important limits:** this is not a benchmark claim of Microsoft Windows App equivalence. H.264/hardware video decoding is not enabled; RDPGFX uses software decoding rather than a zero-copy video surface; high-motion/video workloads and actual phone radio/touch/GPU behaviour still require physical-device validation. Server cursor shapes remain a synthetic cursor. GNOME per-user desktop sharing/headless sessions and GDM system Remote Login are distinct: server redirection remains blocked rather than forwarding credentials to an untrusted target. First-use certificate trust requires independent fingerprint verification; local passwords use Android Keystore encryption, while explicitly selected legacy WebDAV sync still contains plaintext passwords and is not end-to-end encrypted. See [SECURITY.md](SECURITY.md), [PRIVACY.md](PRIVACY.md), and [verification notes](docs/VERIFICATION.md).

## Highlights

- RDP desktop connections powered by FreeRDP through a small JNI bridge.
- WebDAV synchronization for saved device profiles across Android devices.
- Mobile-oriented pointer mode and direct-touch mode.
- RDPEI native touch input is attempted first in direct-touch drags, with wheel/HWheel fallback if the channel is unavailable.
- Smooth touch scrolling with wheel batching and fling decay.
- Android `View` based renderer paced by Android vsync.
- Reusable bitmap/frame buffer path to reduce allocations during long sessions.
- Native dirty-rectangle tracking and `frameId` de-duplication to avoid unnecessary redraws.
- Server-negotiated dynamic remote resolution, with honest capability fallback and optional measured adaptive quality.
- No analytics SDK, telemetry SDK, advertising SDK, or bundled third-party tracking.

## Use Cases

- Keep the same RDP server list on several Android phones or tablets.
- Connect to Windows desktops, Windows Server hosts, or compatible xrdp endpoints from a touch screen.
- Use a self-hosted WebDAV service such as Nextcloud, Alist, CloudDrive2, or another standards-compatible provider for profile backup.
- Test or operate remote Windows tools from a mobile device without relying on a proprietary profile sync service.

## Feature Overview

### RDP Connection

- Host, port, username, password, domain, server name, and resolution fields.
- CredSSP/NLA oriented connection path through FreeRDP.
- Mandatory TLS with explicit per-profile endpoint-bound SHA-256 certificate trust; changed certificates are rejected until deliberately approved.
- Screen keep-awake behavior during an active remote desktop session.
- Hardware accelerated Android activity rendering.

### Mobile Input

- Pointer mode for trackpad-like cursor control.
- Direct-touch mode for touch-to-coordinate interaction.
- RDPEI native touch down/move/up/cancel is used first for direct-touch drags when the server negotiates the channel.
- Unicode text input support.
- Axis-aware scroll handling and 60 fps wheel event scheduling remain as fallback for servers or builds without RDPEI.
- Fling velocity smoothing to avoid overly aggressive remote scrolling.

### Rendering

- FreeRDP classic GDI and software RDPGFX render into the same native primary buffer.
- Graphics surface/cache allocations and output mappings are bounded before delegated decoding; diagnostic codec counters reflect successfully processed commands, not only a requested capability.
- JNI copies RGBA dirty rows directly into an Android-compatible ARGB_8888 bitmap.
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

### Explicit File Transfer (Preview 2)

- Open **会话菜单 → 发送文件 / 接收文件**. File transfer is off by default. Its first activation requires an explicit reconnect confirmation because the file-only clipboard channel is negotiated at connection start. This is not automatic text clipboard synchronization or phone-drive redirection.
- **Send:** select regular files using Android's system picker, stage private snapshots, then explicitly choose **提供给远程剪贴板**. Paste in the remote file manager. Serving bytes is not proof that the remote application saved them; confirm the remote files yourself.
- **Receive:** copy files in the remote file manager, choose **读取远程文件列表**, then confirm receipt. Contents are downloaded only after confirmation. Export each complete file by selecting a destination directory; the app creates a new name and does not overwrite existing files.
- The initial send allowance is **32 MiB cumulatively per RDP connection**, including repeated range requests. Remaining allowance is shown before publishing; cancelling or replacing an offer does not reset it. Reconnecting is always an explicit choice and withdraws the old offer. On servers without clipboard snapshot locking, stopping an offer works immediately, but publishing a subsequent or replacement file list requires a manual reconnect; the app explains this instead of endlessly retrying or exposing a new list through stale requests. Incoming files are limited to 128 MiB each, 64 files and 256 MiB total private staging, with a 64 MiB free-space reserve. These are safety limits, not Windows App large-file parity.
- Only ordinary files are supported; folders, shortcuts and virtual documents are rejected. Closing the file dialog does not withdraw an explicitly published snapshot; **停止共享**, leaving the session or disconnecting does. A recipient may already have copied bytes, which cannot be recalled.
- Support depends on the server and remote file manager. Unsupported file-clipboard capability is reported; there is no fallback that exposes a phone directory. Actual GNOME, xrdp and Windows results must be distinguished in the [verification notes](docs/VERIFICATION.md).

## Architecture

| Area | Implementation |
| --- | --- |
| UI | Jetpack Compose screens plus `RdpDesktopView` for the live desktop |
| State | ViewModel, Kotlin Flow, Compose state |
| Local data | Room database for device profiles, SharedPreferences for WebDAV settings |
| Sync | OkHttp WebDAV client, JSON payload format |
| RDP engine | FreeRDP 3.x native libraries |
| Native bridge | C/JNI bridge in `app/src/main/cpp/freerdp_bridge.c` |
| Crypto/TLS dependency | OpenSSL built for Android and linked with FreeRDP |
| Build | Gradle, Android Gradle Plugin, CMake, Android NDK |

### Data Flow

```text
Compose screens
  -> DeviceViewModel
  -> Room / SharedPreferences / SyncManager
  -> WebDavSyncService
  -> WebDAV collection / rdpsync_profiles.json (or explicit legacy rdpsync_devices.json)
```

```text
RdpConnectionScreen
  -> RdpConnector Kotlin wrapper
  -> JNI native bridge
  -> FreeRDP instance and GDI callbacks
  -> native frame buffer + dirty rectangle metadata
  -> RdpDesktopView bitmap copy and canvas draw
```

### Native Render Loop

```text
FreeRDP EndPaint callback
  -> read GDI invalid region
  -> copy valid RGBA dirty rows using the GDI stride
  -> union pending dirty regions and increment frameId
  -> Kotlin checks frameId
  -> copy frame into reusable Bitmap
  -> Android View draws on the next invalidation
```

## Scope

RdpSync combines open-source Android RDP with WebDAV profile portability. The preview improves correctness and interaction in that scope; it does not claim every feature or performance characteristic of mature commercial clients. Android emulator tests exercise real isolated RDP sessions, but are not physical-phone or Windows-server certification.

## Privacy Model

RdpSync is designed to avoid project-owned cloud services.

- No analytics SDK or uploaded telemetry is included. Local session counters are used for diagnostics and optional adaptive quality.
- No advertising SDK is included.
- RDP passwords are stored locally using device-bound Android Keystore authenticated encryption.
- The saved WebDAV password uses the same encrypted storage; endpoint and account metadata are not encrypted.
- When WebDAV sync is used, device profiles are uploaded to the WebDAV server selected by the user.
- Default WebDAV JSON includes connection metadata but omits passwords. Explicit legacy sync includes plaintext passwords.
- Use trusted HTTPS WebDAV endpoints. Password-inclusive sync is not end-to-end encrypted; old secret-bearing files are not automatically removed.

For more detail, see [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## Quick Start

### Download

Get the latest APK from the project's GitHub Releases page.

- The existing [Preview 2 release](https://github.com/Shermanxwz/rdpsync-android/releases/tag/v1.1.0-preview.2) remains unchanged; its APK does not include Preview 3 changes.
- Preview 3 (`1.2.0-preview.3-debug`, `com.rdp.sync.preview3`) is a candidate, not yet a published release. Publication awaits all exact-commit acceptance gates; no candidate download is promised here.
- Minimum Android version: Android 8.0, API 26
- Current packaged ABI target: `arm64-v8a`

### Basic Usage

1. Add a device profile with host, port, credentials, optional domain, and resolution.
2. Open the device and start an RDP session.
3. Switch between pointer mode and direct-touch mode from the connection screen.
4. Optionally configure WebDAV sync and use upload, download, or merge.

### Build Requirements

- JDK 21 or compatible JDK for the Android build.
- Android SDK with API 35.
- Android NDK installed locally.
- CMake and Ninja through the Android SDK tooling.
- FreeRDP and OpenSSL Android native libraries built before APK packaging.

### Build Commands

```bash
git clone <repository-url>
cd rdpsync-android

export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
bash scripts/build-apk.sh
```

`scripts/build-apk.sh` builds pinned FreeRDP 3.32.2 and OpenSSL 3.5.9 LTS from verified source, then tests/lints/packages a side-by-side preview. It uses NDK 28.0.13004108 and places generated `.so` files under `app/src/main/jniLibs/arm64-v8a/`. Override `ANDROID_NDK` if needed. The `ABI=x86_64` native build and Gradle `-PnativeAbis=x86_64` are used only for emulator testing. These native outputs and APK artifacts are intentionally ignored by Git.

## Project Layout

```text
.
├── app/src/main/cpp/
│   ├── freerdp_bridge.c          # FreeRDP lifecycle, JNI calls, frame buffer bridge
│   ├── freerdp_client_stub.c     # Native client stub symbols
│   └── CMakeLists.txt
├── app/src/main/java/com/rdp/sync/
│   ├── data/                     # Room entity and DAO
│   ├── database/                 # Room database singleton
│   ├── manager/                  # Sync orchestration and merge logic
│   ├── network/                  # RDP wrapper and WebDAV service
│   ├── repository/               # Device repository
│   ├── ui/                       # Compose screens, navigation, theme, RDP view
│   └── viewmodel/                # DeviceViewModel and UI state
├── scripts/                      # Native dependency and APK build scripts
├── assets/                       # Source icon asset
├── CHANGELOG.md
├── CONTRIBUTING.md
├── PRIVACY.md
└── SECURITY.md
```

## Roadmap

- Multi-ABI release builds.
- Dedicated non-debug release signing key.
- Import/export format documentation.
- Broader physical-device, Windows-server, codec and enterprise identity validation.

## Contributing

Issues and pull requests are welcome. Keep reports free of private server addresses, production credentials, personal tokens, or screenshots containing sensitive desktop content. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.

## License

MIT License. See [LICENSE](LICENSE).

## Acknowledgements

- [FreeRDP](https://www.freerdp.com) for the RDP implementation.
- [OpenSSL](https://www.openssl.org) for TLS and cryptographic primitives used by the native stack.
- [OkHttp](https://square.github.io/okhttp/) for WebDAV HTTP requests.
- Android Jetpack libraries including Compose, Room, and lifecycle components.
