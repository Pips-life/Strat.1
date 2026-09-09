# Pips-life — Strat.1

`Pips-life/Strat.1` is the single source of truth for the Pips-life trading system.

## New architecture

The trading bot is now designed as a **platform-neutral direct MetaApi system**. Vercel and Render are not runtime dependencies.

```text
Pips-life/Strat.1
├── strat/                 # canonical Python strategy engine
├── runner/                # standalone Node + MetaApi execution runtime
├── app/                   # optional local/web control UI
├── lib/                   # backend support libraries
├── mobile/                # Android application
├── tests/                 # tests
├── docs/                  # design and operating documentation
└── .github/workflows/     # CI and release automation
```

The runner connects directly to MetaApi using the official SDK streaming API. MetaApi maintains the live terminal state, market-data subscriptions and trade execution; no Render proxy or Vercel control plane is required. MetaApi's streaming API is specifically intended for automated trading and real-time market data. 

## Independent strategies

Strategies are plugins with stable IDs and independent runtime state. The canonical registry currently exposes:

- **001 — QOF Strategy 001**: canonical Python QOF engine.
- **002 — Velocity Expansion**: tick-event JavaScript MetaApi execution adapter.

Selecting one strategy does not alter another account/runtime. Strategy IDs are routing/selection data, never credentials.

The Python strategy contract remains the source of truth under `strat/strategies/`; the registry already provides independent construction by strategy ID.

## Real market watchlist

The watchlist is now a broker/MetaApi data feature rather than a UI-only list. The direct runner subscribes to selected symbols and exposes their current bid, ask, spread, pip size and subscription state. Symbols can be selected by the user at runtime.

## Security

`METAAPI_TOKEN` stays outside source control. It must be supplied to the runner environment. The Android application must not contain the platform MetaApi token.

## Running the bot

```bash
cd runner
npm install
METAAPI_TOKEN="..." METAAPI_ACCOUNT_ID="..." npm start
```

The runner exposes `/health`, `/strategies`, `/state`, `/watchlist`, and `/control`.

## Deployment

There is intentionally **no Vercel configuration and no Render configuration** in this architecture. The runner is portable: local machine, VPS, Docker, or another generic Node process supervisor can host it.

## Releases

Android releases remain numbered with `vMAJOR.MINOR.PATCH`, matching `versionName`, a monotonically increasing `versionCode`, and a signed APK.
