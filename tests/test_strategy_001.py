from strat.strategies.options_flow_001 import OptionsFlowStrategy


def levels():
    return {
        "active_dealer_support": {"price": 4605.0, "confidence": 90},
        "main_reclaim_pivot": {"price": 4615.0, "confidence": 85},
        "active_dealer_resistance": {"price": 4630.0, "confidence": 90},
        "dealer_ceiling": {"price": 4650.0, "confidence": 85},
        "stabilization_support": {"price": 4585.0, "confidence": 80},
    }


def base_market():
    return {
        "price": 4606.0,
        "atr": 20.0,
        "levels": levels(),
        "confluence": {
            "options": 92,
            "structure": 84,
            "liquidity": 80,
            "volatility": 72,
        },
        "setup_side": "LONG",
        "price_action": {"sweep_reclaim": True, "higher_low": True},
        "liquidity": {"sweep": True, "absorption": True},
        "trigger_low": 4600.0,
        "minutes_to_session_close": 120,
    }


def test_strategy_001_generates_precise_long_entry():
    strategy = OptionsFlowStrategy()
    signal = strategy.generate_signal(strategy.analyze(base_market()))
    assert signal.action == "BUY"
    assert signal.entry == 4606.0
    assert signal.stop_loss == 4598.0
    assert signal.take_profit == 4630.0
    assert signal.metadata["rr"] >= 1.35


def test_strategy_001_waits_without_price_action_trigger():
    market = base_market()
    market["price_action"] = {}
    strategy = OptionsFlowStrategy()
    signal = strategy.generate_signal(strategy.analyze(market))
    assert signal.action == "WAIT"


def test_strategy_001_waits_when_reward_risk_is_too_small():
    market = base_market()
    market["levels"]["active_dealer_resistance"] = {"price": 4610.0, "confidence": 90}
    strategy = OptionsFlowStrategy()
    signal = strategy.generate_signal(strategy.analyze(market))
    assert signal.action == "WAIT"
    assert "R available" in signal.reason


def test_strategy_001_forces_intraday_flatten():
    market = base_market()
    market["position"] = {
        "side": "LONG",
        "stop_loss": 4598.0,
        "take_profit": 4630.0,
    }
    market["minutes_to_session_close"] = 10
    strategy = OptionsFlowStrategy()
    signal = strategy.generate_signal(strategy.analyze(market))
    assert signal.action == "CLOSE"
    assert signal.metadata["exit_type"] == "TIME_EXIT"


def test_strategy_001_closes_at_target():
    market = base_market()
    market["position"] = {
        "side": "LONG",
        "stop_loss": 4598.0,
        "take_profit": 4630.0,
    }
    market["price"] = 4630.0
    strategy = OptionsFlowStrategy()
    signal = strategy.generate_signal(strategy.analyze(market))
    assert signal.action == "CLOSE"
    assert signal.metadata["exit_type"] == "TARGET"
