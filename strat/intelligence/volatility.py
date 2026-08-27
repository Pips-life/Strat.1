"""Complete provider-neutral volatility and Greeks intelligence for QOF."""
from __future__ import annotations

from dataclasses import dataclass
from math import isfinite
from typing import Any, Iterable, Mapping


def _num(value: object, default: float = 0.0) -> float:
    try:
        value = float(value)
    except (TypeError, ValueError):
        return default
    return value if isfinite(value) else default


def _first(mapping: Mapping[str, Any], *keys: str, default: float = 0.0) -> float:
    for key in keys:
        if key in mapping and mapping[key] not in (None, ""):
            return _num(mapping[key], default)
    return default


@dataclass(frozen=True)
class VolatilitySnapshot:
    iv: float = 0.0
    iv_change: float = 0.0
    iv_regime: str = "UNKNOWN"
    iv_rank: float = 50.0
    iv_percentile: float = 50.0
    realized_volatility: float = 0.0
    iv_rv_spread: float = 0.0
    iv_rv_ratio: float = 0.0
    put_iv: float = 0.0
    call_iv: float = 0.0
    atm_iv: float = 0.0
    skew: float = 0.0
    skew_change: float = 0.0
    term_structure_slope: float = 0.0
    term_structure_regime: str = "UNKNOWN"
    vega_exposure: float = 0.0
    theta_exposure: float = 0.0
    vanna_exposure: float = 0.0
    charm_exposure: float = 0.0
    volatility_score: float = 50.0


def _aggregate(options: Iterable[Mapping[str, Any]], field: str) -> float:
    total = 0.0
    for row in options:
        contracts = abs(_first(row, "contracts", "volume", default=0.0))
        multiplier = abs(_first(row, "multiplier", default=1.0)) or 1.0
        total += _num(row.get(field)) * contracts * multiplier
    return total


def _weighted_iv(options: Iterable[Mapping[str, Any]], kind: str) -> float:
    weighted = 0.0
    weight = 0.0
    for row in options:
        option_kind = str(row.get("option_type", row.get("type", ""))).upper()
        if kind == "PUT" and option_kind not in {"P", "PUT", "BUY_PUT"}:
            continue
        if kind == "CALL" and option_kind not in {"C", "CALL", "BUY_CALL"}:
            continue
        iv = _first(row, "iv", "implied_volatility", default=0.0)
        w = abs(_first(row, "open_interest", "oi", "volume", "contracts", default=0.0))
        if iv > 0 and w > 0:
            weighted += iv * w
            weight += w
    return weighted / weight if weight else 0.0


def _term_slope(volatility: Mapping[str, Any]) -> float:
    direct = _first(volatility, "term_structure_slope", "iv_term_slope", default=float("nan"))
    if isfinite(direct):
        return direct
    curve = volatility.get("term_structure") or volatility.get("iv_by_expiry") or ()
    rows: list[tuple[float, float]] = []
    if isinstance(curve, Mapping):
        for days, iv in curve.items():
            d = _num(days, float("nan"))
            v = _num(iv, float("nan"))
            if isfinite(d) and isfinite(v):
                rows.append((d, v))
    else:
        for row in curve:
            if not isinstance(row, Mapping):
                continue
            d = _first(row, "days", "dte", default=float("nan"))
            v = _first(row, "iv", "implied_volatility", default=float("nan"))
            if isfinite(d) and isfinite(v):
                rows.append((d, v))
    rows.sort()
    if len(rows) < 2 or rows[-1][0] == rows[0][0]:
        return 0.0
    return (rows[-1][1] - rows[0][1]) / (rows[-1][0] - rows[0][0])


def build_volatility_snapshot(
    options: Iterable[Mapping[str, Any]] = (),
    volatility: Mapping[str, Any] | None = None,
    *,
    current_iv: float = 0.0,
    baseline_iv: float = 0.0,
    velocity_regime: str = "UNKNOWN",
) -> VolatilitySnapshot:
    """Build a causal volatility/Greeks snapshot from normalized provider data."""
    vol = volatility or {}
    option_rows = list(options or ())
    iv = _first(vol, "iv", "implied_volatility", "atm_iv", default=current_iv)
    if iv <= 0:
        iv = _weighted_iv(option_rows, "CALL") or _weighted_iv(option_rows, "PUT")
    baseline = _first(vol, "iv_baseline", default=baseline_iv)
    iv_change = (iv - baseline) / baseline if iv > 0 and baseline > 0 else 0.0
    if baseline <= 0:
        iv_regime = "UNKNOWN"
    elif iv_change >= 0.03:
        iv_regime = "EXPANDING"
    elif iv_change <= -0.03:
        iv_regime = "CONTRACTING"
    else:
        iv_regime = "STABLE"

    put_iv = _first(vol, "put_iv", default=0.0) or _weighted_iv(option_rows, "PUT")
    call_iv = _first(vol, "call_iv", default=0.0) or _weighted_iv(option_rows, "CALL")
    atm_iv = _first(vol, "atm_iv", default=iv)
    skew = _first(vol, "skew", "put_call_skew", "iv_skew", default=(put_iv - call_iv if put_iv and call_iv else 0.0))
    skew_change = _first(vol, "skew_change", "iv_skew_change", default=0.0)
    rv = _first(vol, "realized_volatility", "rv", default=0.0)
    iv_rv_spread = iv - rv if iv > 0 and rv > 0 else 0.0
    iv_rv_ratio = iv / rv if iv > 0 and rv > 0 else 0.0
    slope = _term_slope(vol)
    term_regime = "STEEPENING" if slope > 0 else "INVERTED" if slope < 0 else "FLAT"
    vega = _first(vol, "vega", "vega_exposure", default=0.0) or _aggregate(option_rows, "vega")
    theta = _first(vol, "theta", "theta_exposure", default=0.0) or _aggregate(option_rows, "theta")
    vanna = _first(vol, "vanna", "vanna_exposure", default=0.0) or _aggregate(option_rows, "vanna")
    charm = _first(vol, "charm", "charm_exposure", default=0.0) or _aggregate(option_rows, "charm")
    rank = max(0.0, min(100.0, _first(vol, "iv_rank", default=50.0)))
    percentile = max(0.0, min(100.0, _first(vol, "iv_percentile", default=50.0)))

    score = 50.0
    if iv_regime == "EXPANDING":
        score += 12.0
    elif iv_regime == "CONTRACTING":
        score += 5.0
    if velocity_regime == "EXPANSION" and iv_regime == "EXPANDING":
        score += 12.0
    if rv > 0 and iv_rv_ratio >= 1.25:
        score += 8.0
    elif rv > 0 and iv_rv_ratio <= 0.85:
        score += 5.0
    if max(rank, percentile) >= 80.0:
        score += 6.0
    elif max(rank, percentile) <= 20.0:
        score += 3.0
    if skew_change != 0.0:
        score += 4.0
    if slope != 0.0:
        score += 3.0
    score = max(0.0, min(100.0, score))

    return VolatilitySnapshot(
        iv=iv, iv_change=iv_change, iv_regime=iv_regime,
        iv_rank=rank, iv_percentile=percentile,
        realized_volatility=rv, iv_rv_spread=iv_rv_spread, iv_rv_ratio=iv_rv_ratio,
        put_iv=put_iv, call_iv=call_iv, atm_iv=atm_iv,
        skew=skew, skew_change=skew_change,
        term_structure_slope=slope, term_structure_regime=term_regime,
        vega_exposure=vega, theta_exposure=theta,
        vanna_exposure=vanna, charm_exposure=charm,
        volatility_score=score,
    )
