# Pips-life Android release system

Pips-life uses GitHub Releases as the official Android distribution channel.

## Numbering

Every release has two numbers:

- `versionName`: semantic version, for example `0.2.1`
- `versionCode`: monotonically increasing Android integer, for example `3`

The GitHub release tag is `v<versionName>`, so `0.2.1` is published as `v0.2.1`.

APK assets are named `Pips-life-<versionName>-<versionCode>.apk`.

## Publishing

1. Change `versionName` and increment `versionCode` in `mobile/app/build.gradle.kts`.
2. Run the **Pips-life Android Release** GitHub Actions workflow manually with the same version, or push a matching `vMAJOR.MINOR.PATCH` tag.
3. The workflow builds a release APK with Gradle, verifies the APK signature, and publishes it to the GitHub Release.
4. Do not reuse a `versionCode` or release tag.

## In-app updates

The Android application checks:

`https://api.github.com/repos/Pips-life/Strat.1/releases/latest`

at application startup. It only considers a release newer when its semantic `versionName` is greater than the installed version. It then uses the APK attached to that GitHub Release.

The user receives a Pips-life update dialog with **LATER** and **DOWNLOAD & INSTALL**. The latter downloads the release APK using Android DownloadManager and opens Android's package installer. Android may require the user to allow Pips-life to install unknown-app updates once.

Secrets such as MetaApi credentials are never placed in the APK or the release metadata.

## Important

A GitHub Release is the source of truth for user-facing Android updates. CI artifacts are not treated as releases and are not used by the in-app updater.
