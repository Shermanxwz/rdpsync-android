# Contributing to RdpSync

Thanks for helping improve RdpSync. The project is centered on Android RDP connectivity, mobile touch interaction, and WebDAV device profile synchronization.

## Ground Rules

- Do not commit real RDP hosts, usernames, passwords, WebDAV URLs, access tokens, private keys, personal contact details, or screenshots containing sensitive desktop content.
- Use placeholders such as `example.com`, `user`, `password`, and `<repository-url>` in documentation and examples.
- Keep source changes focused. Avoid unrelated formatting churn in files you are not changing.
- Prefer clear bug reports with reproducible steps, Android version, device model, server type, and app version.

## Development Environment

Recommended baseline:

- JDK 21
- Android SDK 35
- Android NDK installed locally
- CMake/Ninja from Android SDK tooling
- Gradle wrapper from this repository
- A WebDAV test endpoint with disposable credentials
- A test RDP endpoint with non-production credentials

The current app module targets Android API 35 and minimum API 26. Native build scripts currently assume an Android NDK under `/opt/android-sdk/ndk/28.0.13004108`; override the environment or edit the script locally if your SDK layout differs.

## Native Dependency Build

Build OpenSSL and FreeRDP before packaging the APK:

```bash
bash scripts/build-openssl-android.sh
bash scripts/build-freerdp-android.sh
```

Generated native libraries are copied into `app/src/main/jniLibs/arm64-v8a/` and are ignored by Git.

## APK Build

```bash
bash scripts/build-apk.sh
```

For Gradle-only checks after native libraries are present:

```bash
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:testDebugUnitTest
```

## RDP Debugging Notes

- Validate credentials and NLA/CredSSP behavior with a desktop FreeRDP client when possible.
- Keep test hosts disposable. Do not paste real production server addresses into public issues.
- If a server requires NLA, avoid adding fallback behavior that silently downgrades authentication.
- Check that the APK contains the expected `arm64-v8a` native libraries before testing on device.
- Keep connection logs useful but avoid logging credentials, tokens, or full private URLs.

## WebDAV Debugging Notes

- The settings screen should pass "test connection" before sync is considered ready.
- WebDAV input should be a collection URL, not a direct JSON file URL. The app attempts to normalize common mistakes.
- Test upload, download, merge, and failed-auth behavior separately.
- Merge behavior should preserve existing useful values and must not silently swallow WebDAV errors.
- Use HTTPS WebDAV endpoints for any non-disposable data.

## Documentation Changes

Documentation should describe current behavior accurately. In particular, do not claim encrypted credential storage until the implementation actually uses an encrypted store such as Android Keystore backed encryption.

## Release Checklist

- Build public APKs from the same commit that is tagged.
- Keep release notes aligned with the tagged source.
- Attach release APKs only; do not attach debug APKs to public GitHub releases.
- Verify the APK contains the native `arm64-v8a` FreeRDP/OpenSSL libraries before publishing.
- Do one final secret scan for real hosts, usernames, passwords, WebDAV URLs, access tokens, and private keys.
