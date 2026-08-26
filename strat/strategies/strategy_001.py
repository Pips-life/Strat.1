"""Strategy 001: QOF (Quantitative Options Flow).

Canonical path: market intelligence -> QOF-implied market map -> confluence
-> QOF entry/target validation -> global risk/execution.

Conventional TradingView structure is secondary context only and is never an
entry prerequisite.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Optional

from strat.confluence import ConfluenceConfig, ConfluenceEngine
from strat.intelligence import delta_pressure, gamma_exposure, gamma_regime, iv_regime, velocity_signal
from strat.core.models import PriceBar
from strat.zones.engine import QOFStructureEngine
from strat.zones.models import ZoneRole
from .base import Signal, Strategy
from .registry import registry


@dataclass(frozen=True)
class Strategy001Config:
    confluence_minimum: float = 65.0
    strong_confluence: float = 75.0
    minimum_directional_edge: float = 12.0
    minimum_rr: float = 1.35
    entry_zone_atr: float = 0.35
    stop_buffer_atr: float = 0.10
    end_of_day_flatten_minutes: int = 15


def _bars(market: Dict[str, Any]) -> list[PriceBar]:
    return [bar for bar in (market.get("bars", ()) or ()) if isinstance(bar, PriceBar)]


@registry.register
class Strategy001(Strategy):
    id = "strategy_001"
    name = "QOF — Quantitative Options Flow"
    version = "4.1.0"

    def __init__(self, config: Optional[Strategy001Config] = None) -> None:
        self.config = config or Strategy001Config()
        self.qof_structure_engine = QOFStructureEngine()
        self.confluence = ConfluenceEngine(ConfluenceConfig(
            minimum_score=self.config.confluence_minimum,
            strong_score=self.config.strong_confluence,
            minimum_directional_edge=self.config.minimum_directional_edge,
        ))

    def _market_map(self, market: Dict[str, Any]) -> list[Any]:
        """Return the canonical QOF market map from QOFStructureEngine.

        An externally supplied QOF map may be consumed when produced by the
        canonical engine upstream. Otherwise the engine builds the map from
        the current causal market snapshot. There is no legacy price-structure
        fallback: conventional TradingView structure is secondary evidence.
        """
        supplied = market.get("qof_market_map") or market.get("qof_structure_map")
        if supplied:
            return list(supplied)
        bars = _bars(market)
        if not bars:
            return []
        options = market.get("options", ()) or ()
        zones = self.qof_structure_engine.build(
            bars,
            float(market["atr"]),
            float(market["price"]),
            options=options,
            as_of_index=market.get("as_of_index"),
        )
        return self.qof_structure_engine.update_states(
            zones,
            float(market["price"]),
            float(market["atr"]),
            market.get("timestamp", bars[-1].timestamp),
        )

    def _intelligence(self, market: Dict[str, Any]) -> Dict[str, Any]:
        options = market.get("options", ()) or ()
        price = float(market["price"])
        delta = delta_pressure(options, price)
        gex = gamma_exposure(options, price)
        gamma_state = gamma_regime(gex)
        volatility = market.get("volatility", {}) or {}
        current_iv = float(market.get("iv", volatility.get("iv", 0.0)) or 0.0)
        baseline_iv = float(market.get("iv_baseline", volatility.get("iv_baseline", 0.0)) or 0.0)
        iv_change, iv_state = iv_regime(current_iv, baseline_iv)
        momentum = velocity_signal(_bars(market))
        return {"delta_pressure": delta, "gex": gex, "gamma_regime": gamma_state, "iv_change": iv_change, "iv_regime": iv_state, "velocity_regime": momentum.regime, "velocity_ratio": momentum.velocity_ratio, "relative_volume": momentum.relative_volume}

    @staticmethod
    def _zone_dict(zone: Any) -> Dict[str, Any]:
        if isinstance(zone, dict):
            return zone
        return {"id": getattr(zone, "id", None), "center": getattr(zone, "center", None), "lower": getattr(zone, "lower", None), "upper": getattr(zone, "upper", None), "role": getattr(getattr(zone, "role", None), "value", getattr(zone, "role", None)), "state": getattr(getattr(zone, "state", None), "value", getattr(zone, "state", None)), "strength": getattr(zone, "strength", 0.0), "type": getattr(getattr(zone, "type", None), "value", getattr(zone, "type", None)), "origin": getattr(getattr(zone, "origin", None), "value", getattr(zone, "origin", None))}

    def _component_scores(self, market: Dict[str, Any], intelligence: Dict[str, Any], zones: list[Any]) -> tuple[dict[str, float], dict[str, float]]:
        supplied = market.get("evidence", {}) or {}
        if supplied.get("long") or supplied.get("short"):
            return dict(supplied.get("long", {}) or {}), dict(supplied.get("short", {}) or {})
        flow_direction = str(market.get("flow_direction", "")).upper()
        flow_strength = max(0.0, min(100.0, float(market.get("flow_strength", 50.0))))
        delta = float(intelligence["delta_pressure"])
        velocity = intelligence["velocity_regime"]
        rel_volume = float(intelligence["relative_volume"])
        gamma_state = intelligence["gamma_regime"]
        iv_state = intelligence["iv_regime"]
        zone_dicts = [self._zone_dict(z) for z in zones]

        def structure(side: str) -> float:
            role = ZoneRole.SUPPORT.value if side == "LONG" else ZoneRole.RESISTANCE.value
            candidates = [z for z in zone_dicts if str(z.get("role", "")).upper() == role and z.get("center") is not None]
            primary = [z for z in candidates if bool(z.get("qof_primary", True))]
            candidates = primary or candidates
            if not candidates:
                return 50.0
            nearest = min(candidates, key=lambda z: abs(float(z["center"]) - float(market["price"])))
            distance = abs(float(nearest["center"]) - float(market["price"]))
            strength = float(nearest.get("strength", nearest.get("confidence", 50.0)))
            proximity = max(0.0, 1.0 - distance / max(float(market["atr"]) * 2.0, 1e-9))
            return min(100.0, 50.0 + 0.5 * strength * proximity)

        def directional_from_signed(value: float, side: str) -> float:
            signed = value if side == "LONG" else -value
            return 50.0 + 0.5 * max(-100.0, min(100.0, signed))

        def momentum(side: str) -> float:
            bars = _bars(market)
            direction = 1.0 if len(bars) < 2 or bars[-1].close >= bars[-2].close else -1.0
            aligned = direction > 0 if side == "LONG" else direction < 0
            if velocity == "EXPANSION":
                return 80.0 if aligned else 35.0
            if velocity == "CONTRACTION":
                return 55.0
            return 60.0 if aligned else 45.0

        def gamma_score() -> float:
            if gamma_state == "NEGATIVE_GAMMA" and velocity == "EXPANSION":
                return 75.0
            if gamma_state == "POSITIVE_GAMMA" and velocity == "CONTRACTION":
                return 70.0
            return 55.0

        iv_score = 70.0 if iv_state == "EXPANDING" else 55.0 if iv_state == "CONTRACTING" else 50.0
        volume_score = 70.0 if rel_volume >= 1.20 else 60.0 if rel_volume >= 1.0 else 45.0
        gamma = gamma_score()
        long_scores = {"structure": structure("LONG"), "options_flow": flow_strength if flow_direction == "LONG" else 100.0 - flow_strength if flow_direction == "SHORT" else 50.0, "gamma": gamma, "delta": directional_from_signed(delta, "LONG"), "iv": iv_score, "velocity": momentum("LONG"), "volume": volume_score}
        short_scores = {"structure": structure("SHORT"), "options_flow": flow_strength if flow_direction == "SHORT" else 100.0 - flow_strength if flow_direction == "LONG" else 50.0, "gamma": gamma, "delta": directional_from_signed(delta, "SHORT"), "iv": iv_score, "velocity": momentum("SHORT"), "volume": volume_score}
        return long_scores, short_scores

    def analyze(self, market: Any) -> Dict[str, Any]:
        if not isinstance(market, dict):
            raise TypeError("market must be a dictionary")
        zones = self._market_map(market)
        intelligence = self._intelligence(market)
        long_components, short_components = self._component_scores(market, intelligence, zones)
        result = self.confluence.evaluate_directional(long_components, short_components, market.get("contradictions", []) or [], derived=intelligence)
        return {**market, "qof_market_map": zones, "intelligence": intelligence, "confluence_result": result}

    def _relevant_zone(self, analysis: Dict[str, Any], side: str) -> Optional[Dict[str, Any]]:
        zones = [self._zone_dict(z) for z in analysis.get("qof_market_map", []) or ()]
        price = float(analysis["price"])
        role = ZoneRole.SUPPORT.value if side == "LONG" else ZoneRole.RESISTANCE.value
        candidates = [z for z in zones if str(z.get("role", "")).upper() == role and z.get("center") is not None and bool(z.get("qof_primary", True))]
        if not candidates:
            return None
        directional = [z for z in candidates if (side == "LONG" and float(z["center"]) <= price) or (side == "SHORT" and float(z["center"]) >= price)]
        return min(directional or candidates, key=lambda z: abs(float(z["center"]) - price))

    def _target_zone(self, analysis: Dict[str, Any], side: str, price: float) -> Optional[Dict[str, Any]]:
        zones = [self._zone_dict(z) for z in analysis.get("qof_market_map", []) or ()]
        target_role = ZoneRole.RESISTANCE.value if side == "LONG" else ZoneRole.SUPPORT.value
        candidates = [z for z in zones if str(z.get("role", "")).upper() == target_role and z.get("center") is not None and bool(z.get("qof_primary", True))]
        if side == "LONG":
            candidates = [z for z in candidates if float(z["center"]) > price]
            return min(candidates, key=lambda z: float(z["center"]) - price) if candidates else None
        candidates = [z for z in candidates if float(z["center"]) < price]
        return min(candidates, key=lambda z: price - float(z["center"])) if candidates else None

    def _manage_position(self, analysis: Dict[str, Any]) -> Optional[Signal]:
        position = analysis.get("position") or {}
        side = str(position.get("side", "")).upper()
        if side not in {"LONG", "SHORT"}:
            return None
        price = float(analysis["price"])
        minutes_left = analysis.get("minutes_to_session_close")
        if minutes_left is not None and float(minutes_left) <= self.config.end_of_day_flatten_minutes:
            return Signal("CLOSE", 100.0, reason="Mandatory intraday end-of-day flatten.", metadata={"exit_type": "TIME_EXIT"})
        stop = position.get("stop_loss")
        target = position.get("take_profit")
        if stop is not None and ((side == "LONG" and price <= float(stop)) or (side == "SHORT" and price >= float(stop))):
            return Signal("CLOSE", 100.0, reason=f"{side} stop reached.", metadata={"exit_type": "STOP"})
        if target is not None and ((side == "LONG" and price >= float(target)) or (side == "SHORT" and price <= float(target))):
            return Signal("CLOSE", 100.0, reason=f"{side} target reached.", metadata={"exit_type": "TARGET"})
        return Signal("WAIT", 0.0, reason="Open intraday position remains inside its plan.", metadata={"manage": True})

    def _entry(self, analysis: Dict[str, Any], side: str) -> Signal:
        result = analysis["confluence_result"]
        if result.direction != side or not result.tradable:
            return Signal("WAIT", result.score, reason="QOF confluence direction, score, or edge is insufficient.")
        zone = self._relevant_zone(analysis, side)
        if zone is None:
            return Signal("WAIT", result.score, reason="No relevant QOF-implied structure in the market map.")
        price = float(analysis["price"])
        atr = float(analysis["atr"])
        if abs(price - float(zone["center"])) > atr * self.config.entry_zone_atr:
            return Signal("WAIT", result.score, reason="Price is outside the QOF-implied structure entry area.")
        if side == "LONG":
            stop = float(zone["lower"]) - self.config.stop_buffer_atr * atr
        else:
            stop = float(zone["upper"]) + self.config.stop_buffer_atr * atr
        target_zone = self._target_zone(analysis, side, price)
        if target_zone is None:
            return Signal("WAIT", result.score, reason="No valid opposing QOF-implied structure for a same-day target.")
        target = float(target_zone["center"])
        risk = abs(price - stop)
        reward = abs(target - price)
        rr = reward / risk if risk > 0 else 0.0
        if rr < self.config.minimum_rr:
            return Signal("WAIT", result.score, reason=f"Only {rr:.2f}R available; minimum is {self.config.minimum_rr:.2f}R.")
        return Signal(action="BUY" if side == "LONG" else "SELL", confidence=result.score, entry=price, stop_loss=stop, take_profit=target, reason=f"QOF {side} structure-confluence setup confirmed with {rr:.2f}R available.", metadata={"strategy": self.id, "structure_type": "QOF_IMPLIED", "setup_zone": zone, "target_zone": target_zone, "confluence": result, "intraday_only": True})

    def generate_signal(self, analysis: Dict[str, Any]) -> Signal:
        managed = self._manage_position(analysis)
        if managed is not None:
            return managed
        if analysis.get("position"):
            return Signal("WAIT", 0.0, reason="Unknown position state; new entries blocked.")
        minutes_left = analysis.get("minutes_to_session_close")
        if minutes_left is not None and float(minutes_left) <= self.config.end_of_day_flatten_minutes:
            return Signal("WAIT", 0.0, reason="Too close to session close for a new entry.")
        requested = str(analysis.get("setup_side", "")).upper()
        if requested in {"LONG", "SHORT"}:
            return self._entry(analysis, requested)
        result = analysis["confluence_result"]
        return self._entry(analysis, result.direction) if result.direction in {"LONG", "SHORT"} else Signal("WAIT", result.score, reason="No directional QOF confluence.")

    def risk_parameters(self) -> Dict[str, Any]:
        return {"min_confidence": self.config.confluence_minimum, "max_positions": 1, "intraday_only": True, "end_of_day_flatten_minutes": self.config.end_of_day_flatten_minutes, "minimum_rr": self.config.minimum_rr, "risk_per_trade": "delegated_to_global_risk_engine"}
