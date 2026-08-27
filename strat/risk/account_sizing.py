"""Account-aware, broker-aware position sizing for all strategies.

Includes an explicit small-account growth regime. The regime is confidence-
gated and has hard loss caps; it never permits a position whose stop-loss risk
would exceed the active regime budget.
"""
from __future__ import annotations
from dataclasses import dataclass
from math import floor

@dataclass(frozen=True)
class BrokerSymbolSpec:
    leverage: float
    tick_size: float
    tick_value: float
    min_volume: float
    max_volume: float
    volume_step: float
    contract_size: float = 1.0

@dataclass(frozen=True)
class AccountSpec:
    balance: float
    equity: float
    free_margin: float

@dataclass(frozen=True)
class SizingPolicy:
    risk_per_trade: float = 0.01
    max_notional_pct: float = 1.0
    min_survival_equity_pct: float = 0.50
    min_margin_buffer_pct: float = 0.20
    risk_budget_utilization: float = 0.95
    small_account_growth_threshold: float = 20.0
    growth_threshold: float = 50.0
    capital_protection_threshold: float = 100.0
    growth_risk_cap: float = 0.15
    transition_risk_cap: float = 0.05
    growth_confidence_floor: float = 80.0
    transition_confidence_floor: float = 90.0

@dataclass(frozen=True)
class SizingDecision:
    approved: bool
    volume: float = 0.0
    risk_amount: float = 0.0
    margin_required: float = 0.0
    notional: float = 0.0
    reason: str = ""
    regime: str = "capital_protection"
    effective_risk_rate: float = 0.0

class AccountRiskSizer:
    """Find the safest executable broker volume for a proposed trade."""
    def __init__(self, policy: SizingPolicy | None = None) -> None:
        self.policy = policy or SizingPolicy()

    def _regime(self, equity: float) -> str:
        if equity < self.policy.small_account_growth_threshold:
            return "small_account_growth"
        if equity < self.policy.growth_threshold:
            return "growth"
        if equity < self.policy.capital_protection_threshold:
            return "transition"
        return "capital_protection"

    def _risk_rate(self, equity: float, confidence: float) -> tuple[str, float]:
        regime = self._regime(equity)
        base = self.policy.risk_per_trade
        if regime == "small_account_growth":
            if confidence < self.policy.growth_confidence_floor:
                return regime, base
            if confidence >= 95.0:
                return regime, min(self.policy.growth_risk_cap, 0.15)
            if confidence >= 90.0:
                return regime, min(self.policy.growth_risk_cap, 0.10)
            return regime, min(self.policy.growth_risk_cap, 0.05)
        if regime == "growth":
            if confidence < self.policy.growth_confidence_floor:
                return regime, base
            return regime, min(self.policy.transition_risk_cap, 0.05 if confidence >= 90.0 else 0.025)
        if regime == "transition":
            if confidence < self.policy.transition_confidence_floor:
                return regime, base
            return regime, min(self.policy.transition_risk_cap, 0.02)
        return regime, base

    @staticmethod
    def _round_down(value: float, step: float) -> float:
        if step <= 0:
            return 0.0
        return floor(value / step) * step

    def evaluate(self, account: AccountSpec, broker: BrokerSymbolSpec, entry: float, stop_loss: float, confidence: float = 0.0) -> SizingDecision:
        if account.equity <= 0 or account.balance <= 0 or account.free_margin < 0:
            return SizingDecision(False, reason="invalid account state")
        if not 0 <= confidence <= 100:
            return SizingDecision(False, reason="invalid confidence")
        if min(broker.leverage, broker.tick_size, broker.tick_value, broker.min_volume, broker.max_volume, broker.volume_step) <= 0:
            return SizingDecision(False, reason="invalid broker symbol specification")
        if broker.min_volume > broker.max_volume:
            return SizingDecision(False, reason="broker volume bounds are invalid")
        stop_distance = abs(entry - stop_loss)
        if stop_distance <= 0:
            return SizingDecision(False, reason="zero stop distance")
        utilization = self.policy.risk_budget_utilization
        if not 0 < utilization <= 1:
            return SizingDecision(False, reason="invalid risk budget utilization")

        regime, risk_rate = self._risk_rate(account.equity, confidence)
        nominal_budget = account.equity * risk_rate
        survival_floor = account.balance * self.policy.min_survival_equity_pct
        survival_budget = max(0.0, account.equity - survival_floor)
        risk_budget = min(nominal_budget * utilization, survival_budget)
        if risk_budget <= 0:
            return SizingDecision(False, reason="no account-survival risk budget available", regime=regime, effective_risk_rate=risk_rate)

        ticks = stop_distance / broker.tick_size
        risk_per_volume = ticks * broker.tick_value
        max_risk_volume = risk_budget / risk_per_volume
        volume = self._round_down(min(max_risk_volume, broker.max_volume), broker.volume_step)
        if volume < broker.min_volume:
            return SizingDecision(False, reason="broker minimum volume exceeds safe stop-loss risk budget", regime=regime, effective_risk_rate=risk_rate)

        volume = max(broker.min_volume, volume)
        risk_amount = risk_per_volume * volume
        if risk_amount > risk_budget + 1e-12:
            return SizingDecision(False, reason="broker minimum volume exceeds safe stop-loss risk budget", regime=regime, effective_risk_rate=risk_rate)

        notional = abs(entry * broker.contract_size * volume)
        max_notional = account.equity * self.policy.max_notional_pct
        if notional > max_notional:
            return SizingDecision(False, reason="notional exposure exceeds account limit", regime=regime, effective_risk_rate=risk_rate)

        margin_required = notional / broker.leverage
        available_after = account.free_margin - margin_required
        if available_after < account.equity * self.policy.min_margin_buffer_pct:
            return SizingDecision(False, reason="insufficient free-margin buffer", regime=regime, effective_risk_rate=risk_rate)

        return SizingDecision(True, volume, risk_amount, margin_required, notional, "approved", regime, risk_rate)
