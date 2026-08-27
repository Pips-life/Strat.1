# Strat.1 Android App

Version: `0.1.0`  
Android versionCode: `1`

This directory is an isolated Android client. It does **not** contain or modify the Strategy 001 engine.

## Architecture

- `app/` — Android UI and presentation layer
- `core/network/` — backend API client contracts
- `core/security/` — secure credential storage boundary
- `core/update/` — release/update checking boundary
- `feature/mt5/` — MT5 broker/server/account flow

The Android client is intentionally thin. Strategy logic, MetaApi credentials, execution, risk controls, and market intelligence remain server-side.

## MT5 flow

1. User searches broker name.
2. App displays matching MT5 servers.
3. User selects the exact server.
4. User enters account number and password.
5. Credentials are sent only through the authenticated backend connection.
6. Broker/server preference is remembered locally.
7. Password is never stored in ordinary preferences.

## Release/update model

Releases use semantic versions plus monotonically increasing Android `versionCode` values. Example: `0.1.0 (1)`, `0.2.0 (2)`, `1.0.0 (100)`.

The update client will check a signed release manifest, show release notes, download an approved APK, verify integrity, and hand installation to Android's package installer. It will never attempt to bypass Android installation/security controls.
