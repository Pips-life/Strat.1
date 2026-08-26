"""Domain models for the canonical QOF Structure Engine."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Mapping, Optional


class ZoneType(str, Enum):
    """Structural output categories; QOF_IMPLIED is the primary QOF type."""
    STRUCTURAL = "STRUCTURAL"          # price-derived confirmation/context
    QOF_IMPLIED = "QOF_IMPLIED"        # predictive QOF-derived structure
    OPTIONS_DEALER = "QOF_IMPLIED"     # enum alias for old serialized values
    RECLAIM = "RECLAIM"
    STABILIZATION = "STABILIZATION"
    LIQUIDITY_SWEEP = "LIQUIDITY_SWEEP"
    COMPOSITE = "COMPOSITE"


class ZoneRole(str, Enum):
    SUPPORT = "SUPPORT"
    RESISTANCE = "RESISTANCE"
    NEUTRAL = "NEUTRAL"


class ZoneState(str, Enum):
    FORMING = "FORMING"
    ACTIVE = "ACTIVE"
    TESTED = "TESTED"
    RESPECTED = "RESPECTED"
    BROKEN = "BROKEN"
    RECLAIMED = "RECLAIMED"
    FLIPPED = "FLIPPED"
    EXHAUSTED = "EXHAUSTED"
    INVALIDATED = "INVALIDATED"


@dataclass(frozen=True)
class ZoneEvidence:
    source: str
    score: float
    reason: str = ""
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not 0.0 <= self.score <= 100.0:
            raise ValueError("evidence score must be between 0 and 100")


@dataclass(frozen=True)
class ZoneCandidate:
    center: float
    lower: float
    upper: float
    role: ZoneRole
    detected_at: datetime
    source: str = "STRUCTURAL"
    reaction_count: int = 0
    reaction_strength: float = 0.0
    evidence: tuple[ZoneEvidence, ...] = ()

    def __post_init__(self) -> None:
        if self.lower > self.center or self.center > self.upper:
            raise ValueError("zone boundaries must contain center")
        if self.center <= 0:
            raise ValueError("zone center must be positive")


@dataclass
class Zone:
    id: str
    center: float
    lower: float
    upper: float
    type: ZoneType
    role: ZoneRole
    state: ZoneState
    strength: float
    confidence: float
    detected_at: datetime
    last_tested: Optional[datetime] = None
    reaction_count: int = 0
    last_reaction_strength: float = 0.0
    source_evidence: list[ZoneEvidence] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    def contains(self, price: float) -> bool:
        return self.lower <= price <= self.upper

    def distance(self, price: float) -> float:
        if self.contains(price):
            return 0.0
        return min(abs(price - self.lower), abs(price - self.upper))

    def normalized_distance(self, price: float, atr: float) -> float:
        if atr <= 0:
            raise ValueError("atr must be positive")
        return self.distance(price) / atr
