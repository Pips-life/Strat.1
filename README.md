# Strat.1 — QOF (Strategy 001)

Quantitative Options Flow (QOF) intraday trading-bot research repository.

## Pips-life strategies

1. QOF.
2.
3.

Future strategies will be added only when formally designed and approved. Blank entries are intentional placeholders.

## Canonical objective

**QOF trades QOF-derived market structures, not TradingView-derived market structures.**

QOF continuously converts options flow, open interest, Delta, Gamma/GEX, IV, options volume and price/market data into mathematical estimates of potential dealer resistance, dealer support, stabilization, liquidity/reaction and flip structures. These QOF-implied structures may exist before conventional chart structure becomes visually obvious.

TradingView-style swings/support/resistance may be used as secondary confirmation, but they are never a prerequisite for a QOF trade.

## Canonical workflow

```text
Provider / Replay Data
        ↓
Provider-Neutral Normalization
        ↓
Market Intelligence
  ├─ Options Flow
  ├─ Open Interest
  ├─ Delta
  ├─ Gamma / GEX
  ├─ IV
  ├─ Options Volume
  ├─ Price / Volume
  └─ Velocity
        ↓
QOF Structure Engine
  ├─ Dealer Resistance
  ├─ Dealer Support
  ├─ Stabilisation Zones
  ├─ Liquidity / Reaction Zones
  └─ Potential Flip Zones
        ↓
QOF Market Map
        ↓
Reusable Confluence Engine
        ↓
QOF (Strategy 001) Setup / Entry Validation
        ↓
Global Risk Engine
        ↓
Execution Interface
   ├─ Demo
   └─ Live
```

Replay is development/testing only; the eventual APK exposes Demo / Live.

## Repository rules

- QOF is the canonical name and identity of Strategy 001.
- QOF-derived structure is the primary structural map.
- Do not turn conventional TradingView S/R into the strategy's entry prerequisite.
- QOF-implied structures are predictions/evidence, not guaranteed support or resistance; price and live QOF behavior validate, reject or update them.
- Keep one active implementation for each trading decision layer.
- Do not create parallel strategy specifications, duplicate confluence engines, or alternate structural definitions.
- Superseded rules must be removed or replaced, not left for runtime interpretation.
- Provider adapters normalize data only; they do not make trading decisions.
- The QOF Structure Engine owns QOF-derived market structures and the market map.
- The Confluence Engine scores evidence; QOF decides whether a setup is tradable.
- Risk and execution remain outside strategy/confluence logic.
- Production and replay decisions must be causal: no look-ahead data.

## Current implementation

### Phase 1 — Core models

Provider-neutral `PriceBar` and `MarketSnapshot` models are implemented in `strat/core/`.

### Phase 2 — Structural intelligence

Implemented in `strat/zones/`:

- Structural swing detection with confirmation delay
- ATR-normalized zone width
- Pivot clustering
- Zone strength and relevance
- Zone state machine and break/flip handling
- Active market-map orchestration

These price-derived structures are supporting evidence only; they do not define the QOF strategy.

### Phase 3 — QOF intelligence + Confluence

Implemented:

- Options flow evidence
- Delta-adjusted directional pressure
- Gamma exposure proxy with explicit provider sign convention
- Positive/negative/neutral gamma regime
- IV expansion/contraction/stable regime
- Velocity expansion/contraction/stable regime
- Relative volume
- Options-derived candidate structures
- Composite multi-source structures
- Seven-factor directional Confluence Engine
- Directional edge and contradiction penalty
- Missing-data neutrality
- Canonical QOF Strategy 001 consuming the structural map and confluence result

The former competing level-based Strategy 001 implementation was removed.

## QOF structure principle

The engine should identify potential QOF market structure **before conventional chart structure forms**.

Example:

```text
QOF detects active dealer resistance
              ↓
price approaches resistance
              ↓
QOF flow + Delta + Gamma/GEX + IV + velocity
validate bearish pressure
              ↓
QOF SHORT
              ↓
target = next QOF-derived dealer support /
stabilisation zone
```

A later TradingView swing high is confirmation/context, not the reason the trade exists.

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
