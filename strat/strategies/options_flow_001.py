"""Strategy 001: intraday Gold options-flow/GEX strategy.

Flow: market intelligence -> Confluence Engine -> Strategy 001 -> Signal.
The strategy is day-trading oriented: precise level entries, defined stops,
minimum reward/risk, and mandatory end-of-day flattening.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Optional

from strat.confluence import ConfluenceConfig, ConfluenceEngine
from .base import Signal, Strategy
from .registry import registry


@dataclass(frozen=True)
class OptionsFlow001Config:
    confluence_minimum: float = 62.0
    strong_confluence: float = 78.0
    min_entry_confidence: float = 70.0
    minimum_rr: float = 1.35
    entry_zone_atr: float = 0.25
    stop_buffer_atr: float = 0.10
    fallback_stop_atr: float = 0.30
    end_of_day_flatten_minutes: int = 15


@registry.register
class OptionsFlowStrategy(Strategy):
    id = "options_flow_001"
    name = "Gold Options Flow"
    version = "2.0.0"

    def __init__(self, config: Optional[OptionsFlow001Config] = None) -> None:
        self.config = config or OptionsFlow001Config()
        self.confluence = ConfluenceEngine(ConfluenceConfig(
            minimum_score=self.config.confluence_minimum,
            strong_score=self.config.strong_confluence,
        ))

    @staticmethod
    def _level(levels: Dict[str, Any], name: str) -> Optional[Dict[str, Any]]:
        value = levels.get(name)
        if value is None:
            return None
        if hasattr(value, "price"):
            return {"price": value.price, "confidence": getattr(value, "confidence", 0.0)}
        return value

    @staticmethod
    def _near(price: float, level: Optional[Dict[str, Any]], atr: float, max_atr: float) -> bool:
        return bool(level and level.get("price") is not None and
                    abs(price - float(level["price"])) <= atr * max_atr)

    def analyze(self, market: Any) -> Dict[str, Any]:
        if not isinstance(market, dict):
            raise TypeError("market must be a dictionary")
        result = self.confluence.evaluate(
            market.get("confluence", {}) or {},
            market.get("contradictions", []) or [],
        )
        return {**market, "confluence_result": result}

    def _manage_position(self, analysis: Dict[str, Any]) -> Optional[Signal]:
        position = analysis.get("position") or {}
        side = str(position.get("side", "")).upper()
        if side not in {"LONG", "SHORT"}:
            return None

        price = float(analysis["price"])
        minutes_left = analysis.get("minutes_to_session_close")

        if minutes_left is not None and float(minutes_left) <= self.config.end_of_day_flatten_minutes:
            return Signal("CLOSE", 100.0, reason="Mandatory intraday end-of-day flatten.",
                          metadata={"exit_type": "TIME_EXIT", "minutes_to_close": minutes_left})

        stop = position.get("stop_loss")
        target = position.get("take_profit")
        if stop is not None:
            stop = float(stop)
        if target is not None:
            target = float(target)

        hit_stop = (side == "LONG" and stop is not None and price <= stop) or (side == "SHORT" and stop is not None and price >= stop)
        hit_target = (side == "LONG" and target is not None and price >= target) or (side == "SHORT" and target is not None and price <= target)
        if hit_stop:
            return Signal("CLOSE", 100.0, reason=f"{side} stop reached.", metadata={"exit_type": "STOP"})
        if hit_target:
            return Signal("CLOSE", 100.0, reason=f"{side} target reached.", metadata={"exit_type": "TARGET"})

        return Signal("WAIT", 0.0, reason="Open intraday position remains inside its plan.", metadata={"manage": True})

    def _entry(self, analysis: Dict[str, Any], side: str) -> Signal:
        cfg = self.config
        price = float(analysis["price"])
        atr = float(analysis["atr"])
        result = analysis["confluence_result"]
        levels = analysis.get("levels", {}) or {}
        pa = analysis.get("price_action", {}) or {}
        liq = analysis.get("liquidity", {}) or {}

        if not result.tradable or result.score < cfg.min_entry_confidence:
            return Signal("WAIT", result.score, reason="Confluence below Strategy 001 entry threshold.")

        explicit_side = str(analysis.get("setup_side", "")).upper()
        if explicit_side and explicit_side != side:
            return Signal("WAIT", result.score, reason=f"Setup direction is {explicit_side}, not {side}.")

        if side == "LONG":
            location_names = ["active_dealer_support", "main_reclaim_pivot"]
            target_names = ["active_dealer_resistance", "dealer_ceiling"]
            trigger_ok = bool(pa.get("sweep_reclaim") or (pa.get("reclaim") and pa.get("higher_low")) or (pa.get("rejection") and pa.get("higher_low")))
            liquidity_ok = bool(liq.get("absorption") or liq.get("delta_reversal") or liq.get("sweep"))
        else:
            location_names = ["active_dealer_resistance", "dealer_ceiling", "main_reclaim_pivot"]
            target_names = ["active_dealer_support", "stabilization_support"]
            trigger_ok = bool(pa.get("sweep_rejection") or (pa.get("rejection") and pa.get("lower_high")) or (pa.get("breakdown") and pa.get("lower_high")))
            liquidity_ok = bool(liq.get("distribution") or liq.get("delta_reversal") or liq.get("sweep"))

        entry_level = next((self._level(levels, n) for n in location_names if self._level(levels, n)), None)
        if not self._near(price, entry_level, atr, cfg.entry_zone_atr):
            return Signal("WAIT", result.score, reason="Price is outside the precision entry zone.")
        if not trigger_ok:
            return Signal("WAIT", result.score, reason="Price-action trigger not confirmed.")
        if not liquidity_ok:
            return Signal("WAIT", result.score, reason="Liquidity/participation trigger not confirmed.")

        trigger_low = analysis.get("trigger_low")
        trigger_high = analysis.get("trigger_high")
        if side == "LONG":
            stop = float(trigger_low) - cfg.stop_buffer_atr * atr if trigger_low is not None else float(entry_level["price"]) - cfg.fallback_stop_atr * atr
        else:
            stop = float(trigger_high) + cfg.stop_buffer_atr * atr if trigger_high is not None else float(entry_level["price"]) + cfg.fallback_stop_atr * atr

        risk = abs(price - stop)
        if risk <= 0:
            return Signal("WAIT", result.score, reason="Invalid stop distance.")

        target_level = None
        for name in target_names:
            candidate = self._level(levels, name)
            if not candidate or candidate.get("price") is None:
                continue
            p = float(candidate["price"])
            if (side == "LONG" and p > price) or (side == "SHORT" and p < price):
                target_level = candidate
                break

        if target_level is None:
            return Signal("WAIT", result.score, reason="No valid same-day opposing options level for target.")

        target = float(target_level["price"])
        reward = abs(target - price)
        rr = reward / risk
        if rr < cfg.minimum_rr:
            return Signal("WAIT", result.score, reason=f"Only {rr:.2f}R available; minimum is {cfg.minimum_rr:.2f}R.")

        return Signal(
            action="BUY" if side == "LONG" else "SELL",
            confidence=result.score,
            entry=price,
            stop_loss=stop,
            take_profit=target,
            reason=f"{side} options-level setup confirmed by price action and liquidity; {rr:.2f}R available.",
            metadata={
                "strategy": self.id,
                "entry_level": entry_level,
                "target_level": target_level,
                "risk_distance": risk,
                "reward_distance": reward,
                "rr": rr,
                "intraday_only": True,
                "confluence": {
                    "score": result.score,
                    "grade": result.grade,
                    "components": result.components,
                    "missing": result.missing,
                    "contradictions": result.contradictions,
                },
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

        setup_side = str(analysis.get("setup_side", "")).upper()
        if setup_side in {"LONG", "SHORT"}:
            return self._entry(analysis, setup_side)

        # Evaluate both directions when no upstream directional label exists.
        long_signal = self._entry(analysis, "LONG")
        if long_signal.action != "WAIT":
            return long_signal
        return self._entry(analysis, "SHORT")

    def risk_parameters(self) -> Dict[str, Any]:
        return {
            "min_confidence": self.config.min_entry_confidence,
            "max_positions": 1,
            "intraday_only": True,
            "end_of_day_flatten_minutes": self.config.end_of_day_flatten_minutes,
            "minimum_rr": self.config.minimum_rr,
            "risk_per_trade": "delegated_to_global_risk_engine",
        }
