"""Precision-aware QOF entry planning.

Precision is a first-class trading input.  The planner does not predict a
future price or force an entry.  It identifies the part of a QOF structure
where a proposed entry has the best risk geometry and tells the strategy to
wait when the live price is materially worse than that location.

The module is deliberately independent of broker sizing. Broker/account
constraints are applied after a precise entry, stop and target have been
selected.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Sequence


@dataclass(frozen=True)
class PrecisionPlan:
    accepted: bool
    entry: float
    preferred_entry: float
    precision_score: float
    reason: str


@dataclass(frozen=True)
class MultiTimeframePrecisionPlan:
    accepted: bool
    execution_timeframe: str
    context_timeframes: tuple[str, ...]
    preferred_entry: float
    precision_score: float
    aligned_context: bool
    reason: str
    htf_respected: bool = False


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
        preferred = lower + width * self.preferred_fraction if side == "LONG" else upper - width * self.preferred_fraction
        risk = abs(price - stop)
        reward = (target - price) if side == "LONG" else (price - target)
        rr = reward / risk if risk > 0 else 0.0
        if rr < minimum_rr:
            return PrecisionPlan(False, price, preferred, 0.0, f"current entry provides only {rr:.2f}R")

        tolerance = max(width * 0.5, atr * 0.10)
        unfavorable_distance = (price - preferred) if side == "LONG" else (preferred - price)
        score = 100.0 if unfavorable_distance <= 0 else max(0.0, 100.0 * (1.0 - unfavorable_distance / tolerance))
        if score < self.minimum_score:
            return PrecisionPlan(False, price, preferred, score, "live price is not precise enough; wait for a better QOF entry")
        return PrecisionPlan(True, price, preferred, score, "precision entry accepted")


class MultiTimeframePrecisionPlanner:
    """Use HTF QOF zones as context and accept explicit LTF respect as confirmation.

    Higher timeframes establish structural context; they do not manufacture
direction. The execution timeframe normally provides a QOF structure compatible
with the selected side. A higher-timeframe QOF zone that is explicitly
*respected by the execution timeframe* is also valid confirmation: the LTF
reaction validates the HTF location rather than requiring a duplicate LTF zone.
Price/TradingView structure cannot satisfy this requirement by itself.
    """

    def __init__(self, entry_planner: PrecisionEntryPlanner | None = None, *, context_bonus: float = 10.0) -> None:
        if context_bonus < 0.0 or context_bonus > 25.0:
            raise ValueError("context_bonus must be between 0 and 25")
        self.entry_planner = entry_planner or PrecisionEntryPlanner()
        self.context_bonus = context_bonus

    @staticmethod
    def _qof(zone: Mapping[str, object]) -> bool:
        return bool(zone.get("qof_primary", zone.get("origin", "QOF_IMPLIED") == "QOF_IMPLIED"))

    @staticmethod
    def _role(zone: Mapping[str, object]) -> str:
        role = zone.get("role", "")
        return str(getattr(role, "value", role)).upper()

    @staticmethod
    def _timeframe(zone: Mapping[str, object], default: str) -> str:
        return str(zone.get("timeframe", default))

    @staticmethod
    def _ltf_respects_htf(zone: Mapping[str, object], side: str) -> bool:
        """Consume an explicit causal LTF confirmation of an HTF QOF zone.

        Upstream market-structure processing owns the actual price-action
        recognition. This planner consumes its normalized result so it never
        invents a future reaction or uses look-ahead data.
        """
        if not MultiTimeframePrecisionPlanner._qof(zone):
            return False
        raw = zone.get("ltf_respect", zone.get("respected_by_ltf", False))
        if not isinstance(raw, bool) or not raw:
            return False
        direction = zone.get("respect_direction")
        if direction is None:
            return True
        return str(getattr(direction, "value", direction)).upper() == side

    def _context_aligned(
        self,
        *,
        side: str,
        price: float,
        context_zones: Sequence[Mapping[str, object]],
    ) -> tuple[bool, bool]:
        wanted = "SUPPORT" if side == "LONG" else "RESISTANCE"
        for zone in context_zones:
            if not self._qof(zone) or self._role(zone) != wanted:
                continue
            if self._ltf_respects_htf(zone, side):
                return True, True
            lower = float(zone["lower"])
            upper = float(zone["upper"])
            width = max(upper - lower, 0.0)
            if lower <= price <= upper or abs(float(zone.get("center", (lower + upper) / 2.0)) - price) <= max(width, 1e-9):
                return True, False
        return False, False

    def plan(
        self,
        *,
        side: str,
        price: float,
        execution_zone: Mapping[str, object],
        execution_timeframe: str,
        context_zones_by_timeframe: Mapping[str, Sequence[Mapping[str, object]]] | None,
        atr: float,
        stop: float,
        target: float,
        minimum_rr: float,
    ) -> MultiTimeframePrecisionPlan:
        side = side.upper()
        context = context_zones_by_timeframe or {}
        context_timeframes = tuple(str(tf) for tf in context)
        base = self.entry_planner.plan(
            side=side,
            price=price,
            zone=execution_zone,
            atr=atr,
            stop=stop,
            target=target,
            minimum_rr=minimum_rr,
        )
        if not base.accepted:
            return MultiTimeframePrecisionPlan(False, str(execution_timeframe), context_timeframes, base.preferred_entry, base.precision_score, False, base.reason)

        context_zones = [z for zones in context.values() for z in zones]
        aligned, htf_respected = self._context_aligned(side=side, price=price, context_zones=context_zones)
        score = min(100.0, base.precision_score + (self.context_bonus if aligned else 0.0))
        if context_zones and not aligned:
            return MultiTimeframePrecisionPlan(False, str(execution_timeframe), context_timeframes, base.preferred_entry, score, False, "execution setup lacks aligned higher-timeframe QOF context")
        if htf_respected:
            return MultiTimeframePrecisionPlan(True, str(execution_timeframe), context_timeframes, base.preferred_entry, score, True, "LTF respect validated the higher-timeframe QOF zone", True)
        return MultiTimeframePrecisionPlan(True, str(execution_timeframe), context_timeframes, base.preferred_entry, score, aligned, "multi-timeframe QOF precision entry accepted")
