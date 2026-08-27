"""Account-aware, broker-aware position sizing for all strategies.

The sizing policy is risk-first, not account-size-first: a small account is
not rejected merely because it is small. The engine searches for a better,
more precise QOF setup and evaluates the actual broker minimum volume against
the stop distance. A wider stop is acceptable when the setup warrants it,
because risk is controlled by monetary loss at the stop, not by an arbitrary
maximum pip distance.
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


@dataclass(frozen=True)
class SizingDecision:
    approved: bool
    volume: float = 0.0
    risk_amount: float = 0.0
    margin_required: float = 0.0
    notional: float = 0.0
    reason: str = ""


class AccountRiskSizer:
    """Find the safest executable broker volume for a proposed trade."""

    def __init__(self, policy: SizingPolicy | None = None) -> None:
        self.policy = policy or SizingPolicy()

    @staticmethod
    def _round_down(value: float, step: float) -> float:
        if step <= 0:
            return 0.0
        return floor(value / step) * step

    def evaluate(
        self,
        account: AccountSpec,
        broker: BrokerSymbolSpec,
        entry: float,
        stop_loss: float,
    ) -> SizingDecision:
        """Evaluate a trade.

        Positional arguments are intentionally accepted for backward
        compatibility with existing integrations; new callers may still pass
        the same four arguments by keyword.
        """
        if account.equity <= 0 or account.balance <= 0 or account.free_margin < 0:
            return SizingDecision(False, reason="invalid account state")
        if min(broker.leverage, broker.tick_size, broker.tick_value, broker.min_volume, broker.max_volume, broker.volume_step) <= 0:
            return SizingDecision(False, reason="invalid broker symbol specification")
        if broker.min_volume > broker.max_volume:
            return SizingDecision(False, reason="broker volume bounds are invalid")

        stop_distance = abs(entry - stop_loss)
        if stop_distance <= 0:
            return SizingDecision(False, reason="zero stop distance")

        risk_budget = account.equity * self.policy.risk_per_trade
        survival_floor = account.balance * self.policy.min_survival_equity_pct
        survival_budget = max(0.0, account.equity - survival_floor)
        risk_budget = min(risk_budget, survival_budget)
        if risk_budget <= 0:
            return SizingDecision(False, reason="no account-survival risk budget available")

        ticks = stop_distance / broker.tick_size
        risk_per_volume = ticks * broker.tick_value
        max_risk_volume = risk_budget / risk_per_volume
        volume = self._round_down(min(max_risk_volume, broker.max_volume), broker.volume_step)
        if volume < broker.min_volume:
            return SizingDecision(False, reason="broker minimum volume exceeds safe stop-loss risk budget")

        volume = max(broker.min_volume, volume)
        risk_amount = risk_per_volume * volume
        notional = abs(entry * broker.contract_size * volume)
        max_notional = account.equity * self.policy.max_notional_pct
        if notional > max_notional:
            return SizingDecision(False, reason="notional exposure exceeds account limit")

        margin_required = notional / broker.leverage
        available_after = account.free_margin - margin_required
        if available_after < account.equity * self.policy.min_margin_buffer_pct:
            return SizingDecision(False, reason="insufficient free-margin buffer")

        return SizingDecision(True, volume, risk_amount, margin_required, notional, "approved")
