"""QOF market-map orchestration for Strategy 001."""
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
    max_active_structures: int = 12

class QOFStructureEngine:
    """Build QOF-implied structures; price structure is secondary evidence."""
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
            merged[hit] = ZoneCandidate(center, min(existing.lower, candidate.lower), max(existing.upper, candidate.upper),
                                        existing.role, max(existing.detected_at, candidate.detected_at), "COMPOSITE",
                                        total, max(existing.reaction_strength, candidate.reaction_strength),
                                        existing.evidence + candidate.evidence)
        return merged

    def build(self, bars: Sequence[PriceBar], atr: float, price: float | None = None,
              now_minutes_old: float = 0.0, options: Iterable[Mapping[str, object]] | None = None,
              evidence: ZoneEvidenceContext | None = None) -> list[Zone]:
        if not bars or atr <= 0:
            return []
        current_price = float(price if price is not None else bars[-1].close)
        candidates = self.detector.detect(bars, atr)
        if options is not None:
            candidates.extend(detect_qof_structure(options, current_price, atr, bars[-1].timestamp, self.config.options))
        if evidence is not None:
            candidates = [attach_market_evidence(c, current_price, atr, evidence) for c in candidates]
        candidates = self._merge_candidates(candidates, atr)
        structures: list[Zone] = []
        for candidate in candidates:
            strength = self.scorer.strength(candidate, now_minutes_old)
            zone_type = ZoneType.COMPOSITE if candidate.source == "COMPOSITE" else ZoneType.OPTIONS_DEALER if candidate.source == "QOF_IMPLIED" else ZoneType.STRUCTURAL
            structure = Zone(self._structure_id(candidate), candidate.center, candidate.lower, candidate.upper,
                             zone_type, candidate.role, ZoneState.ACTIVE, strength, strength,
                             candidate.detected_at, candidate.reaction_count, candidate.reaction_strength,
                             list(candidate.evidence))
            structure.metadata["relevance"] = self.scorer.relevance(structure, current_price, atr)
            structure.metadata["qof_primary"] = candidate.source == "QOF_IMPLIED" or candidate.source == "COMPOSITE"
            structure.metadata["source_count"] = len({e.source for e in candidate.evidence})
            structures.append(structure)
        structures.sort(key=lambda z: (float(z.metadata.get("relevance", 0.0)), z.strength), reverse=True)
        return structures[: self.config.max_active_structures]

    def update_states(self, structures: Sequence[Zone], price: float, atr: float, timestamp) -> list[Zone]:
        for structure in structures:
            self.state_machine.update(structure, price, atr, timestamp)
        return list(structures)

    @staticmethod
    def nearest(structures: Sequence[Zone], price: float, role: ZoneRole | None = None) -> Zone | None:
        candidates = [z for z in structures if role is None or z.role == role]
        return min(candidates, key=lambda z: z.distance(price)) if candidates else None


# Compatibility alias while internal imports are migrated to the QOF vocabulary.
ZoneEngine = QOFStructureEngine
ZoneEngineConfig = QOFStructureEngineConfig
