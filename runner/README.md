# Pips-life Direct MetaApi Runner

This runner is platform-neutral. It does **not** require Vercel or Render.

## Required environment

- `METAAPI_TOKEN` — MetaApi API token; never commit it.
- `METAAPI_ACCOUNT_ID` — optional default MetaTrader account id.
- `PIPSLIFE_BOT_CONTROL_TOKEN` — optional bearer token for runner control.
- `PIPSLIFE_WATCHLIST` — comma-separated broker symbols, default `XAUUSD`.
- `PIPSLIFE_EXECUTION_VOLUME` — order volume.
- `PIPSLIFE_LIVE_TRADING_ENABLED` — `true`/`false`.
- `PIPSLIFE_STRATEGY002_TRAIL_PIPS` — Strategy 002 trail distance.

## Control API

- `GET /health` — runner and direct MetaApi status.
- `GET /strategies` — independent strategies available to the user.
- `GET /state?accountId=...` — per-account runtime state.
- `GET /watchlist?accountId=...` — live broker quotes.
- `POST /control` — `select`, `start`, `stop`, or `watchlist` actions.

The watchlist is sourced from the connected MetaApi terminal and its live quote state; it is not a decorative hard-coded list. Strategy state is isolated per account runtime.

Strategy 001 remains the canonical Python QOF engine. Strategy 002 is the JavaScript tick-stream MetaApi execution adapter. The redesign deliberately does not fake a JavaScript port of QOF.

## Architecture

```text
Android / local UI
        |
        v
Direct MetaApi runner (Node)
        |
        +--> Strategy 001 selection -> canonical Python QOF engine
        +--> Strategy 002 selection -> tick-event MetaApi adapter
        +--> User watchlist -> MetaApi terminal symbols + live prices
        |
        v
MetaApi -> MT5
```

There is no Vercel control plane and no Render-specific configuration. The runner can be started locally, on a VPS, or under any generic Node/Docker process supervisor.
