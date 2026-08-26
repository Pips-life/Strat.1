"""Canonical QOF Structure Engine package for Strategy 001."""

from .models import Zone, ZoneCandidate, ZoneEvidence, ZoneRole, ZoneState, ZoneType
from .engine import QOFStructureEngine, QOFStructureEngineConfig
from .detector import StructuralZoneDetector
from .scorer import ZoneScorer
from .state import ZoneStateMachine

# Legacy import names remain only as compatibility aliases during migration;
# there is one canonical engine implementation: QOFStructureEngine.
ZoneEngine = QOFStructureEngine

__all__ = [
    "Zone", "ZoneCandidate", "ZoneEvidence", "ZoneRole", "ZoneState", "ZoneType",
    "QOFStructureEngine", "QOFStructureEngineConfig",
    "ZoneEngine", "StructuralZoneDetector", "ZoneScorer", "ZoneStateMachine",
]
