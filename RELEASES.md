# Pips-life release system

Pips-life uses numbered Android releases.

## Numbering

Every release has both:

- `versionName`: human-facing semantic version, e.g. `0.2.1`
- `versionCode`: monotonically increasing Android build number, e.g. `3`

The source of truth is `mobile/release.properties`.

Release tags must use `vMAJOR.MINOR.PATCH`, for example `v0.2.1`.

The release workflow validates that the tag version matches `mobile/release.properties`, builds a signed APK named:

`Pips-life-<versionName>-<versionCode>.apk`

and publishes that APK plus a SHA-256 checksum to the GitHub Release.

## Automatic app updates

The Android app checks the public GitHub Releases `latest` endpoint after the main screen is resumed. It compares the APK's numbered `versionCode` with its installed `BuildConfig.VERSION_CODE`.

If a newer release exists, the app prompts the user with **DOWNLOAD & INSTALL** or **LATER**. The APK is downloaded from the GitHub Release asset and Android's package installer performs the user-approved installation.

The app never contains the MetaApi token or backend secrets.

## Required GitHub Actions secrets

Before publishing signed releases, configure these repository secrets:

- `PIPSLIFE_KEYSTORE_BASE64`
- `PIPSLIFE_KEYSTORE_PASSWORD`
- `PIPSLIFE_KEY_ALIAS`
- `PIPSLIFE_KEY_PASSWORD`

The same release keystore must be used for every production release so Android accepts the APK as an update to the installed app.

## Publishing a release

1. Update `mobile/release.properties` with a new semantic version and a higher `versionCode`.
2. Commit the change.
3. Create and push a matching tag, for example `v0.2.2`.
4. GitHub Actions builds and verifies the signed APK.
5. Only after the build succeeds does the workflow publish the numbered GitHub Release.
6. Installed Pips-life builds will discover the new release automatically.

Do not reuse a versionCode. Do not publish an APK manually outside the release workflow.
