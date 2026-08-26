# Strategy 001 — Phase 1 + Phase 2 Implementation Blueprint

## Purpose

This locks the first two implementation phases:

1. Phase 1 — core normalized domain models.
2. Phase 2 — quantitative S/R Zone Engine.

The engine is provider-independent and deterministic across replay, demo, and live modes. Replay is a development capability only; the eventual APK exposes Demo/Live.

## Phase 1

`strat/core/models.py` defines `PriceBar` and `MarketSnapshot`. Provider adapters must normalize into these domain models before strategy logic is called.

## Phase 2 — S/R Zone Engine

### Zone types

- STRUCTURAL
- OPTIONS_DEALER
- RECLAIM
- STABILIZATION
- LIQUIDITY_SWEEP
- COMPOSITE

### Zone roles

- SUPPORT
- RESISTANCE
- NEUTRAL

### Zone states

- FORMING
- ACTIVE
- TESTED
- RESPECTED
- BROKEN
- RECLAIMED
- FLIPPED
- EXHAUSTED
- INVALIDATED

## Structural detection algorithm

1. Detect swing highs/lows with configurable left/right confirmation windows.
2. Do not emit a pivot until the right confirmation bars exist.
3. Measure reaction magnitude relative to ATR.
4. Cluster pivots of the same role within a volatility-adjusted tolerance.
5. Convert each cluster into a price zone, not a single line.
6. Score reaction count and reaction strength.
7. Merge overlapping candidates.
8. Rank active zones by strength and volatility-normalized proximity.

## Zone strength

Initial configurable weights:

- structural evidence: 20%
- reaction history: 15%
- options evidence: 15%
- gamma/GEX: 15%
- volume: 10%
- liquidity/sweep evidence: 10%
- recency: 10%
- multi-source agreement bonus: 5%

Phase 2 exposes interfaces for options, gamma, volume, and liquidity evidence but does not fabricate those inputs. Later intelligence modules will populate them.

## Zone state machine

Zones can move through active/tested/respected states, or through broken -> flipped states when price crosses and retests a zone. Breaks and reclaim tolerances are ATR-normalized rather than fixed point distances.

## No-lookahead rule

A pivot requiring `right_bars` confirmation is not visible to the strategy until those bars have actually occurred. The detector therefore stores a confirmation index and filters by the observation boundary. This rule must remain true in replay, demo, and live execution.

## Integration boundary

Phase 2 does not alter Strategy 001's existing entry/risk code. `ZoneEngine` exposes a reusable market map for the Confluence Engine. Future detectors can add options/dealer zones, stabilization zones, liquidity/sweep zones, reclaim classification, and composite multi-source zones without changing the public `Zone` model.

## Next phase

Phase 3 connects Options Flow -> Delta -> Gamma/GEX -> IV -> Zone Evidence -> Confluence Engine, then updates Strategy 001 to consume the unified market map.
