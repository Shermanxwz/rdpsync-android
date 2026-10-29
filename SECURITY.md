# Security Policy

## Reporting a Vulnerability

Report the app version, Android version and minimal reproduction steps. Do not include passwords, tokens, private keys, private server addresses or screenshots containing personal desktop content. Use the repository's private vulnerability reporting option if available; public issues should contain only non-sensitive information.

## RDP Server Identity

- TLS certificate verification is mandatory. Legacy non-TLS RDP security is disabled, including compatibility mode.
- A first connection is rejected **before authentication** until the user explicitly trusts the displayed SHA-256 certificate fingerprint. Compare it using a separate trusted channel with the server administrator. Clicking trust without checking does not protect against a first-connection attacker.
- Trust is bound to the saved profile, canonical host, port and effective server name. Every connection checks the exact leaf-certificate fingerprint, including CA-valid certificates. Pins are local and are not synchronized.
- A changed certificate is rejected and requires a new explicit decision with an old/new fingerprint warning. There is no automatic trust of changed certificates or redirected/gateway endpoints.
- Trust-on-first-use pinning is not an enterprise PKI/revocation-management system. Legitimate certificate renewals require deliberate reapproval.

## Local Credentials

RDP passwords and the saved WebDAV password are encrypted with a device-bound Android Keystore AES-256-GCM key and a fresh random IV. Profile metadata, usernames and certificate fingerprints are not secrets encrypted by this feature. Passwords are necessarily available in process memory while connecting or explicitly exporting them; protection against a compromised/rooted device is not claimed.

Existing Room plaintext password rows are migrated transactionally and idempotently. WebDAV preference migration uses a durable atomic commit. Encryption or storage failures retain recoverable original data rather than silently writing empty passwords. If a key is lost or ciphertext is damaged, the profile remains visible but connecting/exporting its password is blocked; deliberately re-enter the password in Edit. The app does not delete or silently rotate keys to "repair" data.

Android backup/data transfer of app data is disabled because device-bound encrypted credentials cannot be restored independently of their key. Existing plaintext may remain in historical SQLite pages/WAL, filesystem snapshots or previously exported backups; field encryption is **not** forensic erasure. Do not uninstall the old app as a migration step.

## WebDAV Sync

- The default profile-only mode reads/writes **`rdpsync_profiles.json`**, with passwords omitted. It never rewrites or deletes an older `rdpsync_devices.json` file.
- Legacy/password-inclusive sync is a separate explicit choice. It reads/writes **`rdpsync_devices.json`**, including plaintext passwords, after an on-screen warning. Old app versions understand only this legacy file. These two files are intentionally independent; select the intended source explicitly.
- Password omission does not mean an empty password. Import retains the matching local credential using connection/account identity; unmatched local profiles are retained. New passwordless profiles retain a durable “password missing” state and cannot silently log in with an empty password. Explicit empty passwords in a password-inclusive import retain their meaning.
- This is **not end-to-end encrypted sync**. HTTPS protects transport, not against the WebDAV administrator or an exposed remote backup. Use HTTPS and a trusted endpoint; protect or remove old secret-bearing backups yourself when appropriate.

## Build and Remaining Boundaries

Preview APKs are development-signed, side-by-side test builds (`com.rdp.sync.preview`). They do not overwrite the original app or automatically copy its profiles. No production signing key is created or replaced by this project change. A dedicated release key and a reviewed distribution/update process remain necessary for production releases.

The pinned FreeRDP/OpenSSL build, tests and known coverage limits are documented in [verification notes](docs/VERIFICATION.md). Neither automated emulator testing nor these security improvements constitute a complete security audit or Microsoft Windows App parity certification.
