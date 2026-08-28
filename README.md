# Pips-life — Strat.1

`Pips-life/Strat.1` is the single source of truth for the Pips-life trading system: QOF Strategy 001, backend/API, Android application, tests, release system and production deployment.

## Canonical architecture

```text
Pips-life/Strat.1
├── strat/                 # QOF Strategy 001 and reusable trading engine
├── tests/                 # strategy and system tests
├── app/                   # canonical Next.js backend/API
├── lib/                   # canonical backend support libraries
├── mobile/                # canonical Pips-life Android app
├── docs/                  # design and operating documentation
├── .github/workflows/     # CI and numbered release automation
├── package.json           # canonical Next.js project
├── pyproject.toml         # canonical Python package
└── vercel.json            # canonical Vercel deployment configuration
```

There is intentionally one repository and one production application. The strategy engine remains independent from hosting, mobile UI, release infrastructure and execution adapters.

## Strategy

QOF (Quantitative Options Flow) is Strategy 001. QOF-derived structures are the primary market map; conventional price structure is secondary confirmation only. The reusable Confluence Engine evaluates independent evidence streams, while risk and execution remain separate layers.

The strategy-engine implementation under `strat/` is the authoritative trading logic and must not be duplicated in application or deployment code.

## Backend

The root Next.js application under `app/` is the canonical backend. It provides MT5 connection/server discovery, account state, bot-control forwarding and the GitHub-release gateway used by the Android updater.

Production deployment is the single Vercel project associated with `Pips-life/Strat.1`. The canonical production hostname is `https://strat-1.vercel.app`.

Required server-side secrets are never embedded in the APK.

## Android app

The Android application is under `mobile/`. It connects to the canonical backend, supports MT5 account configuration, and uses the canonical GitHub Releases feed through the backend release gateway.

The updater compares the release `versionCode` with the installed version. A newer stable numbered release produces an update prompt; installation is handled by Android's package installer.

## Releases

Production Android releases are numbered with:

- Git tag: `vMAJOR.MINOR.PATCH`
- `versionName`: matching semantic version
- `versionCode`: monotonically increasing Android integer
- APK: `pips-life-<versionName>-<versionCode>.apk`

The **Pips-life Release** workflow is the only production release workflow. It builds and signs the APK with the permanent release key, verifies the package and signature, generates a SHA-256 manifest, and publishes the numbered GitHub Release.

The latest published release is currently `v0.2.13` (versionCode `13`); `main` is prepared at `0.2.14` / versionCode `14` for the next intentional release.

## CI

The **Pips-life CI** workflow is the single continuous validation workflow. It runs Python tests, the canonical Next.js build, and an Android debug build/metadata check.

Replay data is for development/testing only. Production trading remains dependent on live provider integrations and explicit execution/risk controls.

## Rule

Do not create parallel repositories, duplicate application backends, duplicate release tracks, alternate strategy implementations or ad-hoc deployment workflows. Changes should be made in this repository and validated here before release.
