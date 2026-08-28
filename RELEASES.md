# Pips-life Android release system

GitHub Releases are the only production Android distribution channel for `Pips-life/Strat.1`.

## Numbering

- `versionName`: semantic version, for example `0.2.14`.
- `versionCode`: positive Android integer that increases for every production release.
- Git tag: `v<versionName>`.
- APK: `pips-life-<versionName>-<versionCode>.apk`.
- Each release publishes the APK, its `.sha256` sidecar and `release-manifest.json`.

The current published release is `v0.2.13` / versionCode `13`. The development baseline on `main` is `0.2.14` / versionCode `14`.

## Publishing

The single **Pips-life Release** workflow is manually dispatched with the exact tested commit SHA, version name and version code. It builds the signed APK, verifies package metadata and the permanent signing key, creates the numbered Git tag, and publishes the GitHub Release.

The release workflow is the only production Android release workflow. Debug APKs are CI artifacts only and are never used by the updater.

## In-app updating

Because the repository is private, the Android app does not contain GitHub credentials. It calls the canonical production backend at `https://strat-1.vercel.app`:

- `GET /api/app/release/latest` reads the latest GitHub Release with the server-only `GITHUB_RELEASE_TOKEN`.
- `GET /api/app/release/download?asset=<id>` streams the selected APK.

The app checks the gateway on resume. A higher `versionCode` causes the normal Pips-life update prompt. The APK is downloaded with Android DownloadManager and handed to Android's package installer.

## Signing

The same permanent release key must be used for every production release. Configure these GitHub repository secrets once:

- `PIPS_LIFE_KEYSTORE_BASE64`
- `PIPS_LIFE_KEYSTORE_PASSWORD`
- `PIPS_LIFE_KEY_ALIAS`
- `PIPS_LIFE_KEY_PASSWORD`

Never commit the keystore or server secrets.
