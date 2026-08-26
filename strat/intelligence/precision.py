"""Precision-aware QOF entry planning.

Precision is treated as a first-class trading input.  The planner does not
predict a future price or force an entry.  It identifies the part of a QOF
structure where a proposed entry has the best risk geometry and tells the
strategy to wait when the live price is materially worse than that location.

This layer is deliberately independent of broker sizing.  Broker/account
constraints are applied by AccountRiskSizer after a precise entry, stop and
target have been selected.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping


@dataclass(frozen=True)
class PrecisionPlan:
    accepted: bool
    entry: float
    preferred_entry: float
    precision_score: float
    reason: str


class PrecisionEntryPlanner:
    """Rank a live QOF entry against the structure's invalidation boundary."""

    def __init__(self, *, preferred_fraction: float = 0.25, minimum_score: float = 70.0) -> None:
        if not 0.0 < preferred_fraction < 0.5:
            raise ValueError("preferred_fraction must be between 0 and 0.5")
        if not 0.0 <= minimum_score <= 100.0:
            raise ValueError("minimum_score must be between 0 and 100")
        self.preferred_fraction = preferred_fraction
        self.minimum_score = minimum_score

    def plan(
        self,
        *,
        side: str,
        price: float,
        zone: Mapping[str, object],
        atr: float,
        stop: float,
        target: float,
        minimum_rr: float,
    ) -> PrecisionPlan:
        side = side.upper()
        if side not in {"LONG", "SHORT"}:
            return PrecisionPlan(False, price, price, 0.0, "invalid direction")
        lower = float(zone["lower"])
        upper = float(zone["upper"])
        if upper <= lower or atr <= 0 or stop == price or target == price:
            return PrecisionPlan(False, price, price, 0.0, "invalid precision geometry")

        width = upper - lower
        # Longs prefer the lower portion of support; shorts prefer the upper
        # portion of resistance.  This reduces stop distance without imposing
        # an arbitrary maximum stop in pips.
        preferred = lower + width * self.preferred_fraction if side == "LONG" else upper - width * self.preferred_fraction
        risk = abs(price - stop)
        reward = (target - price) if side == "LONG" else (price - target)
        rr = reward / risk if risk > 0 else 0.0
        if rr < minimum_rr:
            return PrecisionPlan(False, price, preferred, 0.0, f"current entry provides only {rr:.2f}R")

        # A score of 100 is reserved for the preferred entry.  A price on the
        # favorable side of that point inside the zone is accepted; the farther
        # away on the unfavorable side, the more strongly the planner waits.
        tolerance = max(width * 0.5, atr * 0.10)
        unfavorable_distance = (price - preferred) if side == "LONG" else (preferred - price)
        score = 100.0 if unfavorable_distance <= 0 else max(0.0, 100.0 * (1.0 - unfavorable_distance / tolerance))
        if score < self.minimum_score:
            return PrecisionPlan(False, price, preferred, score, "live price is not precise enough; wait for a better QOF entry")
        return PrecisionPlan(True, price, preferred, score, "precision entry accepted")
