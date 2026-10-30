# Isolated Android / RDP integration fixture

`Android emulator RDP integration` runs on a **standard `ubuntu-24.04` GitHub-hosted runner** with an API 35 x86_64 Android emulator and KVM. It builds the real JNI/FreeRDP dependencies for x86_64; it does not use stub frames or mock the RDP transport.

The runner builds pinned xrdp **0.10.6.1** (`80c52879fead585d6347df5b3c587718adf8f5a5`) and xorgxrdp **0.10.5** (`c2d3cbafebe00be3221f985c06ef6b97f1c63790`), then creates a disposable `rdpfixture` desktop. The shared versioned setup reuses the distribution fixture's account/certificate/desktop but explicitly replaces its server/backend before testing; actual versions and source/binary hashes are retained. Its documented test-only password belongs only to that ephemeral VM. RDP (`3389`), state inspection (`8765`), and the weak-link proxy (`3390`) bind **only to runner loopback**. The emulator reaches that loopback through Android's `10.0.2.2` alias. Nothing connects to a saved user device, production host, or external RDP service. The versioned setup refuses to run outside a disposable GitHub-hosted runner.

The 14-test Android xrdp fixture is explicitly **classic GDI/DISP coverage**:
only its server build adds upstream `--disable-rfxcodec`. In pinned xrdp,
`XRDP_RFXCODEC` gates GFX capability acceptance (`libxrdp/xrdp_sec.c`), so the test
asserts actual no-GFX negotiation/zero decoded GFX commands while preserving the
normal production client's automatic GFX advertisement and real DISP resize.
Exact RGB/alpha checks are not relaxed for a lossy codec. Server build trees are
fresh and purpose-specific, and configure flags are retained. The independent
official-client reference comparison keeps its original server configuration;
the separate mandatory GNOME jobs prove actual software Progressive codec9.
Historical GFX-enabled/coachmark-obscured failures remain retained, not rewritten.

## Assertions

- A separate `NativeRuntimeInstrumentedTest` initializes app-private WinPR/FreeRDP runtime paths idempotently and allocates/frees a genuine native client context without any network connection.
- Native libraries load, the real desktop connects, and the saved profile dimensions are honored.
- Red, green, and blue probe blocks match **exact RGB channels** at uniform interior pixels in Android screen captures, covering native decode → JNI bitmap → Android rendering. After HD resize, an additional background pixel lies outside the original framebuffer.
- Injected Android touchscreen events pass through `RdpDesktopView`, the ordered input dispatcher, FreeRDP, and the server. The test waits for the server's response color to appear on the Android screen.
- Before each measured single down/up, the actual View must be attached, shown, nonzero-sized, window-focused, and visibly painting the exact RGB probes with the settings dialog absent. This avoids injecting into a closing Android dialog; it does not retry lost clicks or extend the response deadline. Bounds/focus/frame metadata are retained per attempt.
- IME composing text is committed once; backspace/newline reach the desktop. Held mouse press/move/release and wheel events reach the X server.
- The server must advertise DISP. Selecting `1280 × 720` must change **both** the actual decoded framebuffer and X server desktop size; a successful request return code alone does not pass.
- Fit-window + rotation changes real remote orientation while the session remains connected.
- With the keyboard hidden, compact landscape must dedicate at least 72% of the fixture's full window height to the desktop while its essential controls remain reachable. Expanding/restoring controls must resize the actual remote framebuffer without changing native generation or X desktop UUID/PID.
- The keyboard toolbar must show and hide a real software IME. The local viewport shrinks/restores, but remote geometry and session identity must stay unchanged over three observed session-loop display polls spanning at least two seconds. The fixture temporarily permits a software IME alongside the emulator's hardware keyboard and restores that setting afterward.
- While typing, the desktop must occupy at least 90% of the remaining system-IME-free height. Its shared rendering/input transform must preserve the previous scale, and the observed exact-color probe width must remain the same (at most two pixels of edge quantization). This tests local cropping instead of an unreadably shrunken whole desktop. Hide/tools/exit controls and the existing shortcut menu stay reachable.
- Real software-keyboard `a` and Enter keys are located from the actual input-method accessibility window and tapped once each through Android input injection. Completed composition must reach the server as `a\n` and return a distinct three-color text-length probe through the framebuffer. Interim composition is recorded, not forced; missing keys or final text fail rather than falling back to direct/native text injection. A direct remote touch and tools-menu Esc are also exercised while the IME remains visible.
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

