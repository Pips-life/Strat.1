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
- `FLASHALPHA_API_KEY` — FlashAlpha API key for Strategy 001; keep it in the runtime secret store and never commit it or put it in the Android app.
- `FLASHALPHA_BASE_URL` — optional FlashAlpha API base URL, default `https://lab.flashalpha.com`.
- `FLASHALPHA_TIMEOUT_SECONDS` — optional API timeout, default `2.5` seconds.
- `FLASHALPHA_CACHE_TTL_SECONDS` — optional per-symbol cache, default `5` seconds.
- `FLASHALPHA_USE_FLOW_SIGNAL` — optional `true`/`false`; enables the FlashAlpha flow-anomaly confirmation endpoint, default `true`.

For Strategy 001, pass a US equity/ETF ticker in the market snapshot as `flashalpha_symbol`, `ticker`, or `symbol`. FlashAlpha's exposure endpoints provide GEX and key levels; its flow-anomaly signal can be used as an additional directional confirmation when the account plan exposes that endpoint. API failures and unsupported symbols fail soft, leaving native QOF inputs in control.

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
        +--> Strategy 001 selection -> canonical Python QOF engine -> optional FlashAlpha confirmation
        +--> Strategy 002 selection -> tick-event MetaApi adapter
        +--> User watchlist -> MetaApi terminal symbols + live prices
        |
        v
MetaApi -> MT5
```

There is no Vercel control plane and no Render-specific configuration. The runner can be started locally, on a VPS, or under any generic Node/Docker process supervisor.
