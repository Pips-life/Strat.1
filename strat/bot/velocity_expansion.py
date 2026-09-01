"""Execution controller for Strategy 002's one-position/one-opposite-stop loop."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Dict

from strat.execution.interface import ExecutionAdapter
from strat.execution.models import ExecutionResult, Fill, OrderRequest
from strat.strategies.strategy_002 import Strategy002, Strategy002Config
from strat.strategies.base import Signal


@dataclass
class VelocityPair:
    position_order_id: str
    position_side: str
    quantity: float
    stop_order_id: str
    stop_price: float


class VelocityExpansionController:
    """Maintains a trailing opposite STOP for every Strategy 002 position.

    For BUY: SELL STOP = current reference price - 100 pips.
    For SELL: BUY STOP = current reference price + 100 pips.

    A triggered stop is treated as the reversal event: the source position is
    closed, the stop direction becomes the new market position, and a fresh
    opposite stop is installed. The controller never widens a trailing stop.
    """

    def __init__(self, execution: ExecutionAdapter, strategy: Strategy002 | None = None) -> None:
        self.execution = execution
        self.strategy = strategy or Strategy002()
        self._pairs: Dict[str, VelocityPair] = {}

    @property
    def pairs(self) -> dict[str, VelocityPair]:
        return dict(self._pairs)

    def enter(self, symbol: str, signal: Signal, *, balance: float, tick_size: float, tick_value: float,
              timestamp: datetime | None = None) -> ExecutionResult:
        if signal.action not in {"BUY", "SELL"} or signal.entry is None:
            return ExecutionResult("REJECTED", "", reason="Strategy 002 has no executable entry")
        quantity = self.strategy.calculate_quantity(
            balance=balance, entry=signal.entry, tick_size=tick_size, tick_value=tick_value
        )
        if quantity <= 0:
            return ExecutionResult("REJECTED", "", reason="balance is too small for configured risk and 100-pip distance")
        order = OrderRequest(
            symbol=symbol, side=signal.action, quantity=quantity, order_type="MARKET",
            price=signal.entry, timestamp=timestamp,
            metadata={"strategy_id": self.strategy.id, "velocity_expansion": True},
        )
        result = self.execution.submit(order)
        if result.status != "FILLED" or result.fill is None:
            return result
        self._install_stop(symbol, result.fill, timestamp or result.fill.timestamp)
        return result

    def on_bar(self, symbol: str, timestamp: datetime, high: float, low: float, close: float) -> list[Fill]:
        # Trail each live pair before processing the bar so the stop is always
        # exactly the configured distance behind the latest price reference.
        pair = self._pairs.get(symbol)
        if pair is not None:
            self._trail(symbol, pair, close, timestamp)

        fills = self.execution.on_bar(symbol, timestamp, high, low, close)
        for fill in fills:
            pair = self._pairs.get(symbol)
            if pair is None or fill.order_id != pair.stop_order_id:
                continue
            # The STOP order is the reversal trigger. Close the source position,
            # then create the new running position at the triggered price.
            self.execution.close_position(symbol, timestamp, price=fill.price)
            self._pairs.pop(symbol, None)
            result = self.execution.submit(OrderRequest(
                symbol=symbol, side=fill.side, quantity=fill.quantity, order_type="MARKET",
                price=fill.price, timestamp=timestamp,
                metadata={"strategy_id": self.strategy.id, "velocity_expansion_reversal": True},
            ))
            if result.status == "FILLED" and result.fill is not None:
                self._install_stop(symbol, result.fill, timestamp)
        return fills

    def close_all(self, timestamp: datetime, prices: dict[str, float]) -> None:
        for symbol, pair in list(self._pairs.items()):
            self.execution.cancel_order(pair.stop_order_id)
            if symbol in prices:
                self.execution.close_position(symbol, timestamp, prices[symbol])
            self._pairs.pop(symbol, None)

    def _install_stop(self, symbol: str, fill: Fill, timestamp: datetime) -> None:
        distance = self.strategy.config.trail_distance
        stop_side = "SELL" if fill.side == "BUY" else "BUY"
        stop_price = fill.price - distance if fill.side == "BUY" else fill.price + distance
        stop_id = f"VEL2-STOP-{fill.order_id}"
        result = self.execution.submit(OrderRequest(
            symbol=symbol, side=stop_side, quantity=fill.quantity, order_type="STOP",
            price=stop_price, client_order_id=stop_id, timestamp=timestamp,
            metadata={
                "strategy_id": self.strategy.id,
                "paired_position_order_id": fill.order_id,
                "trail_pips": self.strategy.config.trail_pips,
                "oco_reversal": True,
            },
        ))
        if result.status != "NEW":
            raise RuntimeError(f"failed to install paired opposite stop: {result.reason}")
        self._pairs[symbol] = VelocityPair(fill.order_id, fill.side, fill.quantity, stop_id, stop_price)

    def _trail(self, symbol: str, pair: VelocityPair, reference_price: float, timestamp: datetime) -> None:
        distance = self.strategy.config.trail_distance
        candidate = reference_price - distance if pair.position_side == "BUY" else reference_price + distance
        # BUY trailing stop only rises; SELL trailing stop only falls.
        improves = candidate > pair.stop_price if pair.position_side == "BUY" else candidate < pair.stop_price
        if not improves:
            return
        cancelled = self.execution.cancel_order(pair.stop_order_id)
        if cancelled.status != "CANCELLED":
            return
        stop_side = "SELL" if pair.position_side == "BUY" else "BUY"
        result = self.execution.submit(OrderRequest(
            symbol=symbol, side=stop_side, quantity=pair.quantity, order_type="STOP",
            price=candidate, client_order_id=pair.stop_order_id, timestamp=timestamp,
            metadata={"strategy_id": self.strategy.id, "paired_position_order_id": pair.position_order_id,
                      "trail_pips": self.strategy.config.trail_pips, "oco_reversal": True},
        ))
        if result.status == "NEW":
            pair.stop_price = candidate
