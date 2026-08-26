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


def _signed_components(row: Mapping[str, object]) -> dict[str, float]:
    """Preserve signed inputs; Gamma is structural/regime evidence, not direction."""
    return {
        "gamma": _num(row.get("gamma", row.get("gex", 0))),
        "delta": _num(row.get("delta", 0)),
        "options_flow": _num(row.get("flow_score", row.get("flow", row.get("net_flow", 0)))),
    }


def _row_score(row: Mapping[str, object], cfg: OptionsStructureConfig) -> tuple[float, dict[str, float], float]:
    """Separate concentration from direction.

    OI/volume measure concentration. Gamma contributes magnitude/regime evidence.
    Signed Delta and signed options flow provide directional pressure. IV is
    volatility context and never determines bullish/bearish direction.
    """
    oi = max(0.0, _num(row.get("open_interest", row.get("oi", 0))))
    volume = max(0.0, _num(row.get("volume", row.get("contracts", 0))))
    signed = _signed_components(row)
    gamma, delta, flow = signed["gamma"], signed["delta"], signed["options_flow"]
    iv = max(0.0, _num(row.get("iv", row.get("implied_volatility", 0))))

    oi_n = min(100.0, oi / max(1.0, cfg.min_open_interest) * 20.0)
    volume_n = min(100.0, volume / max(1.0, cfg.min_volume) * 20.0)
    gamma_n = min(100.0, abs(gamma) * 100.0)
    delta_n = min(100.0, abs(delta) * 100.0)
    iv_n = min(100.0, iv if iv <= 100.0 else iv / 2.0)
    flow_n = min(100.0, abs(flow) if abs(flow) <= 100.0 else abs(flow) / 2.0)

    concentration_score = _bounded(
        oi_n * cfg.oi_weight + volume_n * cfg.volume_weight
        + gamma_n * cfg.gamma_weight + delta_n * cfg.delta_weight
        + iv_n * cfg.iv_weight + flow_n * cfg.flow_weight
    )
    # Deliberately exclude Gamma and IV from directional pressure.
    pressure = delta * cfg.delta_weight + flow * cfg.flow_weight
    components = {
        "open_interest": oi_n, "volume": volume_n, "gamma": gamma_n,
        "delta": delta_n, "iv": iv_n, "options_flow": flow_n,
    }
    return concentration_score, components, pressure


def _role(row: Mapping[str, object], strike: float, spot: float, pressure: float) -> ZoneRole | None:
    explicit = str(row.get("structure_role", row.get("role", ""))).upper()
    if explicit in {"SUPPORT", "DEALER_SUPPORT", "STABILISATION", "STABILIZATION"}:
        return ZoneRole.SUPPORT
    if explicit in {"RESISTANCE", "DEALER_RESISTANCE"}:
        return ZoneRole.RESISTANCE
    if pressure < 0:
        return ZoneRole.RESISTANCE
    if pressure > 0:
        return ZoneRole.SUPPORT
    # Neutral directional evidence must not be converted into a directional
    # QOF structure by option type or strike location.
    return None


def detect_qof_structure(options: Iterable[Mapping[str, object]], spot: float, atr: float, detected_at: datetime, config: OptionsStructureConfig | None = None) -> list[ZoneCandidate]:
    """Generate QOF-primary structures before conventional chart structure forms."""
    cfg = config or OptionsStructureConfig()
    if spot <= 0 or atr <= 0:
        return []
    rows: list[ZoneCandidate] = []
    max_distance = atr * max(0.0, cfg.max_distance_atr)
    for row in options:
        strike = _num(row.get("strike"))
        if strike <= 0 or abs(strike - spot) > max_distance:
            continue
        oi = max(0.0, _num(row.get("open_interest", row.get("oi", 0))))
        volume = max(0.0, _num(row.get("volume", row.get("contracts", 0))))
        if oi < cfg.min_open_interest and volume < cfg.min_volume:
            continue
        score, components, pressure = _row_score(row, cfg)
        role = _role(row, strike, spot, pressure)
        if role is None:
            continue
        proximity = exp(-abs(strike - spot) / atr)
        relevance = _bounded(score * proximity)
        width = max(atr * 0.05, atr * cfg.zone_width_atr)
        kind = "DEALER_RESISTANCE" if role == ZoneRole.RESISTANCE else "DEALER_SUPPORT"
        if str(row.get("structure_role", row.get("role", ""))).upper() in {"STABILISATION", "STABILIZATION"}:
            kind = "STABILISATION"
        evidence = [ZoneEvidence("qof_options", relevance, "Predictive QOF positioning concentration", {"strike": strike, "distance_atr": abs(strike - spot) / atr, "structure_kind": kind, "proximity_weight": proximity, "signed_pressure": pressure})]
        for source, component_score in components.items():
            if component_score > 0:
                evidence.append(ZoneEvidence(source, _bounded(component_score * proximity), f"QOF {source} contribution", {"strike": strike}))
        rows.append(ZoneCandidate(center=strike, lower=strike - width, upper=strike + width, role=role, detected_at=detected_at, source="QOF_IMPLIED", reaction_count=0, reaction_strength=relevance, evidence=tuple(evidence)))
    rows.sort(key=lambda c: c.reaction_strength, reverse=True)
    return rows[: cfg.max_candidates]


detect_options_zones = detect_qof_structure
OptionsZoneConfig = OptionsStructureConfig
