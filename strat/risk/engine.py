"""Global risk guardrails, deliberately independent of strategies."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class RiskLimits:
    risk_per_trade: float = 0.01
    max_positions: int = 1
    max_daily_loss: float = 0.03


class RiskEngine:
    def __init__(self, limits: RiskLimits | None = None) -> None:
        self.limits = limits or RiskLimits()

    def approve(self, *, confidence: float, current_positions: int, daily_loss: float) -> bool:
        if confidence < 0 or confidence > 100:
            return False
        if current_positions >= self.limits.max_positions:
            return False
        if daily_loss >= self.limits.max_daily_loss:
            return False
        return True
