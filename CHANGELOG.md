# Changelog

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
