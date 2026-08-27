# Pips-life Android Release System

Pips-life uses GitHub Releases as its public Android update channel.

## Release numbering

Every Android release has two numbers:

- `versionName`: user-facing semantic version, for example `0.2.1`.
- `versionCode`: Android upgrade number, for example `3`.

The release is tagged `v<versionName>` and published as:

`Pips-life <versionName> (<versionCode>)`

The APK asset is named:

`Pips-life-<versionName>-<versionCode>.apk`

`versionCode` must always increase for an installable upgrade.

## Publishing a release

1. Update `mobile/app/build.gradle.kts` with the new `versionName` and a higher `versionCode`.
2. Commit the change to `main`.
3. Create and push a matching tag, for example `v0.2.2`.
4. GitHub Actions runs `.github/workflows/android-release.yml`.
5. The workflow builds and signs/verifies the APK, then creates the GitHub Release and attaches the numbered APK.

The workflow refuses a tag whose version does not match `versionName`.

## In-app update behavior

On application startup, Pips-life calls the public GitHub `releases/latest` endpoint. It only considers a release newer than the installed `versionName`, and it requires an APK asset whose download URL is on `github.com`.

When a newer release is found, the app prompts the user:

**Pips-life update available → DOWNLOAD & INSTALL**

The APK is downloaded with Android's DownloadManager. Android's package installer is then opened. On Android versions that require it, the user must allow Pips-life to install packages from this source.

The app does not silently install updates; Android requires user confirmation for normal APK installation.

## Security boundaries

- GitHub release checks require no GitHub token.
- MetaApi/backend secrets are never embedded in the APK.
- Update URLs are accepted only from `https://github.com/`.
- Release assets are APKs produced by the repository's GitHub Actions workflow.
