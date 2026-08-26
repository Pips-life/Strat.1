"""Reusable quantitative confluence engine for QOF and future strategies.

The engine scores normalized evidence and its cross-factor interactions. It never
places orders. Missing evidence is neutral, while explicit or inferred conflicts
reduce directional edge. QOF-implied structure is expected to be the primary
structural component supplied by the strategy.
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
    interaction_bonus_cap: float = 8.0
    inferred_conflict_penalty: float = 4.0


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

    def _score_direction(self, components: Mapping[str, float | None]) -> tuple[float, list[str]]:
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

    def _interactions(self, components: Mapping[str, float | None]) -> tuple[float, list[str], Dict[str, float]]:
        """Evaluate cross-factor agreement instead of treating every factor independently."""
        n = {k: self._normalize(v) for k, v in components.items() if v is not None}
        bonuses: list[float] = []
        conflicts: list[str] = []

        def pair(a: str, b: str, label: str, threshold: float = 70.0) -> None:
            if a not in n or b not in n:
                return
            if n[a] >= threshold and n[b] >= threshold:
                bonuses.append(min(n[a], n[b]) / 100.0 * 3.0)
            elif n[a] >= 75.0 and n[b] <= 30.0:
                conflicts.append(label)

        pair("structure", "options_flow", "structure_flow_conflict")
        pair("structure", "delta", "structure_delta_conflict")
        pair("options_flow", "delta", "flow_delta_conflict")
        pair("gamma", "velocity", "gamma_velocity_conflict")
        pair("iv", "velocity", "iv_velocity_conflict")
        pair("volume", "velocity", "volume_velocity_conflict")

        raw_bonus = sum(bonuses)
        bonus = min(self.config.interaction_bonus_cap, raw_bonus)
        return bonus, conflicts, {
            "interaction_bonus": round(bonus, 3),
            "interaction_pairs": float(len(bonuses)),
        }

    def evaluate_directional(
        self,
        long_components: Mapping[str, float | None],
        short_components: Mapping[str, float | None],
        contradictions: Iterable[str] = (),
        derived: Mapping[str, float] | None = None,
    ) -> ConfluenceResult:
        long_score, long_missing = self._score_direction(long_components)
        short_score, short_missing = self._score_direction(short_components)

        long_bonus, long_conflicts, long_interactions = self._interactions(long_components)
        short_bonus, short_conflicts, short_interactions = self._interactions(short_components)
        long_score = min(100.0, long_score + long_bonus)
        short_score = min(100.0, short_score + short_bonus)

        contradiction_list = list(contradictions)
        inferred = sorted(set(long_conflicts + short_conflicts))
        all_conflicts = contradiction_list + inferred
        explicit_penalty = self.config.contradiction_penalty * len(contradiction_list)
        inferred_penalty = self.config.inferred_conflict_penalty * len(inferred)
        total_penalty = explicit_penalty + inferred_penalty
        long_score = max(0.0, long_score - total_penalty)
        short_score = max(0.0, short_score - total_penalty)

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
        combined_derived = dict(derived or {})
        combined_derived.update({
            "long_interaction_bonus": long_interactions["interaction_bonus"],
            "short_interaction_bonus": short_interactions["interaction_bonus"],
            "inferred_conflicts": float(len(inferred)),
            "total_contradiction_penalty": total_penalty,
        })
        return ConfluenceResult(
            score=round(score, 2),
            grade=grade,
            tradable=tradable,
            direction=direction,
            long_score=round(long_score, 2),
            short_score=round(short_score, 2),
            directional_edge=round(edge, 2),
            components={k: self._normalize(v) for k, v in selected.items() if v is not None},
            contradictions=sorted(set(all_conflicts)),
            missing=missing,
            derived=combined_derived,
        )

    def evaluate(self, components: Dict[str, float], contradictions: Iterable[str] = ()) -> ConfluenceResult:
        """Backward-compatible single-direction evaluation using the canonical seven factors."""
        inverse = {name: 100.0 - self._normalize(value) for name, value in components.items()}
        return self.evaluate_directional(components, inverse, contradictions)
