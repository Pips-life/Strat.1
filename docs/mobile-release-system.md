# Pips-life Android Release System

Pips-life uses numbered Android releases and GitHub Releases as the distribution channel.

## Release numbering

Every release has:

- `versionName`: semantic version such as `0.2.1`.
- `versionCode`: monotonically increasing Android build number.
- Git tag: `v<versionName>`.
- GitHub Release title: `Pips-life <versionName> (<versionCode>)`.
- APK asset: `pips-life-<versionName>-<versionCode>.apk`.
- SHA-256 asset beside the APK.

The next release must use a higher `versionCode` than the installed release.

## Publishing

The workflow is `.github/workflows/mobile-release.yml`.

A numbered release can be produced by either:

1. Pushing a semantic-version tag such as `v0.2.2`; or
2. Running **Pips-life Android Release** manually with an explicit `version_name` and higher `version_code`.

The workflow installs Android SDK/build tools, installs Gradle 8.9, verifies the Android project, builds the APK, verifies package/version metadata, uploads the APK and SHA-256 checksum to GitHub Releases, then verifies that the release exists.

## In-app update flow

On app foreground, `PipsLifeApplication` checks the public GitHub Releases API. It compares the latest release version with `BuildConfig.VERSION_NAME` and only prompts when the GitHub release is newer and contains an APK asset.

The user sees:

**Pips-life update available** → **DOWNLOAD & INSTALL** / **LATER**

The APK is downloaded with Android `DownloadManager`. Android's package installer is then opened using a `FileProvider` URI. On Android 8+, Pips-life requests the user to allow installs from this source if that permission is not already enabled.

No GitHub token or MetaApi secret is embedded in the APK.

## Signing requirement

For seamless upgrades over an already-installed Pips-life APK, release APKs must be signed with the same persistent Android signing key. The current repository workflow establishes the release/update plumbing; production signing credentials should be supplied through GitHub Actions secrets before publishing signed production releases.

Do not commit a keystore, signing password, MetaApi token, or backend API secret to the repository.
