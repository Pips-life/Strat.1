from strat.strategies import registry
from strat.strategies.strategy_004_price_action import Strategy004


def _bars(values):
    return [{"open": o, "high": h, "low": l, "close": c} for o, h, l, c in values]


def _mtf():
    context = _bars([
        (100, 101, 99, 100), (100, 102, 98, 101), (101, 101.5, 99, 100.5),
        (100.5, 103, 100, 102), (102, 102.5, 99.5, 101.5),
        (101.5, 104, 100.5, 103), (103, 103.5, 101, 102.5),
        (102.5, 105, 102, 104),
    ])
    setup = _bars([
        (100, 101, 99, 100.5), (100.5, 102, 100, 101), (101, 101.5, 99.5, 100.5),
        (100.5, 102.5, 100, 102), (102, 102.5, 100.5, 101.5),
        (101.5, 103, 101, 102.5), (102.5, 103, 101.5, 102),
        (102, 102.5, 98.8, 101.8), (101.8, 104.5, 101.5, 104),
    ])
    execution = _bars([
        (103, 103.5, 102.5, 103.2), (103.2, 103.6, 102.8, 103.3),
        (103.3, 103.7, 103, 103.4), (103.4, 103.8, 103.1, 103.5),
        (103.5, 103.7, 102.9, 103.1), (103.1, 104.5, 103, 104.2),
    ])
    return {"timeframes": {"15m": context, "5m": setup, "1m": execution}}


def test_strategy_004_is_registered_and_independent():
    ids = {item["id"] for item in registry.list()}
    assert {"strategy_001", "strategy_002", "strategy_003", "strategy_004"} <= ids


def test_strategy_004_requires_all_three_timeframes():
    strategy = Strategy004()
    analysis = strategy.analyze({"timeframes": {"15m": [], "5m": [], "1m": []}})
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
        (103, 103.3, 102.7, 103.1),
        (103.1, 103.4, 102.8, 103.2),
        (103.2, 103.5, 102.9, 103.3),
        (103.3, 103.5, 103, 103.2),
        (103.2, 103.4, 103, 103.1),
    ])
    analysis = Strategy004().analyze(market)
    assert analysis["direction"] == "WAIT"
    assert analysis["execution_state"] == "WAIT"
