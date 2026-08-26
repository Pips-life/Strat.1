import numpy as np
import pandas as pd

from strat.options_engine import GoldOptionsEngine, black76_gamma


def sample_chain(n=31):
    strikes = np.arange(4470, 4621, 5)
    rows = []
    expiry = (pd.Timestamp.now(tz="UTC") + pd.Timedelta(days=3)).isoformat()
    for i, k in enumerate(strikes):
        for typ in ("C", "P"):
            rows.append({
                "strike": float(k),
                "expiry": expiry,
                "option_type": typ,
                "bid": 10.0,
                "ask": 11.0,
                "last": 10.5,
                "volume": 100 + i,
                "open_interest": 1000 + i * 10,
                "delta": 0.5 if typ == "C" else -0.5,
                "gamma": 0.002,
                "vega": 0.4,
                "iv": 0.20,
                "trade_price": 10.9,
                "trade_size": 10,
                "multiplier": 100,
            })
    return pd.DataFrame(rows)


def test_black76_gamma_positive():
    g = black76_gamma(4620, 4620, 0.20, 3 / 365)
    assert g > 0
    assert np.isfinite(g)


def test_engine_returns_six_levels():
    engine = GoldOptionsEngine()
    result = engine.calculate(sample_chain(), futures_price=4600, atr=20)
    assert set(result.levels) == {
        "dealer_ceiling",
        "main_reclaim_pivot",
        "active_dealer_resistance",
        "active_dealer_support",
        "stabilization_support",
        "sweep_trap_zone",
    }
    assert result.futures_price == 4600
    assert len(result.strike_map) > 0


def test_missing_columns_are_rejected():
    engine = GoldOptionsEngine()
    df = sample_chain().drop(columns=["gamma"])
    try:
        engine.calculate(df, 4600, 20)
    except ValueError as exc:
        assert "gamma" in str(exc)
    else:
        raise AssertionError("Expected ValueError")
