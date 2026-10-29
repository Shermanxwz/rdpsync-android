# Isolated Android / RDP integration fixture

`Android emulator RDP integration` runs on a **standard `ubuntu-24.04` GitHub-hosted runner** with an API 35 x86_64 Android emulator and KVM. It builds the real JNI/FreeRDP dependencies for x86_64; it does not use stub frames or mock the RDP transport.

The runner installs xrdp + xorgxrdp and creates a disposable `rdpfixture` account. Its documented test-only password belongs only to that ephemeral VM. RDP (`3389`), state inspection (`8765`), and the weak-link proxy (`3390`) bind **only to runner loopback**. The emulator reaches that loopback through Android's `10.0.2.2` alias. Nothing connects to a saved user device, production host, or external RDP service. `start-rdp-fixture.sh` refuses to run outside GitHub Actions.

## Assertions

- A separate `NativeRuntimeInstrumentedTest` initializes app-private WinPR/FreeRDP runtime paths idempotently and allocates/frees a genuine native client context without any network connection.
- Native libraries load, the real desktop connects, and the saved profile dimensions are honored.
- Red, green, and blue probe blocks appear with the correct colors in **Android screen captures**, covering native decode → JNI bitmap → Android rendering.
- Injected Android touchscreen events pass through `RdpDesktopView`, the ordered input dispatcher, FreeRDP, and the server. The test waits for the server's response color to appear on the Android screen.
- IME composing text is committed once; backspace/newline reach the desktop. Held mouse press/move/release and wheel events reach the X server.
- The server must advertise DISP. Selecting `1280 × 720` must change **both** the actual decoded framebuffer and X server desktop size; a successful request return code alone does not pass.
- Fit-window + rotation changes real remote orientation while the session remains connected.
- A new session starts in direct touch even if the preceding session temporarily used trackpad mode.
- The weak-link case uses a separate user-space TCP proxy, not `tc`, firewall rules, or runner-wide network changes. It introduces 80 ms per read chunk in each direction plus a 1 MiB/s per-direction transmission budget. These are proxy parameters, not a model of mobile radio behavior or a guaranteed exact throughput/RTT.
- A compatibility session without DISP rejects resize with the expected unsupported code, keeps its original actual size, and remains connected.

## Evidence

The workflow retains the exact checkout SHA, emulator fingerprint/ABI, xrdp version, Android logcat, xrdp/Xorg/fixture logs, screenshots, instrumentation reports, and `measurements.json`. The measurements include native telemetry and adaptive-mode state; unavailable server network telemetry remains unavailable rather than being fabricated.

Input timing is **touch injection to observed matching screen-capture color**, including a 40 ms touch hold, 100 ms polling granularity, and screenshot overhead. It is useful regression evidence, not click-to-photon latency or a physical-phone benchmark. This fixture uses xrdp, not Windows, and may not implement RDPEI or RDP network autodetection. Native touch/Windows server compatibility, hardware decoder behavior, real touchscreen feel, Wi-Fi/cellular variation, power/thermal behavior, and comparison against Windows App still require real-device/server acceptance.

## Re-run

Run the workflow manually on a branch or open a pull request. Locally, instrumentation compilation needs no fixture:

```sh
./gradlew :app:assembleDebugAndroidTest
```

The integration test itself intentionally fails without `rdpFixture=true`; a missing real server must not produce a misleading skipped/green integration result. `run-emulator-tests.sh` supplies the argument in the isolated hosted job.

## Certificate trust and local credential acceptance

The fixture generates a new one-day self-signed certificate solely for this ephemeral xrdp service. The runner independently derives its DER SHA-256 via OpenSSL and supplies it as a test argument. A loopback-only controller (`8766`) exposes public certificate fingerprints and counts xrdp's DEBUG-level `sending login info to session manager` markers. The test requires:

- First trust shows the complete expected fingerprint; confirmation is disabled until the independent-verification checkbox is selected.
- No session-manager authentication request or authenticated desktop exists before the explicit test approval. A real login afterward must increment the same observed counter, preventing a silently ineffective logging assertion.
- Reentry with the same profile/authority and pin does not prompt again.
- Rotating only the disposable fixture certificate after disconnect creates a changed-certificate prompt, without a new authentication request. Cancel does not authenticate or replace the pin; a later explicit verified replacement permits connection.

The controller accepts no command, path, user, certificate, or key material from clients. Its only mutation is a fixed certificate rotation/restart action for this one ephemeral fixture. It refuses to start outside GitHub Actions. No private keys or passwords are included in evidence artifacts. These checks validate this TLS fixture; they do not prove Windows CredSSP/NLA interoperability.

The same emulator invocation also executes `CredentialStorageInstrumentedTest`, which uses isolated UUID databases/preferences and test-only Android Keystore aliases to verify storage, migration/rollback, restart, corruption/key-loss handling, and password-free imports.

The instrumentation pumps Compose's test frame clock while waiting for real-time network work. It copies screenshots/JSON to shell-owned `/sdcard/Download/rdpsync-evidence` before AGP/UTP uninstalls the test app, so evidence survives test cleanup.

Session teardown allows up to 90 seconds: xorgxrdp 0.9.x clamps disconnected-session cleanup to a 60-second minimum and checks on a timer. The test waits for the old desktop to exit rather than accepting stale session state.
