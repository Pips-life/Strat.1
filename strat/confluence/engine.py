"""Reusable quantitative confluence engine for Strategy 001 and future strategies.

The engine scores evidence; it never places orders. Missing evidence is neutral,
while explicit contradictions reduce the final score. Components are normalized
to 0..100 before weighted aggregation.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, Iterable, Mapping


@dataclass(frozen=True)
class ConfluenceConfig:
    weights: Dict[str, float] = field(default_factory=lambda: {
        "structure": 0.25,
        "options_flow": 0.20,
        "gamma": 0.15,
        "delta": 0.10,
        "iv": 0.10,
        "velocity": 0.10,
        "volume": 0.10,
    })
    minimum_score: float = 65.0
    strong_score: float = 75.0
    exceptional_score: float = 80.0
    minimum_directional_edge: float = 12.0
    contradiction_penalty: float = 6.0
    component_cap: float = 100.0


@dataclass
class ConfluenceResult:
    score: float
    grade: str
    tradable: bool
    direction: str
    long_score: float
    short_score: float
    directional_edge: float
    components: Dict[str, float]
    contradictions: list[str]
    missing: list[str]
    derived: Dict[str, float] = field(default_factory=dict)


class ConfluenceEngine:
    def __init__(self, config: ConfluenceConfig | None = None):
        self.config = config or ConfluenceConfig()

    @staticmethod
    def _normalize(value: object) -> float:
        try:
            return max(0.0, min(100.0, float(value)))
        except (TypeError, ValueError):
            return 0.0

    def _score_direction(self, components: Mapping[str, float | None], direction: str) -> tuple[float, list[str]]:
        weighted = 0.0
        total_weight = 0.0
        missing: list[str] = []
        for name, weight in self.config.weights.items():
            value = components.get(name)
            if value is None:
                missing.append(name)
                continue
            weighted += self._normalize(value) * weight
            total_weight += weight
        return ((weighted / total_weight) if total_weight else 0.0, missing)

    def evaluate_directional(
        self,
        long_components: Mapping[str, float | None],
        short_components: Mapping[str, float | None],
        contradictions: Iterable[str] = (),
        derived: Mapping[str, float] | None = None,
    ) -> ConfluenceResult:
        long_score, long_missing = self._score_direction(long_components, "LONG")
        short_score, short_missing = self._score_direction(short_components, "SHORT")
        contradiction_list = list(contradictions)
        penalty = self.config.contradiction_penalty * len(contradiction_list)
        long_score = max(0.0, min(100.0, long_score - penalty))
        short_score = max(0.0, min(100.0, short_score - penalty))
        edge = abs(long_score - short_score)
        direction = "LONG" if long_score > short_score else "SHORT" if short_score > long_score else "NONE"
        score = max(long_score, short_score)
        missing = sorted(set(long_missing + short_missing))
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
        tradable = direction != "NONE" and score >= self.config.minimum_score and edge >= self.config.minimum_directional_edge
        selected = long_components if direction == "LONG" else short_components
        return ConfluenceResult(
            score=round(score, 2),
            grade=grade,
            tradable=tradable,
            direction=direction,
            long_score=round(long_score, 2),
            short_score=round(short_score, 2),
            directional_edge=round(edge, 2),
            components={k: self._normalize(v) for k, v in selected.items() if v is not None},
            contradictions=contradiction_list,
            missing=missing,
            derived=dict(derived or {}),
        )

    def evaluate(self, components: Dict[str, float], contradictions: Iterable[str] = ()) -> ConfluenceResult:
        """Backward-compatible single-direction evaluation.

        New callers should use ``evaluate_directional``. This method interprets
        supplied components as bullish evidence and mirrors it for a neutral
        opposite side, preserving the one-engine contract without reviving the
        former four-component scoring model.
        """
        inverse = {name: 100.0 - self._normalize(value) for name, value in components.items()}
        return self.evaluate_directional(components, inverse, contradictions)
