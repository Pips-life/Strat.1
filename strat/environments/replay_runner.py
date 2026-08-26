from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from strat.backtest.models import Bar
from strat.backtest.replay import ReplayEngine
from strat.execution.interface import ExecutionAdapter
from strat.execution.models import Fill, OrderRequest
from strat.risk.engine import RiskEngine, RiskRequest
from strat.strategies.base import Strategy


@dataclass(frozen=True)
class ReplayEvent:
    timestamp: Any
    kind: str
    payload: dict[str, Any]


class ReplayRunner:
    """Run a Strategy through the production risk and execution pipeline."""

    def __init__(self, bars: list[Bar], execution: ExecutionAdapter, strategy: Strategy,
                 risk: RiskEngine | None = None,
                 market_builder: Callable[[Bar], dict[str, Any]] | None = None,
                 account_equity: float = 0.0, point_value: float = 1.0,
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
        self.daily_pnl = 0.0
        self.trades_today = 0
        self.consecutive_losses = 0

    def run(self) -> list[ReplayEvent]:
        for bar in self.replay.stream():
            symbol = self._symbol(bar)
            protective = self.execution.on_bar(symbol, bar.timestamp, bar.high, bar.low, bar.close)
            self._record_fills(protective, bar, "PROTECTIVE_EXIT")

            # Global no-overnight guard: flatten before the strategy can open a new trade.
            if self.risk.should_flatten(bar.timestamp) and self.execution.positions():
                result = self.execution.close_position(symbol, bar.timestamp, bar.close)
                self.events.append(ReplayEvent(bar.timestamp, "ORDER", {
                    "status": result.status, "order_id": result.order_id,
                    "reason": "risk: end-of-day flatten",
                }))
                if result.fill:
                    self._record_fills([result.fill], bar, "END_OF_DAY_EXIT")
                continue

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

            if signal.action in {"BUY", "SELL"} and signal.entry is not None and signal.stop_loss is not None and signal.take_profit is not None:
                request = RiskRequest(
                    side=signal.action, entry=signal.entry, stop_loss=signal.stop_loss,
                    take_profit=signal.take_profit, equity=self.account_equity,
                    current_positions=len(self.execution.positions()), daily_pnl=self.daily_pnl,
                    trades_today=self.trades_today, consecutive_losses=self.consecutive_losses,
                    now=bar.timestamp, point_value=self.point_value,
                    confidence=signal.confidence,
                )
                decision = self.risk.evaluate(request)
                self.events.append(ReplayEvent(bar.timestamp, "RISK", {
                    "approved": decision.approved, "quantity": decision.quantity,
                    "reason": decision.reason, "risk_amount": decision.risk_amount,
                    "reward_risk": decision.reward_risk,
                }))
                if not decision.approved:
                    continue

                order = OrderRequest(
                    symbol=symbol, side=signal.action, quantity=decision.quantity,
                    price=signal.entry, stop_loss=signal.stop_loss,
                    take_profit=signal.take_profit, timestamp=bar.timestamp,
                    metadata={"strategy": self.strategy.id, "confidence": signal.confidence, **signal.metadata},
                )
                result = self.execution.submit(order)
                self.events.append(ReplayEvent(bar.timestamp, "ORDER", {
                    "status": result.status, "order_id": result.order_id, "reason": result.reason,
                }))
                if result.fill:
                    self.trades_today += 1
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
