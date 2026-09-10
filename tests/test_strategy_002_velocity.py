from datetime import datetime, timedelta

from strat.bot.velocity_expansion import VelocityExpansionController
from strat.execution.simulated import SimulatedExecution, SimulationConfig
from strat.strategies import registry
from strat.strategies.strategy_002 import Strategy002, Strategy002Config


def _ticks(prices):
    start = datetime(2026, 1, 1)
    return [{"timestamp": (start + timedelta(seconds=i)).timestamp(), "price": p} for i, p in enumerate(prices)]


def test_strategy_002_is_registered():
    ids = {item["id"] for item in registry.list()}
    assert "strategy_001" in ids
    assert "strategy_002" in ids


def test_velocity_expansion_detects_direction_and_immediate_entry():
    strategy = Strategy002(Strategy002Config(baseline_window=5, min_velocity_ratio=1.4, min_acceleration_ratio=1.01))
    # Small movement establishes the baseline; the final jump expands velocity.
    analysis = strategy.analyze(_ticks([100, 100.1, 100.2, 100.3, 100.4, 100.5, 101.0]))
    signal = strategy.generate_signal(analysis)
    assert signal.action == "BUY"
    assert signal.entry == 101.0
    assert signal.stop_loss == 100.3  # 70 pips at pip_size=0.01
    assert signal.metadata["opposite_stop_side"] == "SELL"


def test_velocity_expansion_sizes_from_balance_and_tick_value():
    strategy = Strategy002()
    # $1,000 balance, 1% risk = $10. A $0.70 stop is 70 ticks at $0.10/tick/lot.
    qty = strategy.calculate_quantity(balance=1000, entry=3000, tick_size=0.01, tick_value=0.10)
    assert qty == 1.42


def test_controller_keeps_one_opposite_stop_and_reverses_on_trigger():
    execution = SimulatedExecution(SimulationConfig(allow_multiple_positions=False))
    strategy = Strategy002(Strategy002Config(baseline_window=5))
    controller = VelocityExpansionController(execution, strategy)

    signal = strategy.generate_signal(strategy.analyze(_ticks([100, 100.1, 100.2, 100.3, 100.4, 100.5, 101.0])))
    result = controller.enter("XAUUSD", signal, balance=1000, tick_size=0.01, tick_value=0.10,
                              timestamp=datetime(2026, 1, 1, 12, 0, 0))
    assert result.status == "FILLED"
    assert len(execution.positions()) == 1
    pair = controller.pairs["XAUUSD"]
    assert pair.position_side == "BUY"
    assert pair.stop_price == 100.3

    # Price rises: SELL STOP trails from 100.30 to 100.80 using the 70-pip distance.
    controller.on_bar("XAUUSD", datetime(2026, 1, 1, 12, 0, 1), 101.6, 101.4, 101.5)
    assert controller.pairs["XAUUSD"].stop_price == 100.8

    # Price reverses through the paired stop. The source BUY is closed and a
    # SELL becomes the new running position with a fresh BUY STOP 70 pips above.
    fills = controller.on_bar("XAUUSD", datetime(2026, 1, 1, 12, 0, 2), 100.6, 100.4, 100.5)
    assert any(fill.order_id.startswith("VEL2-STOP-") for fill in fills)
    positions = execution.positions()
    assert len(positions) == 1
    assert positions[0].side == "SELL"
    assert controller.pairs["XAUUSD"].position_side == "SELL"
    assert controller.pairs["XAUUSD"].stop_price == 101.2
