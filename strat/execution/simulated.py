from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from itertools import count
from .interface import ExecutionAdapter
from .models import ExecutionResult, Fill, OrderRequest, Position


@dataclass(frozen=True)
class SimulationConfig:
    slippage_points: float = 0.0
    commission_per_unit: float = 0.0
    reject_zero_quantity: bool = True
    allow_multiple_positions: bool = False


class SimulatedExecution(ExecutionAdapter):
    """Deterministic replay/paper broker supporting MARKET and STOP orders."""

    def __init__(self, config: SimulationConfig | None = None):
        self.config = config or SimulationConfig()
        self._positions: list[Position] = []
        self._pending: dict[str, OrderRequest] = {}
        self._ids = count(1)

    def submit(self, order: OrderRequest) -> ExecutionResult:
        if self.config.reject_zero_quantity and order.quantity <= 0:
            return ExecutionResult("REJECTED", "", reason="quantity must be positive")
        oid = order.client_order_id or f"SIM-{next(self._ids):08d}"
        if order.order_type == "STOP":
            if order.price is None:
                return ExecutionResult("REJECTED", oid, reason="stop order requires a trigger price")
            self._pending[oid] = OrderRequest(
                symbol=order.symbol, side=order.side, quantity=order.quantity,
                order_type="STOP", price=order.price, stop_loss=order.stop_loss,
                take_profit=order.take_profit, client_order_id=oid,
                timestamp=order.timestamp, metadata=dict(order.metadata),
            )
            return ExecutionResult("NEW", oid)
        if order.order_type != "MARKET":
            return ExecutionResult("REJECTED", oid, reason="unsupported order type")
        if not self.config.allow_multiple_positions and any(p.symbol == order.symbol for p in self._positions):
            return ExecutionResult("REJECTED", oid, reason="position already open")
        if order.price is None:
            return ExecutionResult("REJECTED", oid, reason="market replay requires a fill price")

        slip = self.config.slippage_points if order.side == "BUY" else -self.config.slippage_points
        fill_price = order.price + slip
        ts = order.timestamp or datetime.utcnow()
        fill = Fill(oid, order.symbol, order.side, order.quantity, fill_price, ts,
                    commission=order.quantity * self.config.commission_per_unit,
                    slippage=slip)
        self._positions.append(Position(
            symbol=order.symbol, side=order.side, quantity=order.quantity,
            entry_price=fill_price, opened_at=ts, stop_loss=order.stop_loss,
            take_profit=order.take_profit, metadata=dict(order.metadata),
        ))
        return ExecutionResult("FILLED", oid, fill=fill)

    def cancel_order(self, order_id: str) -> ExecutionResult:
        if order_id not in self._pending:
            return ExecutionResult("REJECTED", order_id, reason="pending order not found")
        del self._pending[order_id]
        return ExecutionResult("CANCELLED", order_id)

    def on_bar(self, symbol: str, timestamp: datetime, high: float, low: float, close: float) -> list[Fill]:
        fills: list[Fill] = []
        for oid, order in list(self._pending.items()):
            if order.symbol != symbol or order.price is None:
                continue
            triggered = (order.side == "BUY" and high >= order.price) or (order.side == "SELL" and low <= order.price)
            if not triggered:
                continue
            del self._pending[oid]
            fill = Fill(oid, symbol, order.side, order.quantity, order.price, timestamp,
                        commission=order.quantity * self.config.commission_per_unit)
            fills.append(fill)
        # Existing position-native SL/TP support remains for ordinary strategies.
        for position in list(self._positions):
            if position.symbol != symbol:
                continue
            exit_price = None
            reason = None
            if position.side == "BUY":
                if position.stop_loss is not None and low <= position.stop_loss:
                    exit_price, reason = position.stop_loss, "STOP"
                elif position.take_profit is not None and high >= position.take_profit:
                    exit_price, reason = position.take_profit, "TARGET"
            else:
                if position.stop_loss is not None and high >= position.stop_loss:
                    exit_price, reason = position.stop_loss, "STOP"
                elif position.take_profit is not None and low <= position.take_profit:
                    exit_price, reason = position.take_profit, "TARGET"
            if exit_price is not None:
                fills.append(self._close(position, timestamp, exit_price, reason))
        return fills

    def close_position(self, symbol: str, timestamp: datetime, price: float | None = None) -> ExecutionResult:
        matches = [p for p in self._positions if p.symbol == symbol]
        if not matches:
            return ExecutionResult("REJECTED", "", reason="no open position")
        if price is None:
            return ExecutionResult("REJECTED", "", reason="close price required by simulator")
        fill = self._close(matches[0], timestamp, price, "END_OF_DAY")
        return ExecutionResult("FILLED", fill.order_id, fill=fill)

    def positions(self) -> list[Position]:
        return list(self._positions)

    def _close(self, position: Position, timestamp: datetime, price: float, reason: str) -> Fill:
        oid = f"SIM-CLOSE-{next(self._ids):08d}"
        side = "SELL" if position.side == "BUY" else "BUY"
        slip = -self.config.slippage_points if side == "SELL" else self.config.slippage_points
        fill = Fill(oid, position.symbol, side, position.quantity, price + slip, timestamp,
                    commission=position.quantity * self.config.commission_per_unit,
                    slippage=slip)
        self._positions.remove(position)
        return fill
