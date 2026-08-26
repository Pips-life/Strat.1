from datetime import datetime, timezone

from strat.zones.evidence import ZoneEvidenceContext, attach_market_evidence
from strat.zones.models import ZoneCandidate, ZoneRole
from strat.zones.scorer import ZoneScorer


def candidate(center: float = 100.0) -> ZoneCandidate:
    return ZoneCandidate(
        center=center,
        lower=center - 0.5,
        upper=center + 0.5,
        role=ZoneRole.SUPPORT,
        detected_at=datetime.now(timezone.utc),
        source="STRUCTURAL",
        reaction_count=2,
        reaction_strength=70.0,
    )


def test_market_intelligence_is_proximity_weighted():
    near = attach_market_evidence(
        candidate(), 100.0, 2.0,
        ZoneEvidenceContext(options_flow=90, delta=80, gamma=70, iv=60, velocity=90, volume=80),
    )
    far = attach_market_evidence(
        candidate(110.0), 100.0, 2.0,
        ZoneEvidenceContext(options_flow=90, delta=80, gamma=70, iv=60, velocity=90, volume=80),
    )
    near_score = sum(e.score for e in near.evidence if e.source == "velocity")
    far_score = sum(e.score for e in far.evidence if e.source == "velocity")
    assert near_score > far_score


def test_zone_scorer_consumes_new_evidence_sources():
    enriched = attach_market_evidence(
        candidate(), 100.0, 2.0,
        ZoneEvidenceContext(options_flow=90, delta=80, gamma=70, iv=60, velocity=90, volume=80),
    )
    baseline = ZoneScorer().strength(candidate())
    enhanced = ZoneScorer().strength(enriched)
    assert enhanced > baseline
    assert enhanced <= 100
