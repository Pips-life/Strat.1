# Pips-life Android Release System

Pips-life uses numbered GitHub Releases as its public Android update channel.

## Release numbering

Every Android release has two numbers:

- `versionName`: user-facing semantic version, e.g. `0.2.2`.
- `versionCode`: monotonically increasing Android upgrade number, e.g. `4`.

The canonical version source is `mobile/release.properties`.

The release tag is `v<versionName>` and the APK is named `pips-life-<versionName>-<versionCode>.apk`.

The current mobile baseline is **0.2.1 (3)**. The next installable release must use a higher versionCode, such as **0.2.2 (4)**.

## Publishing

The single Android release workflow is `.github/workflows/mobile-release.yml`.

It runs when a matching `vMAJOR.MINOR.PATCH` tag is pushed, or manually from GitHub Actions with a version name and version code. It installs JDK 17, Android SDK 35 and Gradle 8.10.2, builds the signed release APK, verifies package/version/signature, and creates the matching numbered GitHub Release with the APK, checksum and release manifest attached.

There is deliberately one release workflow so competing Android release workflows do not publish different APKs for the same version.

## In-app update behavior

`PipsLifeApplication` checks GitHub's public `releases/latest` endpoint when the main activity resumes. It compares the latest semantic version with the installed `versionName` and only accepts an APK asset whose download URL is on `https://github.com/`.

If a newer release exists, the app prompts:

**Pips-life update available → DOWNLOAD & INSTALL**

The APK is downloaded with Android's DownloadManager and Android's package installer is opened. The app never silently installs an update; Android requires user confirmation. On Android versions that require it, the user must allow Pips-life to install packages from this source.

## Release signing

Android only accepts an update when the new APK is signed by the same signing key as the installed release. GitHub Actions therefore expects these repository secrets:

- `PIPS_LIFE_KEYSTORE_BASE64`
- `PIPS_LIFE_KEYSTORE_PASSWORD`
- `PIPS_LIFE_KEY_ALIAS`
- `PIPS_LIFE_KEY_PASSWORD`

The keystore is never committed to the repository.

## Release integrity checklist

A release is considered complete only after:

1. GitHub Actions is green.
2. The APK exists and is non-empty.
3. Package ID, versionName and versionCode match the release.
4. APK signature verification passes.
5. The GitHub Release contains the numbered APK.
6. The APK can install as an upgrade over the previous Pips-life release signed with the same key.
