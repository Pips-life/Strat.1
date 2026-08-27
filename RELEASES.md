# Pips-life numbered Android release system

Pips-life uses numbered Android releases published as GitHub Releases.

## Numbering

- Release tag: `vMAJOR.MINOR.PATCH`
- Android `versionName`: same semantic version.
- Android `versionCode`: a monotonically increasing integer stored in `mobile/release.properties`.
- APK asset: `Pips-life-<versionName>-<versionCode>.apk`.
- Each release also publishes a SHA-256 checksum and `release-manifest.json`.

`versionCode` is the authoritative update number. Never reuse it.

## Publishing

For a normal release:

1. Update `mobile/release.properties` with the new `versionName` and a higher `versionCode`.
2. Commit the change.
3. Push a matching tag such as `v0.2.2`.
4. The `mobile-release.yml` workflow validates the tag against `release.properties`.
5. It verifies that the new versionCode is greater than published releases.
6. It builds a signed release APK.
7. It verifies package name, app label, version name, version code and APK signature.
8. It publishes the numbered GitHub Release and its APK/checksum/manifest.

The workflow also supports manual release creation with explicit `version_name` and `version_code` inputs.

## Signing

Production updates must use the same signing certificate every time. The release workflow therefore expects these GitHub repository secrets:

- `PIPSLIFE_KEYSTORE_BASE64`
- `PIPSLIFE_KEYSTORE_PASSWORD`
- `PIPSLIFE_KEY_ALIAS`
- `PIPSLIFE_KEY_PASSWORD`

The keystore is never committed to the repository.

## In-app updating

Release builds run `PipsLifeApplication` at launch/resume. It checks GitHub's public `releases/latest` endpoint. Drafts and prereleases are ignored.

The updater looks for the official `Pips-life-<version>-<versionCode>.apk` asset and compares the release `versionCode` with the installed Android `BuildConfig.VERSION_CODE`.

If the release is newer, Pips-life prompts the user to **DOWNLOAD & INSTALL** or **LATER**. The APK is downloaded from the GitHub Release and handed to Android's package installer. Android may require the user to allow Pips-life to install updates from this source once.

No MetaApi token, backend secret, broker password, or GitHub credential is shipped in the APK.

## First published release

The repository may have no GitHub Release yet. In that case the updater simply treats the absence of a latest release as "no update". Once the first numbered release is published, installed Pips-life builds can discover later releases automatically.
