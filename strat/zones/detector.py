"""Causal structural S/R detection.

A pivot is emitted only after the configured right-hand confirmation bars exist.
"""
from __future__ import annotations

from dataclasses import dataclass
from statistics import median
from typing import Sequence

from strat.core.models import PriceBar
from .models import ZoneCandidate, ZoneEvidence, ZoneRole


@dataclass(frozen=True)
class ZoneDetectionConfig:
    left_bars: int = 3
    right_bars: int = 3
    cluster_tolerance_atr: float = 0.35
    zone_width_atr: float = 0.12
    min_reactions: int = 1
    min_reaction_atr: float = 0.35


@dataclass(frozen=True)
class _Pivot:
    index: int
    price: float
    role: ZoneRole
    reaction_strength: float
    confirmed_index: int


class StructuralZoneDetector:
    def __init__(self, config: ZoneDetectionConfig | None = None) -> None:
        self.config = config or ZoneDetectionConfig()

    def _pivots(self, bars: Sequence[PriceBar], atr: float) -> list[_Pivot]:
        c = self.config
        if atr <= 0:
            raise ValueError("atr must be positive")
        if len(bars) < c.left_bars + c.right_bars + 1:
            return []

        pivots: list[_Pivot] = []
        for i in range(c.left_bars, len(bars) - c.right_bars):
            bar = bars[i]
            left = bars[i - c.left_bars:i]
            right = bars[i + 1:i + 1 + c.right_bars]
            highs = [b.high for b in (*left, *right)]
            lows = [b.low for b in (*left, *right)]

            if bar.high >= max(highs) and bar.high > max(b.high for b in left):
                reaction = max(0.0, (bar.high - max(b.close for b in right)) / atr)
                if reaction >= c.min_reaction_atr:
                    pivots.append(_Pivot(i, bar.high, ZoneRole.RESISTANCE,
                                         min(100.0, reaction * 50.0),
                                         i + c.right_bars))

            if bar.low <= min(lows) and bar.low < min(b.low for b in left):
                reaction = max(0.0, (min(b.close for b in right) - bar.low) / atr)
                if reaction >= c.min_reaction_atr:
                    pivots.append(_Pivot(i, bar.low, ZoneRole.SUPPORT,
                                         min(100.0, reaction * 50.0),
                                         i + c.right_bars))
        return pivots

    def detect(self, bars: Sequence[PriceBar], atr: float,
               as_of_index: int | None = None) -> list[ZoneCandidate]:
        """Return confirmed structural zones visible at ``as_of_index``."""
        if not bars:
            return []
        boundary = len(bars) - 1 if as_of_index is None else as_of_index
        if boundary < 0 or boundary >= len(bars):
            raise IndexError("as_of_index outside bars")

        pivots = [p for p in self._pivots(bars[:boundary + 1], atr)
                  if p.confirmed_index <= boundary]
        zones: list[ZoneCandidate] = []
        tolerance = atr * self.config.cluster_tolerance_atr
        width = atr * self.config.zone_width_atr

        for role in (ZoneRole.SUPPORT, ZoneRole.RESISTANCE):
            group = sorted((p for p in pivots if p.role == role), key=lambda p: p.price)
            clusters: list[list[_Pivot]] = []
            for pivot in group:
                if not clusters or abs(pivot.price - median(p.price for p in clusters[-1])) > tolerance:
                    clusters.append([pivot])
                else:
                    clusters[-1].append(pivot)

            for cluster in clusters:
                if len(cluster) < self.config.min_reactions:
                    continue
                center = float(median(p.price for p in cluster))
                strength = min(
                    100.0,
                    45.0 * min(1.0, len(cluster) / 4.0)
                    + 55.0 * min(1.0, median(p.reaction_strength for p in cluster) / 100.0),
                )
                detected_index = max(p.confirmed_index for p in cluster)
                zones.append(ZoneCandidate(
                    center=center,
                    lower=center - width,
                    upper=center + width,
                    role=role,
                    detected_at=bars[detected_index].timestamp,
                    source="STRUCTURAL",
                    reaction_count=len(cluster),
                    reaction_strength=round(median(p.reaction_strength for p in cluster), 2),
                    evidence=(ZoneEvidence(
                        source="swing_reaction",
                        score=round(strength, 2),
                        reason=f"{len(cluster)} confirmed reaction pivot(s)",
                    ),),
                ))
        return zones
