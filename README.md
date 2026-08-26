# Strat.1 — Strategy 001

Quantitative intraday trading-bot research repository for Gold futures/options intelligence.

## Canonical workflow

There is **one active architecture**. Strategy logic must follow this flow:

```text
Provider / Replay Data
        ↓
Provider-Neutral Normalization
        ↓
Market Intelligence
  ├─ Price Structure
  ├─ Volume
  ├─ Velocity
  ├─ Options Flow
  ├─ Delta
  ├─ Gamma / GEX
  └─ IV
        ↓
S/R Zone Engine
        ↓
Active Market Map
        ↓
Reusable Confluence Engine
        ↓
Strategy 001 Setup / Entry Validation
        ↓
Global Risk Engine
        ↓
Execution Interface
   ├─ Demo
   └─ Live
```

Replay is a **development/testing capability**, not a third production mode. The eventual APK exposes only Demo / Live.

## Repository rules

- `docs/strategy_001_phase1_phase2_blueprint.md` is the canonical implementation blueprint for the current Phase 1/2 work.
- Do not create parallel strategy specifications, duplicate confluence engines, or alternate S/R definitions.
- New ideas must extend the canonical modules rather than create competing implementations.
- If an older rule conflicts with the current architecture, the older rule must be removed or replaced rather than left for the runtime to interpret.
- Provider adapters normalize data only; they must not contain trading decisions.
- The Confluence Engine scores evidence; Strategy 001 decides whether a setup is tradable.
- Risk and execution remain outside strategy/confluence logic.
- All production and replay decisions must be causal: no look-ahead data.

## Current implementation status

### Phase 1 — Core models

Implemented in `strat/core/`:

- `PriceBar`
- `MarketSnapshot`

These are provider-neutral domain models used as the boundary between data adapters and strategy intelligence.

### Phase 2 — S/R Zone Engine

Implemented in `strat/zones/`:

- Structural swing detection with confirmation delay
- ATR-normalized zone width
- Pivot clustering
- Zone strength scoring
- Volatility-normalized relevance
- Zone state machine
- Break / flip handling
- Market-map orchestration

The Zone Engine is intentionally extensible. Options/dealer, gamma, stabilization, liquidity/sweep, reclaim, and composite evidence can be added through the same zone model without creating another S/R system.

### Existing options intelligence

`strat/options_engine.py` remains a reusable **market-intelligence/data-calculation module**. Its options levels are inputs to the broader Zone/Confluence architecture; they are not a second Strategy 001 specification.

## Next implementation phase

Phase 3 will connect:

```text
Options Flow → Delta → Gamma/GEX → IV
                    ↓
              Zone Evidence
                    ↓
             Confluence Engine
                    ↓
              Strategy 001
```

That phase will also reconcile the existing options-flow strategy implementation with the canonical S/R Zone Engine so there is only one Strategy 001 runtime path.

## Testing

```bash
pip install -e .
pytest -q
```

This repository is a quantitative research/development system and does not guarantee trading performance or provide financial advice.
