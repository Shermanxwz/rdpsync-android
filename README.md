# RdpSync

Android RDP remote desktop client with WebDAV device synchronization and mobile-first touch controls.

RdpSync focuses on a very specific gap: an open Android RDP client that can keep RDP device profiles in sync through a generic WebDAV endpoint. In practice, it is one of the few, and currently known to be the only open mobile RDP app in this niche that combines RDP connectivity, WebDAV profile sync, and touch/scrolling optimizations in one project.

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org)
[![FreeRDP](https://img.shields.io/badge/FreeRDP-3.x-orange)](https://www.freerdp.com)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

## Highlights

- RDP desktop connections powered by FreeRDP through a small JNI bridge.
- WebDAV synchronization for saved device profiles across Android devices.
- Mobile-oriented pointer mode and direct-touch mode.
- RDPEI native touch input is attempted first in direct-touch drags, with wheel/HWheel fallback if the channel is unavailable.
- Smooth touch scrolling with wheel batching and fling decay.
- Android `View` based renderer paced by Android vsync.
- Reusable bitmap/frame buffer path to reduce allocations during long sessions.
- Native dirty-rectangle tracking and `frameId` de-duplication to avoid unnecessary redraws.
- Adaptive remote resolution for phone and foldable screen ratios.
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
- Certificate acceptance mode suitable for lab/self-hosted RDP endpoints.
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

- FreeRDP GDI renders into a native primary buffer.
- JNI copies the remote frame into an Android-compatible ARGB buffer.
- A single reusable Android `Bitmap` is retained across frames when size is stable.
- `frameId` only advances when the native side has a changed frame.
- Dirty rectangles are tracked by the native bridge and used to reduce pixel copying work.
- `RdpBitmapView` draws the reusable bitmap through Android `Canvas` invalidation.

### WebDAV Sync

- The app serializes saved device profiles into `rdpsync_devices.json`.
- WebDAV upload, download, merge, and connection-test flows are implemented with OkHttp.
- The connection test validates collection creation and read/write/delete behavior.
- URLs are normalized so a mistakenly entered file URL can be treated as its parent collection.
- Merge uses a stable device key based on host, port, username, and domain.

## Architecture

| Area | Implementation |
| --- | --- |
| UI | Jetpack Compose screens plus `RdpBitmapView` for the live desktop |
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
  -> WebDAV collection / rdpsync_devices.json
```

```text
RdpConnectionScreen
  -> RdpConnector Kotlin wrapper
  -> JNI native bridge
  -> FreeRDP instance and GDI callbacks
  -> native frame buffer + dirty rectangle metadata
  -> RdpBitmapView bitmap copy and canvas draw
```

### Native Render Loop

```text
FreeRDP EndPaint callback
  -> read GDI invalid region
  -> convert RGBX32 pixels to Android ARGB
  -> store dirty rectangle and increment frameId
  -> Kotlin checks frameId
  -> copy frame into reusable Bitmap
  -> Android View draws on the next invalidation
```

## Comparison

| Capability | RdpSync | Microsoft RD Client | aFreeRDP | bVNC | 1Remote |
| --- | --- | --- | --- | --- | --- |
| Android RDP client | Yes | Yes | Yes | No, VNC focused | No, desktop focused |
| Open source | Yes | No | Yes | Yes | Yes |
| WebDAV profile sync | Yes | No | No | No | No |
| Self-hosted sync backend | Yes | No | No | No | No |
| Pointer-style mobile control | Yes | Yes | Yes | Not RDP | No |
| Direct touch mode | Yes | Yes | Limited | Not RDP | No |
| Dirty-region render path | Yes | Not documented | Varies | Not RDP | No |
| Mobile-first resolution handling | Yes | Yes | Limited | Limited | No |

This table is a product-level comparison, not a benchmark claim. RdpSync's strongest advantage is not that it replaces every mature commercial client, but that it combines open-source Android RDP access with WebDAV-based device profile portability.

## Privacy Model

RdpSync is designed to avoid project-owned cloud services.

- No analytics or telemetry code is included.
- No advertising SDK is included.
- RDP credentials are stored locally because they are required to connect.
- WebDAV credentials are stored locally because they are required to sync.
- When WebDAV sync is used, device profiles are uploaded to the WebDAV server selected by the user.
- The WebDAV JSON currently includes device connection fields, including RDP username and password.
- Use HTTPS WebDAV endpoints and avoid syncing high-value production credentials until an encrypted credential store is implemented.

For more detail, see [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## Quick Start

### Download

Get the latest APK from the project's GitHub Releases page.

- Current app version: `1.0.11`
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

bash scripts/build-openssl-android.sh
bash scripts/build-freerdp-android.sh
bash scripts/build-apk.sh
```

`scripts/build-apk.sh` expects the native FreeRDP/OpenSSL outputs under `build/freerdp-android/` and places generated `.so` files under `app/src/main/jniLibs/arm64-v8a/`. These native outputs and APK artifacts are intentionally ignored by Git.

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

- Encrypted credential storage using Android Keystore backed keys.
- Optional redaction or exclusion of RDP passwords from WebDAV sync payloads.
- Multi-ABI release builds.
- Dedicated non-debug release signing key.
- Better certificate verification and trust-on-first-use UX.
- Import/export format documentation.
- More automated tests around sync merge behavior and WebDAV edge cases.

## Contributing

Issues and pull requests are welcome. Keep reports free of private server addresses, production credentials, personal tokens, or screenshots containing sensitive desktop content. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.

## License

MIT License. See [LICENSE](LICENSE).

## Acknowledgements

- [FreeRDP](https://www.freerdp.com) for the RDP implementation.
- [OpenSSL](https://www.openssl.org) for TLS and cryptographic primitives used by the native stack.
- [OkHttp](https://square.github.io/okhttp/) for WebDAV HTTP requests.
- Android Jetpack libraries including Compose, Room, and lifecycle components.
