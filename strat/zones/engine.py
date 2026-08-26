"""Canonical QOF Structure Engine for Strategy 001."""
from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha1
from typing import Iterable, Mapping, Sequence

from strat.core.models import PriceBar
from .detector import StructuralZoneDetector, ZoneDetectionConfig
from .evidence import ZoneEvidenceContext, attach_market_evidence
from .models import Zone, ZoneCandidate, ZoneRole, ZoneState, ZoneType
from .options import OptionsStructureConfig, detect_qof_structure
from .scorer import ZoneScorer, ZoneScoreConfig
from .state import ZoneStateMachine, ZoneStateConfig


@dataclass(frozen=True)
class QOFStructureEngineConfig:
    detection: ZoneDetectionConfig = ZoneDetectionConfig()
    scoring: ZoneScoreConfig = ZoneScoreConfig()
    state: ZoneStateConfig = ZoneStateConfig()
    options: OptionsStructureConfig = OptionsStructureConfig()
    merge_tolerance_atr: float = 0.35
    max_active_structures: int = 16
    predictive_state: ZoneState = ZoneState.FORMING


class QOFStructureEngine:
    """Derive, rank and maintain the QOF market map.

    QOF-implied structures are primary. Price-derived structure is retained as
    secondary confirmation/evidence and is never required before a QOF candidate
    can exist.
    """

    def __init__(self, config: QOFStructureEngineConfig | None = None) -> None:
        self.config = config or QOFStructureEngineConfig()
        self.detector = StructuralZoneDetector(self.config.detection)
        self.scorer = ZoneScorer(self.config.scoring)
        self.state_machine = ZoneStateMachine(self.config.state)

    @staticmethod
    def _structure_id(candidate: ZoneCandidate) -> str:
        raw = f"{candidate.role.value}:{candidate.center:.8f}:{candidate.lower:.8f}:{candidate.upper:.8f}"
        return sha1(raw.encode("utf-8")).hexdigest()[:12]

    def _merge_candidates(self, candidates: Iterable[ZoneCandidate], atr: float) -> list[ZoneCandidate]:
        ordered = sorted(candidates, key=lambda c: (c.role.value, c.center))
        merged: list[ZoneCandidate] = []
        tolerance = atr * self.config.merge_tolerance_atr
        for candidate in ordered:
            hit = next((i for i, existing in enumerate(merged)
                        if existing.role == candidate.role and abs(existing.center - candidate.center) <= tolerance), None)
            if hit is None:
                merged.append(candidate)
                continue
            existing = merged[hit]
            total = existing.reaction_count + candidate.reaction_count
            center = (existing.center * existing.reaction_count + candidate.center * candidate.reaction_count) / max(1, total)
            if existing.reaction_count == 0 and candidate.reaction_count == 0:
                center = (existing.center + candidate.center) / 2.0
            merged[hit] = ZoneCandidate(
                center=center,
                lower=min(existing.lower, candidate.lower),
                upper=max(existing.upper, candidate.upper),
                role=existing.role,
                detected_at=max(existing.detected_at, candidate.detected_at),
                source="COMPOSITE",
                reaction_count=total,
                reaction_strength=max(existing.reaction_strength, candidate.reaction_strength),
                evidence=existing.evidence + candidate.evidence,
            )
        return merged

    def build(
        self,
        bars: Sequence[PriceBar],
        atr: float,
        price: float | None = None,
        now_minutes_old: float = 0.0,
        options: Iterable[Mapping[str, object]] | None = None,
        evidence: ZoneEvidenceContext | None = None,
        as_of_index: int | None = None,
    ) -> list[Zone]:
        """Build a causal QOF market map as of the supplied observation.

        ``options`` may contain strikes ahead of current price, allowing the
        engine to identify potential structures before price creates visible
        swing structure. ``as_of_index`` is provided for replay causality.
        """
        if not bars or atr <= 0:
            return []
        boundary = len(bars) - 1 if as_of_index is None else as_of_index
        if boundary < 0 or boundary >= len(bars):
            raise IndexError("as_of_index outside bars")
        visible_bars = bars[: boundary + 1]
        current_price = float(price if price is not None else visible_bars[-1].close)
        candidates = self.detector.detect(visible_bars, atr, boundary)
        if options is not None:
            candidates.extend(
                detect_qof_structure(
                    options, current_price, atr, visible_bars[-1].timestamp, self.config.options
                )
            )
        if evidence is not None:
            candidates = [attach_market_evidence(c, current_price, atr, evidence) for c in candidates]
        candidates = self._merge_candidates(candidates, atr)

        structures: list[Zone] = []
        for candidate in candidates:
            strength = self.scorer.strength(candidate, now_minutes_old)
            qof_primary = candidate.source in {"QOF_IMPLIED", "COMPOSITE"}
            structure = Zone(
                self._structure_id(candidate),
                candidate.center,
                candidate.lower,
                candidate.upper,
                ZoneType.COMPOSITE if candidate.source == "COMPOSITE" else (
                    ZoneType.OPTIONS_DEALER if candidate.source == "QOF_IMPLIED" else ZoneType.STRUCTURAL
                ),
                candidate.role,
                self.config.predictive_state if qof_primary else ZoneState.ACTIVE,
                strength,
                strength,
                candidate.detected_at,
                candidate.reaction_count,
                candidate.reaction_strength,
                list(candidate.evidence),
            )
            structure.metadata.update({
                "qof_primary": qof_primary,
                "structure_kind": self._structure_kind(candidate),
                "predictive": qof_primary and candidate.reaction_count == 0,
                "relevance": self.scorer.relevance(structure, current_price, atr),
                "source_count": len({e.source for e in candidate.evidence}),
                "distance_atr": structure.normalized_distance(current_price, atr),
                "validated_by_price": candidate.reaction_count > 0,
            })
            structures.append(structure)

        structures.sort(
            key=lambda z: (
                bool(z.metadata.get("qof_primary")),
                float(z.metadata.get("relevance", 0.0)),
                z.strength,
            ),
            reverse=True,
        )
        return structures[: self.config.max_active_structures]

    @staticmethod
    def _structure_kind(candidate: ZoneCandidate) -> str:
        if candidate.source == "QOF_IMPLIED":
            kinds = [
                e.metadata.get("structure_kind")
                for e in candidate.evidence
                if e.source == "qof_options"
            ]
            return str(kinds[0]) if kinds and kinds[0] else (
                "DEALER_RESISTANCE" if candidate.role == ZoneRole.RESISTANCE else "DEALER_SUPPORT"
            )
        if candidate.source == "COMPOSITE":
            kinds = [e.metadata.get("structure_kind") for e in candidate.evidence if e.metadata.get("structure_kind")]
            return str(kinds[0]) if kinds else "COMPOSITE"
        return "PRICE_CONFIRMATION"

    def update_states(self, structures: Sequence[Zone], price: float, atr: float, timestamp) -> list[Zone]:
        """Apply causal price interaction to the current QOF market map."""
        for structure in structures:
            before = structure.state
            self.state_machine.update(structure, price, atr, timestamp)
            if before != structure.state:
                structure.metadata["last_transition"] = f"{before.value}->{structure.state.value}"
            if structure.state in {ZoneState.TESTED, ZoneState.RESPECTED}:
                structure.metadata["validated_by_price"] = True
        return list(structures)

    @staticmethod
    def nearest(
        structures: Sequence[Zone],
        price: float,
        role: ZoneRole | None = None,
        primary_only: bool = False,
    ) -> Zone | None:
        candidates = [
            z for z in structures
            if (role is None or z.role == role)
            and (not primary_only or bool(z.metadata.get("qof_primary")))
            and z.state != ZoneState.INVALIDATED
        ]
        return min(candidates, key=lambda z: z.distance(price)) if candidates else None

    @staticmethod
    def target_candidates(structures: Sequence[Zone], price: float, direction: str) -> list[Zone]:
        """Return QOF structures that can serve as structure-to-structure targets."""
        direction = direction.upper()
        if direction == "SHORT":
            candidates = [z for z in structures if z.center < price and z.role == ZoneRole.SUPPORT]
        elif direction == "LONG":
            candidates = [z for z in structures if z.center > price and z.role == ZoneRole.RESISTANCE]
        else:
            return []
        return sorted(
            [z for z in candidates if z.state != ZoneState.INVALIDATED],
            key=lambda z: (z.distance(price), -float(z.metadata.get("relevance", 0.0))),
        )


# Compatibility aliases while remaining internal callers migrate.
ZoneEngine = QOFStructureEngine
ZoneEngineConfig = QOFStructureEngineConfig