### Optional offline software-emulator smoke

With an existing Android SDK emulator and `system-images;android-29;default;x86_64`,
`bash scripts/fixtures/run-local-native-smoke.sh APP_X86_64_APK TEST_APK [OUTPUT_DIRECTORY]` creates an
isolated workspace AVD, boots without KVM, and runs the real native-context/channel
smoke without any RDP server or network connection. It bounds boot to 300 seconds
and instrumentation to 180 seconds, records source SHA/status plus APK hashes and
logs, and stops only its own emulator. It does not download SDK components, build
APKs, change signing, create OS users, or modify host network policy. Set `JAVA_HOME`
and `ANDROID_HOME` as needed. `RDP_SMOKE_CLASSES` can also include
`com.rdp.sync.security.CredentialStorageInstrumentedTest` for genuine Android
Keystore regression coverage. Software-emulation timings are not performance data.

### Optional real-TLS callback isolation

`bash scripts/fixtures/run-local-tls-smoke.sh APP_X86_64_APK TEST_APK [OUTPUT_DIRECTORY]`
starts a workspace-owned Python X.224/TLS peer on loopback ports 33890/8767 and uses
the same isolated software emulator. It generates two disposable self-signed
certificates and exercises production JNI connect/challenge handling: unknown and
changed pins must close with zero TLS application bytes; a matching pin may reach
only the parsed MCS Connect-Initial boundary, where this synthetic peer deliberately
closes. Each negative assertion waits for the native disconnect and server-side
settled connection counter. It never implements authentication or a desktop, and
**must not be reported as full RDP acceptance**. Hosted xrdp rendering/input/resize
remains mandatory. This optional Android test skips unless explicitly enabled; the
local runner rejects skipped/error statuses for an explicit run.

The hosted emulator workflow explicitly enables and executes this TLS class before
the full desktop test (14 Android tests total: runtime, TLS, desktop and 11 credential
tests). It starts a separate loopback fixture with keys under `RUNNER_TEMP`, retains
only public counters/logs, and stops its owned process during unconditional cleanup.
Direct test pins are not saved to profile preferences, and the TLS test always
disconnects before the production-screen desktop scenario begins.
An additional fresh-XML gate requires exactly those 14 distinct test cases and
class counts with zero failures, errors or skips; a missing optional TLS class
or assumption skip cannot silently make the hosted gate green.

The output `private/` directory contains synthetic fixture keys: do not upload it.
Retain only logs, fingerprints, counters, source/APK hashes and test output. An
optional `RDP_TLS_PEM_PROBE` executable can diagnose the exact Android FreeRDP/OpenSSL
PEM export path; only the public leaf certificate and native libraries are copied
to the isolated emulator, never private keys. These test certificates authorize no
real RDP host.

### Upstream classic-GDI resize comparison

`RDP upstream resize comparison` is a separate diagnostic, not a replacement or
waiver for Android acceptance. It compares the unmodified official FreeRDP 3.32.2
X11 client against the original Ubuntu xrdp 0.9.24 fixture and pinned upstream
xrdp 0.10.6.1 / xorgxrdp 0.10.5. The latter is a matched server/backend version
pair; differences cannot be attributed to the server alone without further evidence.
Each server runs four explicit output-flush / synchronous-DVC profiles. GFX is
disabled both in the client build and settings so the comparison exercises classic
GDI Deactivate/Reactivate, not a GFX resize shortcut.

An actual 1024×768 → 1280×720 resize must retain the server UUID, keep the client
alive, paint RGB probes and a pixel outside the old framebuffer, and respond to a
post-resize click with the correct visible marker. All profile outcomes/logs are
retained, including failures. The job terminates only its own newly created fixture
account's session between profiles; this cleanup is not a reconnect-latency metric.
Current-server auth-log markers are not used to claim certificate/authentication
coverage in this reference diagnostic. The mandatory Android run separately retains
the pre-trust unchanged-auth-counter assertion and a required positive increment
after explicit approval; the current server's ordinary DEBUG login marker was also
observed in the reference logs. No missing marker can silently pass the positive control.

