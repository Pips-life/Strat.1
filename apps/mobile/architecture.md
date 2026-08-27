# Mobile Architecture Contract

## Boundary rule

The existing `strat/` strategy-engine implementation remains untouched by the mobile foundation. The Android client consumes stable backend contracts and must not import Python strategy code or duplicate trading decisions.

```text
Android UI
  |
  +-- MT5 account/session API
  +-- Strategy dashboard API
  +-- Market/Confluence read APIs
  +-- Release manifest API
  |
Backend/API
  |
  +-- Execution Interface -> MetaApi/MT5
  +-- QOF Strategy 001
  +-- Confluence Engine
  +-- Global Risk Engine
```

## MT5 account model

`Mt5AccountPreference` contains broker display name, server, masked account number, and connection state. The password is never displayed and should not be logged. Production authentication should use a short-lived backend session/token rather than sending the MT5 password with every request.

## Broker/server discovery

The app submits a broker search term to the backend. The backend owns provider-specific discovery and normalization. The mobile UI renders a list of normalized servers with fields such as broker, server name, environment (demo/live), region, and provider identifier.

## Local persistence

Non-secret preferences may use ordinary app persistence. Secrets require Android Keystore-backed encryption; Android documents that Keystore key material is protected and not directly accessible as plaintext. The app should also support biometric/device-credential gating where appropriate.

## Update contract

The release manifest contains a monotonically increasing `versionCode`, semantic `versionName`, minimum supported version, signed APK URL, SHA-256 digest, release notes, and optional mandatory-update flag. The app compares its installed version code to the manifest before offering an update.

Downloaded packages are verified and passed to Android's package installer. The app does not attempt to bypass Android installation/security controls.
