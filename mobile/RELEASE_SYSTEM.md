# Pips-life Android release system

Pips-life uses numbered GitHub Releases for mobile updates.

## Versioning

`mobile/release.properties` is the release source of truth:

```text
versionName=0.2.1
versionCode=3
```

- `versionName` is the user-visible semantic version.
- `versionCode` is the Android install/upgrade sequence and must always increase.
- GitHub release tags must exactly match `v<versionName>` (for example `v0.2.2`).

## Publishing

Create a tag such as `v0.2.2` only after updating `mobile/release.properties` to the matching version and higher versionCode. The `Pips-life Android Release` workflow then:

1. validates the tag and release number;
2. builds the release APK;
3. verifies that the APK exists and is non-empty;
4. publishes a numbered GitHub Release;
5. attaches the APK to that release.

No APK is published when the build or release-number validation fails.

## In-app updater

The launcher starts `UpdateGateActivity`. It checks GitHub's latest published release. If the release version is newer than the installed app, Pips-life prompts the user to download and install the APK attached to that official GitHub release. The APK is downloaded into the app's private external files area and installed through Android's package installer using a `FileProvider` URI.

If GitHub is unavailable, or there is no newer release, the trading UI opens normally. Update checking never blocks access to the app.

The updater contains no backend or MetaApi secrets.
