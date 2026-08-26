"""Runtime bot coordinator. Keeps strategy, risk and execution separate."""
from __future__ import annotations

from typing import Any

from strat.risk.engine import RiskEngine
from strat.strategies import registry


class BotEngine:
    def __init__(self, risk_engine: RiskEngine | None = None) -> None:
        self.risk_engine = risk_engine or RiskEngine()
        self.active_strategy_id: str | None = None
        self.strategy = None

    def select_strategy(self, strategy_id: str, **kwargs: Any) -> None:
        self.strategy = registry.create(strategy_id, **kwargs)
        self.active_strategy_id = strategy_id

    def available_strategies(self) -> list[dict]:
        return registry.list()

    def evaluate(self, market: Any, *, current_positions: int = 0, daily_loss: float = 0.0):
        if self.strategy is None:
            raise RuntimeError("No strategy selected")

        analysis = self.strategy.analyze(market)
        signal = self.strategy.generate_signal(analysis)

        approved = self.risk_engine.approve(
            confidence=signal.confidence,
            current_positions=current_positions,
            daily_loss=daily_loss,
        )

        if not approved and signal.action != "WAIT":
            signal.action = "WAIT"
            signal.reason = "Blocked by global risk engine."

        return signal
