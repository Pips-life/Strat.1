"""Event-driven bot coordinator.

The execution hot path is deliberately synchronous and in-memory:
MetaApi tick -> BotEngine.on_tick() -> strategy signal -> risk gate -> executor.
There is no polling timer, sleep, HTTP request, or network call in the decision path.
"""
from __future__ import annotations

from collections import deque
from datetime import datetime, timezone
from typing import Any
import time

from strat.risk.engine import RiskEngine
from strat.strategies import registry


class BotEngine:
    def __init__(self, risk_engine: RiskEngine | None = None) -> None:
        self.risk_engine = risk_engine or RiskEngine()
        self.active_strategy_id: str | None = None
        self.strategy = None
        self._ticks: deque[tuple[float, float]] = deque(maxlen=64)
        self._last_emitted_action = "WAIT"
        self.tick_count = 0
        self.last_decision_ns = 0

    @staticmethod
    def _normalize_strategy_id(strategy_id: str) -> str:
        value = str(strategy_id).strip()
        return {"001": "strategy_001", "002": "strategy_002"}.get(value, value)

    def select_strategy(self, strategy_id: str, **kwargs: Any) -> None:
        normalized = self._normalize_strategy_id(strategy_id)
        self.strategy = registry.create(normalized, **kwargs)
        self.active_strategy_id = normalized
        self._ticks.clear()
        self._last_emitted_action = "WAIT"
        self.tick_count = 0
        self.last_decision_ns = 0

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

    def on_tick(
        self,
        price: float,
        timestamp: float | None = None,
        *,
        current_positions: int = 0,
        daily_loss: float = 0.0,
    ):
        """Process one market tick without awaiting or performing I/O."""
        if self.strategy is None:
            raise RuntimeError("No strategy selected")
        price = float(price)
        if price <= 0:
            return None
        ts = float(timestamp if timestamp is not None else datetime.now(timezone.utc).timestamp())
        self._ticks.append((ts, price))
        self.tick_count += 1

        analysis = self.strategy.analyze({"ticks": list(self._ticks)})
        signal = self.strategy.generate_signal(analysis)
        self.last_decision_ns = time.perf_counter_ns()

        if signal.action == "WAIT":
            self._last_emitted_action = "WAIT"
            return signal
        if current_positions >= self.risk_engine.limits.max_positions:
            signal.action = "WAIT"
            signal.reason = "Maximum simultaneous positions reached."
            return signal
        if signal.action == self._last_emitted_action:
            signal.action = "WAIT"
            signal.reason = "Signal already emitted for current expansion edge."
            return signal
        if not self.risk_engine.approve(
            confidence=signal.confidence,
            current_positions=current_positions,
            daily_loss=daily_loss,
        ):
            signal.action = "WAIT"
            signal.reason = "Blocked by global risk engine."
            return signal
        self._last_emitted_action = signal.action
        return signal
