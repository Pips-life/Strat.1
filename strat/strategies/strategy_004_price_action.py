"""Strategy 004: Pure Price Action.

Standalone multi-timeframe price-action strategy.

Flow:
15M context -> 5M liquidity/setup -> 1M confirmation -> execution.

The strategy uses only OHLC candles and optional candle timestamps. It has no
dependency on Strategies 001-003, indicators, volume, options flow, or feeds.
"""
from __future__ import annotations

from dataclasses import dataclass
from statistics import median
from typing import Any

from .base import Signal, Strategy


@dataclass(frozen=True)
class Strategy004Config:
    context_timeframe: str = "15m"
    setup_timeframe: str = "5m"
    execution_timeframe: str = "1m"
    pivot_left: int = 2
    pivot_right: int = 2
    context_lookback: int = 20
    setup_lookback: int = 30
    execution_lookback: int = 20
    range_lookback: int = 8
    displacement_multiple: float = 1.35
    sweep_tolerance_fraction: float = 0.10
    stop_buffer_fraction: float = 0.10
    min_reward_risk: float = 1.35
    risk_per_trade: float = 0.01


class Strategy004(Strategy):
    id = "strategy_004"
    name = "Pure Price Action"
    version = "2.0.0"

    def __init__(self, config: Strategy004Config | None = None) -> None:
        self.config = config or Strategy004Config()

    @staticmethod
    def _bars(raw: Any) -> list[dict[str, float]]:
        if not isinstance(raw, list):
            return []
        out: list[dict[str, float]] = []
        for b in raw:
            if not isinstance(b, dict):
                continue
            try:
                o, h, l, c = (float(b[k]) for k in ("open", "high", "low", "close"))
            except (KeyError, TypeError, ValueError):
                continue
            if min(o, h, l, c) <= 0 or h < l:
                continue
            out.append({"open": o, "high": h, "low": l, "close": c})
        return out

    @classmethod
    def _series(cls, market: Any, timeframe: str) -> list[dict[str, float]]:
        if isinstance(market, dict):
            m = market.get("timeframes")
            if isinstance(m, dict):
                for key in (timeframe, timeframe.upper(), timeframe.lower()):
                    if key in m:
                        return cls._bars(m[key])
            for key in (timeframe, timeframe.upper(), timeframe.lower()):
                if key in market:
                    return cls._bars(market[key])
            # Backward-compatible single-series input is treated as execution data.
            if timeframe == "1m":
                return cls._bars(market.get("bars") or market.get("candles") or [])
            return []
        return cls._bars(market) if timeframe == "1m" else []

    def _pivots(self, bars: list[dict[str, float]]) -> tuple[list[int], list[int]]:
        left, right = self.config.pivot_left, self.config.pivot_right
        highs, lows = [], []
        for i in range(left, len(bars) - right):
            h, lo = bars[i]["high"], bars[i]["low"]
            if all(h > bars[j]["high"] for j in range(i - left, i)) and all(
                h >= bars[j]["high"] for j in range(i + 1, i + right + 1)
            ):
                highs.append(i)
            if all(lo < bars[j]["low"] for j in range(i - left, i)) and all(
                lo <= bars[j]["low"] for j in range(i + 1, i + right + 1)
            ):
                lows.append(i)
        return highs, lows

    @staticmethod
    def _structure(bars: list[dict[str, float]], highs: list[int], lows: list[int]) -> str:
        if len(highs) < 2 or len(lows) < 2:
            return "NEUTRAL"
        h1, h2 = bars[highs[-2]]["high"], bars[highs[-1]]["high"]
        l1, l2 = bars[lows[-2]]["low"], bars[lows[-1]]["low"]
        if h2 > h1 and l2 > l1:
            return "BULLISH"
        if h2 < h1 and l2 < l1:
            return "BEARISH"
        return "NEUTRAL"

    def _context(self, bars: list[dict[str, float]]) -> dict[str, Any]:
        highs, lows = self._pivots(bars)
        structure = self._structure(bars, highs, lows)
        return {
            "structure": structure,
            "highs": highs,
            "lows": lows,
            "swing_high": bars[highs[-1]]["high"] if highs else None,
            "swing_low": bars[lows[-1]]["low"] if lows else None,
        }

    def _recent_range(self, bars: list[dict[str, float]]) -> float:
        sample = bars[-self.config.range_lookback - 1 : -1]
        values = [b["high"] - b["low"] for b in sample if b["high"] > b["low"]]
        return median(values) if values else 0.0

    def _setup(self, bars: list[dict[str, float]], context: dict[str, Any]) -> dict[str, Any]:
        if len(bars) < 5:
            return {"state": "NO_SETUP"}

        highs, lows = self._pivots(bars)
        start = max(0, len(bars) - self.config.setup_lookback)
        typical = self._recent_range(bars)
        candidates: list[dict[str, Any]] = []

        for i in range(start, len(bars)):
            prior_highs = [p for p in highs if p < i]
            prior_lows = [p for p in lows if p < i]
            if not prior_highs or not prior_lows:
                continue
            liq_high = bars[prior_highs[-1]]["high"]
            liq_low = bars[prior_lows[-1]]["low"]
            tolerance = max(liq_high - liq_low, 1e-9) * self.config.sweep_tolerance_fraction

            # BUY setup = sell-side liquidity swept.
            if (
                context["structure"] == "BULLISH"
                and bars[i]["low"] < liq_low
                and bars[i]["close"] > liq_low
                and liq_low - bars[i]["low"] <= tolerance
            ):
                candidates.append({
                    "side": "BUY", "sweep_index": i, "level": liq_low,
                    "sweep_extreme": bars[i]["low"], "broken_level": liq_high,
                })

            # SELL setup = buy-side liquidity swept.
            if (
                context["structure"] == "BEARISH"
                and bars[i]["high"] > liq_high
                and bars[i]["close"] < liq_high
                and bars[i]["high"] - liq_high <= tolerance
            ):
                candidates.append({
                    "side": "SELL", "sweep_index": i, "level": liq_high,
                    "sweep_extreme": bars[i]["high"], "broken_level": liq_low,
                })

        if not candidates:
            return {"state": "NO_SETUP"}

        for candidate in reversed(candidates):
            rejection_i = None
            displacement_i = None
            for j in range(candidate["sweep_index"] + 1, len(bars)):
                b = bars[j]
                if candidate["side"] == "BUY":
                    rejection = b["close"] > b["open"] and b["close"] > candidate["level"]
                else:
                    rejection = b["close"] < b["open"] and b["close"] < candidate["level"]
                if rejection and rejection_i is None:
                    rejection_i = j
                if rejection_i is None:
                    continue
                displacement_ok = typical > 0 and (b["high"] - b["low"]) >= typical * self.config.displacement_multiple
                if not displacement_ok:
                    continue
                displacement_i = j
                break

            if rejection_i is not None and displacement_i is not None:
                return {
                    **candidate,
                    "state": "DISPLACED",
                    "rejection_index": rejection_i,
                    "displacement_index": displacement_i,
                }

            if candidate["sweep_index"] == len(bars) - 1:
                return {**candidate, "state": "SWEPT"}

        return {"state": "NO_SETUP"}

    def _execution(self, bars: list[dict[str, float]], setup: dict[str, Any]) -> dict[str, Any]:
        if setup.get("state") != "DISPLACED" or len(bars) < 5:
            return {"state": "WAIT"}

        side = setup["side"]
        broken_level = float(setup["broken_level"])
        highs, lows = self._pivots(bars)
        start = max(0, len(bars) - self.config.execution_lookback)
        if side == "BUY":
            levels = [bars[i]["high"] for i in highs if i >= start]
            bos_level = max(levels) if levels else broken_level
            confirmed = bars[-1]["close"] > bos_level
        else:
            levels = [bars[i]["low"] for i in lows if i >= start]
            bos_level = min(levels) if levels else broken_level
            confirmed = bars[-1]["close"] < bos_level

        return {
            "state": "BOS_CONFIRMED" if confirmed else "WAIT",
            "side": side,
            "bos_level": bos_level,
        }

    @staticmethod
    @staticmethod
    def _next_opposing_liquidity(
        bars: list[dict[str, float]], side: str, entry: float, pivots: tuple[list[int], list[int]]
    ) -> float | None:
        highs, lows = pivots
        if side == "BUY":
            targets = sorted({bars[i]["high"] for i in highs if bars[i]["high"] > entry})
            return targets[0] if targets else None
        targets = sorted({bars[i]["low"] for i in lows if bars[i]["low"] < entry}, reverse=True)
        return targets[0] if targets else None

    def analyze(self, market: Any) -> dict[str, Any]:
        context_bars = self._series(market, self.config.context_timeframe)
        setup_bars = self._series(market, self.config.setup_timeframe)
        execution_bars = self._series(market, self.config.execution_timeframe)

        minimum = self.config.pivot_left + self.config.pivot_right + 5
        if len(context_bars) < minimum or len(setup_bars) < minimum or len(execution_bars) < minimum:
            return {
                "price_action_ready": False,
                "reason": "waiting for 15m, 5m and 1m OHLC candles",
                "timeframes": {
                    "context": self.config.context_timeframe,
                    "setup": self.config.setup_timeframe,
                    "execution": self.config.execution_timeframe,
                },
                "sample_counts": {
                    "15m": len(context_bars), "5m": len(setup_bars), "1m": len(execution_bars)
                },
            }

        context = self._context(context_bars)
        setup = self._setup(setup_bars, context)
        execution = self._execution(execution_bars, setup)
        setup_pivots = self._pivots(setup_bars)
        target = self._next_opposing_liquidity(setup_bars, setup.get("side", ""), execution_bars[-1]["close"], setup_pivots) if setup.get("side") else None

        direction = "WAIT"
        if execution.get("state") == "BOS_CONFIRMED":
            direction = execution["side"]

        return {
            "price_action_ready": True,
            "direction": direction,
            "confidence": 92.0 if direction != "WAIT" else 0.0,
            "price": execution_bars[-1]["close"],
            "context_timeframe": self.config.context_timeframe,
            "setup_timeframe": self.config.setup_timeframe,
            "execution_timeframe": self.config.execution_timeframe,
            "context_structure": context["structure"],
            "context_swing_high": context["swing_high"],
            "context_swing_low": context["swing_low"],
            "setup_state": setup.get("state", "NO_SETUP"),
            "setup": setup,
            "execution_state": execution.get("state", "WAIT"),
            "bos_level": execution.get("bos_level"),
            "target_liquidity": target,
            "sweep_extreme": setup.get("sweep_extreme"),
            "sweep_level": setup.get("level"),
            "rejection_index": setup.get("rejection_index"),
            "displacement_index": setup.get("displacement_index"),
            "sample_counts": {"15m": len(context_bars), "5m": len(setup_bars), "1m": len(execution_bars)},
            "reasons": (
                ["15m bullish structure", "5m sell-side liquidity sweep",
                 "5m rejection", "5m displacement", "1m bullish BOS"]
                if direction == "BUY" else
                ["15m bearish structure", "5m buy-side liquidity sweep",
                 "5m rejection", "5m displacement", "1m bearish BOS"]
                if direction == "SELL" else []
            ),
        }

    def generate_signal(self, analysis: dict[str, Any]) -> Signal:
        if not analysis.get("price_action_ready"):
            return Signal(action="WAIT", reason=analysis.get("reason", "MTF price-action engine not ready"), metadata=analysis)

        if analysis.get("position"):
            return self._manage_position(analysis)

        side = str(analysis.get("direction", "WAIT")).upper()
        if side not in {"BUY", "SELL"}:
            return Signal(
                action="WAIT", confidence=0.0,
                reason="15m context, 5m setup and 1m confirmation are not fully aligned.",
                metadata=analysis,
            )

        entry = float(analysis["price"])
        sweep = analysis.get("sweep_extreme")
        if sweep is None:
            return Signal(action="WAIT", reason="No confirmed liquidity-sweep extreme for the entry.", metadata=analysis)

        target = analysis.get("target_liquidity")
        if target is None:
            return Signal(action="WAIT", confidence=float(analysis["confidence"]),
                          reason="No confirmed opposing 5m liquidity target beyond entry.",
                          metadata={**analysis, "entry_block": "NO_OPPOSING_LIQUIDITY"})

        risk = abs(entry - (float(sweep) - abs(entry - float(sweep)) * self.config.stop_buffer_fraction if side == "BUY" else float(sweep) + abs(entry - float(sweep)) * self.config.stop_buffer_fraction))
        reward = abs(float(target) - entry)
        if risk <= 0 or reward / risk < self.config.min_reward_risk:
            return Signal(action="WAIT", confidence=float(analysis["confidence"]),
                          reason="No valid opposing-liquidity target with sufficient reward/risk.",
                          metadata={**analysis, "entry_block": "INSUFFICIENT_REWARD_RISK", "reward_risk": reward / risk if risk else 0.0})

        stop = float(sweep) - abs(entry - float(sweep)) * self.config.stop_buffer_fraction if side == "BUY" else float(sweep) + abs(entry - float(sweep)) * self.config.stop_buffer_fraction
        return Signal(
            action=side, confidence=float(analysis["confidence"]), entry=entry,
            stop_loss=stop, take_profit=float(target),
            reason="Pure price action MTF: " + ", ".join(analysis["reasons"]),
            metadata={**analysis, "strategy": self.id, "entry_mode": "15m-context-5m-sweep-1m-bos",
                      "stop_model": "5m sweep extreme plus buffer", "target_model": "next opposing liquidity",
                      "reward_risk": reward / risk, "indicator_free": True},
        )

    def _manage_position(self, analysis: dict[str, Any]) -> Signal:
        position = analysis.get("position") or {}
        side = str(position.get("side", "")).upper()
        price = float(analysis["price"])
        if side not in {"BUY", "SELL"}:
            return Signal(action="WAIT", reason="Unknown position state; new entries blocked.")
        stop = position.get("stop_loss")
        target = position.get("take_profit")
        if stop is not None and ((side == "BUY" and price <= float(stop)) or (side == "SELL" and price >= float(stop))):
            return Signal(action="CLOSE", confidence=100.0, reason=f"{side} price-action stop reached.",
                          metadata={"exit_type": "STOP", "strategy": self.id})
        if target is not None and ((side == "BUY" and price >= float(target)) or (side == "SELL" and price <= float(target))):
            return Signal(action="CLOSE", confidence=100.0, reason=f"{side} opposing liquidity target reached.",
                          metadata={"exit_type": "TARGET", "strategy": self.id})
        if side == "BUY" and analysis.get("context_structure") == "BEARISH":
            return Signal(action="CLOSE", confidence=96.0, reason="15m context reversed bearish; long thesis invalidated.",
                          metadata={"exit_type": "OPPOSITE_CONTEXT", "strategy": self.id})
        if side == "SELL" and analysis.get("context_structure") == "BULLISH":
            return Signal(action="CLOSE", confidence=96.0, reason="15m context reversed bullish; short thesis invalidated.",
                          metadata={"exit_type": "OPPOSITE_CONTEXT", "strategy": self.id})
        return Signal(action="WAIT", reason="Position remains valid; no price-action exit condition.",
                      metadata={"manage": True, "strategy": self.id})

    def risk_parameters(self) -> dict[str, Any]:
        return {
            "risk_per_trade": self.config.risk_per_trade,
            "min_reward_risk": self.config.min_reward_risk,
            "timeframes": "15m context -> 5m setup -> 1m execution",
            "entry_model": "15m structure + 5m liquidity sweep/rejection/displacement + 1m BOS",
            "stop_model": "5m sweep extreme plus buffer",
            "target_model": "next opposing confirmed liquidity",
            "indicator_free": True,
        }
