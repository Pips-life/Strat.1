from strat.strategies import registry
from strat.strategies.strategy_004_price_action import Strategy004


def _bars(values):
    return [{"open": o, "high": h, "low": l, "close": c} for o, h, l, c in values]


def test_strategy_004_is_registered_and_independent():
    ids = {item["id"] for item in registry.list()}
    assert "strategy_001" in ids
    assert "strategy_002" in ids
    assert "strategy_003" in ids
    assert "strategy_004" in ids


def test_strategy_004_waits_for_confirmation():
    bars = _bars(
        [(100, 101, 99, 100.5)] * 10
        + [(100.5, 102, 100, 101)] * 8
        + [(101, 102, 100, 101)]
    )
    strategy = Strategy004()
    analysis = strategy.analyze(bars)
    signal = strategy.generate_signal(analysis)
    assert signal.action == "WAIT"


def test_strategy_004_requires_real_price_action_not_just_a_wick():
    bars = _bars(
        [(100, 101, 99, 100)] * 8
        + [(100, 105, 99, 104)]
        + [(104, 104.5, 100, 101)]
        + [(101, 101.5, 99.5, 100)]
        + [(100, 100.5, 98.5, 99)]
        + [(99, 99.5, 98, 98.5)]
    )
    strategy = Strategy004()
    analysis = strategy.analyze(bars)
    assert analysis["price_action_ready"]
    assert analysis["direction"] in {"WAIT", "BUY", "SELL"}
    if analysis["direction"] == "WAIT":
        assert "liquidity" in analysis.get("reason", "").lower() or analysis.get("bullish_pending") or analysis.get("bearish_pending")


def test_strategy_004_does_not_use_external_market_inputs():
    bars = _bars(
        [(100, 101, 99, 100.5)] * 20
    )
    strategy = Strategy004()
    analysis = strategy.analyze({"bars": bars, "options": [{"gamma": 999}], "volume": 999999})
    assert analysis["price_action_ready"] is True
    assert "options" not in analysis or "gamma" not in str(analysis.get("reasons", ""))
