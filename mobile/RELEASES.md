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

The Android application checks:

`https://api.github.com/repos/Pips-life/Strat.1/releases/latest`

when the app resumes, throttled to avoid unnecessary requests. Draft/prerelease releases are ignored. A GitHub-hosted APK with a higher numbered release is offered to the user.

The user receives a Pips-life update dialog with **LATER** and **DOWNLOAD & INSTALL**. The latter downloads the release APK using Android DownloadManager and opens Android's package installer. Android may require the user to allow Pips-life to install updates once. Choosing **LATER** does not accept or permanently suppress the release.

Secrets such as MetaApi credentials, backend keys and signing keys are never placed in the APK or release metadata.

## Source of truth

A GitHub Release is the source of truth for user-facing Android updates. CI artifacts are not treated as releases and are not used by the in-app updater.
