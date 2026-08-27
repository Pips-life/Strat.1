# Pips-life Android release system

Pips-life uses two release numbers:

- `versionName`: semantic version shown to users, e.g. `0.2.1`.
- `versionCode`: Android's monotonically increasing integer, e.g. `3`.

The source of truth is `mobile/release.properties`.

## Creating the next release

1. Update `mobile/release.properties`, for example:

```properties
versionName=0.2.2
versionCode=4
```

2. Commit the version change and app changes.
3. Create and push the matching tag:

```text
v0.2.2
```

4. The `android-release.yml` workflow builds a signed release APK, verifies package name, version, Pips-life label and APK signature, then publishes the APK to the GitHub Release.

## Signing secrets

The production signing keystore must never be committed. Configure these GitHub Actions repository secrets once:

- `PIPSLIFE_KEYSTORE_BASE64` — base64 encoded production `.jks`/`.keystore` file.
- `PIPSLIFE_KEYSTORE_PASSWORD`
- `PIPSLIFE_KEY_ALIAS`
- `PIPSLIFE_KEY_PASSWORD`

The same keystore must be used for every production release. Changing it would prevent Android from installing an update over the previous release.

## In-app update flow

The Android app checks the public GitHub `releases/latest` endpoint when the app is resumed. It compares the latest release `versionName` with the installed `BuildConfig.VERSION_NAME`.

When a newer stable numbered release contains an APK asset, Pips-life prompts the user. The user can choose **DOWNLOAD & INSTALL**. Android's package installer then handles installation; on Android 8+ the user may first need to allow Pips-life to install packages from this source.

The app does not embed the MetaApi token or backend secrets in this update mechanism.

## Important

Do not use debug APKs as production releases. Debug signing is not stable across GitHub runners and would break seamless updates. Production GitHub Releases must be built and signed by `android-release.yml` using the permanent Pips-life release keystore.
