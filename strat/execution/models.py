from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Literal

Side = Literal["BUY", "SELL"]
OrderType = Literal["MARKET", "LIMIT", "STOP"]
OrderStatus = Literal["NEW", "FILLED", "REJECTED", "CANCELLED"]


@dataclass(frozen=True)
class OrderRequest:
    symbol: str
    side: Side
    quantity: float
    order_type: OrderType = "MARKET"
    price: float | None = None
    stop_loss: float | None = None
    take_profit: float | None = None
    client_order_id: str | None = None
    timestamp: datetime | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Fill:
    order_id: str
    symbol: str
    side: Side
    quantity: float
    price: float
    timestamp: datetime
    commission: float = 0.0
    slippage: float = 0.0


@dataclass
class Position:
    symbol: str
    side: Side
    quantity: float
    entry_price: float
    opened_at: datetime
    stop_loss: float | None = None
    take_profit: float | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ExecutionResult:
    status: OrderStatus
    order_id: str
    fill: Fill | None = None
    reason: str | None = None
