# Pips-life Android release system

Pips-life uses GitHub Releases as the official Android distribution channel.

## Numbering

Every production release has two numbers:

- `versionName`: semantic version, for example `0.2.2`
- `versionCode`: monotonically increasing Android integer, for example `4`

The GitHub release tag is `v<versionName>`, so `0.2.2` is published as `v0.2.2`.

APK assets are named `Pips-life-<versionName>-<versionCode>.apk`.

## Publishing

1. Update `mobile/release.properties` with the next `versionName` and a higher `versionCode`.
2. Commit that change to `main`.
3. Create and push the matching `vMAJOR.MINOR.PATCH` tag.
4. The **Pips-life Android Release** GitHub Actions workflow builds the signed release APK, verifies its package, app label, version and signature, and publishes it to the GitHub Release.
5. Never reuse a release tag or `versionCode`.

The workflow also supports manual dispatch for a controlled build check. Production releases remain tag-based so the source commit, version metadata and release are unambiguous.

## In-app updates

`Strat.1` is a **private GitHub repository**, so the APK must never contain a GitHub credential. GitHub Releases remain the source of truth, but the app reaches them through the Pips-life backend release gateway:

- `GET /api/app/release/latest` reads the latest private GitHub Release using the server-only `GITHUB_RELEASE_TOKEN`.
- `GET /api/app/release/download?asset=<id>` streams the selected GitHub release APK through the backend.

The Android application checks the gateway when the app first resumes. If the release `versionCode` is higher than the installed version and an APK asset exists, the user receives **Pips-life update available** with **LATER** and **DOWNLOAD & INSTALL**.

The latter downloads the GitHub release APK using Android DownloadManager and opens Android's package installer. Android may require the user to allow Pips-life to install updates once. Choosing **LATER** does not accept or permanently suppress the release.

## Signing

Direct APK updates require one persistent release signing key. Configure these GitHub repository secrets before publishing releases:

- `PIPSLIFE_KEYSTORE_BASE64`
- `PIPSLIFE_KEYSTORE_PASSWORD`
- `PIPSLIFE_KEY_ALIAS`
- `PIPSLIFE_KEY_PASSWORD`

The workflow refuses to publish an APK if these secrets are missing. This prevents a new signing key from accidentally breaking in-place updates.

The backend also requires a server-only `GITHUB_RELEASE_TOKEN` with read access to the private repository's Releases/Contents data. Never put this token in the Android APK.

## Source of truth

The GitHub Release is the user-facing Android release. CI artifacts are not treated as releases and are not used by the in-app updater.
