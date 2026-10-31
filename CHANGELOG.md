# Changelog

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
