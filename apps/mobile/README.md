# Strat.1 Android Mobile

This directory is the mobile client foundation for Strat.1. It is intentionally isolated from the existing Python strategy engine and backend.

## Architecture

- `app/` — Android application shell and UI.
- `domain/` — mobile-safe models and use cases.
- `data/` — API clients, local preferences and secure credential storage.
- `update/` — release manifest/update orchestration.

The mobile app must never contain the MetaApi secret. MT5 credentials are handled as sensitive data and the preferred production design is to keep broker connectivity behind the backend.

## MT5 flow

1. User opens the MT5 tab.
2. User enters/searches broker name.
3. Backend returns matching broker/server choices.
4. User selects the exact MT5 server.
5. User enters account number and password.
6. Backend verifies the connection.
7. The app remembers non-secret preferences and protects any locally retained sensitive material using Android Keystore-backed encryption.

## Releases

Release metadata is represented by a version name and monotonically increasing Android version code. The client checks the release manifest, presents release notes, downloads the signed APK, and hands installation to Android's package installer. Android security prompts are never bypassed.

Initial foundation release: `0.1.0` / versionCode `1`.
