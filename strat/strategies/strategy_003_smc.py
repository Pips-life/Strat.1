"""Strategy 003: Smart Money Concepts (SMC).

A standalone price-action strategy built around market structure, liquidity
sweeps, displacement/FVGs, order blocks and premium/discount location.

The strategy is intentionally independent of Strategy 001/002 and returns
normalized Signals; global risk/execution controls remain outside it.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .base import Signal, Strategy


@dataclass(frozen=True)
class Strategy003Config:
    pivot_left: int = 2
    pivot_right: int = 2
    min_displacement_atr: float = 1.0
    min_confidence: float = 70.0
    stop_buffer_atr: float = 0.15
    risk_per_trade: float = 0.01


class Strategy003(Strategy):
    id = "strategy_003"
    name = "Smart Money Concepts"
    version = "1.0.0"

    def __init__(self, config: Strategy003Config | None = None) -> None:
        self.config = config or Strategy003Config()

    @staticmethod
    def _bars(market: Any) -> list[dict[str, float]]:
        raw = market.get("bars") or market.get("candles") or [] if isinstance(market, dict) else market
        out: list[dict[str, float]] = []
        for b in raw:
            if not isinstance(b, dict):
                continue
            try:
                o = float(b["open"]); h = float(b["high"]); l = float(b["low"]); c = float(b["close"])
            except (KeyError, TypeError, ValueError):
                continue
            if min(o, h, l, c) <= 0 or h < l:
                continue
            out.append({"open": o, "high": h, "low": l, "close": c})
        return out

    @staticmethod
    def _atr(bars: list[dict[str, float]], period: int = 14) -> float:
        if len(bars) < 2:
            return 0.0
        trs: list[float] = []
        start = max(1, len(bars) - period)
        for i in range(start, len(bars)):
            b, p = bars[i], bars[i - 1]
            trs.append(max(b["high"] - b["low"], abs(b["high"] - p["close"]), abs(b["low"] - p["close"])))
        return sum(trs) / len(trs) if trs else 0.0

    def _pivots(self, bars: list[dict[str, float]]) -> tuple[list[int], list[int]]:
        l = self.config.pivot_left; r = self.config.pivot_right
        highs: list[int] = []; lows: list[int] = []
        for i in range(l, len(bars) - r):
            h = bars[i]["high"]; lo = bars[i]["low"]
            if all(h > bars[j]["high"] for j in range(i-l, i)) and all(h >= bars[j]["high"] for j in range(i+1, i+r+1)):
                highs.append(i)
            if all(lo < bars[j]["low"] for j in range(i-l, i)) and all(lo <= bars[j]["low"] for j in range(i+1, i+r+1)):
                lows.append(i)
        return highs, lows

    def analyze(self, market: Any) -> dict[str, Any]:
        bars = self._bars(market)
        if len(bars) < max(20, self.config.pivot_left + self.config.pivot_right + 5):
            return {"smc_ready": False, "reason": "waiting for sufficient OHLC candles", "sample_count": len(bars)}

        atr = self._atr(bars)
        highs, lows = self._pivots(bars)
        if not highs or not lows or atr <= 0:
            return {"smc_ready": False, "reason": "waiting for confirmed swing structure", "sample_count": len(bars)}

        last = bars[-1]; prev = bars[-2]
        last_high_idx = highs[-1]; last_low_idx = lows[-1]
        swing_high = bars[last_high_idx]["high"]
        swing_low = bars[last_low_idx]["low"]

        bullish_bos = last["close"] > swing_high and last["close"] > prev["high"]
        bearish_bos = last["close"] < swing_low and last["close"] < prev["low"]
        displacement = abs(last["close"] - last["open"]) / atr

        # Liquidity sweep: current bar trades through a recent swing but closes back inside it.
        buy_side_sweep = last["high"] > swing_high and last["close"] < swing_high
        sell_side_sweep = last["low"] < swing_low and last["close"] > swing_low

        # Three-candle FVG (imbalance).
        fvg_bull = bars[-1]["low"] > bars[-3]["high"]
        fvg_bear = bars[-1]["high"] < bars[-3]["low"]
        fvg_bull_zone = (bars[-3]["high"], bars[-1]["low"]) if fvg_bull else None
        fvg_bear_zone = (bars[-1]["high"], bars[-3]["low"]) if fvg_bear else None

        # Last opposite candle before displacement is treated as a simple order block.
        ob_bull = bars[-2]["close"] < bars[-2]["open"] and last["close"] > last["open"] and displacement >= self.config.min_displacement_atr
        ob_bear = bars[-2]["close"] > bars[-2]["open"] and last["close"] < last["open"] and displacement >= self.config.min_displacement_atr
        midpoint = (swing_high + swing_low) / 2.0
        premium_discount = "discount" if last["close"] < midpoint else "premium"

        bull_score = 0; bear_score = 0
        bull_reasons: list[str] = []; bear_reasons: list[str] = []
        if bullish_bos: bull_score += 2; bull_reasons.append("bullish BOS")
        if bearish_bos: bear_score += 2; bear_reasons.append("bearish BOS")
        if sell_side_sweep: bull_score += 2; bull_reasons.append("sell-side liquidity sweep")
        if buy_side_sweep: bear_score += 2; bear_reasons.append("buy-side liquidity sweep")
        if fvg_bull: bull_score += 1; bull_reasons.append("bullish FVG")
        if fvg_bear: bear_score += 1; bear_reasons.append("bearish FVG")
        if ob_bull: bull_score += 1; bull_reasons.append("bullish order block")
        if ob_bear: bear_score += 1; bear_reasons.append("bearish order block")
        if premium_discount == "discount": bull_score += 1; bull_reasons.append("discount")
        if premium_discount == "premium": bear_score += 1; bear_reasons.append("premium")
        if displacement >= self.config.min_displacement_atr:
            if last["close"] > last["open"]: bull_score += 1; bull_reasons.append("bullish displacement")
            if last["close"] < last["open"]: bear_score += 1; bear_reasons.append("bearish displacement")

        direction = "BUY" if bull_score > bear_score and bull_score >= 4 else "SELL" if bear_score > bull_score and bear_score >= 4 else "WAIT"
        confidence = min(98.0, 55.0 + 7.0 * max(bull_score, bear_score)) if direction != "WAIT" else 0.0
        return {
            "smc_ready": True, "direction": direction, "confidence": confidence,
            "price": last["close"], "atr": atr, "swing_high": swing_high, "swing_low": swing_low,
            "bull_score": bull_score, "bear_score": bear_score,
            "bull_reasons": bull_reasons, "bear_reasons": bear_reasons,
            "bullish_bos": bullish_bos, "bearish_bos": bearish_bos,
            "buy_side_sweep": buy_side_sweep, "sell_side_sweep": sell_side_sweep,
            "fvg_bull": fvg_bull, "fvg_bear": fvg_bear,
            "fvg_bull_zone": fvg_bull_zone, "fvg_bear_zone": fvg_bear_zone,
            "premium_discount": premium_discount, "displacement_atr": displacement,
            "order_block": "bullish" if ob_bull else "bearish" if ob_bear else None,
        }

    def generate_signal(self, analysis: dict[str, Any]) -> Signal:
        if not analysis.get("smc_ready") or analysis.get("direction") == "WAIT":
            return Signal(action="WAIT", confidence=0.0, reason=analysis.get("reason", "SMC confluence not aligned"), metadata=analysis)
        side = analysis["direction"]
        entry = float(analysis["price"]); atr = float(analysis["atr"])
        stop = float(analysis["swing_low"] - self.config.stop_buffer_atr * atr) if side == "BUY" else float(analysis["swing_high"] + self.config.stop_buffer_atr * atr)
        reasons = analysis["bull_reasons"] if side == "BUY" else analysis["bear_reasons"]
        return Signal(action=side, confidence=float(analysis["confidence"]), entry=entry, stop_loss=stop,
                      reason="SMC confluence: " + ", ".join(reasons),
                      metadata={**analysis, "strategy": self.id, "entry_mode": "smc-confluence", "risk_per_trade": self.config.risk_per_trade})

    def risk_parameters(self) -> dict[str, Any]:
        return {"risk_per_trade": self.config.risk_per_trade, "stop_model": "recent swing + ATR buffer", "min_confidence": self.config.min_confidence}
