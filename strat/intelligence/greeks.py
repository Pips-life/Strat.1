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
    """Return normalized directional delta pressure in [-100, 100].

    Each record may contain contracts, multiplier, delta and side/type. Calls
    contribute positive delta and puts negative delta. The result is normalized
    by gross absolute delta exposure so contract count alone cannot dominate.
    """
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


def gamma_exposure(options: Iterable[Mapping[str, object]], underlying_price: float) -> float:
    """Return a convention-neutral raw net GEX proxy.

    The caller must supply ``gex_sign`` when the provider's dealer convention
    differs from the default. This keeps vendor-specific sign assumptions out
    of the confluence engine.
    """
    total = 0.0
    price_sq = max(underlying_price, 0.0) ** 2
    for row in options:
        gamma = _num(row.get("gamma"))
        oi = abs(_num(row.get("open_interest", row.get("oi", 0))))
        multiplier = abs(_num(row.get("multiplier", 1), 1))
        sign = _num(row.get("gex_sign", 1), 1)
        total += gamma * oi * multiplier * price_sq * sign
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
