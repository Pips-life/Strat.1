# Strat.1 Mobile Architecture

The mobile client is intentionally isolated under `mobile/` and does not modify the Python QOF engine under `strat/`.

## Boundaries

```text
Android UI
  ├─ Dashboard
  ├─ MT5 account setup
  ├─ Strategy 001 status
  └─ Settings / Updates
        ↓
Mobile API client
        ↓
Strat.1 backend
  ├─ MT5/MetaApi adapter
  ├─ QOF Intelligence
  ├─ QOF Structure Engine
  ├─ Confluence Engine
  ├─ Risk Engine
  └─ Execution Interface
```

The APK never contains the MetaApi service token. Broker discovery and MT5 verification are backend operations.

## MT5 flow

1. User enters broker name.
2. Backend returns matching MT5 servers.
3. User selects the exact server.
4. User enters account number and password.
5. Backend verifies the account.
6. Broker/server/account preference is persisted locally; the password is not stored in ordinary preferences.
7. User can disconnect, forget the account, or switch accounts.

The current UI contains a temporary server-discovery adapter so the screen can be built independently. It must be replaced by the authenticated backend endpoint before production use.

## Releases and updates

Release metadata will be served by a small version manifest, for example:

```json
{
  "versionName": "0.1.0",
  "versionCode": 1,
  "channel": "stable",
  "mandatory": false,
  "releaseNotes": [],
  "apkUrl": "...",
  "sha256": "..."
}
```

The app compares `versionCode` numerically. A release is newer only when the remote version code is greater than the installed version code.

Update flow:

```text
check manifest
    ↓
newer release?
    ↓ yes
show release notes
    ↓
user chooses Download
    ↓
verify HTTPS download + SHA-256
    ↓
Android PackageInstaller
    ↓
user confirmation when Android requires it
    ↓
new version installed
```

Android package installation may require user intervention; the app must not attempt to bypass Android's installation security model. Updates must retain the same package name and signing identity. Android requires compatible package/version/signing information for an upgrade.

## Versioning policy

- `versionName`: semantic release label, e.g. `1.2.0`.
- `versionCode`: monotonically increasing integer for every installable release.
- No release may reuse a previous version code.
- Stable releases and development builds use separate channels.
- Release artifacts should be signed consistently.

## Distribution

For Google Play, production publishing should use an Android App Bundle (AAB); APKs remain useful for direct testing/sideloading. The same release pipeline should produce the appropriate artifact(s).
