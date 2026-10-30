# Preview verification and boundaries

## Build identity

- FreeRDP: **3.32.2**, exact commit `97eff6ddfbbfaa8e42a126d362686bd001ff8bdc`.
- OpenSSL: **3.5.9 LTS**, archive SHA256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`.
- SDK/target 35, minimum 26, NDK 28.0.13004108, JDK 21.
- RDPDR/autodetect requires the RDPSND `sys:fake` protocol dependency. Only that fake backend is built; real audio backends and microphone input are disabled, and the actual post-load settings must retain no playback/capture or redirected devices.
- Remote desktop scale is currently fixed at 100%; resolution and local zoom are independently adjustable, but there is no separate remote DPI selector.
- Landscape defaults to a compact 56dp toolbar with accessible keyboard, input mode, settings and disconnect controls. Expanded tools are optional; system safe insets remain respected. Detailed resolution/adaptive status is available in settings without permanently consuming desktop height.
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

Native sanitizer fixtures execute the production bridge code against exact upstream structures with mocked Android/JNI/event surfaces: primary-GDI damage ownership; disjoint skipped-paint union; padded source/bitmap strides; RGB conversion; equal-area orientation swaps; stale bitmap rejection; unlock failure retaining damage; input edge ordering and extended scancodes; nonblocking overflow; capability bounds; freshness of actual network reports; JNI private runtime-directory initialization and idempotence.

A separate-translation-unit **Clang `-O2`** regression invokes the production context factory and verifies its session binding survives the upstream allocator's `malloc` annotation. The previous allocator-return-only binding is a failing negative control: its store was optimized away. The real Android runtime gate also checks the owner/session relationship, certificate callback and external certificate management before networking.

Pure Kotlin tests cover resolution bounds/profile preservation, adaptive hysteresis/cooldown/stale/repeated/absent data, resize acknowledgement/timeouts, input ordering/multiple contacts/cancellation/overload, IME composition, and viewport coordinate mapping. Security tests cover certificate parsing/pin matching and stale approval decisions, authenticated-encryption corruption/wrong-key behavior, migration and omitted-versus-empty sync semantics. JVM loopback MockWebServer tests exercise sync semantics and verify malformed secret-bearing responses cannot escape through error messages or exception causes; Android instrumentation exercises genuine Keystore storage and raw durable data.

The Android desktop scenario also requires at least 72% landscape viewport coverage on its fixed emulator, essential control reachability, same-session expanded/compact resizing, and a real software-keyboard show/hide cycle that changes only the local viewport. Measured taps begin only after the desktop is visibly painted and its window has regained focus after dialogs. Uniform interior RGB and newly exposed resized pixels are compared exactly; input latency still includes injection and screenshot/polling overhead.

`Android emulator RDP integration` builds real x86_64 libraries on **standard ubuntu-24.04 GitHub-hosted infrastructure** and uses KVM/API35 plus loopback-only disposable xrdp/Xorg and a synthetic desktop. A separate Android runtime smoke gate first allocates/frees a genuine FreeRDP context without networking, checking app-private HOME/cache initialization and the exact normal/compatibility channel profiles, including dependency loading and cleanup. It tests actual rendered RGB, Android touch-to-server-painted response, IME/held mouse/wheel, real DISP capability and framebuffer acknowledgement, rotation/reentry, compatibility fallback, and a bounded user-space latency/bandwidth proxy. Reports retain exact SHA, logcat, server logs, synthetic screenshots and timing samples. Tests deliberately fail if requested dynamic resolution is not actually applied; a successful send return is not accepted as proof.

A workflow definition or compiled test is **not** a passing emulator result. Consult the exact commit's completed Actions run and its evidence artifact. Raw timing samples reflect emulator/software rendering and test polling, not physical-phone or click-to-photon benchmarks.

The optional local X.224/TLS fixture exercises the actual Android FreeRDP certificate callback with disposable certificates: unknown/changed pins must send no TLS application data; a matching pin may reach the deliberately terminated MCS boundary. This focused test does **not** complete authentication, render a desktop, or replace the hosted real-RDP scenario. See [fixture instructions](../scripts/fixtures/README.md).

## Classic resize interoperability evidence

The original Ubuntu xrdp **0.9.24** fixture resized its X server from 1024×768 to 1280×720, then the client rejected an UPDATE while still waiting for reactivation synchronization. This failed the Android resized-framebuffer assertion; server dimensions alone were not accepted as success.

A [controlled upstream-client comparison](https://github.com/Shermanxwz/rdpsync-android/actions/runs/40981333137) at commit `25236532c0e0dcab21bcda5a9567661d0827d5dc` reproduced that failure outside the app with unmodified FreeRDP **3.32.2 X11**, classic GDI/32bpp, no GFX or smart sizing. All four combinations of output-flush waiting and synchronous dynamic channels failed against 0.9.24. With pinned xrdp **0.10.6.1** (`80c52879fead585d6347df5b3c587718adf8f5a5`) and xorgxrdp **0.10.5** (`c2d3cbafebe00be3221f985c06ef6b97f1c63790`), all four passed: same server session/PID, actual 1280×720 client pixels, exact RGB including a newly exposed region, and a post-resize input-painted response while the client remained alive. The reference keeps upstream internal channel build defaults, including AINPUT; it is not a byte-identical Android client profile.

This demonstrates a version-specific interoperability boundary, not exclusive server fault or universal server compatibility. The historical diagnostic run remains failed overall because its old-server cases genuinely failed; those results are not silently reclassified. Subsequent comparisons use explicit **known-negative controls** for that pinned old pair: a valid initial desktop, actual same-session server resize, the specific synchronization/UPDATE rejection, case failure, and natural client termination before cleanup must all be established. The raw case and child exit codes are retained separately. A setup failure, timeout, missing evidence, cleanup-caused termination, or other unexpected result fails the control. A validated negative means only “old interoperability failure reproduced,” never “old resize passed.” Current-server cases still require successful received pixels and post-resize input.

Earlier reference build failures and the development-logging initial-frame timeout are not resize evidence. The mandatory Android fixture now uses the verified pinned current server pair, but its **own exact-commit Android results** remain the acceptance gate. No production synchronization/state checks were relaxed. For old servers affected by this boundary, use a fixed resolution and reconnect deliberately rather than relying on live resize.

The event-driven native transport keeps `WaitForOutputBufferFlush=FALSE` to avoid waiting inside each write for a stalled peer before the owner thread can process lifecycle events. This is a lifecycle design choice, **not a measured FPS improvement**, a strict disconnect-time guarantee, or a bound on the transport BIO's memory. The four-way comparison shows this setting is not necessary for the old-server resize failure.

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
- TLS uses explicit per-profile endpoint-bound SHA-256 trust-on-first-use pinning; a new/changed certificate is rejected before authentication. Local passwords migrate to Android Keystore AES-GCM. Default WebDAV profile sync omits passwords into a separate file; explicit legacy sync still contains plaintext passwords and is not end-to-end encrypted. See [security and migration boundaries](../SECURITY.md), including first-use fingerprint verification, key loss and historical plaintext remnants.
- Preview installs side by side, does not copy old profiles automatically and must not require deleting the original app. No production signing key, merge, public GitHub release, app-store deployment, server policy change or user RDP connection is part of this work.
