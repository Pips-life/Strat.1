# Strat.1 Android Mobile Architecture

This directory is the mobile client boundary. It is intentionally isolated from the Python trading engine under `strat/` and `tests/`.

## Principles

- The mobile app is a client, not the trading engine.
- No MetaApi token, broker secret, or server-side strategy logic is bundled in the APK.
- MT5 credentials are handled through a secure credential boundary and are never logged.
- Strategy 001 remains authoritative in the existing backend/research code.
- Mobile releases are independently versioned and progressively numbered.
- Every release must pass mobile tests before an APK is promoted.

## Planned Android modules

```text
mobile/
  android/
    app/                  # Android application
    core/network/         # Backend API client
    core/security/        # Android Keystore-backed credential storage
    core/update/          # Release manifest + update flow
    feature/mt5/          # Broker/server/account UX
    feature/dashboard/    # Trading dashboard
    feature/settings/     # Preferences and account management
```

## MT5 flow

```text
Broker name
    -> broker/server search API
    -> exact MT5 server selection
    -> account number + password
    -> secure save / connect
    -> backend verification
    -> masked account status
```

The app remembers broker/server/account preferences. Passwords are stored only through the Android secure credential layer when the user explicitly enables remembering credentials.

## Update flow

```text
App launch / periodic check
    -> release manifest
    -> compare versionCode
    -> verify release metadata / checksum
    -> prompt user
    -> download APK
    -> Android PackageInstaller / permitted installer flow
    -> user confirmation when Android requires it
    -> install newer signed APK
```

A release must use the same application ID and signing identity and have a monotonically increasing `versionCode`. Android requires these conditions for an in-place update. Direct APK installation may require user approval; silent installation is not assumed.

## Version policy

- `versionName`: semantic release label, e.g. `0.1.0`, `0.2.0`, `1.0.0`.
- `versionCode`: monotonically increasing integer, e.g. `1`, `2`, `3`, `100`, `101`.
- Never reuse a released versionCode.
- Release artifacts are named with both versionName and versionCode.

## Release manifest

The update service will expose a signed/validated JSON manifest containing:

```json
{
  "versionName": "0.1.0",
  "versionCode": 1,
  "minimumSupportedVersionCode": 1,
  "mandatory": false,
  "releaseNotes": [],
  "apkUrl": "...",
  "sha256": "..."
}
```

The APK URL and checksum are untrusted input until validated. The updater must reject downgrades, mismatched application IDs, incompatible signatures, and failed checksum verification.

## Current milestone

`0.1.0 / versionCode 1` is the mobile foundation milestone. It is separate from Strategy 001 and does not modify the strategy implementation.
