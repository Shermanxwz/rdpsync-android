# Changelog

## 1.3.3 - 2026-11-16

- Fixed the root causes of Ubuntu graphical desktop direct Chinese IME input and `"发送文字"` (`Send text`) pasting stale clipboard contents or escaped hex literals:
  - Enabled `CLIPRDR_FLAG_LOCAL_TO_REMOTE` (`0x00000001`) in `FreeRDP_ClipboardFeatureMask` while keeping `CLIPRDR_FLAG_REMOTE_TO_LOCAL = 0`, preventing FreeRDP 3.32.2's `cliprdr_filter_format_list` from stripping outbound `CF_UNICODETEXT (13)` formats.
  - Added dual-engine automatic Unix backend routing (`rdpsync_transfer_is_xrdp_peer` + `OSMAJORTYPE_UNIX`): on **Ubuntu GNOME Remote Desktop 46 (Wayland)** (where WinPR 3.32.2's `winpr_utf8ToUtfEscapedString` escapes `CF_UNICODETEXT` into literal `\uXXXX` text), direct Chinese IME commits and `"发送文字"` automatically stream via 2ms-paced ISO-14755 (`Ctrl+Shift+U <hex> Space`) with zero remote clipboard touch; on **Ubuntu `xrdp` (X11)** and **Windows**, outbound `CF_UNICODETEXT (13)` strictly awaits `CB_FORMAT_LIST_RESPONSE(CB_RESPONSE_OK)` + selection-ownership settle before `Ctrl+V` (never firing blind `Ctrl+V` onto stale clipboard data).
- Removed manual `"直输:自动"` input-mode selectors from `MagneticEdgeHud` and `SessionImeQuickBar` (and purged legacy `linux_ime_mode` overrides on upgrade), making Windows, Ubuntu GNOME Wayland, and Ubuntu `xrdp` X11 text input 100% zero-configuration automatic detection while promoting `"发送文字"` (`Send text`) directly into the primary 3×2 Bento grid.
- Added an unmodified FreeRDP 3.32.2 `cliprdr_filter_format_list` native C sanitizer test (`ubuntu_cliprdr_filter_test`) and live end-to-end Android 15 emulator -> real Ubuntu 24.04 **GNOME Remote Desktop 46 (Wayland)** + real Ubuntu 24.04 **`xrdp` (X11)** graphical desktop verification (`UbuntuLiveChineseImeInstrumentedTest`) with pre-armed stale clipboard traps.

## 1.3.2 - 2026-11-15

- Fixed missing Android home screen launcher icon after installation by correcting `<category android:name="android.intent.category.LAUNCHER" />` and explicit `android:enabled="true"` on `.ui.MainActivity` in `AndroidManifest.xml`.
- Configured a persistent official production release signing keystore (`CN=RDPsync Official`, `SHA-256: 68:73:44:CE:FB:4A:91:9C:65:DE:9C:09:5E:D4:A9:39:5C:F3:9D:D8:78:22:F2:5B:73:B0:2F:75:24:DD:F9:18`, V1+V2+V3 schemes) via GitHub Actions Secrets so `RDPsync-v1.3.2-arm64-v8a.apk` (`com.rdp.sync`, `versionCode = 19`) and all future releases support direct in-place upgrade without `Android Debug` signature conflicts or OEM risk quarantines.
- Added Android 8–15 (`API 26+`) vector Adaptive Launcher Icons (`mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`) plus Android 13–15 Material You monochrome icon layers.
- Hardened `scripts/verify-apk.py` and `scripts/test-product-ui.py` to enforce `aapt` `launchable-activity`, packaged adaptive icon resources, non-debuggable release packaging, and persistent official certificate fingerprint verification in CI.

## 1.3.1 - 2026-11-15

- Fixed IPv4 post-connect session disconnects (`frame=0x0`) on Windows RDP hosts by disabling unnegotiated byte-buffer `GfxH264` defaults while retaining `GfxSmallCache`, enabling TCP KeepAlive (`15s / 5s / 3`), accepting Windows `rdpclip.exe` `CB_TEMP_DIRECTORY (0x0006)` PDUs, and making text-only `CLIPRDR` resilient to nonstandard remote clipboard frames without tearing down the session.
- Fixed Ubuntu (`xrdp` X11 and GNOME Remote Desktop Wayland) direct mobile Chinese/CJK IME input and `"发送文字"` (`Send Text`): added `xrdp` 4-byte zero-trailer peer fingerprinting alongside FreeRDP `CAPSTYPE_GENERAL` `OSMAJORTYPE_UNIX` detection, pure-text `CB_FORMAT_LIST` (`CF_UNICODETEXT` / `UTF8_STRING` without `FileGroupDescriptorW` interference), X11 selection-ownership settle timing, and universal clipboard-paste bulk text delivery so neither Chinese nor English triggers remote Pinyin candidate bars.
- Replaced the private app-confined `Shared space` and redundant file-transfer menus with Microsoft Windows App–style direct phone storage redirection (`Android 上的 RdpSync` / `RdpSync on Android`): once Android file access (`MANAGE_EXTERNAL_STORAGE` / storage runtime permission) is granted, remote desktops directly read and write real phone folders (`Download`, `DCIM`, `Documents`, `Pictures`, etc.).

## 1.3.0 - 2026-11-15

- Switched back to the official package identity `com.rdp.sync` (`versionCode = 17`, `versionName = "1.3.0"`), producing verified non-debuggable release APKs alongside debug instrumentation APKs.
- Replaced the bottom-right floating session menu button with `MagneticEdgeHud` (auto-tucking translucent edge capsule capped at `MAX_FRACTION = 0.78f`) and `SessionImeQuickBar`, eliminating occlusion over the remote desktop's bottom-right taskbar, input indicator, and system tray; refined `DeviceListScreen` spatial layout.
- Enabled FreeRDP 3.32.2 Android NDK `MediaCodec` H.264 hardware decoding (`-DWITH_MEDIACODEC=ON`, backporting upstream FreeRDP PR #13706 weak-symbol availability guards for `minSdk 26`), runtime `video/avc` hardware decoder detection, 64-bit aligned scanline batch copying, adaptive high-refresh `FramePacer` (`60/90/120/144Hz`), and sub-pixel trackpad ballistics.
- Added `rdpsync_ubuntu_ime.h` and `LinuxTextInputMode` to deliver direct mobile Chinese/CJK IME commit parity on Ubuntu (`xrdp` / GNOME Remote Desktop) via isolated text-only outbound `CLIPRDR` (`CF_UNICODETEXT` / `UTF8_STRING` + atomic paste) with automatic zero-channel ISO-14755 (`Ctrl+Shift+U`) fallback, strictly isolated from file-transfer consent.

## 1.2.0-preview.4

- Add a session-menu “Rotate screen” action to switch phone portrait/landscape without restarting the session; restore the prior orientation policy when leaving.
- Use `RDPsync` consistently for the launcher and app title.
- Publish as a separate development-signed `.preview4` package; earlier preview installs and data are not overwritten or automatically migrated.

## 1.2.0 Preview 3 — implementation and release scope

- Added an opt-in RDPDR filesystem for the persistent app-private Shared space, with read-only default and separate read/write consent. The app and remote peer access the same confined workspace; explicit Android file-picker import/export copies do not synchronize phone folders.
- Added app-side browsing, create/rename/delete, bounded storage and remote backpressure, conflicting-handle errors, revocation and reconnect persistence. Remote access never includes other app files or arbitrary phone directories.
- Refreshed connection screens, forms, settings and sharing dialogs with coherent English/Chinese, light/dark design and accessible interaction targets, preserving fullscreen desktop and IME behavior.
- Kept clipboard file transfer as a separate negotiated fallback, with honest unconfirmed/unsupported/rejected/accepted drive states.
- Uses a distinct development-signed `com.rdp.sync.preview3` package, versionCode 15. Preview 2 and its data remain untouched; no automatic migration or future signer compatibility is promised.
- Acceptance is commit-specific. See `docs/PRODUCT_UI_ACCEPTANCE.md`, `docs/SHARED_DRIVE_DESIGN.md` and `docs/VERIFICATION.md`; candidate descriptions are not a claim of completed Windows, physical-device or final runtime certification.

## 1.1.0 previews — historical implementation notes

- Touch-first sessions with an explicit temporary touchpad toggle; preserved device profiles with transactional credential encryption migration.
- Real Display Control negotiation with requested/actual resolution tracking, aspect-preserving window sizing, profile/HD/FHD choices, timeout/unsupported fallback and deliberate reconnect.
- Optional measured adaptive quality with hysteresis, freshness checks and no idle/no-sample downgrade; local vsync presentation without Compose frame churn.
- Dirty-region union/stride/pixel correctness, safe same-area resize, owner-thread native input and teardown, bounded ordered input, held mouse/extended keys and IME composition.
- Pinned FreeRDP 3.32.2 (includes reachable DRDYNVC allocation security fix) and OpenSSL 3.5.9 LTS; removed invalid legacy OpenSSL MD4 callback casts.
- App-private HOME/cache initialization required by current FreeRDP on Android, with a real context-allocation instrumentation gate and sanitized startup diagnostics.
- Explicit visible adaptive cause distinguishes local render pressure from network measurements; sync error boundaries prevent malformed legacy password payloads from leaking through diagnostics.
- Reproducible native APK CI, native production-source sanitizer fixtures, Kotlin regressions, and standard hosted Android emulator plus isolated xrdp fixture.
- Mandatory TLS certificate pinning with explicit first-use/changed-certificate approval before login; stale trust decisions rejected.
- Android Keystore AES-GCM local password storage, non-destructive legacy migration and visible key-loss recovery. Default password-free WebDAV file separate from explicit legacy/password-inclusive sync.
- Side-by-side development-signed `com.rdp.sync.preview` package; later Preview 2 uses `com.rdp.sync.preview2`. Published historical APKs remain unchanged. No production signing key or app-store deployment.

Known limitations: first-use fingerprints need independent verification; explicitly selected legacy WebDAV sync contains plaintext passwords (not end-to-end encryption); no H.264/RDPGFX acceleration or physical-device parity claim. See docs/VERIFICATION.md.

## 1.0.11 - 2026-07-01

### Fixed

- Removed the public debug APK from the latest GitHub release so users only see the release artifact.
- Bumped the app version after the `v1.0.10` tag/release-asset mismatch.
- Removed RDP host and username values from native connection logs and startup diagnostics.

### Documentation

- Updated README version and renderer wording to match the current Android `View` bitmap renderer.
- Added clearer release hygiene notes so tags, APK assets, and release notes should come from the same commit.

## 1.0.9 - 2026-07-01

### Added

- Enabled the Android FreeRDP client channel build path for RDPEI native touch input (`WITH_CHANNELS=ON`, `CHANNEL_RDPEI_CLIENT=ON`, DRDYNVC client channel enabled) while keeping X11/Wayland/SDL/server components disabled.
- Added a JNI `nativeSendTouchEvent(pointerId, eventType, x, y)` path and Kotlin `sendTouchEvent(...)` wrapper for direct-touch down/move/up/cancel events.
- Direct-touch drag now attempts RDPEI first, so horizontal drag/scroll gestures can use native touch instead of relying only on HWheel simulation.

### Fallback

- If RDPEI is not compiled, the server does not negotiate the RDPEI dynamic channel, the channel disconnects, or sending a touch event fails, direct-touch falls back to the existing wheel/HWheel path and pointer/mouse fallback without crashing.

## 1.0.8 - 2026-07-01

### Improved

- Added real native frame-loop pacing so the FreeRDP check loop no longer hot-spins between socket checks.
- Added direct native dirty-region copies into the Android `Bitmap` with `AndroidBitmap_lockPixels`, removing the Java `IntArray` to `Bitmap.setPixels` hop from the render path.
- Moved RDP frame polling and bitmap updates off the Compose UI coroutine, leaving the main thread to receive completed frames and schedule invalidation.
- Guarded bitmap writes and draws with a shared frame lock to avoid tearing while keeping the expensive copy work off the UI thread.

## 1.0.7 - 2026-06-30

### Improved

- Reused the RDP frame `Bitmap` and pixel buffer across frames to reduce GC and long-session heat.
- Added native `frame_id` based frame de-duplication so static remote pages no longer redraw on every 16ms tick.
- Added FreeRDP GDI dirty-rectangle tracking and dirty-region pixel copy for lower CPU and memory bandwidth use.
- Replaced the Compose `Image` frame renderer with a custom Android `View` backed by `Canvas.drawBitmap`.
- Mapped remote dirty rectangles to screen-space partial invalidation for smoother Amazon backend/table workflows.

## 1.0.6 - 2026-06-29

### Improved

- Improved mobile touch scrolling smoothness with a stable 60fps wheel event scheduler.
- Reduced cursor jitter by removing redundant mouse-move events before every wheel frame.
- Added frame-rate independent inertial decay for more natural release momentum.
- Smoothed fling velocity estimation to avoid drag displacement and inertia stacking into overly fast scrolling.

### Notes

- RDPEI native touch input remains disabled in the shipped build because the current FreeRDP libraries are built with `WITH_CHANNELS=OFF` and `CHANNEL_RDPEI_CLIENT` unavailable. This release intentionally avoids unsafe `freerdp_client_*` references and keeps the stable wheel fallback path.

## 1.0.3 - 2026-06-27

### Fixed

- Fixed CredSSP/NLA failures against strict Windows RDP servers by disabling TLS graphical-login fallback when CredSSP is enabled.
- Verified against real RDP endpoint: connection test passed, first frame received.
- Cleaned temporary probe files from the Android native crate.

### Verified

- `./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- APK contains native .so libraries, classes.dex, AndroidManifest.xml, and launcher icons.

## 1.0.2

### Fixed

- Restored CredSSP/NLA authentication path for Windows servers that require NLA.
- Improved RDP error messages for TCP, CredSSP, and negotiation failures.

## 1.0.1

### Added

- WebDAV connection test with PROPFIND/MKCOL/PUT/GET/DELETE validation.
- App icon resources.
- GitHub Actions workflow and project documentation.

### Fixed

- WebDAV 405/409 handling and URL normalization.
- Mobile screen-size based RDP session resolution.
