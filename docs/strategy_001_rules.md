# Strategy 001 — Intraday Gold Options Flow Rules

## Design direction

`Market Intelligence -> Confluence Engine -> Strategy 001 -> Signal -> Risk -> Execution`

The Confluence Engine is strategy-agnostic. Strategy 001 uses its output to make a strategy-specific decision; it does not ask the Confluence Engine to choose BUY/SELL.

## Confluence

Default weighted evidence:

- Options positioning: 35%
- Market structure: 30%
- Liquidity/flow: 20%
- Volatility: 15%

Missing secondary evidence is neutral and available evidence is renormalized. Contradictions are penalized. Strategy 001 currently requires an entry confidence of at least 70, while the reusable engine remains tradable from 62.

## Entry location

Entries are only considered near the options map:

- Long: Active Dealer Support or Main Reclaim Pivot
- Short: Active Dealer Resistance, Dealer Ceiling, or Main Reclaim Pivot

The default precision zone is `0.25 * ATR` around the selected level.

## Long timing trigger

At least one:

- liquidity sweep followed by reclaim (`sweep_reclaim`)
- reclaim + higher low
- rejection + higher low

and at least one participation condition:

- absorption
- delta reversal
- liquidity sweep

## Short timing trigger

At least one:

- sweep followed by rejection (`sweep_rejection`)
- rejection + lower high
- breakdown + lower high

and at least one participation condition:

- distribution
- delta reversal
- liquidity sweep

## Stop

If a trigger extreme is supplied:

- Long: `trigger_low - 0.10 * ATR`
- Short: `trigger_high + 0.10 * ATR`

Fallback when no trigger extreme is supplied:

- Long: `entry_level - 0.30 * ATR`
- Short: `entry_level + 0.30 * ATR`

## Target

Target is the first valid opposing options level in the direction of the trade.

- Long: Active Dealer Resistance, then Dealer Ceiling
- Short: Active Dealer Support, then Stabilization Support

The trade is rejected unless reward/risk is at least `1.35R`.

## Intraday controls

- One Strategy 001 position at a time by default.
- No new entries inside the final 15 minutes of the trading session.
- Open positions are forcibly closed inside the final 15 minutes.
- Stop and target are explicit on every accepted entry.
- Execution remains outside the strategy.

These thresholds are **initial testable parameters**, not claims of optimality. They must be evaluated and tuned using replay/backtesting before live trading.
