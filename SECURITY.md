# Security Policy

## Supported Versions

Only the latest debug APK in `releases/` is supported for testing.

## Reporting a Vulnerability

Please open a GitHub issue with:

- RdpSync version
- Android version and device model
- RDP server type/version when relevant
- WebDAV server type when relevant
- Exact error text and reproduction steps

Do not paste real production passwords, access tokens, or private WebDAV URLs into public issues.

## Notes

RdpSync stores RDP/WebDAV credentials locally for connection and synchronization features. Treat debug APKs as testing builds and avoid using them with high-value production credentials until a signed release build and encrypted credential store are added.
