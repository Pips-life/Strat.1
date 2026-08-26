from datetime import datetime, time

from strat.risk.engine import RiskEngine, RiskLimits, RiskRequest


def test_sizes_from_equity_and_rounds_down():
    engine = RiskEngine(RiskLimits(risk_per_trade=0.01, quantity_step=0.01))
    qty = engine.calculate_quantity(equity=1000, entry=100, stop_loss=99, point_value=1)
    assert qty == 10.0


def test_rejects_bad_reward_risk():
    engine = RiskEngine(RiskLimits(min_reward_risk=1.35))
    result = engine.evaluate(
        RiskRequest("BUY", 100, 99, 100.2, 1000, confidence=90)
    )
    assert not result.approved
    assert result.reason == "reward/risk below minimum"


def test_rejects_daily_loss_limit():
    engine = RiskEngine(RiskLimits(max_daily_loss=0.03))
    result = engine.evaluate(
        RiskRequest("BUY", 100, 99, 102, 1000, daily_pnl=-30, confidence=90)
    )
    assert not result.approved
    assert result.reason == "maximum daily loss reached"


def test_rejects_after_consecutive_losses():
    engine = RiskEngine(RiskLimits(max_consecutive_losses=3))
    result = engine.evaluate(
        RiskRequest("BUY", 100, 99, 102, 1000, consecutive_losses=3, confidence=90)
    )
    assert not result.approved


def test_rejects_entry_near_session_close():
    engine = RiskEngine(RiskLimits(session_start=time(7), session_end=time(22), flatten_minutes_before_close=15))
    result = engine.evaluate(
        RiskRequest(
            "BUY", 100, 99, 102, 1000,
            now=datetime(2026, 8, 26, 21, 50),
            confidence=90,
        )
    )
    assert not result.approved
    assert "session close" in result.reason


def test_approves_valid_intraday_trade():
    engine = RiskEngine(RiskLimits(risk_per_trade=0.01, min_reward_risk=1.35))
    result = engine.evaluate(
        RiskRequest(
            "BUY", 100, 99, 102, 1000,
            now=datetime(2026, 8, 26, 14, 0),
            confidence=82,
        )
    )
    assert result.approved
    assert result.quantity == 10.0
    assert result.reward_risk == 2.0
    assert result.risk_amount == 10.0


def test_flatten_enforces_no_overnight():
    engine = RiskEngine(RiskLimits(session_end=time(22), allow_overnight=False))
    assert engine.should_flatten(datetime(2026, 8, 26, 22, 0))
