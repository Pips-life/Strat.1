# Strat.1 Android mobile architecture

The mobile client is isolated under `mobile/` so it can evolve without changing the Python strategy engine under `strat/`.

## Boundaries

- `mobile/app`: Android UI, local preferences, secure credential handling, release/update client.
- Backend/API: broker/server discovery, MT5 credential verification, MetaApi connectivity, account state, Strategy 001 intelligence and execution.
- Strategy engine: remains under `strat/` and is not imported into the Android app.
- Release service: publishes a signed APK plus a small release manifest.

## MT5 flow

1. User enters broker name.
2. App requests matching servers from the backend.
3. User selects the exact MT5 server.
4. User enters account number and password.
5. Backend verifies the connection before enabling trading functions.
6. Broker/server/account preference is persisted locally. The password is handled separately through Android Keystore-backed encryption and is never stored in ordinary preferences.

The Android Keystore protects key material from ordinary application access. citeturn0search0turn0search2

## Progressive releases

- `versionName` is human-readable (`0.1.0`, `0.2.0`, `1.0.0`).
- `versionCode` is monotonically increasing and must never decrease.
- A release manifest contains the minimum supported version, APK URL, SHA-256 and release notes.
- The app checks the manifest on startup/resume.
- If a newer compatible release exists, the app prompts the user, downloads the APK, verifies its SHA-256 and launches Android's package installer.
- Installation remains subject to Android's security/permission model.

For future Google Play distribution, the build configuration already targets Android 16/API 36, which matches the Play requirement taking effect August 31, 2026. citeturn0search4

## Release manifest shape

```json
{
  "versionName": "0.2.0",
  "versionCode": 2,
  "minimumSupportedVersionCode": 1,
  "downloadUrl": "https://.../strat1-0.2.0.apk",
  "sha256": "...",
  "releaseNotes": ["Improved MT5 connection flow"]
}
```

## Safety rule

No MT5 password, MetaApi token, broker credential, or trading secret belongs in the APK build configuration, source tree, or Git history. The mobile client receives short-lived/session-level backend results rather than backend secrets.
