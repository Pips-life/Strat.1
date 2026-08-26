from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class EquityPoint:
    timestamp: datetime
    equity: float
    realized_pnl: float
    drawdown: float


class EquityCurve:
    """Chronological equity curve for replay/backtest reporting."""

    def __init__(self, initial_equity: float) -> None:
        self.initial_equity = initial_equity
        self.peak_equity = initial_equity
        self.points: list[EquityPoint] = []

    def record(self, timestamp: datetime, equity: float) -> EquityPoint:
        self.peak_equity = max(self.peak_equity, equity)
        point = EquityPoint(
            timestamp=timestamp,
            equity=equity,
            realized_pnl=equity - self.initial_equity,
            drawdown=max(0.0, self.peak_equity - equity),
        )
        self.points.append(point)
        return point

    @property
    def max_drawdown(self) -> float:
        return max((p.drawdown for p in self.points), default=0.0)

    def as_dicts(self) -> list[dict[str, object]]:
        return [
            {"timestamp": p.timestamp.isoformat(), "equity": p.equity,
             "realized_pnl": p.realized_pnl, "drawdown": p.drawdown}
            for p in self.points
        ]
