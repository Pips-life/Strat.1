"""Reusable confluence scoring engine.

Designed to avoid the common failure mode of requiring every confirmation to
be present. A primary setup can remain tradable when one secondary signal is
missing, while contradictory evidence is penalized.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, Iterable


@dataclass(frozen=True)
class ConfluenceConfig:
    # Primary evidence carries the most weight.
    weights: Dict[str, float] = field(default_factory=lambda: {
        "options": 0.35,
        "structure": 0.30,
        "liquidity": 0.20,
        "volatility": 0.15,
    })

    # Do not demand 100% confirmation. This is deliberately permissive.
    minimum_score: float = 62.0
    strong_score: float = 78.0
    exceptional_score: float = 90.0

    # A missing secondary confluence is neutral, not a failure.
    missing_penalty: float = 0.0

    # Contradiction matters more than absence.
    contradiction_penalty: float = 12.0

    # Prevent a single extreme component from dominating everything.
    component_cap: float = 100.0


@dataclass
class ConfluenceResult:
    score: float
    grade: str
    tradable: bool
    components: Dict[str, float]
    contradictions: list[str]
    missing: list[str]


class ConfluenceEngine:
    def __init__(self, config: ConfluenceConfig | None = None):
        self.config = config or ConfluenceConfig()

    def evaluate(
        self,
        components: Dict[str, float],
        contradictions: Iterable[str] = (),
    ) -> ConfluenceResult:
        weights = self.config.weights
        total_weight = 0.0
        weighted = 0.0
        missing: list[str] = []

        normalized: Dict[str, float] = {}
        for name, weight in weights.items():
            value = components.get(name)
            if value is None:
                missing.append(name)
                continue

            value = max(0.0, min(self.config.component_cap, float(value)))
            normalized[name] = value
            weighted += value * weight
            total_weight += weight

        # Renormalize available evidence. Missing data does not automatically
        # turn a good setup into a no-trade setup.
        score = (weighted / total_weight) if total_weight else 0.0
        contradiction_list = list(contradictions)
        score -= self.config.contradiction_penalty * len(contradiction_list)
        score = max(0.0, min(100.0, score))

        if score >= self.config.exceptional_score:
            grade = "EXCEPTIONAL"
        elif score >= self.config.strong_score:
            grade = "STRONG"
        elif score >= self.config.minimum_score:
            grade = "VALID"
        elif score >= 50.0:
            grade = "WATCH"
        else:
            grade = "WEAK"

        return ConfluenceResult(
            score=round(score, 2),
            grade=grade,
            tradable=score >= self.config.minimum_score,
            components=normalized,
            contradictions=contradiction_list,
            missing=missing,
        )
