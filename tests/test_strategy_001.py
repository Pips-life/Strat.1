from types import SimpleNamespace

from strat.strategies.strategy_001 import Strategy001


def analysis_for(price=4600.0, side="LONG"):
    zone = {
        "id": "qof-support",
        "center": 4605.0,
        "lower": 4599.0,
        "upper": 4611.0,
        "role": "SUPPORT",
        "qof_primary": True,
        "strength": 90.0,
    }
    target = {
        "id": "qof-resistance",
        "center": 4630.0,
        "lower": 4627.0,
        "upper": 4633.0,
        "role": "RESISTANCE",
        "qof_primary": True,
        "strength": 90.0,
    }
    result = SimpleNamespace(direction=side, tradable=True, score=85.0)
    return {
        "price": price,
        "atr": 20.0,
        "qof_market_map": [zone, target],
        "confluence_result": result,
        "minutes_to_session_close": 120,
        "position": None,
    }


def test_strategy_001_accepts_precise_long_entry():
    strategy = Strategy001()
    signal = strategy.generate_signal(analysis_for())
    assert signal.action == "BUY"
    assert signal.metadata["precision_score"] >= 70.0


def test_strategy_001_waits_when_live_price_is_poorly_located_in_qof_zone():
    strategy = Strategy001()
    signal = strategy.generate_signal(analysis_for(price=4610.5))
    assert signal.action == "WAIT"
    assert signal.metadata["precision_required"] is True
    assert signal.metadata["preferred_entry"] < 4610.5


def test_strategy_001_keeps_intraday_flatten_rule():
    strategy = Strategy001()
    analysis = analysis_for()
    analysis["position"] = {"side": "LONG", "stop_loss": 4598.0, "take_profit": 4630.0}
    analysis["minutes_to_session_close"] = 10
    signal = strategy.generate_signal(analysis)
    assert signal.action == "CLOSE"
    assert signal.metadata["exit_type"] == "TIME_EXIT"
