"""QOF-implied market-structure candidates derived from options positioning."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Iterable, Mapping

from .models import ZoneCandidate, ZoneEvidence, ZoneRole


def _num(value: object, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class OptionsStructureConfig:
    min_open_interest: float = 1.0
    min_volume: float = 1.0
    strike_tolerance_atr: float = 0.35
    max_candidates: int = 12


def detect_qof_structure(
    options: Iterable[Mapping[str, object]],
    spot: float,
    atr: float,
    detected_at: datetime,
    config: OptionsStructureConfig | None = None,
) -> list[ZoneCandidate]:
    """Infer early QOF market-structure candidates from option positioning.

    These are probabilistic QOF-implied structures, not confirmed chart S/R.
    Price action later validates, rejects, or flips the inferred structure.
    """
    cfg = config or OptionsStructureConfig()
    if spot <= 0 or atr <= 0:
        return []
    rows: list[ZoneCandidate] = []
    for row in options:
        strike = _num(row.get("strike"))
        if strike <= 0 or abs(strike - spot) > atr * max(0.0, cfg.strike_tolerance_atr):
            continue
        oi = abs(_num(row.get("open_interest", row.get("oi", 0))))
        volume = abs(_num(row.get("volume", row.get("contracts", 0))))
        if oi < cfg.min_open_interest and volume < cfg.min_volume:
            continue
        kind = str(row.get("option_type", row.get("type", ""))).upper()
        if kind in {"C", "CALL"}:
            role = ZoneRole.RESISTANCE if strike >= spot else ZoneRole.SUPPORT
        elif kind in {"P", "PUT"}:
            role = ZoneRole.SUPPORT if strike <= spot else ZoneRole.RESISTANCE
        else:
            continue
        oi_score = min(100.0, oi / max(1.0, cfg.min_open_interest) * 20.0)
        vol_score = min(100.0, volume / max(1.0, cfg.min_volume) * 20.0)
        score = max(oi_score, vol_score)
        width = max(atr * 0.05, atr * 0.10)
        evidence = (ZoneEvidence(
            "qof_options", score, "QOF-implied option positioning",
            {"strike": strike, "open_interest": oi, "volume": volume, "option_type": kind},
        ),)
        rows.append(ZoneCandidate(
            strike, strike - width, strike + width, role, detected_at,
            "QOF_IMPLIED", 1, score, evidence,
        ))
    rows.sort(key=lambda c: c.reaction_strength, reverse=True)
    return rows[: cfg.max_candidates]


# Compatibility aliases for in-place callers while the canonical terminology migrates.
detect_options_zones = detect_qof_structure
OptionsZoneConfig = OptionsStructureConfig
