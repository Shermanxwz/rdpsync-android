# Security Policy

## Supported Versions

Only the latest published APK or the latest build from the default branch is considered supported for testing.

## Reporting a Vulnerability

Open a GitHub issue with:

- RdpSync version
- Android version and device model
- RDP server type/version when relevant
- WebDAV server type when relevant
- Exact error text and reproduction steps

Do not include production passwords, personal access tokens, private keys, private WebDAV URLs, private RDP hostnames/IP addresses, or screenshots that reveal sensitive desktop content. Replace them with placeholders.

## Current Security Notes

- RDP credentials are stored locally because the app needs them to connect.
- WebDAV credentials are stored locally because the app needs them to synchronize.
- The current implementation does not yet provide an app-level encrypted credential store.
- WebDAV sync uploads saved device profile data to the user-selected WebDAV server. The JSON payload currently includes RDP username and password fields.
- Use HTTPS WebDAV endpoints.
- Treat debug APKs as testing builds.
- Avoid high-value production credentials until encrypted storage and a signed release workflow are in place.

## Planned Hardening

- Android Keystore backed encryption for local credentials.
- Optional password exclusion or redaction for WebDAV sync.
- Signed release artifacts.
- Stronger certificate trust UX for RDP endpoints.
