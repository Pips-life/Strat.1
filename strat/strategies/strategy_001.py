"""Strategy 001: zone-aware intraday directional strategy.

The strategy consumes the single canonical S/R market map and the seven-factor
Confluence Engine. It does not maintain a second options-level rule set.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Optional

from strat.confluence import ConfluenceConfig, ConfluenceEngine
from strat.intelligence import delta_pressure, gamma_exposure, gamma_regime, iv_regime, velocity_signal
from strat.core.models import PriceBar
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
    fallback_stop_atr: float = 0.30
    end_of_day_flatten_minutes: int = 15


def _bars(market: Dict[str, Any]) -> list[PriceBar]:
    result: list[PriceBar] = []
    for row in market.get("bars", ()) or ():
        if isinstance(row, PriceBar):
            result.append(row)
    return result


@registry.register
class Strategy001(Strategy):
    id = "strategy_001"
    name = "Quantitative Zone Confluence"
    version = "3.0.0"

    def __init__(self, config: Optional[Strategy001Config] = None) -> None:
        self.config = config or Strategy001Config()
        self.confluence = ConfluenceEngine(ConfluenceConfig(
            minimum_score=self.config.confluence_minimum,
            strong_score=self.config.strong_confluence,
            minimum_directional_edge=self.config.minimum_directional_edge,
        ))

    def _intelligence(self, market: Dict[str, Any]) -> Dict[str, Any]:
        options = market.get("options", ()) or ()
        price = float(market["price"])
        delta = delta_pressure(options, price)
        gex = gamma_exposure(options, price)
        regime = gamma_regime(gex)
        current_iv = float(market.get("iv", market.get("volatility", {}).get("iv", 0.0)) or 0.0)
        baseline_iv = float(market.get("iv_baseline", market.get("volatility", {}).get("iv_baseline", 0.0)) or 0.0)
        iv_change, iv_state = iv_regime(current_iv, baseline_iv)
        momentum = velocity_signal(_bars(market))
        return {
            "delta_pressure": delta,
            "gex": gex,
            "gamma_regime": regime,
            "iv_change": iv_change,
            "iv_regime": iv_state,
            "velocity_regime": momentum.regime,
            "velocity_ratio": momentum.velocity_ratio,
            "relative_volume": momentum.relative_volume,
        }

    @staticmethod
    def _zone_dict(zone: Any) -> Dict[str, Any]:
        if isinstance(zone, dict):
            return zone
        return {
            "id": getattr(zone, "id", None),
            "center": getattr(zone, "center", None),
            "lower": getattr(zone, "lower", None),
            "upper": getattr(zone, "upper", None),
            "role": getattr(getattr(zone, "role", None), "value", getattr(zone, "role", None)),
            "state": getattr(getattr(zone, "state", None), "value", getattr(zone, "state", None)),
            "strength": getattr(zone, "strength", 0.0),
            "type": getattr(getattr(zone, "type", None), "value", getattr(zone, "type", None)),
        }

    def _relevant_zone(self, analysis: Dict[str, Any], side: str) -> Optional[Dict[str, Any]]:
        zones = [self._zone_dict(z) for z in analysis.get("zone_map", []) or []]
        role = ZoneRole.SUPPORT.value if side == "LONG" else ZoneRole.RESISTANCE.value
        price = float(analysis["price"])
        candidates = [z for z in zones if str(z.get("role", "")).upper() == role]
        candidates = [z for z in candidates if z.get("center") is not None]
        if not candidates:
            return None
        return min(candidates, key=lambda z: abs(float(z["center"]) - price))

    def analyze(self, market: Any) -> Dict[str, Any]:
        if not isinstance(market, dict):
            raise TypeError("market must be a dictionary")
        intelligence = self._intelligence(market)
        evidence = dict(market.get("evidence", {}) or {})
        # Provider adapters may supply calibrated directional component scores.
        # Raw Greek/velocity metrics are retained as diagnostics, not guessed into
        # directional scores when the provider has insufficient information.
        long_components = dict(evidence.get("long", {}) or {})
        short_components = dict(evidence.get("short", {}) or {})
        derived = dict(market.get("derived", {}) or {})
        derived.update(intelligence)
        result = self.confluence.evaluate_directional(
            long_components,
            short_components,
            market.get("contradictions", []) or [],
            derived=derived,
        )
        return {**market, "intelligence": intelligence, "confluence_result": result}

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
        cfg = self.config
        result = analysis["confluence_result"]
        if result.direction != side or not result.tradable or result.score < cfg.confluence_minimum:
            return Signal("WAIT", result.score, reason="Confluence direction, score, or edge is insufficient.")
        zone = self._relevant_zone(analysis, side)
        if zone is None:
            return Signal("WAIT", result.score, reason="No relevant S/R zone in the canonical market map.")
        price = float(analysis["price"])
        atr = float(analysis["atr"])
        if abs(price - float(zone["center"])) > atr * cfg.entry_zone_atr:
            return Signal("WAIT", result.score, reason="Price is outside the volatility-adjusted zone entry area.")
        trigger = (analysis.get("entry_trigger") or {}).get(side.lower(), False)
        if not trigger:
            return Signal("WAIT", result.score, reason="Strategy 001 price-action trigger is not confirmed.")

        center = float(zone["center"])
        if side == "LONG":
            stop = float(zone["lower"]) - cfg.stop_buffer_atr * atr
            targets = [self._zone_dict(z) for z in analysis.get("zone_map", []) or ()]
            targets = [z for z in targets if str(z.get("role", "")).upper() == ZoneRole.RESISTANCE.value and z.get("center") is not None and float(z["center"]) > price]
            target_zone = min(targets, key=lambda z: float(z["center"]) - price) if targets else None
        else:
            stop = float(zone["upper"]) + cfg.stop_buffer_atr * atr
            targets = [self._zone_dict(z) for z in analysis.get("zone_map", []) or ()]
            targets = [z for z in targets if str(z.get("role", "")).upper() == ZoneRole.SUPPORT.value and z.get("center") is not None and float(z["center"]) < price]
            target_zone = min(targets, key=lambda z: price - float(z["center"])) if targets else None
        if target_zone is None:
            return Signal("WAIT", result.score, reason="No valid opposing S/R zone for a same-day target.")
        target = float(target_zone["center"])
        risk = abs(price - stop)
        reward = abs(target - price)
        rr = reward / risk if risk > 0 else 0.0
        if rr < cfg.minimum_rr:
            return Signal("WAIT", result.score, reason=f"Only {rr:.2f}R available; minimum is {cfg.minimum_rr:.2f}R.")
        return Signal(
            action="BUY" if side == "LONG" else "SELL",
            confidence=result.score,
            entry=price,
            stop_loss=stop,
            take_profit=target,
            reason=f"{side} zone-confluence setup confirmed with {rr:.2f}R available.",
            metadata={
                "strategy": self.id,
                "setup_zone": zone,
                "target_zone": target_zone,
                "confluence": result,
                "intraday_only": True,
            },
        )

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
        return self._entry(analysis, result.direction) if result.direction in {"LONG", "SHORT"} else Signal("WAIT", result.score, reason="No directional confluence.")

    def risk_parameters(self) -> Dict[str, Any]:
        return {
            "min_confidence": self.config.confluence_minimum,
            "max_positions": 1,
            "intraday_only": True,
            "end_of_day_flatten_minutes": self.config.end_of_day_flatten_minutes,
            "minimum_rr": self.config.minimum_rr,
            "risk_per_trade": "delegated_to_global_risk_engine",
        }
