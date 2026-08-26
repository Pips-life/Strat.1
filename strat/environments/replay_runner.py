from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from strat.backtest.models import Bar
from strat.execution.interface import ExecutionAdapter
from strat.execution.models import Fill, OrderRequest
from strat.backtest.replay import ReplayEngine


@dataclass(frozen=True)
class ReplayEvent:
    timestamp: Any
    kind: str
    payload: dict[str, Any]


class ReplayRunner:
    """Runs the real strategy/execution pipeline against replay bars.

    The runner intentionally knows nothing about Strategy 001. A strategy is
    supplied as a callable receiving the current bar and returning an optional
    OrderRequest. The same execution interface can later be backed by demo or
    live adapters.
    """

    def __init__(
        self,
        bars: list[Bar],
        execution: ExecutionAdapter,
        strategy_step: Callable[[Bar], OrderRequest | None],
    ) -> None:
        self.replay = ReplayEngine(bars)
        self.execution = execution
        self.strategy_step = strategy_step
        self.events: list[ReplayEvent] = []

    def run(self) -> list[ReplayEvent]:
        for bar in self.replay.stream():
            # Existing stops/targets are processed before a new decision at this bar.
            fills = self.execution.on_bar(
                symbol=self._symbol(bar),
                timestamp=bar.timestamp,
                high=bar.high,
                low=bar.low,
                close=bar.close,
            )
            self._record_fills(fills, bar, "PROTECTIVE_EXIT")

            order = self.strategy_step(bar)
            if order is not None:
                result = self.execution.submit(order)
                self.events.append(
                    ReplayEvent(bar.timestamp, "ORDER", {"status": result.status, "order_id": result.order_id, "reason": result.reason})
                )
                if result.fill:
                    self._record_fills([result.fill], bar, "ENTRY")

        return self.events

    @staticmethod
    def _symbol(bar: Bar) -> str:
        return str(bar.metadata.get("symbol", "XAUUSD"))

    def _record_fills(self, fills: list[Fill], bar: Bar, kind: str) -> None:
        for fill in fills:
            self.events.append(
                ReplayEvent(
                    bar.timestamp,
                    kind,
                    {
                        "order_id": fill.order_id,
                        "symbol": fill.symbol,
                        "side": fill.side,
                        "quantity": fill.quantity,
                        "price": fill.price,
                        "commission": fill.commission,
                        "slippage": fill.slippage,
                    },
                )
            )
