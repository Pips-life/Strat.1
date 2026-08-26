# Strat.1 — QOF (Strategy 001)

Quantitative Options Flow (QOF) intraday trading-bot research repository.

## Pips-life strategies

1. QOF.
2.
3.

Future strategies will be added only when formally designed and approved. Blank entries are intentional placeholders.

## Canonical QOF objective

**QOF trades QOF-derived market structures, not TradingView-derived market structures.**

QOF is the primary strategy. It continuously converts live options-flow and market data into mathematical estimates of where participant/dealer positioning may create resistance, support, stabilization, liquidity/reaction, acceleration or structural flips. These QOF-implied structures can be identified before conventional chart structure becomes visually obvious.

Conventional TradingView-style swings, support and resistance are **secondary context/confirmation only**. They must never be a prerequisite for a QOF trade and must never redefine the QOF market map.

## Canonical terminology

- **QOF** = Quantitative Options Flow; Strategy 001.
- **QOF Structure Engine** = the reusable engine that derives and maintains QOF-implied market structures from quantitative positioning and market intelligence.
- **QOF-implied structure** = a probabilistic structure inferred from QOF evidence before or during market formation; it is not guaranteed support/resistance.
- **Dealer Resistance** = a QOF-implied region where positioning/evidence indicates increased probability of upward-price rejection or supply/hedging pressure.
- **Dealer Support** = a QOF-implied region where positioning/evidence indicates increased probability of downward-price rejection or demand/hedging support.
- **Stabilisation Zone** = a QOF-implied region where pressure is expected to diminish and price may stabilize; it is not required to have a prior swing low/high.
- **Market Structure Confirmation** = subsequent price behavior that validates, rejects, strengthens, weakens or flips a QOF-implied structure.
- **TradingView structure** = conventional price-derived swing/S/R context. It is not the primary QOF structural signal.

Avoid using "S/R strategy", "TradingView structure strategy", or similar names for QOF. Structural labels describe QOF outputs, not separate strategies.

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
  ├─ Expansion / Acceleration Zones
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

## Predictive structure principle

The QOF Structure Engine is predictive first and reactive second:

```text
QOF positioning
      ↓
QOF mathematical calculation
      ↓
QOF-implied structure emerges
      ↓
price approaches the structure
      ↓
real-time QOF + price behavior validates/rejects it
      ↓
QOF Confluence determines tradeability
```

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

The bot does **not** wait for a later TradingView swing high/low to exist before acting. A later visible swing can provide confirmation/context, but it is not the reason the QOF trade exists.

## QOF structure lifecycle

QOF-implied structures are dynamic and must be continuously recalculated as the underlying/options state changes.

```text
UNFORMED → EMERGING → FORMING → ACTIVE → TESTED → CONFIRMED
                                      ↓
                                  WEAKENING
                                      ↓
                                  INVALIDATED
```

A broken QOF-implied structure may become a potential flip structure when subsequent positioning and price behavior support the new role.

## QOF structure evidence

Structure strength may incorporate:

- Options flow
- Open interest
- Delta
- Gamma/GEX
- IV
- Options volume
- Price/volume
- Velocity
- Multi-source agreement
- Price interaction after the structure is approached
- Volatility-normalized proximity/relevance

No single Greek or options strike automatically creates a tradable structure. Provider-specific Greek/GEX sign conventions must be normalized explicitly.

## Repository rules

- QOF is the canonical name and identity of Strategy 001.
- QOF-derived structure is the primary structural map.
- Do not turn conventional TradingView S/R into the strategy's entry prerequisite.
- QOF-implied structures are probabilistic predictions/evidence, not guaranteed support or resistance.
- The QOF Structure Engine owns QOF-derived structures and the QOF market map.
- The Confluence Engine scores evidence; QOF determines whether a setup is tradable.
- Risk and execution remain outside strategy/confluence logic.
- Keep one active implementation for each trading decision layer.
- Do not create parallel strategy specifications, duplicate confluence engines, or alternate structural definitions.
- Superseded rules/code must be removed or replaced, not left for runtime interpretation.
- Provider adapters normalize data only; they do not make trading decisions.
- Production and replay decisions must be causal: no look-ahead data.

## Current implementation

### Phase 1 — Core models

Provider-neutral `PriceBar` and `MarketSnapshot` models are implemented in `strat/core/`.

### Phase 2 — Structural intelligence

The structural layer provides price-derived evidence such as confirmed swings, volatility-normalized regions and state transitions. These remain **supporting evidence** and do not define QOF structure by themselves.

### Phase 3 — QOF intelligence + Confluence

Implemented:

- Options flow evidence
- Delta-adjusted directional pressure
- Gamma exposure proxy with explicit provider sign convention
- Positive/negative/neutral gamma regime
- IV expansion/contraction/stable regime
- Velocity expansion/contraction/stable regime
- Relative volume
- QOF-derived candidate structures
- Composite multi-source structures
- Seven-factor directional Confluence Engine
- Directional edge and contradiction penalty
- Missing-data neutrality
- Canonical QOF Strategy 001 consuming the structural map and confluence result

Superseded level-based Strategy 001 logic was removed.

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
