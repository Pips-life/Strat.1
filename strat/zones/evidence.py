"""Market-intelligence evidence adapter for the canonical S/R Zone Engine."""
from __future__ import annotations

from dataclasses import dataclass
from math import exp
from typing import Mapping

from .models import ZoneCandidate, ZoneEvidence


@dataclass(frozen=True)
class ZoneEvidenceContext:
    """Normalized market-intelligence inputs, each scored 0..100."""
    options_flow: float = 0.0
    delta: float = 0.0
    gamma: float = 0.0
    iv: float = 0.0
    velocity: float = 0.0
    volume: float = 0.0
    proximity_decay_atr: float = 0.75


def _clamp(value: object) -> float:
    try:
        return max(0.0, min(100.0, float(value)))
    except (TypeError, ValueError):
        return 0.0


def attach_market_evidence(candidate: ZoneCandidate, price: float, atr: float,
                            context: ZoneEvidenceContext) -> ZoneCandidate:
    """Attach market-wide intelligence to a zone with volatility-normalized proximity.

    Market-wide evidence is intentionally attenuated with distance. This avoids
    turning every distant zone into a high-confidence zone merely because the
    overall market has strong flow, velocity, or IV.
    """
    if atr <= 0:
        return candidate
    distance = 0.0 if candidate.lower <= price <= candidate.upper else min(abs(price - candidate.lower), abs(price - candidate.upper))
    factor = exp(-distance / max(atr * context.proximity_decay_atr, 1e-12))

    additions: list[ZoneEvidence] = []
    for source, raw in (
        ("options_flow", context.options_flow),
        ("delta", context.delta),
        ("gamma", context.gamma),
        ("iv", context.iv),
        ("velocity", context.velocity),
        ("volume", context.volume),
    ):
        score = _clamp(raw) * factor
        if score > 0:
            additions.append(ZoneEvidence(source, score, "market intelligence near zone", {"proximity_factor": factor}))

    return ZoneCandidate(
        center=candidate.center,
        lower=candidate.lower,
        upper=candidate.upper,
        role=candidate.role,
        detected_at=candidate.detected_at,
        source=candidate.source,
        reaction_count=candidate.reaction_count,
        reaction_strength=candidate.reaction_strength,
        evidence=candidate.evidence + tuple(additions),
    )


def context_from_signals(signals: Mapping[str, float | None]) -> ZoneEvidenceContext:
    """Build a context from normalized intelligence scores."""
    return ZoneEvidenceContext(
        options_flow=_clamp(signals.get("options_flow")),
        delta=_clamp(signals.get("delta")),
        gamma=_clamp(signals.get("gamma")),
        iv=_clamp(signals.get("iv")),
        velocity=_clamp(signals.get("velocity")),
        volume=_clamp(signals.get("volume")),
    )
