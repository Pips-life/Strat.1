"""Strategy 004: Pure Price Action.

Standalone price-action strategy. It uses only OHLC price history:
confirmed swing structure, liquidity pools, liquidity sweeps, rejection,
displacement and break-of-structure confirmation.

It has no dependency on Strategies 001-003 and does not use indicators,
options flow, volume, volatility feeds, or external signals.
"""
from __future__ import annotations

from dataclasses import dataclass
from statistics import median
from typing import Any

from .base import Signal, Strategy
from .registry import registry


@dataclass(frozen=True)
class Strategy004Config:
    pivot_left: int = 2
    pivot_right: int = 2
    structure_lookback: int = 8
    setup_lookback: int = 12
    range_lookback: int = 8
    displacement_multiple: float = 1.35
    sweep_tolerance_fraction: float = 0.10
    stop_buffer_fraction: float = 0.10
    min_reward_risk: float = 1.35
    min_confidence: float = 70.0
    risk_per_trade: float = 0.01


class Strategy004(Strategy):
    id = "strategy_004"
    name = "Pure Price Action"
    version = "1.1.0"

    def __init__(self, config: Strategy004Config | None = None) -> None:
        self.config = config or Strategy004Config()

    @staticmethod
    def _bars(market: Any) -> list[dict[str, float]]:
        raw = (
            market.get("bars") or market.get("candles") or []
            if isinstance(market, dict)
            else market
        )
        out: list[dict[str, float]] = []
        for b in raw:
            if not isinstance(b, dict):
                continue
            try:
                o = float(b["open"])
                h = float(b["high"])
                l = float(b["low"])
                c = float(b["close"])
            except (KeyError, TypeError, ValueError):
                continue
            if min(o, h, l, c) <= 0 or h < l:
                continue
            out.append({"open": o, "high": h, "low": l, "close": c})
        return out

    def _pivots(self, bars: list[dict[str, float]]) -> tuple[list[int], list[int]]:
        left = self.config.pivot_left
        right = self.config.pivot_right
        highs: list[int] = []
        lows: list[int] = []
        for i in range(left, len(bars) - right):
            h = bars[i]["high"]
            lo = bars[i]["low"]
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
    def _range(b: dict[str, float]) -> float:
        return max(0.0, b["high"] - b["low"])

    def _recent_range(self, bars: list[dict[str, float]]) -> float:
        sample = bars[-self.config.range_lookback - 1 : -1]
        ranges = [self._range(b) for b in sample if self._range(b) > 0]
        return median(ranges) if ranges else 0.0

    def _structure(self, bars: list[dict[str, float]], highs: list[int], lows: list[int]) -> str:
        if len(highs) < 2 or len(lows) < 2:
            return "NEUTRAL"
        h1, h2 = bars[highs[-2]]["high"], bars[highs[-1]]["high"]
        l1, l2 = bars[lows[-2]]["low"], bars[lows[-1]]["low"]
        if h2 > h1 and l2 > l1:
            return "BULLISH"
        if h2 < h1 and l2 < l1:
            return "BEARISH"
        return "NEUTRAL"

    def analyze(self, market: Any) -> dict[str, Any]:
        bars = self._bars(market)
        minimum = max(
            20,
            self.config.pivot_left + self.config.pivot_right + self.config.range_lookback + 5,
        )
        if len(bars) < minimum:
            return {
                "price_action_ready": False,
                "reason": "waiting for sufficient OHLC candles",
                "sample_count": len(bars),
            }

        highs, lows = self._pivots(bars)
        if len(highs) < 2 or len(lows) < 2:
            return {
                "price_action_ready": False,
                "reason": "waiting for confirmed swing structure",
                "sample_count": len(bars),
            }

        last = bars[-1]
        swing_high = bars[highs[-1]]["high"]
        prior_swing_high = bars[highs[-2]]["high"]
        swing_low = bars[lows[-1]]["low"]
        prior_swing_low = bars[lows[-2]]["low"]

        # Evaluate chronologically: sweep -> rejection -> displacement -> BOS.
        typical_range = self._recent_range(bars)
        search_start = max(0, len(bars) - self.config.setup_lookback)
        candidates: list[dict[str, Any]] = []
        for i in range(search_start, len(bars)):
            b = bars[i]
            prior_highs = [p for p in highs if p < i]
            prior_lows = [p for p in lows if p < i]
            if not prior_highs or not prior_lows:
                continue
            liq_high = bars[prior_highs[-1]]["high"]
            liq_low = bars[prior_lows[-1]]["low"]
            tolerance = max(liq_high - liq_low, 1e-9) * self.config.sweep_tolerance_fraction
            if b["low"] < liq_low and b["close"] > liq_low and liq_low - b["low"] <= tolerance:
                candidates.append({"side": "BUY", "sweep_index": i, "level": liq_low, "bos_level": liq_high, "sweep_extreme": b["low"]})
            if b["high"] > liq_high and b["close"] < liq_high and b["high"] - liq_high <= tolerance:
                candidates.append({"side": "SELL", "sweep_index": i, "level": liq_high, "bos_level": liq_low, "sweep_extreme": b["high"]})

        setup: dict[str, Any] | None = None
        for candidate in reversed(candidates):
            rejection_i: int | None = None
            bos_i: int | None = None
            displacement_i: int | None = None
            for j in range(candidate["sweep_index"] + 1, len(bars)):
                b = bars[j]
                rejection = (b["close"] > b["open"] and b["close"] > candidate["level"]) if candidate["side"] == "BUY" else (b["close"] < b["open"] and b["close"] < candidate["level"])
                if rejection and rejection_i is None:
                    rejection_i = j
                if rejection_i is None:
                    continue
                displacement_ok = typical_range > 0 and self._range(b) >= typical_range * self.config.displacement_multiple
                if not displacement_ok:
                    continue
                if candidate["side"] == "BUY" and b["close"] > candidate["bos_level"]:
                    displacement_i, bos_i = j, j
                    break
                if candidate["side"] == "SELL" and b["close"] < candidate["bos_level"]:
                    displacement_i, bos_i = j, j
                    break
            if bos_i is not None:
                setup = {**candidate, "rejection_index": rejection_i, "displacement_index": displacement_i, "bos_index": bos_i}
                break
            if candidate["sweep_index"] == len(bars) - 1:
                setup = candidate
                break

        bullish_bos = bool(setup and setup["side"] == "BUY" and setup.get("bos_index") == len(bars) - 1)
        bearish_bos = bool(setup and setup["side"] == "SELL" and setup.get("bos_index") == len(bars) - 1)
        bullish_pending = bool(setup and setup["side"] == "BUY" and setup.get("bos_index") is None)
        bearish_pending = bool(setup and setup["side"] == "SELL" and setup.get("bos_index") is None)
        if bullish_bos:
            direction, confidence = "BUY", 92.0
            reasons = ["sell-side liquidity sweep", "bullish rejection", "bullish displacement", "bullish break of structure"]
        elif bearish_bos:
            direction, confidence = "SELL", 92.0
            reasons = ["buy-side liquidity sweep", "bearish rejection", "bearish displacement", "bearish break of structure"]
        else:
            direction, confidence, reasons = "WAIT", 0.0, []

        buy_side_sweep = bool(setup and setup["side"] == "SELL" and setup["sweep_index"] == len(bars) - 1)
        sell_side_sweep = bool(setup and setup["side"] == "BUY" and setup["sweep_index"] == len(bars) - 1)
        bullish_rejection = bool(setup and setup["side"] == "BUY" and setup.get("rejection_index") == len(bars) - 1)
        bearish_rejection = bool(setup and setup["side"] == "SELL" and setup.get("rejection_index") == len(bars) - 1)
        displacement = self._range(last)
        return {
            "price_action_ready": True,
            "direction": direction,
            "confidence": confidence,
            "price": last["close"],
            "structure": self._structure(bars, highs, lows),
            "swing_high": swing_high,
            "prior_swing_high": prior_swing_high,
            "swing_low": swing_low,
            "prior_swing_low": prior_swing_low,
            "buy_side_liquidity": swing_high,
            "sell_side_liquidity": swing_low,
            "buy_side_sweep": buy_side_sweep,
            "sell_side_sweep": sell_side_sweep,
            "bullish_rejection": bullish_rejection,
            "bearish_rejection": bearish_rejection,
            "bullish_bos": bullish_bos,
            "bearish_bos": bearish_bos,
            "bullish_pending": bullish_pending,
            "bearish_pending": bearish_pending,
            "displacement_range": displacement,
            "reference_range": typical_range,
            "displacement_multiple": (
                displacement / typical_range if typical_range > 0 else 0.0
            ),
            "reasons": reasons,
            "setup_state": ("BOS_CONFIRMED" if bullish_bos or bearish_bos else "SETUP_PENDING" if bullish_pending or bearish_pending else "NO_SETUP"),
            "setup": setup,
            "sweep_extreme": setup.get("sweep_extreme") if setup else None,
            "sweep_level": setup.get("level") if setup else None,
            "bos_level": setup.get("bos_level") if setup else None,
            "rejection_index": setup.get("rejection_index") if setup else None,
            "displacement_index": setup.get("displacement_index") if setup else None,
            "bos_index": setup.get("bos_index") if setup else None,
            "sample_count": len(bars),
        }

    def _target(self, analysis: dict[str, Any], side: str) -> float | None:
        price = float(analysis["price"])
        if side == "BUY":
            target = float(analysis.get("bos_level") or analysis["prior_swing_high"])
            if target <= price:
                return None
            return target
        target = float(analysis.get("bos_level") or analysis["prior_swing_low"])
        if target >= price:
            return None
        return target

    def generate_signal(self, analysis: dict[str, Any]) -> Signal:
        if not analysis.get("price_action_ready"):
            return Signal(
                action="WAIT",
                reason=analysis.get("reason", "price-action engine not ready"),
                metadata=analysis,
            )

        if analysis.get("position"):
            return self._manage_position(analysis)

        side = str(analysis.get("direction", "WAIT")).upper()
        if side not in {"BUY", "SELL"}:
            return Signal(
                action="WAIT",
                confidence=0.0,
                reason=(
                    "Price-action setup is incomplete: "
                    "a liquidity sweep, rejection, displacement and structure break "
                    "must align before entry."
                ),
                metadata={
                    **analysis,
                    "setup_state": (
                        "BULLISH_PENDING"
                        if analysis.get("bullish_pending")
                        else "BEARISH_PENDING"
                        if analysis.get("bearish_pending")
                        else "NO_SETUP"
                    ),
                },
            )

        entry = float(analysis["price"])
        swing_high = float(analysis["swing_high"])
        swing_low = float(analysis["swing_low"])
        structure_range = max(swing_high - swing_low, 1e-9)
        buffer = structure_range * self.config.stop_buffer_fraction

        if side == "BUY":
            stop_base = float(analysis.get("sweep_extreme") or swing_low)
            stop = stop_base - buffer
        else:
            stop_base = float(analysis.get("sweep_extreme") or swing_high)
            stop = stop_base + buffer

        target = self._target(analysis, side)
        if target is None:
            return Signal(
                action="WAIT",
                confidence=float(analysis["confidence"]),
                reason="No valid opposing swing target exists beyond the entry.",
                metadata={**analysis, "entry_block": "NO_OPPOSING_LIQUIDITY"},
            )

        risk = abs(entry - stop)
        reward = abs(target - entry)
        rr = reward / risk if risk > 0 else 0.0
        if rr < self.config.min_reward_risk:
            return Signal(
                action="WAIT",
                confidence=float(analysis["confidence"]),
                reason=f"Price-action target only provides {rr:.2f}R; minimum is {self.config.min_reward_risk:.2f}R.",
                metadata={
                    **analysis,
                    "entry_block": "INSUFFICIENT_REWARD_RISK",
                    "reward_risk": rr,
                },
            )

        return Signal(
            action=side,
            confidence=float(analysis["confidence"]),
            entry=entry,
            stop_loss=stop,
            take_profit=target,
            reason="Pure price action: " + ", ".join(analysis["reasons"]) + f" ({rr:.2f}R).",
            metadata={
                **analysis,
                "strategy": self.id,
                "entry_mode": "liquidity-sweep-displacement-bos",
                "reward_risk": rr,
                "stop_model": "sweep extreme plus price-range buffer",
                "target_model": "previous opposing confirmed swing/liquidity",
                "indicator_free": True,
            },
        )

    def _manage_position(self, analysis: dict[str, Any]) -> Signal:
        position = analysis.get("position") or {}
        side = str(position.get("side", "")).upper()
        price = float(analysis["price"])

        if side not in {"BUY", "SELL"}:
            return Signal(
                action="WAIT",
                confidence=0.0,
                reason="Unknown position state; new price-action entries blocked.",
            )

        stop = position.get("stop_loss")
        target = position.get("take_profit")
        if stop is not None and (
            (side == "BUY" and price <= float(stop))
            or (side == "SELL" and price >= float(stop))
        ):
            return Signal(
                action="CLOSE",
                confidence=100.0,
                reason=f"{side} price-action stop reached.",
                metadata={"exit_type": "STOP", "strategy": self.id},
            )

        if target is not None and (
            (side == "BUY" and price >= float(target))
            or (side == "SELL" and price <= float(target))
        ):
            return Signal(
                action="CLOSE",
                confidence=100.0,
                reason=f"{side} opposing liquidity target reached.",
                metadata={"exit_type": "TARGET", "strategy": self.id},
            )

        # Once in a trade, a confirmed opposite liquidity sweep + displacement
        # is treated as invalidation of the original price-action thesis.
        if side == "BUY" and analysis.get("bearish_bos"):
            return Signal(
                action="CLOSE",
                confidence=96.0,
                reason="Confirmed bearish price-action reversal invalidated the long thesis.",
                metadata={"exit_type": "OPPOSITE_STRUCTURE", "strategy": self.id},
            )
        if side == "SELL" and analysis.get("bullish_bos"):
            return Signal(
                action="CLOSE",
                confidence=96.0,
                reason="Confirmed bullish price-action reversal invalidated the short thesis.",
                metadata={"exit_type": "OPPOSITE_STRUCTURE", "strategy": self.id},
            )

        return Signal(
            action="WAIT",
            confidence=0.0,
            reason="Position remains valid; no price-action exit condition.",
            metadata={"manage": True, "strategy": self.id},
        )

    def risk_parameters(self) -> dict[str, Any]:
        return {
            "risk_per_trade": self.config.risk_per_trade,
            "min_reward_risk": self.config.min_reward_risk,
            "stop_model": "confirmed swing + price-range buffer",
            "target_model": "opposing confirmed swing",
            "entry_model": "liquidity sweep + rejection + displacement + BOS",
            "indicator_free": True,
        }
