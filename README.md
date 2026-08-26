# Strat.1

Options-flow research engine for Gold futures (GC) options.

The repository contains a provider-independent calculation engine plus a provider-neutral data-adapter layer for the six-level Gold options map:

- Dealer Ceiling
- Main Reclaim Pivot
- Active Dealer Resistance
- Active Dealer Support
- Stabilization Support
- Sweep-Trap Zone

## Architecture

```text
Provider API / CSV / JSON replay
             |
             v
      strat.adapters
             |
     canonical DataFrame
             |
             v
    strat.options_engine
             |
             v
       six-level map
```

The analytics layer does not know which market-data vendor supplied the data. This lets us develop and test the complete strategy before live CME access is available.

## Install

```bash
pip install -e .
```

## Offline adapter example

```python
from strat.adapters import RecordsAdapter
from strat.options_engine import GoldOptionsEngine

adapter = RecordsAdapter([
    {
        "strike": 4630,
        "expiry": "2026-08-28",
        "option_type": "CALL",
        "bid": 31.2,
        "ask": 32.1,
        "last": 31.7,
        "volume": 1250,
        "oi": 8430,
        "delta": 0.58,
        "gamma": 0.0018,
        "vega": 0.42,
        "iv": 0.185,
    }
])

chain = adapter.fetch_chain()
```

`CsvReplayAdapter` and `JsonReplayAdapter` can replay saved snapshots from any provider. `RecordsAdapter` is useful for tests and for integrating a provider SDK.

## Required normalized fields

`strike`, `expiry`, `option_type`, `bid`, `ask`, `last`, `volume`, `open_interest`, `delta`, `gamma`, `vega`, `iv`.

Optional trade fields are `trade_price`, `trade_size`, `aggressor`, and `multiplier`. The default multiplier is 100 ounces for standard GC; pass the instrument multiplier explicitly for other contracts.

## Adding CME later

The future CME adapter should only translate CME's response into the canonical schema. It should not contain GEX, level-selection, or signal logic. That logic remains in `strat.options_engine`.

## Testing

```bash
pytest -q
```

This is an analytics/research component, not financial advice or a guaranteed trading signal.
