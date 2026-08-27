"""Strategy-independent risk controls for replay, demo, and live trading.

The risk engine is deliberately broker- and strategy-neutral. It approves or
rejects a proposed trade, calculates a risk-based quantity, tracks daily risk
state, and enforces the project's low-equity/intraday guardrails.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, time
from math import floor
from typing import Optional


@dataclass(frozen=True)
class RiskLimits:
    """Global safety limits.

    Percentages are expressed as decimals: 0.01 == 1%.
    """

    risk_per_trade: float = 0.01
    # AccountRiskSizer owns the broker-aware small-account safety reserve.
    # Keep the strategy-independent engine's sizing contract deterministic.
    risk_budget_utilization: float = 1.0
    max_positions: int = 1
    max_daily_loss: float = 0.03
    max_trades_per_day: int = 5
    max_consecutive_losses: int = 3
    min_reward_risk: float = 1.35
    max_position_notional_pct: float = 1.0
    min_confidence: float = 70.0
    flatten_minutes_before_close: int = 15
    session_start: time = time(7, 0)
    session_end: time = time(22, 0)
    allow_overnight: bool = False
    quantity_step: float = 0.01
    min_quantity: float = 0.01


@dataclass(frozen=True)
class RiskRequest:
    """Inputs required to approve and size one proposed position."""

    side: str
    entry: float
    stop_loss: float
    take_profit: float
    equity: float
    current_positions: int = 0
    daily_pnl: float = 0.0
    trades_today: int = 0
    consecutive_losses: int = 0
    now: Optional[datetime] = None
    point_value: float = 1.0
    confidence: float = 0.0


@dataclass(frozen=True)
class RiskDecision:
    approved: bool
    quantity: float = 0.0
    risk_amount: float = 0.0
    reward_risk: float = 0.0
    reason: str = ""


class RiskEngine:
    """Global risk gate shared by every strategy and every environment."""

    def __init__(self, limits: RiskLimits | None = None) -> None:
        self.limits = limits or RiskLimits()

    @staticmethod
    def _valid_side(side: str) -> bool:
        return side.upper() in {"BUY", "SELL"}

    @staticmethod
    def _reward_risk(side: str, entry: float, stop: float, target: float) -> float:
        risk = abs(entry - stop)
        reward = (target - entry) if side.upper() == "BUY" else (entry - target)
        return reward / risk if risk > 0 else 0.0

    def in_session(self, now: datetime) -> bool:
        current = now.time()
        start, end = self.limits.session_start, self.limits.session_end
        if start <= end:
            return start <= current < end
        return current >= start or current < end

    def near_session_close(self, now: datetime) -> bool:
        current = now.time()
        end = self.limits.session_end
        minutes = current.hour * 60 + current.minute + current.second / 60
        end_minutes = end.hour * 60 + end.minute + end.second / 60
        if end_minutes < minutes:
            end_minutes += 24 * 60
        return 0 <= end_minutes - minutes <= self.limits.flatten_minutes_before_close

    def calculate_quantity(
        self,
        *,
        equity: float,
        entry: float,
        stop_loss: float,
        point_value: float = 1.0,
    ) -> float:
        """Calculate quantity from account equity and stop distance.

        This generic engine deliberately performs no account-regime adjustment.
        Broker-aware small-account policy belongs in AccountRiskSizer.
        """
        if equity <= 0 or point_value <= 0:
            return 0.0
        stop_distance = abs(entry - stop_loss)
        if stop_distance <= 0:
            return 0.0
        utilization = self.limits.risk_budget_utilization
        if not 0 < utilization <= 1:
            return 0.0
        risk_budget = equity * self.limits.risk_per_trade * utilization
        raw = risk_budget / (stop_distance * point_value)
        step = self.limits.quantity_step
        quantity = floor(raw / step) * step
        if quantity < self.limits.min_quantity:
            return 0.0
        return round(quantity, 10)

    def evaluate(self, request: RiskRequest) -> RiskDecision:
        limits = self.limits
        side = request.side.upper()

        if not self._valid_side(side):
            return RiskDecision(False, reason="invalid side")
        if request.equity <= 0:
            return RiskDecision(False, reason="non-positive equity")
        if not (0 <= request.confidence <= 100):
            return RiskDecision(False, reason="invalid confidence")
        if request.confidence < limits.min_confidence:
            return RiskDecision(False, reason="confidence below risk threshold")
        if request.current_positions >= limits.max_positions:
            return RiskDecision(False, reason="maximum simultaneous positions reached")
        if request.daily_pnl <= -(request.equity * limits.max_daily_loss):
            return RiskDecision(False, reason="maximum daily loss reached")
        if request.trades_today >= limits.max_trades_per_day:
            return RiskDecision(False, reason="maximum daily trades reached")
        if request.consecutive_losses >= limits.max_consecutive_losses:
            return RiskDecision(False, reason="consecutive-loss limit reached")
        if request.now is not None:
            if not self.in_session(request.now):
                return RiskDecision(False, reason="outside configured trading session")
            if self.near_session_close(request.now):
                return RiskDecision(False, reason="too close to session close for new entry")

        if side == "BUY":
            if not (request.stop_loss < request.entry < request.take_profit):
                return RiskDecision(False, reason="invalid BUY stop/target geometry")
        else:
            if not (request.take_profit < request.entry < request.stop_loss):
                return RiskDecision(False, reason="invalid SELL stop/target geometry")

        rr = self._reward_risk(side, request.entry, request.stop_loss, request.take_profit)
        if rr < limits.min_reward_risk:
            return RiskDecision(False, reward_risk=rr, reason="reward/risk below minimum")

        quantity = self.calculate_quantity(
            equity=request.equity,
            entry=request.entry,
            stop_loss=request.stop_loss,
            point_value=request.point_value,
        )
        if quantity <= 0:
            return RiskDecision(False, reward_risk=rr, reason="minimum executable quantity exceeds risk budget")

        risk_amount = abs(request.entry - request.stop_loss) * request.point_value * quantity
        notional = abs(request.entry * request.point_value * quantity)
        if notional > request.equity * limits.max_position_notional_pct:
            return RiskDecision(False, quantity, risk_amount, rr, "position notional exceeds equity limit")

        return RiskDecision(True, quantity, risk_amount, rr, "approved")

    def approve(self, *, confidence: float, current_positions: int, daily_loss: float) -> bool:
        return (
            0 <= confidence <= 100
            and confidence >= self.limits.min_confidence
            and current_positions < self.limits.max_positions
            and daily_loss < self.limits.max_daily_loss
        )

    def should_flatten(self, now: datetime) -> bool:
        if self.limits.allow_overnight:
            return False
        return now.time() >= self.limits.session_end
