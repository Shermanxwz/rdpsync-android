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

## Persistent Shared Space (Preview 3 development)

Shared Space is a dedicated app-private, no-backup workspace. Its contents are
**not** encrypted by the password-storage feature. The workspace is not purged after a normal
session, revocation or app restart; clearing app data or uninstalling removes
that app's copy. Export important work explicitly. There is no automatic cloud
backup, original-folder synchronization or transfer from older preview packages.

Remote drive sharing is off until an explicit connection-scoped grant. Read-only
is the default. Read/write grants allow the selected remote computer to create,
modify, rename and delete workspace files, including later imports. Nothing
outside this workspace is redirected. The stock arbitrary-path drive plugin is
not enabled. User-selected SAF imports/exports are copies; temporary permissions
are not retained as a permanent phone-folder sharing grant.

The app and remote server use the same confined broker and share handles. A
selected object is pinned while a picker or mutation confirmation is open, so a
remote replacement cannot silently change what the user selected. Stopping
sharing withdraws future access without purging local work or undoing accepted
writes/deletions. An accepted delete-on-close request can complete when handles
close; already transmitted bytes cannot be recalled. The remote icon may remain listed but unavailable.

## Explicit File Transfer

Preview 2 offers optional, session-scoped file clipboard transfer to the already selected RDP server. It does not watch Android's clipboard, synchronize text automatically, redirect a phone drive, or upload files to a project-operated service. Initial activation requires explicit reconnection; selecting files and publishing them are separate actions.

Only files selected through Android's document picker are read for sending. Private temporary snapshots retain the chosen names, lengths and contents while offered. The remote server can read a published snapshot until it is withdrawn or the session closes; closing the dialog alone does not withdraw it. Downloading remote file contents requires explicit confirmation after viewing the list, and writing outside private staging requires a separately selected export directory. No permanent document-access grant is requested.

Temporary file contents are not encrypted by the credential-storage feature. Normal teardown closes native file handles before cleanup; process interruption can leave app-private cache remnants until a later cleanup or Android cache eviction. Neither deletion nor cancellation is forensic erasure, and cancellation cannot recall bytes already received by the peer. Failed exports can leave a newly created incomplete file in the selected directory; existing destination files are not intentionally overwritten. Filenames, document URIs and raw provider exceptions must not be added to diagnostic logs.

Do not publish sync files, credentials, private hostnames or screenshots of private desktops in bug reports.
