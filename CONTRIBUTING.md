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

The current app module targets Android API 35 and minimum API 26. Native build scripts currently assume an Android NDK under `/opt/android-sdk/ndk/28.0.13004108`; override `ANDROID_HOME`/`ANDROID_NDK` if your SDK layout differs.

## Native Dependency Build

The canonical scripts pin FreeRDP 3.32.2 by commit and OpenSSL 3.5.9 LTS by SHA256. Build the actual runtime before any APK is considered installable:

```bash
bash scripts/build-native.sh
bash scripts/test-native.sh
```

Generated native libraries are copied into `app/src/main/jniLibs/arm64-v8a/` and are ignored by Git.

The native host fixtures include production bridge code with Android/JNI stubs and sanitizer instrumentation; they are not a real-server test. In ptrace-only sandboxes, use `ASAN_OPTIONS=detect_leaks=0` and disclose that LeakSanitizer was unavailable.

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

`build-apk.sh` runs unit tests, lint, preview packaging and ELF/JNI/dependency/signature/16 KiB alignment checks. This follow-up's debug builds have package suffix `.preview2`, preserving both the original app and the older `.preview` release. Never claim a fresh debug signer can upgrade existing releases. Emulator workflows build x86_64 and use isolated synthetic xrdp/GNOME fixtures on standard GitHub-hosted runners; never point them at a user host.

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
- Stable releases require a reviewed release build and update-compatible signing identity. An explicitly authorized development preview may attach a debug-signed APK only as a clearly labelled prerelease with a separate package and disclosed update/data limitations; never present it as a stable or production-signed build.
- Verify the APK contains the native `arm64-v8a` FreeRDP/OpenSSL libraries before publishing.
- Do one final secret scan for real hosts, usernames, passwords, WebDAV URLs, access tokens, and private keys.
