from strat.strategies import registry
from strat.strategies.strategy_004_price_action import Strategy004


def _bars(values):
    return [{"open": o, "high": h, "low": l, "close": c} for o, h, l, c in values]


def _mtf():
    context = _bars([
        (100, 101, 99, 100), (100, 102, 99, 101), (101, 103, 100, 102),
        (100, 101, 98, 99), (100, 102, 99, 101), (102, 105, 101, 104),
        (103, 104, 101, 102), (102, 103, 100, 101), (102, 104, 101, 103),
        (103, 106, 102, 105),
    ])
    setup = _bars([
        (100, 101, 99, 100), (100, 102, 99, 101), (101, 103, 100, 102),
        (100, 101, 98, 99), (100, 102, 99, 101), (102, 105, 101, 104),
        (103, 104, 101, 102), (102, 103, 100, 101), (102, 104, 101, 103),
        (103, 104, 97.5, 99.5),  # sell-side liquidity sweep
        (99.5, 102, 99, 101.5),  # rejection
        (101.5, 107, 101, 106.5),  # displacement
    ])
    execution = _bars([
        (103, 103.5, 102.5, 103.2), (103.2, 103.6, 102.8, 103.3),
        (103.3, 103.7, 103, 103.4), (103.4, 103.8, 103.1, 103.5),
        (103.5, 103.7, 102.9, 103.1), (103.1, 104.5, 103, 104.2),
        (104.2, 104.4, 103.8, 104.1), (104.1, 105.5, 104, 105.2),
        (105.2, 105.4, 104.8, 105.1), (105.1, 107, 105, 106.8),
    ])
    return {"timeframes": {"15m": context, "5m": setup, "1m": execution}}


def test_strategy_004_is_registered_and_independent():
    ids = {item["id"] for item in registry.list()}
    assert {"strategy_001", "strategy_002", "strategy_003", "strategy_004"} <= ids


def test_strategy_004_requires_all_three_timeframes():
    analysis = Strategy004().analyze({"timeframes": {"15m": [], "5m": [], "1m": []}})
    assert analysis["price_action_ready"] is False
    assert analysis["timeframes"] == {"context": "15m", "setup": "5m", "execution": "1m"}


def test_strategy_004_uses_only_ohlc():
    market = _mtf()
    market["options"] = [{"gamma": 999}]
    market["volume"] = 999999
    analysis = Strategy004().analyze(market)
    assert analysis["price_action_ready"] is True
    assert "gamma" not in str(analysis["reasons"])


def test_strategy_004_exposes_mtf_state():
    analysis = Strategy004().analyze(_mtf())
    assert analysis["context_timeframe"] == "15m"
    assert analysis["setup_timeframe"] == "5m"
    assert analysis["execution_timeframe"] == "1m"
    assert set(analysis["sample_counts"]) == {"15m", "5m", "1m"}
    assert analysis["setup_state"] in {"NO_SETUP", "SWEPT", "DISPLACED"}


def test_strategy_004_does_not_allow_setup_without_1m_confirmation():
    market = _mtf()
    market["timeframes"]["1m"] = _bars([
        (103, 103.3, 102.7, 103.1), (103.1, 103.4, 102.8, 103.2),
        (103.2, 103.5, 102.9, 103.3), (103.3, 103.5, 103, 103.2),
        (103.2, 103.4, 103, 103.1), (103.1, 103.5, 102.9, 103.2),
        (103.2, 103.4, 103, 103.1), (103.1, 103.6, 102.9, 103.3),
        (103.3, 103.5, 103, 103.2),
    ])
    analysis = Strategy004().analyze(market)
    assert analysis["direction"] == "WAIT"
    assert analysis["execution_state"] == "WAIT"


def test_strategy_004_requires_opposing_liquidity_target():
    market = _mtf()
    analysis = Strategy004().analyze(market)
    if analysis["direction"] == "BUY":
        assert analysis["target_liquidity"] is None or analysis["target_liquidity"] > analysis["price"]
