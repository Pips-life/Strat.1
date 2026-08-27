# Pips-life Android Release System

Pips-life uses GitHub Releases as the single source of truth for mobile updates.

## Release numbering

Every Android release has two numbers:

- `versionName`: semantic release number, e.g. `0.2.1`
- `versionCode`: monotonically increasing Android build number, e.g. `3`

The APK is named `pips-life-<versionName>-<versionCode>.apk` and each release is tagged `v<versionName>`.

`mobile/release.properties` is the repository version source of truth for tag-triggered releases.

## How a release is published

1. Update `mobile/release.properties` to the next version and versionCode.
2. Create/push the matching tag, for example `v0.2.2`.
3. GitHub Actions runs `.github/workflows/mobile-release.yml`.
4. The workflow installs JDK/Android SDK/Gradle, validates the project, signs the APK with the protected Pips-life release keystore, verifies package/name/version/signature, computes SHA-256, and publishes the APK to the numbered GitHub Release.
5. The workflow verifies that the published release contains exactly one APK.

The workflow can also be started manually with a versionName and versionCode; it publishes the resulting numbered release at the selected commit.

## Required GitHub Actions secrets

The release workflow intentionally refuses to publish an unsigned APK. Configure these repository secrets:

- `PIPS_LIFE_KEYSTORE_BASE64`
- `PIPS_LIFE_KEYSTORE_PASSWORD`
- `PIPS_LIFE_KEY_ALIAS`
- `PIPS_LIFE_KEY_PASSWORD`

The same signing key must be used for every production release so Android can install an update over the previous Pips-life APK.

The keystore is never committed to the repository.

## In-app update flow

The Android app's `PipsLifeApplication` checks:

`https://api.github.com/repos/Pips-life/Strat.1/releases/latest`

on the first activity resume of an app process. It ignores draft/prerelease releases, reads the exact Version/VersionCode metadata from the release notes, locates the APK asset, compares it with the installed build, and prompts the user only when a newer release exists.

The user chooses **Download & install**. Android's package installer performs the final installation; Pips-life does not silently install software.

If Android requires permission for installing packages from this source, the app opens the system setting for Pips-life so the user can allow it and retry.

## Safety boundaries

- Updates come from published GitHub Release APK assets, not branch files.
- Release APKs are signed with the stable production signing key.
- Package name is verified as `life.pipslife.mobile`.
- Application label is verified as `Pips-life`.
- VersionName and versionCode are verified before publication.
- SHA-256 is published beside every APK.
- Strategy 001 engine code is not part of the release mechanism and is not modified by it.
