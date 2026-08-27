# Strat.1 Android Mobile Architecture

Version: 0.1.0 (versionCode 1)

This directory is intentionally isolated from the existing trading engine. Mobile code must not import or modify `strat/` directly. The app communicates with backend APIs only.

## Architecture

- `app/` — Android client (planned Kotlin/Jetpack Compose module)
- `contracts/` — API/data contracts shared conceptually with backend
- `release/` — release metadata/update manifest

## MT5 account flow

1. User opens the MT5 tab.
2. User searches/selects a broker.
3. App displays matching MT5 servers.
4. User selects the exact server.
5. User enters account number and password.
6. Backend validates the connection; the mobile client never embeds MetaApi credentials.
7. Broker/server/account preference is remembered locally. Passwords are stored only through Android secure credential mechanisms and should not be logged.

## Updates

Production distribution should use Google Play App Bundles with Play in-app updates where possible. Direct APK distribution may use the release manifest under `mobile/release/`, but updates must preserve Android signing identity and use monotonically increasing version codes.

Version naming uses semantic versioning (`MAJOR.MINOR.PATCH`) while Android `versionCode` is strictly increasing.
