from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Literal

Side = Literal["LONG", "SHORT"]
ExitReason = Literal["STOP", "TARGET", "TIME", "END_OF_DAY"]


@dataclass(frozen=True)
class Bar:
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0
    atr: float | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class BacktestTrade:
    side: Side
    entry_time: datetime
    entry_price: float
    stop_price: float
    target_price: float
    exit_time: datetime
    exit_price: float
    exit_reason: ExitReason
    risk_points: float
    pnl_points: float
    r_multiple: float
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class BacktestResult:
    trades: list[BacktestTrade]
    initial_equity: float
    final_equity: float
    max_drawdown: float
    win_rate: float
    profit_factor: float
    expectancy_r: float
    average_holding_minutes: float
    total_r: float
    metadata: dict[str, Any] = field(default_factory=dict)
