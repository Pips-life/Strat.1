# Pips-life Android releasing

The release source of truth is `mobile/release.properties` plus the exact commit being released.

Example:

```properties
versionName=0.2.14
versionCode=14
```

## Controlled production release

1. Make and test the app changes on `main`.
2. Confirm the Pips-life CI workflow is green for the exact commit.
3. Dispatch **Pips-life Release** with that commit SHA, `versionName` and `versionCode`.
4. The workflow builds a signed release APK, verifies package/version/signature, creates `v<versionName>`, and publishes the APK, checksum and manifest.
5. Never reuse a tag or `versionCode`.

## Signing

The production signing keystore is CI-only. Required repository secrets:

- `PIPS_LIFE_KEYSTORE_BASE64`
- `PIPS_LIFE_KEYSTORE_PASSWORD`
- `PIPS_LIFE_KEY_ALIAS`
- `PIPS_LIFE_KEY_PASSWORD`

The same signing key must be retained for every production update so Android can install the new APK over the previous version.

## Updater

The Android updater uses the canonical backend release gateway at `https://strat-1.vercel.app/api/app/release/latest`. It compares the returned `versionCode` with the installed app and prompts only when a newer numbered stable release exists.

GitHub credentials and backend secrets never ship in the APK.
