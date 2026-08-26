"""Market-map orchestration for Strategy 001 S/R zones."""
from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha1
from typing import Iterable, Sequence, Mapping

from strat.core.models import PriceBar
from .detector import StructuralZoneDetector, ZoneDetectionConfig
from .evidence import ZoneEvidenceContext, attach_market_evidence
from .models import Zone, ZoneCandidate, ZoneRole, ZoneState, ZoneType
from .options import OptionsZoneConfig, detect_options_zones
from .scorer import ZoneScorer, ZoneScoreConfig
from .state import ZoneStateMachine, ZoneStateConfig

@dataclass(frozen=True)
class ZoneEngineConfig:
    detection: ZoneDetectionConfig = ZoneDetectionConfig()
    scoring: ZoneScoreConfig = ZoneScoreConfig()
    state: ZoneStateConfig = ZoneStateConfig()
    options: OptionsZoneConfig = OptionsZoneConfig()
    merge_tolerance_atr: float = 0.35
    max_active_zones: int = 12

class ZoneEngine:
    """Build and maintain the canonical quantitative market map."""
    def __init__(self, config: ZoneEngineConfig | None = None) -> None:
        self.config = config or ZoneEngineConfig()
        self.detector = StructuralZoneDetector(self.config.detection)
        self.scorer = ZoneScorer(self.config.scoring)
        self.state_machine = ZoneStateMachine(self.config.state)

    @staticmethod
    def _zone_id(candidate: ZoneCandidate) -> str:
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
            merged[hit] = ZoneCandidate(
                center=center, lower=min(existing.lower, candidate.lower), upper=max(existing.upper, candidate.upper),
                role=existing.role, detected_at=max(existing.detected_at, candidate.detected_at), source="COMPOSITE",
                reaction_count=total, reaction_strength=max(existing.reaction_strength, candidate.reaction_strength),
                evidence=existing.evidence + candidate.evidence,
            )
        return merged

    def build(self, bars: Sequence[PriceBar], atr: float, price: float | None = None,
              now_minutes_old: float = 0.0, options: Iterable[Mapping[str, object]] | None = None,
              evidence: ZoneEvidenceContext | None = None) -> list[Zone]:
        if not bars or atr <= 0:
            return []
        current_price = float(price if price is not None else bars[-1].close)
        candidates = self.detector.detect(bars, atr)
        if options is not None:
            candidates.extend(detect_options_zones(options, current_price, atr, bars[-1].timestamp, self.config.options))
        if evidence is not None:
            candidates = [attach_market_evidence(c, current_price, atr, evidence) for c in candidates]
        candidates = self._merge_candidates(candidates, atr)
        zones: list[Zone] = []
        for candidate in candidates:
            strength = self.scorer.strength(candidate, now_minutes_old)
            zone_type = ZoneType.COMPOSITE if candidate.source == "COMPOSITE" else ZoneType.OPTIONS_DEALER if candidate.source == "OPTIONS_DEALER" else ZoneType.STRUCTURAL
            zone = Zone(id=self._zone_id(candidate), center=candidate.center, lower=candidate.lower, upper=candidate.upper,
                        type=zone_type, role=candidate.role, state=ZoneState.ACTIVE, strength=strength, confidence=strength,
                        detected_at=candidate.detected_at, reaction_count=candidate.reaction_count,
                        last_reaction_strength=candidate.reaction_strength, source_evidence=list(candidate.evidence))
            zone.metadata["relevance"] = self.scorer.relevance(zone, current_price, atr)
            zone.metadata["source_count"] = len({e.source for e in candidate.evidence})
            zones.append(zone)
        zones.sort(key=lambda z: (float(z.metadata.get("relevance", 0.0)), z.strength), reverse=True)
        return zones[: self.config.max_active_zones]

    def update_states(self, zones: Sequence[Zone], price: float, atr: float, timestamp) -> list[Zone]:
        for zone in zones:
            self.state_machine.update(zone, price, atr, timestamp)
        return list(zones)

    @staticmethod
    def nearest(zones: Sequence[Zone], price: float, role: ZoneRole | None = None) -> Zone | None:
        candidates = [z for z in zones if role is None or z.role == role]
        return min(candidates, key=lambda z: z.distance(price)) if candidates else None
