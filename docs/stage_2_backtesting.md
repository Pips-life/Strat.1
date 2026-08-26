# Stage 2 — Backtesting & Replay

Stage 2 begins with a deterministic replay foundation. Historical OHLCV bars are replayed chronologically and can later be joined to historical options snapshots through the existing provider-neutral adapters.

## Design goals

- Deterministic, reproducible tests.
- Strategy-agnostic replay infrastructure.
- No look-ahead: strategies receive only data available at the current replay timestamp.
- Same Strategy → Confluence → Risk pipeline used later in paper/live operation.
- Intraday-first: session boundaries and mandatory end-of-day flattening remain explicit.
- Performance measured in R as well as cash so results remain comparable across account sizes.

## Next implementation blocks

1. Market/options snapshot synchronization.
2. Simulated order and fill engine.
3. Strategy runner using the existing registry.
4. Risk-aware position sizing and daily loss limits.
5. Intraday session calendar and end-of-day flattening.
6. Equity curve and drawdown tracking.
7. Detailed trade/setup analytics, including performance by confluence score and time of day.
8. Parameter-sweep framework for Strategy 001 without changing production rules.

Starting values must be treated as hypotheses. Backtesting will determine whether entry thresholds, ATR buffers, R:R requirements, and time filters should be loosened or tightened.
