"""Predictive QOF market structures derived from live options positioning."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from math import exp
from typing import Iterable, Mapping

from .models import ZoneCandidate, ZoneEvidence, ZoneRole


def _num(value: object, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _bounded(value: float) -> float:
    return max(0.0, min(100.0, value))


@dataclass(frozen=True)
class OptionsStructureConfig:
    """Calibration controls for QOF-implied structure generation.

    Thresholds are intentionally configurable. Replay calibration must determine
    production values rather than assuming the initial defaults are optimal.
    """
    min_open_interest: float = 1.0
    min_volume: float = 1.0
    max_distance_atr: float = 2.50
    zone_width_atr: float = 0.10
    max_candidates: int = 16
    oi_weight: float = 0.30
    volume_weight: float = 0.20
    gamma_weight: float = 0.20
    delta_weight: float = 0.10
    iv_weight: float = 0.10
    flow_weight: float = 0.10


def _row_score(row: Mapping[str, object], cfg: OptionsStructureConfig) -> tuple[float, dict[str, float]]:
    oi = abs(_num(row.get("open_interest", row.get("oi", 0))))
    volume = abs(_num(row.get("volume", row.get("contracts", 0))))
    gamma = abs(_num(row.get("gamma", row.get("gex", 0))))
    delta = abs(_num(row.get("delta", 0)))
    iv = abs(_num(row.get("iv", row.get("implied_volatility", 0))))
    flow = abs(_num(row.get("flow_score", row.get("flow", 0))))

    # Each feature is normalized against its configured minimum or the row's
    # natural bounded scale. This creates a comparable evidence score without
    # pretending that raw provider units are interchangeable.
    oi_n = min(100.0, oi / max(1.0, cfg.min_open_interest) * 20.0)
    volume_n = min(100.0, volume / max(1.0, cfg.min_volume) * 20.0)
    gamma_n = min(100.0, gamma * 100.0)
    delta_n = min(100.0, delta * 100.0)
    iv_n = min(100.0, iv if iv <= 100.0 else iv / 2.0)
    flow_n = min(100.0, flow if flow <= 100.0 else flow / 2.0)

    score = (
        oi_n * cfg.oi_weight
        + volume_n * cfg.volume_weight
        + gamma_n * cfg.gamma_weight
        + delta_n * cfg.delta_weight
        + iv_n * cfg.iv_weight
        + flow_n * cfg.flow_weight
    )
    return _bounded(score), {
        "open_interest": oi_n,
        "volume": volume_n,
        "gamma": gamma_n,
        "delta": delta_n,
        "iv": iv_n,
        "options_flow": flow_n,
    }


def _role(row: Mapping[str, object], strike: float, spot: float) -> ZoneRole | None:
    explicit = str(row.get("structure_role", row.get("role", ""))).upper()
    if explicit in {"SUPPORT", "DEALER_SUPPORT", "STABILISATION", "STABILIZATION"}:
        return ZoneRole.SUPPORT
    if explicit in {"RESISTANCE", "DEALER_RESISTANCE"}:
        return ZoneRole.RESISTANCE

    kind = str(row.get("option_type", row.get("type", ""))).upper()
    if kind in {"C", "CALL"}:
        return ZoneRole.RESISTANCE if strike >= spot else ZoneRole.SUPPORT
    if kind in {"P", "PUT"}:
        return ZoneRole.SUPPORT if strike <= spot else ZoneRole.RESISTANCE
    return None


def detect_qof_structure(
    options: Iterable[Mapping[str, object]],
    spot: float,
    atr: float,
    detected_at: datetime,
    config: OptionsStructureConfig | None = None,
) -> list[ZoneCandidate]:
    """Generate predictive QOF-implied structures before chart structure forms.

    The engine deliberately scans a forward price landscape rather than only
    strikes already touching spot. A candidate is probabilistic evidence; live
    price/flow interaction later validates, rejects, strengthens, weakens or
    flips it.
    """
    cfg = config or OptionsStructureConfig()
    if spot <= 0 or atr <= 0:
        return []

    rows: list[ZoneCandidate] = []
    max_distance = atr * max(0.0, cfg.max_distance_atr)
    for row in options:
        strike = _num(row.get("strike"))
        if strike <= 0 or abs(strike - spot) > max_distance:
            continue
        oi = abs(_num(row.get("open_interest", row.get("oi", 0))))
        volume = abs(_num(row.get("volume", row.get("contracts", 0))))
        if oi < cfg.min_open_interest and volume < cfg.min_volume:
            continue

        role = _role(row, strike, spot)
        if role is None:
            continue

        score, components = _row_score(row, cfg)
        proximity = exp(-abs(strike - spot) / atr)
        relevance = _bounded(score * proximity)
        width = max(atr * 0.05, atr * cfg.zone_width_atr)
        kind = "DEALER_RESISTANCE" if role == ZoneRole.RESISTANCE else "DEALER_SUPPORT"
        if str(row.get("structure_role", row.get("role", ""))).upper() in {"STABILISATION", "STABILIZATION"}:
            kind = "STABILISATION"

        evidence = [ZoneEvidence(
            "qof_options", relevance,
            "Predictive QOF positioning concentration",
            {
                "strike": strike,
                "distance_atr": abs(strike - spot) / atr,
                "structure_kind": kind,
                "proximity_weight": proximity,
            },
        )]
        for source, component_score in components.items():
            if component_score > 0:
                evidence.append(ZoneEvidence(
                    source, _bounded(component_score * proximity),
                    f"QOF {source} contribution",
                    {"strike": strike},
                ))

        rows.append(ZoneCandidate(
            center=strike,
            lower=strike - width,
            upper=strike + width,
            role=role,
            detected_at=detected_at,
            source="QOF_IMPLIED",
            reaction_count=0,
            reaction_strength=relevance,
            evidence=tuple(evidence),
        ))

    rows.sort(key=lambda c: c.reaction_strength, reverse=True)
    return rows[: cfg.max_candidates]


# Compatibility aliases while callers migrate to QOF terminology.
detect_options_zones = detect_qof_structure
OptionsZoneConfig = OptionsStructureConfig
