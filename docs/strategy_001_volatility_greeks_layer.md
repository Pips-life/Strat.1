# Strategy 001 — Volatility & Greeks Layer

Strategy 001 now consumes a reusable provider-neutral volatility/Greeks evidence layer.

## Wired inputs

- Implied volatility (IV), IV change and IV regime
- IV rank and IV percentile
- Put IV, call IV and ATM IV
- IV skew and skew change
- Realized volatility and IV/RV spread/ratio
- IV term structure and term-structure slope/regime
- Vega exposure
- Theta exposure
- Vanna exposure
- Charm exposure
- Existing Delta and dealer-signed Gamma/GEX
- Existing velocity and volume intelligence

## Architecture

```text
Normalized market/options data
        ↓
Volatility & Greeks snapshot
        ↓
Contextual volatility score
        ↓
Strategy 001 Confluence
        ↓
QOF zone + positioning + volatility + timing
        ↓
Precision entry / risk / execution
```

The volatility layer is contextual rather than a collection of independent votes. Its aggregate score is directional-neutral by default, so it cannot manufacture a long or short signal by itself. It improves the quality of an already directional QOF setup and participates in volatility/velocity and volatility/gamma interactions.

## Missing data

Missing volatility inputs remain neutral. Providers may supply aggregate volatility fields through `market["volatility"]` or normalized per-option fields. Provider adapters remain responsible for normalization.

## Dealer GEX rule

Dealer GEX continues to require explicit dealer-side information (`gex_sign` or `dealer_position`). Open interest alone is never used to infer dealer positioning.

## Strategy 001 weights

```text
Structure      25%
Options Flow  20%
Gamma         15%
Delta         10%
Volatility    10%
Velocity      10%
Volume        10%
```

These are initial calibration weights, not claimed optimal values. Replay/backtesting should be used to calibrate them.

## Runtime activation

`strat/strategies/strategy_001_volatility.py` extends the canonical Strategy 001 implementation and is activated under the existing `strategy_001` registry ID. This preserves the bot's existing Strategy 001 interface while adding the volatility layer.

Replay remains development/testing only; production execution remains behind the existing Demo/Live boundary.
