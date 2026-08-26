# Strat.1

Options-flow research engine for Gold futures (GC) options.

This repository contains the first provider-independent calculation engine for the six-level Gold options map:

- Dealer Ceiling
- Main Reclaim Pivot
- Active Dealer Resistance
- Active Dealer Support
- Stabilization Support
- Sweep-Trap Zone

The engine is designed to accept normalized options-chain/trade data from CME or another provider without coupling the analytics to a particular API.

## Install

```bash
pip install -e .
```

## Quick start

```python
import pandas as pd
from strat.options_engine import GoldOptionsEngine

chain = pd.DataFrame([...])

engine = GoldOptionsEngine()
result = engine.calculate(
    chain=chain,
    futures_price=4623.54,
    atr=18.50,
)

print(result.levels)
print(result.gamma_regime)
```

## Required normalized fields

`strike`, `expiry`, `option_type`, `bid`, `ask`, `last`, `volume`, `open_interest`, `delta`, `gamma`, `vega`, `iv`.

Optional trade fields are `trade_price`, `trade_size`, `aggressor`, and `multiplier`. The default multiplier is 100 ounces for standard GC; pass the instrument multiplier explicitly for other contracts.

## Important modelling note

The engine estimates dealer positioning. Public open interest does not reveal the private dealer/customer side of every position. The structural GEX model therefore uses a configurable customer-long/dealer-short baseline, while flow-adjusted GEX uses signed trade pressure as a proxy. These assumptions must be validated with historical data before live trading.

## Testing

```bash
pytest -q
```

This is an analytics/research component, not financial advice or a guaranteed trading signal.
