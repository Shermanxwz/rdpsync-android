# Privacy

RdpSync uses local storage and user-selected infrastructure. There is no project-operated cloud service, analytics, advertising or crash-reporting SDK.

## Local Data

The app stores profile names, RDP host/port, username/domain/server name, preferred dimensions, display settings and per-profile certificate fingerprints. RDP and saved WebDAV passwords use Android Keystore-backed authenticated encryption; profile metadata and WebDAV URL/username remain readable within the app's private storage. Passwords exist in memory while needed. Details and migration/key-loss limitations are in [SECURITY.md](SECURITY.md).

App backup and data transfer are disabled. The side-by-side preview has its own storage and does not automatically inherit the original app's profiles or credentials.

## Network Data

Only user-selected RDP connections and WebDAV operations send application data:

- RDP authenticates to the selected server only after its certificate fingerprint is explicitly trusted and matches on subsequent connections.
- WebDAV authentication goes to the configured endpoint. Use HTTPS.
- Default profile-only sync uses `rdpsync_profiles.json`, including connection metadata but **omitting passwords**. Its download/import preserves matching local passwords.
- Explicit legacy/password-inclusive sync uses `rdpsync_devices.json` and includes plaintext RDP passwords. It is not end-to-end encrypted. The default mode does not delete or modify an existing legacy file; old copies may still contain passwords.
- WebDAV connection testing creates, reads and deletes a small `.rdpsync_webdav_test` file in the selected collection.

Local render/network statistics support optional adaptive display quality; they are not uploaded to a project telemetry endpoint. Automated CI screenshots and logs use an isolated synthetic test desktop, not user servers.

## Explicit File Transfer

Preview 2 offers optional, session-scoped file clipboard transfer to the already selected RDP server. It does not watch Android's clipboard, synchronize text automatically, redirect a phone drive, or upload files to a project-operated service. Initial activation requires explicit reconnection; selecting files and publishing them are separate actions.

Only files selected through Android's document picker are read for sending. Private temporary snapshots retain the chosen names, lengths and contents while offered. The remote server can read a published snapshot until it is withdrawn or the session closes; closing the dialog alone does not withdraw it. Downloading remote file contents requires explicit confirmation after viewing the list, and writing outside private staging requires a separately selected export directory. No permanent document-access grant is requested.

Temporary file contents are not encrypted by the credential-storage feature. Normal teardown closes native file handles before cleanup; process interruption can leave app-private cache remnants until a later cleanup or Android cache eviction. Neither deletion nor cancellation is forensic erasure, and cancellation cannot recall bytes already received by the peer. Failed exports can leave a newly created incomplete file in the selected directory; existing destination files are not intentionally overwritten. Filenames, document URIs and raw provider exceptions must not be added to diagnostic logs.

Do not publish sync files, credentials, private hostnames or screenshots of private desktops in bug reports.
