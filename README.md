# Strat.1 — Strategy 001

Quantitative intraday trading-bot research repository for Gold futures/options intelligence.

## Canonical workflow

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

Replay is development/testing only; the eventual APK exposes Demo / Live.

## Repository rules

- Keep one active implementation for each trading decision layer.
- Do not create parallel strategy specifications, duplicate confluence engines, or alternate S/R definitions.
- Superseded rules must be removed or replaced, not left for runtime interpretation.
- Provider adapters normalize data only; they do not make trading decisions.
- The S/R Zone Engine owns the market map.
- The Confluence Engine scores evidence; Strategy 001 decides whether a setup is tradable.
- Risk and execution remain outside strategy/confluence logic.
- Production and replay decisions must be causal: no look-ahead data.

## Current implementation

### Phase 1 — Core models

Provider-neutral `PriceBar` and `MarketSnapshot` models are implemented in `strat/core/`.

### Phase 2 — S/R Zone Engine

Implemented in `strat/zones/`:

- Structural swing detection with confirmation delay
- ATR-normalized zone width
- Pivot clustering
- Zone strength and relevance
- Zone state machine and break/flip handling
- Active market-map orchestration

### Phase 3 — Intelligence + Confluence

Implemented:

- Delta-adjusted directional pressure
- Gamma exposure proxy with explicit provider sign convention
- Positive/negative/neutral gamma regime
- IV expansion/contraction/stable regime
- Velocity expansion/contraction/stable regime
- Relative volume
- Seven-factor directional Confluence Engine
- Directional edge and contradiction penalty
- Missing-data neutrality
- Canonical Strategy 001 consuming the zone map and confluence result

The former `options_flow_001.py` strategy implementation was removed because it maintained a competing level-based entry path.

## Confluence weights

```text
Structure      25%
Options Flow  20%
Gamma         15%
Delta         10%
IV            10%
Velocity      10%
Volume        10%
```

Initial thresholds are configurable and intended for replay calibration, not assumed optimal parameters.

## Testing

```bash
pip install -e .
pytest -q
```

This repository is a quantitative research/development system and does not guarantee trading performance or provide financial advice.
