# Strat.1 Android App

This directory contains the Android client for Strat.1. It is intentionally isolated from the Python trading engine under `strat/`.

## Architecture

- `presentation/` — screens and navigation
- `domain/` — MT5 account models and use cases
- `data/` — backend API, secure local preferences, release/update services
- `core/` — shared app configuration and versioning

The Android client never contains MetaApi secrets or trading decision logic. Trading strategy, risk, execution and provider credentials remain backend responsibilities.

## MT5 flow

1. User enters broker name.
2. App requests matching MT5 servers from the backend.
3. User selects the exact server.
4. User enters account number and password.
5. Credentials are sent only to the authenticated backend connection service.
6. Non-secret broker/server/account preference is persisted locally; password storage is handled by secure credential storage only when explicitly required.

## Releases

`versionName` is semantic (for example `0.1.0`) and `versionCode` is strictly increasing. Release metadata is designed to support update checks. When distributed through Google Play, use Play In-App Updates; Google documents flexible and immediate update flows. Direct APK distribution can use the same release manifest with Android's package installer.

Do not decrement version codes or reuse signed release artifacts.
