# Pips-life Android Release System

Pips-life uses numbered Android releases published as GitHub Releases.

## Numbering

- Release tag: `vMAJOR.MINOR.PATCH` (for example `v0.2.2`).
- Android `versionName`: must match the release tag.
- Android `versionCode`: a positive integer that **must increase for every release**. It is the primary value the app uses to detect an update.
- APK asset: `pips-life-<versionName>-<versionCode>.apk`.
- Each release also publishes `release-manifest.json` with version, tag, APK name and SHA-256.

## Publishing

Create and push a tag such as `v0.2.2`, with `mobile/release.properties` containing the intended version and monotonically higher `versionCode`. The `mobile-release.yml` workflow then:

1. Sets up Java 17 and Android SDK 35.
2. Validates the tag and release numbering.
3. Stamps the Android version into the release build.
4. Builds the signed release APK with Gradle.
5. Verifies package name, app label, version name, version code and APK signature.
6. Calculates the APK SHA-256.
7. Publishes the APK, checksum and release manifest to the numbered GitHub Release.

The workflow can also be started manually when a specific version name and version code are required.

## In-app updating

Release builds run `PipsLifeApplication` at launch/resume. It checks GitHub's public `releases/latest` endpoint periodically. If the latest stable release has a higher `versionCode` and a numbered Pips-life APK asset, Pips-life prompts the user to **DOWNLOAD & INSTALL**.

The APK is downloaded through Android's `DownloadManager`, then Android's package installer is opened. Android may require the user to allow Pips-life to install updates from this source once. The app never silently installs an APK.

No MetaApi token, backend secret, broker password, or other server secret is used by the release checker.

## Current state

The release infrastructure is now consolidated around one in-app updater and one numbered mobile-release workflow. There are currently **no published GitHub Releases**, so the first release still needs to be published. With the current mobile baseline (`0.2.1`, versionCode `3`), the first release can be `v0.2.1`; the next release should use a higher versionCode (for example `0.2.2`, versionCode `4`).
