# Third-party native notices

The project's [MIT license](LICENSE) is unchanged. Bundled third-party components retain their own licenses and copyright notices.

## Native runtime included in the preview

| Component | Pinned source | Bundled notices |
| --- | --- | --- |
| FreeRDP / WinPR 3.32.2 | [commit `97eff6ddfbbfaa8e42a126d362686bd001ff8bdc`](https://github.com/FreeRDP/FreeRDP/tree/97eff6ddfbbfaa8e42a126d362686bd001ff8bdc) | [Apache 2.0 license](app/src/main/assets/third_party/freerdp-3.32.2-LICENSE.txt), source notices below |
| OpenSSL 3.5.9 LTS, statically linked into the native runtime | [official source release](https://github.com/openssl/openssl/releases/tag/openssl-3.5.9); archive SHA256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a` | [Apache 2.0 license](app/src/main/assets/third_party/openssl-3.5.9-LICENSE.txt), source notices below |
| Android CPU feature detection bundled by WinPR | The pinned FreeRDP source's `winpr/libwinpr/sysinfo/cpufeatures` | [Upstream NOTICE](app/src/main/assets/third_party/android-cpufeatures-NOTICE.txt), including the source's additional terms in the collection below |

[Native source notices](app/src/main/assets/third_party/native-source-NOTICES.txt) preserve exact upstream copyright/license comment blocks, with relative source paths. This conservative collection includes runtime source branches and optional implementations beyond the selected Android build; it is **not** a claim that every mentioned component is linked or enabled. In particular, the actual profile does not enable H.264/RDPGFX, USB, real audio playback, or microphone redirection. The collection also preserves distinct terms such as WinPR's internal MD4 and CPU-feature sources instead of assuming every file has only the root Apache license.

All notice assets and their [provenance manifest](app/src/main/assets/third_party/manifest.json) are included in the APK at `assets/third_party/`. `scripts/verify-apk.py` checks that the APK contains the exact committed asset bytes. When changing dependency pins, regenerate and review the notices with `scripts/collect-native-notices.py` against the pinned source inputs, then run its `--check` mode and rebuild/verify the APK. No production signing, app-store or legal certification is implied by this packaging check.

## Narrow local FreeRDP transport addition

The Shared Space build applies [`patch-freerdp-flow-control.py`](scripts/patch-freerdp-flow-control.py)
to the exact commit above. It appends one read-only pending-ciphertext accessor;
it does not change TLS, authentication, certificate checks or transport writes.
The patch rejects four source-file hash mismatches, and the build rejects all
other working-tree changes. Its script SHA256 is
`2bff70af5face163878447ac2c53518029193c7a15f41a5db14b399c16e7cc5b`.
Per-ABI build provenance records the source pins, patch-suffix hash and resulting
transport-source hash. The separately built official reference client remains
unpatched. Upstream notices above are collected from the immutable upstream
commit; the local addition is this project's code, not an upstream feature.

## Android/JVM dependencies

This implementation does not add or change production Maven dependencies: their declarations remain in [app/build.gradle.kts](app/build.gradle.kts). Existing packaged resources, including OkHttp's public-suffix NOTICE, are retained. New `org.json`, MockWebServer and coroutine-test dependencies are `testImplementation` only and are not bundled into the user APK. Test tools and their notices do not turn the preview APK into a production-signed release.
