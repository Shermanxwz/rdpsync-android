# Privacy

RdpSync is built around local storage and user-selected infrastructure. It does not include a project-operated cloud service.

## What The App Stores Locally

- Device profile name.
- RDP host and port.
- RDP username, password, optional domain, and optional server name.
- Preferred remote desktop width and height.
- WebDAV base URL, username, and password.

RDP profiles are stored in the app's Room database. WebDAV settings are currently stored in Android SharedPreferences. The current implementation does not yet use app-level encrypted credential storage.

## What The App Sends

RdpSync sends data only for user-triggered connection and sync flows:

- RDP connection data is sent to the RDP server selected by the user.
- WebDAV credentials are sent to the WebDAV endpoint selected by the user.
- WebDAV sync uploads a JSON file named `rdpsync_devices.json` to the selected WebDAV collection.

The WebDAV JSON currently includes device profile fields needed to recreate connections on another device, including RDP username and password. Use a trusted HTTPS WebDAV server and disposable/test credentials when evaluating the app.

## What The App Does Not Include

- No analytics SDK.
- No advertising SDK.
- No crash reporting SDK.
- No project-owned telemetry endpoint.
- No hardcoded personal server, account, token, or API key.

## Recommendations

- Prefer HTTPS WebDAV URLs.
- Use test credentials for public bug reports and screenshots.
- Do not publish `rdpsync_devices.json`.
- Avoid syncing high-value production credentials until encrypted storage and optional password redaction are implemented.
