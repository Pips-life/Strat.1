"""Strategy 006 — Options Flow.

Independent options-flow strategy. Two daily files establish a static map;
live market ticks are used only to react to those calculated zones.

Dealer positioning is explicitly an estimate. Open interest alone cannot
identify the dealer's true side, so the default GEX convention is configurable.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from math import isfinite
from typing import Any, Iterable, Mapping, Sequence

from .base import Signal, Strategy


@dataclass(frozen=True)
class OptionsRow:
    strike: float
    option_type: str
    open_interest: float = 0.0
    volume: float = 0.0
    gamma: float = 0.0
    delta: float = 0.0
    vega: float = 0.0
    theta: float = 0.0
    iv: float = 0.0
    premium: float = 0.0


@dataclass(frozen=True)
class OptionsZones:
    upper_inventory_ceiling: float | None
    reclaim_gate: float | None
    immediate_hedge_wall: float | None
    primary_hedge_floor: float | None
    call_wall: float | None
    put_wall: float | None
    gamma_flip: float | None
    positive_gex_region: tuple[float, float] | None
    negative_gex_region: tuple[float, float] | None


@dataclass(frozen=True)
class OptionsFlowMap:
    spot: float | None
    rows: tuple[OptionsRow, ...]
    call_gex_by_strike: dict[float, float]
    put_gex_by_strike: dict[float, float]
    net_gex_by_strike: dict[float, float]
    net_gex: float
    zones: OptionsZones
    qof: float
    flow_bias: str
    data_valid: bool
    warnings: tuple[str, ...] = ()


@dataclass
class Strategy006Config:
    risk_fraction: float = 0.05
    minimum_reward_risk: float = 1.35
    zone_buffer_fraction: float = 0.10
    gex_multiplier: float = 100.0
    dealer_side_convention: str = "dealer_short_options"
    minimum_zone_score: float = 0.25
    require_rejection: bool = True


def _number(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value) if isfinite(float(value)) else None
    text = str(value).strip().replace(",", "").replace("%", "")
    if not text or text in {"-", "—", "N/A", "NA", "null"}:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def _pick(row: Mapping[str, Any], *names: str) -> Any:
    lowered = {str(k).strip().lower().replace("_", " "): v for k, v in row.items()}
    for name in names:
        key = name.lower().replace("_", " ")
        if key in lowered:
            return lowered[key]
    return None


def normalize_rows(rows: Iterable[Mapping[str, Any]]) -> list[OptionsRow]:
    result: list[OptionsRow] = []
    for raw in rows:
        strike = _number(_pick(raw, "strike", "strike price"))
        typ = str(_pick(raw, "type", "option type", "call/put", "put/call") or "").upper()
        if typ.startswith("C"):
            typ = "C"
        elif typ.startswith("P"):
            typ = "P"
        if strike is None or typ not in {"C", "P"}:
            continue
        result.append(OptionsRow(
            strike=strike,
            option_type=typ,
            open_interest=_number(_pick(raw, "open interest", "oi")) or 0.0,
            volume=_number(_pick(raw, "volume", "vol")) or 0.0,
            gamma=_number(_pick(raw, "gamma")) or 0.0,
            delta=_number(_pick(raw, "delta")) or 0.0,
            vega=_number(_pick(raw, "vega")) or 0.0,
            theta=_number(_pick(raw, "theta")) or 0.0,
            iv=_number(_pick(raw, "iv", "implied volatility", "implied vol")) or 0.0,
            premium=_number(_pick(raw, "premium", "last", "option price", "price")) or 0.0,
        ))
    return result


def _gex(row: OptionsRow, spot: float, multiplier: float, convention: str) -> float:
    # Dollar-gamma approximation. The 0.01 factor expresses a 1% underlying move.
    # Sign is an estimate, not a claim about the dealer's actual book.
    sign = 1.0 if row.option_type == "C" else -1.0
    if convention != "dealer_short_options":
        sign *= -1.0
    return sign * row.gamma * row.open_interest * multiplier * (spot ** 2) * 0.01


def _strongest_strike(values: Mapping[float, float], absolute: bool = False) -> float | None:
    if not values:
        return None
    return max(values, key=lambda k: abs(values[k]) if absolute else values[k])


def _gamma_flip(values: Mapping[float, float]) -> float | None:
    ordered = sorted(values)
    if len(ordered) < 2:
        return None
    running = 0.0
    prev_sign = 0
    for strike in ordered:
        running += values[strike]
        sign = 1 if running > 0 else -1 if running < 0 else 0
        if prev_sign and sign and sign != prev_sign:
            return strike
        if sign:
            prev_sign = sign
    return None


def calculate_options_map(rows: Sequence[OptionsRow], spot: float | None,
                          config: Strategy006Config | None = None) -> OptionsFlowMap:
    cfg = config or Strategy006Config()
    warnings: list[str] = []
    if not rows:
        return OptionsFlowMap(None, (), {}, {}, {}, 0.0,
            OptionsZones(None, None, None, None, None, None, None, None, None),
            0.0, "NEUTRAL", False, ("No valid option rows.",))
    if spot is None or spot <= 0:
        # Mapping remains useful without spot, but live-zone classification cannot be done.
        spot = None
        warnings.append("Underlying spot is missing; live zone reaction is blocked until MT5 price is supplied.")

    call: dict[float, float] = {}
    put: dict[float, float] = {}
    net: dict[float, float] = {}
    if spot is not None:
        for row in rows:
            value = _gex(row, spot, cfg.gex_multiplier, cfg.dealer_side_convention)
            target = call if row.option_type == "C" else put
            target[row.strike] = target.get(row.strike, 0.0) + value
            net[row.strike] = net.get(row.strike, 0.0) + value

    call_wall = _strongest_strike(call)
    put_wall = _strongest_strike(put, absolute=True)
    flip = _gamma_flip(net)
    positive = [k for k, v in net.items() if v > 0]
    negative = [k for k, v in net.items() if v < 0]
    pos_region = (min(positive), max(positive)) if positive else None
    neg_region = (min(negative), max(negative)) if negative else None

    upper = None
    floor = None
    immediate = None
    reclaim = None
    if spot is not None:
        upper_candidates = [k for k in call if k > spot]
        lower_candidates = [k for k in put if k < spot]
        upper = max(upper_candidates, key=lambda k: call[k]) if upper_candidates else call_wall
        floor = min(lower_candidates, key=lambda k: abs(k - spot)) if lower_candidates else put_wall
        # Immediate Hedge Wall is the nearest opposing wall in the trade direction.
        opposing = [k for k in (call_wall, put_wall) if k is not None and ((k > spot) if upper is not None and upper > spot else (k < spot))]
        if opposing:
            immediate = min(opposing, key=lambda k: abs(k - spot))
        # Reclaim gate is the nearest meaningful upper boundary, distinct from
        # the wall itself: it is the lower edge of the upper inventory zone.
        if upper is not None:
            reclaim = upper

    total = sum(net.values())
    gross = sum(abs(v) for v in net.values()) or 1.0
    qof = max(-100.0, min(100.0, 100.0 * total / gross))
    bias = "UPSIDE" if qof >= 20 else "DOWNSIDE" if qof <= -20 else "NEUTRAL"

    zones = OptionsZones(
        upper_inventory_ceiling=upper,
        reclaim_gate=reclaim,
        immediate_hedge_wall=immediate,
        primary_hedge_floor=floor,
        call_wall=call_wall,
        put_wall=put_wall,
        gamma_flip=flip,
        positive_gex_region=pos_region,
        negative_gex_region=neg_region,
    )
    return OptionsFlowMap(
        spot=spot, rows=tuple(rows), call_gex_by_strike=call,
        put_gex_by_strike=put, net_gex_by_strike=net, net_gex=total,
        zones=zones, qof=qof, flow_bias=bias, data_valid=True,
        warnings=tuple(warnings),
    )


def zone_trade(map_: OptionsFlowMap, live_price: float, account_balance: float,
                direction: str, stop_loss: float, cfg: Strategy006Config | None = None) -> Signal:
    cfg = cfg or Strategy006Config()
    if not map_.data_valid or live_price <= 0 or account_balance <= 0:
        return Signal(reason="Options Flow map or account data is not valid.")
    if stop_loss <= 0:
        return Signal(reason="A valid stop-loss is required.")
    z = map_.zones
    if direction == "BUY":
        origin, target = z.primary_hedge_floor, z.immediate_hedge_wall
        if origin is None or target is None or target <= live_price:
            return Signal(reason="No valid upward zone-to-zone target.")
        risk_distance = live_price - stop_loss
        reward = target - live_price
    elif direction == "SELL":
        origin, target = z.immediate_hedge_wall, z.primary_hedge_floor
        if origin is None or target is None or target >= live_price:
            return Signal(reason="No valid downward zone-to-zone target.")
        risk_distance = stop_loss - live_price
        reward = live_price - target
    else:
        return Signal(reason="Direction must be BUY or SELL.")
    if risk_distance <= 0:
        return Signal(reason="Stop-loss is on the wrong side of entry.")
    rr = reward / risk_distance
    if rr < cfg.minimum_reward_risk:
        return Signal(reason=f"Zone target gives {rr:.2f}R, below minimum {cfg.minimum_reward_risk:.2f}R.")
    risk_amount = account_balance * cfg.risk_fraction
    return Signal(
        action=direction, confidence=max(0.0, min(100.0, abs(map_.qof))),
        entry=live_price, stop_loss=stop_loss, take_profit=target,
        reason=f"Options Flow zone-to-zone {direction}: origin {origin} → target {target}.",
        metadata={
            "strategy": "006", "risk_amount": risk_amount,
            "risk_fraction": cfg.risk_fraction, "reward_risk": rr,
            "qof": map_.qof, "flow_bias": map_.flow_bias,
            "exit_rule": "nearest_opposing_zone",
        },
    )


class Strategy006(Strategy):
    id = "006"
    name = "Options Flow"
    version = "1.0.0"

    def __init__(self, config: Strategy006Config | None = None) -> None:
        self.config = config or Strategy006Config()
        self._map: OptionsFlowMap | None = None

    def load_options(self, rows: Iterable[Mapping[str, Any]], spot: float | None = None) -> OptionsFlowMap:
        normalized = normalize_rows(rows)
        self._map = calculate_options_map(normalized, spot, self.config)
        return self._map

    @property
    def options_map(self) -> OptionsFlowMap | None:
        return self._map

    def analyze(self, market: Any) -> dict[str, Any]:
        if self._map is None:
            return {"ready": False, "reason": "Load the two options files before trading."}
        live_price = market.get("price") if isinstance(market, Mapping) else None
        return {
            "ready": self._map.data_valid and live_price is not None,
            "map": self._map,
            "live_price": live_price,
            "zones": self._map.zones,
        }

    def generate_signal(self, analysis: dict[str, Any]) -> Signal:
        if not analysis.get("ready"):
            return Signal(reason=analysis.get("reason", "Waiting for valid options map and live MT5 price."))
        return Signal(reason="Waiting for a confirmed zone reaction; Strategy 006 does not enter on a zone touch alone.")

    def risk_parameters(self) -> dict[str, Any]:
        return {
            "risk_per_trade": self.config.risk_fraction,
            "max_risk_per_trade": 0.05,
            "minimum_reward_risk": self.config.minimum_reward_risk,
            "exit": "nearest_opposing_zone",
        }
