# Strat.1 Release Update Contract

## Manifest

The mobile client checks the stable release manifest on launch and at a controlled background interval.

```json
{
  "channel": "stable",
  "versionName": "0.1.1",
  "versionCode": 2,
  "minimumSupportedVersionCode": 1,
  "releaseNotes": ["..."],
  "downloadUrl": "https://...",
  "sha256": "...",
  "updatePolicy": {
    "checkOnLaunch": true,
    "promptBeforeInstall": true,
    "forceUpdate": false
  }
}
```

## Rules

- `versionCode` must always increase.
- The APK/AAB must be signed with the same release identity for updates.
- The client compares the installed version code with the manifest version code.
- A newer release is downloaded only after user approval unless `forceUpdate` is explicitly required.
- Downloads must be integrity-checked using the published SHA-256 before installation.
- Google Play distribution should use Play in-app updates; direct APK distribution uses the manifest as the fallback update channel.
