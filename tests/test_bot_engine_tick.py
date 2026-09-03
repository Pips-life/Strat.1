from strat.bot.engine import BotEngine


def test_strategy_002_emits_from_live_tick_stream():
    engine = BotEngine()
    engine.select_strategy("002")

    assert engine.on_tick(2500.00, 1.0) is not None
    signal = engine.on_tick(2500.50, 2.0)

    assert signal.action == "BUY"
    assert signal.entry == 2500.50
    assert signal.stop_loss < signal.entry


def test_strategy_001_tick_only_snapshot_is_safe_wait():
    engine = BotEngine()
    engine.select_strategy("001")

    signal = engine.on_tick(2500.00, 1.0)

    assert signal is not None
    assert signal.action == "WAIT"
    assert "QOF" in signal.reason or "structure" in signal.reason.lower()


def test_close_signal_is_not_blocked_by_position_limit(monkeypatch):
    engine = BotEngine()

    class CloseStrategy:
        def analyze(self, market):
            return market

        def generate_signal(self, analysis):
            from strat.strategies.base import Signal
            return Signal("CLOSE", 100.0, reason="test close")

    engine.strategy = CloseStrategy()
    engine.active_strategy_id = "test"

    signal = engine.on_tick(2500.00, 1.0, current_positions=1)
    assert signal.action == "CLOSE"
