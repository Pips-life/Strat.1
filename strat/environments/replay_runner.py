from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from strat.backtest.models import Bar
from strat.backtest.replay import ReplayEngine
from strat.execution.interface import ExecutionAdapter
from strat.execution.models import Fill, OrderRequest
from strat.strategies.base import Strategy


@dataclass(frozen=True)
class ReplayEvent:
    timestamp: Any
    kind: str
    payload: dict[str, Any]


class ReplayRunner:
    """Run a real Strategy plugin through the normal execution interface.

    A market_builder converts each historical bar and synchronized historical
    options snapshot into the canonical market dictionary expected by the
    selected strategy. No future bars are exposed to the strategy.
    """

    def __init__(self, bars: list[Bar], execution: ExecutionAdapter, strategy: Strategy,
                 market_builder: Callable[[Bar], dict[str, Any]] | None = None) -> None:
        self.replay = ReplayEngine(bars)
        self.execution = execution
        self.strategy = strategy
        self.market_builder = market_builder or self._default_market_builder
        self.events: list[ReplayEvent] = []

    def run(self) -> list[ReplayEvent]:
        for bar in self.replay.stream():
            symbol = self._symbol(bar)
            protective = self.execution.on_bar(symbol, bar.timestamp, bar.high, bar.low, bar.close)
            self._record_fills(protective, bar, "PROTECTIVE_EXIT")

            market = self.market_builder(bar)
            market.setdefault("symbol", symbol)
            market.setdefault("price", bar.close)
            if bar.atr is not None:
                market.setdefault("atr", bar.atr)

            analysis = self.strategy.analyze(market)
            signal = self.strategy.generate_signal(analysis)
            self.events.append(ReplayEvent(bar.timestamp, "SIGNAL", {
                "strategy": self.strategy.id, "action": signal.action,
                "confidence": signal.confidence, "reason": signal.reason,
                "metadata": signal.metadata,
            }))

            if signal.action in {"BUY", "SELL"} and signal.entry is not None:
                order = OrderRequest(
                    symbol=symbol, side=signal.action,
                    quantity=float(market.get("quantity", 1.0)), price=signal.entry,
                    stop_loss=signal.stop_loss, take_profit=signal.take_profit,
                    timestamp=bar.timestamp,
                    metadata={"strategy": self.strategy.id, "confidence": signal.confidence, **signal.metadata},
                )
                result = self.execution.submit(order)
                self.events.append(ReplayEvent(bar.timestamp, "ORDER", {
                    "status": result.status, "order_id": result.order_id, "reason": result.reason,
                }))
                if result.fill:
                    self._record_fills([result.fill], bar, "ENTRY")
            elif signal.action == "CLOSE" and self.execution.positions():
                result = self.execution.close_position(symbol, bar.timestamp, bar.close)
                self.events.append(ReplayEvent(bar.timestamp, "ORDER", {
                    "status": result.status, "order_id": result.order_id, "reason": result.reason,
                }))
                if result.fill:
                    self._record_fills([result.fill], bar, "STRATEGY_EXIT")

        return self.events

    @staticmethod
    def _default_market_builder(bar: Bar) -> dict[str, Any]:
        return {"symbol": str(bar.metadata.get("symbol", "XAUUSD")), "price": bar.close,
                "atr": bar.atr or 0.0, "confluence": {}, "levels": {},
                "price_action": {}, "liquidity": {}}

    @staticmethod
    def _symbol(bar: Bar) -> str:
        return str(bar.metadata.get("symbol", "XAUUSD"))

    def _record_fills(self, fills: list[Fill], bar: Bar, kind: str) -> None:
        for fill in fills:
            self.events.append(ReplayEvent(bar.timestamp, kind, {
                "order_id": fill.order_id, "symbol": fill.symbol, "side": fill.side,
                "quantity": fill.quantity, "price": fill.price,
                "commission": fill.commission, "slippage": fill.slippage,
            }))
