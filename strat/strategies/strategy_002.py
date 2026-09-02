"""Strategy 002: Velocity Expansion.

Detects directional price movement from the live tick stream and enters
immediately. The strategy does not own broker execution; it emits an entry
plus the parameters needed by the execution controller to maintain the
100-pip opposite stop loop.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import floor
from typing import Any

from .base import Signal, Strategy


@dataclass(frozen=True)
class Strategy002Config:
    pip_size: float = 0.01
    trail_pips: float = 100.0
    # Strategy 002 is an execution-first velocity strategy. A live tick with
    # any non-zero directional movement is enough to establish velocity.
    baseline_window: int = 1
    min_velocity_ratio: float = 1.0
    min_acceleration_ratio: float = 1.0
    min_velocity: float = 0.0
    risk_per_trade: float = 0.01
    quantity_step: float = 0.01
    min_quantity: float = 0.01
    min_confidence: float = 70.0

    @property
    def trail_distance(self) -> float:
        return self.pip_size * self.trail_pips


class Strategy002(Strategy):
    id = "strategy_002"
    name = "Velocity Expansion"
    version = "1.1.0"

    def __init__(self, config: Strategy002Config | None = None) -> None:
        self.config = config or Strategy002Config()

    @staticmethod
    def _samples(market: Any) -> list[tuple[float, float]]:
        """Return [(timestamp_seconds, price), ...] in chronological order."""
        if isinstance(market, dict):
            raw = market.get("ticks") or market.get("prices") or market.get("samples") or []
        else:
            raw = market
        result: list[tuple[float, float]] = []
        for item in raw:
            if isinstance(item, dict):
                ts = item.get("timestamp", item.get("time"))
                price = item.get("price", item.get("close"))
            else:
                ts, price = item
            if ts is None or price is None:
                continue
            if hasattr(ts, "timestamp"):
                ts = ts.timestamp()
            result.append((float(ts), float(price)))
        return result

    def analyze(self, market: Any) -> dict[str, Any]:
        samples = self._samples(market)
        if len(samples) < 2:
            return {"velocity_expanding": False, "reason": "waiting for first price movement", "sample_count": len(samples)}

        # Use the live tick-to-tick velocity. There is deliberately no 20-tick
        # warm-up and no large expansion multiplier: the first non-zero move
        # is actionable. This is what makes Strategy 002 execution-sensitive.
        t0, p0 = samples[-2]
        t1, p1 = samples[-1]
        dt = t1 - t0
        current = (p1 - p0) / dt if dt > 0 else 0.0
        previous = 0.0
        if len(samples) >= 3:
            tp, pp = samples[-3]
            previous_dt = t0 - tp
            previous = (p0 - pp) / previous_dt if previous_dt > 0 else 0.0

        current_abs = abs(current)
        previous_abs = abs(previous)
        baseline = max(previous_abs, 1e-12)
        expansion_ratio = current_abs / baseline if previous_abs > 0 else float("inf") if current_abs > 0 else 0.0
        acceleration_ratio = current_abs / baseline if previous_abs > 0 else float("inf") if current_abs > 0 else 0.0
        expanding = current_abs > self.config.min_velocity and current_abs > 0
        direction = "BUY" if current > 0 else "SELL" if current < 0 else "WAIT"
        # A non-zero live directional tick is intentionally high-confidence;
        # the global risk engine still enforces its position/exposure limits.
        confidence = 100.0 if direction in {"BUY", "SELL"} else 0.0
        return {
            "velocity_expanding": expanding,
            "direction": direction,
            "velocity": current,
            "previous_velocity": previous,
            "baseline_velocity": previous_abs,
            "expansion_ratio": expansion_ratio,
            "acceleration_ratio": acceleration_ratio,
            "confidence": confidence,
            "price": p1,
            "timestamp": t1,
            "sample_count": len(samples),
            "movement_detected": current_abs > 0,
        }

    def generate_signal(self, analysis: dict[str, Any]) -> Signal:
        if not analysis.get("velocity_expanding"):
            return Signal(action="WAIT", reason=analysis.get("reason", "no directional movement"), metadata=analysis)

        side = analysis["direction"]
        entry = float(analysis["price"])
        distance = self.config.trail_distance
        opposite_stop = entry - distance if side == "BUY" else entry + distance
        confidence = float(analysis.get("confidence", 0.0))
        return Signal(
            action=side,
            confidence=confidence,
            entry=entry,
            stop_loss=opposite_stop,
            reason="live tick movement detected; immediate directional entry",
            metadata={
                **analysis,
                "strategy": self.id,
                "trail_pips": self.config.trail_pips,
                "trail_distance": distance,
                "opposite_stop_side": "SELL" if side == "BUY" else "BUY",
                "loop": "on opposite stop trigger: close source position, promote stop to running position, place new opposite stop",
            },
        )

    def calculate_quantity(self, *, balance: float, entry: float, tick_size: float, tick_value: float) -> float:
        """Size from account balance using the configured 100-pip reversal distance."""
        if balance <= 0 or entry <= 0 or tick_size <= 0 or tick_value <= 0:
            return 0.0
        risk_budget = balance * self.config.risk_per_trade
        ticks_at_risk = self.config.trail_distance / tick_size
        risk_per_lot = ticks_at_risk * tick_value
        if risk_per_lot <= 0:
            return 0.0
        raw = risk_budget / risk_per_lot
        qty = floor(raw / self.config.quantity_step) * self.config.quantity_step
        return round(qty, 10) if qty >= self.config.min_quantity else 0.0

    def risk_parameters(self) -> dict[str, Any]:
        return {
            "risk_per_trade": self.config.risk_per_trade,
            "trail_pips": self.config.trail_pips,
            "pip_size": self.config.pip_size,
            "trail_distance": self.config.trail_distance,
            "sizing": "balance * risk_per_trade / (trail_distance / tick_size * tick_value)",
        }
