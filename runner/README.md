# Pips-life live bot runner

This is the **single shared execution runner for the entire Pips-life strategy platform**. Strategy 001, Strategy 002, and future strategies use this same runner, the same Vercel control plane, the same MetaApi connection layer, and the same account credentials. A strategy is selected by ID; it never gets its own password or infrastructure.

## Shared environment

- `METAAPI_TOKEN` — one MetaApi token for the platform. Never commit it.
- `PIPSLIFE_BOT_CONTROL_TOKEN` — one shared bearer token between the Vercel backend and this runner. It is not a strategy password.
- `METAAPI_ACCOUNT_ID` — optional default MetaApi account id.
- `PIPSLIFE_SYMBOL` — defaults to `XAUUSD`.
- `PIPSLIFE_LIVE_TRADING_ENABLED` — defaults to `false`.

The runner owns the canonical Python `BotEngine`; it does not duplicate strategy logic. The engine's strategy registry is the source of truth for available strategies.

## Control contract

`POST /` accepts JSON such as:

```json
{"action":"select","strategy":"002","accountId":"..."}
```

or:

```json
{"action":"start","strategy":"002","accountId":"..."}
```

The same endpoint accepts any strategy ID registered by `BotEngine`; there is no separate endpoint, URL, token, password, or deployment per strategy.

`GET /?accountId=...` returns the active strategy and runner state.

## Security model

There is only **one platform-level control secret** between Vercel and the runner. Strategy IDs are routing data, not credentials. MT5/MetaApi credentials remain server-side and are never stored in the Android app or per-strategy configuration.

## Important execution gate

The current service connects MetaApi and feeds live prices into `BotEngine`. It intentionally does **not** place live orders yet. Strategy 002 uses an opposite pending stop as a reversal mechanism, and MT5 behaves differently on netting versus hedging accounts. The execution adapter must verify the account mode before enabling live orders.

This gate is intentional: setting `PIPSLIFE_LIVE_TRADING_ENABLED=true` alone does not arm an order adapter.

## Deployment architecture

```text
Android app
    |
    v
ONE Vercel backend /api/*
    |
    | one shared control URL + one shared control token
    v
ONE persistent Pips-life runner
    |
    +--> BotEngine --> Strategy 001
    |
    +--> BotEngine --> Strategy 002
    |
    +--> BotEngine --> future strategies
    |
    v
ONE MetaApi / MT5 account connection
```

Deploy the runner once as a persistent service. Configure the Vercel project once with `PIPSLIFE_BOT_CONTROL_URL` and the single shared `PIPSLIFE_BOT_CONTROL_TOKEN`. **Do not create strategy-specific passwords or infrastructure.**
