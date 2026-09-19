"""Strategy 005: Woodie 4H Pivot / 5M Price Action.

Standalone strategy:
- 4H Woodie pivots are calculated only from the previous completed 4H candle.
- MetaApi supplies the live stream; the execution layer supplies 5M OHLC built from that stream.
- Entries are 5M rejection/reclaim patterns at S1/S2 for BUY or R1/R2 for SELL.
- Exit/target is the current 4H Woodie PP.
- Stop is beyond the 5M rejection extreme.
- Risk cap is 5% of account balance; sizing is execution/risk-layer responsibility.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .base import Signal, Strategy


@dataclass(frozen=True)
class Strategy005Config:
    pivot_timeframe: str = "4h"
    entry_timeframe: str = "5m"
    risk_cap: float = 0.05
    touch_tolerance_fraction: float = 0.15
    stop_buffer_fraction: float = 0.10
    min_reward_risk: float = 1.0


class Strategy005(Strategy):
    id = "strategy_005"
    name = "Woodie 4H Pivot / 5M Price Action"
    version = "1.0.0"

    def __init__(self, config: Strategy005Config | None = None) -> None:
        self.config = config or Strategy005Config()

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

    @staticmethod
    def woodie(previous_4h: dict[str, float]) -> dict[str, float]:
        h, l, c = previous_4h["high"], previous_4h["low"], previous_4h["close"]
        p = (h + l + 2.0 * c) / 4.0
        return {
            "pp": p,
            "r1": 2.0 * p - l,
            "s1": 2.0 * p - h,
            "r2": p + h - l,
            "s2": p - h + l,
            "r3": h + 2.0 * (p - l),
            "s3": l - 2.0 * (h - p),
        }

    def _series(self, market: Any, key: str) -> list[dict[str, float]]:
        if isinstance(market, dict):
            timeframes = market.get("timeframes")
            if isinstance(timeframes, dict):
                for k in (key, key.upper(), key.lower()):
                    if k in timeframes:
                        return self._bars(timeframes[k])
            for k in (key, key.upper(), key.lower()):
                if k in market:
                    return self._bars(market[k])
        return []

    @staticmethod
    def _rejection(candle: dict[str, float], side: str, level: float, tolerance: float) -> bool:
        o, h, l, c = candle["open"], candle["high"], candle["low"], candle["close"]
        rng = max(h - l, 1e-12)
        if side == "BUY":
            return l <= level + tolerance and c > level and c > o and (min(o, c) - l) / rng >= 0.25
        return h >= level - tolerance and c < level and c < o and (h - max(o, c)) / rng >= 0.25

    def analyze(self, market: Any) -> dict[str, Any]:
        four_h = self._series(market, self.config.pivot_timeframe)
        five_m = self._series(market, self.config.entry_timeframe)
        if len(four_h) < 2 or len(five_m) < 2:
            return {"ready": False, "reason": "waiting for previous closed 4H candle and 5M price action"}

        # The final 4H bar is treated as the currently forming bar; the pivot
        # source is always the immediately preceding completed 4H bar.
        pivot_source = four_h[-2]
        levels = self.woodie(pivot_source)
        candle = five_m[-1]
        prior = five_m[-2]
        price = candle["close"]
        span = max(pivot_source["high"] - pivot_source["low"], 1e-12)
        tolerance = span * self.config.touch_tolerance_fraction

        side = "WAIT"
        trigger = None
        if self._rejection(candle, "BUY", levels["s1"], tolerance):
            side, trigger = "BUY", "S1_REJECTION"
        elif self._rejection(candle, "BUY", levels["s2"], tolerance):
            side, trigger = "BUY", "S2_REJECTION"
        elif self._rejection(candle, "SELL", levels["r1"], tolerance):
            side, trigger = "SELL", "R1_REJECTION"
        elif self._rejection(candle, "SELL", levels["r2"], tolerance):
            side, trigger = "SELL", "R2_REJECTION"

        # Require the prior 5M candle not to have already closed on the
        # target side of PP; this avoids late entries after the move has run.
        if side == "BUY" and prior["close"] >= levels["pp"]:
            side, trigger = "WAIT", None
        if side == "SELL" and prior["close"] <= levels["pp"]:
            side, trigger = "WAIT", None

        stop = None
        if side == "BUY":
            stop = candle["low"] - abs(candle["close"] - candle["low"]) * self.config.stop_buffer_fraction
        elif side == "SELL":
            stop = candle["high"] + abs(candle["high"] - candle["close"]) * self.config.stop_buffer_fraction

        risk = abs(price - stop) if stop is not None else 0.0
        reward = abs(levels["pp"] - price)
        rr = reward / risk if risk > 0 else 0.0

        return {
            "ready": True,
            "side": side,
            "price": price,
            "stop": stop,
            "target": levels["pp"],
            "reward_risk": rr,
            "trigger": trigger,
            "pivot_source": pivot_source,
            "pivots": levels,
            "entry_timeframe": "5m",
            "pivot_timeframe": "4h",
            "risk_cap": self.config.risk_cap,
        }

    def generate_signal(self, analysis: dict[str, Any]) -> Signal:
        if not analysis.get("ready"):
            return Signal(action="WAIT", reason=analysis.get("reason", "Strategy 005 waiting"), metadata=analysis)

        side = analysis.get("side", "WAIT")
        if side not in {"BUY", "SELL"}:
            return Signal(action="WAIT", reason="No confirmed 5M rejection at a Woodie support/resistance level.", metadata=analysis)

        rr = float(analysis.get("reward_risk", 0.0))
        if rr < self.config.min_reward_risk:
            return Signal(action="WAIT", confidence=0.0, reason="Woodie PP target does not provide sufficient room for the 5M setup.", metadata={**analysis, "entry_block": "INSUFFICIENT_REWARD_RISK"})

        return Signal(
            action=side,
            confidence=90.0,
            entry=float(analysis["price"]),
            stop_loss=float(analysis["stop"]),
            take_profit=float(analysis["target"]),
            reason=f"Woodie 4H {analysis['trigger']} confirmed by 5M price action; exit at 4H PP.",
            metadata={**analysis, "strategy": self.id, "entry_model": "5M rejection at S1/S2 or R1/R2", "exit_model": "4H Woodie PP", "risk_model": "maximum 5% account balance"},
        )

    def risk_parameters(self) -> dict[str, Any]:
        return {
            "risk_cap": self.config.risk_cap,
            "risk_basis": "account balance",
            "pivot_formula": "P=(H+L+2C)/4",
            "pivot_source": "previous completed 4H candle",
            "entry_timeframe": "5m",
            "entry_model": "5M rejection/reclaim at S1/S2 or R1/R2",
            "exit_model": "current 4H Woodie PP",
            "independent": True,
            "feed": "MetaApi stream",
        }
