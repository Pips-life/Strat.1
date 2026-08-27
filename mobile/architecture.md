# Mobile Architecture Contract

## Isolation rule

The mobile client is an adapter around the existing trading platform. It must not import, copy, or rewrite Strategy 001 implementation. Backend APIs expose stable contracts to the app.

## Layers

`UI -> ViewModel/State -> Domain services -> Repository interfaces -> API/security/update implementations`

### MT5 domain

`BrokerSearchQuery`
- brokerName

`Mt5Server`
- id
- brokerName
- serverName
- environment (demo/live)
- region (optional)

`Mt5AccountPreference`
- brokerId
- serverId
- maskedAccountNumber
- lastConnectedAt

`Mt5ConnectionRequest`
- serverId
- accountNumber
- password (transient only)

`Mt5ConnectionStatus`
- disconnected
- connecting
- connected
- failed

## Credential rules

- Account number may be remembered in masked form.
- Broker/server preference may be remembered.
- Password must not be stored in SharedPreferences/plain files.
- Use Android Keystore-backed encryption for any locally retained secret material. Android documents that Keystore key material can be protected and is not accessible as plaintext outside the secure key system. citeturn0search1turn0search3
- Backend authentication must use TLS.
- Logs must redact account numbers and credentials.

## Update contract

The app polls a release manifest endpoint at startup and at a bounded interval while active.

Manifest fields:

- versionName
- versionCode
- minimumSupportedVersionCode
- releaseNotes
- apkUrl
- sha256
- publishedAt
- mandatory

Update sequence:

`check -> compare versionCode -> prompt -> download -> verify SHA-256 -> Android package installer -> restart -> report installed version`

VersionCode must always increase. A release must never reuse a previous versionCode.

For Google Play distribution, Play's update mechanism should be preferred. Android's current Play requirements state that new apps and updates submitted from August 31, 2026 must target API 36 or higher. citeturn0search9

## Backend boundary

The client will consume endpoints conceptually shaped as:

- `GET /api/mt5/brokers?query=`
- `GET /api/mt5/brokers/{brokerId}/servers`
- `POST /api/mt5/connect`
- `GET /api/mt5/account`
- `POST /api/mt5/disconnect`
- `GET /api/mobile/releases/latest`

These are contracts only at this stage. Existing Strategy 001 code remains untouched.
