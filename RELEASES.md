# Pips-life Android Release System

Pips-life uses numbered Android releases published as GitHub Releases.

## Numbering

- Release tag: `vMAJOR.MINOR.PATCH`
- Android `versionName`: same semantic version.
- Android `versionCode`: derived monotonically from the semantic version for tag-triggered releases (`major * 1,000,000 + minor * 1,000 + patch`).
- APK asset: `pips-life-<versionName>-<versionCode>.apk`.
- Each release also publishes `release-manifest.json` containing version, tag, APK name and SHA-256.

## Publishing

Create a tag such as `v0.2.2` and push it. The `mobile-release.yml` workflow then:

1. Sets up Java 17 and Android SDK 35.
2. Verifies the version format.
3. Stamps the Android version into the release build.
4. Builds the release APK with Gradle.
5. Verifies package name, version name and version code.
6. Calculates the APK SHA-256.
7. Creates the numbered GitHub Release and uploads the APK plus release manifest.

The workflow can also be started manually when a specific version name and version code are required.

## In-app updating

Release builds run `PipsLifeApplication` at launch. It checks GitHub's public `releases/latest` endpoint. If the latest semantic version is newer than the installed version and contains an APK asset, Pips-life prompts the user to **DOWNLOAD & INSTALL**. The APK is downloaded through Android's `DownloadManager`, then Android's package installer is opened. Android may require the user to allow Pips-life to install unknown-source updates once.

No MetaApi token, backend secret, broker password, or other server secret is used by the release checker.

## First release

The repository currently has no published GitHub Release, so a first numbered release must be published before an installed app can detect an update. The release checker treats GitHub's 404/no-release state as "no update" rather than an app error.
