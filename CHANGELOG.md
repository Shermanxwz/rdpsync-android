# Changelog

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
