from datetime import datetime, timedelta

from strat.backtest.models import Bar
from strat.environments.replay_runner import ReplayRunner
from strat.execution.models import OrderRequest
from strat.execution.simulated import SimulatedExecution, SimulationConfig


def test_replay_runner_fills_entry_and_target():
    t0 = datetime(2026, 1, 1, 10, 0)
    bars = [
        Bar(t0, 100, 101, 99, 100, metadata={"symbol": "XAUUSD"}),
        Bar(t0 + timedelta(minutes=1), 100, 103, 100, 102, metadata={"symbol": "XAUUSD"}),
    ]
    calls = {"n": 0}

    def strategy(bar):
        if calls["n"] == 0:
            calls["n"] += 1
            return OrderRequest("XAUUSD", "BUY", 1, price=100, stop_loss=98, take_profit=102, timestamp=bar.timestamp)
        calls["n"] += 1
        return None

    execution = SimulatedExecution(SimulationConfig())
    events = ReplayRunner(bars, execution, strategy).run()

    assert any(e.kind == "ENTRY" for e in events)
    assert any(e.kind == "PROTECTIVE_EXIT" for e in events)
    assert execution.positions() == []


def test_simulator_rejects_second_position_by_default():
    t0 = datetime(2026, 1, 1, 10, 0)
    execution = SimulatedExecution()
    first = execution.submit(OrderRequest("XAUUSD", "BUY", 1, price=100, timestamp=t0))
    second = execution.submit(OrderRequest("XAUUSD", "BUY", 1, price=101, timestamp=t0))
    assert first.status == "FILLED"
    assert second.status == "REJECTED"
