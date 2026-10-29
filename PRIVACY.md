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

Do not publish sync files, credentials, private hostnames or screenshots of private desktops in bug reports.
