# Pips-life Android Release System

Pips-life uses numbered GitHub Releases as its public Android update channel.

## Release numbering

Every Android release has two numbers:

- `versionName`: user-facing semantic version, e.g. `0.2.2`.
- `versionCode`: monotonically increasing Android upgrade number.

The release tag is `v<versionName>` and the APK is named `Pips-life-<versionName>-<versionCode>.apk`.

The current mobile baseline is **0.2.1 (3)**. The next installable build must use a higher versionCode.

## Publishing

The single release workflow is `.github/workflows/android-release.yml`.

It runs when a `vMAJOR.MINOR.PATCH` tag is pushed, or manually from GitHub Actions with a version. It installs JDK 17, Android SDK 35 and Gradle 8.10.2, builds the release APK, verifies the APK signature and metadata, and creates the matching numbered GitHub Release with the APK attached.

There is deliberately only one Android release workflow so a release cannot be published twice by competing workflows.

## In-app update behavior

On application startup, Pips-life checks the public GitHub `releases/latest` endpoint. It compares the latest semantic version with the installed `versionName` and only accepts an APK asset whose download URL is on `https://github.com/`.

If a newer release exists, the app prompts:

**Pips-life update available → DOWNLOAD & INSTALL**

The APK is downloaded with Android's DownloadManager and Android's package installer is opened. The app never silently installs an update; Android requires user confirmation. On Android versions that require it, the user must allow Pips-life to install packages from this source.

## Release integrity

A release is considered complete only after:

1. GitHub Actions is green.
2. The APK exists and is non-empty.
3. Package ID, versionName and versionCode match the release.
4. APK signature verification passes.
5. The GitHub Release contains the numbered APK.
6. The APK can install as an upgrade over the previous signed Pips-life build.

The production signing key must remain private and must never be committed to the repository.
