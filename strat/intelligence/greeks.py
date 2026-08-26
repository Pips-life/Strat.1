"""Reusable options Greek and volatility calculations for Strategy 001."""
from __future__ import annotations

from dataclasses import dataclass
from math import isfinite
from typing import Iterable, Mapping


@dataclass(frozen=True)
class GreekSignal:
    delta_pressure: float = 0.0
    gamma_exposure: float = 0.0
    gamma_regime: str = "UNKNOWN"
    iv_change: float = 0.0
    iv_regime: str = "UNKNOWN"
    confidence: float = 0.0


def _num(value: object, default: float = 0.0) -> float:
    try:
        value = float(value)
    except (TypeError, ValueError):
        return default
    return value if isfinite(value) else default


def delta_pressure(options: Iterable[Mapping[str, object]], underlying_price: float) -> float:
    """Return normalized directional delta pressure in [-100, 100]."""
    bullish = bearish = 0.0
    for row in options:
        contracts = abs(_num(row.get("contracts", row.get("volume", 0))))
        multiplier = abs(_num(row.get("multiplier", 1), 1))
        delta = abs(_num(row.get("delta", 0)))
        exposure = contracts * multiplier * delta * max(underlying_price, 0.0)
        kind = str(row.get("option_type", row.get("type", ""))).upper()
        if kind in {"C", "CALL", "BUY_CALL"}:
            bullish += exposure
        elif kind in {"P", "PUT", "BUY_PUT"}:
            bearish += exposure
    gross = bullish + bearish
    if gross <= 0:
        return 0.0
    return max(-100.0, min(100.0, 100.0 * (bullish - bearish) / gross))


def _gex_sign(row: Mapping[str, object]) -> float | None:
    """Resolve dealer-GEX sign only from explicit dealer-side information.

    ``gex_sign`` is a normalized convention: +1 means long gamma and -1 means
    short gamma for that contract. ``dealer_position`` may alternatively be LONG
    or SHORT. Option type and open interest alone are never used to infer dealer
    positioning because OI does not reveal which side is the dealer.
    """
    if "gex_sign" in row and row.get("gex_sign") not in (None, ""):
        raw = _num(row.get("gex_sign"), float("nan"))
        if isfinite(raw) and raw != 0:
            return 1.0 if raw > 0 else -1.0
    position = str(row.get("dealer_position", "")).upper().strip()
    if position in {"LONG", "BUY"}:
        return 1.0
    if position in {"SHORT", "SELL"}:
        return -1.0
    return None


def gamma_exposure(options: Iterable[Mapping[str, object]], underlying_price: float) -> float:
    """Return signed dealer GEX from explicitly signed dealer positioning only.

    Rows without ``gex_sign`` or ``dealer_position`` are excluded. This prevents
    an OI-only assumption from being mislabeled as observed dealer exposure.
    """
    total = 0.0
    price_sq = max(underlying_price, 0.0) ** 2
    for row in options:
        sign = _gex_sign(row)
        if sign is None:
            continue
        gamma = abs(_num(row.get("gamma")))
        oi = abs(_num(row.get("open_interest", row.get("oi", 0))))
        multiplier = abs(_num(row.get("multiplier", 1), 1))
        total += sign * gamma * oi * multiplier * price_sq
    return total


def gamma_regime(gex: float, reference: float = 0.0) -> str:
    if not isfinite(gex):
        return "UNKNOWN"
    if gex > reference:
        return "POSITIVE_GAMMA"
    if gex < reference:
        return "NEGATIVE_GAMMA"
    return "NEUTRAL"


def iv_regime(current_iv: float, baseline_iv: float, expansion_threshold: float = 0.03) -> tuple[float, str]:
    current = _num(current_iv)
    baseline = _num(baseline_iv)
    if current <= 0 or baseline <= 0:
        return 0.0, "UNKNOWN"
    change = (current - baseline) / baseline
    if change >= expansion_threshold:
        return change, "EXPANDING"
    if change <= -expansion_threshold:
        return change, "CONTRACTING"
    return change, "STABLE"
