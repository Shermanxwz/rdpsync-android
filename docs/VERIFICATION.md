# Preview verification and boundaries

## Build identity

- FreeRDP: **3.32.2**, exact commit `97eff6ddfbbfaa8e42a126d362686bd001ff8bdc`.
- OpenSSL: **3.5.9 LTS**, archive SHA256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`.
- SDK/target 35, minimum 26, NDK 28.0.13004108, JDK 21.
- Preview application ID `com.rdp.sync.preview`, versionCode 13, versionName `1.1.0-preview-debug`, ARM64 user APK; x86_64 separately built for emulator tests.
- Development signing only. `verify-apk.py` records package/version/ABIs/SHA256/signing certificate and verifies all defined JNI symbols, ELF architectures, dependency closure, 16 KiB ELF and ZIP alignment.

FreeRDP was upgraded rather than shipping the old 3.9.0 pin: vendor [GHSA-rrp5-666j-g6gp](https://github.com/FreeRDP/FreeRDP/security/advisories/GHSA-rrp5-666j-g6gp) covers a relevant DRDYNVC allocation issue fixed in [3.32.2](https://github.com/FreeRDP/FreeRDP/releases/tag/3.32.2). OpenSSL's [official downloads](https://openssl-library.org/source/) identify 3.5 as the supported LTS series.

## Automated checks

```sh
bash scripts/build-native.sh
bash scripts/test-native.sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
```

Native sanitizer fixtures execute the production bridge code against exact upstream structures with mocked Android/JNI/event surfaces: primary-GDI damage ownership; disjoint skipped-paint union; padded source/bitmap strides; RGB conversion; equal-area orientation swaps; stale bitmap rejection; unlock failure retaining damage; input edge ordering and extended scancodes; nonblocking overflow; capability bounds; freshness of actual network reports.

Pure Kotlin tests cover resolution bounds/profile preservation, adaptive hysteresis/cooldown/stale/repeated/absent data, resize acknowledgement/timeouts, input ordering/multiple contacts/cancellation/overload, IME composition, and viewport coordinate mapping.

`Android emulator RDP integration` builds real x86_64 libraries on **standard ubuntu-24.04 GitHub-hosted infrastructure** and uses KVM/API35 plus loopback-only disposable xrdp/Xorg and a synthetic desktop. It tests actual rendered RGB, Android touch-to-server-painted response, IME/held mouse/wheel, real DISP capability and framebuffer acknowledgement, rotation/reentry, compatibility fallback, and a bounded user-space latency/bandwidth proxy. Reports retain exact SHA, logcat, server logs, synthetic screenshots and timing samples. Tests deliberately fail if requested dynamic resolution is not actually applied; a successful send return is not accepted as proof.

A workflow definition or compiled test is **not** a passing emulator result. Consult the exact commit's completed Actions run and its evidence artifact. Raw timing samples reflect emulator/software rendering and test polling, not physical-phone or click-to-photon benchmarks.

## Adaptive semantics

- Off by default; explicit opt-in. Manual fixed resolutions remain exact when off.
- Fit-window pixel scales: economy 0.60 (local max30 FPS), balanced 0.80 (max60), sharp1.0 (max60), preserving aspect ratio and bounding dimensions to4096 and area to8MP.
- Downshift after3 sustained new bad observations; at least12s between downshifts. Upgrade after15 healthy observations, at least30s after a change. A repeated aging server report is not counted as multiple fresh measurements.
- Genuine FreeRDP server network-characteristics samples only; missing/zero/stale values remain unknown. Received data volume and a static desktop's FPS are not network-capacity measures. Some servers do not provide these reports.
- Local copy and Canvas draw **CPU submission** times are measured separately; not GPU completion or end-to-end latency. Sustained active render pressure may also reduce resolution.
- Online changes use DISP and await actual framebuffer dimensions. Unsupported caps or an8s acknowledgement timeout retain the current session; explicit reconnect is offered, never automatic quality reconnect loops.

## Remaining limits and safety

- No claim of Microsoft Windows App equivalence. No real handset radio/touch/GPU or Windows-host certification was performed by the cloud-only automated harness.
- H.264/RDPGFX acceleration is not enabled. FreeRDP's experimental MediaCodec path has no documented device fallback and references Android28 APIs unguarded despite this app's API26 support; it also copies decoded YUV through GDI rather than a zero-copy Surface. A supported codec path needs its own tested device matrix before enabling.
- Native touch depends on server RDPEI support; otherwise direct touch uses mouse/wheel compatibility. Server-specific Windows precision, handwriting/pen and native cursor-shape parity are not promised.
- Existing certificate-ignore behaviour is still present and must not be mistaken for verified TLS server identity. Saved credentials/ WebDAV JSON remain plaintext at rest; no production credential migration was attempted. Do not use untrusted RDP hosts or high-value credentials.
- Preview installs side by side, does not copy old profiles automatically and must not require deleting the original app. No production signing key, merge, public GitHub release, app-store deployment, server policy change or user RDP connection is part of this work.
