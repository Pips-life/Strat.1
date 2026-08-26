from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from strat.backtest.models import Bar
from strat.backtest.replay import ReplayEngine
from strat.execution.interface import ExecutionAdapter
from strat.execution.models import Fill, OrderRequest
from strat.risk.engine import RiskEngine, RiskState
from strat.strategies.base import Strategy


@dataclass(frozen=True)
class ReplayEvent:
    timestamp: Any
    kind: str
    payload: dict[str, Any]


class ReplayRunner:
    """Run a real Strategy through the production risk/execution pipeline.

    Replay is an environment, not a special strategy. The strategy generates
    intent; the RiskEngine sizes/approves it; the ExecutionAdapter simulates it.
    Historical options data remains an injectable market_builder.
    """

    def __init__(self, bars: list[Bar], execution: ExecutionAdapter, strategy: Strategy,
                 risk: RiskEngine | None = None,
                 market_builder: Callable[[Bar], dict[str, Any]] | None = None,
                 account_equity: float = 0.0,
                 point_value: float = 1.0,
                 quantity_step: float = 0.01) -> None:
        self.replay = ReplayEngine(bars)
        self.execution = execution
        self.strategy = strategy
        self.risk = risk or RiskEngine()
        self.market_builder = market_builder or self._default_market_builder
        self.account_equity = account_equity
        self.point_value = point_value
        self.quantity_step = quantity_step
        self.events: list[ReplayEvent] = []
        self.state = RiskState()

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
                risk_result = self.risk.evaluate_signal(
                    signal=signal,
                    equity=self.account_equity,
                    point_value=self.point_value,
                    quantity_step=self.quantity_step,
                    current_positions=len(self.execution.positions()),
                    daily_loss=self.state.daily_loss,
                    trades_today=self.state.trades_today,
                    consecutive_losses=self.state.consecutive_losses,
                    timestamp=bar.timestamp,
                )
                self.events.append(ReplayEvent(bar.timestamp, "RISK", {
                    "approved": risk_result.approved,
                    "quantity": risk_result.quantity,
                    "reason": risk_result.reason,
                    "risk_amount": risk_result.risk_amount,
                }))
                if not risk_result.approved:
                    continue

                order = OrderRequest(
                    symbol=symbol, side=signal.action,
                    quantity=risk_result.quantity, price=signal.entry,
                    stop_loss=signal.stop_loss, take_profit=signal.take_profit,
                    timestamp=bar.timestamp,
                    metadata={"strategy": self.strategy.id, "confidence": signal.confidence, **signal.metadata},
                )
                result = self.execution.submit(order)
                self.events.append(ReplayEvent(bar.timestamp, "ORDER", {
                    "status": result.status, "order_id": result.order_id, "reason": result.reason,
                }))
                if result.fill:
                    self.state.trades_today += 1
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
