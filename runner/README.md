# Pips-life live bot runner

This is the long-lived process behind `/api/bot/control`.

## Environment

- `METAAPI_TOKEN` — MetaApi token. Never commit it.
- `PIPSLIFE_BOT_CONTROL_TOKEN` — shared bearer token between Vercel and runner.
- `METAAPI_ACCOUNT_ID` — optional default MetaApi account id.
- `PIPSLIFE_SYMBOL` — defaults to `XAUUSD`.
- `PIPSLIFE_LIVE_TRADING_ENABLED` — defaults to `false`.

The runner owns the canonical Python `BotEngine`; it does not duplicate strategy logic.

## Control contract

`POST /` accepts JSON:

```json
{"action":"select","strategy":"002","accountId":"..."}
```

or:

```json
{"action":"start","strategy":"002","accountId":"..."}
```

`GET /?accountId=...` returns the active strategy and runner state.

## Important execution gate

The current service connects MetaApi and feeds live prices into `BotEngine`. It intentionally does **not** place live orders yet. Strategy 002 uses an opposite pending stop as a reversal mechanism, and MT5 behaves differently on netting versus hedging accounts. The execution adapter must verify the account mode before enabling live orders.

This gate is intentional: setting `PIPSLIFE_LIVE_TRADING_ENABLED=true` alone does not arm an order adapter.

## Deployment

Run the container as a persistent service, then set Vercel `PIPSLIFE_BOT_CONTROL_URL` to that service's HTTPS URL and `PIPSLIFE_BOT_CONTROL_TOKEN` to the same control token.