The controlled comparison at commit `25236532c0e0dcab21bcda5a9567661d0827d5dc`
([run 40981333137](https://github.com/Shermanxwz/rdpsync-android/actions/runs/40981333137))
passed all four current-server profiles, including received resized pixels and
post-resize input. All four original xrdp 0.9.24 profiles instead resized the X
desktop then disconnected at `FINALIZATION_CLIENT_SYNC`, matching the Android
failure. These old failures are preserved, not relabeled as passes. The mandatory
Android workflow therefore explicitly uses the pinned current pair with the same
strict 14-test suite; the reference success itself does **not** validate Android,
Windows/NLA interoperability, physical-phone performance, or older-server support.

The current reference gate runs
`python3 scripts/fixtures/verify_reference_results.py build/reference-evidence`
and retains `validated-outcomes.json`. The old-server race is not deterministic:
[run 40986954644](https://github.com/Shermanxwz/rdpsync-android/actions/runs/40986954644)
received a complete resized frame and post-resize click in one old-server profile,
while the other three exited with the reactivation error. The strict gate for that
run remained red because two failure snapshots had not yet observed the X desktop
resize. Those raw outcomes and immediate snapshots remain unchanged.

Each original-server case is now classified separately: either **“old server live
resize passed in this attempt”**, requiring exactly the same positive
pixel/resize/input/liveness proof as the current server, or **“old interoperability
failure reproduced (CLIENT_…)”**, recording the exact guard state and awaited
server PDU. The latter must first receive exact initial pixels and DISP
capability, really resize the same X desktop UUID/PID, and then fail naturally with
the precise `update_recv` pre-ACTIVE UPDATE rejection and matching failure chain.
Only the pinned FreeRDP source's server-receive reactivation phases
`FINALIZATION_CLIENT_{SYNC,COOPERATE,GRANTED_CONTROL,FONT_MAP}` are enumerated.
Each later phase requires every completed preceding server flag and state transition;
timestamp ordering handles merged stdout/stderr without accepting later/stale flags.
`CLIENT_SYNC` additionally retains its exact missing-SYNCHRONIZE warning requirement.
No later ACTIVE transition or reconnect is permitted. The old FALSE/FALSE case in
[run 41001680629](https://github.com/Shermanxwz/rdpsync-android/actions/runs/41001680629)
received SYNCHRONIZE/COOPERATE/GRANTED_CONTROL and then failed while awaiting FONT_MAP;
that is explicitly **not** labelled missing synchronization, and the original red
run and raw artifacts remain unchanged. A separate
bounded, timestamped read-only observation may capture fixture metadata settling
after client exit; it does not retry the client, infer a resize, replace the original
failure snapshot, or convert failure into successful display support.
Build/authentication failures, missing images, forced cleanup, signal crashes, stale
metadata, and all other unexplained failures remain errors. Current-server cases
still require all four positive resize/pixel/input/liveness outcomes.

Raw `results.tsv` and case `exit-code.txt` remain unchanged; `process-result.json`
separately records the actual child exit code and natural-versus-cleanup origin.
Fresh run/PID and frame-hash metadata are mandatory. Both old-server classifications
are compatibility observations, not a guarantee of reliable old-server support.
They do not retroactively change historical failed workflow outcomes.

## Native GNOME Remote Desktop (additional, not an xrdp substitute)

`Native GNOME RDP emulator integration` adds two independent standard Ubuntu 24.04
hosted jobs using the distribution's **GNOME Remote Desktop 46.x**, Mutter,
PipeWire/WirePlumber and a synthetic GTK **Wayland** surface. Installed package
versions, source-package identities, daemon hashes and linked libraries are
retained; a different GNOME major version fails setup rather than silently
changing this baseline.

- `headless-extend` uses GNOME's per-user `--headless` mode and genuine virtual
  monitor/DISP resize. Both decoded dimensions and the GTK/GDK actual monitor
  must change, with the same native connection and Wayland process/session.
- `screen-share-mirror` mirrors an existing Mutter virtual primary monitor. It
  must deliver real pixels and input and retain the actual fixed monitor after a
  different client size is requested. This is an explicit capability boundary,
  not a live-resize pass.

Neither mode runs GDM/system **Remote Login**, server redirection, a Windows
server, or a user's desktop. GNOME requires real NLA here; synthetic credential
environment variables are the upstream integration-test credential source, not
an authentication bypass. Certificate approval still uses the complete fixture
DER SHA-256. The existing separate TLS boundary tests remain the evidence that
unknown/changed pins stop before application credential bytes.

GRD's wildcard listener lives in a private network namespace with only its own
loopback interface. Permission-restricted Unix sockets relay to **127.0.0.1**
ports 33891 and 8768 on the disposable runner. No firewall, global routing,
external address, user account or user network configuration is changed. A
private HOME/runtime/D-Bus session contains all fixture settings. Artifacts
include only explicit evidence directories, never the private TLS key or HOME.

Before Android, a pinned unmodified official FreeRDP 3.32.2 **classic/no-GFX**
client connects using verified certificate pin and NLA. The negative control
requires GNOME's exact missing-Graphics-Pipeline rejection, not merely any
nonzero exit. The Android positive then requires native GFX frame decoding,
actual Android touch coordinates arriving at Wayland, returned response pixels,
and View InputConnection text. Actual Gboard key/IME/crop/fullscreen interaction
remains covered by the separate mandatory full xrdp UI scenario. Software
progressive graphics is not H264 or hardware decoding. No GNOME pass is claimed
until actual runtime artifacts have been inspected.

The GNOME Progressive gate now uses a **same-run reference-decoder calibration**,
not ideal-source RGB equality or a global tolerance. The official pinned GFX
client must deliver real frames/input, every source probe color (including all
three text markers), and headless DISP resize. Finalized logs must identify
codec 9 after the owned process is joined, avoiding stdout-buffer races. Linked
ELF hashes, source/build configuration, PNG hashes and sampled pixels bind the
calibration. Repeated uniform patches must be stable and source deviation must
stay within the independently observed two-level-per-channel bound; missing colors,
wrong codec/source, tampered PNGs and inconsistent samples fail closed. Android
then compares **exactly** to those reference values, recording source, reference
and actual RGB. Existing classic/native exact-color and alpha gates are unchanged.

### Explicit files-only transfer gate

The next-stage GNOME jobs also execute `GnomeTransferInstrumentedTest` and the three
`TransferStorageInstrumentedTest` framework-provider/native-FD tests: **seven required tests
per GNOME mode** (including one separately labelled transport diagnostic), with zero skips/errors/failures. The independent xrdp 14-test
scenario remains unchanged. This is file clipboard streaming, not drive mapping,
text clipboard synchronization, or GDM login.

The GTK3 fixture loads a tiny audited URI-list provider into its existing native
Wayland process. Its loopback/private-namespace control endpoint accepts only two
fixed, empty-body synthetic actions: offer three generated server files, or read
three expected Android files through GNOME's actual FUSE clipboard mount. It
accepts no supplied paths, commands, or arbitrary bytes. Binary, zero-length and
Unicode-named files are checked against independently generated SHA-256 manifests.
The provider is compiled with warnings-as-errors; thirteen host safety tests reject
foreign paths/URIs, symlink escapes, duplicates, bad lengths/content, overwrite and
missing/wrong-type/non-containing mount metadata. Real reads require and retain
only matching owned FUSE entries from actual `/proc/self/mountinfo`, plus an
increase in bytes served by the current native offer. Host tests inject explicitly
labelled synthetic metadata and never claim to have mounted or read real FUSE.
The runner fails if real `/dev/fuse` is unavailable instead of substituting a mock.

Android drives the actual DocumentsUI multi-selection and directory-grant dialogs
with single physical taps of observed accessibility bounds. Sources and the
chosen destination subfolder are disposable test-owned Downloads children; the
Downloads root is never granted. A preexisting same-name sentinel must remain
byte-identical while three new exported children match the remote originals.
System-shell hashes independently inspect the resulting test-owned files; this
is not a fabricated activity result or an invented URI grant. Separate provider
unit/instrumentation tests do not count as this actual picker evidence.

The test proves the channel starts disabled, cancellation leaves the generation
unchanged, explicit approval reconnects, closing the transfer dialog retains an
outbound offer for a real peer read, Stop Sharing revokes it without disconnect,
and route Back disposes/revokes a fresh offer. Already copied remote files are not
claimed to be remotely erased. Artifacts retain original/received/export hashes,
actual native capability state, picker bounds/screenshots and exact test counts.
Runtime success remains pending until these new tests actually execute.

### Independent xrdp file-clipboard peer

`xrdp-file-emulator.yml` adds a **separate six-test job**: real native runtime,
three storage/provider/FD checks, one transport-only diagnostic, and `XrdpTransferInstrumentedTest`. It does not
replace or reduce the existing fourteen-test classic desktop/security/IME job,
the two seven-test GNOME jobs, or the official-client resize comparison.

The fixed `android-file-transfer` server purpose builds the same pinned current
xrdp/xorgxrdp sources with explicit `--enable-fuse --disable-rfxcodec`. It requires
real usable `/dev/fuse`, records configure options, source commits and chansrv
binary hashes, and uses a fresh purpose-specific build tree. Other server
purposes do not acquire FUSE changes. This tests a second native CLIPRDR peer,
**not Windows OS compatibility or Windows NLA**.

The existing synthetic X11 desktop runs alongside a tiny GTK3 clipboard owner
under the disposable `rdpfixture` account. Its host-loopback endpoint accepts only
the same two fixed empty-body actions and three generated files as GNOME. It
must read the Android offer through that account's actual
`thinclient_drives/.clipboard` FUSE mount; matching owned kernel mount metadata,
current native offer bytes served, and independently generated byte hashes are
all required. Nine host safety tests cover missing/non-FUSE mounts, wrong roots,
symlink escapes, duplicates, corrupt content and destination overwrite. They use
explicitly labelled synthetic mount metadata, not a claimed real FUSE runtime.

Both peer classes inherit `ClipboardTransferScenario`, so the actual DocumentsUI
multi-selection/tree grant, collision sentinel, bytes/hash equality, explicit
consent/reconnect, monotonic send budget and offer lifecycle assertions stay the
same. Endpoints are a fixed enum rather than caller-supplied hosts. GNOME still
requires actually decoded software Progressive codec 9; the xrdp file job
requires actual classic/no-GFX diagnostics. Shared implementation is not evidence
that either peer passed: each exact class count, executed SHA and runtime result
must be inspected independently.

### API29 fullscreen / IME boundary

`api29-layout-emulator.yml` runs one explicitly selected test on a fresh standard
hosted KVM `android-29/default/x86_64` emulator. It uses the real MainActivity,
SessionSystemBars and SessionViewportLayout with a local EditText, not a copied
layout, RDP server, remote input substitute, or file-transfer claim. Portrait and
landscape require >=95% fullscreen coverage, real IME visibility/local cropping,
every remote-layout callback unchanged during IME, stable activity/editor,
unique reachable controls, bordered/fullscreen transitions, and restored bars
on disposal. Raw pre-30 Compat insets and actual legacy hide flags are retained
separately. A single edge swipe records images/window observations; it does **not**
automatically claim transient-bar visibility from flags.

The runner refuses non-hosted execution, a wrong API/device or multiple devices;
all device commands address the action-owned emulator explicitly. Fresh XML must
contain exactly this one passing non-skipped test, and fresh JSON must match the
checked-out commit/tree, actual device timestamps and all IME callback outcomes.
Screenshots and source/APK/native hashes are retained, never AVD HOME/private
keys. Nine synthetic host mutation tests protect that evidence validator; they
are not Android execution. Prior local TCG startup ANRs and the genuine stale
pre-30-insets failure remain preserved separately. A new gate's existence or
compilation is not a runtime pass.

### Separate native clipboard transport diagnostic

Each GNOME/xrdp job also runs a separate named native-transport test before the
mandatory physical SAF scenario. It stages its own generated bytes through the
real TransferStore and native FD/chunk APIs, with exact test-certificate pinning
and explicit file-channel opt-in, then verifies current transaction IDs, actual
peer FUSE reads/hashes, partial-receive cancellation and full receive bytes.
These diagnostics never claim DocumentsUI grants, user consent or UI coverage:
JSON explicitly records `diagnostic_only=true` and `saf_ui_covered=false`.
Their success cannot substitute for the separately required physical picker and
export scenario. Both use fixed existing peer actions; unique operation-indexed
output directories keep evidence separate without overwriting earlier results.

The pinned xrdp peer does not advertise file-snapshot locking. Its physical SAF
scenario therefore requires the first real bidirectional transfer, then requires
an exact reconnect-required error on a second publish without any hidden
reconnect, new offer, source/sentinel mutation or budget reset. Only physical
activation of the existing reconnect confirmation permits a fresh generation;
that new connection must receive a fresh SAF selection and explicit publish
before the same live-offer route-Back revocation assertion. GNOME's supported
same-connection re-offer assertion is unchanged. This is an explicit capability
boundary, not an automatic retry or a claim that rejected replacement succeeded.
