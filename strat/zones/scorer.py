"""Zone strength and relevance scoring."""
from __future__ import annotations

from dataclasses import dataclass
from math import exp
from typing import Iterable

from .models import Zone, ZoneCandidate, ZoneEvidence


@dataclass(frozen=True)
class ZoneScoreConfig:
    structural_weight: float = 0.15
    reaction_weight: float = 0.10
    options_weight: float = 0.10
    gamma_weight: float = 0.10
    volume_weight: float = 0.08
    liquidity_weight: float = 0.07
    recency_weight: float = 0.05
    options_flow_weight: float = 0.10
    delta_weight: float = 0.05
    iv_weight: float = 0.05
    velocity_weight: float = 0.10
    multi_source_bonus: float = 0.05
    recency_half_life_minutes: float = 240.0


class ZoneScorer:
    """Score zone evidence without assigning a trade direction.

    Price structure remains primary. Options flow, Delta, Gamma/GEX, IV,
    velocity and volume are confirming evidence and are proximity-weighted by
    the evidence adapter before reaching this scorer.
    """

    def __init__(self, config: ZoneScoreConfig | None = None) -> None:
        self.config = config or ZoneScoreConfig()

    @staticmethod
    def _source_score(evidence: Iterable[ZoneEvidence], source: str) -> float:
        values = [e.score for e in evidence if e.source == source]
        return max(values) if values else 0.0

    def strength(self, candidate: ZoneCandidate, now_minutes_old: float = 0.0) -> float:
        c = self.config
        evidence = candidate.evidence
        structural = self._source_score(evidence, "structure") or candidate.reaction_strength
        reaction = candidate.reaction_strength
        options = self._source_score(evidence, "options")
        gamma = self._source_score(evidence, "gamma")
        volume = self._source_score(evidence, "volume")
        liquidity = self._source_score(evidence, "liquidity")
        flow = self._source_score(evidence, "options_flow")
        delta = self._source_score(evidence, "delta")
        iv = self._source_score(evidence, "iv")
        velocity = self._source_score(evidence, "velocity")
        recency = 100.0 * exp(-max(0.0, now_minutes_old) * 0.69314718056 / c.recency_half_life_minutes)
        sources = {e.source for e in evidence if e.score > 0}
        bonus = 100.0 if len(sources) >= 3 else 0.0

        score = (
            structural * c.structural_weight
            + reaction * c.reaction_weight
            + options * c.options_weight
            + gamma * c.gamma_weight
            + volume * c.volume_weight
            + liquidity * c.liquidity_weight
            + recency * c.recency_weight
            + flow * c.options_flow_weight
            + delta * c.delta_weight
            + iv * c.iv_weight
            + velocity * c.velocity_weight
            + bonus * c.multi_source_bonus
        )
        return round(max(0.0, min(100.0, score)), 2)

    def relevance(self, zone: Zone, price: float, atr: float) -> float:
        distance_atr = zone.normalized_distance(price, atr)
        return round(zone.strength * exp(-distance_atr), 2)
