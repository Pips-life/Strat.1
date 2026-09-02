# Pips-life streaming execution runner

Strategy execution is intentionally **tick-event-driven**:

`MetaApi streaming tick -> BotEngine.on_tick() -> risk gate -> async market order`

Strategy 002 then maintains the opposite 100-pip stop order from the same streaming runtime.

## Important runtime requirement

`runner/server.py` is a persistent process. It must run on a continuously available compute instance that can maintain the MetaApi streaming connection. Vercel remains the control/status plane; the Next.js API sends `select`, `start`, and `stop` commands to the runner through `PIPSLIFE_RUNNER_URL`.

Do **not** replace the tick stream with a timer/polling loop. Polling changes the execution semantics and can miss the movement Strategy 002 is designed to react to.

## Required runner environment

- `METAAPI_TOKEN` — MetaApi token
- `PIPSLIFE_BOT_CONTROL_TOKEN` — shared secret used by the Vercel control API
- `PIPSLIFE_LIVE_TRADING_ENABLED=true` — execution gate
- `PIPSLIFE_EXECUTION_VOLUME=0.01` — demo-test default
- `PIPSLIFE_SYMBOL=XAUUSD`
- `PIPSLIFE_STRATEGY002_TRAIL_PIPS=100`

The runner also accepts `METAAPI_ACCOUNT_ID`, but the control API normally passes the authenticated account ID explicitly.

## Start

The supplied Docker image exposes port 8080. Run it as a long-lived service and publish its HTTPS endpoint as `PIPSLIFE_RUNNER_URL` in Vercel.

The health endpoint is `/health`. The status endpoint is `/` and reports `execution: tick-event-driven`, tick count, decision reaction time, and order acknowledgement time.

## Demo verification sequence

1. Start the persistent runner with the demo MetaApi account only.
2. Verify `/health` reports `metaapiConfigured: true`.
3. From the app select Strategy 002.
4. Start the bot.
5. Verify status changes to `RUNNING` and tick count increases.
6. Only then allow live execution on the demo account.
7. Verify the first market position and its opposite 100-pip stop order in MT5.

Never use the smoke-test container as proof of broker connectivity: that test intentionally runs without MetaApi credentials.
