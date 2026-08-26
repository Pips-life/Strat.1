from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any

from strat.backtest.ledger import TradeLedger
from strat.backtest.models import BacktestResult, BacktestTrade


@dataclass(frozen=True)
class EquityPoint:
    timestamp: datetime
    equity: float
    realized_pnl: float
    drawdown: float


def build_result(ledger: TradeLedger, equity_curve: list[EquityPoint] | None = None) -> BacktestResult:
    trades: list[BacktestTrade] = []
    for t in ledger.trades:
        trades.append(BacktestTrade(
            side="LONG" if t.side == "BUY" else "SHORT",
            entry_time=t.entry_time,
            entry_price=t.entry_price,
            stop_price=t.entry_price,
            target_price=t.exit_price,
            exit_time=t.exit_time,
            exit_price=t.exit_price,
            exit_reason=t.reason if t.reason in {"STOP", "TARGET", "TIME", "END_OF_DAY"} else "TIME",
            risk_points=abs(t.entry_price - t.exit_price),
            pnl_points=t.pnl / t.quantity if t.quantity else 0.0,
            r_multiple=t.r_multiple,
            metadata={"commission": t.commission, "slippage": t.slippage},
        ))
    wins = [t.r_multiple for t in trades if t.r_multiple > 0]
    losses = [t.r_multiple for t in trades if t.r_multiple < 0]
    gross_profit = sum(wins)
    gross_loss = abs(sum(losses))
    total_r = sum(t.r_multiple for t in trades)
    pf = gross_profit / gross_loss if gross_loss else (float("inf") if gross_profit else 0.0)
    avg_hold = sum((t.exit_time - t.entry_time).total_seconds() / 60 for t in trades) / len(trades) if trades else 0.0
    curve = equity_curve or []
    max_dd = max((p.drawdown for p in curve), default=ledger.state.max_drawdown)
    return BacktestResult(
        trades=trades, initial_equity=ledger.initial_equity, final_equity=ledger.equity,
        max_drawdown=max_dd, win_rate=(len(wins) / len(trades) * 100) if trades else 0.0,
        profit_factor=pf, expectancy_r=(total_r / len(trades)) if trades else 0.0,
        average_holding_minutes=avg_hold, total_r=total_r,
        metadata={"equity_curve_points": len(curve)},
    )


def result_to_dict(result: BacktestResult) -> dict[str, Any]:
    return {
        "initial_equity": result.initial_equity, "final_equity": result.final_equity,
        "max_drawdown": result.max_drawdown, "win_rate": result.win_rate,
        "profit_factor": result.profit_factor, "expectancy_r": result.expectancy_r,
        "average_holding_minutes": result.average_holding_minutes, "total_r": result.total_r,
        "trade_count": len(result.trades), "metadata": result.metadata,
    }
