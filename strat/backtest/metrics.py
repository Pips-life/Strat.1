from __future__ import annotations

from statistics import mean
from .models import BacktestTrade


def summarize_trades(trades: list[BacktestTrade], initial_equity: float, risk_cash: float) -> dict[str, float]:
    if not trades:
        return {"trades": 0.0, "win_rate": 0.0, "profit_factor": 0.0, "expectancy_r": 0.0, "total_r": 0.0}
    wins = [t for t in trades if t.r_multiple > 0]
    gross_profit = sum(t.r_multiple for t in trades if t.r_multiple > 0)
    gross_loss = abs(sum(t.r_multiple for t in trades if t.r_multiple < 0))
    holds = [(t.exit_time - t.entry_time).total_seconds() / 60 for t in trades]
    return {
        "trades": float(len(trades)),
        "win_rate": len(wins) / len(trades),
        "profit_factor": gross_profit / gross_loss if gross_loss else float("inf"),
        "expectancy_r": mean(t.r_multiple for t in trades),
        "total_r": sum(t.r_multiple for t in trades),
        "average_holding_minutes": mean(holds),
        "initial_equity": initial_equity,
        "risk_cash": risk_cash,
    }
