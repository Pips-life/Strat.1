"""Options-derived zone candidates for the canonical S/R Zone Engine."""
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
class OptionsZoneConfig:
    min_open_interest: float = 1.0
    min_volume: float = 1.0
    strike_tolerance_atr: float = 0.35
    max_candidates: int = 12


def detect_options_zones(
    options: Iterable[Mapping[str, object]],
    spot: float,
    atr: float,
    detected_at: datetime,
    config: OptionsZoneConfig | None = None,
) -> list[ZoneCandidate]:
    """Create candidate S/R zones from meaningful option strikes.

    Options data is evidence, not automatic support/resistance. Call strikes
    above spot are resistance candidates; put strikes below spot are support
    candidates. Existing price/zone evidence must validate the role later.
    """
    cfg = config or OptionsZoneConfig()
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
        role = ZoneRole.RESISTANCE if kind in {"C", "CALL"} and strike >= spot else ZoneRole.SUPPORT
        if kind in {"P", "PUT"} and strike <= spot:
            role = ZoneRole.SUPPORT
        elif kind not in {"C", "CALL", "P", "PUT"}:
            continue
        if kind in {"P", "PUT"} and strike > spot:
            role = ZoneRole.RESISTANCE

        oi_score = min(100.0, oi / max(1.0, cfg.min_open_interest) * 20.0)
        vol_score = min(100.0, volume / max(1.0, cfg.min_volume) * 20.0)
        score = max(oi_score, vol_score)
        width = max(atr * 0.05, atr * 0.10)
        evidence = (
            ZoneEvidence("options", score, "meaningful option strike", {"strike": strike, "open_interest": oi, "volume": volume}),
        )
        rows.append(ZoneCandidate(strike, strike - width, strike + width, role, detected_at, "OPTIONS_DEALER", 1, score, evidence))

    rows.sort(key=lambda c: c.reaction_strength, reverse=True)
    return rows[: cfg.max_candidates]
