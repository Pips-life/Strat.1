"""Reusable options Greek and volatility calculations for Strategy 001."""
from __future__ import annotations

from dataclasses import dataclass
from math import exp, isfinite, log, pi, sqrt
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


def _norm_pdf(x: float) -> float:
    return exp(-0.5 * x * x) / sqrt(2.0 * pi)


def black76_d1(futures_price: float, strike: float, volatility: float, time_to_expiry: float) -> float:
    """Black-76 d1 for a futures option."""
    f, k, sigma, t = map(_num, (futures_price, strike, volatility, time_to_expiry))
    if f <= 0 or k <= 0 or sigma <= 0 or t <= 0:
        raise ValueError("futures_price, strike, volatility and time_to_expiry must be positive")
    return (log(f / k) + 0.5 * sigma * sigma * t) / (sigma * sqrt(t))


def black76_gamma(futures_price: float, strike: float, volatility: float, time_to_expiry: float, discount_factor: float = 1.0) -> float:
    """Black-76 gamma with respect to futures price."""
    f, sigma, t, df = _num(futures_price), _num(volatility), _num(time_to_expiry), _num(discount_factor, 1.0)
    if f <= 0 or sigma <= 0 or t <= 0 or df < 0:
        raise ValueError("invalid Black-76 inputs")
    return df * _norm_pdf(black76_d1(f, strike, sigma, t)) / (f * sigma * sqrt(t))


def delta_pressure(options: Iterable[Mapping[str, object]], underlying_price: float) -> float:
    """Return normalized directional delta pressure in [-100, 100]."""
    bullish = bearish = 0.0
    for row in options:
        contracts = abs(_num(row.get("contracts", row.get("volume", 0))))
        multiplier = abs(_num(row.get("multiplier", 1), 1))
        delta = abs(_num(row.get("delta", 0)))
        exposure = contracts * multiplier * delta * max(underlying_price, 0.0)
        kind = str(row.get("option_type", row.get("type", ""))).upper()
        if kind in {"C", "CALL", "BUY_CALL"}: bullish += exposure
        elif kind in {"P", "PUT", "BUY_PUT"}: bearish += exposure
    gross = bullish + bearish
    return 0.0 if gross <= 0 else max(-100.0, min(100.0, 100.0 * (bullish - bearish) / gross))


def _gex_sign(row: Mapping[str, object]) -> float | None:
    if "gex_sign" in row and row.get("gex_sign") not in (None, ""):
        raw = _num(row.get("gex_sign"), float("nan"))
        if isfinite(raw) and raw != 0: return 1.0 if raw > 0 else -1.0
    position = str(row.get("dealer_position", "")).upper().strip()
    if position in {"LONG", "BUY"}: return 1.0
    if position in {"SHORT", "SELL"}: return -1.0
    return None


def gamma_exposure(options: Iterable[Mapping[str, object]], underlying_price: float) -> float:
    """Return signed dealer GEX from explicitly signed dealer positioning only."""
    total = 0.0
    price_sq = max(underlying_price, 0.0) ** 2
    for row in options:
        sign = _gex_sign(row)
        if sign is None: continue
        total += sign * abs(_num(row.get("gamma"))) * abs(_num(row.get("open_interest", row.get("oi", 0)))) * abs(_num(row.get("multiplier", 1), 1)) * price_sq
    return total


def gamma_regime(gex: float, reference: float = 0.0) -> str:
    if not isfinite(gex): return "UNKNOWN"
    if gex > reference: return "POSITIVE_GAMMA"
    if gex < reference: return "NEGATIVE_GAMMA"
    return "NEUTRAL"


def iv_regime(current_iv: float, baseline_iv: float, expansion_threshold: float = 0.03) -> tuple[float, str]:
    current, baseline = _num(current_iv), _num(baseline_iv)
    if current <= 0 or baseline <= 0: return 0.0, "UNKNOWN"
    change = (current - baseline) / baseline
    if change >= expansion_threshold: return change, "EXPANDING"
    if change <= -expansion_threshold: return change, "CONTRACTING"
    return change, "STABLE"
